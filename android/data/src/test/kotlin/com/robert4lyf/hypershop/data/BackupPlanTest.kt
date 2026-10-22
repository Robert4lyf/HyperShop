package com.robert4lyf.hypershop.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Release B: the meal plan, trips and Buy again preferences go through a backup, and older files still restore. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupPlanTest {
    @get:Rule val r = DbRule()

    private val json = Json { ignoreUnknownKeys = true }

    private fun marker(): EpochMarker {
        val dir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        return EpochMarker(File(dir, "m-${System.nanoTime()}"))
    }

    private suspend fun populate(): BackupData {
        val planner = Planner(r.repo)
        val week = planner.currentWeek()
        r.repo.manualAdd("milk", QtyBase.of(Rational.of(2), null, UnitLocale.METRIC), null)
        for (i in r.repo.snapshot().liveItems) r.repo.tick(i.id)
        r.repo.finish()
        planner.plan(week, r.save("Chilli", "2 onions"))
        planner.addWeekToList(week)
        planner.plan(week + 1, r.save("Soup", "1 carrot"))
        BuyAgain(r.repo).pin(Regular("cat:bread", "bread", QtyBase.PRESENCE, null, true, 0, false))
        return r.repo.exportBackup()
    }

    @Test
    fun planTripsAndRegularsRoundTrip() =
        runBlocking {
            val b = json.decodeFromString(BackupData.serializer(), json.encodeToString(BackupData.serializer(), populate()))
            val before = r.dump()
            val fresh = DbRule().also { it.open() }
            try {
                assertEquals(emptyList<String>(), fresh.repo.restoreBackup(b, marker()))
                assertEquals(before, fresh.dump())
                val planner = Planner(fresh.repo)
                val meal = planner.week(planner.currentWeek()).single()
                assertEquals(MealShopping.TO_BUY, meal.shopping)
                // Restored, the plan still knows it is on the list: no second chilli.
                assertEquals(null, planner.addWeekToList(planner.currentWeek()))
                assertEquals(1, planner.week(planner.currentWeek() + 1).size)
                assertEquals(1, fresh.db.dao().trips().size)
            } finally {
                fresh.close()
            }
        }

    @Test
    fun aBackupFromBeforeThePlanPlansItsRecipesOnce() =
        runBlocking {
            r.import("2 onions")
            val old = r.repo.exportBackup().copy(planVersion = 0, plannedMeals = emptyList(), trips = emptyList(), tripItems = emptyList())
            val fresh = DbRule().also { it.open() }
            try {
                assertEquals(emptyList<String>(), fresh.repo.restoreBackup(old, marker()))
                val planner = Planner(fresh.repo)
                val meal = planner.week(planner.currentWeek()).single()
                assertEquals(fresh.repo.snapshot().uses.keys.single(), meal.useId)
            } finally {
                fresh.close()
            }
        }

    @Test
    fun badPlanRowsAreRefusedBeforeAnythingIsWritten() =
        runBlocking {
            val b = populate()
            val before = r.dump()
            val m = b.plannedMeals.first()
            val t = b.tripItems.first()
            val bad =
                listOf(
                    b.copy(plannedMeals = b.plannedMeals + m.copy(id = 99, useId = 12345)),
                    b.copy(plannedMeals = b.plannedMeals + m.copy(id = 99, servings = 0)),
                    b.copy(plannedMeals = b.plannedMeals + m.copy(id = 99, weekStart = 1_000_000)),
                    b.copy(plannedMeals = b.plannedMeals + m),
                    b.copy(tripItems = b.tripItems + t.copy(tripId = 777)),
                    b.copy(tripItems = b.tripItems + t.copy(mergeKey = "cat:x", aisleCode = "MOON")),
                    b.copy(tripItems = b.tripItems + t.copy(mergeKey = "cat:y", qty = "not json")),
                    b.copy(planVersion = 7),
                )
            for (x in bad) {
                assertTrue(r.repo.restoreBackup(x, marker()).isNotEmpty())
                assertEquals(before, r.dump())
            }
        }
}
