package com.robert4lyf.hypershop.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Backup and restore (docs/design/simplify.md phase 4): a round trip, and every way a file is refused. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupTest {
    @get:Rule val r = DbRule()

    private val json = Json { ignoreUnknownKeys = true }

    private fun marker(): EpochMarker =
        EpochMarker(
            File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "m-${System.nanoTime()}"),
        )

    /** A list with a recipe (one line at home), a hand-added item, a tick, an override, a staple and a custom aisle order. */
    private suspend fun populate(): BackupData {
        val applied = r.import("2 onions", "Salt, to taste", "200 ml milk")
        r.repo.manualAdd("eggs", QtyBase.of(Rational.of(6), null, UnitLocale.METRIC), null)
        val onion = r.repo.snapshot().itemByKey("cat:onion")!!.id
        r.repo.tick(onion)
        r.repo.moveAisle(onion, AisleCode.FROZEN, always = true)
        r.repo.setStaple("cat:milk", true)
        r.repo.setAisleOrder(AisleCode.entries.reversed())
        r.repo.renameUse(applied.useId, "Sunday soup")
        r.repo.recordUsage("2026-10-20", "claude-haiku-5-5", 100, 50, 0, 0)
        return r.repo.exportBackup()
    }

    @Test
    fun aRoundTripRestoresEveryTableAndStartsANewEpoch() =
        runBlocking {
            val b = populate()
            val before = r.dump()
            val epochBefore = r.repo.listEpoch()
            // Through JSON, as the file would go.
            val text = json.encodeToString(BackupData.serializer(), b)
            val back = json.decodeFromString(BackupData.serializer(), text)
            // A different database, as on a new phone.
            val fresh = DbRule().also { it.open() }
            try {
                val m = marker()
                assertEquals(emptyList<String>(), fresh.repo.restoreBackup(back, m))
                assertEquals(before, fresh.dump())
                assertEquals(AisleCode.entries.reversed(), fresh.repo.aisleOrder())
                assertEquals("Sunday soup", fresh.db.dao().allRecipes().single().title)
                assertEquals(1, fresh.db.dao().usage().single().calls)
                // The list is a new one to the watch: a new epoch, the marker file written to match.
                assertNotEquals(epochBefore, fresh.repo.listEpoch())
                assertEquals(fresh.db.dao().meta()!!.epochMarker, m.read())
                assertFalse(fresh.repo.checkEpoch(m))
                // The restored list keeps working: ticks, totals, the item sheet's sources.
                val l = fresh.repo.snapshot()
                assertTrue(l.isDone(l.itemByKey("cat:onion")!!.id))
                assertEquals(Rational.of(6), l.total(l.itemByKey("cat:egg")!!.id)[com.robert4lyf.hypershop.core.model.BaseKey.ITEMS])
                assertTrue(fresh.repo.untick(l.itemByKey("cat:onion")!!.id))
                assertEquals(1, fresh.repo.usesOnList().count { it.recipeId != null })
            } finally {
                fresh.close()
            }
        }

    @Test
    fun aRestoreReplacesWhatWasThere() =
        runBlocking {
            val b = populate()
            // Meanwhile the phone has moved on: another list entirely.
            r.repo.clearAll()
            r.repo.manualAdd("bananas", QtyBase.of(Rational.of(3), null, UnitLocale.METRIC), null)
            assertEquals(emptyList<String>(), r.repo.restoreBackup(b, marker()))
            val l = r.repo.snapshot()
            assertEquals(null, l.itemByKey("cat:banana"))
            assertEquals(setOf("cat:onion", "cat:milk", "cat:egg"), l.liveItems.map { it.mergeKey }.toSet())
        }

    @Test
    fun aBrokenFileChangesNothing() =
        runBlocking {
            val b = populate()
            val before = r.dump()
            val epoch = r.repo.listEpoch()
            val broken =
                listOf(
                    "a contribution for a missing item" to b.copy(contributions = b.contributions.map { it.copy(itemId = 999) }),
                    "a line for a missing recipe" to b.copy(lines = b.lines.map { it.copy(recipeId = 999) }),
                    "a tick covering nothing" to b.copy(contributions = b.contributions.map { it.copy(coveredBy = 999) }),
                    "a recipe use with no recipe" to b.copy(uses = b.uses.map { it.copy(recipeId = null) }),
                    "an unknown aisle" to b.copy(items = b.items.map { it.copy(aisleCode = "BASEMENT") }),
                    "two live items with one key" to b.copy(items = b.items + b.items.first().copy(id = 777)),
                    "nextId behind the ids" to b.copy(nextId = 1),
                    "an unreadable amount" to b.copy(contributions = b.contributions.map { it.copy(qtyBase = "{not json") }),
                    "an unreadable parse" to b.copy(lines = b.lines.map { it.copy(parsedJson = "[]") }),
                    "a decision for a missing line" to b.copy(decisions = b.decisions.map { it.copy(recipeLineId = 999) }),
                    "two usage rows for one day and model" to b.copy(usage = b.usage + b.usage),
                    "two aisle order rows for one aisle" to b.copy(aisleOrder = b.aisleOrder + b.aisleOrder.first()),
                    "two aliases for one old id" to b.copy(aliases = List(2) { ItemAliasEntity(999, b.items.first().id) }),
                    "too many rows" to
                        b.copy(
                            staples =
                                (1..BackupValidator.MAX_ROWS + 1).map {
                                    StapleEntity("name:s$it", "USER")
                                },
                            items = (1..BackupValidator.MAX_ROWS + 1).map { b.items.first().copy(id = 1000L + it, mergeKey = "name:x$it") },
                        ),
                )
            for ((what, bad) in broken) {
                val problems = r.repo.restoreBackup(bad, marker())
                assertTrue(what, problems.isNotEmpty())
                assertEquals(what, before, r.dump())
                assertEquals(what, epoch, r.repo.listEpoch())
            }
        }

    @Test
    fun aBackupFromBeforeAnAisleExistedStillGetsEveryAisle() =
        runBlocking {
            val b = populate()
            val older =
                b.copy(
                    aisles = b.aisles.filter { it.code != "FREE_FROM" },
                    aisleOrder = b.aisleOrder.filter { it.aisleCode != "FREE_FROM" },
                )
            assertEquals(emptyList<String>(), r.repo.restoreBackup(older, marker()))
            assertEquals(AisleCode.entries.size, r.db.dao().aisles().size)
            assertEquals(AisleCode.FREE_FROM, r.repo.aisleOrder().last())
        }

    @Test
    fun anOlderCatalogueIsReKeyedOnRestore() =
        runBlocking {
            val b = populate()
            assertEquals(emptyList<String>(), r.repo.restoreBackup(b.copy(catalogVersion = "ancient"), marker()))
            assertEquals(r.db.dao().meta()!!.catalogVersion, com.robert4lyf.hypershop.core.catalog.CatalogLoader.default.version)
        }
}
