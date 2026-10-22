package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The grammar, rule by rule (docs/parser-spec.md). */
class RuleParserTest {
    private val p = RuleParser()

    @Test
    fun specExample() {
        val r = p.parse("2 ½ cups (300g) plain flour, sifted")
        assertEquals(Rational.of(5, 2), r.quantity)
        assertEquals(QtyUnit.CUP, r.unit)
        assertEquals(Rational.of(300), r.altQuantity)
        assertEquals(QtyUnit.G, r.altUnit)
        assertEquals("plain flour", r.name)
        assertEquals("sifted", r.note)
        assertFalse(r.check)
    }

    @Test
    fun aPastedListMarkerIsNotPartOfTheLine() {
        for (line in listOf("- 3 eggs", "• 3 eggs", "  * 3 eggs", "– 3 eggs")) {
            val r = p.parse(line)
            assertEquals(Rational.of(3), r.quantity, line)
            assertEquals("eggs", r.name, line)
        }
        val soy = p.parse("•  2  tbsp   soy sauce")
        assertEquals(Rational.of(2), soy.quantity)
        assertEquals(QtyUnit.TBSP, soy.unit)
        // A dash that is part of a word or a number is not a marker.
        assertEquals("half-fat crème fraîche", p.parse("200ml half-fat crème fraîche").name)
    }

    @Test
    fun r2CaseSensitiveSpoonsAndFluidOunces() {
        assertEquals(QtyUnit.TBSP, p.parse("1 T soy sauce").unit)
        assertEquals(QtyUnit.TSP, p.parse("1 t cumin").unit)
        assertEquals(QtyUnit.FL_OZ, p.parse("4 fl. oz. milk").unit)
        assertEquals(QtyUnit.FL_OZ, p.parse("4 fluid ounces milk").unit)
        assertEquals(QtyUnit.L, p.parse("1.5 litres stock").unit)
    }

    @Test
    fun r24OtherUnitsAndSizes() {
        val corn = p.parse("4 large ears corn")
        assertEquals(QtyUnit.OTHER, corn.unit)
        assertEquals("corn", corn.name)
        assertEquals("large", corn.note)
        assertTrue(corn.check)
        val garlic = p.parse("2 medium cloves garlic, smashed")
        assertEquals(QtyUnit.CLOVE, garlic.unit)
        assertEquals("garlic", garlic.name)
        val peel = p.parse("Strip of orange peel with pith")
        assertNull(peel.quantity)
        assertEquals("orange peel", peel.name)
    }

    @Test
    fun r3Quantities() {
        assertEquals(Rational.of(3, 2), p.parse("1-1/2 cups milk").quantity)
        val range = p.parse("2-3 spring onions")
        assertEquals(Rational.of(2), range.quantity)
        assertEquals(Rational.of(3), range.quantityMax)
        assertEquals(Rational.of(3), p.parse("2 to 3 eggs").quantityMax)
        assertEquals(Rational.ONE, p.parse("a pinch of salt").quantity)
        assertEquals(QtyUnit.PINCH, p.parse("Pinch of cayenne (optional)").unit)
        assertEquals(Rational.of(1, 2), p.parse("half a lemon").quantity)
        assertNull(p.parse("a few basil leaves").quantity)
    }

    @Test
    fun r34AlternativesAndContainers() {
        val tin = p.parse("400g tin chopped tomatoes")
        assertEquals(Rational.ONE, tin.quantity)
        assertEquals(QtyUnit.TIN, tin.unit)
        assertEquals(Rational.of(400), tin.altQuantity)
        assertEquals("chopped tomatoes", tin.name)
        val two = p.parse("2 x 400g tins chickpeas, drained")
        assertEquals(Rational.of(2), two.quantity)
        assertEquals(QtyUnit.TIN, two.unit)
        assertEquals(QtyUnit.G, two.altUnit)
        val can = p.parse("1 (14-ounce) can coconut milk")
        assertEquals(QtyUnit.TIN, can.unit)
        assertEquals(Rational.of(14), can.altQuantity)
        assertEquals(QtyUnit.OZ, can.altUnit)
        val slash = p.parse("2 1/3 cups/225 grams chopped walnuts")
        assertEquals(Rational.of(225), slash.altQuantity)
        assertEquals("walnuts", slash.name)
    }

