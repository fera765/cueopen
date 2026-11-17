#!/usr/bin/env python3
"""Baixa uma imagem OCI/Docker pública (Docker Hub) e monta o rootfs, sem Docker.

Uso: pull_image.py <repo> <tag> <destino> <cache>

- Camadas ficam em cache por digest (sha256 verificado). Se a instalação cair, a próxima retoma de onde parou:
  o download continua do byte em que parou (HTTP Range) e camadas já baixadas não são baixadas de novo.
- A extração usa o tar nativo (GNU tar) — muito mais rápido que o tarfile do Python sob o proot — com
  checkpoints de progresso, para o app mostrar o andamento em tempo real e detectar travamentos.
- Whiteouts (.wh.<nome>) e diretórios opacos (.wh..wh..opq) são aplicados antes de cada camada.
Todas as mensagens vão para stdout com flush, para o registro do app acompanhar ao vivo.
"""
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

REGISTRY = "https://registry-1.docker.io"
ACCEPT = ", ".join([
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
])
MB = 1024 * 1024


def log(msg):
    print(msg, flush=True)


def fail(msg):
    log(f"ERRO: {msg}")
    sys.exit(2)


def arch():
    m = platform.machine().lower()
    return {"aarch64": "arm64", "arm64": "arm64", "x86_64": "amd64", "amd64": "amd64"}.get(m, m)


def token(repo):
    url = f"https://auth.docker.io/token?service=registry.docker.io&scope=repository:{repo}:pull"
    for attempt in range(5):
        try:
            with urllib.request.urlopen(url, timeout=30) as r:
                return json.load(r)["token"]
        except Exception as e:  # noqa: BLE001
            log(f"   token: tentativa {attempt + 1} falhou ({e}); tentando de novo…")
            time.sleep(3 * (attempt + 1))
    fail("não consegui autenticar no Docker Hub (sem internet?)")


def request(url, tok, accept=ACCEPT, extra=None):
    headers = {"Authorization": f"Bearer {tok}", "Accept": accept}
    headers.update(extra or {})
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60)


def resolve_manifest(repo, tag, tok):
    with request(f"{REGISTRY}/v2/{repo}/manifests/{tag}", tok) as r:
        data = json.load(r)
    if "manifests" in data:
        want = arch()
        pick = next((m for m in data["manifests"]
                     if m.get("platform", {}).get("os") == "linux" and m.get("platform", {}).get("architecture") == want), None)
        if pick is None:
            fail(f"a imagem {repo}:{tag} não tem versão para {want}")
        with request(f"{REGISTRY}/v2/{repo}/manifests/{pick['digest']}", tok) as r:
            data = json.load(r)
        data["_digest"] = pick["digest"]
    return data


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(4 * MB)
            if not b:
                break
            h.update(b)
    return "sha256:" + h.hexdigest()


def download(repo, layer, cache, index, count):
    digest, size = layer["digest"], layer.get("size", 0)
    final = os.path.join(cache, digest.replace(":", "_"))
    if os.path.exists(final) and os.path.getsize(final) == size:
        log(f"[{index}/{count}] camada já baixada ({size // MB} MB) — usando cache")
        return final
    part = final + ".part"
    log(f"[{index}/{count}] baixando {size / MB:.1f} MB…")
    for attempt in range(8):
        have = os.path.getsize(part) if os.path.exists(part) else 0
        if size and have >= size:
            break
        try:
            tok = token(repo)
            extra = {"Range": f"bytes={have}-"} if have else None
            with request(f"{REGISTRY}/v2/{repo}/blobs/{digest}", tok, accept="*/*", extra=extra) as r:
                if have and r.status != 206:
                    have = 0  # servidor ignorou o Range: recomeça
                mode = "ab" if have else "wb"
                last_print, last_pct = time.time(), -1
                with open(part, mode) as out:
                    while True:
                        chunk = r.read(MB)
                        if not chunk:
                            break
                        out.write(chunk)
                        have += len(chunk)
                        pct = int(have * 100 / size) if size else 0
                        now = time.time()
                        if pct // 5 != last_pct // 5 or now - last_print > 10:
                            log(f"   [{index}/{count}] {pct}% · {have / MB:.1f}/{size / MB:.1f} MB")
                            last_pct, last_print = pct, now
            if not size or have >= size:
                break
        except (urllib.error.URLError, OSError, TimeoutError) as e:
            log(f"   rede caiu ({e}); retomando do byte {have} em {3 * (attempt + 1)} s…")
            time.sleep(3 * (attempt + 1))
    else:
        fail(f"download da camada {index} não terminou após várias tentativas")
    log(f"   verificando integridade (sha256)…")
    got = sha256_of(part)
    if got != digest:
        os.unlink(part)
        fail(f"camada {index} corrompida (esperado {digest[:19]}, obtido {got[:19]}). Toque em Instalar de novo.")
    os.rename(part, final)
    return final


