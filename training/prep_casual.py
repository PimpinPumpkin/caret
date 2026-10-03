# SPDX-License-Identifier: GPL-3.0-only
"""Casual English text for the typing model, one message per line, written to OUT_DIR/<source>.txt:

    reddit    Reddit comments from about 50 subreddits (HuggingFaceGECLM/REDDIT_comments)
    soda      everyday dialogues, one turn per line (allenai/soda, CC BY 4.0)
    sms       real text messages, the non-spam ones (ucirvine/sms_spam)
    fineweb   a slice of filtered web text for breadth (HuggingFaceFW/fineweb sample-10BT, ODC-By)

    python prep_casual.py OUT_DIR [source ...]
"""
import json, os, re, sys
from datasets import load_dataset

URL = re.compile(r"https?://\S+|www\.\S+")
MARKDOWN = re.compile(r"[*_~^>#`]+|\[([^\]]*)\]\([^)]*\)")
SPACES = re.compile(r"\s+")


def clean(text):
    text = MARKDOWN.sub(lambda m: m.group(1) or "", URL.sub("", text))
    return SPACES.sub(" ", text).strip()


def usable(text, shortest=8, longest=600):
    if not shortest <= len(text) <= longest:
        return False
    letters = sum(c.isalpha() for c in text)
    return letters >= len(text) * 0.6  # mostly words, not code, numbers or emoji runs


def write(out_dir, name, lines):
    path = os.path.join(out_dir, f"{name}.txt")
    count = size = 0
    with open(path + ".tmp", "w") as out:
        for line in lines:
            out.write(line + "\n")
            count += 1
            size += len(line)
            if count % 1_000_000 == 0:
                print(f"{name}: {count:,} lines, {size / 1e9:.2f} GB", flush=True)
    os.replace(path + ".tmp", path)
    print(f"{name}: done, {count:,} lines, {size / 1e9:.2f} GB", flush=True)


def reddit():
    data = load_dataset("HuggingFaceGECLM/REDDIT_comments", split=None)
    for split in data.values():
        for row in split:
            body = row["body"] or ""
            if body in ("[deleted]", "[removed]") or (row["author"] or "").lower().endswith("bot"):
                continue
            for paragraph in body.split("\n"):
                text = clean(paragraph)
                if usable(text):
                    yield text


def soda():
    for row in load_dataset("allenai/soda", split="train"):
        turns = row["dialogue"]
        if isinstance(turns, str):
            turns = json.loads(turns)
        for turn in turns:
            text = clean(turn)
            if usable(text, 2):
                yield text


def sms():
    for row in load_dataset("ucirvine/sms_spam", split="train"):
        if row["label"] in (0, "0", "ham"):
            text = clean(row["sms"])
            if usable(text, 2):
                yield text


def fineweb(limit_bytes=3_000_000_000):
    size = 0
    for row in load_dataset("HuggingFaceFW/fineweb", "sample-10BT", split="train", streaming=True):
        for paragraph in row["text"].split("\n"):
            text = clean(paragraph)
            if usable(text, 40, 1500):
                size += len(text)
                yield text
        if size > limit_bytes:
            return


SOURCES = {"reddit": reddit, "soda": soda, "sms": sms, "fineweb": fineweb}

if __name__ == "__main__":
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    for name in sys.argv[2:] or ["sms", "soda", "reddit", "fineweb"]:
        if os.path.exists(os.path.join(out_dir, f"{name}.txt")):
            print(f"{name}: already there", flush=True)
            continue
        write(out_dir, name, SOURCES[name]())