    @Test
    fun r4NameAndNote() {
        val onion = p.parse("1 red onion, finely chopped")
        assertEquals("red onion", onion.name)
        assertEquals("finely chopped", onion.note)
        assertEquals("smoked paprika", p.parse("½ tsp smoked paprika").name)
        assertEquals("parsley", p.parse("1 heaping tablespoon minced fresh parsley").name)
        assertEquals("parsley", p.parse("10 small sprigs fresh parsley for garnish").name)
        val about = p.parse("1 onion (about 200 g)")
        assertEquals("about 200 g", about.note)
        assertTrue(p.parse("1 tsp sugar (optional)").optional)
    }

    @Test
    fun r4IdentityWordsBackedByTheCatalogue() {
        assertEquals("toasted sesame oil", p.parse("1 tbsp toasted sesame oil").name)
        assertEquals("ground cumin", p.parse("1 tsp ground cumin").name)
        assertEquals("whole milk", p.parse("1 cup whole milk").name)
    }

    @Test
    fun r7Check() {
        val or = p.parse("4 tablespoons safflower or peanut oil")
        assertEquals("safflower oil", or.name)
        assertTrue(or.check)
        assertTrue(p.parse("salt and pepper").check)
        val zest = p.parse("Finely grated zest of 2 limes")
        assertEquals("limes", zest.name)
        assertEquals(Rational.of(2), zest.quantity)
        assertTrue(zest.check)
    }

    @Test
    fun r8SectionHeaders() {
        val lines = p.parseLines(listOf("For the sauce:", "2 tbsp soy sauce", "TOPPING", "50g butter"))
        assertEquals(listOf("For the sauce", "TOPPING"), lines.map { it.section })
        assertEquals(listOf("soy sauce", "butter"), lines.map { it.name })
    }

    @Test
    fun noQuantityIsPresence() {
        val salt = p.parse("Salt, to taste")
        assertNull(salt.quantity)
        assertEquals("salt", salt.name)
        assertTrue(salt.noScale)
    }

    @Test
    fun r36PlusAmountGoesToTheNote() {
        val a = p.parse("1 tablespoon plus 1 teaspoon sugar")
        assertEquals("sugar", a.name)
        assertEquals(Rational.ONE, a.quantity)
        assertEquals(QtyUnit.TBSP, a.unit)
        assertEquals("plus 1 teaspoon", a.note)
        val b = p.parse("½ cup plus 2 tbsp sugar")
        assertEquals("sugar", b.name)
        assertEquals(Rational.of(1, 2), b.quantity)
        assertEquals(QtyUnit.CUP, b.unit)
        assertEquals("plus 2 tbsp", b.note)
        assertEquals("flour", p.parse("1 cup and 2 tablespoons flour").name)
    }

    @Test
    fun r6LeadingArticle() {
        val onion = p.parse("a large onion")
        assertEquals("onion", onion.name)
        assertEquals(Rational.ONE, onion.quantity)
        assertEquals("large", onion.note)
        val egg = p.parse("an egg")
        assertEquals("egg", egg.name)
        assertEquals(Rational.ONE, egg.quantity)
        val basil = p.parse("a few basil leaves")
        assertEquals("basil leaves", basil.name)
        assertNull(basil.quantity)
        assertEquals("a few", basil.note)
        assertTrue(basil.noScale)
        val sprigs = p.parse("a few sprigs of thyme")
        assertNull(sprigs.quantity)
        assertEquals("thyme", sprigs.name)
    }

    @Test
    fun r41LeadingQualifierBeforeTheAmount() {
        val stock = p.parse("about 2 cups stock")
        assertEquals(Rational.of(2), stock.quantity)
        assertEquals(QtyUnit.CUP, stock.unit)
        assertEquals("stock", stock.name)
        assertEquals("about", stock.note)
        assertEquals("2", stock.quantityText)
        assertEquals(Rational.of(500), p.parse("approx. 500g potatoes").quantity)
    }

    @Test
    fun r33Dozen() {
        val eggs = p.parse("1 dozen eggs")
        assertEquals(Rational.of(12), eggs.quantity)
        assertEquals("eggs", eggs.name)
        assertEquals(Rational.of(24), p.parse("2 dozen clams").quantity)
        assertEquals(Rational.of(6), p.parse("half a dozen eggs").quantity)
        val range = p.parse("2-3 dozen oysters")
        assertEquals(Rational.of(24), range.quantity)
        assertEquals(Rational.of(36), range.quantityMax)
    }

