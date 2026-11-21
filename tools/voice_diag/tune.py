"""Ajuste por Whisper (voz leve, exatamente como o app: sherpa-onnx 1.10.46 + int8 da release voices-v2).
Para cada combinação de parâmetros, gera frases típicas do assistente, transcreve e mede o erro por palavra (WER)
contra o texto enviado. Escolhe a combinação com menos erros e lista as palavras que ainda saem erradas."""
import json, os, re, sys, zipfile, subprocess
import numpy as np, soundfile as sf, sherpa_onnx
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "voice_lab3"))
from normalize_pt import normalize
from faster_whisper import WhisperModel

VID = sys.argv[1]; PT = VID in ("cadu", "faber")
SENT_PT = ["Olá! Tudo bem por aí? Eu sou o seu assistente.", "Estou aqui para ajudar com o que você precisar, é só falar.",
           "Prontinho, criei o fluxo que vai monitorar os seus e-mails a cada meia hora.", "Encontrei três mensagens importantes do seu cliente de São Paulo.",
           "A reunião com a equipe de vendas é amanhã às 10h30.", "O orçamento ficou em R$ 1.250,90, com 25% de desconto.",
           "Quer que eu prepare um rascunho da resposta para você revisar?", "Já liguei o lembrete para as quatro e meia da tarde.",
           "Não consegui acessar o site agora, vou tentar de novo em alguns minutos.", "Pode deixar, vou cuidar disso e te aviso quando terminar.",
           "O tempo amanhã deve ficar nublado, com chance de chuva no fim do dia.", "Achei o arquivo na pasta de documentos e já enviei para você."]
SENT_EN = ["Hello! How are you doing? I'm your assistant.", "I'm here to help with whatever you need, just ask.",
           "Done, I created the workflow that checks your emails every thirty minutes.", "I found three important messages from your client in New York.",
           "Your meeting with the sales team is tomorrow at ten thirty.", "The quote came in at one thousand two hundred and fifty dollars.",
           "Would you like me to draft a reply for you to review?", "I've set the reminder for four thirty in the afternoon.",
           "I couldn't reach the website right now, I'll try again in a few minutes.", "Leave it to me, I'll take care of it and let you know when it's done.",
           "Tomorrow should be cloudy, with a chance of rain in the evening.", "I found the file in your documents folder and sent it to you."]
SENT = SENT_PT if PT else SENT_EN
pkg = f"piper-{VID}-int8"; zipfile.ZipFile(f"pk/{pkg}.zip").extractall("ex"); d = f"ex/{pkg}"
asr = WhisperModel("small", device="cpu", compute_type="int8", cpu_threads=4)

from num2words import num2words
def words(s):
    if PT: s = normalize(s).lower()
    else:
        s = s.replace("$", " dollars ").replace(",", "")
        s = re.sub(r"(\d+)[.:](\d+)", lambda m: num2words(int(m.group(1))) + " " + num2words(int(m.group(2))), s)
        s = re.sub(r"\d+", lambda m: num2words(int(m.group(0))), s).replace("-", " ").lower()
    s = s.replace("e-mails", "i-meils").replace("emails", "i-meils")
    return re.findall(r"[a-záéíóúâêôãõçà0-9]+", s)

def wer(ref, hyp):
    r, h = words(ref), words(hyp); dp = list(range(len(h) + 1))
    for i, rw in enumerate(r, 1):
        prev, dp[0] = dp[0], i
        for j, hw in enumerate(h, 1): prev, dp[j] = dp[j], min(dp[j] + 1, dp[j - 1] + 1, prev + (rw != hw))
    return dp[len(h)], len(r)

def engine(ns, nw, ls):
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/model.onnx", lexicon="", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data",
                                                   noise_scale=ns, noise_scale_w=nw, length_scale=ls), num_threads=4), max_num_sentences=1))

PREFIX = ""
LEAD = 0.3  # silêncio antes da fala (o app também passa a tocar assim)
def run(ns, nw, ls, keep=False):
    t = engine(ns, nw, ls); errs = tot = 0; bad = []; audio = []
    for s in SENT:
        g = t.generate(PREFIX + (normalize(s) if PT else s), sid=0, speed=1.0)
        a = np.concatenate([np.zeros(int(g.sample_rate * LEAD), dtype=np.float32), np.array(g.samples, dtype=np.float32)]); sf.write("tmp.wav", a, g.sample_rate)
        hyp = " ".join(x.text for x in asr.transcribe("tmp.wav", language="pt" if PT else "en", beam_size=5)[0]).strip()
        e, n = wer(s, hyp); errs += e; tot += n
        if e: bad.append({"enviado": s, "whisper": hyp})
        if keep: audio += [a, np.zeros(int(g.sample_rate * 0.35), dtype=np.float32)]
    return errs / tot, bad, (np.concatenate(audio), g.sample_rate) if keep else None

grid = []
for ls in (1.0, 1.08, 1.15):
    for ns in (0.5, 0.667):
        for nw in (0.5, 0.8):
            w, bad, _ = run(ns, nw, ls); grid.append({"ns": ns, "nw": nw, "ls": ls, "wer": round(w, 4)}); print(VID, grid[-1], flush=True)
best = min(grid, key=lambda g: (g["wer"], abs(g["ls"] - 1.05)))
w, bad, (a, sr) = run(best["ns"], best["nw"], best["ls"], keep=True)
os.makedirs("out", exist_ok=True)
a = a / max(1e-6, float(np.abs(a).max())) * 0.95; sf.write(f"out/{VID}.wav", a, sr)
subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", f"out/{VID}.wav", "-b:a", "96k", f"out/teste-{VID}.mp3"]); os.remove(f"out/{VID}.wav")
json.dump({"voice": VID, "best": best, "wer": round(w, 4), "errors": bad, "grid": grid}, open(f"out/tune_{VID}.json", "w"), indent=2, ensure_ascii=False)
print(json.dumps({"voice": VID, "best": best, "errors": bad}, ensure_ascii=False, indent=1))
