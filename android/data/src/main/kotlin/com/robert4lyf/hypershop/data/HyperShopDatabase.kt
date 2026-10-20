package com.robert4lyf.hypershop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.model.AisleCode

@Database(
    version = 4,
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

        /** The live-key unique index: a partial index Room's annotations can't express. */
        private const val LIVE_KEY_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS index_Item_mergeKey_live ON Item(mergeKey) WHERE deletedAt IS NULL"

        /**
         * Every migration starts by dropping the live-key index. It isn't in Room's
         * schema, and Room checks the tables after a migration, so an install that
         * has been opened at the old version (onOpen made the index) would fail the
         * check on Item with it still there. [CALLBACK]'s onOpen makes it again
         * after the check (KNOWN_ISSUES #9). The RecipeUse triggers aren't checked.
         */
        private abstract class LiveKeyMigration(
            from: Int,
            to: Int,
        ) : Migration(from, to) {
            final override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_Item_mergeKey_live")
                migrateTables(db)
            }

            abstract fun migrateTables(db: SupportSQLiteDatabase)
        }

        /**
         * v2: the recipe library (Recipe.lastAddedAt, Recipe.libraryKey). Recipes already
         * on the list join it, dated by their latest use.
         */
        val MIGRATION_1_2: Migration =
            object : LiveKeyMigration(1, 2) {
                override fun migrateTables(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE Recipe ADD COLUMN lastAddedAt INTEGER")
                    db.execSQL("ALTER TABLE Recipe ADD COLUMN libraryKey TEXT")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_Recipe_libraryKey ON Recipe(libraryKey)")
                    db.execSQL("UPDATE Recipe SET libraryKey = COALESCE(url, 'r:' || id)")
                    db.execSQL("UPDATE Recipe SET lastAddedAt = (SELECT MAX(addedAt) FROM RecipeUse WHERE RecipeUse.recipeId = Recipe.id)")
                }
            }

        /** v3: a cookbook recipe's book and page. */
        val MIGRATION_2_3: Migration =
            object : LiveKeyMigration(2, 3) {
                override fun migrateTables(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE Recipe ADD COLUMN book TEXT")
                    db.execSQL("ALTER TABLE Recipe ADD COLUMN page TEXT")
                }
            }

        /** v4: a name for "Added by hand" (Meta.manualTitle). */
        val MIGRATION_3_4: Migration =
            object : LiveKeyMigration(3, 4) {
                override fun migrateTables(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE Meta ADD COLUMN manualTitle TEXT")
                }
            }

        /** Seeds aisles, the store and its aisle order, staples and Meta (PLAN §3.4, §4). */
        val CALLBACK =
            object : Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Back after a migration dropped it; a no-op otherwise.
                    db.execSQL(LIVE_KEY_INDEX)
                }

                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(LIVE_KEY_INDEX)
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
            Room.databaseBuilder(
                context,
                HyperShopDatabase::class.java,
                NAME,
            ).addCallback(CALLBACK).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

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
