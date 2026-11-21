import sys, os, zipfile, json, sherpa_onnx
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "voice_lab3"))
from normalize_pt import normalize
TXT = {"faber": ["Quer que eu prepare um rascunho?", "Prontinho, criei o fluxo.", "Achei o arquivo."], "cadu": ["Encontrei três mensagens.", "Achei o arquivo."],
       "ryan": ["I found three messages.", "Your meeting with the sales team."]}
for vid, ts in TXT.items():
    d = f"ex/piper2-{vid}-int8"; zipfile.ZipFile(f"pk/piper2-{vid}-int8.zip").extractall("ex")
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/model.onnx", lexicon="", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data"), num_threads=2, debug=True)))
    tok = {}
    for line in open(f"{d}/tokens.txt", encoding="utf-8"):
        line = line.rstrip("\n"); sym, i = line[:-len(line.split()[-1])].rstrip(" ") or " ", int(line.split()[-1]); tok[i] = sym
    print("TOKMAP", vid, json.dumps(tok, ensure_ascii=False) if False else len(tok), flush=True)
    for t in ts:
        print("SHERPA-TEXT", vid, repr(t), flush=True); tts.generate(normalize(t) if vid != "ryan" else t, sid=0, speed=1.0)
