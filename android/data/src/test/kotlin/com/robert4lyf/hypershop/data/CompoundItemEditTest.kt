package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.parse.QuantityInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CompoundItemEditTest {
    @get:Rule val r = DbRule()

    @Test
    fun staleWatchUpdateAndRepeatedSaveCannotOverwriteTheList() =
        runBlocking {
            r.import("500 g basmati rice")
            val old = r.repo.snapshot().liveItems.single()
            r.repo.editItem(old.id, old.rev, null, QuantityInput.parse("600 g"), AisleCode.BAKING, false)
            val changed = r.repo.snapshot()
            assertTrue(
                runCatching {
                    r.repo.editItem(old.id, old.rev, null, QuantityInput.parse("700 g"), null, false)
                }.isFailure,
            )
            assertEquals(changed, r.repo.snapshot())
            val fresh = changed.items.getValue(old.id)
            r.repo.tick(fresh.id)
            assertTrue(
                runCatching {
                    r.repo.editItem(fresh.id, fresh.rev, null, QuantityInput.parse("800 g"), null, false)
                }.isFailure,
            )
        }

    @Test
    fun renameCollisionWithAnAmountFailsBeforeAnyMutation() =
        runBlocking {
            r.import("2 onions", "1 carrot")
            val before = r.repo.snapshot()
            val carrot = before.itemByKey("cat:carrot")!!
            assertTrue(
                runCatching {
                    r.repo.editItem(
                        carrot.id,
                        carrot.rev,
                        "onion",
                        QuantityInput.parse("3"),
                        AisleCode.FROZEN,
                        true,
                        PendingLibraryEditEntity("test", "payload"),
                    )
                }.isFailure,
            )
            assertEquals(before, r.repo.snapshot())
            assertTrue(r.repo.pendingLibraryEdits().isEmpty())
        }

    @Test
    fun renameOnlyMergesPurchasesHomeAndContributionOrigins() =
        runBlocking {
            r.import("500 g basmati rice", "200 g spaghetti")
            val before = r.repo.snapshot()
            val rice = before.itemByKey("cat:basmati_rice") ?: before.liveItems.first { it.displayName.contains("rice") }
            val pasta = before.liveItems.first { it.id != rice.id }
            HomeCheck(r.repo).haveSome(rice.mergeKey, "100 g")
            r.repo.tick(pasta.id)
            val source = r.repo.snapshot().items.getValue(pasta.id)
            r.repo.editItem(source.id, source.rev, rice.displayName, null, null, false)
            val after = r.repo.snapshot()
            val merged = after.liveItems.single()
            assertEquals(2, after.contributionsOf(merged.id).size)
            assertEquals(Rational.of(200), after.purchased(merged.id)[BaseKey.MASS])
            assertEquals(Rational.of(100), merged.onHand?.get(BaseKey.MASS))
            assertEquals(before.contributions.map { it.recipeLineId }.toSet(), after.contributions.map { it.recipeLineId }.toSet())
        }

    @Test
    fun compoundPreferenceIsDurableAndBackupCannotSilentlyOmitIt() =
        runBlocking {
            r.import("500 g spaghetti")
            val item = r.repo.snapshot().liveItems.single()
            val pending = PendingLibraryEditEntity("recover", "{\"id\":\"pasta\"}")
            r.repo.editItem(item.id, item.rev, null, QuantityInput.parse("600 g"), null, false, pending)
            assertEquals(listOf(pending), r.repo.pendingLibraryEdits())
            assertTrue(runCatching { r.repo.exportBackup() }.isFailure)
            r.repo.completeLibraryEdit(pending.id)
            assertTrue(r.repo.exportBackup().contributions.isNotEmpty())
        }

    @Test
    fun homeIsNotSpentTwiceAfterPurchasesAndServingChanges() =
        runBlocking {
            val planner = Planner(r.repo)
            val key = r.save("Rice", "500 g basmati rice")
            planner.plan(planner.currentWeek(), key)
            planner.addWeekToList(planner.currentWeek())
            val item = r.repo.snapshot().liveItems.single()
            HomeCheck(r.repo).haveSome(item.mergeKey, "300 g")
            r.repo.tick(item.id)
            var list = r.repo.snapshot()
            assertEquals(Rational.of(200), list.purchased(item.id)[BaseKey.MASS])
            planner.setServings(planner.week(planner.currentWeek()).single().id, 8)
            list = r.repo.snapshot()
            val live = list.itemByKey(item.mergeKey)!!
            assertEquals(Rational.of(500), list.toBuy(live.id)[BaseKey.MASS])
        }

    @Test
    fun storesKeepIndependentShelfOrderAndPackPreferencesInBackup() =
        runBlocking {
            r.repo.renameStore(2, "Aldi Hemel")
            r.import("500 g spaghetti")
            val item = r.repo.snapshot().liveItems.single()
            r.repo.editItem(
                item.id, item.rev, null, null, null, false, packChanged = true,
                pack = QuantityInput.parse("400 g"), storeId = 2,
            )
            val backup = r.repo.exportBackup()
            assertTrue(BackupValidator.validate(backup).isEmpty())
            assertEquals("Aldi Hemel", backup.stores.single { it.id == 2L }.name)
            assertEquals(QuantityInput.parse("400 g"), r.repo.packPreference(2, item.mergeKey))
            assertNull(r.repo.packPreference(1, item.mergeKey))
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val marker = EpochMarker(java.io.File(context.cacheDir, "pack-restore"))
            assertTrue(r.repo.restoreBackup(backup, marker).isEmpty())
            assertEquals(QuantityInput.parse("400 g"), r.repo.packPreference(2, item.mergeKey))
        }

    @Test
    fun homeUndoRestoresAnEarlierPartialAmountAndRefusesToUndoAPurchase() =
        runBlocking {
            r.import("500 g basmati rice")
            val item = r.repo.snapshot().liveItems.single()
            val home = HomeCheck(r.repo)
            home.haveSome(item.mergeKey, "100 g")
            val enough = home.changeSome(item.mergeKey, "600 g")
            assertTrue(home.undoSome(enough.undo!!))
            assertEquals(Rational.of(100), r.repo.snapshot().items.getValue(item.id).onHand?.get(BaseKey.MASS))
            val changed = home.changeSome(item.mergeKey, "200 g")
            r.repo.tick(item.id)
            assertTrue(!home.undoSome(changed.undo!!))
            assertEquals(Rational.of(300), r.repo.snapshot().purchased(item.id)[BaseKey.MASS])
        }
}