    @Test
    fun r34SizeBeforeAContainerWithoutParentheses() {
        val tins = p.parse("2 400g tins chopped tomatoes")
        assertEquals(Rational.of(2), tins.quantity)
        assertEquals(QtyUnit.TIN, tins.unit)
        assertEquals(Rational.of(400), tins.altQuantity)
        assertEquals(QtyUnit.G, tins.altUnit)
        assertEquals("chopped tomatoes", tins.name)
        val can = p.parse("one 14-ounce can tomatoes")
        assertEquals(Rational.ONE, can.quantity)
        assertEquals(QtyUnit.TIN, can.unit)
        assertEquals(Rational.of(14), can.altQuantity)
        assertEquals(QtyUnit.OZ, can.altUnit)
        assertEquals("tomatoes", can.name)
    }

    @Test
    fun r31MixedNumberWithUnicodeFraction() {
        val r = p.parse("1-½ cups flour")
        assertEquals(Rational.of(3, 2), r.quantity)
        assertNull(r.quantityMax)
        assertEquals(QtyUnit.CUP, r.unit)
        assertEquals("flour", r.name)
    }

    @Test
    fun r7LeftoverAmountInTheNameIsChecked() {
        assertTrue(p.parse("1 lb. 4 oz. beef").check)
        assertTrue(p.parse("1/0 cup sugar").check)
        assertFalse(p.parse("2 cups flour").check)
    }

    @Test
    fun unclosedParenthesisKeepsItsLastWord() {
        val r = p.parse("2 cups flour (sifted")
        assertEquals("flour", r.name)
        assertEquals("sifted", r.note)
    }

    @Test
    fun r35SizedCountWithoutAContainer() {
        val salmon = p.parse("4 x 150g salmon fillets")
        assertEquals(Rational.of(4), salmon.quantity)
        assertNull(salmon.unit)
        assertEquals(Rational.of(150), salmon.altQuantity)
        assertEquals(QtyUnit.G, salmon.altUnit)
        assertEquals("salmon fillets", salmon.name)
        assertFalse(salmon.check)
        val chicken = p.parse("2 x 200g skinless chicken breasts")
        assertEquals(Rational.of(2), chicken.quantity)
        assertNull(chicken.unit)
        assertEquals(Rational.of(200), chicken.altQuantity)
        assertEquals("skinless chicken breasts", chicken.name)
        assertFalse(chicken.check)
    }

    @Test
    fun adjectivesBeforeACommaStayWithTheName() {
        val chicken = p.parse("1 lb boneless, skinless chicken breasts")
        assertEquals("boneless skinless chicken breasts", chicken.name)
        assertEquals(Rational.ONE, chicken.quantity)
        assertEquals(QtyUnit.LB, chicken.unit)
        assertEquals("cat:chicken_breast", Normaliser().mergeKey(chicken.name))
        val tomatoes = p.parse("4 large, ripe tomatoes, chopped")
        assertEquals("tomatoes", tomatoes.name)
        assertEquals("large ripe, chopped", tomatoes.note)
        // A catalogue name before the comma still ends the name.
        assertEquals("salt", p.parse("Salt, to taste").name)
    }

    @Test
    fun compoundImperialAltAfterASlash() {
        val mince = p.parse("500g/1lb 2oz beef mince")
        assertEquals(Rational.of(500), mince.quantity)
        assertEquals(QtyUnit.G, mince.unit)
        assertEquals(Rational.ONE, mince.altQuantity)
        assertEquals(QtyUnit.LB, mince.altUnit)
        assertEquals("beef mince", mince.name)
        assertEquals("1lb 2oz", mince.note)
        val big = p.parse("1.25kg/2lb 12oz beef mince")
        assertEquals(QtyUnit.KG, big.unit)
        assertEquals("beef mince", big.name)
        assertFalse(big.check)
    }

    @Test
    fun juiceOfAFractionOrHalf() {
        for (line in listOf("Juice of ½ lemon", "juice of 1/2 lemon", "Juice of half a lemon")) {
            val r = p.parse(line)
            assertEquals("lemon", r.name, line)
            assertEquals(Rational.of(1, 2), r.quantity, line)
            assertEquals("juice", r.note, line)
        }
    }

