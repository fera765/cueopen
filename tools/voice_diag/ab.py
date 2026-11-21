"""A/B de motor: mesma voz leve, mesmas frases, Whisper. Uso: ab.py <voz> <motor: sherpa|piper>"""
import json, os, re, sys, zipfile
import numpy as np, soundfile as sf
sys.path.insert(0, os.path.dirname(__file__)); sys.argv_backup = list(sys.argv)
VID, ENGINE = sys.argv[1], sys.argv[2]
sys.argv = [sys.argv[0], VID]
import importlib.util
spec = importlib.util.spec_from_file_location("t", os.path.join(os.path.dirname(__file__), "tune_lib.py")); t = importlib.util.module_from_spec(spec); spec.loader.exec_module(t)
P = {"cadu": (0.667, 0.5, 1.15), "faber": (0.5, 0.8, 1.08), "lessac": (0.5, 0.5, 1.08), "ryan": (0.5, 0.8, 1.0), "amy": (0.667, 0.5, 1.08)}[VID]
ns, nw, ls = P
if ENGINE == "sherpa":
    import sherpa_onnx
    d = f"ex/piper2-{VID}-int8"; zipfile.ZipFile(f"pk/piper2-{VID}-int8.zip").extractall("ex")
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/model.onnx", lexicon="", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data",
                                                   noise_scale=ns, noise_scale_w=nw, length_scale=ls), num_threads=4), max_num_sentences=1))
    def synth(text):
        g = tts.generate(text, sid=0, speed=1.0); return np.array(g.samples, dtype=np.float32), g.sample_rate
else:
    from piper import PiperVoice, SynthesisConfig
    from huggingface_hub import hf_hub_download
    from onnxruntime.quantization import quantize_dynamic, QuantType
    path = {"cadu": "pt/pt_BR/cadu/medium/pt_BR-cadu-medium", "faber": "pt/pt_BR/faber/medium/pt_BR-faber-medium", "lessac": "en/en_US/lessac/medium/en_US-lessac-medium",
            "ryan": "en/en_US/ryan/medium/en_US-ryan-medium", "amy": "en/en_US/amy/medium/en_US-amy-medium"}[VID]
    m = hf_hub_download("rhasspy/piper-voices", path + ".onnx"); c = hf_hub_download("rhasspy/piper-voices", path + ".onnx.json")
    quantize_dynamic(m, "q.onnx", weight_type=QuantType.QUInt8); v = PiperVoice.load("q.onnx", config_path=c)
    cfg = SynthesisConfig(noise_scale=ns, noise_w_scale=nw, length_scale=ls)
    def synth(text):
        return np.concatenate([ch.audio_float_array for ch in v.synthesize(text, syn_config=cfg)]), v.config.sample_rate
errs = tot = 0; bad = []; allaudio = []
for s in t.SENT:
    a, sr = synth(t.normalize(s) if t.PT else s)
    sf.write("tmp.wav", np.concatenate([np.zeros(int(sr * 0.3), dtype=np.float32), a]), sr)
    allaudio += [a, np.zeros(int(sr * 0.4), dtype=np.float32)]
    hyp = " ".join(x.text for x in t.asr.transcribe("tmp.wav", language="pt" if t.PT else "en", beam_size=5)[0]).strip()
    e, n = t.wer(s, hyp); errs += e; tot += n
    if e: bad.append({"enviado": s, "whisper": hyp})
ver = __import__("sherpa_onnx").__version__ if ENGINE == "sherpa" else "piper"
os.makedirs("out", exist_ok=True)
r = {"voice": VID, "engine": ENGINE, "version": ver, "wer": round(errs / tot, 4), "errors": bad}
json.dump(r, open(f"out/ab_{VID}_{ENGINE}_{ver}.json", "w"), indent=2, ensure_ascii=False); print(json.dumps(r, ensure_ascii=False, indent=1))

import subprocess
full = np.concatenate(allaudio); full = full / max(1e-6, float(np.abs(full).max())) * 0.95
sf.write("full.wav", full, sr); subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", "full.wav", "-b:a", "96k", f"out/amostra-{VID}.mp3"])
