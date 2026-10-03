# SPDX-License-Identifier: GPL-3.0-only
"""English Wikipedia (CC BY-SA 4.0) as plain text for the typing model: one paragraph per line,
headings, lists, tables and very short lines left out.

    python prep_wikipedia.py OUT_DIR
"""
import os, re, sys
from datasets import load_dataset

out_dir = sys.argv[1]
os.makedirs(out_dir, exist_ok=True)
dataset = load_dataset("wikimedia/wikipedia", "20231101.en", split="train")
keep = re.compile(r"^[A-Z\"'(].{60,}[.!?\"')]$")
written = 0
with open(os.path.join(out_dir, "wikipedia.txt"), "w") as out:
    for n, article in enumerate(dataset):
        for paragraph in article["text"].split("\n"):
            paragraph = paragraph.strip()
            if keep.match(paragraph) and "|" not in paragraph:
                out.write(paragraph + "\n")
                written += len(paragraph)
        if n % 100000 == 0:
            print(f"{n} articles, {written / 1e9:.2f} GB", flush=True)
print("done", written / 1e9, "GB", flush=True)
