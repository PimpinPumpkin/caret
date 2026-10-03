#!/usr/bin/env python3
"""How well tap typing comes out when the taps are sloppy, on a device or emulator (ANDROID_SERIAL).

    scripts/typing-benchmark.py [package] [--sigma 0.3] [--sentences 30] [--seed 7]
    scripts/typing-benchmark.py [package] --rare --sigma 0    rarer words typed cleanly: what corrections break
    --save-keys FILE / --keys FILE    reuse key positions for a keyboard that does not report them
    --pause SECONDS                   wait after each word, for keyboards that correct in the background

Focus a text field with the keyboard showing first (a messaging app works; a search field gets no
corrections). Every letter is tapped at its key center plus random noise, sigma times the key size,
from a fixed seed, so two keyboards with the same layout get exactly the same taps. Each sentence
is then read back from the field and compared word by word. Needs the keyboard's keys in the
accessibility tree: switch on any accessibility service before the keyboard appears.

    words right             what the field holds in the end, after corrections
    taps on the right key   how many words were tapped right as they were, for comparison
"""
import json, random, re, subprocess, sys, time

SENTENCES = """
are you coming to the party tonight
i will be there in about ten minutes
can you pick up some milk on the way home
thanks for helping me move last weekend
what time does the movie start
i think we should leave a little earlier
let me know when you get to the airport
the meeting got moved to tomorrow afternoon
did you see the game last night
my phone is almost out of battery
we need to talk about the budget for next month
sorry i missed your call earlier
happy birthday hope you have a great day
can we reschedule our appointment to friday
i forgot my keys at the office again
the weather is supposed to be nice this weekend
do you want to grab lunch together
please send me the document when you have a chance
traffic is terrible so i will be late
that sounds like a really good idea
i just finished reading the book you recommended
how much did you pay for the tickets
remember to water the plants while i am away
the kids are already asleep
could you check if the store is still open
we should probably book the hotel soon
i have a doctor appointment in the morning
thank you so much for the beautiful flowers
my brother is visiting next week
are we still on for dinner on saturday
the internet has been really slow today
i will call you back after work
let us meet at the coffee shop near the station
he said the package should arrive tomorrow
this restaurant has the best pizza in town
i cannot believe how fast this year went by
she wants to learn how to play the guitar
please do not forget to bring your passport
the train was delayed by almost an hour
we are thinking about buying a new car
""".strip().splitlines()

# Real words that are less common, typed without mistakes: a correction here is a wrong one.
RARE = """
the quaint harbor smelled of brine and tar
her aunt knits mittens from alpaca wool
we hiked past the moss covered cairn
the oboe solo sounded wistful and sparse
he spilled cocoa on the wicker ottoman
a lone heron waded through the marsh
they bartered figs for a brass lantern
the vet trimmed the parrot beak
my niece adores glittery unicorn decals
the cobbler resoled my scuffed loafers
a gust toppled the flimsy trellis
we ate kale and lentil stew at dusk
the tutor quizzed him on obscure verbs
an owl hooted near the old silo
she jotted a terse memo about the leak
""".strip().splitlines()

A = ["adb", "shell"]


def sh(cmd):
    return subprocess.run(A + [cmd], capture_output=True, text=True).stdout


