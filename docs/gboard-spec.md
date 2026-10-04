# Gboard spec

Caret side by side with Gboard 18.2.8 (arm64, from APKMirror, Google's signature) on the `caret_phone`
emulator: 1080 x 2340 pixels at 420 dpi, so 1 dp = 2.625 px. Dark theme, English (US), default
settings, no Google account. Screenshots live in `~/Documents/caret-spec/` (outside the repo, they
show Google's app). Numbers are in dp, measured from the accessibility tree and from screenshots.

Each section ends with what Caret does differently and what to change. "Same" means no change.

## 1. Keyboard and suggestion strip

| | Gboard | Caret 0.1.10 |
| --- | --- | --- |
| Suggestion strip height | 44 dp | 40 dp |
| Gap from strip to first key row | 8 dp | 6 dp |
| Key row pitch | 59 dp | 61 dp |
| Total height, strip to bottom edge | 288 dp | 291 dp |
| Margin left and right of the keys | 2 dp | 0 |
| Letter key pitch (touch area) | 40.8 dp | 41.1 dp |
| Drawn letter key | 35.4 x 46.9 dp | to measure |
| Gap between drawn keys | 5.3 dp across, 11.8 dp down | to measure |
| Key corner radius | about 7 dp | to measure |
| Bottom row, left to right | ?123 61, comma 41, emoji 41, space 163, period 41, enter 61 dp | symbols 62, comma 41, emoji 41, space 165, period 41, enter 62 dp |
| Number hints on the top row | top right of each key, small | top right, small |

Colors (dark theme): keyboard and strip background `#1F1F23`, letter keys `#38393C`, shift / enter /
?123 `#3F4759` (a blue-grey tint), space `#38393C`, labels near white `#E3E2E6`.

Strip: the features (grid) button on the left at 42 dp square, three suggestions sharing the rest,
the center one a bit wider, thin vertical dividers, the voice key on the right. Suggestions use a
regular weight; the word space would insert is not boxed, just in the center.

Done in 0.1.12: strip 44 dp; rows 59 dp apart with Gboard's taller gaps (drawn keys about 47 dp high),
the first row at the same height; only the top row's number hints on the keys (the rest stays a long
press away); the enter key a tonal pill in the function key color instead of the accent; shift,
delete and enter a dark tint of the secondary color at night; letter keys a little lighter at night;
the emoji key colored like a letter key.

Still open: the 2 dp side margin, ?123 as a full pill, label sizes.

## 2. Toolbar (features panel)

Gboard's grid button does not add a row. It replaces the keyboard with a page of large tiles
(about 80 x 55 dp, icon above a label): One-handed, Text editing, Translate, Floating, Resize,
Emoji, Undo, and so on, under a "Hold and drag to customize" hint. The strip turns into a row of
quick icons (stickers, GIF, clipboard, settings, theme, voice) with a back arrow on the left, and a
tile can be dragged up into that row; a "Drag here to replace" bubble shows where it lands.

Caret changes: an "All actions" page in that style (Caret already has `ActionsPanelView`), with
drag into the strip; keep pinned keys on the suggestion strip as Caret has now.

## 3. Emoji panel

A search field at the top left, category icons across the top with the current one in a filled
circle, a section title ("Smileys & Emotions"), 8 emoji per row, and at the bottom ABC on the left,
then tabs for emoji, GIF, stickers and emoticons, and delete on the right.

## To capture next

Long-press popups and key previews, clipboard, text editing, one-handed and floating modes, the
settings screens, first-run setup, motion.
