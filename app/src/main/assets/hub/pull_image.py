#!/usr/bin/env python3
"""Baixa uma imagem OCI/Docker pública (Docker Hub) e monta o rootfs, sem Docker.

Uso: pull_image.py <repo> <tag> <destino>
Aplica as camadas em ordem, respeitando whiteouts (.wh.<nome>) e diretórios opacos (.wh..wh..opq).
Só usa a biblioteca padrão do Python (roda dentro do Alpine do app).
"""
import json
import os
import platform
import shutil
import sys
import tarfile
import tempfile
import urllib.request

REGISTRY = "https://registry-1.docker.io"
ACCEPT = ", ".join([
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
])


def log(msg):
    print(msg, flush=True)


def arch():
    m = platform.machine().lower()
    return {"aarch64": "arm64", "arm64": "arm64", "x86_64": "amd64", "amd64": "amd64"}.get(m, m)


def token(repo):
    url = f"https://auth.docker.io/token?service=registry.docker.io&scope=repository:{repo}:pull"
    with urllib.request.urlopen(url, timeout=30) as r:
        return json.load(r)["token"]


def get(url, tok, accept=ACCEPT):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {tok}", "Accept": accept})
    return urllib.request.urlopen(req, timeout=60)


def resolve_manifest(repo, tag, tok):
    with get(f"{REGISTRY}/v2/{repo}/manifests/{tag}", tok) as r:
        data = json.load(r)
    if "manifests" in data:  # índice multi-arquitetura
        want = arch()
        pick = next((m for m in data["manifests"]
                     if m.get("platform", {}).get("os") == "linux" and m.get("platform", {}).get("architecture") == want), None)
        if pick is None:
            raise SystemExit(f"A imagem {repo}:{tag} não tem versão para {want}.")
        with get(f"{REGISTRY}/v2/{repo}/manifests/{pick['digest']}", tok) as r:
            data = json.load(r)
        data["_digest"] = pick["digest"]
    return data


def safe_join(root, name):
    name = name.lstrip("./").lstrip("/")
    path = os.path.normpath(os.path.join(root, name))
    if path != root and not path.startswith(root + os.sep):
        return None
    return path


def remove(path):
    if os.path.islink(path) or os.path.isfile(path):
        os.unlink(path)
    elif os.path.isdir(path):
        shutil.rmtree(path, ignore_errors=True)


def apply_layer(tar_path, root):
    with tarfile.open(tar_path, "r:*") as tf:
        members = tf.getmembers()
        keep = []
        # 1ª passada: whiteouts afetam as camadas de baixo
        for m in members:
            base = os.path.basename(m.name.rstrip("/"))
            parent = os.path.dirname(m.name.rstrip("/"))
            if base == ".wh..wh..opq":
                d = safe_join(root, parent)
                if d and os.path.isdir(d):
                    for child in os.listdir(d):
                        remove(os.path.join(d, child))
            elif base.startswith(".wh."):
                t = safe_join(root, os.path.join(parent, base[4:]))
                if t:
                    remove(t)
            else:
                if safe_join(root, m.name) is None or m.isdev():
                    continue
                keep.append(m)
        # 2ª passada: extrai o resto (substitui o que já existir)
        for m in keep:
            target = safe_join(root, m.name)
            if os.path.lexists(target) and not (m.isdir() and os.path.isdir(target) and not os.path.islink(target)):
                remove(target)
            try:
                tf.extract(m, root, set_attrs=not m.issym(), numeric_owner=True, filter="fully_trusted")
            except TypeError:  # Python < 3.12
                tf.extract(m, root, set_attrs=not m.issym(), numeric_owner=True)
            except (PermissionError, OSError) as e:
                if m.islnk():  # hardlink: copia o arquivo
                    src = safe_join(root, m.linkname)
                    if src and os.path.isfile(src):
                        shutil.copy2(src, target)
                        continue
                log(f"   aviso: {m.name}: {e}")


def main():
    repo, tag, dest = sys.argv[1], sys.argv[2], os.path.abspath(sys.argv[3])
    tok = token(repo)
    man = resolve_manifest(repo, tag, tok)
    layers = man.get("layers") or []
    if not layers:
        raise SystemExit("Manifesto sem camadas.")
    total = sum(l.get("size", 0) for l in layers)
    log(f"Imagem {repo}:{tag} ({arch()}) · {len(layers)} camadas · {total // (1024 * 1024)} MB")
    staging = dest + ".partial"
    shutil.rmtree(staging, ignore_errors=True)
    os.makedirs(staging)
    tmpdir = tempfile.mkdtemp(prefix="layer-", dir=os.path.dirname(dest))
    done = 0
    for i, layer in enumerate(layers, 1):
        mt = layer.get("mediaType", "")
        if "zstd" in mt:
            raise SystemExit("Camada zstd não suportada por este Python.")
        tok = token(repo)  # o token expira em ~5 min
        path = os.path.join(tmpdir, f"{i}.tar")
        with get(f"{REGISTRY}/v2/{repo}/blobs/{layer['digest']}", tok, accept="*/*") as r, open(path, "wb") as out:
            last = -1
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk)
                done += len(chunk)
                pct = int(done * 100 / total) if total else 0
                if pct // 5 != last // 5:
                    log(f"   {pct}% baixado")
                    last = pct
        log(f"Aplicando camada {i}/{len(layers)}…")
        apply_layer(path, staging)
        os.unlink(path)
    shutil.rmtree(tmpdir, ignore_errors=True)
    shutil.rmtree(dest, ignore_errors=True)
    os.rename(staging, dest)
    with open(os.path.join(dest, ".fluwy-image"), "w") as f:
        f.write(json.dumps({"repo": repo, "tag": tag, "digest": man.get("_digest", "")}))
    log("Imagem pronta.")


if __name__ == "__main__":
    main()
