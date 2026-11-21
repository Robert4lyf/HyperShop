package com.robert4lyf.hypershop.ui.list

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.testutil.TestContainer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Step AB: where everyday things typed into Add item land (the aisle a hand add gets, through the repository's own
 * target), written to build/reports/hand-aisles.txt for the step's report.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HandAisleTest {
    @Test
    fun recordWhereHouseholdThingsLand() {
        val c = TestContainer.create()
        val table =
            HAND_ADDED.joinToString("\n") { name ->
                val t = c.repository.target(name)
                "%-20s %-12s %s".format(name, t.aisle.name, t.mergeKey)
            }
        println(table)
        File("build/reports").mkdirs()
        File("build/reports/hand-aisles.txt").writeText(table + "\n")
        // AB.4: every one of them in Household (AB.0: batteries, toothpaste, cat food and shampoo were Other).
        val other = HAND_ADDED.filter { c.repository.target(it).aisle != com.robert4lyf.hypershop.core.model.AisleCode.HOUSEHOLD }
        org.junit.Assert.assertTrue("not Household: $other", other.isEmpty())
    }

    companion object {
        val HAND_ADDED =
            listOf("bin bags", "batteries", "toothpaste", "kitchen roll", "washing-up liquid", "cat food", "shampoo", "foil")
    }
}
