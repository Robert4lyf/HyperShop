package com.robert4lyf.hypershop.backup

import com.robert4lyf.hypershop.catalog.EntryEdit
import com.robert4lyf.hypershop.data.BackupData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 9.2: everything in a backup outside the database is checked before a restore starts. */
class BackupFileValidatorTest {
    private val ok =
        BackupFile(
            createdAt = 1_760_000_000_000,
            data = BackupData(listRev = 1, nextId = 1, catalogVersion = "2026.1"),
            settings = BackupSettings("METRIC", "DARK", "STORE", "claude-haiku-4-5", 5.0, 20),
            libraryEdits = listOf(EntryEdit("user_zorblax", "zorblax", "zorblaxes", listOf("blax"), "PRODUCE")),
        )

    private fun problems(f: BackupFile) = BackupFileValidator.validate(f)

    private fun refused(
        f: BackupFile,
        what: String,
    ) {
        val p = problems(f)
        assertTrue("expected \"$what\" in $p", p.any { what in it })
    }

    private fun edit(e: EntryEdit.() -> EntryEdit) = ok.copy(libraryEdits = listOf(ok.libraryEdits.single().e()))

    @Test
    fun aGoodFilePasses() = assertEquals(emptyList<String>(), problems(ok))

    @Test
    fun settings() {
        refused(ok.copy(settings = ok.settings.copy(units = "FURLONGS")), "unknown units")
        refused(ok.copy(settings = ok.settings.copy(theme = "dark")), "unknown theme")
        refused(ok.copy(settings = ok.settings.copy(listView = "GRID")), "unknown list view")
        refused(ok.copy(settings = ok.settings.copy(monthlyCapUsd = Double.NaN)), "monthly cap")
        refused(ok.copy(settings = ok.settings.copy(monthlyCapUsd = Double.POSITIVE_INFINITY)), "monthly cap")
        refused(ok.copy(settings = ok.settings.copy(monthlyCapUsd = -1.0)), "monthly cap")
        refused(ok.copy(settings = ok.settings.copy(dailyCalls = 1_000_000)), "daily calls")
        refused(ok.copy(settings = ok.settings.copy(claudeModel = "")), "Claude model")
        // A model this version doesn't know (retired since) is not a broken file: the phone keeps its own.
        assertEquals(emptyList<String>(), problems(ok.copy(settings = ok.settings.copy(claudeModel = "claude-retired-1"))))
        // Nothing at all is "leave it as it is".
        assertEquals(emptyList<String>(), problems(ok.copy(settings = BackupSettings())))
    }

    @Test
    fun header() {
        refused(ok.copy(version = 0), "unknown version")
        refused(ok.copy(version = BackupFile.VERSION + 1), "unknown version")
        refused(ok.copy(format = "other"), "not a HyperShop backup")
        refused(ok.copy(createdAt = -5), "before 1970")
        refused(ok.copy(appVersion = "x".repeat(1000)), "app version")
        refused(ok.copy(data = ok.data.copy(manualTitle = "x".repeat(1000))), "hand-added title")
    }

    @Test
    fun ingredients() {
        refused(edit { copy(name = " ") }, "name")
        refused(edit { copy(name = "x".repeat(201)) }, "name")
        refused(edit { copy(id = "../../etc") }, "a bad id")
        refused(edit { copy(id = "") }, "a bad id")
        refused(edit { copy(aisle = "MOON") }, "unknown aisle")
        refused(edit { copy(displayPolicy = "WEIGHT") }, "unknown display")
        refused(edit { copy(shoppingUnit = "x".repeat(41)) }, "shopping unit")
        refused(edit { copy(shoppingUnit = "bag\u0000") }, "shopping unit")
        refused(edit { copy(gramsPerCup = Double.NaN) }, "grams per cup")
        refused(edit { copy(gramsPerCup = 0.0) }, "grams per cup")
        refused(edit { copy(synonyms = listOf("")) }, "bad synonyms")
        refused(edit { copy(synonyms = List(51) { "s$it" }) }, "bad synonyms")
        refused(edit { copy(packNote = "x".repeat(301)) }, "overlong note")
        refused(edit { copy(replaceWith = "nothing_like_this") }, "replaced by a missing")
        refused(edit { copy(replaceWith = id) }, "replaced by a missing")
        refused(edit { copy(swaps = listOf("nothing_like_this")) }, "swaps for a missing")
        // A name that is already a bundled ingredient's.
        refused(edit { copy(synonyms = listOf("onion")) }, "is already")
    }

    @Test
    fun duplicatesConflictsAndRings() {
        val z = ok.libraryEdits.single()
        refused(ok.copy(libraryEdits = listOf(z, z)), "duplicate ids")
        refused(ok.copy(libraryEdits = listOf(z, EntryEdit("user_other", "other", "others", listOf("blax"), "PRODUCE"))), "belongs to both")
        val a = z.copy(replaceWith = "user_b")
        val b = EntryEdit("user_b", "bee", "bees", aisle = "PRODUCE", replaceWith = "user_zorblax")
        refused(ok.copy(libraryEdits = listOf(a, b)), "go round in a circle")
        // A chain that ends is fine.
        assertEquals(emptyList<String>(), problems(ok.copy(libraryEdits = listOf(a, b.copy(replaceWith = null)))))
    }

    @Test
    fun theDatabasesOwnChecksStillRun() {
        refused(ok.copy(data = ok.data.copy(nextId = 0)), "nextId")
    }
}
