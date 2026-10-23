# Hand-off notes

## Status: Final quality pass — ⏸ Hand-off H (replaces E, F and G)

The final quality pass is on branch `claude/serene-lovelace-464dnp` (PROGRESS.md › Quality pass, the report in docs/reports/quality-pass.md). Hand-offs E, F and G were never answered; their checks that still matter are folded into H below, so H is the one list to work through. Everything here was driven on the JVM and CI's emulator; nothing in this list has been done on a real phone or a real Forerunner 245. **Back up first** (Settings › Data › Back up to a file), and do checks 15–17 on a second install or a phone whose list you can lose: they restore backups.

**When a check fails, capture:** a screenshot or screen recording; the recipe URL or the exact line text; for the watch, the time it happened and `adb logcat -d -s HyperShop:* WatchSync:* > watch.txt` from the phone straight afterwards (the watch side has no log you can pull, so say what the watch showed). Write the check number next to it.

## ⏸ Hand-off H — your checks (yes/no, and three numbers)

**A routine week**
1. **Upgrade.** Install over your current version. Does the list open with no "Starting…" wait, everything you had still there, and Recipes › Plan the week showing the recipes on the list as this week's meals, nothing added twice?
2. **Stopwatch.** The week after a finished trip: List › Plan this week › Same as last week? › Add N meals › Buy again's Add all › Already at home? (Have it on what you have) › Start shopping. Time from opening the app to the shop screen, leaving out deciding what to cook: ___ min ___ s. Expected: under two minutes.
3. **Already at home.** Is the list short and about this week's meals only? "Have it" takes an item off; "Need it" puts exactly that amount back?
4. **One-handed shop.** Tick ten items quickly with your thumb at the largest font size you use. Every tick shows at once and none undoes another? A mis-tap tapped again within two seconds unticks without expanding "N bought"? Does the bottom bar stay still?
5. **Remove a meal you have part-bought.** What you bought stays ticked; only the rest comes off; Undo puts it back. Did any Undo-only action (Clear all, Remove a meal, Remove from list, Reset a library entry) feel too easy to trigger by accident? ___
6. **Finish shopping.** Finish, then open Buy again next week: are the chips things you buy by hand, and nothing a recipe asked for?

**Recipe imports**
7. **Three real recipes** (UK site, US site, a chatty blog). On each review, count the rows that are wrong and how many of those were flagged ("Check: …"). Wrong *and unflagged* is what matters: ___ / ___ / ___. Expected: tins of tomatoes in Tins & jars (not Fruit & veg), minced beef as beef mince, cups of cheese, rice or seeds in grams (not ml).
8. **Correct a line, then Ask Claude.** Set an amount on the sheet's Amount field, rename a row, and Remove from recipe + Undo (back where it was). Then Ask Claude: all three corrections still there?
9. **Two identical lines** (new in this pass). Share or paste a recipe that lists the same line twice in two sections, e.g. "100 g sugar" under the cake and again under the icing. Change only the first to 150 g and Ask Claude: the first still 150 g, the second still 100 g, the row 250 g? Remove one of them, Undo: the right one back in its place? Leave the review and open it again from the card: still as you left it?
10. **Edit after buying.** Buy part of a recipe, then edit it to need more: the bought amount stays bought and only the extra is needed?

**Accessibility and looks**
11. **TalkBack.** Tick an item in the shop: is the new count spoken? Swipe through the review: title a heading, outcome spoken before the button, focus in reading order?
12. **Largest font size.** Are the tab labels whole, and can you still find Add, Start shopping and Finish without scrolling sideways?
13. **Dark mode and daylight.** Light and dark: is all text readable on the heroes? Outside in daylight at your usual brightness, can you read the shop list? ___
14. **Plan screen density.** Is the week screen readable at a glance, or is there too much on it? Which part would you remove first? ___

**Backup** (second install or a list you can lose)
15. **Downloads and a cloud provider.** Back up to Downloads, then to Google Drive (or another cloud provider). Restore each: the list, the week's plan, Buy again's regulars and your Claude key all still there?
16. **A broken file.** Restore a file that is not a backup (any photo or PDF): refused with a plain-words message, nothing changed?
17. **Undo of a restore** after ticking something: refused (the list has moved on), not silently undoing the tick?