    @Test
    fun hostileLinesDoNotThrowAndStayFast() {
        val lines = listOf("", "(((((", ")))", "1/", "½-½-½", "x x x 2 x", "of of of", ",,,,", "(".repeat(3000), "1 ".repeat(3000))
        for (l in lines) p.parse(l)
        val t0 = System.nanoTime()
        p.parse("2 cups " + "very ".repeat(2000) + "flour")
        assertTrue((System.nanoTime() - t0) / 1e6 < 200)
    }

    @Test
    fun aBareMultiplierIsNotPartOfTheName() {
        val thighs = p.parse("4 x chicken thighs")
        assertEquals(Rational.of(4), thighs.quantity)
        assertEquals("chicken thighs", thighs.name)
        assertFalse(thighs.check)
        val onions = p.parse("2 x large onions")
        assertEquals(Rational.of(2), onions.quantity)
        assertEquals("onions", onions.name)
        // A multiplier with no size after it: the count stays, the line is flagged (R7).
        val eggs = p.parse("2 x 3 eggs")
        assertEquals(Rational.of(2), eggs.quantity)
        assertTrue(eggs.name.endsWith("eggs"))
        assertTrue(eggs.check)
    }

    @Test
    fun aDescendingRangeIsFlagged() {
        val r = p.parse("3-2 eggs")
        assertEquals(Rational.of(3), r.quantity)
        assertNull(r.quantityMax)
        assertTrue(r.check)
    }

    @Test
    fun hostileNumbersNeverOverflowOrCrash() {
        // Thousands groups on top of the nine-digit cap would overflow Long.
        val big = p.parse("999999999,999,999.9999 g flour")
        assertTrue(big.quantity == null || !big.quantity!!.isNegative)
        // A 320-digit "amount" in the note parses to Infinity; shopBase must not throw on it.
        val note = p.parse("1 cup flour (" + "9".repeat(320) + "g)")
        assertEquals(QtyUnit.CUP, note.shopUnit)
        assertFalse(note.shopBase(com.robert4lyf.hypershop.core.model.UnitLocale.METRIC).isPresence)
        // Twenty digits: not a metric alternative either, so the line is still shopped by the cup.
        assertEquals(QtyUnit.CUP, p.parse("1 cup flour (" + "9".repeat(20) + "g)").shopUnit)
    }

    @Test
    fun aFlaggedLineSaysWhy() {
        assertEquals("\"A or B\": one of two", p.parse("2 tbsp olive oil or butter").checkReason)
        assertEquals("\"X and Y\": may be two items", p.parse("salt and pepper").checkReason)
        assertEquals("a range that goes down", p.parse("3-2 eggs").checkReason)
        assertNull(p.parse("2 onions").checkReason)
    }
}

/** Release C (docs/design/release-c.md, X1–X12): confidently wrong readings found on the independent set. */
class RuleParserReleaseCTest {
    private val p = RuleParser()
    private val norm = Normaliser()

    /** The same ingredient as far as the catalogue is concerned ("fresh ginger" is ginger). */
    private fun assertSame(
        want: String,
        got: String,
    ) = assertEquals(norm.compareKey(want), norm.compareKey(got), "$got is not $want")

    @Test
    fun anAmountWrittenAfterTheNameIsRead() {
        val eggs = p.parse("Eggs 2")
        assertEquals("eggs", eggs.name)
        assertEquals(Rational.of(2), eggs.quantity)
        assertNull(eggs.unit)
        assertFalse(Uncertainty.uncertain(eggs))
        val mince = p.parse("mince 500g")
        assertEquals("mince", mince.name)
        assertEquals(Rational.of(500), mince.quantity)
        assertEquals(QtyUnit.G, mince.unit)
        val bananas = p.parse("bananas x6")
        assertEquals("bananas", bananas.name)
        assertEquals(Rational.of(6), bananas.quantity)
        val dashed = p.parse("Eggs - 6, large")
        assertEquals("eggs", dashed.name)
        assertEquals(Rational.of(6), dashed.quantity)
        val cups = p.parse("Tomato purée 1 ¼ cup")
        assertEquals("tomato purée", cups.name)
        assertEquals(Rational.of(5, 4), cups.quantity)
        assertEquals(QtyUnit.CUP, cups.unit)
        val beef = p.parse("Beef (minced, coarsely ground and mixed) 10.6 oz")
        assertEquals("beef", beef.name)
        assertEquals(QtyUnit.OZ, beef.unit)
        assertEquals("minced, coarsely ground and mixed", beef.note)
        val spinach = p.parse("Spinach 8.8 oz")
        assertEquals(Rational.fromDouble(8.8), spinach.quantity)
        // Still flagged: a measure the shop does not sell by.
        val punnets = p.parse("strawbs 2 punnets")
        assertEquals(Rational.of(2), punnets.quantity)
        assertEquals(QtyUnit.OTHER, punnets.unit)
        assertTrue(punnets.check)
        // A number that is not an amount at the end is left alone.
        assertEquals("type 00 flour", p.parse("Type 00 flour 1.3 cups").name)
        assertNull(p.parse("salt and pepper").quantity)
    }

