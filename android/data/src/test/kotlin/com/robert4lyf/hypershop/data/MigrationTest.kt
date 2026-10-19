package com.robert4lyf.hypershop.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v1 → v2 (the recipe library) and v2 → v3 (book and page), each from an opened
 * install, which has the live-key index that isn't in Room's schema (KNOWN_ISSUES #9).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HyperShopDatabase::class.java)

    @Test
    fun recipesOnTheListJoinTheLibraryAndTheLiveKeyIndexSurvives() {
        helper.createDatabase(name, 1).apply {
            // As a real v1 install has it: made in onCreate, outside Room's schema.
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_Item_mergeKey_live ON Item(mergeKey) WHERE deletedAt IS NULL")
            execSQL("INSERT INTO Recipe VALUES (1,'https://example.com/chilli','Chilli',4,'METRIC',100,'WEB','RULES',NULL,0)")
            execSQL("INSERT INTO Recipe VALUES (2,NULL,'Soup',2,'METRIC',200,'WEB','RULES',NULL,0)")
            execSQL("INSERT INTO Recipe VALUES (3,NULL,'Never added',2,'METRIC',300,'WEB','RULES',NULL,0)")
            execSQL("INSERT INTO RecipeUse VALUES (10,1,'RECIPE',4,500,NULL)")
            execSQL("INSERT INTO RecipeUse VALUES (11,1,'RECIPE',4,700,NULL)")
            execSQL("INSERT INTO RecipeUse VALUES (12,2,'RECIPE',2,600,800)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 2, true, HyperShopDatabase.MIGRATION_1_2)
        val c = db.query("SELECT id, lastAddedAt, libraryKey FROM Recipe ORDER BY id")
        val rows = ArrayList<Triple<Long, Long?, String?>>()
        while (c.moveToNext()) rows += Triple(c.getLong(0), if (c.isNull(1)) null else c.getLong(1), c.getString(2))
        c.close()
        assertEquals(Triple(1L, 700L, "https://example.com/chilli"), rows[0])
        assertEquals(Triple(2L, 600L, "r:2"), rows[1])
        assertNull(rows[2].second)
        db.close()
        // Opened by the app: onOpen makes the live-key index again.
        val room =
            Room.databaseBuilder(ApplicationProvider.getApplicationContext(), HyperShopDatabase::class.java, name)
                .addCallback(HyperShopDatabase.CALLBACK)
                .addMigrations(HyperShopDatabase.MIGRATION_1_2, HyperShopDatabase.MIGRATION_2_3)
                .allowMainThreadQueries()
                .build()
        val idx =
            room.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_Item_mergeKey_live'")
        assertTrue(idx.moveToFirst())
        idx.close()
        room.close()
    }

    @Test
    fun anOpenedV2InstallUpgradesWithTheLiveKeyIndexInPlace() {
        helper.createDatabase(name, 2).apply {
            // As a real v2 install has it once opened: onOpen made the index, outside Room's schema.
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_Item_mergeKey_live ON Item(mergeKey) WHERE deletedAt IS NULL")
            execSQL("INSERT INTO Meta VALUES (1, 7, 12, 'old', 'marker', 99)")
            execSQL("INSERT INTO Item VALUES (1,'cat:onion','onion','onion','onion','PRODUCE',0,1,NULL,0)")
            close()
        }
        // Opened by the app: Room runs 2 → 3, then checks Item against its schema.
        val room =
            Room.databaseBuilder(ApplicationProvider.getApplicationContext(), HyperShopDatabase::class.java, name)
                .addCallback(HyperShopDatabase.CALLBACK)
                .addMigrations(HyperShopDatabase.MIGRATION_1_2, HyperShopDatabase.MIGRATION_2_3)
                .allowMainThreadQueries()
                .build()
        val meta = runBlocking { room.dao().meta() }
        assertEquals(MetaEntity(1, 7, 12, "old", "marker", 99), meta)
        assertEquals(1, runBlocking { room.dao().items() }.size)
        val idx =
            room.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_Item_mergeKey_live'")
        assertTrue(idx.moveToFirst())
        idx.close()
        room.close()
    }

    @Test
    fun aCookbookRecipeGetsABookAndPage() {
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO Recipe VALUES (1,NULL,'Soup',2,'METRIC',200,'WEB','CLAUDE',NULL,0,300,'r:1')")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 3, true, HyperShopDatabase.MIGRATION_2_3)
        db.execSQL("UPDATE Recipe SET book = 'Simple', page = '142' WHERE id = 1")
        val c = db.query("SELECT title, libraryKey, book, page FROM Recipe")
        assertTrue(c.moveToFirst())
        assertEquals(listOf("Soup", "r:1", "Simple", "142"), (0..3).map { c.getString(it) })
        c.close()
        db.close()
    }
}
