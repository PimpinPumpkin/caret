#!/usr/bin/env python3
"""Reads the typing corrections Caret kept (Settings > Text correction > Save my corrections).

    scripts/corrections-report.py caret-corrections.jsonl
"""
import collections, json, sys

if len(sys.argv) < 2:
    sys.exit(__doc__)
words, corrections = 0, []
for line in open(sys.argv[1], encoding="utf-8"):
    j = json.loads(line)
    if "words" in j:
        words += j["words"]
    elif "kind" in j:
        corrections.append(j)
kinds = collections.Counter(c["kind"] for c in corrections)
print(f"{words} words typed (counted in fifties), {len(corrections)} corrections: "
      + ", ".join(f"{n} {k}" for k, n in kinds.most_common()))
if words:
    print(f"about {100 * len(corrections) / words:.1f} corrections per 100 words")
wrong_autocorrect = [c for c in corrections if c["cameOut"].lower() != c["typed"].lower()]
print(f"{len(wrong_autocorrect)} times a correction the keyboard made was wrong, "
      f"{len(corrections) - len(wrong_autocorrect)} times a typo it left alone was fixed by hand")
print("\nmost common (typed -> came out -> meant):")
for (t, o, m), n in collections.Counter((c["typed"], c["cameOut"], c["meant"]) for c in corrections).most_common(30):
    print(f"  {n:3}  {t} -> {o} -> {m}")
