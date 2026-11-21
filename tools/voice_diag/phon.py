"""Compara os IDs de fonemas: Piper oficial x sherpa-onnx 1.10.46 (debug imprime os tokens)."""
import sys, os, json
from piper import PiperVoice
from huggingface_hub import hf_hub_download
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "voice_lab3"))
from normalize_pt import normalize
P = {"faber": "pt/pt_BR/faber/medium/pt_BR-faber-medium", "cadu": "pt/pt_BR/cadu/medium/pt_BR-cadu-medium", "ryan": "en/en_US/ryan/medium/en_US-ryan-medium"}
TXT = {"faber": ["Quer que eu prepare um rascunho?", "Prontinho, criei o fluxo.", "Achei o arquivo."], "cadu": ["Encontrei três mensagens.", "Achei o arquivo."],
       "ryan": ["I found three messages.", "Your meeting with the sales team."]}
for vid, path in P.items():
    m = hf_hub_download("rhasspy/piper-voices", path + ".onnx"); c = hf_hub_download("rhasspy/piper-voices", path + ".onnx.json")
    v = PiperVoice.load(m, config_path=c)
    for t in TXT[vid]:
        ph = v.phonemize(normalize(t) if vid != "ryan" else t)
        ids = [v.phonemes_to_ids(p) for p in ph]
        print("PIPER", vid, repr(t), "|", "".join("".join(p) for p in ph), "|", ids, flush=True)
