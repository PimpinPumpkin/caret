# What Caret took from HeliBoard's backlog

Caret forked HeliBoard at v4.1 (commit 415c45f1, 2026-09-30). This records which open HeliBoard
pull requests and issues were looked at, so nobody goes through them twice. Pull requests are
squash-merged with their author kept as the commit author.

## How a pull request gets in

Passing the tests is not enough. Before one is kept: read the whole diff, read the discussion on
the pull request, and check what HeliBoard's maintainer or testers said. Drafts, changes the
maintainer objected to, changes a tester could not confirm, and changes of taste are left out.
Many of the open pull requests carry descriptions that read as machine written; that alone does not
decide anything, the diff and the discussion do.

## Pull requests taken

| PR | What it does | Why it was kept |
| --- | --- | --- |
| #2619 | Fix crash on long-press when a placeholder is the only popup key | One guard and two tests. |
| #2636 | Fix duplicated leading digit when picking a suggestion | Narrow: only acts when the picked word starts with the text before the cursor. Two tests. |
| #2626 | Setting to disable the URL and email specific layout | Off changes nothing. Draft upstream, but the diff is a plain switch. German translation left out. |
| #2499 | Option to delete whole words when swiping backspace (issue #1289) | Off by default. HeliBoard's maintainer noted it finds words with a fixed-locale break iterator instead of the keyboard's own word logic, so it can be off for some scripts. `WordStepsTest` covers the helper. |

## Pull requests merged and then taken out again

These passed the tests and were merged in a first pass, then removed after reading the discussion.

| PR | Why it was removed |
| --- | --- |
| #2780 | Treats digits as word characters, which broke URL detection and two tests. |
| #1803 | Phantom space backspace. The maintainer said it must be a setting, because without one it looks like a bug. It has no setting. |
| #1816 | `hintLocales` in settings search. The maintainer did not want the layout to change while searching. |
| #2537 | Arabic markers. Also reorders popups, which the maintainer doubted because of muscle memory. |
| #2292 | Toolbar auto-show and auto-hide. Draft; the maintainer was worried about its changes inside suggestion handling and wanted part of it optional. |
| #2695 | Toolbar toggle highlight. A tester reported it did not work and disputed the design. |
| #2689 | Popup highlight color. A tester saw no difference; the highlight is already clear in Caret's look. |
| #2693 | Numpad and D-pad overlay stack. A reviewer disputed the premise. |
| #2694 | Toolbar stays open across reopening. A change of taste, and with Caret's favorites toolbar it would hide suggestions on reopen. |
| #2531 | Thai word segmentation. Touches the input logic, a reviewer asked for a rework, and nobody here can test Thai. |

## Pull requests looked at and left

| PR | Why |
| --- | --- |
| #2447 | A reviewer questioned the logic and it was never verified. |
| #2406 | Placeholder (`if (true) return this`). |
| #2491 | Clipboard masking. Does not fit the current clipboard code, and hides any text that contains words like "password". Worth redoing with narrower rules. |
| #2743 | In-keyboard dictation through the system speech service. Unfinished draft, but the idea fits Alyo, which is a speech service. |
| #2773, #2329, #2556 | Other takes on deleting whole words. #2773 is a draft whose author says the tests were generated and unchecked. Long-press to delete words (issue #535) is still open. |
| #2696 | Fixes for an unmerged upstream branch (`remove_recents`), not for the code Caret has. |
| #2620 | Emoji tabs always visible. Draft for discussion; another contributor explained the current design and the default could simply be changed. Does not affect Caret, whose toolbar is not hidden. |
| #2175 | Vibration strength. Six rounds of review open, and the maintainer saw no reason for its extra mode setting. A plain strength slider would be the way to do it. |
| #2125 | Control by TV remote or external device. The maintainer said it breaks normal hardware keyboards and that much is left to do. The idea matters for Android TV boxes, so it is worth doing properly later. |
| #2351 | Java swipe fallback. Caret has the FUTO engine. |
| #2775, #2512, #1886, #999, #2681 | Languages and layouts nobody asked Caret for yet. |

## Issues

| Issue | State in Caret |
| --- | --- |
| #1055 Gboard-like appearance by default | Done. |
| #2226 free gesture typing | Done with the FUTO Swipe library. |
| #1289 delete whole word by swiping backspace | Option added from #2499, off by default. |
| #2826 spacebar shrinks on the symbols page | The D-pad key there is off by default. |
| #1113 words learned with a capital | Swipe no longer spells a result with a capital because the history has one. Tap typing still has upstream's behavior. |
| #2465 backspace repeats text | Firefox bug, also happens with other keyboards. Kept as a case for the misbehaving text field tests. |
| #2635 spacebar duplicates the last word | No way to reproduce yet. |
| #2639 uppercase not working with the "No language" layout | Not looked into yet. |
| #535 long-press backspace deletes words | Done in Caret (not from a pull request): characters first, then whole words, using the keyboard's own word separators. On by default, switch in Advanced. |
