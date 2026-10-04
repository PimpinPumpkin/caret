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

Done in 0.1.13: the grid button opens the all actions panel over the keys together with the
toolbar above the suggestions; the panel has a back arrow and "Hold and drag to customize" on top,
Gboard-sized tiles (56 dp, icon above a one-line label), and an action held and dragged onto the
toolbar replaces the key it lands on (or goes at the end). Pinned keys stay on the suggestion strip.
A toolbar setting turns the panel off so the grid only opens the toolbar, as before.

## 3. Emoji panel

A search field at the top left, category icons across the top with the current one in a filled
circle, a section title ("Smileys & Emotions"), 8 emoji per row, and at the bottom ABC on the left,
then tabs for emoji, GIF, stickers and emoticons, and delete on the right.

Done in 0.1.18: the category in view sits in a filled circle. Caret keeps its own bottom row (ABC,
a search key when an emoji dictionary is installed, the space bar, delete): the space bar there is
more useful than GIF and sticker tabs Caret has nothing behind. Still different: Gboard's panel is
taller than the keyboard and shows a section title above each group.

## 4. Key preview and long-press popups

Gboard: pressing a key shows a white, slightly raised bubble above it with the letter (and three
small dots when the key has more behind it). Holding opens a panel rounded all the way (a pill for
one row, a soft rectangle for two), white, with the other keys dimmed behind it; the selected
entry sits in a filled circle. Accents come first and the default is the most common one.

Done in 0.1.17: the preview is a white raised bubble above the key instead of a tall flag over it
(letter at 62% of the key height); the long-press panel has 26 dp corners and the selection is a
pill; the keys dim to 40% while the panel is open.

Still different: Caret's selection uses the accent color with dark text where Gboard uses a dark
fill with white text; Caret lists the number and the symbol of that key in the panel too.

## 5. Settings

Gboard's home screen: Languages, Preferences, Theme, Corrections & suggestions, Glide typing, Voice
typing, Physical keyboard, Clipboard, Dictionary, Emojis, then sharing, privacy and about. Each
page is short and split into titled groups (Preferences: Keys, Appearance, Layout, Shortcuts, Key
press). There is no long list anywhere.

Caret today: nine home entries, but Preferences, Text correction and Toolbar are long flat lists
that mix what everyone changes with what almost nobody touches, and dictation and clipboard
settings are spread over other pages.

Built in 0.1.19 as proposed below, with two differences: Secondary layouts stays a home entry of
its own under Languages & Layouts, and keyboard height and split keyboard moved to Preferences >
Layout rather than staying under Appearance. The fold is `SettingsFold` in a page's list of settings.

Nothing is removed, every setting stays findable by search:

1. **Home**, in this order: Languages & Layouts; Preferences; Appearance; Text correction; Gesture
   typing; Voice typing (new); Clipboard (new); Toolbar; Dictionaries; Advanced; About. Secondary
   layouts moves inside Languages & Layouts.
2. **Voice typing** (new page) takes what is on the Toolbar page now: dictate inside the keyboard,
   the sound, period at every pause, microphone on the left, microphone size.
3. **Clipboard** (new page) takes the clipboard group from Preferences, plus "Suggest clipboard
   content" from Text correction and "Select clipboard toolbar keys" from Toolbar.
4. **Each page starts with what people change** and ends with an "Advanced" group that is folded
   shut until tapped:
   - Preferences: Keys (number row, emoji key, language switch key, key next to the spacebar), Key
     press (popup, vibration, sound), Layout (one-handed, split, height). Advanced: hint source,
     popup key order, functional hints, TLD popups, URL and email layout, redundant popups,
     language per app.
   - Text correction: auto-correction and its confidence, auto-capitalization, double-space period,
     show suggestions, next-word suggestions, personal dictionary, learning from you. Advanced:
     the autospace switches, more auto-correction, shortcuts, capitalized suggestions, always show
     suggestions, middle suggestion, contacts and app names, spellchecker.
   - Toolbar: mode, arrange the toolbar, open above the suggestions, grid opens all actions.
     Advanced: quick pin, auto show and hide, custom key codes, physical keyboard, direction.
   - Appearance: theme, colors, style, key borders, background image, keyboard height. Advanced:
     the scales, fonts, icons, navigation bar.
   - Gesture typing: models, enable, trail, preview. Advanced: phrase gesture, cooldown, trail
     lifespan. "Finding swipe mistakes" stays as its own group.

## To capture next

Text editing, one-handed and floating modes, first-run setup, motion.
