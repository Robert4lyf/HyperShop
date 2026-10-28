package com.robert4lyf.hypershop.data

import androidx.room.withTransaction
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.merge.LineStatus
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.UseProgress
import com.robert4lyf.hypershop.core.merge.UseState
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.plan.Week
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Where a planned meal's shopping stands, derived from its use on the list. */
enum class MealShopping {
    /** Not put on the list (yet, or again since it was taken off). */
    NOT_ON_LIST,

    /** On the list, something still to buy. */
    TO_BUY,

    /** Everything it needed is bought. */
    BOUGHT,

    /** Nothing to buy: every line at home, or the trip finished and the list moved on. */
    NOTHING_TO_BUY,

    /** Its items were taken off the list unbought ("Clear all"). */
    TAKEN_OFF,
    ;

    /** "Add to the list" would put it on: nothing of it is on the list now. */
    val pending: Boolean get() = this == NOT_ON_LIST || this == TAKEN_OFF
}

/** A planned meal as the planner shows it. */
data class PlannedMeal(
    val id: Long,
    val week: Week,
    val libraryKey: String,
    val title: String,
    /** The meal's servings, when the recipe says what it serves. */
    val servings: Int?,
    val useId: Long?,
    val shopping: MealShopping,
    val progress: UseProgress?,
    /** The recipe page, for "From …". */
    val url: String?,
    val bookRef: String?,
    /** Still in "Your recipes" (false: removed from them since it was planned). */
    val saved: Boolean,
    /** X.14: when "Cooked it" was tapped on this meal; null if not. */
    val cookedAt: Long? = null,
)

/**
 * "Remove" on a planned meal, for Undo: the row, and what it took off the list —
 * its whole use when nothing of it was bought, else only its unbought shares.
 */
data class MealRemoval(
    val meal: PlannedMealEntity,
    val use: UseRemoval?,
    val withdrawn: com.robert4lyf.hypershop.core.merge.Withdrawn? = null,
)

/** "Add to the list" for a week: what was put on, for the summary and Undo. */
data class WeekAdded(
    /** (meal, the use it went on the list as). */
    val meals: List<Pair<Long, Long>>,
    /** List rows the meals need, each once. */
    val items: Int,
)

/** What "Add to the list" would do, worked out on a copy of the list. */
data class WeekPreview(
    val meals: Int,
    /** Rows that would be new on the list. */
    val newItems: Int,
    /** Rows already on the list that would get more. */
    val joined: Int,
    /** Ingredients left off as usually at home (staples). */
    val atHome: Int,
)

/**
 * The weekly meal plan (docs/design/release-b.md). Owns [PlannedMealEntity]; puts
 * meals on the list through [ListRepository]'s own review path, so merging,
 * staples, purchases and Undo behave as they always have.
 */
