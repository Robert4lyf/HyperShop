# Hand-off notes

## Status: Run 4 (fourth pass) — ⏸ Hand-off D

Steps 9.1–9.6 are on `main` (PROGRESS.md). Everything below was tested on the JVM (Robolectric) and the device scenarios were compiled, but **none of it has been run on a phone or a watch here**: the container has no emulator. Each check takes a minute or two.

## ⏸ Hand-off D — your checks (yes/no)

Use the debug APK from the latest `build-N` release. **Back up first** (Settings › Data › Back up to a file).

1. **Recipe states.** Add two saved recipes from Meals that share an ingredient. Tick that ingredient. Do both recipe cards and Meals say "N still needed" with the right N, and the recipe that needed nothing else say "All bought"?
2. **At home only.** Add a recipe whose every line is marked at home. Does its card say "Nothing to buy — all at home", not "All bought"?
3. **More servings after buying.** Tick everything for a recipe, then raise its servings on Meals. Does the extra come back on the list as partly bought ("1 more…"), and lowering servings again remove it?
4. **Fast servings taps.** Tap + four times quickly on a meal. Does it land four higher, with one "serves N" message whose Undo goes back to where you started?
5. **Shop mode, recipe view.** Set the list to group by recipe, then tap Shop. Is the shop in aisle order, and does Plan bring the recipe grouping back?
6. **Now and Next.** Scroll the shop screen slowly. Does the top bar's "Now: …" follow the aisle at the top, and "Next" always name a different aisle?
7. **TalkBack.** On the shop screen, does a row's tick read "<item>, Still needed, checkbox" and change to "Bought" when double-tapped?
8. **Restore interrupted.** Restore a backup and force-stop HyperShop from Android's app info *while* "Restoring…" is showing (it is quick; try a large backup). Reopen: does a message say the restore was finished (or put back), with the list, theme and ingredient edits all from one side?
9. **Restore refused.** Edit a backup file so a setting reads `"theme": "PURPLE"` and restore it. Is it refused with "nothing was changed", and is your data untouched?
10. **Key kept.** After any restore, is your Claude key still saved (Settings › Claude)?
11. **Watch after a restore.** With the watch app open, restore a backup. Does the watch pick up the restored list within a few seconds, with no stale ticks?

**Device scenarios.** `cd android && ./gradlew :app:connectedDebugAndroidTest` runs `DeviceScenariosTest` (quick shop, weekly meals, backup through a file, interrupted restore, shop-screen accessibility). Run it **on an emulator only**: Gradle uninstalls the app after the run, which deletes the list on a phone. CI's `device-tests` job runs it on an emulator, non-blocking until it has passed on a run of PRs.

## Status: Run 3 (simplify) — stopped at ⏸ Hand-off C

Run 3 is the usability pass planned in `docs/design/simplify.md`: Shop mode,
"At home" and "bought" wording, Claude only when the rules are in doubt (with
streamed replies), backup and restore, and "Meals this week". Sub-steps and
their checks are in `PROGRESS.md` (Step 8). `tools/verify.sh` is green.

## ⏸ Hand-off C — your checks (yes/no)

Nothing here could be tried on a phone or a watch in the container; the
unit, Compose and sync-vector tests cover the logic, these cover the feel.

**Shop mode** (List › Shop)
- Walking a real list one-handed: the tick is easy to hit and the row's text
  (details) is not hit by mistake. Ticked rows drop under "N bought" without
  the list jumping about.
- "Next: <aisle>" takes you to the next aisle with something left; "Plan"
  brings back the add field. Rotate and come back: the opened "bought" rows
  and the mode are as you left them.
- Finish shopping with everything bought: the list is empty and back in
  planning; Undo brings the trip back.

**Watch** (Forerunner 245)
- Tick two items on the watch with the phone out of reach, add a recipe on
  the phone that needs more of one of them, reconnect: the phone shows
  "1 more …" / "N already bought" for that item and the watch shows the same
  after its next sync; the other item is done on both.

**Backup** (Settings › Data)
- "Back up to a file" opens the system file picker and writes a
  `hypershop-backup-<date>.json` you can see in Files. "Restore" on that
  file shows what it holds and asks first; after it the list, recipes,
  aisle order and units are as backed up, the Claude key is still there,
  and the watch takes the list afresh (it says "list replaced" once).
- Restore from a photo or a text file: refused in words, nothing changed.

**Claude**
- Share a recipe page with a key set: a clean recipe says "Parsed offline —
  every line was clear" with "Ask Claude" available; one with a line such as
  "juice of 1 lemon" or "butter or margarine" goes to Claude as before.
- Settings › Claude's spend meter moves after an import that Claude read,
  including one you backed out of while it was reading.

**Meals** (Recipes › This week)
- Pick three saved recipes: the list has their ingredients merged, staples
  at home; change a serving: the amounts follow; remove one: its items go,
  the recipe stays saved; Undo on each.

Run 2's notes are below.

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
5. [x] In an aisle, tick 3 items with Start: each one buzzes, sinks to the bottom, and shows ticked on the phone within a few seconds? — about 2 s after the paging fix (first try took 20–30 s).
6. [x] Tick every item in an aisle: "<aisle> done" appears with **Next: <aisle>** selected, and Start goes to that aisle?
7. [x] "Undo <item>" on that prompt unticks it on both the watch and the phone?
8. [x] **Offline:** with the phone's Bluetooth off (or the phone out of range), tick 2 items; the status row says "2 unsynced". On the phone, add a recipe that also uses one of those items, then turn Bluetooth back on and reopen the watch app. Does that item come back unticked with "1 more" under it?
9. [x] **Memory** (debug build): watch › Settings › Load sample (60). Scrolling and ticking stay quick, and nothing crashes. Use "Back to phone list" afterwards.
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

- Run 3: the shop screen has no "At home" section and no add field; both are one tap away on "Plan". Claude's aisle suggestions are only made for recipes Claude reads (a clear recipe is categorised by the catalogue alone, as offline imports always were). The Garmin simulator job is still non-blocking (below).

- Monkey C tests: they compile against the real `fr245` definition in CI. The job that *runs* them in the simulator (`connectiq.yml` › simulator, Ubuntu 22.04 + Xvfb) is experimental and non-blocking until it has passed. The same logic is held to the Kotlin model step by step by the generated traces (`garmin/test/SyncTraces.mc`).
- Auto-advance to the next aisle (optional, off by default in PLAN): not built.
- Real-app share payloads (KNOWN_ISSUES #5) and the real Android Keystore path (#7) are still yours to confirm on the phone.
