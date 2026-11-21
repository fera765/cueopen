"""Laboratório 3: melhora vozes Piper sem retreino.
Para cada voz: (1) mede a voz original; (2) busca noise_scale/noise_w/length_scale pela nota
(UTMOS = naturalidade 1-5, Whisper = erros de pronúncia); (3) aplica o normalizador pt-BR;
(4) compara fp32 x int8; (5) gera amostras de 30 s+ de antes e depois."""
import json, os, sys, time, re, resource, subprocess, traceback
import numpy as np, soundfile as sf, torch
from huggingface_hub import hf_hub_download
from onnxruntime.quantization import quantize_dynamic, QuantType
from piper import PiperVoice, SynthesisConfig
from faster_whisper import WhisperModel
from normalize_pt import normalize

torch.set_num_threads(2)
VOICE = sys.argv[1]
REPO = {"faber": "pt/pt_BR/faber/medium/pt_BR-faber-medium", "cadu": "pt/pt_BR/cadu/medium/pt_BR-cadu-medium",
        "jeff": "pt/pt_BR/jeff/medium/pt_BR-jeff-medium", "amy": "en/en_US/amy/medium/en_US-amy-medium",
        "lessac": "en/en_US/lessac/medium/en_US-lessac-medium", "ryan": "en/en_US/ryan/medium/en_US-ryan-medium"}[VOICE]
EN = VOICE in ("amy", "lessac", "ryan")
OUT = "out"; os.makedirs(OUT, exist_ok=True)

PT_LONG = ("Bom dia! Já organizei sua agenda: a reunião com o Dr. Paulo é às 10h30, e a entrega do projeto ficou para 15/12. "
           "O orçamento do cliente de São Paulo chegou em R$ 1.250,90, com 25% de desconto se fecharmos até sexta-feira. "
           "Também chegaram 3 e-mails importantes; o 2º é da equipe financeira pedindo a nota fiscal. "
           "Se quiser, eu preparo agora um rascunho da resposta, e você só revisa antes de enviar. "
           "Ah, e amanhã deve chover forte depois das 17h, então talvez seja melhor sair um pouco mais cedo. "
           "Quer que eu coloque um lembrete para as 16h30?")
EN_LONG = ("Good morning! I've organized your schedule: your meeting with Doctor Paul is at ten thirty, and the project is due on December fifteenth. "
           "The quote from the client in New York came in at one thousand two hundred and fifty dollars, with a twenty five percent discount if we close by Friday. "
           "Three important emails also arrived; the second one is from the finance team asking for the invoice. "
           "If you'd like, I can draft a reply right now, and you just review it before sending. "
           "Oh, and it's supposed to rain hard after five p.m. tomorrow, so it might be better to leave a little early. "
           "Should I set a reminder for four thirty?")
TEXT = EN_LONG if EN else PT_LONG
EVAL = [s for s in re.split(r"(?<=[.!?])\s+", TEXT) if s][:4]   # frases de avaliação
SHORT = "Sure, I'm on it." if EN else "Claro, já estou vendo isso."

def log(*a): print(*a, flush=True)
m = hf_hub_download("rhasspy/piper-voices", REPO + ".onnx"); c = hf_hub_download("rhasspy/piper-voices", REPO + ".onnx.json")
q = f"{VOICE}.int8.onnx"; quantize_dynamic(m, q, weight_type=QuantType.QUInt8)
voices = {"fp32": PiperVoice.load(m, config_path=c), "int8": PiperVoice.load(q, config_path=c)}
SR = voices["fp32"].config.sample_rate

utmos = torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong", trust_repo=True).eval()
asr = WhisperModel("small", device="cpu", compute_type="int8", cpu_threads=2)

def synth(v, text, ns, nw, ls):
    cfg = SynthesisConfig(noise_scale=ns, noise_w_scale=nw, length_scale=ls)
    return np.concatenate([ch.audio_float_array for ch in v.synthesize(text, syn_config=cfg)])

def mos(a):
    with torch.no_grad(): return float(utmos(torch.from_numpy(a).float().unsqueeze(0), SR).item())

