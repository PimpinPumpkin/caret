# SPDX-License-Identifier: GPL-3.0-only
"""Turns the prepared text files into what the typing model trains on.

    python build_data.py DATA_DIR OUT_DIR tokenizer     a SentencePiece tokenizer from a sample of every source
    python build_data.py DATA_DIR OUT_DIR encode        train.bin / val.bin: uint16 token ids, each line starting with BOS

The mix, by share of the text: casual writing first, encyclopedic text for breadth. Small sources are
repeated so they are not drowned out.
"""
import os, random, sys
from multiprocessing import Pool
import numpy as np
import sentencepiece as spm

MIX = {  # source: (share of the training text, times repeated at most)
    "reddit.txt": (0.62, 1),
    "wikipedia.txt": (0.25, 1),
    "soda.txt": (0.10, 2),
    "sms.txt": (0.03, 50),
}
TOTAL_BYTES = 12_000_000_000  # about 3 billion tokens
VOCAB = 16000


def sample_lines(path, fraction, seed):
    rng = random.Random(seed)
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            if rng.random() < fraction:
                line = line.strip()
                if line:
                    yield line


def tokenizer(data, out):
    sample = os.path.join(out, "tokenizer_sample.txt")
    with open(sample, "w") as w:
        for name, (share, _) in MIX.items():
            size = os.path.getsize(os.path.join(data, name))
            want = 400_000_000 * share  # 400 MB of text across sources
            for line in sample_lines(os.path.join(data, name), min(1.0, want / size), 1):
                w.write(line + "\n")
    spm.SentencePieceTrainer.train(
        input=sample, model_prefix=os.path.join(out, "tokenizer"), vocab_size=VOCAB, model_type="bpe",
        byte_fallback=True, character_coverage=0.9995, input_sentence_size=8_000_000, shuffle_input_sentence=True,
        bos_id=1, eos_id=2, unk_id=0, pad_id=-1, num_threads=10, max_sentence_length=4096,
        normalization_rule_name="identity", split_digits=True, allow_whitespace_only_pieces=True)


_sp = None


def _encode(lines):
    global _sp
    if _sp is None:
        _sp = spm.SentencePieceProcessor(model_file=os.environ["TOKENIZER"])
    out = []
    for ids in _sp.encode(lines):
        out.append(1)  # BOS: every line is its own message
        out.extend(ids)
    return np.array(out, dtype=np.uint16)


def chunks(data):
    """Lines from every source in the mix, interleaved at random, in batches."""
    streams = []
    for name, (share, repeat) in MIX.items():
        path = os.path.join(data, name)
        size = os.path.getsize(path)
        fraction = TOTAL_BYTES * share / size
        for r in range(repeat if fraction > 1 else 1):
            streams.append(sample_lines(path, min(1.0, fraction / (repeat if fraction > 1 else 1) if fraction > 1 else fraction), 100 + r))
    weights = [MIX[n][0] / (MIX[n][1] if TOTAL_BYTES * MIX[n][0] / os.path.getsize(os.path.join(data, n)) > 1 else 1)
               for n in MIX for _ in range(MIX[n][1] if TOTAL_BYTES * MIX[n][0] / os.path.getsize(os.path.join(data, n)) > 1 else 1)]
    rng = random.Random(7)
    batch = []
    while streams:
        i = rng.choices(range(len(streams)), weights)[0]
        line = next(streams[i], None)
        if line is None:
            del streams[i], weights[i]
            continue
        batch.append(line)
        if len(batch) == 20000:
            yield batch
            batch = []
    if batch:
        yield batch


def encode(data, out):
    os.environ["TOKENIZER"] = os.path.join(out, "tokenizer.model")
    tokens = 0
    with open(os.path.join(out, "train.bin"), "wb") as train, open(os.path.join(out, "val.bin"), "wb") as val, Pool(10) as pool:
        for n, ids in enumerate(pool.imap(_encode, chunks(data), chunksize=1)):
            (val if n % 200 == 199 else train).write(ids.tobytes())
            tokens += len(ids)
            if n % 100 == 0:
                print(f"{tokens / 1e9:.2f} billion tokens", flush=True)
    print(f"done, {tokens / 1e9:.2f} billion tokens", flush=True)


if __name__ == "__main__":
    data, out, step = sys.argv[1:4]
    os.makedirs(out, exist_ok=True)
    {"tokenizer": tokenizer, "encode": encode}[step](data, out)
