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

    @Query("SELECT * FROM Contribution")
    suspend fun contributions(): List<ContributionEntity>

    @Query("SELECT * FROM Tick")
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

    @Query("SELECT * FROM Recipe WHERE url = :url ORDER BY importedAt DESC")
    suspend fun recipesByUrl(url: String): List<RecipeEntity>

    @Query("DELETE FROM Recipe WHERE id = :id")
    suspend fun deleteRecipe(id: Long)

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

    @Query("UPDATE ImportJob SET state = :state WHERE id = :id")
    suspend fun setJobState(
        id: Long,
        state: String,
    )

    @Query("UPDATE ImportJob SET state = :state, resultJson = :resultJson, error = :error WHERE id = :id")
    suspend fun finishJob(
        id: Long,
        state: String,
        resultJson: String?,
        error: String?,
    )

    @Query("SELECT * FROM ImportJob WHERE id = :id")
    suspend fun job(id: Long): ImportJobEntity?

    @Query("SELECT * FROM ImportJob WHERE state = :state ORDER BY createdAt")
    fun observeJobs(state: String): Flow<List<ImportJobEntity>>

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
