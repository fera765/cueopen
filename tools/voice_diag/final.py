"""Avaliação final: parâmetros finais + dicionário por voz (igual ao app), 24 frases x 2 gerações, Whisper medium.
Também gera a amostra longa e a prévia (base64 no log)."""
import json, os, re, sys, zipfile, subprocess, base64
import numpy as np, soundfile as sf, sherpa_onnx
sys.argv = [sys.argv[0], sys.argv[1]]
import importlib.util
spec = importlib.util.spec_from_file_location("t", os.path.join(os.path.dirname(__file__), "tune_lib2.py")); t = importlib.util.module_from_spec(spec); spec.loader.exec_module(t)
VID = sys.argv[1]
P = {"cadu": (0.5, 0.5, 1.08), "faber": (0.333, 0.5, 1.08), "lessac": (0.333, 0.8, 1.0), "ryan": (0.333, 0.8, 1.15)}[VID]
LEX = {"cadu": [(r"(?<![^\W\d_])criei(?![^\W\d_])", "criiêi")], "faber": [(r"(?<![^\W\d_])(?<!eu )criei(?![^\W\d_])", "eu criei")],
       "ryan": [(r"(?<![^\W\d_])saved(?![^\W\d_])", "save'd")]}.get(VID, [])
def prep(s):
    s = t.normalize(s) if t.PT else s
    for pat, say in LEX: s = re.sub(pat, lambda m: say[0].upper() + say[1:] if m.group(0)[0].isupper() else say, s, flags=re.I)
    return s
d = t.d
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/model.onnx", lexicon="", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data",
                                               noise_scale=P[0], noise_scale_w=P[1], length_scale=P[2]), num_threads=4), max_num_sentences=1))
errs = tot = 0; bad = []; audio = []
for s in t.SENT:
    for k in range(2):
        g = tts.generate(prep(s), sid=0, speed=1.0); sr = g.sample_rate; a = np.array(g.samples, dtype=np.float32)
        sf.write("tmp.wav", np.concatenate([np.zeros(int(sr * 0.3), dtype=np.float32), a]), sr)
        hyp = " ".join(x.text for x in t.asr.transcribe("tmp.wav", language="pt" if t.PT else "en", beam_size=5)[0]).strip()
        e, n = t.wer(s, hyp); errs += e; tot += n
        if e: bad.append({"enviado": s, "whisper": hyp})
        if k == 0: audio += [np.zeros(int(sr * (0.15 if not audio else 0)), dtype=np.float32), a, np.zeros(int(sr * 0.4), dtype=np.float32)]
full = np.concatenate(audio); full = full / max(1e-6, float(np.abs(full).max())) * 0.95
sf.write("full.wav", full, sr); subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", "full.wav", "-ac", "1", "-b:a", "48k", "full.mp3"])
PREV = "Oi! Eu sou a sua assistente de voz. Posso ler suas mensagens, organizar sua agenda e responder o que você precisar, a qualquer hora." if t.PT else \
       "Hi! I'm your voice assistant. I can read your messages, organize your schedule, and answer anything you need, any time."
g = tts.generate(prep(PREV), sid=0, speed=1.0); a = np.concatenate([np.zeros(int(g.sample_rate * 0.15), dtype=np.float32), np.array(g.samples, dtype=np.float32)])
a = a / max(1e-6, float(np.abs(a).max())) * 0.95; sf.write("prev.wav", a, g.sample_rate)
subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", "prev.wav", "-ac", "1", "-b:a", "48k", "prev.mp3"])
for tag, p in (("AMOSTRA", "full.mp3"), ("PREVIEW", "prev.mp3")):
    print(f"=={tag}-{VID}-BEGIN=="); print(base64.b64encode(open(p, "rb").read()).decode()); print(f"=={tag}-{VID}-END==")
print("RESULT " + json.dumps({"voice": VID, "wer": round(errs / tot, 4), "errors": bad}, ensure_ascii=False), flush=True)
