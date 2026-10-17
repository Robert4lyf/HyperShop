package com.robert4lyf.hypershop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.model.AisleCode

@Database(
    version = 1,
    exportSchema = true,
    entities = [
        MetaEntity::class, ItemEntity::class, ItemAliasEntity::class, RecipeEntity::class, RecipeLineEntity::class,
        RecipeUseEntity::class, TickEntity::class, ContributionEntity::class, LineDecisionEntity::class,
        ImportJobEntity::class, AisleEntity::class, StoreProfileEntity::class, AisleOrderEntity::class,
        CategoryOverrideEntity::class, StapleEntity::class, ClaudeUsageEntity::class, DeletedItemEntity::class,
        WatchInstallEntity::class, WatchOpResultEntity::class,
    ],
)
abstract class HyperShopDatabase : RoomDatabase() {
    abstract fun dao(): ListDao

    companion object {
        const val NAME = "hypershop.db"

        /** Seeds aisles, the store and its aisle order, staples and Meta (PLAN §3.4, §4). */
        val CALLBACK =
            object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_Item_mergeKey_live ON Item(mergeKey) WHERE deletedAt IS NULL")
                    for (op in listOf("INSERT", "UPDATE")) {
                        db.execSQL(
                            "CREATE TRIGGER IF NOT EXISTS RecipeUse_kind_check_${op.lowercase()} BEFORE $op ON RecipeUse " +
                                "WHEN NEW.kind <> 'MANUAL' AND NEW.recipeId IS NULL " +
                                "BEGIN SELECT RAISE(ABORT, 'RECIPE use needs a recipeId'); END",
                        )
                    }
                    for ((i, a) in AisleCode.entries.withIndex()) {
                        db.execSQL(
                            "INSERT INTO Aisle(code, name, hidden, defaultOrder) VALUES (?, ?, 0, ?)",
                            arrayOf(a.name, a.displayName, i),
                        )
                    }
                    db.execSQL("INSERT INTO StoreProfile(id, name) VALUES (1, 'My store')")
                    for ((i, a) in AisleCode.entries.withIndex()) {
                        db.execSQL("INSERT INTO AisleOrder(storeId, aisleCode, position) VALUES (1, ?, ?)", arrayOf(a.name, i))
                    }
                    for (e in CatalogLoader.default.entries.filter { it.staple }) {
                        db.execSQL("INSERT INTO Staple(`key`, source) VALUES (?, 'SEED')", arrayOf(e.mergeKey))
                    }
                    db.execSQL(
                        "INSERT INTO Meta(id, listEpoch, listRev, catalogVersion, epochMarker, nextId) VALUES (1, ?, 1, ?, '', 1)",
                        arrayOf(EpochMarker.newEpoch(), CatalogLoader.default.version),
                    )
                }
            }

        fun build(context: Context): HyperShopDatabase =
            Room.databaseBuilder(context, HyperShopDatabase::class.java, NAME).addCallback(CALLBACK).build()

        /**
         * Tests: [queryExecutor] can run Room's background work (invalidation
         * refresh) on the calling thread, so none outlives the test.
         */
        fun inMemory(
            context: Context,
            queryExecutor: java.util.concurrent.Executor? = null,
        ): HyperShopDatabase =
            Room.inMemoryDatabaseBuilder(context, HyperShopDatabase::class.java)
                .addCallback(CALLBACK)
                .allowMainThreadQueries()
                .apply { queryExecutor?.let { setQueryExecutor(it) } }
                .build()
    }
}
