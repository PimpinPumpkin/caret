# SPDX-License-Identifier: GPL-3.0-only
"""Trains Caret's swipe encoder (swipe_model.py) on the arrays from swipe_prep.py.

    python swipe_train.py DATA_DIR RUN_DIR [--epochs 40] [--batch 256]

The loss is CTC: the keys of the word have to come out in order somewhere along the swipe. So that the
model learns keys by their place and not by their number, every batch gets its own keyboard: the keys in
another order, the whole keyboard and the swipe on it a little stretched and shifted, the swipe shaken.
Saves checkpoint.pt (picked up again when started anew) and best.pt (best share of dev words whose keys
come out right when the likeliest key of each moment is read off).
"""
import argparse, math, os, time
import numpy as np
import torch
from swipe_model import SwipeEncoder, MAX_KEYS, MOMENTS

p = argparse.ArgumentParser()
p.add_argument("data")
p.add_argument("run")
p.add_argument("--epochs", type=int, default=40)
p.add_argument("--batch", type=int, default=256)
p.add_argument("--lr", type=float, default=2e-3)
args = p.parse_args()
os.makedirs(args.run, exist_ok=True)
device = "cuda"

# the keyboard the swipes were made on: the middle of the keys a to z (the swipe library's default_en_qwerty)
CX = [.1, .6, .4, .3, .25, .4, .5, .6, .75, .7, .8, .9, .8, .7, .85, .95, .05, .35, .2, .45, .65, .5, .15, .3, .55, .2]
ROW = {c: r for r, row in enumerate(["qwertyuiop", "asdfghjkl", "zxcvbnm"]) for c in row}
CY = [(ROW[chr(97 + i)] + 0.5) / 3 for i in range(26)]
QWERTY = torch.tensor(list(zip(CX, CY)), dtype=torch.float32, device=device)


def load(name):
    paths = np.load(os.path.join(args.data, name + ".paths.npy"), mmap_mode="r")
    words = torch.from_numpy(np.load(os.path.join(args.data, name + ".words.npy")).astype(np.int64))
    lengths = torch.from_numpy(np.load(os.path.join(args.data, name + ".lengths.npy")).astype(np.int64))
    return paths, words, lengths


def batch_of(data, index, shake):
    paths, words, lengths = data
    index = np.sort(index)
    x = torch.from_numpy(np.ascontiguousarray(paths[index]).astype(np.float32)).to(device)   # [B, 2, 64]
    target, length = words[index].to(device), lengths[index].to(device)
    b = x.shape[0]
    keys = torch.zeros(b, MAX_KEYS, 2, device=device)
    mask = torch.zeros(b, MAX_KEYS, dtype=torch.bool, device=device)
    # each swipe's keyboard in an order of its own
    order = torch.argsort(torch.rand(b, 26, device=device), dim=1) if shake else torch.arange(26, device=device).expand(b, 26)
    keys[:, :26] = QWERTY[order]
    mask[:, :26] = True
    where = torch.argsort(order, dim=1)                         # letter -> its place in this order
    target = torch.where(target > 0, torch.gather(where, 1, (target - 1).clamp(min=0)), torch.zeros_like(target))
    if shake:
        scale = 1 + (torch.rand(b, 1, 2, device=device) - 0.5) * 0.3
        shift = (torch.rand(b, 1, 2, device=device) - 0.5) * 0.1
        keys[:, :26] = (keys[:, :26] - 0.5) * scale + 0.5 + shift
        x = ((x.transpose(1, 2) - 0.5) * scale + 0.5 + shift + torch.randn(b, 64, 2, device=device) * 0.004).transpose(1, 2)
        x = x.clamp(0, 1)
    return x, keys, mask, target, length


def loss_of(model, x, keys, mask, target, length):
    log_probs = model(x, keys, mask)[0]                          # [B, 32, 65]
    return torch.nn.functional.ctc_loss(
        log_probs.transpose(0, 1), target, torch.full((x.shape[0],), MOMENTS, device=device), length,
        blank=MAX_KEYS, zero_infinity=True), log_probs


def words_right(log_probs, target, length):
    best = log_probs.argmax(-1)                                  # [B, 32]
    right = 0
    for row, want, n in zip(best.tolist(), target.tolist(), length.tolist()):
        out, last = [], MAX_KEYS
        for k in row:
            if k != last and k != MAX_KEYS:
                out.append(k)
            last = k
        right += out == want[:n]
    return right


train, dev = load("train"), load("dev")
steps_per_epoch = len(train[1]) // args.batch
total = steps_per_epoch * args.epochs
model = SwipeEncoder().to(device)
optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
checkpoint = os.path.join(args.run, "checkpoint.pt")
step, best = 0, 0.0
if os.path.exists(checkpoint):
    state = torch.load(checkpoint, map_location=device)
    model.load_state_dict(state["model"]); optimizer.load_state_dict(state["optimizer"])
    step, best = state["step"], state["best"]
    print(f"resuming at step {step}", flush=True)
print(f"{sum(p.numel() for p in model.parameters()) / 1e6:.2f}M parameters, {len(train[1])} swipes, "
      f"{steps_per_epoch} steps an epoch, {total} steps", flush=True)

rng = np.random.default_rng(step)
start = time.time()
while step < total:
    lr = args.lr * min(1.0, (step + 1) / 1000) * (0.05 + 0.95 * 0.5 * (1 + math.cos(math.pi * step / total)))
    for group in optimizer.param_groups:
        group["lr"] = lr
    model.train()
    # a run of neighbors in the file, from a random place: reading the memory-mapped file in order is fast
    at = int(rng.integers(0, len(train[1]) - args.batch * 8))
    index = at + rng.choice(args.batch * 8, args.batch, replace=False)
    loss, _ = loss_of(model, *batch_of(train, index, shake=True))
    optimizer.zero_grad(set_to_none=True)
    loss.backward()
    torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
    optimizer.step()
    step += 1
    if step % 200 == 0:
        rate = step / (time.time() - start) if step > 200 else 0
        print(f"step {step}/{total} loss {loss.item():.3f} lr {lr:.2e} eta {(total - step) / max(rate, 1e-9) / 3600:.1f} h", flush=True)
    if step % 4000 == 0 or step == total:
        model.eval()
        with torch.no_grad():
            right = seen = 0
            losses = []
            for at in range(0, min(len(dev[1]), 20000), 500):
                x, keys, mask, target, length = batch_of(dev, np.arange(at, min(at + 500, len(dev[1]))), shake=False)
                l, log_probs = loss_of(model, x, keys, mask, target, length)
                losses.append(l.item())
                right += words_right(log_probs, target, length); seen += x.shape[0]
        share = 100 * right / seen
        print(f"step {step} dev loss {sum(losses) / len(losses):.3f}, keys right as read off {share:.1f}%", flush=True)
        if share > best:
            best = share
            torch.save(model.state_dict(), os.path.join(args.run, "best.pt"))
        torch.save({"model": model.state_dict(), "optimizer": optimizer.state_dict(), "step": step, "best": best}, checkpoint + ".tmp")
        os.replace(checkpoint + ".tmp", checkpoint)
print("done", flush=True)
