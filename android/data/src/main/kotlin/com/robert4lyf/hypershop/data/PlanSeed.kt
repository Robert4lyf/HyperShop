package com.robert4lyf.hypershop.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.robert4lyf.hypershop.core.plan.Week
import java.time.ZoneId

/**
 * Before Release B the recipes on the list *were* the week's meals. Moving to a
 * plan of its own, each recipe still on the list (not archived) becomes a meal
 * planned for the week it was added, linked to its use: nothing on the list
 * changes, and nothing is added twice. Run by the 4→5 migration, and by a restore
 * of a backup made before plans existed.
 */
internal object PlanSeed {
    fun fromUses(
        db: SupportSQLiteDatabase,
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val rows = ArrayList<Array<Any?>>()
        db.query(
            "SELECT u.id, u.servings, u.addedAt, COALESCE(r.libraryKey, r.url, 'r:' || r.id), r.servingsOriginal " +
                "FROM RecipeUse u JOIN Recipe r ON r.id = u.recipeId " +
                "WHERE u.kind = 'RECIPE' AND u.archivedAt IS NULL " +
                "AND u.id NOT IN (SELECT useId FROM PlannedMeal WHERE useId IS NOT NULL) ORDER BY u.addedAt, u.id",
        ).use { c ->
            val position = HashMap<Long, Int>()
            while (c.moveToNext()) {
                val week = Week.of(c.getLong(2), zone).startEpochDay
                val p = position.getOrPut(week) { nextPosition(db, week) }
                position[week] = p + 1
                // A recipe that doesn't say what it serves has no servings to keep.
                val servings = if (c.isNull(4)) null else c.getInt(1)
                rows += arrayOf(week, c.getString(3), servings, p, c.getLong(0), c.getLong(2))
            }
        }
        for (r in rows) {
            db.execSQL(
                "INSERT INTO PlannedMeal(weekStart, libraryKey, servings, position, useId, addedAt) VALUES (?, ?, ?, ?, ?, ?)",
                r,
            )
        }
    }

    private fun nextPosition(
        db: SupportSQLiteDatabase,
        week: Long,
    ): Int =
        db.query("SELECT COALESCE(MAX(position), -1) + 1 FROM PlannedMeal WHERE weekStart = ?", arrayOf<Any?>(week)).use {
            it.moveToFirst()
            it.getInt(0)
        }
}
