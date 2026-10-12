#!/usr/bin/env python3
"""What ordering swipe candidates by the sentence is worth, without a phone.

    scripts/dev/swipe-rerank.py RESULT SWIPES [RESULT SWIPES ...]

RESULT is what the swipe library's test tool printed for SWIPES (scripts/swipe-benchmark.sh leaves both
in swipe/data: bench-result.txt and bench.jsonl), with the "cands" lines our patch adds: every candidate
and its score. Each candidate gets the typing model's log probability after the words before the swipe
(lm/build/host/lm_test), times a weight, added to the engine's score. Prints top-1 for a row of weights;
the first pair is for choosing the weight, the others show whether it holds.
"""
import json, subprocess, sys

MODEL = "lm/models/caret-typing.gguf"
WEIGHTS = [0, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.5, 0.6]


def load(result, swipes):
    context = {}
    for line in open(swipes):
        try:
            j = json.loads(line)
        except ValueError:
            continue
        words = j.get("sentence", "").split()
        i = j.get("word_idx", 0)
        context[j["id"]] = (" ".join(words[:i]), "".join(c for c in j["word"].lower() if c.isalpha()))
    items = []
    for line in open(result):
        parts = line.split()
        if not parts or parts[0] != "cands" or len(parts) < 4:
            continue
        before, want = context[int(parts[1])]
        items.append((before, want, parts[2::2], list(map(float, parts[3::2]))))
    return items


def sentence_scores(items):
    # the engine's words are in small letters; the model knows "I" and names by their capital, so each
    # word is asked both ways and the better one counts (the keyboard knows the real spelling)
    lines = "".join(f"{before}\t{' '.join(words + [w.capitalize() for w in words])}\n" for before, _, words, _ in items)
    out = subprocess.run(["lm/build/host/lm_test", MODEL, "-"], input=lines, capture_output=True, text=True).stdout.split("\n")
    scores = []
    for l, (_, _, words, _) in zip(out, items):
        v = list(map(float, l.split())) if l.strip() else []
        n = len(words)
        scores.append([max(a, b) for a, b in zip(v[:n], v[n:])] if len(v) == 2 * n else [])
    return scores


if __name__ == "__main__":
    args = sys.argv[1:]
    print("weight " + " ".join(f"{w:5.2f}" for w in WEIGHTS))
    for result, swipes in zip(args[0::2], args[1::2]):
        items = load(result, swipes)
        lm = sentence_scores(items)
        row = []
        for w in WEIGHTS:
            right = 0
            for (before, want, words, scores), fit in zip(items, lm):
                if len(fit) != len(words):
                    fit = [0.0] * len(words)
                # at the start of a line there is nothing to go by: the engine's order stands
                total = [s + (w * f if before else 0.0) for s, f in zip(scores, fit)]
                right += words[max(range(len(words)), key=total.__getitem__)] == want
            row.append(100 * right / len(items))
        print(f"top-1  " + " ".join(f"{r:5.1f}" for r in row) + f"   {result} ({len(items)} swipes)")
