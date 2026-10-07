# SPDX-License-Identifier: GPL-3.0-only
"""Builds what the typing model is fine-tuned on: text where a word is followed by how it was typed.

    python typing_data.py DATA_DIR RUN_DIR [--tokens 2.5e8]

Half the lines stay as they are, so the model keeps predicting the next word. In the other half one
word is cut out and written as the keys a sloppy typist would have hit, then the word that was meant:

    see you at | t e h | the

The keys come from simulated taps on a QWERTY keyboard: each tap lands on the key's center plus
Gaussian noise, and the key under it is what gets written; now and then a letter is skipped, doubled
or swapped with its neighbor, as fingers do. A share of the words is typed cleanly, rare ones
included, so the model also learns to leave a word alone. Apostrophes are never typed and capitals
never held, as on a phone: the word that was meant has both.

Writes typing_train.bin and typing_val.bin (uint16 token ids, each line starting with BOS) into
RUN_DIR, next to the tokenizer.model of that run.
"""
import argparse, os, random
from multiprocessing import Pool
import numpy as np
import sentencepiece as spm
from build_data import MIX, sample_lines

OPEN = CLOSE = "|"
ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
ROW_OFFSET = [0.0, 0.5, 1.5]   # in key widths
ROW_PITCH = 1.44               # a row is this many key widths high (59 dp over 41 dp)
KEYS = {c: (ROW_OFFSET[r] + i + 0.5, (r + 0.5) * ROW_PITCH) for r, row in enumerate(ROWS) for i, c in enumerate(row)}
CENTERS = np.array([KEYS[c] for c in sorted(KEYS)])
LETTERS = sorted(KEYS)


def typed(word, rng):
    """The keys hit when typing [word] sloppily, or None when it is not something typed on the letter keys."""
    letters = [c for c in word.lower() if c != "'" and c != "’"]
    if not letters or any(c not in KEYS for c in letters):
        return None
    sigma = 0.0 if rng.random() < 0.25 else rng.uniform(0.15, 0.45)
    slips = sigma > 0 and len(letters) > 2
    out = []
    i = 0
    while i < len(letters):
        c = letters[i]
        roll = rng.random() if slips else 1.0
        if roll < 0.012:                      # skipped
            i += 1
            continue
        if roll < 0.024 and i + 1 < len(letters):  # swapped with the next
            pair = [letters[i + 1], c]
            i += 2
        else:
            pair = [c, c] if roll < 0.032 else [c]  # doubled
            i += 1
        for key in pair:
            x, y = KEYS[key]
            if sigma:
                x += rng.gauss(0, sigma)
                y += rng.gauss(0, sigma)
            d = (CENTERS[:, 0] - x) ** 2 + (CENTERS[:, 1] - y) ** 2
            out.append(LETTERS[int(d.argmin())])
    return out or None


def with_typing(line, rng):
    """[line] cut after one of its words, that word written as typed keys and then as meant."""
    words = line.split(" ")
    if len(words) > 40:
        start = rng.randrange(len(words) - 40)
        words = words[start:start + 40]
    for _ in range(4):
        i = rng.randrange(len(words))
        word = words[i].strip(".,!?;:\"()[]")
        if not word or len(word) > 20:
            continue
        keys = typed(word, rng)
        if keys is None:
            continue
        before = " ".join(words[:i])
        return (before + " " if before else "") + OPEN + " " + " ".join(keys) + " " + CLOSE + " " + word
    return line


_sp = None


def _encode(job):
    global _sp
    seed, lines = job
    if _sp is None:
        _sp = spm.SentencePieceProcessor(model_file=os.environ["TOKENIZER"])
    rng = random.Random(seed)
    texts = [with_typing(l, rng) if rng.random() < 0.5 else l for l in lines]
    out = []
    for ids in _sp.encode(texts):
        out.append(1)
        out.extend(ids[:200])
    return np.array(out, dtype=np.uint16)


def batches(data, total_bytes):
    streams, weights = [], []
    for name, (share, _) in MIX.items():
        path = os.path.join(data, name)
        # other lines than the base model saw first, as far as the seed decides
        streams.append(sample_lines(path, min(1.0, total_bytes * share / os.path.getsize(path)), 4242))
        weights.append(share)
    rng = random.Random(11)
    batch, n = [], 0
    while streams:
        i = rng.choices(range(len(streams)), weights)[0]
        line = next(streams[i], None)
        if line is None:
            del streams[i], weights[i]
            continue
        batch.append(line)
        if len(batch) == 20000:
            n += 1
            yield n, batch
            batch = []
    if batch:
        yield n + 1, batch


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("data")
    p.add_argument("run")
    p.add_argument("--tokens", type=float, default=2.5e8)
    args = p.parse_args()
    os.environ["TOKENIZER"] = os.path.join(args.run, "tokenizer.model")
    tokens = 0
    with open(os.path.join(args.run, "typing_train.bin"), "wb") as train, \
            open(os.path.join(args.run, "typing_val.bin"), "wb") as val, Pool(10) as pool:
        # typed lines are cut short, so more text is read than tokens come out: about 5 bytes a token
        for n, ids in enumerate(pool.imap(_encode, batches(args.data, args.tokens * 5), chunksize=1)):
            (val if n % 100 == 99 else train).write(ids.tobytes())
            tokens += len(ids)
            if n % 50 == 0:
                print(f"{tokens / 1e6:.0f} million tokens", flush=True)
    print(f"done, {tokens / 1e6:.0f} million tokens", flush=True)
