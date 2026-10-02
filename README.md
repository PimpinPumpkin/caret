# Caret

Caret is a free and open source Android keyboard. It aims for the look and feel of Gboard and the
feature set of FUTO Keyboard, under a license that keeps it free: GPL-3.0.

It is a fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard), which is based on OpenBoard and
the AOSP keyboard.

## Features

- Swipe typing with the free [FUTO swipe library](https://gitlab.futo.org/keyboard/swipe-library).
  The models are downloaded once from within the app, or imported from a zip file. Google's
  closed library can still be loaded by the user instead.
- Rounded keys, dynamic colors on Android 12 and newer, custom themes and background images.
- A microphone key that can be pinned to the left or right of the suggestion strip and resized.
  It hands dictation to the voice input app you have installed, for example
  [Alyo](https://github.com/PimpinPumpkin/alyo).
- A toolbar with pinned actions and an all-actions panel: clipboard history, undo, redo, cut,
  copy, paste, select, one-handed, split and floating modes.
- Resize the keyboard by dragging, with separate scales for keys, rows and fonts.
- Hold or swipe backspace to delete whole words.
- Emoji search, an emoji font of your choice, number pad, custom layouts, multilingual typing.
- Dictionaries per language for suggestions and spell check.
- Backup and restore of settings and learned words. Backups from HeliBoard can be restored.
- Settings in Material 3 Expressive.

Requires Android 7 or newer.

## Privacy

Nothing you type leaves the device. The internet permission is used only for downloads you start
yourself: the swipe models and an emoji font. Offensive words are not filtered by default.

## Building

```
./gradlew assembleDebug
```

- `scripts/test.sh` runs the unit tests (needs Java 21).
- `scripts/smoke.sh` types and swipes on an emulator and checks the result.
- `scripts/build-swipe.sh` builds the swipe library, `scripts/swipe-models.sh` fetches the models,
  `scripts/swipe-benchmark.sh` measures swipe accuracy.

[FORK_PLAN.md](FORK_PLAN.md) holds the plan and status. [docs/UPSTREAM.md](docs/UPSTREAM.md)
records which HeliBoard pull requests and issues were taken, and why others were not.

## Reporting issues

Open an issue in this repository with the app version, the Android version, the app you were
typing in, and the steps that lead to the problem. One topic per issue.

## Documentation

- [layouts.md](layouts.md) describes the layout file format.
- The [HeliBoard wiki](https://github.com/HeliBorg/HeliBoard/wiki) still applies to most of the
  app: layouts, dictionaries, hidden features.
- Dictionaries: [aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries).

## License

Caret is licensed under the [GNU General Public License v3.0](LICENSE), like HeliBoard and
OpenBoard before it. The AOSP keyboard it descends from is under [Apache 2.0](LICENSE-Apache-2.0).
Some artwork inherited from HeliBoard is under
[Creative Commons BY-SA 4.0](LICENSE-CC-BY-SA-4.0). Google Sans Flex is under the
[SIL Open Font License](licenses/GoogleSansFlex-OFL.txt).

The swipe library is GPL-3.0-or-later. Its models are under the FUTO Model Weights License and
are not part of this repository or the app package.

## Credits

- [HeliBoard](https://github.com/HeliBorg/HeliBoard) and its contributors
- [OpenBoard](https://github.com/openboard-team/openboard)
- [AOSP Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/)
- [FUTO](https://futo.org) for the swipe library and models
- [LineageOS](https://review.lineageos.org/admin/repos/LineageOS/android_packages_inputmethods_LatinIME),
  [Simple Keyboard](https://github.com/rkkr/simple-keyboard),
  [Indic Keyboard](https://gitlab.com/indicproject/indic-keyboard) and
  [FlorisBoard](https://github.com/florisboard/florisboard/), which HeliBoard drew on
