# Contributing

## Getting started

Caret builds with Gradle and the Android Gradle Plugin. Open the project in
[Android Studio](https://developer.android.com/studio) or build from the command line:

```
./gradlew assembleDebug
scripts/test.sh
```

The unit tests need Java 21. `scripts/smoke.sh` runs an end-to-end check on an emulator.

# About the Code

Caret is a fork of Caret, which is based on the AOSP keyboard, and in many places still contains mostly the original code. There are some extensions, and some parts have been replaced completely.
When working on this app, you will likely notice its rather large size, and quite different code styles and often ancient comments and _TODO_s, where the latter are typically untouched since AOSP times.
Unfortunately a lot of the old code is hard to read or to fully understand with all of its intended (and unintended) consequences.

Some hints for finding what you're looking for:
* Layouts: stored in `layouts` folder in assets, interpreted by `KeyboardParser` and `TextKeyData`
  * Popups: either on layouts, or in `locale_key_texts` (mostly letter variations for specific languages that are not dependent on layout)
* Touch and swipe input handling: `PointerTracker`
* Handling of keycode / text inputs: `InputLogic`
  * chain: `PointerTracker` -> `KeyboardActionListenerImpl` -> `LatinIME` -> `InputLogic`
* Suggestions: `DictionaryFacilitatorImpl`, `Suggest`, `InputLogic`, and `SuggestionStripView` (in order from creation to display, omitting the native library)
* Communication with the app / text field (inputs, reading current text): `RichInputConnection`
* Receiving events and information from the app / text field: `LatinIME`
* Settings are in `SettingsValues`, with some functionality in `Settings` and the default values in `Default`

# Guidelines

- One topic per pull request, described so a reviewer can follow what changes and why.
- Changes to typing behavior (`InputLogic`, `Suggest`, `RichInputConnection`, `PointerTracker`,
  `LatinIME`) come with a test. These parts are easy to break in ways that only show up in
  particular apps. See the refactoring rules in [FORK_PLAN.md](FORK_PLAN.md).
- Moves and renames do not change behavior, and behavior changes do not ride along with moves.
- Code must be compatible with GPL-3.0. Say where code comes from if you did not write it.
- No code from FUTO Keyboard itself: its license is not compatible with the GPL. The FUTO swipe
  library is GPL and is fine.
- New user-facing text goes into `app/src/main/res/values/strings.xml`.

# Changes from HeliBoard

Before taking a pull request or a fix from HeliBoard, read the whole diff and its discussion, and
record the decision in [docs/UPSTREAM.md](docs/UPSTREAM.md).

# Translations

The translations were inherited from HeliBoard. Caret has no translation platform yet; strings
added by Caret are English only for now.
