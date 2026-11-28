import json
from huggingface_hub import hf_hub_download
P = {"faber": "pt/pt_BR/faber/medium/pt_BR-faber-medium", "cadu": "pt/pt_BR/cadu/medium/pt_BR-cadu-medium", "ryan": "en/en_US/ryan/medium/en_US-ryan-medium", "lessac": "en/en_US/lessac/medium/en_US-lessac-medium"}
for vid, path in P.items():
    cfg = json.load(open(hf_hub_download("rhasspy/piper-voices", path + ".onnx.json")))
    multi = {k: v[0] for k, v in cfg["phoneme_id_map"].items() if len(k) > 1}
    print("MULTI", vid, json.dumps(multi, ensure_ascii=False), "num_symbols", cfg.get("num_symbols"), "phoneme_type", cfg.get("phoneme_type"), flush=True)