    @Test
    fun aLengthIsASizeNotACount() {
        val ginger = p.parse("5cm piece of ginger, grated")
        assertSame("ginger", ginger.name)
        assertEquals(Rational.ONE, ginger.quantity)
        assertEquals(QtyUnit.PIECE, ginger.unit)
        assertEquals("5cm, grated", ginger.note)
        val stick = p.parse("2cm cinnamon stick")
        assertEquals("cinnamon stick", stick.name)
        assertEquals(Rational.ONE, stick.quantity)
        val sized = p.parse("1 2cm piece ginger")
        assertEquals(Rational.ONE, sized.quantity)
        assertSame("ginger", sized.name)
        val inch = p.parse("2 inch piece fresh ginger")
        assertSame("ginger", inch.name)
        assertEquals(QtyUnit.PIECE, inch.unit)
    }

    @Test
    fun anArticleAfterTheAmountIsNotTheName() {
        val bunch = p.parse("½ a bunch of fresh coriander")
        assertSame("coriander", bunch.name)
        assertEquals(Rational.of(1, 2), bunch.quantity)
        assertEquals(QtyUnit.BUNCH, bunch.unit)
        val onion = p.parse("½ an onion")
        assertEquals("onion", onion.name)
        assertEquals(Rational.of(1, 2), onion.quantity)
    }

    @Test
    fun aShapeWordAfterASizeIsANote() {
        val ball = p.parse("125g ball mozzarella roughly torn")
        assertEquals("mozzarella", ball.name)
        assertEquals(Rational.of(125), ball.quantity)
        assertEquals(QtyUnit.G, ball.unit)
        assertTrue(ball.note!!.contains("ball"))
        assertFalse(ball.check)
        val tub = p.parse("250g tub mascarpone")
        assertEquals("mascarpone", tub.name)
        assertEquals(Rational.of(250), tub.quantity)
        // Without a size the shape is a unit the shop does not sell by: flagged, as "1 bag spinach" is.
        assertTrue(p.parse("1 block halloumi").check)
    }

    @Test
    fun sizeWordsBeforeAUnitAndPrepVerbsWithoutACommaLeaveTheName() {
        val garlic = p.parse("2 fat cloves garlic, peeled and grated into a paste")
        assertEquals("garlic", garlic.name)
        assertEquals(QtyUnit.CLOVE, garlic.unit)
        val fenugreek = p.parse("1 rounded tsp dried fenugreek leaves, crushed to a powder (optional)")
        assertEquals("dried fenugreek leaves", fenugreek.name)
        assertEquals(QtyUnit.TSP, fenugreek.unit)
        assertTrue(fenugreek.optional)
        val chicken = p.parse("8 boneless, skinless chicken breasts cut into 2.5cm cubes")
        assertEquals("boneless skinless chicken breasts", chicken.name)
        assertEquals(Rational.of(8), chicken.quantity)
        val whites = p.parse("2 large egg whites")
        assertEquals("egg whites", whites.name)
        val sugar = p.parse("3/4 cup packed light brown sugar")
        assertEquals("light brown sugar", sugar.name)
        val ginger = p.parse("1/2 teaspoon grated fresh ginger root")
        assertEquals("ginger root", ginger.name)
        // A catalogue phrase that ends the name keeps its qualifier.
        assertEquals("fresh coriander", p.parse("1 bunch fresh coriander, leaves picked").name)
        assertEquals("toasted sesame oil", p.parse("1 tsp toasted sesame oil").name)
    }

