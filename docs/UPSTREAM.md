# What Caret took from HeliBoard's backlog

Caret forked HeliBoard at v4.1 (commit 415c45f1, 2026-09-30). This records which open HeliBoard
pull requests and issues were looked at, so nobody goes through them twice. Pull requests are
squash-merged with their author kept as the commit author.

## Pull requests taken

| PR | What it does |
| --- | --- |
| #2636 | Fix duplicated leading digit when picking a suggestion |
| #2619 | Fix crash on long-press when a placeholder is the only popup key |
| #2694 | Keep the toolbar expand/collapse state across reopening the keyboard |
| #2693 | Return to the base layout when closing a numpad or D-pad overlay |
| #1803 | Make deleting a phantom space a separate backspace press |
| #2537 | Arabic dotted-circle markers |
| #1816 | `hintLocales` in settings search |
| #2689 | Long-press popup highlight from the functional key color |
| #2695 | Highlight active toolbar toggles, simpler incognito key |
| #2292 | Better auto-show and auto-hide of the toolbar |
| #2531 | Segment Thai composing text by word boundaries |
| #2626 | Setting to disable the URL and email specific layout (German translation left out) |
| #2499 | Option to delete whole words when swiping backspace (issue #1289) |

## Pull requests looked at and left

| PR | Why |
| --- | --- |
| #2780 | Treats digits as word characters, which broke URL detection and two tests. Reverted. |
| #2447 | A reviewer questioned the logic and it was never verified. |
| #2406 | Placeholder (`if (true) return this`). |
| #2491 | Clipboard masking. Does not fit the current clipboard code, and hides any text that contains words like "password". Worth redoing with narrower rules. |
| #2743 | In-keyboard dictation through the system speech service. Unfinished draft, but the idea fits Alyo, which is a speech service. |
| #2773, #2329, #2556 | Other takes on deleting whole words. Conflict in the input logic; #2499 covers the most requested form. Long-press to delete words (issue #535) is still open. |
| #2696, #2620, #2175, #2125 | Conflict with later upstream changes. Not ported yet: recents refresh, emoji tabs, vibration strength, external keyboard fix. |
| #2351 | Java swipe fallback. Caret has the FUTO engine. |
| #2775, #2512, #1886, #999, #2681 | Languages and layouts nobody asked Caret for yet. |

## Issues

| Issue | State in Caret |
| --- | --- |
| #1055 Gboard-like appearance by default | Done. |
| #2226 free gesture typing | Done with the FUTO Swipe library. |
| #1289 delete whole word by swiping backspace | Option added from #2499. |
| #2826 spacebar shrinks on the symbols page | The D-pad key there is off by default. |
| #1113 words learned with a capital | Swipe no longer spells a result with a capital because the history has one. Tap typing still has upstream's behavior. |
| #2465 backspace repeats text | Firefox bug, also happens with other keyboards. Kept as a case for the misbehaving text field tests. |
| #2635 spacebar duplicates the last word | No way to reproduce yet. |
| #2639 uppercase not working with the "No language" layout | Not looked into yet. |
| #535 long-press backspace deletes words | Open. |