def words(s):
    s = s.lower() if EN else normalize(s).lower()
    return re.findall(r"[\wáéíóúâêôãõçà]+", s)

def wer(a, ref):
    hyp = " ".join(seg.text for seg in asr.transcribe(a.astype(np.float32) if SR == 16000 else _rs(a), language="en" if EN else "pt", beam_size=1)[0])
    r, h = words(ref), words(hyp)
    d = list(range(len(h) + 1))
    for i, rw in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, hw in enumerate(h, 1): prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (rw != hw))
    return d[len(h)] / max(1, len(r))

def _rs(a):
    import scipy.signal as ss
    return ss.resample_poly(a, 16000, SR).astype(np.float32)

def score(vname, ns, nw, ls, norm):
    ms, ws = [], []
    for s in EVAL:
        a = synth(voices[vname], normalize(s) if (norm and not EN) else s, ns, nw, ls)
        ms.append(mos(a)); ws.append(wer(a, s))
    return round(float(np.mean(ms)), 3), round(float(np.mean(ws)), 3)

res = {"voice": VOICE, "sr": SR, "size_fp32_mb": round(os.path.getsize(m) / 1e6, 1), "size_int8_mb": round(os.path.getsize(q) / 1e6, 1)}
base = score("fp32", 0.667, 0.8, 1.0, False); res["original"] = {"utmos": base[0], "wer": base[1]}; log("original", base)
grid = []
for ns in (0.4, 0.55, 0.667, 0.8):
    for nw in (0.5, 0.8, 1.0):
        mo, we = score("fp32", ns, nw, 1.0, True); grid.append({"ns": ns, "nw": nw, "ls": 1.0, "utmos": mo, "wer": we}); log(grid[-1])
best = max(grid, key=lambda g: g["utmos"] - 3 * g["wer"])
for ls in (0.92, 1.08):
    mo, we = score("fp32", best["ns"], best["nw"], ls, True); grid.append({"ns": best["ns"], "nw": best["nw"], "ls": ls, "utmos": mo, "wer": we}); log(grid[-1])
best = max(grid, key=lambda g: g["utmos"] - 3 * g["wer"])
mo8, we8 = score("int8", best["ns"], best["nw"], best["ls"], True)
res.update(grid=grid, best=best, int8={"utmos": mo8, "wer": we8})

# Latência (frase curta, aquecido) e RAM em processo isolado
bench = r'''
import sys,time,resource
from piper import PiperVoice, SynthesisConfig
v=PiperVoice.load(sys.argv[1],config_path=sys.argv[2]); cfg=SynthesisConfig(noise_scale=float(sys.argv[4]),noise_w_scale=float(sys.argv[5]),length_scale=float(sys.argv[6]))
list(v.synthesize(sys.argv[3],syn_config=cfg)); t=time.perf_counter(); list(v.synthesize(sys.argv[3],syn_config=cfg)); f=time.perf_counter()-t
print(round(f,3), round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1024,1))'''
for tag, path in (("fp32", m), ("int8", q)):
    o = subprocess.run(["taskset", "-c", "0,1", sys.executable, "-c", bench, path, c, SHORT, str(best["ns"]), str(best["nw"]), str(best["ls"])], capture_output=True, text=True)
    try: f, r = o.stdout.split(); res[f"latency_{tag}"] = {"first_audio_s": float(f), "peak_ram_mb": float(r)}
    except Exception: log(o.stderr[-500:])

def save(name, a):
    a = a / max(1e-6, float(np.max(np.abs(a)))) * 0.95
    sf.write(f"{OUT}/{name}.wav", a, SR)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", f"{OUT}/{name}.wav", "-b:a", "128k", f"{OUT}/{name}.mp3"]); os.remove(f"{OUT}/{name}.wav")
save(f"{VOICE}_antes", synth(voices["fp32"], TEXT, 0.667, 0.8, 1.0))
save(f"{VOICE}_depois", synth(voices["int8"], TEXT if EN else normalize(TEXT), best["ns"], best["nw"], best["ls"]))
json.dump(res, open(f"{OUT}/report_{VOICE}.json", "w"), indent=2, ensure_ascii=False)
log(json.dumps(res, ensure_ascii=False))
