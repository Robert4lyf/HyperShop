package com.robert4lyf.hypershop.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema v1 (PLAN §4). Enums are stored by name, never ordinal. Two rules Room
 * annotations cannot express are added in HyperShopDatabase.CALLBACK:
 *  - Item.mergeKey is unique among live items (a partial unique index), so a
 *    soft-deleted item does not block a new one with the same key;
 *  - RecipeUse: kind = 'MANUAL' OR recipeId IS NOT NULL (insert/update triggers).
 */

@Entity(tableName = "Meta")
data class MetaEntity(
    @PrimaryKey val id: Int = 1,
    val listEpoch: Long,
    val listRev: Long,
    val catalogVersion: String,
    /** Must match the marker file in noBackupFilesDir; a mismatch means a restore. */
    val epochMarker: String,
    /** The next id for any list row (ShoppingList.nextId); ids are never reused. */
    val nextId: Long,
)

@Entity(tableName = "Item", indices = [Index("mergeKey"), Index("aisleCode")])
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val mergeKey: String,
    val catalogId: String?,
    val normName: String,
    val displayName: String,
    val aisleCode: String,
    val position: Int,
    val rev: Long,
    val deletedAt: Long?,
    val updatedAt: Long,
)

@Entity(tableName = "ItemAlias")
data class ItemAliasEntity(
    @PrimaryKey val oldId: Long,
    val newId: Long,
)

@Entity(tableName = "Recipe", indices = [Index("url"), Index("libraryKey")])
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String?,
    val title: String?,
    val servingsOriginal: Int?,
    val unitLocale: String,
    val importedAt: Long,
    val source: String,
    val parsedBy: String,
    val model: String?,
    val claudePending: Boolean,
    /**
     * When it last went on the list; null until then (a review not yet confirmed).
     * Recipes with one form the recipe library, oldest first.
     */
    val lastAddedAt: Long? = null,
    /**
     * Which library entry this copy belongs to: the page's URL, or "r:<id>" of the
     * first copy. Adding again, an edit or a re-add from the library store a new
     * copy with the same key; the library shows the latest.
     */
    val libraryKey: String? = null,
    /** A cookbook recipe's book and page, as typed (photo or typed-in recipes). */
    val book: String? = null,
    val page: String? = null,
)

/** "Ottolenghi Simple · p. 142", "p. 142" or "Ottolenghi Simple"; null when neither was given. */
fun bookRef(
    book: String?,
    page: String?,
): String? {
    val b = book?.trim()?.takeIf { it.isNotEmpty() }
    val p = page?.trim()?.takeIf { it.isNotEmpty() }?.let { "p. $it" }
    return listOfNotNull(b, p).joinToString(" · ").ifEmpty { null }
}

@Entity(
    tableName = "RecipeLine",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recipeId")],
)
data class RecipeLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    val section: String?,
    val raw: String,
    /** The ParsedIngredient as JSON. Lines never change; a re-import is a new Recipe. */
    val parsedJson: String,
)

@Entity(
    tableName = "RecipeUse",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("recipeId")],
)
data class RecipeUseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val recipeId: Long?,
    val kind: String,
    val servings: Int,
    val addedAt: Long,
    val archivedAt: Long?,
)

@Entity(
    tableName = "Tick",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("itemId")],
)
data class TickEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val itemId: Long,
    val at: Long,
    val source: String,
    val installId: Int?,
    val seq: Int?,
)

@Entity(
    tableName = "Contribution",
    foreignKeys = [
        ForeignKey(entity = RecipeUseEntity::class, parentColumns = ["id"], childColumns = ["useId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(
            entity = RecipeLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeLineId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(entity = ItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TickEntity::class, parentColumns = ["id"], childColumns = ["coveredBy"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("useId"), Index("recipeLineId"), Index("itemId"), Index("coveredBy")],
)
data class ContributionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val useId: Long,
    val recipeLineId: Long?,
    val itemId: Long,
    /** QtyBase as JSON; "{}" is presence. */
    val qtyBase: String,
    val noScale: Boolean,
    val createdListRev: Long,
    val coveredBy: Long?,
    val unitHint: String?,
)

@Entity(
    tableName = "LineDecision",
    primaryKeys = ["useId", "recipeLineId"],
    foreignKeys = [
        ForeignKey(entity = RecipeUseEntity::class, parentColumns = ["id"], childColumns = ["useId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(
            entity = RecipeLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeLineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recipeLineId")],
)
data class LineDecisionEntity(
    val useId: Long,
    val recipeLineId: Long,
    val status: String,
)

@Entity(tableName = "ImportJob")
data class ImportJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val state: String,
    val inputRefs: String,
    val resultJson: String?,
    val error: String?,
    val createdAt: Long,
)

@Entity(tableName = "Aisle")
data class AisleEntity(
    @PrimaryKey val code: String,
    val name: String,
    val hidden: Boolean,
    val defaultOrder: Int,
)

@Entity(tableName = "StoreProfile")
data class StoreProfileEntity(
    @PrimaryKey val id: Long,
    val name: String,
)

@Entity(
    tableName = "AisleOrder",
    primaryKeys = ["storeId", "aisleCode"],
    foreignKeys = [
        ForeignKey(
            entity = StoreProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["storeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AisleOrderEntity(
    val storeId: Long,
    val aisleCode: String,
    val position: Int,
)

@Entity(tableName = "CategoryOverride")
data class CategoryOverrideEntity(
    @PrimaryKey val key: String,
    val aisleCode: String,
    val source: String,
)

@Entity(tableName = "Staple")
data class StapleEntity(
    @PrimaryKey val key: String,
    val source: String,
)

@Entity(tableName = "ClaudeUsage", primaryKeys = ["date", "model"])
data class ClaudeUsageEntity(
    /** ISO date, UTC. */
    val date: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheWrite: Long,
    val cacheRead: Long,
    val calls: Int,
)

@Entity(tableName = "DeletedItem")
data class DeletedItemEntity(
    @PrimaryKey val id: Long,
    val deletedAt: Long,
)

@Entity(tableName = "WatchInstall")
data class WatchInstallEntity(
    @PrimaryKey val installId: Int,
    val lastSeq: Int,
    val clockOffsetMs: Long,
)

@Entity(tableName = "WatchOpResult", primaryKeys = ["installId", "seq"])
data class WatchOpResultEntity(
    val installId: Int,
    val seq: Int,
    val result: String,
)

/** A recipe's number of lines (the library's "12 ingredients"). */
data class LineCount(
    val recipeId: Long,
    val n: Int,
)
