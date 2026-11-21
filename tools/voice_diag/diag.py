"""Diagnóstico: carrega os pacotes da release voices-v2 como o app (sherpa-onnx 1.10.46) e confere duração + transcrição."""
import glob, json, os, sys, zipfile, subprocess
import numpy as np, soundfile as sf, sherpa_onnx
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "voice_lab3"))
from normalize_pt import normalize
from faster_whisper import WhisperModel

PT = "Olá! Tudo bem por aí? Eu sou o Cue, seu assistente. Estou aqui pra ajudar com o que você precisar, é só falar!"
EN = "Hello! How are you doing? I'm Cue, your assistant. I'm here to help with whatever you need, just ask!"
P = {"cadu": (0.4, 0.5), "faber": (0.4, 0.8), "amy": (0.55, 0.5), "lessac": (0.8, 0.8), "ryan": (0.8, 0.5)}
asr = WhisperModel("small", device="cpu", compute_type="int8")
os.makedirs("out", exist_ok=True); rep = []
for z in sorted(glob.glob("pk/*.zip")):
    name = os.path.basename(z)[:-4]; vid = name.split("-")[1]
    zipfile.ZipFile(z).extractall("ex"); d = f"ex/{name}"
    ns, nw = P[vid]; pt = vid in ("cadu", "faber")
    for label, (a, b) in {"tuned": (ns, nw), "default": (0.667, 0.8)}.items():
        tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/model.onnx", lexicon="", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data",
                                                       noise_scale=a, noise_scale_w=b, length_scale=1.0), num_threads=2), max_num_sentences=1))
        txt = normalize(PT) if pt else EN
        g = tts.generate(txt, sid=0, speed=1.0)
        s = np.array(g.samples, dtype=np.float32); dur = len(s) / g.sample_rate
        sf.write(f"out/{name}_{label}.wav", s, g.sample_rate)
        hyp = " ".join(x.text for x in asr.transcribe(f"out/{name}_{label}.wav", language="pt" if pt else "en")[0])
        r = {"pkg": name, "params": label, "dur_s": round(dur, 2), "sr": g.sample_rate, "peak": round(float(np.abs(s).max()), 3), "rms": round(float(np.sqrt((s ** 2).mean())), 4), "asr": hyp}
        print(json.dumps(r, ensure_ascii=False), flush=True); rep.append(r)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", f"out/{name}_{label}.wav", "-b:a", "64k", f"out/{name}_{label}.mp3"]); os.remove(f"out/{name}_{label}.wav")
    # tokens.txt e metadados
    print(name, "tokens", sum(1 for _ in open(f"{d}/tokens.txt")), flush=True)
json.dump(rep, open("out/diag.json", "w"), indent=2, ensure_ascii=False)
