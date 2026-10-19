# Hand-off notes

## Status: Run 2 finished — stopped at ⏸ Hand-off B

Steps 6–7 are done: the Forerunner 245 app (`garmin/`) and phone ↔ watch sync.
`tools/verify.sh` is green, and it now compiles the watch app too. The real `fr245` build passes in CI
with your Garmin secrets (release: 16 KB code + 2.5 KB data of the 128 KB).
Details per sub-step are in `PROGRESS.md` (Run 2). Run 1's notes are below.

## ⏸ Hand-off B — your checks (yes/no)

**Install**

The watch files are attached to the same rolling release as the APK once
this run is merged:

- **Debug build**, with the sample list and the probe:
  https://github.com/Robert4lyf/HyperShop/releases/latest/download/hypershop-fr245-debug.prg
- **Release build**, for everyday use:
  https://github.com/Robert4lyf/HyperShop/releases/latest/download/hypershop-fr245.prg

To install, plug the watch into a computer by USB and copy the `.prg` into
`GARMIN/APPS/`. Unplug it, and HyperShop appears in the watch's apps list.
Copy one build at a time; the newer copy replaces the older one. Install the
new APK on the phone as usual.

1. [x] The watch app opens and shows "Waiting for phone", then your list within 10 s of opening it with the phone nearby?
2. [x] Settings › Watch on the phone says "Forerunner connected…"?
3. [x] **Payload probe** (debug build): watch › Settings (bottom of the aisle list) › Payload probe. Note the two numbers ("To watch", "To phone") here: 4096 / 4133 — the probe's top size both ways; the phone now pages at 2048 B.
4. [x] Import a recipe on the phone, then open the watch app: the aisles appear, each showing "N left"?
5. [~] In an aisle, tick 3 items with Start: each one buzzes, sinks to the bottom, and shows ticked on the phone within a few seconds? — took 20–30 s: the phone sent each snapshot twice in 384 B pages and queued the next tick behind them. Fixed (2048 B pages, one snapshot per tick); check again.
6. [x] Tick every item in an aisle: "<aisle> done" appears with **Next: <aisle>** selected, and Start goes to that aisle?
7. [x] "Undo <item>" on that prompt unticks it on both the watch and the phone?
8. [ ] **Offline:** with the phone's Bluetooth off (or the phone out of range), tick 2 items; the status row says "2 unsynced". On the phone, add a recipe that also uses one of those items, then turn Bluetooth back on and reopen the watch app. Does that item come back unticked with "1 more" under it?
9. [ ] **Memory** (debug build): watch › Settings › Load sample (60). Scrolling and ticking stay quick, and nothing crashes. Use "Back to phone list" afterwards.
10. [~] The glance (scroll up or down from the watch face) shows "N left · <aisle> next" after using the app? — No glance, and the 245 offers no way to add one for a watch app. Removed from the app.

If the probe numbers differ much from 384 bytes, tell me and I'll set the phone's page size from them (`PayloadCap`).

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

## Open items carried forward

- Monkey C tests: they compile against the real `fr245` definition in CI. The job that *runs* them in the simulator (`connectiq.yml` › simulator, Ubuntu 22.04 + Xvfb) is experimental and non-blocking until it has passed. The same logic is held to the Kotlin model step by step by the generated traces (`garmin/test/SyncTraces.mc`).
- Auto-advance to the next aisle (optional, off by default in PLAN): not built.
- Real-app share payloads (KNOWN_ISSUES #5) and the real Android Keystore path (#7) are still yours to confirm on the phone.
