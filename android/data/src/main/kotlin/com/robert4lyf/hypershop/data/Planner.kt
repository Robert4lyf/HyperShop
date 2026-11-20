package com.robert4lyf.hypershop.data

import androidx.room.withTransaction
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.UseProgress
import com.robert4lyf.hypershop.core.merge.UseState
import com.robert4lyf.hypershop.core.plan.Week

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
     * Step Z "Add to list": the saved recipe's current copy on the list at its own servings, as the review would put
     * it with nothing changed, and planned for this week and linked to its use in the same transaction
     * ([ListRepository.apply]'s plan path, the review's own). Null when the recipe is gone or has no lines.
     */
    suspend fun addToList(libraryKey: String): ApplyResult? {
        val r = latestCopy(libraryKey) ?: return null
        return repo.addSavedRecipe(r.id, plan = true)
    }

    /**
     * Undo of [addToList] (and of the review's add): the meal planned with use [useId] leaves the plan, and only what
     * is still unbought leaves the list ([unplan]: a purchase is never undone). With no meal linked, the use comes off
     * the list the same way ([ListRepository.removeFromList]).
     */
    suspend fun takeBack(useId: Long) {
        db.withTransaction {
            val meals = dao.mealsOfUse(useId)
            if (meals.isEmpty()) repo.removeFromList(useId)
            for (m in meals) unplan(m.id)
        }
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
     * Step Z "Servings" on a recipe's group on the list: its meal's servings and the list's amounts, in one transaction
     * ([setServings]: what was bought stays bought). A use with no meal (from before the plan) is rescaled alone. False
     * when the use is gone or its recipe says nothing about servings.
     */
    suspend fun setUseServings(
        useId: Long,
        servings: Int,
    ): Boolean =
        db.withTransaction {
            if (servings < 1) return@withTransaction false
            val meals = dao.mealsOfUse(useId)
            if (meals.isNotEmpty()) return@withTransaction meals.map { setServings(it.id, servings) }.all { it }
            val l = repo.load()
            val use = l.uses[useId] ?: return@withTransaction false
            if (use.recipeId?.let { dao.recipe(it) }?.servingsOriginal == null) return@withTransaction false
            repo.persist(l, l.rescaleUse(useId, servings))
            true
        }
}

/**
 * Nothing of this meal is on the list — never linked, its use gone, or its items
 * taken off unbought ("Clear all"): a recipe going on now is this meal, not a
 * second one ([ListRepository.apply]'s plan path).
 */
internal fun PlannedMealEntity.pendingOn(l: ShoppingList): Boolean {
    val use = useId?.let { l.uses[it] } ?: return true
    return l.progress(use.id).state == UseState.DROPPED
}
