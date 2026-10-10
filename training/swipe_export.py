# SPDX-License-Identifier: GPL-3.0-only
"""Writes a trained swipe encoder as the files the swipe library loads: model_fp32.pte and metadata.json.

    python swipe_export.py RUN_DIR/best.pt OUT_DIR [--name NAME]

Needs executorch (the release the library is built against, 1.2.0). The parts XNNPACK can run are
handed to it, the rest runs on ExecuTorch's own operators; the library is built with both.
"""
import argparse, json, os
import torch
from executorch.exir import to_edge_transform_and_lower
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
from swipe_model import SwipeEncoder, POINTS, MAX_KEYS

p = argparse.ArgumentParser()
p.add_argument("weights")
p.add_argument("out")
p.add_argument("--name", default="caret_swipe_encoder")
p.add_argument("--plain", action="store_true", help="no XNNPACK, only ExecuTorch's own operators")
args = p.parse_args()
os.makedirs(args.out, exist_ok=True)

model = SwipeEncoder()
model.load_state_dict(torch.load(args.weights, map_location="cpu"))
model.eval()
example = (torch.rand(1, 2, POINTS), torch.rand(1, MAX_KEYS, 2), torch.ones(1, MAX_KEYS, dtype=torch.bool))
program = torch.export.export(model, example)
lowered = to_edge_transform_and_lower(program, partitioner=[] if args.plain else [XnnpackPartitioner()])
executorch = lowered.to_executorch()
with open(os.path.join(args.out, "model_fp32.pte"), "wb") as f:
    f.write(executorch.buffer)
with torch.no_grad():
    want = model(*example)[0]
json.dump({
    "kind": "encoder",
    "codename": args.name,
    "license": "GPL-3.0-only",
    "trained_on": "futo-org/swipe.futo.org (MIT)",
    "input_shapes": {"features": [1, 2, POINTS], "layout_keys": [1, MAX_KEYS, 2], "layout_mask": [1, MAX_KEYS]},
    "max_keys": MAX_KEYS,
    # how the library weighs word length and word frequency against the encoder; to be tuned with the benchmark
    "scoring": {"gamma": 0.1017, "lambda": 0.0373, "beta": 2.1745},
    # the library takes the width of the coefficients output from here (freq_x times freq_y): one, of zeros
    "model_config": {"freq_x": 1, "freq_y": 1},
    "parameter_counts": {"total": sum(p.numel() for p in model.parameters())},
}, open(os.path.join(args.out, "metadata.json"), "w"), indent=2)
print("wrote", os.path.join(args.out, "model_fp32.pte"), os.path.getsize(os.path.join(args.out, "model_fp32.pte")), "bytes;",
      "first emissions", [round(v, 3) for v in want[0, 0, :3].tolist()])
