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

