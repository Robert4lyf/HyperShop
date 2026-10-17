# Hand-off notes

## Status: Run 1 finished — stopped at ⏸ Hand-off A

Steps 0–5 are done (branch `claude/happy-bardeen-i5ervk`, now on `main`). The
live smoke test (4.5) and eval (4.6) were run afterwards, once a key was set. `tools/verify.sh`
is green on the last commit. Details per sub-step: `PROGRESS.md`. Problems and
workarounds: `docs/KNOWN_ISSUES.md`.

**No pull request was opened.** PLAN §6 rule 4 says to open one only if `main`
exists, and the repository has no `main` (its default branch is
`ccr-e37096cd-947kz3`). Creating `main` is the first check below.

## The APK

Rolling release, rebuilt by `.github/workflows/release.yml` on every push to `main`:

**https://github.com/Robert4lyf/HyperShop/releases/latest/download/hypershop-debug.apk**

It goes live once `main` exists and the first release run finishes (Actions ›
"Release APK"). Every build is signed with the committed debug keystore, so a
new one installs over the old.

## ⏸ Hand-off A — your checks (yes/no)

Answer each with yes or no; note anything that's a "no".

1. [ ] Created `main` from `claude/happy-bardeen-i5ervk` (or merged it in), and the "Release APK" run published the APK?
2. [ ] The APK installs from the link above on your phone?
3. [ ] Settings › Claude: you entered your key, and "Save and test" said "✓ The key works"?
4. [ ] Settings › Claude: Haiku 5.5 is selected and the caps read $2.00 a month and 50 calls a day?
5. [ ] Shared 5 recipes from Chrome: each opened the review screen with the right ingredients ("Parsed by Claude")?
6. [ ] On the review screen, in-stock ticks, "I have some", swap and move aisle did what you expected, and "Add N items" added them?
7. [ ] Sharing one of the same recipes again asked "Already on your list — add again?"
8. [ ] Typed 5 items into "Add item" (try a pasted multi-line list, and one already on the list for "+1?"); all landed in the right aisles?
9. [ ] In airplane mode, a shared recipe still imported (offline parser, "Claude pending"), and "Retry with Claude" worked once back online?
10. [ ] Ticking, Undo, swipe-delete, "Finish shopping" and the item sheet all behaved on the list?
11. [ ] Shares from Firefox, Samsung Internet, Instagram or YouTube (any you use) found the link? (KNOWN_ISSUES #5: tested only with reconstructed payloads.)
12. [ ] `docs/contact-sheet.png` looks right in light and dark (no clipped text, readable contrast)?
13. [ ] `catalog/REVIEW.md` — the aisles, staples and pack notes look right for your shop?
14. [ ] The 20 held-out golden lines (`docs/golden-held-out.md`) are labelled the way you'd read them?
15. [ ] Eval: `docs/reports/eval-claude.md`. Keep Claude Haiku 5.5 as the default? (`max_tokens` is now 16000 after Haiku hit the old 4000 cap; KNOWN_ISSUES #8.)

## Before Run 2

- Hand-off A done (above), with `main` in place.
- Copy your BangGym secrets to this repository: `CIQ_DEVELOPER_KEY`, `GARMIN_USERNAME`, `GARMIN_PASSWORD` (Settings › Secrets and variables › Actions).

## Open items carried forward

- 4.5 live smoke test and 4.6 eval report: done after Run 1, once the key was set.
- Real-app share payloads (KNOWN_ISSUES #5) and the real Android Keystore path (KNOWN_ISSUES #7) are checked by you in Hand-off A.
- Settings › Aisles reorders the store's walking order; the list screen picks it up when it is next opened.
- The watch: Settings › Watch says "not connected"; the debug build's "Fake watch" ticks through the same transaction Run 2's link will use.
