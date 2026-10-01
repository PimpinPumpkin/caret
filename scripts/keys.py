#!/usr/bin/env python3
"""Taps or swipes words on Caret's default QWERTY keyboard on a 1080x2340 screen.

    keys.py tap WORD...      taps each letter
    keys.py swipe WORD...    one swipe per word, through the letter centers
"""
import subprocess
import sys

ROWS = [("qwertyuiop", 54, 1755), ("asdfghjkl", 108, 1919), ("zxcvbnm", 216, 2083)]
KEYS = {c: (x0 + 108 * i, y) for letters, x0, y in ROWS for i, c in enumerate(letters)}
SPACE = (540, 2240)


def shell(commands):
    subprocess.run(["adb", "shell", ";".join(commands)], check=True)


def swipe(word, steps=4):
    centers = [KEYS[c] for c in word]
    points = []
    for a, b in zip(centers, centers[1:]):
        for s in range(steps):
            points.append((a[0] + (b[0] - a[0]) * s / steps, a[1] + (b[1] - a[1]) * s / steps))
    points.append(centers[-1])
    commands = ["input motionevent DOWN %d %d" % points[0]]
    commands += ["input motionevent MOVE %d %d" % p for p in points[1:]]
    commands += ["input motionevent UP %d %d" % points[-1]]
    shell(commands)


mode, words = sys.argv[1], sys.argv[2:]
for n, word in enumerate(words):
    if mode == "swipe":
        swipe(word)
        subprocess.run(["sleep", "1.2"])
    else:
        if n:
            shell(["input tap %d %d" % SPACE])
        shell(["input tap %d %d" % KEYS[c] for c in word])
