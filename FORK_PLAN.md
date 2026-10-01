# Fork plan

A hard fork of HeliBoard (GPL-3.0). Name: Caret, package id `app.caret`. Java packages keep the `helium314.keyboard` name so upstream changes still merge.

## Goal

A fully free keyboard that looks and feels like Gboard, has FUTO Keyboard's feature set, swipes
well, and hands dictation to Alyo through the microphone key.

## Rules

- Stays GPL-3.0. No code from FUTO Keyboard itself (Source First License is not GPL compatible).
  FUTO's swipe library is GPL-3.0-or-later and is fine.
- FUTO swipe weights are under the FUTO Model Weights License (attribution shown to the user,
  modifications disclosed). Download them on first use, do not bundle them in the APK.
- Gboard is the reference for layout, spacing, shapes and behavior. No Google logos, fonts with
  restrictive licenses, or copied image assets.
- Upstream HeliBoard does not take AI-generated PRs. Small, hand-checked bug fixes only go back.

## What HeliBoard already has (checked in v4.1 source)

- Toolbar keys: voice, clipboard, numpad, dpad, undo, redo, select all, select word, copy, cut,
  paste, one-handed, floating, split, incognito, autocorrect toggle, emoji, arrows, word and page
  movement. Reorderable, with pinned keys.
- One-handed, split and floating modes. Scales for height, bottom row, padding, key gap and fonts.
- Clipboard history, custom colors, Material You colors, custom layouts.
- Settings written in Compose with Material 3.
- Import of Google's closed swipe library by the user (`JniUtils.java`). This stays as an option.

## Gaps against the target

| Gap | Notes |
| --- | --- |
| Free swipe typing | Add FUTO swipe library. It needs minSdk 24 and ExecuTorch. |
| Gboard look | Key shapes, spacing, popups, suggestion strip, toolbar, emoji and clipboard panels. These are legacy views and XML. |
| M3 Expressive settings | Raise minSdk (21 to 24 or higher), move to a current Compose BOM, restyle. |
| Mic placement | Pin the mic to the left or right end of the strip, with its own size setting. |
| Action organization like FUTO | Action key beside the spacebar, pinned actions, favorites, overflow, hidden. |
| Text editor action, theme switcher action | Not in HeliBoard. |
| Next-word and transformer prediction | FUTO uses a transformer language model. The swipe library's context model exposes `predictNext`; evaluate later. |
| Automated tests | 14 test files today. See below. |

## Swipe integration

- Entry point for gesture suggestions: `getSuggestedWordsForBatchInput` in `Suggest.kt`.
- Shape of the adapter: a `Dictionary` subclass that only answers batch (swipe) input. It turns
  `InputPointers` plus the key centers of the current `Keyboard` into normalized points, calls the
  library's `SwipeDecoder`, and returns `SuggestedWordInfo` items. Registered next to the other
  dictionaries in the facilitator, so capitalization, next-word handling and the strip stay as is.
- Building the library on macOS needs cmake 3.29+, the ExecuTorch submodule, and Makefile paths
  changed from `linux-x86_64` to the host NDK prebuilt directory.
- Engine choice setting: FUTO library (default), user-imported Google library, off. Leave room for
  HeliBoard's own NLnet-funded library when it ships.
- The library wants normalized 0..1 coordinates with millisecond timestamps, a layout description
  (letters and key centers) and a trie dictionary through its `ITrie` interface. HeliBoard's
  dictionaries are compiled `.dict` files, so either implement `ITrie` over the native dictionary
  or ship `.combined` word lists for the swipe engine.
- Models: encoder (layout agnostic), English QWERTY decoder, English context model. Other
  languages run encoder-only.

## Swipe status

- Works on the emulator: `scripts/build-swipe.sh`, `scripts/swipe-models.sh install`, then swipe.
- Benchmark: `scripts/swipe-benchmark.sh` replays real swipes from FUTO's test split with Caret's
  own word list. First result: top-1 92.3%, top-4 94.9% on 1936 swipes. The baseline is in
  `swipe/benchmark-baseline.txt`.
- Open: models reach the app by adb only (needs an import or download screen); the word list is
  written and the models are loaded on the first swipe, which blocks for a few seconds; only the
  main dictionary is used, not personal words or typing history; no setting to pick the engine yet;
  only arm64 is built.

## Testing system

1. Logic tests on the JVM (extend `InputLogicTest.kt`, `InputTest.kt`, `SuggestTest.kt`): scripted
   key and swipe events against a fake text field, including fields that misbehave (slow replies,
   no cursor info, text changed underneath).
2. Swipe benchmark: replay FUTO's MIT dataset through each engine, report top-1 and top-3 accuracy
   and latency. Fails the build on a regression.
3. Emulator tests: real touches into real field types (plain, password, URL, web view, Compose,
   terminal), plus screenshot tests of the keyboard in each theme and mode.
4. CI runs 1 and 2 on every push, 3 nightly.

## Order of work

1. Rename and new package id (done). Private repo, no release channels yet.
2. Test harness layers 1 and 2, so there is a baseline before anything changes.
3. Swipe library wired in behind the engine setting.
4. Mic placement and size. Action organization.
5. Gboard look for the keyboard surface. Screenshot tests land with it.
6. M3 Expressive settings.
7. Refactor the legacy core in small pieces, only where tests cover it.
