# SPDX-License-Identifier: GPL-3.0-only
"""Trains Caret's typing language model: a small Llama (so llama.cpp can run it on the phone) on the
token files from build_data.py. Saves a checkpoint every few thousand steps and picks up where it left
off when started again.

    python train.py RUN_DIR [--tokens 1.5e9] [--batch 32] [--context 128]

Fine-tuning for typing (see typing_data.py) starts from a trained model and reads other token files:

    python train.py RUN_DIR --name typing --init RUN_DIR/model --tokens 2e8 --lr 3e-4

which trains on typing_train.bin, keeps its own checkpoint and saves to RUN_DIR/typing_model.
"""
import argparse, math, os, time
import numpy as np
import torch
from transformers import LlamaConfig, LlamaForCausalLM

p = argparse.ArgumentParser()
p.add_argument("run")
p.add_argument("--tokens", type=float, default=1.5e9)
p.add_argument("--batch", type=int, default=32)
p.add_argument("--context", type=int, default=128)
p.add_argument("--lr", type=float, default=1e-3)
p.add_argument("--every", type=int, default=2000)
p.add_argument("--name", default="", help="prefix of the token files, the checkpoint and the saved model")
p.add_argument("--init", default="", help="a saved model to start from")
args = p.parse_args()
torch.backends.cuda.matmul.allow_tf32 = True
device = "cuda"

config = LlamaConfig(
    vocab_size=16000, hidden_size=512, intermediate_size=1408, num_hidden_layers=8,
    num_attention_heads=8, num_key_value_heads=8, max_position_embeddings=512,
    rms_norm_eps=1e-5, tie_word_embeddings=True, bos_token_id=1, eos_token_id=2,
)
prefix = args.name + "_" if args.name else ""
train = np.memmap(os.path.join(args.run, prefix + "train.bin"), dtype=np.uint16, mode="r")
val = np.memmap(os.path.join(args.run, prefix + "val.bin"), dtype=np.uint16, mode="r")
steps = int(args.tokens / (args.batch * args.context))
warmup = 1000


def batch(data, rng):
    starts = rng.integers(0, len(data) - args.context - 1, args.batch)
    x = np.stack([data[s:s + args.context + 1].astype(np.int64) for s in starts])
    x = torch.from_numpy(x).to(device, non_blocking=True)
    return x[:, :-1], x[:, 1:]


def lr_at(step):
    if step < warmup:
        return args.lr * step / warmup
    progress = (step - warmup) / max(1, steps - warmup)
    return args.lr * (0.1 + 0.9 * 0.5 * (1 + math.cos(math.pi * min(1.0, progress))))


model = (LlamaForCausalLM.from_pretrained(args.init) if args.init else LlamaForCausalLM(config)).to(device)
optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, betas=(0.9, 0.95), weight_decay=0.1, fused=True)
checkpoint = os.path.join(args.run, prefix + "checkpoint.pt")
step = 0
if os.path.exists(checkpoint):
    state = torch.load(checkpoint, map_location=device)
    model.load_state_dict(state["model"])
    optimizer.load_state_dict(state["optimizer"])
    step = state["step"]
    print(f"resuming at step {step}", flush=True)
print(f"{sum(p.numel() for p in model.parameters()) / 1e6:.1f}M parameters, {steps} steps, "
      f"{len(train) / 1e9:.2f}B training tokens available", flush=True)

rng = np.random.default_rng(step)
model.train()
start, seen = time.time(), 0
while step < steps:
    for group in optimizer.param_groups:
        group["lr"] = lr_at(step)
    x, y = batch(train, rng)
    loss = model(input_ids=x, labels=None).logits.view(-1, config.vocab_size)
    loss = torch.nn.functional.cross_entropy(loss, y.reshape(-1))
    optimizer.zero_grad(set_to_none=True)
    loss.backward()
    torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
    optimizer.step()
    step += 1
    seen += x.numel()
    if step % 100 == 0:
        rate = seen / (time.time() - start)
        print(f"step {step}/{steps} loss {loss.item():.3f} lr {lr_at(step):.2e} {rate:,.0f} tok/s "
              f"eta {(steps - step) * args.batch * args.context / rate / 3600:.1f} h", flush=True)
    if step % args.every == 0 or step == steps:
        model.eval()
        with torch.no_grad():
            vrng = np.random.default_rng(0)
            losses = []
            for _ in range(20):
                vx, vy = batch(val, vrng)
                logits = model(input_ids=vx).logits.view(-1, config.vocab_size)
                losses.append(torch.nn.functional.cross_entropy(logits, vy.reshape(-1)).item())
        print(f"step {step} val loss {sum(losses) / len(losses):.3f}", flush=True)
        model.train()
        torch.save({"model": model.state_dict(), "optimizer": optimizer.state_dict(), "step": step}, checkpoint + ".tmp")
        os.replace(checkpoint + ".tmp", checkpoint)
        model.save_pretrained(os.path.join(args.run, prefix + "model"))
print("done", flush=True)
