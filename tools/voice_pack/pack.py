"""Empacota as vozes Piper aprovadas para o app (sherpa-onnx 1.10.46): fp32 e int8, tokens, espeak (pt+en),
valida carregando no próprio sherpa-onnx e gera amostras curtas (prévia na tela de vozes)."""
import json, os, glob, shutil, subprocess, sys, zipfile
import onnx, numpy as np, soundfile as sf
from huggingface_hub import hf_hub_download
from onnxruntime.quantization import quantize_dynamic, QuantType
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "voice_lab3"))
from normalize_pt import normalize

VOICES = {  # id: (caminho rhasspy, idioma, noise_scale, noise_w, length_scale)
    "cadu": ("pt/pt_BR/cadu/medium/pt_BR-cadu-medium", "Portuguese", 0.667, 0.5, 1.15),
    "faber": ("pt/pt_BR/faber/medium/pt_BR-faber-medium", "Portuguese", 0.5, 0.8, 1.08),
    "lessac": ("en/en_US/lessac/medium/en_US-lessac-medium", "English", 0.5, 0.5, 1.08),
    "ryan": ("en/en_US/ryan/medium/en_US-ryan-medium", "English", 0.5, 0.8, 1.0),
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
from onnx import helper, TensorProto

def pad_after_bos(src, dst, pad_id):
    """O sherpa-onnx 1.10.46 monta  ^ p _ r _ …  e o Piper  ^ _ p _ r _ …  (PAD logo após o início).
    Sem esse PAD o 1º som de cada frase sai deformado ("Quer" vira "É"). Insere o PAD dentro do grafo."""
    m = onnx.load(src); g = m.graph
    names = [i.name for i in g.input]; assert "input" in names and "input_lengths" in names, names
    big = 2 ** 62
    nodes = [
        helper.make_node("Constant", [], ["_pf_s0"], value=helper.make_tensor("v", TensorProto.INT64, [1], [0])),
        helper.make_node("Constant", [], ["_pf_s1"], value=helper.make_tensor("v", TensorProto.INT64, [1], [1])),
        helper.make_node("Constant", [], ["_pf_end"], value=helper.make_tensor("v", TensorProto.INT64, [1], [big])),
        helper.make_node("Constant", [], ["_pf_ax"], value=helper.make_tensor("v", TensorProto.INT64, [1], [1])),
        helper.make_node("Constant", [], ["_pf_pad"], value=helper.make_tensor("v", TensorProto.INT64, [1, 1], [pad_id])),
        helper.make_node("Slice", ["input", "_pf_s0", "_pf_s1", "_pf_ax"], ["_pf_first"]),
        helper.make_node("Slice", ["input", "_pf_s1", "_pf_end", "_pf_ax"], ["_pf_rest"]),
        helper.make_node("Concat", ["_pf_first", "_pf_pad", "_pf_rest"], ["_pf_input"], axis=1),
        helper.make_node("Add", ["input_lengths", "_pf_s1"], ["_pf_lengths"]),
    ]
    for n in g.node:
        for i, x in enumerate(n.input):
            if x == "input": n.input[i] = "_pf_input"
            elif x == "input_lengths": n.input[i] = "_pf_lengths"
    for n in reversed(nodes): g.node.insert(0, n)
    onnx.checker.check_model(m); onnx.save(m, dst)

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
    for var in ("int8",):
        pkg = f"piper2-{vid}-{var}"; d = f"build/{pkg}"; os.makedirs(d, exist_ok=True)
        pad_after_bos(m, f"{d}/pad.onnx", cfg["phoneme_id_map"]["_"][0])
        add_meta(f"{d}/pad.onnx", f"{d}/tmp.onnx"); quantize_dynamic(f"{d}/tmp.onnx", f"{d}/model.onnx", weight_type=QuantType.QUInt8)
        for f_ in ("tmp.onnx", "pad.onnx"): os.remove(f"{d}/{f_}")
        add_meta(f"{d}/model.onnx", f"{d}/model.onnx")
        with open(f"{d}/tokens.txt", "w") as f:
            for sym, ids in cfg["phoneme_id_map"].items():
                if len(sym) != 1: print(vid, "ignora token combinado", repr(sym)); continue  # o motor do app lê 1 símbolo por linha
                f.write(f"{sym} {ids[0]}\n")
        shutil.copytree(ESPEAK, f"{d}/espeak-ng-data", dirs_exist_ok=True)
        # valida no motor do app
        tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/model.onnx", lexicon="", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data",
                                                       noise_scale=ns, noise_scale_w=nw, length_scale=ls), num_threads=2)))
        txt = PREVIEW[lang]; txt = normalize(txt) if lang == "Portuguese" else txt
        a = tts.generate(txt, sid=0, speed=1.0)
        assert len(a.samples) > sr, f"{pkg}: áudio vazio"
        if True:
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
