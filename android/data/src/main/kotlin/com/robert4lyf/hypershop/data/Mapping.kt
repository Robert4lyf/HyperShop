package com.robert4lyf.hypershop.data

import com.robert4lyf.hypershop.core.merge.Contribution
import com.robert4lyf.hypershop.core.merge.ListItem
import com.robert4lyf.hypershop.core.merge.RecipeUse
import com.robert4lyf.hypershop.core.merge.Tick
import com.robert4lyf.hypershop.core.merge.TickSource
import com.robert4lyf.hypershop.core.merge.UseKind
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.QtyUnit
import kotlinx.serialization.json.Json

internal object Mapping {
    private val json = Json { ignoreUnknownKeys = true }

    fun item(e: ItemEntity) =
        ListItem(
            e.id, e.mergeKey, e.catalogId, e.normName, e.displayName,
            AisleCode.valueOf(
                e.aisleCode,
            ),
            e.position, e.rev, e.deletedAt, e.unavailableAt,
        )

    fun item(
        i: ListItem,
        now: Long,
    ) = ItemEntity(
        i.id,
        i.mergeKey,
        i.catalogId,
        i.normName,
        i.displayName,
        i.aisle.name,
        i.position,
        i.rev,
        i.deletedAt,
        now,
        i.unavailableAt,
    )

    fun use(e: RecipeUseEntity) = RecipeUse(e.id, e.recipeId, UseKind.valueOf(e.kind), e.servings, e.addedAt, e.archivedAt)

    fun use(u: RecipeUse) = RecipeUseEntity(u.id, u.recipeId, u.kind.name, u.servings, u.addedAt, u.archivedAt)

    fun tick(e: TickEntity) = Tick(e.id, e.itemId, e.at, TickSource.valueOf(e.source), e.installId, e.seq)

    fun tick(t: Tick) = TickEntity(t.id, t.itemId, t.at, t.source.name, t.installId, t.seq)

    fun contribution(e: ContributionEntity) =
        Contribution(
            e.id, e.useId, e.recipeLineId, e.itemId, json.decodeFromString(QtyBase.serializer(), e.qtyBase), e.noScale, e.createdListRev,
            e.coveredBy, e.unitHint?.let { QtyUnit.fromSerial(it) },
        )

    fun contribution(c: Contribution) =
        ContributionEntity(
            c.id, c.useId, c.recipeLineId, c.itemId, json.encodeToString(QtyBase.serializer(), c.qty), c.noScale, c.createdListRev,
            c.coveredBy, c.unitHint?.serial,
        )
}
