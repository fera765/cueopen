"""Laboratório da voz Dii: deriva versões mais leves, mede latência/memória com 2 núcleos e valida qualidade.

Saída em out/: report.json, report.md, wavs de amostra e o pacote final para o app (sherpa-onnx).
"""
import json, os, sys, time, glob, resource, subprocess, tarfile, shutil
import numpy as np

OUT = "out"; os.makedirs(OUT, exist_ok=True)
REPO = os.environ.get("VOICE_REPO", "OpenVoiceOS/pipertts_pt-PT_dii")
TEXTS = [
    "Olá! Eu sou a sua assistente.",
    "Claro, já verifiquei a sua caixa de entrada e encontrei três e-mails importantes que precisam de resposta hoje.",
    "A reunião ficou marcada para amanhã às dez horas. Quer que eu envie o convite para a equipe?",
]

def log(*a): print(*a, flush=True)

# ---------- 1) Modelo ----------
from huggingface_hub import snapshot_download
src = snapshot_download(REPO, local_dir="model_src")
onnxs = [p for p in glob.glob(f"{src}/**/*.onnx", recursive=True)]
cfgs = [p for p in glob.glob(f"{src}/**/*.json", recursive=True) if "config" in p or p.endswith(".onnx.json")]
log("arquivos:", os.listdir(src), onnxs, cfgs)
model = sorted(onnxs, key=os.path.getsize)[-1]
cfg_path = next((c for c in cfgs if os.path.basename(c).startswith(os.path.basename(model))), cfgs[0])
cfg = json.load(open(cfg_path))
SR = cfg["audio"]["sample_rate"]
log("modelo", model, os.path.getsize(model) / 1e6, "MB; sr", SR, "espeak", cfg.get("espeak"))

import onnx
from onnxruntime.quantization import quantize_dynamic, QuantType
import onnxruntime as ort

# Voz Piper oficial já convertida pelo sherpa-onnx: copia o formato EXATO dos metadados e o espeak-ng-data compatível.
REF = None
for cand in ["vits-piper-pt_BR-faber-medium", "vits-piper-pt_PT-tugao-medium", "vits-piper-en_US-amy-low"]:
    r = subprocess.run(["bash", "-c", f"curl -fsSL -o ref.tar.bz2 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/{cand}.tar.bz2' && tar xjf ref.tar.bz2"])
    if r.returncode == 0 and os.path.isdir(cand): REF = cand; break
assert REF, "nenhuma voz de referência do sherpa-onnx baixou"
log("referência:", REF)
ref_onnx = glob.glob(f"{REF}/*.onnx")[0]
REF_META = {p.key: p.value for p in onnx.load(ref_onnx).metadata_props}
log("metadados da referência:", REF_META)
if not os.path.isdir("espeak-ng-data"): shutil.copytree(f"{REF}/espeak-ng-data", "espeak-ng-data")

def add_meta(path_in, path_out):
    m = onnx.load(path_in)
    meta = dict(REF_META)
    meta.update({"voice": cfg.get("espeak", {}).get("voice", meta.get("voice", "pt")), "sample_rate": SR,
                 "n_speakers": cfg.get("num_speakers", 1), "language": "Portuguese"})
    del m.metadata_props[:]
    for k, v in meta.items():
        p = m.metadata_props.add(); p.key = k; p.value = str(v)
    onnx.save(m, path_out)

os.makedirs("variants", exist_ok=True)
fp32 = "variants/dii.fp32.onnx"; add_meta(model, fp32)
int8 = "variants/dii.int8.onnx"
quantize_dynamic(fp32, int8, weight_type=QuantType.QUInt8)
add_meta(int8, int8)
with open("variants/tokens.txt", "w") as f:
    for sym, ids in cfg["phoneme_id_map"].items():
        f.write(f"{sym} {ids[0]}\n")

# ---------- 2) espeak-ng-data: o da voz de referência (versão compatível com o sherpa-onnx) ----------
assert os.path.isfile("espeak-ng-data/phontab"), "espeak-ng-data inválido"

