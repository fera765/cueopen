"""Insere o PAD após o BOS dentro de um modelo ONNX Piper (sem a lib onnx: descritores protobuf mínimos;
campos não declarados são preservados como 'unknown fields')."""
import sys
from google.protobuf import descriptor_pb2, descriptor_pool, message_factory

fdp = descriptor_pb2.FileDescriptorProto(name="mini_onnx.proto", package="mini", syntax="proto2")
def msg(name, fields):
    m = fdp.message_type.add(name=name)
    for fname, num, typ, label, tname in fields:
        f = m.field.add(name=fname, number=num, type=typ, label=label)
        if tname: f.type_name = ".mini." + tname
T = descriptor_pb2.FieldDescriptorProto
OPT, REP = T.LABEL_OPTIONAL, T.LABEL_REPEATED
msg("TensorProto", [("dims", 1, T.TYPE_INT64, REP, None), ("data_type", 2, T.TYPE_INT32, OPT, None),
                    ("int64_data", 7, T.TYPE_INT64, REP, None), ("name", 8, T.TYPE_STRING, OPT, None)])
msg("AttributeProto", [("name", 1, T.TYPE_STRING, OPT, None), ("i", 3, T.TYPE_INT64, OPT, None),
                       ("t", 5, T.TYPE_MESSAGE, OPT, "TensorProto"), ("type", 20, T.TYPE_INT32, OPT, None)])
msg("NodeProto", [("input", 1, T.TYPE_STRING, REP, None), ("output", 2, T.TYPE_STRING, REP, None), ("name", 3, T.TYPE_STRING, OPT, None),
                  ("op_type", 4, T.TYPE_STRING, OPT, None), ("attribute", 5, T.TYPE_MESSAGE, REP, "AttributeProto"), ("domain", 7, T.TYPE_STRING, OPT, None)])
msg("GraphProto", [("node", 1, T.TYPE_MESSAGE, REP, "NodeProto")])
msg("ModelProto", [("graph", 7, T.TYPE_MESSAGE, OPT, "GraphProto")])
pool = descriptor_pool.DescriptorPool(); pool.Add(fdp)
get = lambda n: message_factory.GetMessageClass(pool.FindMessageTypeByName("mini." + n))
Model, Node, Attr = get("ModelProto"), get("NodeProto"), get("AttributeProto")
INT64, ATTR_TENSOR, ATTR_INT = 7, 4, 2

def const(name, dims, vals):
    n = Node(output=[name], name=name, op_type="Constant")
    a = n.attribute.add(name="value", type=ATTR_TENSOR); a.t.dims.extend(dims); a.t.data_type = INT64; a.t.int64_data.extend(vals)
    return n

def patch(src, dst, pad_id=0):
    m = Model(); m.ParseFromString(open(src, "rb").read())
    used = sum(1 for n in m.graph.node for x in n.input if x in ("input", "input_lengths"))
    assert used > 0, "modelo sem entradas input/input_lengths"
    assert not any(n.name.startswith("_pf_") for n in m.graph.node), "já corrigido"
    for n in m.graph.node:
        for i, x in enumerate(n.input):
            if x == "input": n.input[i] = "_pf_input"
            elif x == "input_lengths": n.input[i] = "_pf_lengths"
    new = [const("_pf_s0", [1], [0]), const("_pf_s1", [1], [1]), const("_pf_end", [1], [2 ** 62]), const("_pf_ax", [1], [1]),
           const("_pf_pad", [1, 1], [pad_id]),
           Node(input=["input", "_pf_s0", "_pf_s1", "_pf_ax"], output=["_pf_first"], name="_pf_first", op_type="Slice"),
           Node(input=["input", "_pf_s1", "_pf_end", "_pf_ax"], output=["_pf_rest"], name="_pf_rest", op_type="Slice"),
           Node(input=["_pf_first", "_pf_pad", "_pf_rest"], output=["_pf_input"], name="_pf_concat", op_type="Concat"),
           Node(input=["input_lengths", "_pf_s1"], output=["_pf_lengths"], name="_pf_add", op_type="Add")]
    new[7].attribute.add(name="axis", type=ATTR_INT, i=1)
    nodes = list(m.graph.node); del m.graph.node[:]
    m.graph.node.extend(new + nodes)
    open(dst, "wb").write(m.SerializeToString())
    return used

if __name__ == "__main__":
    print("usos trocados:", patch(sys.argv[1], sys.argv[2]))
