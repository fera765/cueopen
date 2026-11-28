"""Avalia o modelo de uma etapa do treino: 24 frases pt-BR, Whisper medium (erros por palavra) e UTMOS (naturalidade 1–5).
Uso: evalstage.py <model.onnx> <config.json> <etapa>"""
import sys, os, re, json, base64, subprocess
import numpy as np, soundfile as sf, torch
from piper import PiperVoice, SynthesisConfig
from faster_whisper import WhisperModel
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "voice_lab3"))
from normalize_pt import normalize
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "voice_diag"))
src = open(os.path.join(os.path.dirname(__file__), "..", "voice_diag", "tune.py"), encoding="utf-8").read()
SENT = eval(src[src.index("SENT_PT = ") + 10: src.index("SENT_EN = ")].strip())
model, cfg, stage = sys.argv[1], sys.argv[2], sys.argv[3]
v = PiperVoice.load(model, config_path=cfg); sr = v.config.sample_rate
asr = WhisperModel("medium", device="cpu", compute_type="int8", cpu_threads=4)
utmos = torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong", trust_repo=True).eval()

def words(s):
    return re.findall(r"[a-záéíóúâêôãõçà]+", normalize(s).lower().replace("whatsapp", "uótsap").replace("-", " "))
def wer(ref, hyp):
    r, h = words(ref), words(hyp); dp = list(range(len(h) + 1))
    for i, rw in enumerate(r, 1):
        prev, dp[0] = dp[0], i
        for j, hw in enumerate(h, 1): prev, dp[j] = dp[j], min(dp[j] + 1, dp[j - 1] + 1, prev + (rw != hw))
    return dp[len(h)], len(r)

errs = tot = 0; bad = []; mos = []; audio = []
for s in SENT:
    a = np.concatenate([c.audio_float_array for c in v.synthesize(normalize(s), syn_config=SynthesisConfig(noise_scale=0.5, noise_w_scale=0.6))])
    sf.write("e.wav", np.concatenate([np.zeros(int(sr * 0.3), dtype=np.float32), a]), sr)
    hyp = " ".join(x.text for x in asr.transcribe("e.wav", language="pt", beam_size=5)[0]).strip()
    e, n = wer(s, hyp); errs += e; tot += n
    if e: bad.append({"enviado": s, "whisper": hyp})
    with torch.no_grad(): mos.append(float(utmos(torch.from_numpy(a).float().unsqueeze(0), sr).item()))
    audio += [a, np.zeros(int(sr * 0.4), dtype=np.float32)]
full = np.concatenate(audio); full = full / max(1e-6, float(np.abs(full).max())) * 0.95
sf.write("full.wav", full, sr); subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", "full.wav", "-ac", "1", "-b:a", "48k", "full.mp3"])
r = {"etapa": stage, "wer": round(errs / tot, 4), "utmos": round(float(np.mean(mos)), 3), "erros": bad}
print("EVAL " + json.dumps(r, ensure_ascii=False), flush=True)
print(f"==AMOSTRA-{stage}-BEGIN=="); print(base64.b64encode(open("full.mp3", "rb").read()).decode()); print(f"==AMOSTRA-{stage}-END==")
json.dump(r, open("eval.json", "w"), ensure_ascii=False)