def keys(package):
    sh("uiautomator dump --windows /sdcard/k.xml")
    xml = sh("cat /sdcard/k.xml")
    found = {}
    for node in xml.split(">"):
        if package not in node:
            continue
        m = re.search(r'content-desc="([^"]*)".*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if not m:
            continue
        label = m[1].lower().removeprefix("capital ")
        x0, y0, x1, y1 = map(int, m.groups()[1:])
        if len(label) == 1 and label.isalpha():
            # some keyboards (Gboard) report a key twice, its touch area and the drawn key: keep the larger
            old = found.get(label)
            if old is None or (x1 - x0) * (y1 - y0) > (old[2] - old[0]) * (old[3] - old[1]):
                found[label] = (x0, y0, x1, y1)
        elif label == "space":
            found[" "] = (x0, y0, x1, y1)
    screen = int(re.search(r"x(\d+)", sh("wm size"))[1])
    if found and max(b[3] for b in found.values()) < 1000:
        # Some keyboards report key positions within the keyboard view rather than on the screen
        # (HeliBoard did). Its view ends at the bottom of the screen, below a small padding under
        # the bottom row, as Caret's does.
        offset = screen - max(b[3] for b in found.values()) - 8
        found = {k: (a, b + offset, c, d + offset) for k, (a, b, c, d) in found.items()}
    if found and found.get(" ", (0, 0, 0, 0))[1] < screen * 0.75:
        sys.exit("the key positions do not look like the bottom of the screen: stopped before tapping")
    return found


def center(box):
    return (box[0] + box[2]) / 2, (box[1] + box[3]) / 2


def letter_at(layout, x, y, pitch):
    """The letter key whose center is nearest, or None when the tap is off the letter area."""
    # on a letter key's touch area at all, so a sloppy tap never becomes space, comma or shift
    if not any(a <= x < c and b <= y < d for k, (a, b, c, d) in layout.items() if k != " "):
        return None
    return min((k for k in layout if k != " "), key=lambda k: (center(layout[k])[0] - x) ** 2 + (center(layout[k])[1] - y) ** 2)


def field():
    sh("uiautomator dump /sdcard/f.xml")
    xml = sh("cat /sdcard/f.xml")
    for node in xml.split(">"):
        if 'focused="true"' in node:
            m = re.search(r' text="([^"]*)"', node)
            if m:
                return m[1]
    return ""


def clear():
    sh("input keycombination 113 29; input keyevent 67")
    time.sleep(0.5)


def main():
    args = sys.argv[1:]
    package = args[0] if args and not args[0].startswith("--") else "app.caret"
    sigma = float(args[args.index("--sigma") + 1]) if "--sigma" in args else 0.3
    count = int(args[args.index("--sentences") + 1]) if "--sentences" in args else 30
    sentences = RARE if "--rare" in args else SENTENCES
    # keyboards that correct in the background (FUTO's language model) need a moment after each word
    pause = float(args[args.index("--pause") + 1]) if "--pause" in args else 0
    if "--keys" in args:
        # a keyboard that does not show its keys to accessibility services (FUTO) borrows the
        # positions of one with the same layout, saved before with --save-keys
        layout = {k: tuple(v) for k, v in json.load(open(args[args.index("--keys") + 1])).items()}
    else:
        layout = keys(package)
    if "--save-keys" in args:
        json.dump(layout, open(args[args.index("--save-keys") + 1], "w"))
    missing = [c for c in "abcdefghijklmnopqrstuvwxyz " if c not in layout]
    if missing:
        sys.exit(f"keys not found for {package}: {''.join(missing)!r} (accessibility on before the keyboard showed?)")
    # The noise is scaled by the distance between key centers, not by key sizes, which keyboards
    # report differently (touch area or drawn key); so every keyboard gets equally sloppy taps.
    width = center(layout["w"])[0] - center(layout["q"])[0]
    height = center(layout["a"])[1] - center(layout["q"])[1]
    pitch = (width, height)
    rng = random.Random(int(args[args.index("--seed") + 1]) if "--seed" in args else 7)
    words = right = clean = 0
    misses = []
    for number, sentence in enumerate(sentences[:count]):
        # a stray tap can leave the text field (send a message, start a call): stop rather than tap on
        if 'mInputShown=true' not in sh("dumpsys input_method"):
            sys.exit(f"the keyboard is gone before sentence {number + 1}: stopped before tapping anywhere else")
        clear()
        commands = []
        tapped = []
        for word in sentence.split():
            hit = []
            for c in word + " ":
                cx, cy = center(layout[c])
                x, y = cx, cy
                if c != " ":
                    # a sloppy tap lands on the key or a neighbor, not on space or off the keyboard
                    for _ in range(20):
                        x, y = cx + rng.gauss(0, sigma) * width, cy + rng.gauss(0, sigma) * height
                        if letter_at(layout, x, y, pitch) is not None:
                            break
                    else:
                        x, y = cx, cy
                commands.append(f"input tap {int(x)} {int(y)}")
                if c == " " and pause:
                    commands.append(f"sleep {pause}")
                # which key the tap really landed on, to know whether the typo was there to fix
                hit.append(letter_at(layout, x, y, pitch) or " ")
            tapped.append("".join(hit).strip())
        sh(";".join(commands))
        time.sleep(1)
        got = re.sub(r"[^a-z' ]", " ", field().lower()).split()
        want = sentence.split()
        for n, w in enumerate(want):
            words += 1
            ok = n < len(got) and got[n] == w
            right += ok
            clean += tapped[n] == w
            if not ok:
                misses.append(f"{w} <- {got[n] if n < len(got) else '?'} (tapped {tapped[n]})")
        if len(got) != len(want):
            misses.append(f"  (sentence came out with {len(got)} words: {' '.join(got)})")
    print(f"{package}: words right {100 * right / words:.1f}% of {words}, taps on the right key {100 * clean / words:.1f}%")
    for m in misses:
        print("  " + m)


if __name__ == "__main__":
    main()
