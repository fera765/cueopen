import sys, numpy as np, onnxruntime as ort
orig, fixed = sys.argv[1], sys.argv[2]
piper = [1, 0, 14, 0, 96, 0, 120, 0, 164, 0, 119, 0, 3, 0, 33, 0, 3, 0, 121, 0, 14, 0, 92, 0, 23, 0, 120, 0, 21, 0, 34, 0, 100, 0, 10, 0, 2]
split = {161: (14, 74), 162: (14, 100), 163: (54, 74), 164: (18, 74), 165: (27, 100)}
sher = [1]
for t in piper[2:]:
    if t in split: a, b = split[t]; sher += [a, 0, b]
    else: sher.append(t)
# formato do sherpa: ^ p _ p _ …  (sem PAD após ^; ditongo em 2 letras)
def run(path, seq):
    s = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    feed = {"input": np.array([seq], dtype=np.int64), "input_lengths": np.array([len(seq)], dtype=np.int64), "scales": np.array([0.0, 1.0, 0.0], dtype=np.float32)}
    if "sid" in [i.name for i in s.get_inputs()]: feed["sid"] = np.array([0], dtype=np.int64)
    return s.run(None, feed)[0].flatten()
a = run(orig, piper); b = run(fixed, sher); c = run(orig, sher)
n = min(len(a), len(b))
print("piper:", len(a), "corrigido:", len(b), "dif máx:", float(np.abs(a[:n] - b[:n]).max()), "| sem correção:", len(c))
assert len(a) == len(b) and np.allclose(a, b, atol=1e-4); print("OK: idêntico ao Piper")