**Garmin Forerunner 245**
18. **First sync.** Open the watch app with the phone near: the phone's list, in aisle order, merged (one onion row for two recipes)?
19. **Offline ticks and Undo.** Phone Bluetooth off. On the watch tick three items, then Undo one. On the phone, add a recipe that also needs one of the ticked items, and change the amount of a shared ingredient. Bluetooth on: within a few seconds phone and watch agree; the shared item shows the new recipe's share still needed; the undone item is not bought; nothing bought twice.
20. **Rapid ticks.** Tick five items as fast as the watch allows: all five bought on the phone, none twice.
21. **Restore with the watch open.** Restore a backup on the phone: the watch switches to the restored list within a few seconds and none of its old ticks land on it (the watch epoch changed).
22. **Phone restart.** Force-stop HyperShop with the watch app open, tick on the watch, open HyperShop: the tick arrives.
23. **A long list.** With 60+ items (Debug tools › Load sample, twice), scroll the watch list to the end while ticking on the phone: no page from an older list mixed in (snapshot build identifiers), every row present once.
24. **Watch unit tests on a desktop** (CI cannot run them, KNOWN_ISSUES #12): `tools/run_watch_tests.sh` on Linux with a display, or the three commands at the top of `tools/build_watch.sh`. Every test PASSED?

## Status: Release C (exceptional polish and real-world quality) — ⏸ Hand-off G

Release C is on branch `ccr-5923064f-985kmq` (PROGRESS.md › Release C, docs/design/release-c.md, the report in docs/reports/release-c-final.md). Everything below was driven on the JVM and CI's emulator; only you can say how it feels in a shop, with TalkBack, and with your own recipes. **Back up first** (Settings › Data › Back up to a file).

## If this session stopped before the PR (continuing with another tool)

State: commits C.0–C.7 are on `ccr-5923064f-985kmq` and pushed. Left to do, in order: C.8 the adversarial review of `git diff 06e4126..HEAD -- android` (areas: `ReviewViewModel.keepCorrections`/`setAmount`/`removeLine`+Undo; `ListViewModel.prep` gated on list mode and the "+1" snackbar; `ShopScreen` lingering ticks; the dialogs replaced by Undo; the parser's trailing amounts, lengths, decilitres, shared heads; `BuyAgain.view(list)`, `Planner.recipesByKeys`; `AmountField(initial)`), fixing what it finds, one `[C.8]` commit with the findings in docs/reports/release-c-final.md § 8; then C.9: fill § 8 of the report, commit `[C.9]`, `tools/verify.sh` green before each commit, and open the PR with the body in the final report's "Delivery" section (the draft PR text is the report's own summary). Rules: CLAUDE.md (gate before every commit, golden data never edited, PROGRESS.md in the same commit as its step).

## Hand-off G — folded into H (kept for the record)

1. **Three real recipes.** Share three recipes you actually cook (one from a UK site, one US, one blog with chatty lines). On each review: how many rows are wrong, and how many of those did the app flag ("Check: …")? Wrong and unflagged is what matters: ___ / ___ / ___.
2. **Correcting a line.** On a flagged row, open the sheet. Is the line as the recipe wrote it visible, and does the reason say what to do? Set the amount from the Amount field, rename the row, swap "A or B". Then tap Ask Claude: are your corrections still there afterwards?
3. **Remove from recipe, Undo.** Remove a line on the sheet, then Undo from the snackbar. Is it back where it was?
4. **The shop, one-handed.** In the shop, tick an item by mistake and tap it again within a couple of seconds. Does it untick without expanding "N bought"? Does the bottom bar stay still on the first tick?
5. **Names.** On the shop screen, does "Edit list" make sense as the way back, and "Plan the week" on Recipes as the week screen? Does opening Plan from the List tab keep the List tab lit?
6. **Undo in place of dialogs.** Clear all, remove a meal, remove a recipe from the list, remove a saved recipe, reset a library entry: each goes at once with Undo on the snackbar. Did any of them feel too easy to do by accident?
7. **"Already on the list".** Type an item that is on the list. Does the message with "Add 1 more" let you keep typing, and does "Add 1 more" add exactly one?
8. **TalkBack.** With TalkBack on: tick an item in the shop (does it say the new count?), swipe through the review (is the title a heading, is the outcome spoken before the button?), set font size to the largest (are the tab labels whole?).
9. **Looks.** Light and dark: do the heroes' bright ends still look like HyperShop with the darker right half under the text? Any screen where the new scrim looks wrong: ___.
10. **Edit after buying.** Buy part of a recipe, then edit the recipe to need more. Does the bought amount stay bought and only the extra show as needed?
11. **Backup error text.** Restore a file that is not a backup. Is the message in plain words (no exception names)?
12. **A week.** Plan next week on the Plan screen: is "Add a meal" where you expect it, right under the meals?

## Status: Release B (make weekly shopping effortless) — ⏸ Hand-off F

Release B is on branch `claude/release-b` (PROGRESS.md › Release B, docs/design/release-b.md). The emulator drives every flow; only you can say whether a real week is quicker. **Back up first** (Settings › Data › Back up to a file): the update migrates your database, and your recipes on the list become this week's planned meals.

## Hand-off F — folded into H (kept for the record)

1. **The upgrade.** After installing, open Recipes › Plan the week. Are the recipes that were on your list there as this week's meals, each saying how much is still to buy? Nothing added twice to the list?
2. **Stopwatch, the first week.** Plan next week's shop as you normally would on the Plan screen (meals, then the items you always buy, then Already at home?), and press Start shopping. Time it from opening the app to the shop screen, leaving out time spent deciding what to cook. ___ min ___ s
3. **Stopwatch, the week after.** Finish that trip in the shop. The following week, open the app: List › Plan this week › Same as last week? › Add N meals to the list › Buy again's Add all › Already at home? (tick what you have, or Have all) › Start shopping. ___ min ___ s. Under two minutes? Faster than check 2?
4. **Buy again.** Are the chips things you really buy each week, and nothing a recipe asked for? Long-press one: do "Always offer" and "Don't suggest" do what they say?
5. **Already at home?** Is the list of cupboard things short and about this week's meals only? Does "Have it" take an item off the list and "Need it" put exactly that amount back?
6. **Removing a meal.** Remove a meal you have already bought part of. Does the dialog say what comes off, and do the things you bought stay ticked?
7. **Next week.** Plan next week (› arrow) while this week's list is out. Is the list unchanged?
8. **Quick list.** On an empty list, type "milk, bread" and shop. Is it just as quick as before (no planning step in the way)?
9. **The watch.** After Add N meals to the list, does the watch show the new items, merged (one onion row for two recipes)?
10. **Backup.** Back up, then restore that file: is the week's plan still there, and does Buy again still know your regulars?


## Status: Release A (verified and dependable) — ⏸ Hand-off E

Release A is on branch `claude/release-a-verified` (PROGRESS.md › Release A). What CI's emulator verified and what it cannot is in docs/design/release-a.md. These checks need your phone and your watch. **Back up first** (Settings › Data › Back up to a file).

## Hand-off E — folded into H (kept for the record)

**Phone**
1. **Start-up.** Open HyperShop from cold. Does the list appear with no visible wait (no "Starting…" screen)?
2. **Servings after a finished trip.** Add a recipe needing onions, tick them, Finish shopping. On Meals raise its servings: is one extra onion back on the list as a plain "1 onion"? Lower the servings again: is the onion gone from the list (not shown as bought)?
3. **Meals headline.** Two meals sharing onions, nothing bought: does "N items still needed" at the top of Meals match the number of rows on the list?
4. **Undo after the list moved on.** Add two recipes sharing onions. Remove the first (don't tap Undo yet), then remove the second, then tap Undo on the first's message if it is still showing, or use "Add again". No crash, and the first recipe's onions are back?
5. **Ticking speed.** With about 60 items (Debug tools › Load sample, twice), go to Shop and tick ten items quickly, one after another. Does every tick show within a moment, and none undo another?
6. **Backup to Google Drive.** Settings › Data › Back up to a file, choose Google Drive. Then Restore from that Drive file. Does it restore, with your Claude key still saved?
7. **Backup to Downloads.** The same with "Downloads" on the phone.

**Watch (Forerunner 245)**
8. **Snapshot.** Open the watch app with the phone nearby: is the list the phone's, in aisle order?
9. **Offline ticks and a new recipe.** Turn the phone's Bluetooth off. Tick three items on the watch, and untick one of them again. On the phone, add a recipe that also needs one of the ticked items. Bluetooth back on: within a few seconds, do the phone and watch agree, with the shared item showing "1 more…" (the new recipe's share still needed) and the unticked item not bought?
10. **Rapid ticks.** Tick five items as fast as the watch allows: all five bought on the phone, none twice?
11. **Restore with the watch open.** With the watch app open, restore a backup on the phone. Does the watch switch to the restored list within a few seconds, with none of its old ticks applied to it?
12. **Phone restart.** Force-stop HyperShop on the phone (App info › Force stop) while the watch app is open, tick an item on the watch, then open HyperShop. Does the tick arrive?
13. **Watch tests on a desktop** (CI cannot run them, KNOWN_ISSUES #12). On a computer with the Connect IQ SDK Manager and the Forerunner 245 device installed: on Linux, `tools/run_watch_tests.sh` (it uses your display when `DISPLAY` is set); on macOS or Windows, the three commands at the top of `tools/build_watch.sh` (`monkeyc … -t`, `connectiq`, `monkeydo … -t`). Every test PASSED? If the simulator shows an "Error" dialog here too, what does it say?

**What the emulator and simulator cannot tell us**
- CI cannot run the Monkey C tests: headless, the simulator never loads the device and its "Error" dialog does not draw (KNOWN_ISSUES #12); check 13 runs them on a desktop. The watch's sync logic is covered by the Kotlin model and its replayed traces, and the phone side by WatchAcceptanceTest, but nothing has run the watch's own code except your watch.
- Real Bluetooth timing, Garmin Connect's message delivery, and the 245's memory limits.
- Cloud document providers (Drive): only MediaStore's local Downloads provider is automated.

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
