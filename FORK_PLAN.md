# Fork plan

A hard fork of HeliBoard (GPL-3.0). Name: Caret, package id `app.caret`. The code package is `app.caret` too (renamed from `helium314.keyboard`), so upstream changes are ported by hand, not merged.

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
- Models: Gesture Typing settings has a download (from huggingface.co, checksums pinned) and a
  zip import. Caret has the internet permission for this download only.
- Models and dictionary load in the background when a keyboard is shown, not on the first swipe.
- Personal dictionary, typing history and contacts are a second trie, rebuilt every five minutes.
- `SwipeLayoutTest` covers the key position mapping.
- Built for arm64, 32-bit ARM and x86_64 (`scripts/build-swipe.sh arm64-v8a armeabi-v7a x86_64`).
- Candidate scores keep the engine's gaps, so Suggest's "prefer a word typed before in this
  context" rule cannot promote a clearly worse candidate.
- Open: non-English runs on the encoder alone and is untested; a made-up
  personal word has not been swiped end to end yet.

## Look status

- Default look is the Rounded style reshaped toward Gboard: flat 8dp keys, tinted function keys,
  pill enter key, outlined shift arrow, emoji key, system colors on Android 12+, Google Sans Flex
  (OFL) as the built-in font. Light and dark checked on the emulator.
- Microphone: pinned to the strip by default, with "Microphone on the left" and "Microphone size"
  in Toolbar settings. It only shows when a voice keyboard such as Alyo is enabled.
- Resize: a "Resize keyboard" toolbar action puts drag handles over the keys. Top handle sets
  height, side handles set side padding, bottom handle sets bottom padding, with Reset and Done.
  It writes the same per-orientation preferences as the sliders in Appearance settings. The Rounded
  style now honors side padding (it ignored it upstream).
- The toolbar opener is a tonal button with a grid icon. Resize mode ends when the keyboard hides.
- Open: key press popup, emoji and clipboard panels,
  symbol layouts, the settings app.

## Settings status

- Material 3 Expressive: Compose BOM 2026.09.00 and material3 1.5.0-alpha29 (same as Alyo), Google
  Sans Flex type, system colors, rows drawn as grouped cards (`RowPosition`, `PreferenceGroup` in
  `Preference.kt`), tonal icon badges on the main screen, check marks in switches.
- Emoji: "Get emoji font from a link" in Appearance downloads a font from a link the user enters.
  Caret ships no emoji font and names no source. Not tested on a device yet.
- List screens (languages, dictionaries, colors, personal dictionaries) use the same cards.
- Dialogs: large corners, raised tone, filled main button (`ThreeButtonAlertDialog`).
- Open: expressive motion and the large collapsing title are not done.

## Actions status

- "Key next to the spacebar" in Toolbar settings: any toolbar action (default emoji). Hidden with
  the existing "Emoji key" switch.
- Favorites and overflow: the toolbar holds a short list of favorites and ends with "All actions"
  (three dots), which opens a grid of every action over the keys (`ActionsPanelView`).
- Offensive words are not blocked by default. The switch in Text correction still exists, and the
  swipe word list follows it.

## Testing system

- Misbehaving text fields: the fake field in `Shadows.kt` can hide its text (`hidesText`) or
  rewrite itself after every edit (`afterEdit`). `InputLogicTest` has cases for a field that hides
  text, an address bar that completes what is typed, and an app that clears its field.
- `scripts/smoke.sh`: installs on the caret_phone emulator, taps and swipes words, compares the
  text field, checks the log for crashes. `scripts/keys.py` holds the key positions.


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

## Refactoring the legacy core

Rules:
- Tests first. Before a piece is touched, every branch of it has a test in `InputLogicTest` (or a
  new test file), including the misbehaving-field cases where they apply.
- One piece per commit, and a commit that moves code does not change behavior.
- After each piece: `scripts/test.sh`, then `scripts/smoke.sh` on the emulator, then try the
  touched behavior by hand on the emulator.
- Converting a file to Kotlin is its own commit, after the file has been split into pieces small
  enough to review.

Done:
- Backspace (`handleBackspaceEvent`): six new tests, then split into `backspaceInComposingWord`,
  `revertOnBackspace`, `backspaceSelection`, `backspaceWithKeyEvents`, `backspaceBeforeCursor`.

- Picking a suggestion (`onPickSuggestionManually`, `commitChosenWord`): eight new tests, two
  steps pulled out, timing logs removed.
- Separators (`handleSeparatorEvent`): five new tests, split into four steps.

- Plain character input (`handleNonSpecialCharacterEvent`, `handleNonSeparatorEvent`): eight new
  tests, four decisions pulled out into named methods.
- Start of a swipe (`onStartBatchInput`): six new tests, split into two steps.

- Function keys (`handleFunctionalEvent`): eight new tests, long cases pulled out. The tests found
  a bug: after "page start" or "page end" in a field that ignores the key event (Compose fields),
  the next letter went into the previously composed word. Fixed.

- Undoing an autocorrection and cursor moves (`revertCommit`, `onUpdateSelection`): six new tests,
  three steps pulled out.

`InputLogic` is through its first pass: 33 Caret tests were added to the 78 that existed, and no
method but the function-key switch is longer than about 75 lines.

- `RichInputConnection`: its methods were already short, so it got tests, not a split. Six cases
  for apps that change the field under the keyboard, all passing.
- `LatinIME`: three tests for starting and restarting input, then `onStartInputViewInternal`
  (150 lines) split into two steps. Nothing else in it is long.
- `PointerTracker`: no method over 76 lines, left as is. It has no unit tests; touch handling is
  covered only by the smoke test.

- `InputLogic` is Kotlin (`InputLogic.kt`), a faithful translation in one commit. How it was done:
  the file was cut into five ranges on method boundaries, each translated under one written set of
  rules, then joined, compiled and run against the tests. The one runtime problem was a parameter
  Java callers pass as null. When converting the next files, check every non-private function for
  that before running the tests.

- `RichInputConnection`, `PointerTracker` and `LatinIME` are Kotlin too, converted the same way.
  `PointerTracker` got touch tests first (`InputTest.kt`).
- The code package was renamed from `helium314.keyboard` to `app.caret`. The keyboard service is
  now `app.caret.latin.LatinIME`, so an install from before the rename has to enable and select
  the keyboard once more after updating.

- Cleanup pass over the four converted files: `m` and `s` prefixes are gone, `!!` went from 119
  to 31 (the rest guard cases where Java threw and null can really occur, or sit once at the top
  of an SDK or interface override), and getters that only returned a field are properties.

Next:
1. `OnGetSuggestedWordsCallback` in `Suggest.kt` could take a non-null parameter (removes four
   `!!` in `InputLogic.kt`).
2. `WordComposer`, `MainKeyboardView` and `KeyboardSwitcher` are Kotlin now (straight
   conversions). They still need the cleanup pass the first four files got: prefixes, `!!`,
   getters to properties.
