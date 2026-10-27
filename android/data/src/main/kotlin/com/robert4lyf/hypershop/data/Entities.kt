package com.robert4lyf.hypershop.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Schema v1 (PLAN §4). Enums are stored by name, never ordinal. Two rules Room
 * annotations cannot express are added in HyperShopDatabase.CALLBACK:
 *  - Item.mergeKey is unique among live items (a partial unique index), so a
 *    soft-deleted item does not block a new one with the same key;
 *  - RecipeUse: kind = 'MANUAL' OR recipeId IS NOT NULL (insert/update triggers).
 *
 * The user-data entities are @Serializable: a backup file (Backup.kt) carries
 * them as they are, so the format and the schema cannot drift apart.
 */

@Serializable
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
    /**
     * What "Added by hand" is called ("Household"), or null for that. Kept here
     * rather than on the use: a new hand-added use after "Finish shopping" keeps it.
     */
    val manualTitle: String? = null,
)

@Serializable
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
    /** X.8: set aside as not available on this trip ([ListItem.unavailableAt]); v8. */
    val unavailableAt: Long? = null,
)

@Serializable
@Entity(tableName = "ItemAlias")
data class ItemAliasEntity(
    @PrimaryKey val oldId: Long,
    val newId: Long,
)

@Serializable
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

@Serializable
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

@Serializable
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

@Serializable
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

@Serializable
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

@Serializable
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

@Serializable
@Entity(tableName = "Aisle")
data class AisleEntity(
    @PrimaryKey val code: String,
    val name: String,
    val hidden: Boolean,
    val defaultOrder: Int,
)

@Serializable
@Entity(tableName = "StoreProfile")
data class StoreProfileEntity(
    @PrimaryKey val id: Long,
    val name: String,
)

@Serializable
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

@Serializable
@Entity(tableName = "CategoryOverride")
data class CategoryOverrideEntity(
    @PrimaryKey val key: String,
    val aisleCode: String,
    val source: String,
)

@Serializable
@Entity(tableName = "Staple")
data class StapleEntity(
    @PrimaryKey val key: String,
    val source: String,
)

@Serializable
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

@Serializable
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

/** A count per library key (meals planned per saved recipe). */
data class KeyCount(
    val key: String,
    val n: Int,
)

/**
 * A meal planned for a week (docs/design/release-b.md): only the intent. Its
 * ingredients come from the saved recipe ([libraryKey], its latest copy) and its
 * shopping from the use it was put on the list as ([useId]), never copied here.
 * [useId] is null until "Add to the list", and again if that use is removed.
 */
@Serializable
@Entity(
    tableName = "PlannedMeal",
    foreignKeys = [
        ForeignKey(entity = RecipeUseEntity::class, parentColumns = ["id"], childColumns = ["useId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("weekStart"), Index("useId"), Index("libraryKey")],
)
data class PlannedMealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The week's Monday as an epoch day ([com.robert4lyf.hypershop.core.plan.Week]). */
    val weekStart: Long,
    val libraryKey: String,
    /** Null: the recipe's own servings. */
    val servings: Int?,
    val position: Int,
    val useId: Long?,
    val addedAt: Long,
)

/** A finished shopping trip: when "Finish shopping" was pressed. Its items are [TripItemEntity]. */
@Serializable
@Entity(tableName = "Trip")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val finishedAt: Long,
)

/**
 * One item bought on a trip, once however many recipes wanted it. [byHand]: some
 * of it was typed or added from Buy again, which is what makes it a household
 * purchase rather than a recipe's; [qty] is that hand-added amount (QtyBase JSON).
 */
@Serializable
@Entity(
    tableName = "TripItem",
    primaryKeys = ["tripId", "mergeKey"],
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mergeKey")],
)
data class TripItemEntity(
    val tripId: Long,
    val mergeKey: String,
    val displayName: String,
    val aisleCode: String,
    val byHand: Boolean,
    val qty: String?,
    val unitHint: String?,
)

/** A Buy again preference: [pinned] always offered, or not, hidden from suggestions. */
@Serializable
@Entity(tableName = "Regular")
data class RegularEntity(
    @PrimaryKey val mergeKey: String,
    val displayName: String,
    val pinned: Boolean,
)

/**
 * What you keep about a saved recipe, by its library entry ([LibraryRecipe.key]),
 * so it outlives Edit and Add again (each makes a new copy): a rating out of 5,
 * cooking notes, and a photo. [photo] is a file in the app's own storage
 * ([com.robert4lyf.hypershop] RecipePhotos), [photoUrl] the recipe page's own
 * photo it was taken from, so a restore onto another phone can fetch it again.
 */
@Serializable
@Entity(tableName = "RecipeNote")
data class RecipeNoteEntity(
    @PrimaryKey val libraryKey: String,
    val rating: Int? = null,
    val notes: String? = null,
    val photo: String? = null,
    val photoUrl: String? = null,
    val updatedAt: Long = 0,
    /**
     * When the start-up check first found nothing naming this entry any more (no stored copy of the recipe, no
     * planned meal): it is deleted, and its photo with it, [ListRepository.NOTE_KEEP_MS] after that. Null while
     * something names it. See [ListRepository.retireOrphanNotes].
     */
    val orphanedAt: Long? = null,
    /** "Cooked it" on a meal (missing 2 of the review of 2026-10-27): how often, and when last. What "your usual" counts. */
    @ColumnInfo(defaultValue = "0") val timesCooked: Int = 0,
    val lastCookedAt: Long? = null,
)

/**
 * X.9: where a group or an item sits within its aisle, as the shopper arranged it.
 * Keys are [com.robert4lyf.hypershop.core.merge.ShelfOrder.groupKey] and `itemKey`;
 * kept across trips, like the walking order.
 */
@Serializable
@Entity(tableName = "ShelfRank")
data class ShelfRankEntity(
    @PrimaryKey val key: String,
    val rank: Int,
)
