# Caret roadmap

Caret is alpha. The goal is a keyboard that is as pleasant to use as Gboard, types and swipes at least
as well as FUTO Keyboard, learns from the person using it, and is free software all the way down.

## Principles

- **GPL-3.0 for everything we make**: the app, the native engine changes, the training code, and the
  models we train. Third-party pieces keep their own licenses and are listed in the About screen.
  Anything Caret ships must be ours or under a license that allows it; a part that is not gets
  replaced (see "Own models").
- **Nothing leaves the phone** unless the user saves a file and sends it on.
- **No setting is removed** to look simpler. Settings get reordered: the common ones first, the rare
  ones lower down or under "Advanced" in the same category, and all of them findable by search.
- **Measured from a clean start.** The benchmark sentences repeat, and a keyboard that has typed them before has learned them: `scripts/dev/typing-benchmark-3.sh` wipes what was learned first. Numbers from before 2026-10-07 (Caret 97.2%, Gboard 97.8%, FUTO 93.2%) were not measured that way; Caret from a clean start is at 95.4%, the other two are to be measured again.
- **Aimed at the keys as drawn.** Since 2026-10-09 the benchmark taps the middle of each key as it is drawn, not of its touch area (larger at the edges of the keyboard); numbers from before that day do not compare with later ones.
- **Measured, not guessed.** Every change to typing, swipe or prediction is checked with the
  benchmarks before it ships: `scripts/typing-benchmark.py` (sloppy taps, plus clean rare words for
  false corrections), `scripts/swipe-benchmark.sh` (FUTO's swipe test set), the swipe recording and
  `scripts/swipe-replay.py` for real swipes, and `scripts/smoke.sh` on the emulator.

## Where it stands (0.1.14)

- HeliBoard 4.1 underneath, all Kotlin, renamed to `app.caret`, signed releases.
- Swipe: FUTO's swipe library and models; the letter-order bug that made it worse than FUTO is fixed.
- Typing: 97.2% of sloppy taps right on the benchmark, against Gboard 97.8% and FUTO 93.2% on the
  same taps; no correctly typed rare word changed by any of them.
- Look: keyboard and strip measured to Gboard's; all actions panel with drag onto the toolbar;
  toolbar row above the suggestions; M3E settings.
- Learning: corrections are recorded on the phone (undone, picked, retyped).
- Dictation: the microphone key streams speech through Alyo, with a chime and a pulsing circle.
- Typing model: base model training on the Dell (run1, 1.5 billion tokens, about 3 days).

## 1. Look and feel: the Gboard spec

Side by side with Gboard 18.2 on the emulator (screenshots in `~/Documents/caret-spec/`, outside the
repo since they are Google's), one surface at a time: measure, write down how it looks and behaves,
then rebuild Caret's to match or beat it.

1. Keyboard and suggestion strip: key sizes and gaps, corner radii, label sizes, popup previews,
   long-press popups, the shift and symbols layers, the number row option.
2. Toolbar: Gboard's grid opens a panel of large labeled tiles that can be dragged into the strip.
   Keep Caret's pinned keys on the strip as well.
3. Emoji panel: search, categories, recent, skin tones, the ABC / emoji / emoticon tabs.
4. Clipboard, text editing, one-handed and floating modes.
5. Settings: same categories and the same options, reordered by how often they are used, with an
   "Advanced" section per category, good search, and plain summaries.
6. First-run setup and the empty states (no languages, no models).
7. Motion: panel transitions, key press feedback, strip changes.

Done when every surface in the spec has a Caret screenshot next to Gboard's and the differences left
are deliberate.

## 2. Own models, all GPL

What Caret ships today that is not ours: FUTO's swipe models (FUTO Model Weights License). The swipe
library is GPL-3.0 and stays.

- **Training box**: the Dell Precision 5540 (Quadro T2000, 4 GB) on the LAN, driven over SSH from the
  Mac, jobs keep running when the Mac is away. Measured 5,800 tokens a second for a 34M model in full
  precision, about 2 days per billion tokens.
- **Typing model** (the big one): a small transformer (about 40M parameters) that reads the sentence
  so far and the actual tap positions of the current word, and writes the word that was meant. The
  premise is FUTO's (taps go in as positions or as blends of the nearest keys), the code and weights
  are ours. Steps: openly licensed English corpus with plenty of casual text, tokenizer, base model
  (2 to 6 days on the Dell), then a fine-tune on simulated typos from real key geometry.
- **In the keyboard**: run it with llama.cpp (MIT) or the ExecuTorch runtime the swipe library
  already uses, merge its guesses with the dictionary engine, measure with the typing benchmark.
  Model files can be imported, so community models in the same format work too.
- **Swipe models**: retrain encoder, decoder and context model on FUTO's MIT-licensed swipe dataset
  with our own GPL training code, then switch Caret to them once they match FUTO's on the benchmark.
- **Other languages** after English works end to end.

Done when the typing benchmark beats FUTO Keyboard on the same taps, and no non-GPL-compatible model
ships in the app.

## 3. Learning the person

1. **Correction recorder**: when a word is backspaced and retyped, a suggestion is picked instead, or
   an autocorrect is undone, keep the taps, the sentence before, what came out and what was meant.
   Private, on the phone, viewable and deletable, never in password fields or incognito. The same
   format the training scripts read, so a user can choose to export it.
2. **Touch calibration**: learn where this person's fingers really land and shift the key targets.
3. **Personal corrections**: remember "teh" to "the", names and slang, and stop correcting a word the
   user keeps restoring.
4. **On-phone fine-tuning**: a small adapter on the typing model, trained overnight while charging.

Done when the typing benchmark on the user's own recorded corrections improves week over week.

## 4. Settings backup that stays small

Export only the settings that differ from the defaults, as a readable file of a few kilobytes.
Personal dictionary, history, layouts and models are separate checkboxes, off by default for models.
Import shows what will change before applying.

## 5. Dictation polish (Caret and Alyo)

- While streaming, show the words in place in the text field (or the strip) with the word being
  spoken highlighted, so it is clear what is new and still changing.
- No stray periods: Alyo ends a "sentence" at every pause and punctuates it. Pauses should not add
  punctuation by themselves; keep spoken punctuation, and make automatic punctuation a setting.
- Smooth hand-over from partial to final text without the line jumping.
- Measure with recorded audio clips: words right, punctuation added that was not said.

## 6. Toward 1.0

- Public repository, F-Droid build, reproducible releases.
- Translations of everything new, accessibility pass with TalkBack, tablets and foldables.
- Crash-free on the smoke tests across a few emulator sizes.

## Work queue

Worked down in this order without waiting for a go-ahead; each item ships as its own release when it
is visible to the user. Things that need the user's eyes or a decision are collected under "Asks".

| # | Item | Status |
| --- | --- | --- |
| 1 | Gboard spec: keyboard and strip | done (0.1.12) |
| 2 | Gboard spec: all actions panel | done (0.1.13) |
| 3 | Correction recorder | done (0.1.14) |
| 4 | Settings backup that stays small | done (0.1.15) |
| 5 | Dictation polish in Caret and Alyo: current word highlighted, no pause periods, smooth partial to final | done in Caret (0.1.16), Alyo's recognizer and Alyo's own keyboard (2026-10-04); delete takes a dictated phrase back whole (0.1.23); not yet checked with real speech |
| 6 | Gboard spec: long-press popups and key previews | done (0.1.17) |
| 7 | Gboard spec: emoji panel | done (0.1.18, taller panel and section title in 0.1.22) |
| 8 | Gboard spec: clipboard, one-handed, floating | done (0.1.18; floating keyboard as a proportional rounded card in 0.1.22; one-handed already matched) |
| 9 | Gboard spec: settings layout (reorder, Advanced per category, nothing removed) | done (0.1.19) |
| 10 | Typing model runtime in Caret (llama.cpp), tested on a small stand-in model | done: `lm/native` + `TypingModel`, a GGUF file imported under Text correction > Advanced ranks corrections and next words. With an early checkpoint (4% of training): 97.1% against 96.8% with the swipe context model, no clean rare word changed. About 30 ms per keystroke on the emulator, in the background; to measure on a phone and to speed up (quantization, ARM dot product) |
| 10b | Gboard defaults (edge margin, ?123 pill, vibration on) and nine more color themes of our own (FUTO's cannot be copied: its license is not GPL-compatible) | done (0.1.20) |
| 11 | Typing model in autocorrect, benchmarked from a clean start | done (0.1.30), not the way first planned. Three fine-tunes that read the typed keys (`training/typing_data.py`) fixed sloppy words but took rare words for slips toward common ones, clean taps included: a 34M model cannot spell rare words from letters. What ships is the plain base model for how a word fits the sentence plus `TapEvidence.fit` for how the taps fit its letters (`Suggest.fitToTaps`), settings found with `scripts/dev/taps-replay.py`. Sloppy sentences 98.0% against 96.4% (97.2% against 95.7% on two sets not tuned on), clean rare words 100%, rare words tapped a little off 95.2% against 97.1%. Gboard and FUTO still to be measured the same clean way |
| 12 | Touch calibration from recorded corrections | |
| 12b | Likely letters get a larger touch target while typing (as the iPhone keyboard does): only taps near a key's edge move, with a switch | done (0.1.29, `LikelyKeys`): with autocorrect off, words that come out letter for letter went from 57% to 69% on the sloppy taps; cleanly tapped rare words unchanged |
| 13 | Personal corrections (remember fixes, stop correcting restored words) | done (0.1.21): a fix made twice is applied from then on, a word taken back twice is left alone, taking a learned fix back unlearns it (`PersonalCorrections`) |
| 14 | Own GPL swipe models on FUTO's MIT dataset | done (0.1.33): one encoder of our own (1.7M parameters, `training/swipe_*.py`) in the app. On 1,936 test swipes: 91.7% top-1 and 95.0% top-4, against FUTO's encoder alone 91.0% / 94.6% and FUTO's three models 92.4% / 95.1%. FUTO's models are no longer downloaded or used. A context step of our own came in 0.1.34: the typing model's log probability of each candidate after the words before, times 0.3, added to the engine's score (`FutoSwipe.bySentence`, weight from `scripts/dev/swipe-rerank.py`): 92.4% top-1 on the test swipes, as good as FUTO's three models. Tuning the library's scoring weights gained 0.8 on the swipes it was tuned on and nothing on the test swipes, so they stay as they are |
| 15 | Gboard spec: first-run setup, motion | first-run done (voice typing step in 0.1.26), text editing layout and hint weight in 0.1.25; motion left (needs screen recordings) |
| 16 | 1.0: translations, TalkBack, tablets, public repo, F-Droid | |

### Asks

- Signal swipe-home fix (0.1.11) and the new look (0.1.12 to 0.1.14): try on the phone and say what
  feels off.
- Supertonic 3 vs Piper hfc for Alyo and Vela: Supertonic is archived; compare only if wanted.

## Order

1. Gboard spec, surface by surface (ongoing, shipped as it lands).
2. Correction recorder (cheap, starts collecting real data).
3. Typing model on the Dell, then in the keyboard.
4. Settings backup.
5. Dictation polish.
6. Own swipe models, touch calibration, personal corrections, on-phone fine-tuning.
7. 1.0 work.
