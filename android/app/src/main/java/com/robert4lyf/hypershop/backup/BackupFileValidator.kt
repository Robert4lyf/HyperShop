package com.robert4lyf.hypershop.backup

import com.robert4lyf.hypershop.catalog.EntryEdit
import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.catalog.CatalogKey
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.catalog.DisplayPolicy
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.data.BackupValidator
import com.robert4lyf.hypershop.settings.ListView
import com.robert4lyf.hypershop.settings.ThemeMode

/**
 * The parts of a backup file outside the database ([BackupValidator] has that):
 * the file's own header, the settings and the ingredient-library edits. Every
 * value is checked before anything is written, so a restore that starts has
 * nothing left to refuse. The Claude model is the one exception: a model id this
 * version doesn't know (retired since) leaves the phone's choice as it is.
 */
object BackupFileValidator {
    const val MAX_EDITS = 5_000
    private const val MAX_ID = 100
    private const val MAX_NAME = 200
    private const val MAX_SYNONYMS = 50
    private const val MAX_UNIT = 40
    private const val MAX_NOTE = 300
    private val idShape = Regex("[a-z0-9_]+")

    fun validate(
        f: BackupFile,
        base: Catalog = CatalogLoader.default,
    ): List<String> {
        val problems = ArrayList<String>()

        fun check(
            ok: Boolean,
            what: String,
        ) {
            if (!ok && problems.size < 20) problems += what
        }
        check(f.format == BackupFile.FORMAT, "not a HyperShop backup")
        check(f.version in 1..BackupFile.VERSION, "unknown version ${f.version}")
        check(f.createdAt >= 0, "a creation time before 1970")
        check((f.appVersion?.length ?: 0) <= MAX_NAME, "the app version is too long")
        check(f.data.catalogVersion.length <= MAX_NAME, "the catalogue version is too long")
        check((f.data.manualTitle?.length ?: 0) <= MAX_NAME, "the hand-added title is too long")
        settings(f.settings, ::check)
        library(f.libraryEdits, base, ::check)
        return (problems + BackupValidator.validate(f.data)).take(20)
    }

    private fun settings(
        s: BackupSettings,
        check: (Boolean, String) -> Unit,
    ) {
        check(s.units == null || UnitsSetting.entries.any { it.name == s.units }, "settings: unknown units ${s.units?.take(40)}")
        check(s.theme == null || ThemeMode.entries.any { it.name == s.theme }, "settings: unknown theme ${s.theme?.take(40)}")
        check(s.listView == null || ListView.entries.any { it.name == s.listView }, "settings: unknown list view ${s.listView?.take(40)}")
        check(s.claudeModel == null || s.claudeModel.length in 1..MAX_ID, "settings: a bad Claude model")
        check(
            s.monthlyCapUsd == null || (s.monthlyCapUsd.isFinite() && s.monthlyCapUsd in 0.0..100.0),
            "settings: monthly cap out of range",
        )
        check(s.dailyCalls == null || s.dailyCalls in 0..1000, "settings: daily calls out of range")
    }

    private fun library(
        edits: List<EntryEdit>,
        base: Catalog,
        check: (Boolean, String) -> Unit,
    ) {
        check(edits.size <= MAX_EDITS, "ingredients: ${edits.size} edits is more than a backup can hold")
        val ids = edits.map { it.id }
        check(ids.size == ids.toSet().size, "ingredients: duplicate ids")
        val aisles = AisleCode.entries.map { it.name }.toSet()
        val policies = DisplayPolicy.entries.map { it.name }.toSet()
        for (e in edits) {
            val at = "ingredient ${e.id.take(40)}"
            check(e.id.length in 1..MAX_ID && idShape.matches(e.id), "$at: a bad id")
            check(e.name.isNotBlank() && e.name.length <= MAX_NAME, "$at: a missing or overlong name")
            check(e.plural.length <= MAX_NAME, "$at: an overlong plural")
            check(e.synonyms.size <= MAX_SYNONYMS && e.synonyms.all { it.isNotBlank() && it.length <= MAX_NAME }, "$at: bad synonyms")
            check(e.aisle in aisles, "$at: unknown aisle ${e.aisle.take(40)}")
            check(e.displayPolicy in policies, "$at: unknown display ${e.displayPolicy.take(40)}")
            check(e.shoppingUnit.length <= MAX_UNIT && e.shoppingUnit.none { it.isISOControl() }, "$at: a bad shopping unit")
            check(
                e.gramsPerCup == null || (e.gramsPerCup.isFinite() && e.gramsPerCup > 0 && e.gramsPerCup <= 10_000),
                "$at: grams per cup out of range",
            )
            check((e.packNote?.length ?: 0) <= MAX_NOTE && (e.group?.length ?: 0) <= MAX_NAME, "$at: an overlong note or group")
            check(e.swaps.size <= MAX_SYNONYMS && e.swaps.all { it.length <= MAX_ID }, "$at: bad swaps")
        }
        if (edits.size > MAX_EDITS || ids.size != ids.toSet().size) return
        val byId = edits.associateBy { it.id }
        val known = { id: String -> id in byId || base.byId.containsKey(id) }
        for (e in edits) {
            val at = "ingredient ${e.id.take(40)}"
            check(e.replaceWith == null || (e.replaceWith != e.id && known(e.replaceWith)), "$at: replaced by a missing ingredient")
            check(e.swaps.all { known(it) }, "$at: swaps for a missing ingredient")
        }
        // "Always use X instead" must end somewhere: a ring of replacements has no ingredient to land on.
        for (e in edits) {
            val seen = HashSet<String>()
            var at: String? = e.id
            while (at != null && seen.add(at)) at = byId[at]?.replaceWith ?: base.byId[at]?.replaceWith
            check(at == null, "ingredient ${e.id.take(40)}: replacements go round in a circle")
        }
        // A name answers to one ingredient: between two edits, or an edit and a bundled entry it doesn't replace.
        val owner = HashMap<String, String>()
        for (e in edits) {
            for (n in listOf(e.name, e.plural) + e.synonyms) {
                val key = CatalogKey.of(n)
                if (key.isEmpty()) continue
                val other = owner.putIfAbsent(key, e.id)
                check(
                    other == null || other == e.id,
                    "ingredients: \"${n.take(40)}\" belongs to both ${other?.take(40)} and ${e.id.take(40)}",
                )
                val bundled = base.lookup(n)
                check(
                    bundled == null || bundled.id == e.id || bundled.id in byId,
                    "ingredients: \"${n.take(40)}\" is already ${bundled?.name?.take(40)}",
                )
            }
        }
    }
}
