# SPDX-License-Identifier: GPL-3.0-only
"""Caret's swipe encoder: reads a swipe and says, for each of 32 moments along it, which key it is at.

It takes what the swipe library hands an encoder and gives back what the library reads from one:

    features     [1, 2, 64]   the swipe, x and then y, 0 to 1 across and down the keyboard
    layout_keys  [1, 64, 2]   the middle of each key, real keys first
    layout_mask  [1, 64]      true for real keys
 -> log_emissions [1, 32, 65]  log probability of each key at each moment, and of "no new key" last
    coefficients  [1, 32, 1]   zeros (only a second, optional model of FUTO's reads these)
    mixing        [1, 32, 1]   zeros (the same)

The keys are not built in: a key is known by where it is, so any layout works. Each moment of the
swipe is turned into a direction in a space where keys also live, by their place; how well the two
point the same way is the score of that key, to which how near the finger is to the key is added.
"""
import math
import torch
from torch import nn

POINTS, MOMENTS, MAX_KEYS = 64, 32, 64


def waves(xy, count):
    """Places as sines and cosines at doubling pitches, so that a network can tell near places apart."""
    out = [xy]
    for k in range(count):
        out += [torch.sin(xy * (math.pi * 2 ** k)), torch.cos(xy * (math.pi * 2 ** k))]
    return torch.cat(out, dim=-1)


class Block(nn.Module):
    def __init__(self, width, dilation):
        super().__init__()
        self.depth = nn.Conv1d(width, width, 7, padding=3 * dilation, dilation=dilation, groups=width)
        self.norm = nn.GroupNorm(8, width)
        self.up = nn.Conv1d(width, width * 3, 1)
        self.down = nn.Conv1d(width * 3, width, 1)

    def forward(self, x):
        return x + self.down(torch.nn.functional.gelu(self.up(self.norm(self.depth(x)))))


class SwipeEncoder(nn.Module):
    def __init__(self, width=160, space=96, dilations=(1, 2, 3, 5, 8, 1, 2, 3)):
        super().__init__()
        self.stem = nn.Conv1d(4 + 2 * 2 * 4 + 2, width, 1)  # place, movement, waves of the place
        self.blocks = nn.Sequential(*[Block(width, d) for d in dilations])
        self.halve = nn.Conv1d(width, width, 4, stride=2, padding=1)
        self.tail = nn.Sequential(*[Block(width, d) for d in (1, 2)])
        self.moment = nn.Linear(width, space)
        self.blank = nn.Linear(width, 1)
        self.key = nn.Sequential(nn.Linear(2 + 2 * 2 * 6, 192), nn.GELU(), nn.Linear(192, space))
        self.match = nn.Parameter(torch.tensor(8.0))
        self.near = nn.Parameter(torch.tensor(30.0))

    def forward(self, features, layout_keys, layout_mask):
        place = features.transpose(1, 2)                                   # [B, 64, 2]
        movement = torch.cat([place[:, 1:] - place[:, :-1], torch.zeros_like(place[:, :1])], dim=1) * 16
        x = torch.cat([waves(place, 4), movement, place], dim=-1).transpose(1, 2)
        x = self.tail(self.halve(self.blocks(self.stem(x)))).transpose(1, 2)  # [B, 32, width]
        moment = torch.nn.functional.normalize(self.moment(x), dim=-1)
        key = torch.nn.functional.normalize(self.key(waves(layout_keys, 6)), dim=-1)  # [B, 64, space]
        score = self.match * torch.matmul(moment, key.transpose(1, 2))     # [B, 32, 64]
        # where the finger is at each moment (two points of the swipe per moment) against where the keys are
        finger = (place[:, 0::2] + place[:, 1::2]) / 2                      # [B, 32, 2]
        gap = finger.unsqueeze(2) - layout_keys.unsqueeze(1)                # [B, 32, 64, 2]
        score = score - self.near * (gap * gap).sum(-1)
        score = score.masked_fill(~layout_mask.unsqueeze(1), -1e4)
        logits = torch.cat([score, self.blank(x)], dim=-1)                  # the blank goes last
        zeros = torch.zeros_like(logits[:, :, :1])
        return torch.log_softmax(logits, dim=-1), zeros, zeros
