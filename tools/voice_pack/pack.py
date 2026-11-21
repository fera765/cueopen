"""Empacota as vozes Piper aprovadas para o app (sherpa-onnx 1.10.46): fp32 e int8, tokens, espeak (pt+en),
valida carregando no próprio sherpa-onnx e gera amostras curtas (prévia na tela de vozes)."""
import json, os, glob, shutil, subprocess, sys, zipfile
import onnx, numpy as np, soundfile as sf
from huggingface_hub import hf_hub_download
from onnxruntime.quantization import quantize_dynamic, QuantType
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "voice_lab3"))
from normalize_pt import normalize

VOICES = {  # id: (caminho rhasspy, idioma, noise_scale, noise_w, length_scale)
    "cadu": ("pt/pt_BR/cadu/medium/pt_BR-cadu-medium", "Portuguese", 0.4, 0.5, 1.0),
    "faber": ("pt/pt_BR/faber/medium/pt_BR-faber-medium", "Portuguese", 0.4, 0.8, 1.0),
    "amy": ("en/en_US/amy/medium/en_US-amy-medium", "English", 0.55, 0.5, 1.0),
    "lessac": ("en/en_US/lessac/medium/en_US-lessac-medium", "English", 0.8, 0.8, 1.0),
    "ryan": ("en/en_US/ryan/medium/en_US-ryan-medium", "English", 0.8, 0.5, 1.0),
}
PREVIEW = {"Portuguese": "Oi! Eu sou a sua assistente de voz. Posso ler suas mensagens, organizar sua agenda e responder o que você precisar, a qualquer hora.",
           "English": "Hi! I'm your voice assistant. I can read your messages, organize your schedule, and answer anything you need, any time."}
OUT = "dist"; os.makedirs(OUT, exist_ok=True)

subprocess.run("curl -fsSL -o ref.tar.bz2 https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2 && tar xjf ref.tar.bz2", shell=True, check=True)
REF = "vits-piper-pt_BR-faber-medium"
REF_META = {p.key: p.value for p in onnx.load(glob.glob(f"{REF}/*.onnx")[0]).metadata_props}
print("meta ref", REF_META)
ESPEAK = "espeak-ng-data"
shutil.copytree(f"{REF}/espeak-ng-data", ESPEAK, dirs_exist_ok=True)
for d in glob.glob(f"{ESPEAK}/*_dict"):
    if os.path.basename(d) not in ("pt_dict", "en_dict"): os.remove(d)

import sherpa_onnx
manifest = []
for vid, (path, lang, ns, nw, ls) in VOICES.items():
    m = hf_hub_download("rhasspy/piper-voices", path + ".onnx"); cfg = json.load(open(hf_hub_download("rhasspy/piper-voices", path + ".onnx.json")))
    sr = cfg["audio"]["sample_rate"]
    def add_meta(src, dst):
        mo = onnx.load(src); meta = dict(REF_META)
        meta.update(voice=cfg["espeak"]["voice"], sample_rate=sr, n_speakers=cfg.get("num_speakers", 1), language=lang)
        del mo.metadata_props[:]
        for k, v in meta.items(): p = mo.metadata_props.add(); p.key = k; p.value = str(v)
        onnx.save(mo, dst)
    for var in ("fp32", "int8"):
        pkg = f"piper-{vid}-{var}"; d = f"build/{pkg}"; os.makedirs(d, exist_ok=True)
        if var == "fp32": add_meta(m, f"{d}/model.onnx")
        else:
            add_meta(m, f"{d}/tmp.onnx"); quantize_dynamic(f"{d}/tmp.onnx", f"{d}/model.onnx", weight_type=QuantType.QUInt8)
            os.remove(f"{d}/tmp.onnx"); add_meta(f"{d}/model.onnx", f"{d}/model.onnx")
        with open(f"{d}/tokens.txt", "w") as f:
            for sym, ids in cfg["phoneme_id_map"].items(): f.write(f"{sym} {ids[0]}\n")
        shutil.copytree(ESPEAK, f"{d}/espeak-ng-data", dirs_exist_ok=True)
        # valida no motor do app
        tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/model.onnx", lexicon="", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data",
                                                       noise_scale=ns, noise_scale_w=nw, length_scale=ls), num_threads=2)))
        txt = PREVIEW[lang]; txt = normalize(txt) if lang == "Portuguese" else txt
        a = tts.generate(txt, sid=0, speed=1.0)
        assert len(a.samples) > sr, f"{pkg}: áudio vazio"
        if var == "fp32":
            s = np.array(a.samples, dtype=np.float32); s = s / max(1e-6, float(np.abs(s).max())) * 0.95
            sf.write(f"{vid}.wav", s, a.sample_rate)
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", f"{vid}.wav", "-ac", "1", "-b:a", "48k", f"{OUT}/preview-{vid}.mp3"], check=True)
        z = f"{OUT}/{pkg}.zip"
        with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED) as zf:
            for root, _, files in os.walk(d):
                for fn in files:
                    full = os.path.join(root, fn); zf.write(full, os.path.relpath(full, "build"))
        manifest.append({"id": vid, "variant": var, "zip": os.path.basename(z), "bytes": os.path.getsize(z), "lang": lang, "ns": ns, "nw": nw, "ls": ls})
        print(pkg, os.path.getsize(z) / 1e6, "MB ok", flush=True)
json.dump(manifest, open(f"{OUT}/manifest.json", "w"), indent=2)
