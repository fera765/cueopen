"""Testa grafias alternativas para palavras que a voz pronuncia mal (só o texto enviado à voz muda).
Juiz: Whisper medium. Cada variante: 3 frases x 3 gerações por voz; conta se a palavra-alvo foi entendida."""
import json, os, re, sys, zipfile
import numpy as np, soundfile as sf, sherpa_onnx
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "voice_lab3"))
from normalize_pt import normalize
from faster_whisper import WhisperModel
VID = sys.argv[1]; PT = VID in ("cadu", "faber")
P = {"cadu": (0.5, 0.5, 1.08), "faber": (0.333, 0.5, 1.08), "lessac": (0.333, 0.8, 1.0), "ryan": (0.333, 0.8, 1.15)}[VID]
TESTS_PT = {  # palavra-alvo: (frases com {w}, variantes)
    "criei": (["Prontinho, {w} o fluxo que vai monitorar os seus e-mails.", "{w} uma automação para você.", "Eu {w} a tarefa agora mesmo."],
              ["criei", "criêi", "criiêi", "cri-ei", "criei mesmo"]),
    "recibo": (["O recibo já está no seu e-mail.".replace("recibo", "{w}"), "Enviei o {w} do pagamento.", "Guarde o {w}, por favor."],
               ["recibo", "recíbo", "rressíbo", "recibu"]),
    "lembre": (["{w}-se de tomar água.", "Por favor, {w}-se da reunião.", "{w}-se de mim amanhã."], ["Lembre", "Lêmbre", "Lémbre"]),
    "tarde": (["Já liguei o lembrete para as quatro e meia da {w}.", "Boa {w}! Tudo certo?", "Vou fazer isso à {w}."], ["tarde", "tárde", "tardi"]),
}
TESTS_EN = {
    "tasks": (["You have two pending {w} for today.", "I finished all the {w}.", "These {w} are done."], ["tasks", "tahsks", "task s"]),
    "saved": (["I saved the result in the folder.".replace("saved", "{w}"), "I {w} your file.", "Everything was {w}."], ["saved", "saaved", "save'd"]),
}
TESTS = TESTS_PT if PT else TESTS_EN
pkg = f"piper2-{VID}-int8"; zipfile.ZipFile(f"pk/{pkg}.zip").extractall("ex"); d = f"ex/{pkg}"
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/model.onnx", lexicon="", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data",
                                               noise_scale=P[0], noise_scale_w=P[1], length_scale=P[2]), num_threads=4), max_num_sentences=1))
asr = WhisperModel("medium", device="cpu", compute_type="int8", cpu_threads=4)
out = {}
for target, (sents, variants) in TESTS.items():
    for v in variants:
        ok = n = 0; heard = []
        for s in sents:
            for _ in range(3):
                txt = s.format(w=v); txt = normalize(txt) if PT else txt
                g = tts.generate(txt, sid=0, speed=1.0); sr = g.sample_rate
                sf.write("tmp.wav", np.concatenate([np.zeros(int(sr * 0.3), dtype=np.float32), np.array(g.samples, dtype=np.float32)]), sr)
                hyp = " ".join(x.text for x in asr.transcribe("tmp.wav", language="pt" if PT else "en", beam_size=5)[0]).strip()
                good = re.search(r"\b" + target + r"\b", hyp.lower()) is not None
                ok += good; n += 1
                if not good: heard.append(hyp)
        out.setdefault(target, []).append({"variant": v, "acertos": f"{ok}/{n}", "errados": heard[:3]})
        print(VID, target, repr(v), f"{ok}/{n}", heard[:2], flush=True)
print("RESULT " + json.dumps({"voice": VID, "res": out}, ensure_ascii=False), flush=True)
