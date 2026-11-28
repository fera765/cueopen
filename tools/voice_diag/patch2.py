"""Correção completa no grafo para o sherpa-onnx 1.10.46 falar como o Piper:
1) PAD logo após o início (^ _ …);  2) ditongos (aɪ aʊ ɔɪ eɪ oʊ) que o sherpa quebra em duas letras viram o símbolo único.
Uso: patch2.py <model_in> <model_out> <tokens.txt>"""
import sys
sys.path.insert(0, "/tmp/v3tools")
from patch_pad import Model, Node, const, ATTR_INT

DIPH = {"aɪ": 161, "aʊ": 162, "ɔɪ": 163, "eɪ": 164, "oʊ": 165}

def tokens(path):
    t = {}
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line: continue
        idx = line.rsplit(" ", 1); t[idx[0] if idx[0] else " "] = int(idx[1])
    return t

def patch(src, dst, tok_path, pad_id=0):
    tok = tokens(tok_path)
    m = Model(); m.ParseFromString(open(src, "rb").read())
    assert not any(n.name.startswith("_pf_") for n in m.graph.node), "já corrigido"
    for n in m.graph.node:
        for i, x in enumerate(n.input):
            if x == "input": n.input[i] = "_pf_final"
            elif x == "input_lengths": n.input[i] = "_pf_lengths"
    new = [const("_pf_s0", [1], [0]), const("_pf_s1", [1], [1]), const("_pf_s2", [1], [2]), const("_pf_end", [1], [2 ** 62]),
           const("_pf_m1", [1], [-1]), const("_pf_m2", [1], [-2]), const("_pf_ax", [1], [1]), const("_pf_flat", [1], [-1]),
           const("_pf_pad", [1, 1], [pad_id]), const("_pf_neg2", [1, 2], [-1, -1]), const("_pf_zero1", [1, 1], [0]), const("_pf_zero2", [1, 2], [0, 0]),
           Node(input=["input", "_pf_s0", "_pf_s1", "_pf_ax"], output=["_pf_first"], name="_pf_first", op_type="Slice"),
           Node(input=["input", "_pf_s1", "_pf_end", "_pf_ax"], output=["_pf_rest"], name="_pf_rest", op_type="Slice"),
           Node(input=["_pf_first", "_pf_pad", "_pf_rest"], output=["_pf_x0"], name="_pf_concat", op_type="Concat")]
    new[-1].attribute.add(name="axis", type=ATTR_INT, i=1)
    x = "_pf_x0"; k = 0
    for sym, did in DIPH.items():
        a, b = tok.get(sym[0]), tok.get(sym[1])
        if a is None or b is None: continue
        p = f"_pf_d{k}_"; k += 1
        def N(op, ins, out, **attrs):
            nd = Node(input=ins, output=[p + out], name=p + out, op_type=op)
            for kk, vv in attrs.items(): nd.attribute.add(name=kk, type=ATTR_INT, i=vv)
            new.append(nd); return p + out
        new += [const(p + "A", [1, 1], [a]), const(p + "B", [1, 1], [b]), const(p + "ID", [1, 1], [did])]
        tail2 = N("Slice", [x, "_pf_s2", "_pf_end", "_pf_ax"], "tail2")
        sh2 = N("Concat", [tail2, "_pf_neg2"], "sh2", axis=1)                     # x[i+2]
        eqa = N("Equal", [x, p + "A"], "eqa"); eqb = N("Equal", [sh2, p + "B"], "eqb")
        first = N("And", [eqa, eqb], "first")
        rep = N("Where", [first, p + "ID", x], "rep")
        fi = N("Cast", [first], "fi", to=7)                                        # bool → int64 para deslocar
        h1 = N("Slice", [fi, "_pf_s0", "_pf_m1", "_pf_ax"], "h1"); d1 = N("Concat", ["_pf_zero1", h1], "d1", axis=1)
        h2 = N("Slice", [fi, "_pf_s0", "_pf_m2", "_pf_ax"], "h2"); d2 = N("Concat", ["_pf_zero2", h2], "d2", axis=1)
        dsum = N("Add", [d1, d2], "dsum"); keep = N("Equal", [dsum, "_pf_zero1"], "keep")
        keep1 = N("Reshape", [keep, "_pf_flat"], "keep1")
        x = N("Compress", [rep, keep1], "x", axis=1)
    new += [Node(input=[x], output=["_pf_final"], name="_pf_final", op_type="Identity"),
            Node(input=["_pf_final"], output=["_pf_shape"], name="_pf_shape", op_type="Shape"),
            Node(input=["_pf_shape", "_pf_s1", "_pf_s2", "_pf_s0"], output=["_pf_lengths"], name="_pf_lengths", op_type="Slice")]
    nodes = list(m.graph.node); del m.graph.node[:]
    m.graph.node.extend(new + nodes)
    open(dst, "wb").write(m.SerializeToString())
    return k

if __name__ == "__main__":
    print("ditongos:", patch(sys.argv[1], sys.argv[2], sys.argv[3]))
