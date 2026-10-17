package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.parse.RuleParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class CategoriserTest {
    private val c = Categoriser()

    @Test
    fun lookupOrder() {
        assertEquals(AisleCode.HOUSEHOLD to AisleSource.OVERRIDE, c.aisle("onion", overrides = mapOf("cat:onion" to AisleCode.HOUSEHOLD)))
        assertEquals(AisleCode.FROZEN to AisleSource.IDENTITY, c.aisle("frozen raspberries"))
        assertEquals(AisleCode.TINS_JARS to AisleSource.IDENTITY, c.aisle("tinned peaches"))
        assertEquals(AisleCode.NUTS_DRIED_FRUIT to AisleSource.IDENTITY, c.aisle("dried mango"))
        assertEquals(AisleCode.PRODUCE to AisleSource.CATALOGUE, c.aisle("courgette", claude = AisleCode.FROZEN))
        assertEquals(AisleCode.WORLD_FOODS to AisleSource.CLAUDE, c.aisle("galangal", claude = AisleCode.WORLD_FOODS))
        assertEquals(AisleCode.PRODUCE to AisleSource.HEAD_NOUN, c.aisle("heirloom cherry tomatoes"))
        assertEquals(AisleCode.MEAT_FISH to AisleSource.KEYWORD, c.aisle("venison loin"))
        assertEquals(AisleCode.OTHER to AisleSource.OTHER, c.aisle("unobtainium"))
    }

    @Test
    fun catalogueBeatsTheDriedAndCannedIdentityRules() {
        assertEquals(AisleCode.PASTA_RICE_GRAINS to AisleSource.CATALOGUE, c.aisle("dried pasta"))
        assertEquals(AisleCode.PASTA_RICE_GRAINS to AisleSource.CATALOGUE, c.aisle("dried beans"))
        assertEquals(AisleCode.BAKERY to AisleSource.CATALOGUE, c.aisle("dried breadcrumbs"))
        assertEquals(AisleCode.BAKING to AisleSource.CATALOGUE, c.aisle("dried yeast"))
        assertEquals(AisleCode.WORLD_FOODS to AisleSource.CATALOGUE, c.aisle("canned pumpkin"))
        assertEquals(AisleCode.WORLD_FOODS, c.aisle("dried porcini").first)
        assertEquals(AisleCode.WORLD_FOODS, c.aisle("dried mushrooms").first)
        // Not catalogued as written: dried X takes X's aisle unless X is fruit or a herb.
        assertEquals(AisleCode.PASTA_RICE_GRAINS, c.aisle("dried spaghetti").first)
        assertEquals(AisleCode.PASTA_RICE_GRAINS, c.aisle("dried red lentils").first)
        assertEquals(AisleCode.PASTA_RICE_GRAINS, c.aisle("dried chickpeas").first)
        assertEquals(AisleCode.WORLD_FOODS, c.aisle("dried egg noodles").first)
        assertEquals(AisleCode.NUTS_DRIED_FRUIT, c.aisle("dried apricots").first)
        assertEquals(AisleCode.SPICES_CONDIMENTS, c.aisle("dried rosemary").first)
    }

    @Test
    fun groundRedPepperIsCayenne() {
        assertEquals(AisleCode.SPICES_CONDIMENTS, c.aisle("ground red pepper").first)
        assertEquals(AisleCode.PRODUCE, c.aisle("red pepper").first)
    }

    @Test
    fun garlicClovesAreGarlicNotTheSpice() {
        assertEquals(AisleCode.PRODUCE to AisleSource.CATALOGUE, c.aisle("garlic cloves"))
    }

    /** Step 3 check: at least 85% of the golden names get an aisle other than Other. */
    @Test
    fun goldenNamesMostlyGetAnAisle() {
        val names =
            listOf("frozen.json", "synthetic-uk.json").flatMap { f ->
                Json.parseToJsonElement(File("../../testdata/ingredients/$f").readText()).jsonArray.map {
                    it.jsonObject.getValue("expected").jsonObject.getValue("name").jsonPrimitive.content
                }
            }
        val other = names.filter { c.aisle(it).first == AisleCode.OTHER }
        val rate = 1.0 - other.size.toDouble() / names.size
        println("aisle coverage ${"%.1f".format(rate * 100)}% of ${names.size}; Other: $other")
        assertTrue(rate >= 0.85, "only ${"%.1f".format(rate * 100)}% categorised; Other: $other")
        // And through the rule parser, as the app would see them.
        val p = RuleParser()
        val viaParser =
            Json.parseToJsonElement(File("../../testdata/ingredients/frozen.json").readText()).jsonArray.map {
                p.parse(it.jsonObject.getValue("expected").jsonObject.getValue("raw").jsonPrimitive.content).name
            }
        val r2 = 1.0 - viaParser.count { c.aisle(it).first == AisleCode.OTHER }.toDouble() / viaParser.size
        assertTrue(r2 >= 0.85, "via parser only ${"%.1f".format(r2 * 100)}%")
    }
}