def safe_join(root, name):
    name = name.lstrip("./").lstrip("/")
    path = os.path.normpath(os.path.join(root, name))
    return path if path == root or path.startswith(root + os.sep) else None


def remove(path):
    if os.path.islink(path) or os.path.isfile(path):
        os.unlink(path)
    elif os.path.isdir(path):
        shutil.rmtree(path, ignore_errors=True)


def apply_layer(tar_path, root, index, count):
    # 1) whiteouts: listar é rápido; aplica antes de extrair
    lst = subprocess.run(["tar", "-tzf", tar_path], capture_output=True, text=True)
    if lst.returncode != 0:
        fail(f"camada {index} ilegível: {lst.stderr.strip()[:300]}")
    names = lst.stdout.splitlines()
    wh = 0
    for name in names:
        clean = name.rstrip("/")
        base, parent = os.path.basename(clean), os.path.dirname(clean)
        if base == ".wh..wh..opq":
            d = safe_join(root, parent)
            if d and os.path.isdir(d):
                for child in os.listdir(d):
                    remove(os.path.join(d, child))
            wh += 1
        elif base.startswith(".wh."):
            t = safe_join(root, os.path.join(parent, base[4:]))
            if t:
                remove(t)
            wh += 1
    log(f"[{index}/{count}] extraindo {len(names)} itens" + (f" ({wh} remoções)" if wh else "") + "…")
    # 2) extração nativa com progresso periódico
    step = max(500, len(names) // 20)
    cmd = ["tar", "-xzf", tar_path, "-C", root, "--no-same-owner", "--overwrite", "--exclude=.wh.*",
           f"--checkpoint={step}", f"--checkpoint-action=echo=   [{index}/{count}] extraindo… registro %u"]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    errors = []
    for line in p.stdout:
        line = line.rstrip()
        if "extraindo" in line:
            log(line)
        elif line:
            errors.append(line)
            if len(errors) <= 20:
                log(f"   tar: {line}")
    rc = p.wait()
    # GNU tar retorna 2 em erros fatais; 1 = alguns arquivos diferentes (aceitável)
    if rc >= 2:
        fail(f"extração da camada {index} falhou (tar={rc}): " + " | ".join(errors[-5:]))
    log(f"[{index}/{count}] camada aplicada")


def main():
    repo, tag, dest, cache = sys.argv[1], sys.argv[2], os.path.abspath(sys.argv[3]), os.path.abspath(sys.argv[4])
    if shutil.which("tar") is None:
        fail("tar não encontrado no Linux do app")
    if b"GNU tar" not in subprocess.run(["tar", "--version"], capture_output=True).stdout:
        fail("o tar do sistema não é o GNU tar (rode: apk add tar)")
    os.makedirs(cache, exist_ok=True)
    free = shutil.disk_usage(cache).free
    log(f"Espaço livre: {free / MB / 1024:.1f} GB · arquitetura {arch()}")
    tok = token(repo)
    man = resolve_manifest(repo, tag, tok)
    layers = man.get("layers") or []
    if not layers:
        fail("manifesto sem camadas")
    total = sum(l.get("size", 0) for l in layers)
    log(f"Imagem {repo}:{tag} · {len(layers)} camadas · {total / MB:.0f} MB compactados")
    if free < total * 3:
        fail(f"espaço insuficiente: precisa de ~{total * 3 / MB / 1024:.1f} GB livres")
    for l in layers:
        if "zstd" in l.get("mediaType", ""):
            fail("camada zstd não suportada")
    paths = [download(repo, l, cache, i, len(layers)) for i, l in enumerate(layers, 1)]
    log("Download completo. Montando o sistema de arquivos…")
    staging = dest + ".partial"
    shutil.rmtree(staging, ignore_errors=True)
    os.makedirs(staging)
    for i, path in enumerate(paths, 1):
        apply_layer(path, staging, i, len(paths))
    shutil.rmtree(dest, ignore_errors=True)
    os.rename(staging, dest)
    with open(os.path.join(dest, ".fluwy-image"), "w") as f:
        f.write(json.dumps({"repo": repo, "tag": tag, "digest": man.get("_digest", "")}))
    # Cache não é mais necessário: libera ~1 GB.
    shutil.rmtree(cache, ignore_errors=True)
    log("Imagem pronta.")


if __name__ == "__main__":
    main()
