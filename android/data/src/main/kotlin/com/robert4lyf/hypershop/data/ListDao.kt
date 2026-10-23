package com.robert4lyf.hypershop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ListDao {
    // ---- Meta -----------------------------------------------------------------
    @Query("SELECT * FROM Meta WHERE id = 1")
    suspend fun meta(): MetaEntity?

    @Query("SELECT * FROM Meta WHERE id = 1")
    fun observeMeta(): Flow<MetaEntity?>

    @Upsert
    suspend fun putMeta(m: MetaEntity)

    // ---- whole list (read inside a transaction) -------------------------------
    @Query("SELECT * FROM Item")
    suspend fun items(): List<ItemEntity>

    @Query("SELECT * FROM RecipeUse")
    suspend fun uses(): List<RecipeUseEntity>

    @Query("SELECT * FROM Contribution ORDER BY id")
    suspend fun contributions(): List<ContributionEntity>

    // In id order: "untick" undoes the most recent tick, the last one in this list.
    @Query("SELECT * FROM Tick ORDER BY id")
    suspend fun ticks(): List<TickEntity>

    @Query("SELECT * FROM ItemAlias")
    suspend fun aliases(): List<ItemAliasEntity>

    @Upsert
    suspend fun upsertItems(rows: List<ItemEntity>)

    @Upsert
    suspend fun upsertUses(rows: List<RecipeUseEntity>)

    @Upsert
    suspend fun upsertTicks(rows: List<TickEntity>)

    @Upsert
    suspend fun upsertContributions(rows: List<ContributionEntity>)

    @Upsert
    suspend fun upsertAliases(rows: List<ItemAliasEntity>)

    @Query("DELETE FROM Contribution WHERE id IN (:ids)")
    suspend fun deleteContributions(ids: List<Long>)

    @Query("DELETE FROM Tick WHERE id IN (:ids)")
    suspend fun deleteTicks(ids: List<Long>)

    @Query("DELETE FROM RecipeUse WHERE id IN (:ids)")
    suspend fun deleteUses(ids: List<Long>)

    @Query("DELETE FROM Item WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<Long>)

    @Query("DELETE FROM ItemAlias WHERE oldId IN (:ids)")
    suspend fun deleteAliases(ids: List<Long>)

    // ---- recipes --------------------------------------------------------------
    @Insert
    suspend fun insertRecipe(r: RecipeEntity): Long

    @Insert
    suspend fun insertLines(lines: List<RecipeLineEntity>): List<Long>

    @Query("SELECT * FROM Recipe WHERE id = :id")
    suspend fun recipe(id: Long): RecipeEntity?

    @Query("SELECT * FROM Recipe WHERE id IN (:ids)")
    suspend fun recipes(ids: List<Long>): List<RecipeEntity>

    @Query("SELECT * FROM Recipe WHERE url = :url ORDER BY importedAt DESC")
    suspend fun recipesByUrl(url: String): List<RecipeEntity>

    @Query("DELETE FROM Recipe WHERE id = :id")
    suspend fun deleteRecipe(id: Long)

    /** The library: each key's latest copy, the one added longest ago first. */
    @Query(
        "SELECT * FROM Recipe r WHERE r.lastAddedAt IS NOT NULL AND r.id = (" +
            "SELECT r2.id FROM Recipe r2 WHERE r2.libraryKey = r.libraryKey AND r2.lastAddedAt IS NOT NULL " +
            "ORDER BY r2.lastAddedAt DESC, r2.id DESC LIMIT 1) ORDER BY r.lastAddedAt ASC, r.id ASC",
    )
    fun observeLibrary(): Flow<List<RecipeEntity>>

    @Query("SELECT recipeId, COUNT(*) AS n FROM RecipeLine WHERE recipeId IN (:ids) GROUP BY recipeId")
    suspend fun lineCounts(ids: List<Long>): List<LineCount>

    @Query("SELECT * FROM Recipe WHERE libraryKey = :key")
    suspend fun recipesByKey(key: String): List<RecipeEntity>

    @Query("SELECT * FROM Recipe WHERE libraryKey IN (:keys)")
    suspend fun recipesByKeys(keys: List<String>): List<RecipeEntity>

    @Query("UPDATE Recipe SET lastAddedAt = :at, libraryKey = COALESCE(libraryKey, url, 'r:' || id) WHERE id = :id")
    suspend fun markAdded(
        id: Long,
        at: Long,
    )

    @Query("UPDATE Recipe SET lastAddedAt = NULL WHERE libraryKey = :key")
    suspend fun forgetKey(key: String)

    /** Undo of [forgetKey], one copy at a time. */
    @Query("UPDATE Recipe SET lastAddedAt = :at WHERE id = :id")
    suspend fun setLastAdded(
        id: Long,
        at: Long,
    )

    /**
     * Forgotten library copies nothing on the list uses: gone at start-up, once Undo is
     * long past. Only copies imported before [before]: a review not yet confirmed (an
     * edit or a re-add stores its copy with a key and no lastAddedAt) has the same shape.
     */
    @Query(
        "DELETE FROM Recipe WHERE lastAddedAt IS NULL AND libraryKey IS NOT NULL AND importedAt < :before " +
            "AND id NOT IN (SELECT recipeId FROM RecipeUse WHERE recipeId IS NOT NULL) " +
            // A meal planned for this week or later still names it: the plan shows it and can add it.
            "AND libraryKey NOT IN (SELECT libraryKey FROM PlannedMeal WHERE weekStart >= :keepWeek)",
    )
    suspend fun deleteForgottenRecipes(
        before: Long,
        keepWeek: Long,
    )

    @Query("SELECT COUNT(*) FROM RecipeUse WHERE recipeId = :id")
    suspend fun usesOfRecipe(id: Long): Int

    @Query("SELECT * FROM RecipeLine WHERE recipeId = :recipeId ORDER BY id")
    suspend fun lines(recipeId: Long): List<RecipeLineEntity>

    @Query("SELECT * FROM RecipeLine WHERE recipeId IN (:ids) ORDER BY id")
    suspend fun linesFor(ids: List<Long>): List<RecipeLineEntity>

    @Query("UPDATE Recipe SET claudePending = :pending, parsedBy = :parsedBy, model = :model WHERE id = :id")
    suspend fun setParsed(
        id: Long,
        pending: Boolean,
        parsedBy: String,
        model: String?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDecisions(rows: List<LineDecisionEntity>)

    @Query("SELECT * FROM LineDecision WHERE useId = :useId")
    suspend fun decisions(useId: Long): List<LineDecisionEntity>

    @Query("SELECT * FROM LineDecision WHERE useId IN (:useIds)")
    suspend fun decisionsFor(useIds: List<Long>): List<LineDecisionEntity>

    // ---- aisles, store, overrides, staples ----------------------------------------
    @Query("SELECT * FROM Aisle ORDER BY defaultOrder")
    suspend fun aisles(): List<AisleEntity>

    @Query("SELECT * FROM AisleOrder WHERE storeId = :storeId ORDER BY position")
    suspend fun aisleOrder(storeId: Long = 1): List<AisleOrderEntity>

    @Upsert
    suspend fun putAisleOrder(rows: List<AisleOrderEntity>)

    @Query("SELECT * FROM CategoryOverride")
    suspend fun overrides(): List<CategoryOverrideEntity>

    @Upsert
    suspend fun putOverride(o: CategoryOverrideEntity)

    @Query("DELETE FROM CategoryOverride WHERE `key` = :key")
    suspend fun deleteOverride(key: String)

    @Query("SELECT * FROM Staple")
    suspend fun staples(): List<StapleEntity>

    @Query("SELECT * FROM Staple")
    fun observeStaples(): Flow<List<StapleEntity>>

    @Upsert
    suspend fun putStaple(s: StapleEntity)

    @Query("DELETE FROM Staple WHERE `key` = :key")
    suspend fun deleteStaple(key: String)

    // ---- deleted items --------------------------------------------------------
    @Upsert
    suspend fun putDeleted(rows: List<DeletedItemEntity>)

    @Query("DELETE FROM DeletedItem WHERE id IN (:ids)")
    suspend fun undelete(ids: List<Long>)

    @Query("SELECT id FROM DeletedItem WHERE deletedAt < :before")
    suspend fun deletedBefore(before: Long): List<Long>

    // ---- Claude usage ---------------------------------------------------------
    @Query("SELECT * FROM ClaudeUsage")
    suspend fun usage(): List<ClaudeUsageEntity>

    @Query("SELECT * FROM ClaudeUsage")
    fun observeUsage(): Flow<List<ClaudeUsageEntity>>

    @Query("SELECT * FROM ClaudeUsage WHERE date = :date AND model = :model")
    suspend fun usageRow(
        date: String,
        model: String,
    ): ClaudeUsageEntity?

    @Upsert
    suspend fun putUsage(u: ClaudeUsageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUsageIfAbsent(u: ClaudeUsageEntity)

    /** One call's tokens, added in a single statement (no read-then-write). */
    @Query(
        "UPDATE ClaudeUsage SET inputTokens = inputTokens + :input, outputTokens = outputTokens + :output, " +
            "cacheWrite = cacheWrite + :cacheWrite, cacheRead = cacheRead + :cacheRead, calls = calls + 1 " +
            "WHERE date = :date AND model = :model",
    )
    suspend fun addUsage(
        date: String,
        model: String,
        input: Long,
        output: Long,
        cacheWrite: Long,
        cacheRead: Long,
    )

    // ---- import jobs ------------------------------------------------------------
    @Insert
    suspend fun insertJob(j: ImportJobEntity): Long

    @Upsert
    suspend fun putJob(j: ImportJobEntity)

    // A DONE job stays done: a late worker run or a second Add can't bring it back.
    @Query("UPDATE ImportJob SET state = :state WHERE id = :id AND state != 'DONE'")
    suspend fun setJobState(
        id: Long,
        state: String,
    )

    // Not over a DONE job, nor a DISCARDING one: an answer that lands after Cancel must not bring the import back.
    @Query(
        "UPDATE ImportJob SET state = :state, resultJson = :resultJson, error = :error " +
            "WHERE id = :id AND state NOT IN ('DONE', 'DISCARDING')",
    )
    suspend fun finishJob(
        id: Long,
        state: String,
        resultJson: String?,
        error: String?,
    )

    /** The worker's finish: lands only while the job is still the worker's (QUEUED or RUNNING); 0 rows otherwise. */
    @Query(
        "UPDATE ImportJob SET state = :state, resultJson = :resultJson, error = :error " +
            "WHERE id = :id AND state IN ('QUEUED', 'RUNNING')",
    )
    suspend fun finishRunningJob(
        id: Long,
        state: String,
        resultJson: String?,
        error: String?,
    ): Int

    @Query("SELECT * FROM ImportJob WHERE id = :id")
    suspend fun job(id: Long): ImportJobEntity?

    /** The review screen's edits, kept with the job so leaving and coming back loses nothing. */
    @Query("UPDATE ImportJob SET resultJson = :resultJson WHERE id = :id AND state != 'DONE'")
    suspend fun updateJobResult(
        id: Long,
        resultJson: String,
    )

    /** Re-emitted whenever the row changes: the review screen waits on it rather than polling. */
    @Query("SELECT * FROM ImportJob WHERE id = :id")
    fun observeJob(id: Long): Flow<ImportJobEntity?>

    @Query("SELECT * FROM ImportJob WHERE state = :state ORDER BY createdAt")
    fun observeJobs(state: String): Flow<List<ImportJobEntity>>

    @Query("DELETE FROM ImportJob WHERE state IN ('DONE', 'FAILED') AND createdAt < :before")
    suspend fun deleteFinishedJobsBefore(before: Long)

    /** The worker's claim: only a job still waiting (or interrupted mid-run) is run; 0 rows otherwise. */
    @Query("UPDATE ImportJob SET state = 'RUNNING' WHERE id = :id AND state IN ('QUEUED', 'RUNNING')")
    suspend fun claimJob(id: Long): Int

    // ---- watch ----------------------------------------------------------------
    @Query("SELECT * FROM WatchInstall WHERE installId = :id")
    suspend fun install(id: Int): WatchInstallEntity?

    @Upsert
    suspend fun putInstall(i: WatchInstallEntity)

    @Upsert
    suspend fun putOpResult(r: WatchOpResultEntity)

    @Query("SELECT * FROM WatchOpResult WHERE installId = :id ORDER BY seq")
    suspend fun opResults(id: Int): List<WatchOpResultEntity>

    @Query("DELETE FROM WatchOpResult WHERE installId = :id AND seq <= :upTo")
    suspend fun trimOpResults(
        id: Int,
        upTo: Int,
    )

    @Query("DELETE FROM WatchInstall")
    suspend fun clearInstalls()

    /** The install most recently added (the table has no clock; one watch is the case). */
    @Query("SELECT installId FROM WatchInstall ORDER BY rowid DESC LIMIT 1")
    suspend fun lastInstall(): Int?

    @Query("DELETE FROM WatchOpResult")
    suspend fun clearOpResults()

    /** Every copy of a library entry, so the list and the library show the new name. */
    @Query("UPDATE Recipe SET title = :title WHERE libraryKey = :key OR (libraryKey IS NULL AND (url = :key OR 'r:' || id = :key))")
    suspend fun renameRecipes(
        key: String,
        title: String,
    ): Int

    /** Bumped on every list write; the UI observes it. */
    @Query("SELECT listRev FROM Meta WHERE id = 1")
    fun observeListRev(): Flow<Long?>

    // ---- backup and restore (Backup.kt) ------------------------------------------
    @Query("SELECT * FROM LineDecision")
    suspend fun allDecisions(): List<LineDecisionEntity>

    @Query("SELECT * FROM Recipe")
    suspend fun allRecipes(): List<RecipeEntity>

    @Query("SELECT * FROM RecipeLine ORDER BY id")
    suspend fun allLines(): List<RecipeLineEntity>

    @Query("SELECT * FROM StoreProfile")
    suspend fun stores(): List<StoreProfileEntity>

    @Query("SELECT * FROM AisleOrder")
    suspend fun allAisleOrder(): List<AisleOrderEntity>

    @Query("SELECT * FROM DeletedItem")
    suspend fun allDeleted(): List<DeletedItemEntity>

    @Insert
    suspend fun insertRecipes(rows: List<RecipeEntity>)

    @Insert
    suspend fun insertAisles(rows: List<AisleEntity>)

    @Insert
    suspend fun insertStores(rows: List<StoreProfileEntity>)

    @Insert
    suspend fun insertOverrides(rows: List<CategoryOverrideEntity>)

    @Insert
    suspend fun insertStaples(rows: List<StapleEntity>)

    @Insert
    suspend fun insertUsage(rows: List<ClaudeUsageEntity>)

    /** Children first, then parents: every user-data table, in an order the foreign keys allow. */
    @Query("DELETE FROM LineDecision")
    suspend fun clearDecisions()

    @Query("DELETE FROM Contribution")
    suspend fun clearContributions()

    @Query("DELETE FROM Tick")
    suspend fun clearTicks()

    @Query("DELETE FROM RecipeUse")
    suspend fun clearUses()

    @Query("DELETE FROM RecipeLine")
    suspend fun clearLines()

    @Query("DELETE FROM Recipe")
    suspend fun clearRecipes()

    @Query("DELETE FROM ItemAlias")
    suspend fun clearAliases()

    @Query("DELETE FROM DeletedItem")
    suspend fun clearDeleted()

    @Query("DELETE FROM Item")
    suspend fun clearItems()

    @Query("DELETE FROM AisleOrder")
    suspend fun clearAisleOrder()

    @Query("DELETE FROM StoreProfile")
    suspend fun clearStores()

    @Query("DELETE FROM Aisle")
    suspend fun clearAisles()

    @Query("DELETE FROM CategoryOverride")
    suspend fun clearOverrides()

    @Query("DELETE FROM Staple")
    suspend fun clearStaples()

    @Query("DELETE FROM ClaudeUsage")
    suspend fun clearUsage()

    @Query("DELETE FROM ImportJob")
    suspend fun clearJobs()

    // ---- the meal plan (Release B) -------------------------------------------
    @Query("SELECT * FROM PlannedMeal WHERE weekStart = :week ORDER BY position, id")
    suspend fun plannedMeals(week: Long): List<PlannedMealEntity>

    @Query("SELECT * FROM PlannedMeal WHERE weekStart = :week ORDER BY position, id")
    fun observePlannedMeals(week: Long): Flow<List<PlannedMealEntity>>

    @Query("SELECT * FROM PlannedMeal")
    suspend fun allPlannedMeals(): List<PlannedMealEntity>

    /** How many meals each saved recipe has been planned as, every week kept. */
    @Query("SELECT libraryKey AS `key`, COUNT(*) AS n FROM PlannedMeal GROUP BY libraryKey")
    fun observeTimesPlanned(): Flow<List<KeyCount>>

    @Query("SELECT * FROM PlannedMeal WHERE id = :id")
    suspend fun plannedMeal(id: Long): PlannedMealEntity?

    @Query("SELECT * FROM PlannedMeal WHERE useId = :useId")
    suspend fun mealsOfUse(useId: Long): List<PlannedMealEntity>

    /** The latest week before [week] with a meal planned, or null. */
    @Query("SELECT MAX(weekStart) FROM PlannedMeal WHERE weekStart < :week")
    suspend fun latestWeekBefore(week: Long): Long?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM PlannedMeal WHERE weekStart = :week")
    suspend fun nextMealPosition(week: Long): Int

    @Insert
    suspend fun insertPlannedMeal(m: PlannedMealEntity): Long

    @Upsert
    suspend fun upsertPlannedMeals(rows: List<PlannedMealEntity>)

    @Query("DELETE FROM PlannedMeal WHERE id IN (:ids)")
    suspend fun deletePlannedMeals(ids: List<Long>)

    @Query("UPDATE PlannedMeal SET useId = :useId WHERE id = :id")
    suspend fun linkMeal(
        id: Long,
        useId: Long?,
    )

    @Query("UPDATE PlannedMeal SET servings = :servings WHERE id = :id")
    suspend fun setMealServings(
        id: Long,
        servings: Int?,
    )

    @Query("DELETE FROM PlannedMeal")
    suspend fun clearPlannedMeals()

    // ---- shopping history and Buy again (Release B) ----------------------------
    @Insert
    suspend fun insertTrip(t: TripEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTripItems(rows: List<TripItemEntity>)

    @Query("SELECT * FROM Trip ORDER BY finishedAt DESC, id DESC")
    suspend fun trips(): List<TripEntity>

    @Query("SELECT * FROM TripItem WHERE tripId IN (:ids)")
    suspend fun tripItems(ids: List<Long>): List<TripItemEntity>

    @Query("SELECT * FROM TripItem")
    suspend fun allTripItems(): List<TripItemEntity>

    /** Undo of "Finish shopping": the trip it recorded. */
    @Query("DELETE FROM Trip WHERE finishedAt = :stamp")
    suspend fun deleteTripAt(stamp: Long)

    @Query("DELETE FROM Trip WHERE id NOT IN (SELECT id FROM Trip ORDER BY finishedAt DESC, id DESC LIMIT :keep)")
    suspend fun pruneTrips(keep: Int)

    @Query("DELETE FROM Trip")
    suspend fun clearTrips()

    @Query("SELECT COUNT(*) FROM Trip")
    fun observeTripCount(): Flow<Int>

    @Query("SELECT * FROM Regular")
    suspend fun regulars(): List<RegularEntity>

    @Query("SELECT * FROM Regular")
    fun observeRegulars(): Flow<List<RegularEntity>>

    @Upsert
    suspend fun putRegular(r: RegularEntity)

    @Insert
    suspend fun insertRegulars(rows: List<RegularEntity>)

    @Query("DELETE FROM Regular WHERE mergeKey = :key")
    suspend fun deleteRegular(key: String)

    @Query("DELETE FROM Regular")
    suspend fun clearRegulars()

    @Query("SELECT * FROM RecipeNote")
    fun observeRecipeNotes(): Flow<List<RecipeNoteEntity>>

    @Query("SELECT * FROM RecipeNote")
    suspend fun recipeNotes(): List<RecipeNoteEntity>

    @Query("SELECT * FROM RecipeNote WHERE libraryKey = :key")
    suspend fun recipeNote(key: String): RecipeNoteEntity?

    @Upsert
    suspend fun putRecipeNote(n: RecipeNoteEntity)

    @Insert
    suspend fun insertRecipeNotes(rows: List<RecipeNoteEntity>)

    @Query("DELETE FROM RecipeNote")
    suspend fun clearRecipeNotes()

    @Query("SELECT DISTINCT photo FROM RecipeNote WHERE photo IS NOT NULL")
    suspend fun photosInUse(): List<String>

    /** Every library key something still names: a stored copy of the recipe (saved, forgotten but kept, or on the list), or a planned meal. */
    @Query("SELECT COALESCE(libraryKey, url, 'r:' || id) FROM Recipe UNION SELECT libraryKey FROM PlannedMeal")
    suspend fun namedLibraryKeys(): List<String>

    @Query("UPDATE RecipeNote SET orphanedAt = :at WHERE libraryKey = :key")
    suspend fun setNoteOrphanedAt(
        key: String,
        at: Long?,
    )

    @Query("DELETE FROM RecipeNote WHERE libraryKey = :key")
    suspend fun deleteRecipeNote(key: String)
}
