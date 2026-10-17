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

1. [x] Created `main` from `claude/happy-bardeen-i5ervk` (or merged it in), and the "Release APK" run published the APK?
2. [x] The APK installs from the link above on your phone?
3. [x] Settings › Claude: you entered your key, and "Save and test" said "✓ The key works"?
4. [x] Settings › Claude: Haiku 5.5 is selected and the caps read $2.00 a month and 50 calls a day?
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

## Hand-off A findings

- **5. Amounts in ml, and 480 ml instead of 500 ml** (RecipeTin Eats, "One Pot Creamy Tomato Chicken Pasta Bake"). Three causes, all fixed:
  - The recipe's own metric figure ("2 cups milk (500ml)") was parsed but never used; the cups were converted instead. A metric alternative now wins over cups, spoons and ounces.
  - The cup size was guessed as US (240 ml) because most lines start with cups. A line giving both cups and ml now settles it (2 cups = 500 ml means 250 ml cups).
  - Cups of solids (cheese, chicken, pasta) showed as ml. Your rule now applies: **amounts are always metric, and teaspoons and tablespoons are never converted** (but when a recipe gives grams as well as spoons, the grams win). Cups of a solid become grams using the catalogue's weight per cup (100 common items now have one: cheeses, cooked chicken, mince, pasta, produce, nuts, dried fruit; agent estimates for a US cup of the grated, shredded or chopped form). An item without one shows in ml.
  Items already on the list keep their old amounts; re-share the recipe to see the fix.

- **Items renamed to something else.** Panko was turned into breadcrumbs, and haricot beans (and butter beans) into cannellini beans, because the catalogue listed them as other names for those items. Panko, haricot beans (also navy beans) and butter beans (also lima beans) are now items of their own.

## UI overhaul (after Hand-off A)

A ground-up visual refresh, planned in `docs/design/ui-overhaul.md`; behaviour is unchanged. Screenshots: `docs/contact-sheet.png`.
- Own brand colours (basil green, warm neutrals, tomato for "check this"), with dynamic wallpaper colour off. Plus Jakarta Sans throughout.
- Every aisle has a colour and icon, used on the list, the review screen, the sheets and Settings.
- List: a large "N left" with a progress bar, aisle cards, round ticks, a pill-shaped "Add item" field, and a softer swipe-to-delete.
- Review: a recipe card (title, site link, parser badge, servings stepper), "In stock" pills, and "Add N items" pinned to the bottom.
- Recipes and Settings: cards with icons, chips, a segmented units switch, model radio rows and spending bars.
- A new launcher icon: a basket with a leaf.

To check on the phone: everything reads well in daylight and at night, and nothing you used before is harder to find.

## Before Run 2

- Hand-off A done (above), with `main` in place.
- Copy your BangGym secrets to this repository: `CIQ_DEVELOPER_KEY`, `GARMIN_USERNAME`, `GARMIN_PASSWORD` (Settings › Secrets and variables › Actions).

## Open items carried forward

- 4.5 live smoke test and 4.6 eval report: done after Run 1, once the key was set.
- Real-app share payloads (KNOWN_ISSUES #5) and the real Android Keystore path (KNOWN_ISSUES #7) are checked by you in Hand-off A.
- Settings › Aisles reorders the store's walking order; the list screen picks it up when it is next opened.
- The watch: Settings › Watch says "not connected"; the debug build's "Fake watch" ticks through the same transaction Run 2's link will use.
