"""Prepara o conjunto de treino: escolhe no CML-TTS (pt, CC BY 4.0) a locutora/locutor com mais horas, filtra trechos
de 2–12 s, reamostra para 22050 Hz e grava wavs/ + metadata.csv (formato do Piper: arquivo|texto)."""
import os, sys, json, collections, re
import numpy as np, soundfile as sf
from datasets import load_dataset, Audio
import scipy.signal as ss

REPO = "freds0/cml_tts_dataset_portuguese"
OUT = "ds"; os.makedirs(f"{OUT}/wavs", exist_ok=True)
MAX_H = float(os.environ.get("MAX_HOURS", "2.0"))
ds = load_dataset(REPO, split="train", streaming=True)
first = next(iter(ds)); print("colunas:", {k: (type(v).__name__) for k, v in first.items() if k != "audio"}, flush=True)
spk_col = next(c for c in ("speaker_id", "speaker", "client_id", "spk_id", "spk") if c in first)
txt_col = next(c for c in ("text", "transcription", "sentence", "normalized_text") if c in first)
gen_col = next((c for c in ("gender", "sex") if c in first), None)
print("speaker:", spk_col, "texto:", txt_col, "gênero:", gen_col, flush=True)

# 1ª passada (sem áudio): horas por locutor
dur = collections.Counter(); gender = {}
meta = load_dataset(REPO, split="train", streaming=True).remove_columns(["audio"]) if "duration" in first else None
it = meta if meta is not None else load_dataset(REPO, split="train", streaming=True)
for i, r in enumerate(it):
    d = r.get("duration") or (len(r["audio"]["array"]) / r["audio"]["sampling_rate"])
    dur[r[spk_col]] += d
    if gen_col: gender[r[spk_col]] = r[gen_col]
    if i > 60000: break
top = dur.most_common(12)
print("TOP", [(s, round(h / 3600, 2), gender.get(s)) for s, h in top], flush=True)
pref = os.environ.get("GENDER", "F").upper()[:1]
cands = [s for s, h in top if not gen_col or str(gender.get(s, "")).upper().startswith(pref)] or [top[0][0]]
spk = cands[0]; print("ESCOLHIDO", spk, round(dur[spk] / 3600, 2), "h", gender.get(spk), flush=True)

# 2ª passada: só esse locutor
rows = []; total = 0.0
for r in load_dataset(REPO, split="train", streaming=True):
    if r[spk_col] != spk: continue
    a = r["audio"]; x = np.asarray(a["array"], dtype=np.float32); sr = a["sampling_rate"]; d = len(x) / sr
    t = re.sub(r"\s+", " ", str(r[txt_col])).strip()
    if not (2.0 <= d <= 12.0) or len(t) < 8: continue
    if sr != 22050: x = ss.resample_poly(x, 22050, sr).astype(np.float32)
    x = x / max(1e-6, float(np.abs(x).max())) * 0.9
    name = f"u{len(rows):05d}.wav"; sf.write(f"{OUT}/wavs/{name}", x, 22050, subtype="PCM_16")
    rows.append(f"{name}|{t}"); total += d
    if total >= MAX_H * 3600: break
open(f"{OUT}/metadata.csv", "w", encoding="utf-8").write("\n".join(rows) + "\n")
json.dump({"repo": REPO, "license": "CC BY 4.0", "speaker": str(spk), "gender": str(gender.get(spk)), "hours": round(total / 3600, 2), "clips": len(rows)},
          open(f"{OUT}/info.json", "w"), ensure_ascii=False)
print("PRONTO", len(rows), "trechos", round(total / 3600, 2), "h", flush=True)