# ---------- 3) Benchmark em processo separado (memória isolada) ----------
BENCH = r'''
import sys, time, json, resource, numpy as np, sherpa_onnx, soundfile as sf
model, name, threads, ns, nw, ls = sys.argv[1], sys.argv[2], int(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5]), float(sys.argv[6])
texts = json.loads(sys.argv[7]); tokens = sys.argv[8]; chunk = sys.argv[9] == "1"
import re
t0 = time.time()
cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(vits=sherpa_onnx.OfflineTtsVitsModelConfig(
    model=model, lexicon="", tokens=tokens, data_dir="espeak-ng-data", noise_scale=ns, noise_scale_w=nw, length_scale=ls),
    num_threads=threads, provider="cpu"), max_num_sentences=1)
tts = sherpa_onnx.OfflineTts(cfg)
load = time.time() - t0
tts.generate("Oi.", sid=0, speed=1.0)  # aquecimento
res = []
for i, t in enumerate(texts):
    first = [None]; s = time.time()
    def cb(samples, progress):
        if first[0] is None: first[0] = time.time() - s
        return 1
    # Modo "chunk" = como o app vai falar: corta em frases curtas (também em vírgulas) e toca a 1ª enquanto gera o resto.
    parts = [p.strip() for p in re.split(r"(?<=[,;:.!?])\s+", t) if p.strip()] if chunk else [t]
    allsamp = []; sr = 22050
    for p in parts:
        a = tts.generate(p, sid=0, speed=1.0, callback=cb)
        if not a.samples: raise SystemExit("sem áudio para: " + p)
        allsamp.extend(a.samples); sr = a.sample_rate
    total = time.time() - s
    dur = len(allsamp) / sr
    sf.write(f"out/{name}_{i}.wav", np.array(allsamp, dtype=np.float32), sr)
    res.append({"first_audio_s": round(first[0] or total, 3), "total_s": round(total, 3), "audio_s": round(dur, 2), "rtf": round(total / max(dur, 1e-6), 3)})
print(json.dumps({"name": name, "load_s": round(load, 2), "peak_rss_mb": round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024, 1), "runs": res}))
'''
open("bench.py", "w").write(BENCH)

def bench(model, name, threads=2, ns=0.667, nw=0.8, ls=1.0, tokens="variants/tokens.txt", chunk=False):
    r = subprocess.run(["taskset", "-c", "0,1", sys.executable, "bench.py", model, name, str(threads), str(ns), str(nw), str(ls), json.dumps(TEXTS), tokens, "1" if chunk else "0"],
                       capture_output=True, text=True)
    if r.returncode != 0: log(r.stderr[-3000:]); return None
    j = json.loads(r.stdout.strip().splitlines()[-1]); j["size_mb"] = round(os.path.getsize(model) / 1e6, 1); log(json.dumps(j)); return j

results = [bench(ref_onnx, "ref_" + REF.replace("vits-piper-", ""), tokens=f"{REF}/tokens.txt"),
           bench(fp32, "fp32"), bench(int8, "int8"),
           bench(fp32, "fp32_chunk", chunk=True), bench(int8, "int8_chunk", chunk=True),
           bench(fp32, "fp32_chunk_1thread", threads=1, chunk=True),
           bench(fp32, "fp32_natural", ns=0.55, nw=0.7, ls=1.03, chunk=True)]

# ---------- 4) Qualidade: distância espectral log-mel vs. original ----------
def logmel(x, sr=SR, n=1024, hop=256, mels=80):
    import numpy.fft as F
    w = np.hanning(n); fr = [x[i:i + n] * w for i in range(0, max(len(x) - n, 1), hop)]
    S = np.abs(F.rfft(np.array(fr), axis=1)) ** 2
    f = np.linspace(0, sr / 2, S.shape[1]); m = 2595 * np.log10(1 + f / 700)
    edges = np.linspace(m.min(), m.max(), mels + 2); fb = np.zeros((mels, S.shape[1]))
    for k in range(mels):
        l, c, r = edges[k:k + 3]; fb[k] = np.clip(np.minimum((m - l) / (c - l + 1e-9), (r - m) / (r - c + 1e-9)), 0, None)
    return np.log(S @ fb.T + 1e-6)

import soundfile as sf
quality = {}
for v in ["int8", "fp32_chunk", "fp32_natural"]:
    ds = []
    for i in range(len(TEXTS)):
        a, _ = sf.read(f"out/fp32_{i}.wav"); b, _ = sf.read(f"out/{v}_{i}.wav")
        A, B = logmel(a), logmel(b); n = min(len(A), len(B))
        ds.append(float(np.mean(np.abs(A[:n] - B[:n]))))
    quality[v] = round(float(np.mean(ds)), 3)
log("qualidade (log-mel L1 vs fp32; menor = mais igual):", quality)

# ---------- 5) Pacotes do app: fp32 (mais rápido) e int8 (mais leve) ----------
pkgs = {}
for tag, path in [("fp32", fp32), ("int8", int8)]:
    pkg = f"vits-piper-pt_PT-dii-{tag}"
    os.makedirs(f"{OUT}/{pkg}", exist_ok=True)
    shutil.copy(path, f"{OUT}/{pkg}/model.onnx"); shutil.copy("variants/tokens.txt", f"{OUT}/{pkg}/tokens.txt")
    shutil.copytree("espeak-ng-data", f"{OUT}/{pkg}/espeak-ng-data", dirs_exist_ok=True)
    # zip (o Android descompacta com java.util.zip, sem biblioteca extra)
    shutil.make_archive(f"{OUT}/{pkg}", "zip", OUT, pkg)
    shutil.rmtree(f"{OUT}/{pkg}")
    pkgs[tag] = round(os.path.getsize(f"{OUT}/{pkg}.zip") / 1e6, 1)
report = {"repo": REPO, "sample_rate": SR, "espeak_voice": cfg.get("espeak", {}).get("voice"), "results": [r for r in results if r], "quality": quality,
          "packages_mb": pkgs}
json.dump(report, open(f"{OUT}/report.json", "w"), indent=2)
log(json.dumps(report, indent=2))
