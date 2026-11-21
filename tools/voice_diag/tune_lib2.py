"""Ajuste para 0% de palavras erradas (vozes leves v3 do branch voices, motor do app sherpa-onnx 1.10.46).
Cada combinação é avaliada com 24 frases; as 3 melhores são confirmadas com 2 gerações por frase (a voz tem
variação aleatória). Whisper small, beam 5. Imprime as palavras ainda erradas para corrigir no normalizador."""
import json, os, re, sys, zipfile
import numpy as np, soundfile as sf, sherpa_onnx
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "voice_lab3"))
from normalize_pt import normalize
from faster_whisper import WhisperModel
from num2words import num2words

VID = sys.argv[1]; PT = VID in ("cadu", "faber")
SENT_PT = ["Olá! Tudo bem por aí? Eu sou o seu assistente.", "Estou aqui para ajudar com o que você precisar, é só falar.",
           "Prontinho, criei o fluxo que vai monitorar os seus e-mails a cada meia hora.", "Encontrei três mensagens importantes do seu cliente de São Paulo.",
           "A reunião com a equipe de vendas é amanhã às 10h30.", "O orçamento ficou em R$ 1.250,90, com 25% de desconto.",
           "Quer que eu prepare um rascunho da resposta para você revisar?", "Já liguei o lembrete para as quatro e meia da tarde.",
           "Não consegui acessar o site agora, vou tentar de novo em alguns minutos.", "Pode deixar, vou cuidar disso e te aviso quando terminar.",
           "O tempo amanhã deve ficar nublado, com chance de chuva no fim do dia.", "Achei o arquivo na pasta de documentos e já enviei para você.",
           "Bom dia! Você tem duas tarefas pendentes para hoje.", "O pagamento foi confirmado e o recibo já está no seu e-mail.",
           "Vou abrir o navegador e pesquisar os preços mais baratos.", "A bateria do celular está em vinte por cento, quer ativar a economia?",
           "Seu pedido chega na quinta-feira, entre nove e dezoito horas.", "Desculpe, não entendi. Pode repetir, por favor?",
           "Criei uma automação que responde os clientes no WhatsApp.", "O relatório de vendas do mês passado mostra um crescimento de doze por cento.",
           "Encontrei um voo para o Rio de Janeiro por quatrocentos reais.", "Lembre-se de tomar água e fazer uma pausa a cada hora.",
           "A música está tocando na sala, quer que eu aumente o volume?", "Tudo certo! Terminei a tarefa e salvei o resultado na pasta."]
SENT_EN = ["Hello! How are you doing? I'm your assistant.", "I'm here to help with whatever you need, just ask.",
           "Done, I created the workflow that checks your emails every thirty minutes.", "I found three important messages from your client in New York.",
           "Your meeting with the sales team is tomorrow at ten thirty.", "The quote came in at one thousand two hundred and fifty dollars.",
           "Would you like me to draft a reply for you to review?", "I've set the reminder for four thirty in the afternoon.",
           "I couldn't reach the website right now, I'll try again in a few minutes.", "Leave it to me, I'll take care of it and let you know when it's done.",
           "Tomorrow should be cloudy, with a chance of rain in the evening.", "I found the file in your documents folder and sent it to you.",
           "Good morning! You have two pending tasks for today.", "The payment was confirmed and the receipt is in your inbox.",
           "I'll open the browser and look for the cheapest prices.", "Your battery is at twenty percent, should I turn on power saving?",
           "Your order arrives on Thursday, between nine and six.", "Sorry, I didn't catch that. Could you say it again, please?",
           "I built an automation that replies to customers on WhatsApp.", "Last month's sales report shows twelve percent growth.",
           "I found a flight to Chicago for four hundred dollars.", "Remember to drink water and take a short break every hour.",
           "The music is playing in the living room, should I turn it up?", "All set! I finished the task and saved the result in the folder."]
SENT = SENT_PT if PT else SENT_EN
pkg = f"piper2-{VID}-int8"; zipfile.ZipFile(f"pk/{pkg}.zip").extractall("ex"); d = f"ex/{pkg}"
asr = WhisperModel("medium", device="cpu", compute_type="int8", cpu_threads=4)

def words(s):
    if PT:
        s = normalize(s).lower().replace("whatsapp", "uótsap")
    else:
        s = re.sub(r"\$\s?([\d,]+)", lambda m: m.group(1).replace(",", "") + " dollars", s).replace(",", "")
        s = re.sub(r"(\d+)[.:](\d+)", lambda m: num2words(int(m.group(1))) + " " + num2words(int(m.group(2))), s)
        s = re.sub(r"(\d+)%", lambda m: m.group(1) + " percent", s)
        s = re.sub(r"\d+", lambda m: num2words(int(m.group(0))), s).replace("-", " ").lower()
        s = s.replace("emails", "e mails").replace("email", "e mail").replace("whatsapp", "whats app")
    return re.findall(r"[a-záéíóúâêôãõçà]+", s.replace("-", " "))

def wer(ref, hyp):
    r, h = words(ref), words(hyp); dp = list(range(len(h) + 1))
    for i, rw in enumerate(r, 1):
        prev, dp[0] = dp[0], i
        for j, hw in enumerate(h, 1): prev, dp[j] = dp[j], min(dp[j] + 1, dp[j - 1] + 1, prev + (rw != hw))
    return dp[len(h)], len(r)

