"""Normalizador de texto pt-BR para TTS (espelho do que vai para o app em Kotlin)."""
import re

UN = "zero um dois três quatro cinco seis sete oito nove dez onze doze treze catorze quinze dezesseis dezessete dezoito dezenove".split()
DEZ = "_ _ vinte trinta quarenta cinquenta sessenta setenta oitenta noventa".split()
CEN = "_ cento duzentos trezentos quatrocentos quinhentos seiscentos setecentos oitocentos novecentos".split()

def _ate999(n, fem=False):
    if n == 100: return "cem"
    p = []
    c, r = divmod(n, 100)
    if c: p.append(CEN[c])
    if r:
        if r < 20: w = UN[r]
        else:
            d, u = divmod(r, 10); w = DEZ[d] + (" e " + UN[u] if u else "")
        p.append(w)
    s = " e ".join(p)
    if fem: s = re.sub(r"\bum\b", "uma", re.sub(r"\bdois\b", "duas", s)).replace("ntos", "ntas")
    return s

def num(n, fem=False):
    n = int(n)
    if n < 0: return "menos " + num(-n, fem)
    if n == 0: return "zero"
    parts = []
    for val, sg, pl in [(10**9, "um bilhão", "bilhões"), (10**6, "um milhão", "milhões"), (1000, "mil", "mil")]:
        q, n = divmod(n, val)
        if q: parts.append(sg if q == 1 else f"{_ate999(q) if q < 1000 else num(q)} {pl}")
    if n: parts.append(_ate999(n, fem))
    if len(parts) > 1 and (n < 100 or n % 100 == 0) and n: return ", ".join(parts[:-1]) + " e " + parts[-1]
    return " ".join(parts)

ORD = {1: "primeiro", 2: "segundo", 3: "terceiro", 4: "quarto", 5: "quinto", 6: "sexto", 7: "sétimo", 8: "oitavo", 9: "nono", 10: "décimo"}
MES = "janeiro fevereiro março abril maio junho julho agosto setembro outubro novembro dezembro".split()
ABBR = {"Sr.": "senhor", "Sra.": "senhora", "Dr.": "doutor", "Dra.": "doutora", "Av.": "avenida", "nº": "número", "n.º": "número",
        "km": "quilômetros", "kg": "quilos", "min": "minutos", "etc.": "etcétera", "obs.": "observação", "tel.": "telefone",
        "vc": "você", "pq": "porque", "tb": "também", "WhatsApp": "uótsap", "e-mails": "i-meils", "emails": "i-meils", "e-mail": "i-meil", "email": "i-meil", "OK": "oquei", "ok": "oquei"}

def _int(s): return int(s.replace(".", ""))

def normalize(t: str) -> str:
    t = re.sub(r"https?://\S+", "um link", t)
    t = re.sub(r"[\w.+-]+@[\w-]+\.[\w.]+", lambda m: m.group(0).replace("@", " arroba ").replace(".", " ponto "), t)
    t = re.sub(r"[\U0001F300-\U0001FAFF☀-➿]", "", t)
    t = re.sub(r"[*_#`>]+", " ", t)
    def money(m):
        r, c = _int(m.group(1)), int((m.group(2) or "0").ljust(2, "0")[:2])
        s = f"{num(r)} {'real' if r == 1 else 'reais'}" if r else ""
        if c: s += (" e " if s else "") + f"{num(c)} {'centavo' if c == 1 else 'centavos'}"
        return s or "zero reais"
    t = re.sub(r"R\$\s?(\d{1,3}(?:\.\d{3})*|\d+)(?:,(\d{1,2}))?", money, t)
    t = re.sub(r"\b(\d{1,2})h(\d{2})?\b|\b(\d{1,2}):(\d{2})\b",
               lambda m: (lambda h, mi: f"{num(h, True)} {'hora' if h == 1 else 'horas'}" + (f" e {num(mi)}" if mi else ""))(
                   int(m.group(1) or m.group(3)), int(m.group(2) or m.group(4) or 0)), t)
    t = re.sub(r"\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b",
               lambda m: (f"{'primeiro' if int(m.group(1)) == 1 else num(m.group(1))} de {MES[int(m.group(2)) - 1]}"
                          + (f" de {num(int(m.group(3)) if len(m.group(3)) == 4 else 2000 + int(m.group(3)))}" if m.group(3) else ""))
               if 1 <= int(m.group(2)) <= 12 else m.group(0), t)
    t = re.sub(r"(\d+(?:,\d+)?)\s?%", lambda m: f"{_dec(m.group(1))} por cento", t)
    t = re.sub(r"\b(\d{1,2})[ºª°]", lambda m: ORD.get(int(m.group(1)), num(m.group(1))) if "ª" not in m.group(0) else ORD.get(int(m.group(1)), num(m.group(1)))[:-1] + "a", t)
    for k, v in ABBR.items(): t = re.sub(r"(?<!\w)" + re.escape(k) + r"(?!\w)", v, t)
    t = re.sub(r"\b\d{1,3}(?:\.\d{3})+(?:,\d+)?\b|\b\d+,\d+\b|\b\d+\b", lambda m: _dec(m.group(0)), t)
    FEM = r"(horas?|semanas?|pessoas?|vez|vezes|mensagens?|tarefas?|reuniões|reunião|notificações|notificação|páginas?|semanas?|mil pessoas)"
    t = re.sub(r"\bum " + FEM, r"uma \1", t); t = re.sub(r"\bdois " + FEM, r"duas \1", t)
    t = re.sub(r"\b(duzentos|trezentos|quatrocentos|quinhentos|seiscentos|setecentos|oitocentos|novecentos)(?=( e [\wê ]+)? " + FEM + ")", lambda m: m.group(1)[:-2] + "as", t)
    return re.sub(r"\s+", " ", t).strip()

def _dec(s):
    if "," in s:
        a, b = s.split(",", 1); return f"{num(_int(a))} vírgula {' '.join(UN[int(ch)] for ch in b) if len(b) > 2 else num(int(b))}"
    return num(_int(s))

if __name__ == "__main__":
    for s in ["Sua reunião é às 10h30 com o Dr. Paulo.", "O total ficou R$ 1.250,90 (25% de desconto) no dia 15/12/2026.",
              "Chegaram 3 e-mails; o 2º é do cliente: joao.silva@empresa.com.br", "Faltam 2 horas, 21 min e 1.000.000 de passos!"]:
        print(normalize(s))
