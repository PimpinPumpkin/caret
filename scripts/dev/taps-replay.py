#!/usr/bin/env python3
"""Tries other settings of the sentence-and-taps rule (Suggest.fitToTaps / correctByKeys) on decisions a
debug build logged, without typing anything again.

    scripts/dev/taps-replay.py RUN.log RUN.words [more pairs ...] [--grid]

RUN.log is `adb logcat -d` after scripts/typing-benchmark.py ... --words RUN.words. Words whose decision
was not logged (the rule did not apply) count as they came out in the run. Without --grid it prints the
result for the settings in the keyboard; with it, a table over a few settings of each number.
"""
import itertools, re, sys

DEFAULT = dict(margin=4.5, sharp=4.0, slip=6.0, floor=-16.0, unknown=5.0)


def parse(log):
    centers, width, series = {}, 1.0, []
    for line in open(log, errors="ignore"):
        m = re.search(r"Suggest : tapskeys (\S+) (.*)", line)
        if m:
            width = float(m[1])
            centers = {p.split(":")[0]: tuple(map(float, p.split(":")[1].split(","))) for p in m[2].split()}
            continue
        m = re.search(r"Suggest : tapsdecide (.*)", line)
        if not m:
            continue
        says, is_word, first, guard, typed, lm, xs, ys, cands = m[1].rstrip("\n").split("|", 8)
        d = dict(says=says == "true", is_word=is_word == "true", first=first, guard=guard == "true", typed=typed,
                 lm=float(lm), x=list(map(float, xs.split(","))), y=list(map(float, ys.split(","))),
                 cands=[(c.rsplit(":", 2)[0], float(c.rsplit(":", 2)[1]), c.rsplit(":", 2)[2] == "true") for c in cands.split(";") if c],
                 centers=centers, width=width)
        # letters of one word come as a run of growing lengths; a new run starts a new word
        last = series[-1][-1]["typed"] if series else None
        if last is not None and len(typed) > len(last) and typed.startswith(last):
            series[-1].append(d)
        elif last is not None and typed == last:
            series[-1][-1] = d
        else:
            series.append([d])
    decisions = [s[-1] for s in series]
    for d in decisions:  # the keys are logged once per keyboard, which may be before the log was cleared
        d["centers"] = d["centers"] or KEYS[0]
        d["width"] = d["width"] if d["centers"] is not KEYS[0] else KEYS[1]
    return decisions


KEYS = [{}, 1.0]


def keys_from_clean_run(log, words):
    """Where the keys are, from a run tapped without noise: every tap is the middle of its letter's key."""
    centers = {}
    decisions = parse(log)
    by_typed = {d["typed"].lower(): d for d in decisions}
    for line in open(words):
        want = line.split("\t")[0]
        d = by_typed.get(want)
        if d and len(d["x"]) == len(want):
            for c, x, y in zip(want, d["x"], d["y"]):
                centers[c] = (x, y)
    KEYS[0], KEYS[1] = centers, abs(centers["w"][0] - centers["q"][0]) if "q" in centers and "w" in centers else 106.0
    return centers


def fit(word, d, sharp, slip):
    c = d["centers"]
    letters = [l for l in word.lower() if l in c]
    taps = len(d["x"])

    def off(i, j):
        cx, cy = c[letters[i]]
        dx, dy = (d["x"][j] - cx) / d["width"], (d["y"][j] - cy) / d["width"]
        return min(sharp * (dx * dx + dy * dy), 2 * slip)
    two, prev = None, [-j * slip for j in range(taps + 1)]
    for i in range(1, len(letters) + 1):
        row = [-i * slip] + [0.0] * taps
        for j in range(1, taps + 1):
            best = max(prev[j - 1] - off(i - 1, j - 1), prev[j] - slip, row[j - 1] - slip)
            if i > 1 and j > 1:
                best = max(best, two[j - 2] - slip - off(i - 1, j - 2) - off(i - 2, j - 1))
            row[j] = best
        two, prev = prev, row
    return prev[taps]


def decide(d, margin, sharp, slip, floor, unknown):
    """What the word comes out as."""
    typed = d["typed"]
    if not d["cands"]:
        return typed
    total = lambda w, lm: max(lm, floor) + fit(w, d, sharp, slip)
    scored = [(total(w, lm), w, ok) for w, lm, ok in d["cands"]]
    best = max(scored)
    if d["says"] and d["first"].lower() == typed.lower():
        return d["first"]
    if not d["guard"] or not best[2]:
        return d["first"] if d["says"] else typed
    lead = best[0] - total(typed, d["lm"])
    if d["is_word"]:
        return best[1] if lead >= margin else typed
    if d["says"] or lead >= unknown:
        return best[1]
    return typed


def load(pairs):
    runs = []
    for log, words in pairs:
        decisions = parse(log)
        rows = [l.rstrip("\n").split("\t") for l in open(words)]
        k = 0
        items = []
        for want, tapped, ok in rows:
            d = None
            # the next logged decision is this word's when it has its length and mostly its letters
            # (looking a few ahead: a decision can belong to no word, when a stray tap started one)
            for ahead in range(k, min(k + 4, len(decisions))):
                t = decisions[ahead]["typed"].lower()
                if len(t) == len(tapped) and sum(a != b for a, b in zip(t, tapped)) <= max(1, len(t) // 2):
                    d = decisions[ahead]
                    k = ahead + 1
                    break
            items.append((want, ok == "1", d))
        runs.append((words, items, k, len(decisions)))
    return runs


def score(items, **p):
    right = 0
    for want, ok, d in items:
        right += (decide(d, **p).lower() == want.lower()) if d else ok
    return 100 * right / len(items)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    for log, words in zip(args[0::2], args[1::2]):
        if words.endswith("rare.words"):
            found = keys_from_clean_run(log, words)
            print(f"keys from {log}: {len(found)} letters, key width {KEYS[1]:.0f}")
    runs = load(list(zip(args[0::2], args[1::2])))
    for name, items, used, logged in runs:
        asrun = 100 * sum(ok for _, ok, _ in items) / len(items)
        print(f"{name}: {len(items)} words, {used} of {logged} logged decisions matched, as run {asrun:.1f}%, replayed {score(items, **DEFAULT):.1f}%")
    if "--grid" in sys.argv:
        grid = dict(margin=[1.5, 3, 4.5, 6, 8], sharp=[3, 4, 5.5, 8, 11], slip=[4.5, 6, 8], floor=[-16.0, -12.0, -10.0], unknown=[99.0, 3.0, 5.0, 8.0])
        results = []
        for values in itertools.product(*grid.values()):
            p = dict(zip(grid, values))
            results.append(([score(items, **p) for _, items, _, _ in runs], p))
        # the first runs are sloppy sentences, the last two the rare words tapped cleanly and a little off:
        # best sentences among the settings that keep every clean rare word and lose no sloppy rare word
        sentences = lambda r: sum(r[0][:-2]) / max(1, len(r[0]) - 2)
        bar = float(sys.argv[sys.argv.index("--rare-bar") + 1]) if "--rare-bar" in sys.argv else 0.0
        passing = [r for r in results if r[0][-2] >= 100 and r[0][-1] >= bar]
        print(f"{len(passing)} of {len(results)} settings keep the rare words (clean 100, sloppy at least {bar})")
        for r in sorted(passing or results, key=lambda r: (-sentences(r), -r[0][-1]))[:12]:
            print(" ".join(f"{s:5.1f}" for s in r[0]), f"| sentences {sentences(r):.2f} |", r[1])
