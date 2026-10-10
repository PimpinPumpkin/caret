# SPDX-License-Identifier: GPL-3.0-only
"""Turns FUTO's swipe dataset (MIT, huggingface.co/datasets/futo-org/swipe.futo.org) into arrays to train on.

    python swipe_prep.py train.jsonl OUT_PREFIX

Every swipe is resampled the way the swipe library does before it asks the encoder: to 60 Hz, then to
64 points evenly spaced, clipped to the keyboard (0 to 1 both ways). The word is kept as the letters a
to z it has, which are the keys the dataset's QWERTY keyboard has. Writes OUT_PREFIX.paths.npy
(float16, [N, 2, 64]), OUT_PREFIX.words.npy (uint8, [N, 24]: letters as 1 to 26, 0 after the end)
and OUT_PREFIX.lengths.npy.
"""
import json, sys
from multiprocessing import Pool
import numpy as np

POINTS = 64
MAX_LETTERS = 24


def resample(points):
    t = np.array([p["t"] for p in points], dtype=np.float64)
    x = np.array([p["x"] for p in points], dtype=np.float64)
    y = np.array([p["y"] for p in points], dtype=np.float64)
    t -= t[0]
    duration = t[-1]
    if len(t) < 2 or duration <= 0:
        x60, y60 = x[[0, -1]], y[[0, -1]]
    else:
        n = max(2, int(round(duration / (1000.0 / 60.0))) + 1)
        at = np.linspace(0, duration, n)
        x60, y60 = np.interp(at, t, x), np.interp(at, t, y)
    at = np.linspace(0, len(x60) - 1, POINTS)
    index = np.arange(len(x60))
    return np.clip(np.stack([np.interp(at, index, x60), np.interp(at, index, y60)]), 0.0, 1.0)


def one(line):
    try:
        row = json.loads(line)
        letters = [ord(c) - 96 for c in row["word"].lower() if "a" <= c <= "z"]
        if not letters or len(letters) > MAX_LETTERS or len(row["data"]) < 2:
            return None
        word = np.zeros(MAX_LETTERS, dtype=np.uint8)
        word[:len(letters)] = letters
        return resample(row["data"]).astype(np.float16), word, len(letters)
    except (ValueError, KeyError, TypeError):
        return None


if __name__ == "__main__":
    source, out = sys.argv[1:3]
    paths, words, lengths = [], [], []
    with open(source) as f, Pool(10) as pool:
        for n, result in enumerate(pool.imap(one, f, chunksize=2000)):
            if result is not None:
                paths.append(result[0]); words.append(result[1]); lengths.append(result[2])
            if n % 200000 == 0:
                print(f"{n} lines, {len(paths)} swipes kept", flush=True)
    np.save(out + ".paths.npy", np.stack(paths))
    np.save(out + ".words.npy", np.stack(words))
    np.save(out + ".lengths.npy", np.array(lengths, dtype=np.uint8))
    print(f"done, {len(paths)} swipes", flush=True)
