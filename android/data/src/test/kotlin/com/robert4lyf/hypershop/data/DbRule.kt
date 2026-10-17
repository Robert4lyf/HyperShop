package com.robert4lyf.hypershop.data

import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource

/** A fresh in-memory database per test, with a controllable clock. */
class DbRule : ExternalResource() {
    lateinit var db: HyperShopDatabase
    lateinit var repo: ListRepository
    var now = 1_000_000L

    override fun before() {
        db = HyperShopDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repo = ListRepository(db, clock = { now })
    }

    override fun after() = db.close()

    /** Every table except revs, listRev and nextId: "equal apart from revs". */
    fun dump(): String {
        val c = db.openHelper.readableDatabase
        val tables =
            listOf(
                "Item", "ItemAlias", "Recipe", "RecipeLine", "RecipeUse", "Contribution", "Tick", "LineDecision", "Staple",
                "CategoryOverride",
            )
        return tables.joinToString("\n") { t ->
            val cur = c.query("SELECT * FROM $t ORDER BY 1")
            val rows = ArrayList<String>()
            while (cur.moveToNext()) {
                rows +=
                    (0 until cur.columnCount).filter { cur.getColumnName(it) !in setOf("rev", "updatedAt") }.joinToString(",") {
                        "${cur.getColumnName(it)}=${cur.getString(it)}"
                    }
            }
            cur.close()
            "$t: $rows"
        }
    }
}