    @Test
    fun decilitresAreMillilitres() {
        val water = p.parse("8 dl water")
        assertEquals("water", water.name)
        assertEquals(Rational.of(800), water.quantity)
        assertEquals(QtyUnit.ML, water.unit)
        assertFalse(water.check)
    }

    @Test
    fun eachAndASpacedDashAreNotPartOfTheName() {
        val salt = p.parse("1 tsp each salt, cumin, ground coriander, paprika (sweet, normal or smokey)")
        assertEquals("salt", salt.name)
        assertEquals(QtyUnit.TSP, salt.unit)
        val cheddar = p.parse("cheddar - big block")
        assertEquals("cheddar", cheddar.name)
        assertEquals("big block", cheddar.note)
        // A hyphen inside a word or a range is untouched.
        assertEquals("half-fat crème fraîche", p.parse("200ml half-fat crème fraîche").name)
        assertEquals(Rational.of(2), p.parse("1-2 tbsp oil").quantityMax)
    }

    @Test
    fun aUnitAfterAParentheticalThatIsNoAmountIsStillRead() {
        val noodles = p.parse("1 (1o-oz.) package lasagna noodles")
        assertEquals("lasagna noodles", noodles.name)
        assertEquals(QtyUnit.PACKET, noodles.unit)
        assertEquals(Rational.ONE, noodles.quantity)
        val ginger = p.parse("1 (2-inch) piece ginger")
        assertEquals("ginger", ginger.name)
        assertEquals(QtyUnit.PIECE, ginger.unit)
    }

    @Test
    fun aMetricAmountInsideAPhraseIsNotTheAmount() {
        val butter = p.parse("3 tbsp butter (from a 250g block)")
        assertEquals(QtyUnit.TBSP, butter.shopUnit)
        val milk = p.parse("2 cups milk (500ml)")
        assertEquals(QtyUnit.ML, milk.shopUnit)
        val flour = p.parse("2 tbsp flour (plus 50g for dusting)")
        assertEquals(QtyUnit.TBSP, flour.shopUnit)
    }

    @Test
    fun anAlternativeWithASharedHeadKeepsTheHead() {
        val stock = p.parse("2 cups chicken or vegetable stock")
        assertEquals("chicken stock", stock.name)
        assertEquals("or vegetable stock", stock.note)
        assertTrue(stock.check)
        val mince = p.parse("500g beef or lamb mince")
        assertEquals("beef mince", mince.name)
        // "safflower or peanut oil" worked before and still does.
        assertEquals("safflower oil", p.parse("4 tablespoons safflower or peanut oil").name)
    }

    @Test
    fun aCountedPepperIsTheVegetable() {
        assertEquals("bell pepper", p.parse("1 pepper, sliced").name)
        assertEquals("bell pepper", p.parse("3 x peppers").name)
        assertEquals("salt and pepper", p.parse("salt and pepper").name)
    }

    @Test
    fun superscriptFractionsAreFractions() {
        val flour = p.parse("2 ¹⁄₂ cups flour")
        assertEquals(Rational.of(5, 2), flour.quantity)
        assertEquals(QtyUnit.CUP, flour.unit)
        assertEquals("flour", flour.name)
        assertFalse(flour.check)
    }

    @Test
    fun aMeasureWithNoAmountIsPresenceNotACountOrAFlag() {
        val oil = p.parse("a drizzle of olive oil")
        assertEquals("olive oil", oil.name)
        assertNull(oil.quantity)
        assertEquals("a drizzle", oil.note)
        assertFalse(oil.check)
        val butter = p.parse("a knob of butter")
        assertEquals("butter", butter.name)
        assertNull(butter.quantity)
        assertFalse(butter.check)
    }

    @Test
    fun severalItemsOnOneLineAreFlagged() {
        val toppings = p.parse("Toppings of choice: shredded cheddar cheese, avocado, shredded lettuce")
        assertTrue(toppings.check)
        assertEquals("several items on one line", toppings.checkReason)
        // A plain colon ("Note: optional") with one item is not.
        assertFalse(p.parse("2 tbsp olive oil (note: any oil)").check)
    }
}
