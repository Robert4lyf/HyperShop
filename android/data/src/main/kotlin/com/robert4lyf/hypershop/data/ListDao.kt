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

    @Query("UPDATE Recipe SET lastAddedAt = :at, libraryKey = COALESCE(libraryKey, url, 'r:' || id) WHERE id = :id")
    suspend fun markAdded(
        id: Long,
        at: Long,
    )

    @Query("UPDATE Recipe SET lastAddedAt = NULL WHERE libraryKey = :key")
    suspend fun forgetKey(key: String)

    @Query("SELECT COUNT(*) FROM RecipeUse WHERE recipeId = :id")
    suspend fun usesOfRecipe(id: Long): Int

    @Query("SELECT * FROM RecipeLine WHERE recipeId = :recipeId ORDER BY id")
    suspend fun lines(recipeId: Long): List<RecipeLineEntity>

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

    @Query("UPDATE ImportJob SET state = :state, resultJson = :resultJson, error = :error WHERE id = :id AND state != 'DONE'")
    suspend fun finishJob(
        id: Long,
        state: String,
        resultJson: String?,
        error: String?,
    )

    @Query("SELECT * FROM ImportJob WHERE id = :id")
    suspend fun job(id: Long): ImportJobEntity?

    /** Re-emitted whenever the row changes: the review screen waits on it rather than polling. */
    @Query("SELECT * FROM ImportJob WHERE id = :id")
    fun observeJob(id: Long): Flow<ImportJobEntity?>

    @Query("SELECT * FROM ImportJob WHERE state = :state ORDER BY createdAt")
    fun observeJobs(state: String): Flow<List<ImportJobEntity>>

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

    @Query("DELETE FROM WatchOpResult")
    suspend fun clearOpResults()

    /** Bumped on every list write; the UI observes it. */
    @Query("SELECT listRev FROM Meta WHERE id = 1")
    fun observeListRev(): Flow<Long?>
}