class Planner(
    private val repo: ListRepository,
) {
    private val db = repo.db
    private val dao = repo.dao

    fun currentWeek(): Week = Week.of(repo.clock())

    fun observeWeek(week: Week): Flow<List<PlannedMeal>> =
        combine(dao.observePlannedMeals(week.startEpochDay), repo.list) { rows, list -> describe(rows, list) }

    suspend fun week(week: Week): List<PlannedMeal> = db.withTransaction { describe(dao.plannedMeals(week.startEpochDay), repo.load()) }

    private suspend fun describe(
        rows: List<PlannedMealEntity>,
        l: ShoppingList,
    ): List<PlannedMeal> {
        if (rows.isEmpty()) return emptyList()
        // One query for every meal's recipe, not one per meal.
        val byKey = dao.recipesByKeys(rows.map { it.libraryKey }.distinct()).groupBy { it.libraryKey }
        val copies = byKey.mapValues { (_, cs) -> latestOf(cs) }
        return rows.map { m ->
            val r = copies[m.libraryKey]
            val use = m.useId?.let { l.uses[it] }
            val progress = use?.let { l.progress(it.id) }
            PlannedMeal(
                id = m.id,
                week = Week(m.weekStart),
                libraryKey = m.libraryKey,
                title = r?.title ?: "Recipe",
                servings = if (r?.servingsOriginal == null) null else m.servings ?: r.servingsOriginal,
                useId = use?.id,
                shopping = shoppingOf(progress),
                progress = progress,
                url = r?.url,
                bookRef = r?.let { bookRef(it.book, it.page) },
                saved = r?.lastAddedAt != null,
                cookedAt = m.cookedAt,
            )
        }
    }

    private fun shoppingOf(p: UseProgress?): MealShopping =
        when (p?.state) {
            null -> MealShopping.NOT_ON_LIST
            UseState.STILL_NEEDED -> MealShopping.TO_BUY
            UseState.ALL_BOUGHT, UseState.BOUGHT_THE_REST -> MealShopping.BOUGHT
            UseState.NOTHING_TO_BUY -> MealShopping.NOTHING_TO_BUY
            UseState.DROPPED -> MealShopping.TAKEN_OFF
        }

    /** The library entry's current copy: the latest added, else (forgotten) the newest stored. */
    private suspend fun latestCopy(key: String): RecipeEntity? = latestOf(dao.recipesByKey(key))

    private fun latestOf(cs: List<RecipeEntity>): RecipeEntity? =
        cs.filter { it.lastAddedAt != null }.maxWithOrNull(compareBy<RecipeEntity> { it.lastAddedAt }.thenBy { it.id })
            ?: cs.maxByOrNull { it.id }

    /** Plans a saved recipe for [week], after its other meals. Twice is twice: a second chilli is a choice. */
    suspend fun plan(
        week: Week,
        libraryKey: String,
    ): Long =
        db.withTransaction {
            val servings = latestCopy(libraryKey)?.servingsOriginal
            dao.insertPlannedMeal(
                PlannedMealEntity(
                    weekStart = week.startEpochDay,
                    libraryKey = libraryKey,
                    servings = servings,
                    position = dao.nextMealPosition(week.startEpochDay),
                    useId = null,
                    addedAt = repo.clock(),
                ),
            )
        }

    /**
     * "Remove": the meal leaves the plan. If its use still needs something on the
     * list, that comes off too (bought items stay bought); a meal already shopped
     * for leaves the list alone. Null when it was gone already.
     */
    suspend fun unplan(id: Long): MealRemoval? =
        db.withTransaction {
            val m = dao.plannedMeal(id) ?: return@withTransaction null
            val l = repo.load()
            val stillNeeded = m.useId?.takeIf { it in l.uses && l.progress(it).toBuy > 0 }
            // Another meal sharing the use (an edit relinked two) keeps it.
            val shared = m.useId?.let { u -> dao.mealsOfUse(u).any { it.id != id } } == true
            var use: UseRemoval? = null
            var withdrawn: com.robert4lyf.hypershop.core.merge.Withdrawn? = null
            if (stillNeeded != null && !shared) {
                val cs = l.contributionsOfUse(stillNeeded)
                if (cs.none { it.coveredBy != null }) {
                    use = repo.removeUse(stillNeeded)
                } else {
                    // Something of it was bought: a purchase is never undone, so only what is still needed goes.
                    l.withdrawKeeping(cs.filter { it.coveredBy == null }.map { it.id }.toSet())?.let { (next, w) ->
                        repo.persist(l, next)
                        withdrawn = w
                    }
                }
            }
            dao.deletePlannedMeals(listOf(id))
            MealRemoval(m, use, withdrawn)
        }

    /** Undo of [unplan]. */
    suspend fun restore(r: MealRemoval) =
        db.withTransaction {
            if (dao.plannedMeal(r.meal.id) != null) return@withTransaction
            val restored = r.use?.let { repo.restoreUse(it.copy(meals = emptyList())) } ?: false
            r.withdrawn?.let { w ->
                val old = repo.load()
                old.reinstate(w)?.let { repo.persist(old, it) }
            }
            val useId = r.meal.useId?.takeIf { restored || (it in repo.load().uses) }
            dao.upsertPlannedMeals(listOf(r.meal.copy(useId = useId)))
        }

    /**
     * Servings: the plan's number, and the list's amounts if the meal is on it
     * ([ShoppingList.rescaleUse]: what was bought stays bought). False when the
     * meal is gone or its recipe says nothing about servings.
     */
    suspend fun setServings(
        id: Long,
        servings: Int,
    ): Boolean =
        db.withTransaction {
            val m = dao.plannedMeal(id) ?: return@withTransaction false
            if (latestCopy(m.libraryKey)?.servingsOriginal == null || servings < 1) return@withTransaction false
            dao.setMealServings(id, servings)
            val old = repo.load()
            val use = m.useId?.takeIf { it in old.uses }
            if (use != null) repo.persist(old, old.rescaleUse(use, servings))
            true
        }

    /**
     * X.14 "Cooked it" on meal [id], or ([cooked] false) taking it back: the meal says so, and its
     * recipe counts one cooking more or less for "your usual", in one transaction. False when the
     * meal is gone or already in that state, so a double tap never counts twice.
     */
    suspend fun setCooked(
        id: Long,
        cooked: Boolean,
    ): Boolean =
        db.withTransaction {
            val m = dao.plannedMeal(id) ?: return@withTransaction false
            if ((m.cookedAt != null) == cooked) return@withTransaction false
            dao.setMealCooked(id, if (cooked) repo.clock() else null)
            if (cooked) repo.cooked(m.libraryKey) else repo.uncooked(m.libraryKey)
            true
        }

    /** The latest week before [week] with anything planned, for "Repeat the week of …". */
    suspend fun previousPlannedWeek(week: Week): Week? = dao.latestWeekBefore(week.startEpochDay)?.let { Week(it) }

    /**
     * "Repeat": [from]'s meals planned again for [to], at the servings they had,
     * not on the list yet. A meal [to] already has is not planned twice (each
     * repeat of a recipe in [from] matches one in [to]). The new meal ids, for Undo.
     */
    suspend fun repeatWeek(
        from: Week,
        to: Week,
    ): List<Long> =
        db.withTransaction {
            val have = dao.plannedMeals(to.startEpochDay).groupingBy { it.libraryKey }.eachCount().toMutableMap()
            var position = dao.nextMealPosition(to.startEpochDay)
            val now = repo.clock()
            val ids =
                dao.plannedMeals(from.startEpochDay).mapNotNull { m ->
                    // A recipe gone altogether since can't be cooked again.
                    if (latestCopy(m.libraryKey) == null) return@mapNotNull null
                    val n = have[m.libraryKey] ?: 0
                    if (n > 0) {
                        have[m.libraryKey] = n - 1
                        return@mapNotNull null
                    }
                    dao.insertPlannedMeal(m.copy(id = 0, weekStart = to.startEpochDay, position = position++, useId = null, addedAt = now))
                }
            ids
        }

    /** Undo of [plan] or [repeatWeek]: meals not put on the list since. */
    suspend fun unplanNew(ids: List<Long>) =
        db.withTransaction {
            val gone = ids.filter { dao.plannedMeal(it)?.useId == null }
            if (gone.isNotEmpty()) dao.deletePlannedMeals(gone)
        }

    /**
     * What [addWeekToList] would do now: each pending meal applied, in order, to a
     * copy of the list, exactly as the real write will.
     */
    suspend fun previewWeek(week: Week): WeekPreview =
        db.withTransaction {
            val start = repo.load()
            var l = start
            var atHome = 0
            var n = 0
            val staples = repo.staples()
            val overrides = repo.overrides()
            for (m in pending(week, start)) {
                val src = source(m) ?: continue
                val incoming =
                    repo.merger.incoming(
                        src.recipe.id,
                        src.recipe.servingsOriginal,
                        m.servings ?: src.recipe.servingsOriginal,
                        UnitLocale.valueOf(src.recipe.unitLocale),
                        src.lines,
                        overrides,
                    )
                val preview = repo.merger.preview(l, incoming, repo.merger.defaultDecisions(incoming, staples))
                val r = repo.merger.apply(l, preview, repo.clock()) as? ApplyResult.Applied ?: continue
                l = r.list
                atHome += preview.lineStatus.values.count { it == LineStatus.IN_STOCK }
                n++
            }
            val before = start.liveItems.map { it.id }.toSet()
            val touched = l.contributions.filter { it.useId !in start.uses }.map { it.itemId }.toSet()
            WeekPreview(n, touched.count { it !in before }, touched.count { it in before }, atHome)
        }

    private class Source(
        val recipe: RecipeEntity,
        val lines: List<Pair<Long, ParsedIngredient>>,
    )

    private suspend fun source(m: PlannedMealEntity): Source? {
        val r = latestCopy(m.libraryKey) ?: return null
        val lines = dao.lines(r.id).map { it.id to repo.json.decodeFromString(ParsedIngredient.serializer(), it.parsedJson) }
        return if (lines.isEmpty()) null else Source(r, lines)
    }

    /** The meals "Add to the list" puts on: not on it, or taken off it since. */
    private suspend fun pending(
        week: Week,
        l: ShoppingList,
    ): List<PlannedMealEntity> = dao.plannedMeals(week.startEpochDay).filter { it.pendingOn(l) }

    /**
     * "Add to the list": every pending meal of [week] goes on as the review would
     * put it with nothing changed (staples at home), and is linked to its use, all
     * in one transaction: a crash adds the whole week or none of it, and a second
     * tap finds nothing pending. Null when nothing was pending.
     */
    suspend fun addWeekToList(week: Week): WeekAdded? =
        db.withTransaction {
            val meals = pending(week, repo.load())
            if (meals.isEmpty()) return@withTransaction null
            val added = ArrayList<Pair<Long, Long>>()
            for (m in meals) {
                val src = source(m) ?: continue
                val r = src.recipe
                val stored =
                    repo.storeRecipe(
                        r.url,
                        r.title,
                        r.servingsOriginal,
                        UnitLocale.valueOf(r.unitLocale),
                        r.source,
                        runCatching { ParsedBy.valueOf(r.parsedBy) }.getOrDefault(ParsedBy.RULES),
                        r.model,
                        false,
                        src.lines.map { it.second },
                        m.libraryKey,
                        r.book,
                        r.page,
                    )
                // Inside one transaction nothing else writes, so the preview cannot go stale.
                val result = repo.apply(repo.preview(stored, m.servings ?: r.servingsOriginal), plan = false)
                if (result !is ApplyResult.Applied) error("A week's add went stale inside its own transaction")
                dao.linkMeal(m.id, result.useId)
                added += m.id to result.useId
            }
            if (added.isEmpty()) return@withTransaction null
            // Rows, each once: two meals wanting onions put one onion row on the list.
            val uses = added.map { it.second }.toSet()
            WeekAdded(added, repo.load().contributions.filter { it.useId in uses }.map { it.itemId }.toSet().size)
        }

    /**
     * Undo of [addWeekToList]: each use it made comes off, and its meal is planned
     * but not on the list again. A use something was bought for since keeps that
     * purchase (and its meal its link): only what is still needed goes.
     */
    suspend fun undoAddWeek(w: WeekAdded) =
        db.withTransaction {
            for ((mealId, useId) in w.meals) {
                val old = repo.load()
                val cs = old.contributionsOfUse(useId)
                if (cs.any { it.coveredBy != null }) {
                    old.withdraw(cs.filter { it.coveredBy == null }.map { it.id }.toSet())?.let { repo.persist(old, it) }
                    continue
                }
                if (dao.plannedMeal(mealId)?.useId == useId) dao.linkMeal(mealId, null)
                val recipeId = old.uses[useId]?.recipeId ?: continue
                repo.persist(old, old.removeUse(useId))
                if (dao.usesOfRecipe(recipeId) == 0) repo.discardRecipe(recipeId)
            }
        }
}

/**
 * "Add to the list" would put this meal on: nothing of it is on the list — never
 * linked, its use gone, or its items taken off unbought ("Clear all"). The one
 * rule for the planner and for a recipe going on from elsewhere ([ListRepository.apply]).
 */
internal fun PlannedMealEntity.pendingOn(l: ShoppingList): Boolean {
    val use = useId?.let { l.uses[it] } ?: return true
    return l.progress(use.id).state == UseState.DROPPED
}
