#!/usr/bin/env python3
"""Reads a swipe recording from Caret (Settings > Gesture typing > Save the recording).

    scripts/swipe-replay.py caret-swipes.jsonl             what was recorded and where it went wrong
    scripts/swipe-replay.py caret-swipes.jsonl --replay    also runs the swipes through the engine again on a
                                                           device (ANDROID_SERIAL), once with the keys as they
                                                           were on the phone and once with the stock QWERTY

The word that was meant is the practice word when there is one. Otherwise it is the suggestion that
was picked in place of the swipe, or the word that came out when it was left alone. A swipe that was
deleted without a practice word has no known answer and is only counted.

What the three numbers say when they differ:
  live worse than replay with the phone's keys    something around the engine on the phone (context, personal words, timing)
  phone's keys worse than stock keys              the key positions Caret hands to the engine
  all about the same and low                      the model or the word list, with this way of swiping
"""
import json, os, subprocess, sys

STOCK = {c: (x, y) for row, y, x0 in (("qwertyuiop", 1 / 6, 0.05), ("asdfghjkl", 0.5, 0.1), ("zxcvbnm", 5 / 6, 0.2))
         for x, c in ((x0 + 0.1 * i, c) for i, c in enumerate(row))}


def load(path):
    layouts, swipes, by_id = {}, [], {}
    layout = None
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        j = json.loads(line)
        if "layout" in j:
            layout = j["layout"]
        elif "swipe" in j:
            j["keys"] = layout
            j["outcome"] = "kept"
            swipes.append(j)
            by_id[j["swipe"]] = j
        elif "picked" in j and j["picked"] in by_id:
            by_id[j["picked"]]["outcome"] = "picked"
            by_id[j["picked"]]["picked_word"] = j["word"]
        elif "deleted" in j and j["deleted"] in by_id:
            by_id[j["deleted"]]["outcome"] = "deleted"
    return swipes


def meant(s):
    if "target" in s:
        return s["target"]
    if s["outcome"] == "picked":
        return s["picked_word"]
    if s["outcome"] == "deleted":
        return None
    return s["typed"]


def same(a, b):
    return a is not None and b is not None and a.lower() == b.lower()


def report(swipes):
    practice = [s for s in swipes if "target" in s]
    daily = [s for s in swipes if "target" not in s]
    print(f"{len(swipes)} swipes: {len(practice)} practice, {len(daily)} while typing")
    if daily:
        deleted = sum(s["outcome"] == "deleted" for s in daily)
        picked = sum(s["outcome"] == "picked" for s in daily)
        print(f"while typing: {deleted} deleted again ({100 * deleted / len(daily):.0f}%), "
              f"{picked} replaced by another suggestion ({100 * picked / len(daily):.0f}%)")
    known = [s for s in swipes if meant(s) is not None and ("target" in s or s["outcome"] == "picked")]
    if practice:
        right = sum(same(s["typed"], s["target"]) for s in practice)
        listed = sum(any(same(r["word"], s["target"]) for r in s["results"]) for s in practice)
        print(f"practice, live on the phone: top-1 {100 * right / len(practice):.1f}%, "
              f"among the engine's answers {100 * listed / len(practice):.1f}%")
    wrong = [s for s in known if not same(s["typed"], meant(s))]
    if wrong:
        print("\nwrong words (meant <- came out; where the meant word stood in the engine's answers):")
    for s in wrong:
        want = meant(s)
        rank = next((i + 1 for i, r in enumerate(s["results"]) if same(r["word"], want)), None)
        top = s["results"][0] if s["results"] else None
        there = next((r for r in s["results"] if same(r["word"], want)), None)
        detail = "not among them" if rank is None else f"place {rank}, ctc {there['ctc']:.2f} lm {there['lm']:.2f}"
        first = "" if top is None else f"; first was {top['word']} ctc {top['ctc']:.2f} lm {top['lm']:.2f}"
        print(f"  {want} <- {s['typed']}   ({detail}{first}; after: {' '.join(s['before'][-4:])})")
    keys = next((s["keys"] for s in swipes if s["keys"]), None)
    if keys:
        print(f"\nkeys on the phone: letter area {keys['width']:.0f} x {keys['height']:.0f} px "
              f"(width / height {keys['width'] / keys['height']:.2f}; the training data is around 2.5)")
        off = [(c, keys["cx"][i] - STOCK[c][0], keys["cy"][i] - STOCK[c][1]) for i, c in enumerate(keys["letters"]) if c in STOCK]
        worst = max(off, key=lambda o: abs(o[1]) + abs(o[2]), default=None)
        if worst:
            print(f"largest difference to the stock QWERTY key centers: {worst[0]} by {worst[1]:+.3f}, {worst[2]:+.3f}")
    return known


def write_replay(swipes, folder):
    """Swipes in the form the library's infer_jsonl reads, and the phone's key positions as its layout file."""
    os.makedirs(folder, exist_ok=True)
    keys = None
    with open(os.path.join(folder, "recorded.jsonl"), "w") as out:
        for n, s in enumerate(swipes):
            keys = s["keys"]
            word = meant(s)
            data = [{"t": t, "x": x / keys["width"], "y": (y - keys["top"]) / keys["height"]}
                    for x, y, t in zip(s["x"], s["y"], s["t"])]
            out.write(json.dumps({"id": n, "word": word, "data": data,
                                  "sentence": " ".join(s["before"] + [word]), "word_idx": len(s["before"])}) + "\n")
    layout = {"name": "recorded", "letters": "".join(sorted(keys["letters"])), "keys": [
        {"letter": c, "cx": keys["cx"][i], "cy": keys["cy"][i], "rx": 0.05, "ry": 0.1667}
        for i, c in sorted(enumerate(keys["letters"]), key=lambda p: p[1])]}
    json.dump(layout, open(os.path.join(folder, "recorded-layout.json"), "w"))


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    swipes = load(sys.argv[1])
    if not swipes:
        sys.exit("no swipes in that file")
    known = report(swipes)
    if "--replay" not in sys.argv:
        return
    if not known:
        sys.exit("\nnothing to replay: no swipe with a known answer (use the swipe practice)")
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
    folder = os.path.join(root, "swipe", "data")
    write_replay(known, folder)
    print(f"\nreplaying {len(known)} swipes with a known answer")
    for name, layout in (("the phone's keys", os.path.join(folder, "recorded-layout.json")), ("stock QWERTY keys", "")):
        env = dict(os.environ, SWIPES_FILE=os.path.join(folder, "recorded.jsonl"), LAYOUT_FILE=layout)
        result = subprocess.run([os.path.join(root, "scripts", "swipe-benchmark.sh")], env=env, capture_output=True, text=True)
        print(f"  {name}: {(result.stdout.strip().splitlines() or [result.stderr.strip()])[-1]}")


if __name__ == "__main__":
    main()
