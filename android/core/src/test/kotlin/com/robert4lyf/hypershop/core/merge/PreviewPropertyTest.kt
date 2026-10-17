package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.UnitLocale
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreviewPropertyTest {
    private val recipes = Arb.list(Arb.element(LINES), 1..8)

    @Test
    fun addThenRemoveAUseReturnsTheSameList() {
        runBlocking {
            checkAll(recipes, recipes) { a, b ->
                val (base, _) = add(ShoppingList(), a)
                val (withB, useB) = add(base, b)
                assertEquals(base.shape(), withB.removeUse(useB).shape())
            }
        }
    }

    @Test
    fun applyingThePreviewEqualsMergingDirectly() {
        runBlocking {
            checkAll(recipes, recipes) { a, b ->
                val (base, _) = add(ShoppingList(), a)
                val inc = recipe(b)
                val viaPreview = (merger.apply(base, merger.preview(base, inc, emptyMap()), 1000) as ApplyResult.Applied).list
                // Oracle: one manual-style insert per line, straight onto the list.
                var direct =
                    base.copy(
                        uses = base.uses + (base.nextId to RecipeUse(base.nextId, 1, UseKind.RECIPE, 4, 1000)),
                        nextId = base.nextId + 1,
                    )
                val useId = base.nextId
                for (l in inc.lines) {
                    val (d2, itemId) = direct.ensureItem(l.target)
                    val q = QtyBase.of(l.parsed.quantityMax ?: l.parsed.quantity, l.parsed.unit, UnitLocale.METRIC)
                    val noScale = l.parsed.noScale || q.isPresence
                    val c = Contribution(d2.nextId, useId, l.recipeLineId, itemId, q, noScale, base.listRev + 1, unitHint = l.parsed.unit)
                    direct = d2.copy(contributions = d2.contributions + c, nextId = d2.nextId + 1)
                }
                assertEquals(direct.semantic(), viaPreview.semantic())
            }
        }
    }
}
