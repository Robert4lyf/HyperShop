package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.catalog.CatalogEntry
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.model.Dimension
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational

/**
 * The offline rule parser (PLAN §3.2, docs/parser-spec.md). Small on purpose:
 * Claude is the main parser. Lines it cannot handle well ("X and Y", "A or B",
 * "juice of", R2.4 units) are marked `check` (R7) and highlighted in review.
 *
 * Shape of a line: `[quantity [range]] [sizes] [unit [(alt)] [container]] [of] name [, note]`.
 */
class RuleParser(
    private val catalog: Catalog = CatalogLoader.default,
) {
    /** Parses a recipe's lines, tracking section headers (R8). Headers produce no ingredient. */
    private val shelves = Shelf(catalog)

    fun parseLines(lines: List<String>): List<ParsedIngredient> {
        var section: String? = null
        val out = ArrayList<ParsedIngredient>()
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val header = sectionHeader(line)
            if (header != null) {
                section = header
                continue
            }
            out += parse(line).copy(section = section, sourceLine = i)
        }
        return out
    }

    /** R8: ends with ':' or is "For the …" or all caps, with no digits and ≤ 40 characters. */
    fun sectionHeader(line: String): String? = sectionHeaderOf(line)

    fun parse(raw: String): ParsedIngredient = Line(raw.take(Tokeniser.MAX_LINE)).parse()

    /**
     * "• 2 tbsp soy sauce", "- 3 eggs": a list marker pasted in front of the
     * amount hid it (held-out ho-37, hob-34). A dash only counts as a marker
     * when a space follows it, so "-1" or "half-fat" are never touched.
     */
    private fun withoutMarker(text: String): String {
        // Blanked, not cut: the tokens' offsets index the line as written (the notes, "(optional)" and a spaced
        // dash are all read from it), so a marker must not shift them (review C.21).
        val m = LIST_MARKER.find(text) ?: return text
        return " ".repeat(m.value.length) + text.substring(m.range.last + 1)
    }

    /** One parse; holds the cursor over the tokens. */
    private inner class Line(
        val text: String,
    ) {
        val toks: List<Token> = Tokeniser.tokenise(withoutMarker(text))
        var i = 0
        var check = false
        var checkReason: String? = null
        var optional = false
        val notes = ArrayList<String>()

        /** Where the name stops: the end, or where an amount written after it starts. */
        var limit = toks.size
        var trailingText: String? = null

        /**
         * An amount at the end of the line, after the name: "Eggs 2", "Eggs - 6", "mince 500g",
         * "bananas x6", "Tomato purée 1 ¼ cup", "Beef (minced) 10.6 oz". Null when the line's
         * tail is not an amount, or when nothing would be left for the name.
         */
        fun trailingAmount(): Trailing? {
            // "Eggs - 6, large": the amount may sit before a trailing note.
            trailingAmount(toks.size)?.let { return it }
            val comma = toks.indexOfLast { it.kind == Kind.COMMA }
            if (comma <= 0) return null
            return trailingAmount(comma)?.also { notes += span(comma + 1, toks.size) }
        }

        fun trailingAmount(end: Int): Trailing? {
            // The last number, with everything after it a unit (or nothing).
            var j = end - 1
            while (j >= 0 && (toks[j].kind == Kind.PERIOD || toks[j].kind == Kind.OTHER)) j--
            var unitAtEnd: QtyUnit? = null
            var unitLen = 0
            if (j >= 0 && toks[j].kind == Kind.WORD) {
                val u = unitAt(j) ?: unitAt(j - 1)?.takeIf { it.second == 2 }
                if (u != null) {
                    unitAtEnd = u.first
                    unitLen = u.second
                    j -= unitLen
                } else if (word(j) in Lexicon.otherUnits) {
                    unitAtEnd = QtyUnit.OTHER
                    unitLen = 1
                    j--
                } else {
                    return null
                }
            }
            if (j < 0 || toks[j].kind != Kind.NUM) return null
            // "1 ¼": a whole number before a fraction belongs to it.
            var from = j
            var value = toks[j].value!!
            if (j >= 1 && toks[j - 1].kind == Kind.NUM && value < Rational.ONE && toks[j - 1].value!!.isWhole) {
                from = j - 1
                value += toks[j - 1].value!!
            }
            // "x6": a multiplier, "Eggs - 6": a dash; both belong to the amount.
            if (from >= 1 && (word(from - 1) == "x" || toks[from - 1].kind == Kind.DASH)) from--
            // Nothing before it that could be a name, or the number is the first token (a leading amount read already).
            if (from == 0 || (0 until from).none { toks[it].kind == Kind.WORD }) return null
            if (unitAtEnd == QtyUnit.OTHER) flag("an unknown unit")
            // "Serves 4", "Preheat oven to 200": pasted recipe text, not an amount of something (R7, review C.27).
            if (unitAtEnd == null && !knownName(0, from)) flag("a number after a name the catalogue does not know")
            return Trailing(from, value, null, unitAtEnd, null, null)
        }

        /** Whether the words in [from, to), less their qualifiers and prep, name something the catalogue has. */
        fun knownName(
            from: Int,
            to: Int,
        ): Boolean {
            val ws = (from until to).mapNotNull { word(it) }.filter { it !in Lexicon.qualifiers && it !in Lexicon.prep }
            return ws.isNotEmpty() && entryOf(ws.joinToString(" ")) != null
        }

        /** R7: the line needs a look; the first reason is the one shown. */
        fun flag(reason: String) {
            check = true
            if (checkReason == null) checkReason = reason
        }

        fun parse(): ParsedIngredient {
            // "about 2 cups stock": a leading qualifier goes to the note (R4.1).
            val qtyStart = skipLeadingQualifier()
            // R6: "a few basil leaves" is not a quantity.
            if ((word(i) == "a" || word(i) == "an") && word(i + 1) in VAGUE) {
                notes += "a ${word(i + 1)}"
                i += 2
            }
            var qty = readNumber()
            var qtyMax: Rational? = null
            if (qty != null) qtyMax = readRangeEnd(qty)
            if (qty?.isZero == true) flag("a zero amount")
            // R3.3: "1 dozen eggs", "2-3 dozen".
            if (qty != null && word(i) == "dozen") {
                i++
                qty *= DOZEN
                qtyMax = qtyMax?.times(DOZEN)
            }
            if (qty == null && qtyStart > 0) {
                i = 0 // no amount after all: "about" stays where it was
                notes.clear()
            }
            if (qty != null) {
                // "½ a bunch", "½ an onion": the article after the amount is not the name.
                if (word(i) == "a" || word(i) == "an") i++
                // A length is a size, not a count: "5cm piece of ginger" is one piece (R4.3).
                if (word(i) in Lexicon.lengths) {
                    notes += span(qtyStart, i + 1)
                    i++
                    qty = Rational.ONE
                    qtyMax = null
                } else if (kind(i) == Kind.NUM && word(i + 1) in Lexicon.lengths) {
                    notes += span(i, i + 2) // "1 2cm piece", "a 3cm piece"
                    i += 2
                } else if (kind(i) == Kind.DASH && word(i + 1) in Lexicon.lengths) {
                    // "1-inch piece ginger": the dash joins the size to its unit; one piece (review C.22).
                    notes += span(qtyStart, i + 2)
                    i += 2
                    qty = Rational.ONE
                    qtyMax = null
                }
            }
            val qtyEnd = i
            // "1 recipe Italian Dressing", "1 batch roasted tomatoes": made from another recipe, not bought as such.
            if (qty != null && word(i) in SUB_RECIPE) {
                notes += toks[i].text.lowercase()
                i++
                if (word(i) == "of") i++
                flag("made from another recipe")
            }
            var unit: QtyUnit? = null
            var altQty: Rational? = null
            var altUnit: QtyUnit? = null
            var sizedCount = false
            var summed = false

            if (qty != null) {
                skipSizes()
                // R3.4 without parentheses: 2 400g tins, one 14-ounce can
                val bare = if (kind(i) == Kind.NUM) readBareSize() else null
                if (bare != null) {
                    altQty = bare.first
                    altUnit = bare.second
                    unit = bare.third
                } else if (word(i) == "x" && kind(i + 1) == Kind.NUM) {
                    // R3.5: 2 x 400g tins; without a container (4 x 150g salmon fillets)
                    // the amount is a count of the named thing, each of the size.
                    val save = i
                    i++
                    val size = readNumber()
                    val sizeUnit = readUnit()
                    if (size != null && sizeUnit != null) {
                        altQty = size
                        altUnit = sizeUnit
                        unit = readUnit()
                        if (unit == null) sizedCount = true
                    } else {
                        // "2 x 3 eggs": a multiplier with no size. Keep the count, drop the "x", and flag it (R7).
                        i = save + 1
                        flag("a count times a count")
                    }
                } else if (word(i) == "x") {
                    // "4 x chicken thighs": a bare multiplier, not part of the name.
                    i++
                    unit = readUnit()
                } else {
                    if (word(i) in Lexicon.decilitres) {
                        // "2 dl water": a decilitre is 100 ml.
                        i++
                        unit = QtyUnit.ML
                        qty = qty!! * DECILITRE
                        qtyMax = qtyMax?.times(DECILITRE)
                    } else {
                        unit = readUnit()
                    }
                    // R3.4: 2 cups (300g) … / 1 (14-ounce) tin
                    if (kind(i) == Kind.LPAREN) {
                        val close = matching(i)
                        val alt = if (close > i) amountIn(i + 1, close) else null
                        if (alt != null) {
                            altQty = alt.first
                            altUnit = alt.second
                            i = close + 1
                            if (unit == null) unit = readUnit()
                        } else if (unit == null && close > i && unitAt(close + 1) != null) {
                            // "1 (1o-oz.) package noodles": a parenthetical that is no amount, then the unit.
                            notes += span(i + 1, close)
                            i = close + 1
                            unit = readUnit()
                        }
                    }
                    // R3.4: 400g tin chopped tomatoes
                    if (unit != null && unit.dimension != Dimension.COUNT) {
                        val save = i
                        val container = readUnit()
                        if (container != null && container in Lexicon.containers) {
                            altQty = qty
                            altUnit = unit
                            qty = Rational.ONE
                            unit = container
                        } else if (container == QtyUnit.OTHER) {
                            // "125g ball mozzarella", "250g tub mascarpone": the amount stands, the shape is a note.
                            notes += toks[i - 1].text.lowercase()
                        } else {
                            i = save
                        }
                    }
                    // 2 cups/225 grams
                    if (unit != null && kind(i) == Kind.SLASH && kind(i + 1) == Kind.NUM) {
                        val save = i
                        i++
                        val a = readNumber()
                        val au = readUnit()
                        if (a != null && au != null) {
                            altQty = a
                            altUnit = au
                            // 500g/1lb 2oz: the rest of a compound alt goes to the note with it.
                            val more = i
                            if (kind(i) == Kind.NUM && readNumber() != null && readUnit()?.dimension == au.dimension) {
                                notes += span(save + 1, i)
                            } else {
                                i = more
                            }
                        } else {
                            i = save
                        }
                    }
                }
                // R3.6: "1 tbsp plus 1 tbsp sugar" is 2 tbsp; "1 tablespoon plus 1 teaspoon sugar" keeps the first
                // amount and sends the extra to the note.
                if (unit != null && (word(i) == "plus" || word(i) == "and") && kind(i + 1) == Kind.NUM) {
                    val save = i
                    i++
                    val more = readNumber()
                    val moreUnit = more?.let { readUnit() }
                    if (more == null || moreUnit == null) {
                        i = save
                    } else if (moreUnit == unit && word(save) == "plus") {
                        altQty = altQty?.let { if (qty!!.isZero) it else it * (qty!! + more) / qty!! }
                        qty = qty!! + more
                        qtyMax = qtyMax?.plus(more)
                        summed = true
                    } else {
                        notes += span(save, i)
                    }
                }
            } else {
                // No number. R3.3: "Pinch of cayenne" is 1 pinch; R2.4: any other
                // leading unit word ("Strip of …", "Few sprigs …") goes to the note.
                val start = i
                skipSizes()
                val u = unitAt(i)
                // "Pinch of cayenne", and "small bunch thyme" with a size before the unit word.
                if (u != null && u.first in R33 && start == 0) {
                    i += u.second
                    qty = Rational.ONE
                    unit = u.first
                } else if ((word(i) == "a" || word(i) == "an") && (word(i + 1) in Lexicon.otherUnits) && word(i + 2) == "of") {
                    // R6: "a drizzle of olive oil", "a knob of butter": no amount, the measure is a note.
                    notes += "${word(i)} ${word(i + 1)}"
                    i += 2
                } else if (u != null || word(i) in Lexicon.otherUnits) {
                    notes += toks[i].text.lowercase()
                    i += u?.second ?: 1
                }
                // "Eggs 2", "mince 500g", "bananas x6", "Spinach 8.8 oz": the amount written after the name.
                val trailing = trailingAmount()
                if (trailing != null) {
                    qty = trailing.qty
                    qtyMax = trailing.qtyMax
                    unit = trailing.unit
                    altQty = trailing.altQty
                    altUnit = trailing.altUnit
                    limit = trailing.from
                    trailingText = span(trailing.from, toks.size).trim().trimStart('-', '–', 'x', 'X', ' ')
                }
            }
            if (word(i) == "of") i++ // R4.5

            val (named, trailingUnit, impliedQty) = nameAndNote(unit == null && qty != null && !sizedCount)
            if (trailingUnit != null) unit = trailingUnit
            val name = shelf(named, unit)
            val more = if (qty != null) plusAfterName(name, unit) else null
            if (more != null) {
                // "2 tbsp (30g) butter, plus 1 tbsp": the grams written for the first amount grow with it.
                altQty = altQty?.let { if (qty!!.isZero) it else it * (qty!! + more) / qty!! }
                qty = qty!! + more
                qtyMax = qtyMax?.plus(more)
                summed = true
            }
            if (qty == null && impliedQty != null) qty = impliedQty
            if (unit == QtyUnit.OTHER) flag("an unknown unit")

            if ("optional" in lower || "if desired" in lower || "if you like" in lower) optional = true
            // Several things on one line ("Toppings: cheese, avocado, lettuce"): one of them would be wrong.
            if (toks.any { it.kind == Kind.COLON } && toks.count { it.kind == Kind.COMMA } >= 2) flag("several items on one line")
            // A sum is shown as the sum, not as the first amount written.
            val quantityText = if (summed) null else trailingText ?: if (qtyEnd > qtyStart) span(qtyStart, qtyEnd).trim() else null
            return ParsedIngredient(
                raw = text,
                name = name,
                quantity = qty,
                quantityMax = qtyMax,
                quantityText = quantityText,
                unit = unit,
                altQuantity = altQty,
                altUnit = altUnit,
                note = notes.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ").ifEmpty { null },
                optional = optional,
                check = check || name.isEmpty(),
                checkReason = checkReason ?: if (name.isEmpty()) "no ingredient name" else null,
            )
        }

        val lower = text.lowercase()

        /** Words said about the name itself: qualifiers moved off it and its own brackets ([shelf]). */
        val nameWords = HashSet<String>()

        // ---- quantity ---------------------------------------------------------------

        /** Skips "about"/"approx."/"around" before the amount into the note; returns where the amount starts. */
        fun skipLeadingQualifier(): Int {
            if (word(i) !in LEADING_QUALIFIERS) return i
            notes += toks[i].text.lowercase()
            i++
            if (kind(i) == Kind.PERIOD) i++
            return i
        }

        /** "400g tins", "14-ounce can": a size and its unit, then a container. Null (cursor kept) otherwise. */
        fun readBareSize(): Triple<Rational, QtyUnit, QtyUnit>? {
            val save = i
            val size = readNumber()
            if (kind(i) == Kind.DASH) i++
            val sizeUnit = readUnit()
            val container = if (sizeUnit != null && sizeUnit.dimension != Dimension.COUNT) readUnit() else null
            if (size != null && sizeUnit != null && container != null && container in Lexicon.containers) {
                return Triple(size, sizeUnit, container)
            }
            i = save
            return null
        }

        fun readNumber(): Rational? {
            val t = toks.getOrNull(i) ?: return null
            if (t.kind == Kind.NUM) {
                i++
                var v = t.value!!
                val next = toks.getOrNull(i)
                if (next?.kind == Kind.NUM && v.isWhole && next.value!! < Rational.ONE) {
                    v += next.value // 1 1/2, 2 ½
                    i++
                } else if (next?.kind == Kind.DASH && kind(i + 1) == Kind.NUM && v.isWhole &&
                    toks[i + 1].value!! < Rational.ONE && isFraction(toks[i + 1])
                ) {
                    v += toks[i + 1].value!! // 1-1/2, 1-½
                    i += 2
                }
                return v
            }
            val w = word(i) ?: return null
            val n = Lexicon.numberWords[w] ?: return null
            // "a"/"an" count before a unit or a name ("a pinch of salt", "an egg"), not "a few",
            // and not "a drizzle of": a measure the shop does not sell by is a note, not a count (R6).
            if ((w == "a" || w == "an") && (kind(i + 1) !in ARTICLE_BEFORE || word(i + 1) in VAGUE)) return null
            if ((w == "a" || w == "an") && word(i + 1) in Lexicon.otherUnits && word(i + 2) == "of") return null
            i++
            if (w == "half" && (word(i) == "a" || word(i) == "an")) i++
            return n
        }

        /** A slash or unicode vulgar fraction (not a decimal like 0.5). */
        fun isFraction(t: Token): Boolean = t.text.contains('/') || t.text.contains('⁄') || t.text.none { it.isDigit() }

        /** R3.2: 1-2, 1 – 2, 1 to 2, 1 or 2. */
        fun readRangeEnd(first: Rational): Rational? {
            val sep = toks.getOrNull(i) ?: return null
            val isSep = sep.kind == Kind.DASH || word(i) == "to" || word(i) == "or"
            if (!isSep || kind(i + 1) != Kind.NUM) return null
            val save = i
            i++
            val second = readNumber()
            if (second != null && second > first) return second
            // "3-2 eggs": a separator and a number that make no range; the name would otherwise start "-2".
            i = save
            flag("a range that goes down")
            return null
        }

        // ---- units -----------------------------------------------------------------

        /** Size and qualifier words between the amount and the unit go to the note. */
        fun skipSizes() {
            while (true) {
                val w = word(i) ?: return
                val next = word(i + 1)
                val beforeUnit = unitAt(i + 1) != null || next in Lexicon.otherUnits || next in Lexicon.qualifiers
                if (w in Lexicon.qualifiers && beforeUnit) {
                    notes += toks[i].text.lowercase()
                    i++
                } else {
                    return
                }
            }
        }

        fun readUnit(): QtyUnit? {
            val u = unitAt(i)
            if (u != null) {
                i += u.second
                if (kind(i) == Kind.PERIOD) i++
                return u.first
            }
            // R2.4: other units, only when a name follows.
            if (word(i) in Lexicon.otherUnits && kind(i + 1) == Kind.WORD) {
                i++
                return QtyUnit.OTHER
            }
            return null
        }

        fun unitAt(at: Int): Pair<QtyUnit, Int>? = this@RuleParser.unitAt(toks, at)

        fun amountIn(
            from: Int,
            to: Int,
        ): Pair<Rational, QtyUnit>? {
            var j = from
            while (j < to && word(j) in APPROX_WORDS) j++
            if (j >= to || kind(j) != Kind.NUM) return null
            val v = toks[j].value!!
            j++
            if (kind(j) == Kind.DASH) j++
            val u = this@RuleParser.unitAt(toks, j) ?: return null
            return v to u.first
        }

        // ---- name and note ---------------------------------------------------------

        /**
         * The rest of the line: name up to the first top-level comma, then note.
         * Returns the name and, for "2 garlic cloves", the count unit that trailed it.
         */
        fun nameAndNote(
            mayTrailUnit: Boolean,
            impliedCount: Boolean = mayTrailUnit,
        ): Triple<String, QtyUnit?, Rational?> {
            val head = ArrayList<Token>()
            var depth = 0
            var k = i
            var cut = limit
            while (k < limit) {
                val t = toks[k]
                when (t.kind) {
                    Kind.DASH ->
                        if (depth == 0 && spacedDash(k)) {
                            // "cheddar - big block": a dash with spaces round it separates like a comma.
                            cut = k
                            break
                        }
                    Kind.LPAREN -> {
                        val close = matching(k).takeIf { it > k } ?: limit // unclosed: to the end
                        val inner = span(k + 1, close).trim()
                        if (inner.lowercase() == "optional") {
                            optional = true
                        } else if (inner.isNotEmpty()) {
                            notes += inner
                            // "Beef (minced, …)": what the name's own brackets say about it, not an alternative.
                            if (!inner.lowercase().startsWith("or ")) nameWords += inner.lowercase().split(NOT_LETTERS)
                        }
                        k = close + 1
                        continue
                    }
                    Kind.RPAREN -> depth = maxOf(0, depth - 1)
                    Kind.COMMA, Kind.SEMI ->
                        if (t.kind == Kind.COMMA && adjectivesOnly(head) && kind(k + 1) == Kind.WORD) {
                            // "boneless, skinless chicken breasts": the comma sits between adjectives.
                            k++
                            continue
                        } else if (depth == 0) {
                            cut = k
                            break
                        }
                    else -> Unit
                }
                head += t
                k++
            }
            if (cut < limit) notes += span(cut + 1, limit)
            pickSlashSide(head)

            // Words of the name: prep words and qualifiers to the note (R4.1) unless
            // they start a catalogue name (R4.2); a postnominal phrase ends the name.
            // A footnote mark ("brown rice*") is not part of the name.
            val words =
                head.filter {
                    it.kind == Kind.WORD || it.kind == Kind.DASH || it.kind == Kind.NUM ||
                        it.kind == Kind.OTHER && !it.text.all { c -> c in FOOTNOTE_MARKS }
                }
            val kept = ArrayList<Token>()
            val moved = ArrayList<String>()
            var idx = 0
            while (idx < words.size) {
                val t = words[idx]
                val w = t.text.lowercase()
                if (kept.isNotEmpty() && w in Lexicon.prep && !startsCatalogName(words, idx)) {
                    // "soft pitted black olives": prep words *before* the noun, after an adjective the catalogue
                    // does not know. The name goes on past them when what follows them is a catalogue item and what
                    // came before is not; "onion finely chopped" still ends at "finely" (V.1).
                    var after = idx
                    while (after < words.size && words[after].text.lowercase() in Lexicon.prep) after++
                    var end = after
                    while (end < words.size && words[end].text.lowercase() !in Lexicon.nameEnders &&
                        words[end].text.lowercase() !in Lexicon.prep
                    ) {
                        end++
                    }
                    val before = joinWords(kept)
                    val rest = words.subList(after, end).joinToString(" ") { it.text }
                    if (after < end && entryOf(before) == null) {
                        val whole = entryOf("$before $rest") != null
                        if (whole || entryOf(rest) != null) {
                            if (!whole) {
                                moved += kept.map { it.text.lowercase() }
                                kept.clear()
                            }
                            moved += words.subList(idx, after).map { it.text.lowercase() }
                            idx = after
                            continue
                        }
                    }
                }
                // "juice from 1 lemon": "from" is read with "of" by juiceOf below, not as the end of the name (review C.23).
                val juiceFrom = w == "from" && joinWords(kept).lowercase() in Regexless.PARTS
                if (kept.isNotEmpty() && (w in Lexicon.nameEnders || w in Lexicon.prep) && !startsCatalogName(words, idx) && !juiceFrom) {
                    notes += span(toks.indexOf(t), toks.indexOf(words.last()) + 1)
                    break
                }
                val removable =
                    w in Lexicon.prep || w in Lexicon.qualifiers ||
                        (w == "and" && kept.isEmpty() && moved.isNotEmpty())
                if (removable && !startsCatalogName(words, idx)) moved += t.text.lowercase() else kept += t
                idx++
            }
            if (moved.isNotEmpty()) notes.add(0, moved.joinToString(" "))
            nameWords += moved
            // A leftover amount ("1 lb. 4 oz. beef", "1/0 cup sugar") is not a name: R7.
            if (head.firstOrNull()?.kind in LEFTOVER && catalog.lookup(joinWords(kept)) == null) {
                flag("an amount left in the name")
            }

            var name = joinWords(kept).lowercase().trim()
            name = name.removePrefix("of ").trim()
            // "1 pepper", "3 x peppers": a counted pepper is the vegetable (black pepper is never counted).
            if ((name == "pepper" || name == "peppers") && impliedCount) name = "bell pepper"

            // R7: juice/zest of N lemons.
            var implied: Rational? = null
            Regexless.juiceOf(name)?.let { (part, fruit, n) ->
                flag("\"$part of\": the fruit is listed, not the $part")
                name = fruit
                implied = n
                notes.add(0, part)
            }
            // "long- or short-grain brown rice": one product either way, the first spelled out.
            HYPHEN_OR.find(name)?.let { m ->
                val (a, b, tail) = m.destructured
                name = "$a-$tail" + name.substring(m.range.last + 1)
                notes.add(0, "or $b-$tail")
            }
            // R4.4: "A or B" keeps A; "safflower or peanut oil" shares the head noun.
            val orAt = " $name ".indexOf(" or ")
            if (orAt >= 0) {
                flag("\"A or B\": one of two")
                val a = name.substring(0, maxOf(0, orAt - 1)).trim()
                val b = name.substring(minOf(name.length, orAt + 3)).trim()
                val bWords = b.split(' ')
                // A lone modifier before "or" borrows B's head noun.
                val sharesHead =
                    !a.contains(' ') && bWords.size >= 2 &&
                        (catalog.lookup(a) == null || catalog.lookup("$a ${bWords.last()}") != null)
                name = if (sharesHead) "$a ${bWords.drop(1).joinToString(" ")}" else a
                notes.add(0, "or $b")
            } else if (" and " in " $name " || " & " in " $name ") {
                flag("\"X and Y\": may be two items") // R7: Claude's to split
            }
            // A number inside what is left ("2 1 2 cups flour") means an amount was not read whole.
            if (kept.size > 1 && kept.any { it.kind == Kind.NUM } && catalog.lookup(joinWords(kept)) == null) {
                flag("an amount left in the name")
            }

            var trailing: QtyUnit? = null
            if (mayTrailUnit && name.contains(' ')) {
                val last = name.substringAfterLast(' ')
                val tu = Lexicon.units[last]
                if (tu != null && tu in Lexicon.trailingUnits) {
                    trailing = tu
                    name = name.substringBeforeLast(' ')
                }
            }
            return Triple(name.trim(',', '.', '-', ' ', '\''), trailing, implied)
        }

        /** The name so far is only known adjectives ("boneless", "large, ripe") and no catalogue name. */
        fun adjectivesOnly(head: List<Token>): Boolean =
            head.isNotEmpty() &&
                head.all { it.kind == Kind.WORD && it.text.lowercase() in COMMA_ADJECTIVES } &&
                catalog.lookup(joinWords(head)) == null

        fun startsCatalogName(
            words: List<Token>,
            idx: Int,
        ): Boolean {
            // No catalogue name is longer than MAX_KEY_WORDS words, so longer phrases need no lookup.
            for (end in minOf(words.size, idx + MAX_KEY_WORDS) downTo idx + 2) {
                val phrase = words.subList(idx, end).joinToString(" ") { it.text }
                // "fresh ginger" is a catalogue name, but "fresh ginger root" is not: the phrase must end
                // the name (or be followed by what would end it anyway) for "fresh" to stay.
                val next = words.getOrNull(end)?.text?.lowercase()
                if (catalog.lookup(phrase) != null && (next == null || next in Lexicon.nameEnders || next in Lexicon.prep)) return true
            }
            return false
        }

        /**
         * "flax seed/linseeds": two names the catalogue files as one thing, so the first is the name and
         * nothing is flagged. Anything else ("salt/pepper", "milk/dark chocolate", "scotch fillet / rib
         * eye") may be two products: the first is kept, the other goes to the note, and it is flagged.
         */
        fun pickSlashSide(head: MutableList<Token>) {
            val at = head.indexOfFirst { it.kind == Kind.SLASH }
            if (at <= 0 || at >= head.size - 1) return
            val before = head[at - 1]
            val after = head[at + 1]
            if (before.kind != Kind.WORD || after.kind != Kind.WORD) return
            if (before.text.lowercase() in AND_OR || after.text.lowercase() in AND_OR) return
            val left = head.subList(0, at).toList()
            val right = head.subList(at + 1, head.size).toList()
            val l = entryOf(joinWords(left))
            val r = entryOf(joinWords(right))
            if (l != null && l.id == r?.id) {
                // Two names for one thing ("flax seed/linseeds"): the first, nothing to decide.
                notes += joinWords(right)
            } else {
                // Two things, or a name the catalogue does not know: one of two, for the shopper to choose (R4.4).
                notes += "or ${joinWords(right)}"
                flag("\"A or B\": one of two")
            }
            head.clear()
            head += left
        }

        /** Q.3 tins and mince ([Shelf]), from the words said about the name itself. */
        fun shelf(
            name: String,
            unit: QtyUnit?,
        ): String = shelves.of(name, unit, nameWords)

        /**
         * R3.6 after the name: "2 tbsp butter plus 1 tbsp melted", "1 large egg, plus 1 egg yolk". The amount
         * after a "plus" outside brackets is added when it is in the same unit and of the same thing (nothing
         * more than prep words, or the catalogue's same item); "2 tbsp butter, plus 1 tbsp oil" may be a second
         * ingredient and is flagged. In another unit it stays in the note, as before.
         */
        fun plusAfterName(
            name: String,
            unit: QtyUnit?,
        ): Rational? {
            var depth = 0
            var p = -1
            for (k in i until toks.size) {
                when (toks[k].kind) {
                    Kind.LPAREN -> depth++
                    Kind.RPAREN -> depth--
                    else -> if (depth == 0 && word(k) == "plus" && kind(k + 1) == Kind.NUM) p = k
                }
                if (p >= 0) break
            }
            if (p < 0) return null
            val save = i
            i = p + 1
            val more = readNumber()
            val moreUnit = more?.let { readUnit() }
            if (word(i) == "of") i++
            // What the extra is said to be, up to what it is for: "plus 2 tbsp for dusting" is more of the same.
            var end = i
            while (end < toks.size && toks[end].kind == Kind.WORD && word(end) !in Lexicon.nameEnders) end++
            val rest = (i until end).mapNotNull { word(it) }.filter { it !in Lexicon.prep && it !in Lexicon.qualifiers }
            i = save
            if (more == null) return null
            val same = rest.isEmpty() || entryOf(rest.joinToString(" "))?.let { it == entryOf(name) } == true
            if (!same) {
                flag("\"plus\" an amount of something else")
                return null
            }
            return more.takeIf { moreUnit == unit }
        }

        /** A dash with whitespace on both sides. */
        fun spacedDash(at: Int): Boolean {
            val t = toks[at]
            return t.start > 0 && text[t.start - 1].isWhitespace() && t.end < text.length && text[t.end].isWhitespace()
        }

        // ---- helpers ---------------------------------------------------------------

        fun kind(at: Int): Kind? = toks.getOrNull(at)?.kind

        fun word(at: Int): String? = toks.getOrNull(at)?.takeIf { it.kind == Kind.WORD }?.text?.lowercase()

        fun matching(open: Int): Int {
            var depth = 0
            for (k in open until toks.size) {
                if (toks[k].kind == Kind.LPAREN) depth++
                if (toks[k].kind == Kind.RPAREN) {
                    depth--
                    if (depth == 0) return k
                }
            }
            return -1
        }

        fun span(
            from: Int,
            to: Int,
        ): String = if (from >= to || from >= toks.size) "" else text.substring(toks[from].start, toks[to - 1].end)
    }

    /** A unit starting at token [at], and how many tokens it spans. Handles "fl oz" and R2.1. */
    private fun unitAt(
        toks: List<Token>,
        at: Int,
    ): Pair<QtyUnit, Int>? {
        val t = toks.getOrNull(at) ?: return null
        if (t.kind != Kind.WORD) return null
        val raw = t.text
        val w = raw.lowercase()
        if (w == "fl" || w == "fluid") {
            var j = at + 1
            if (toks.getOrNull(j)?.kind == Kind.PERIOD) j++
            if (toks.getOrNull(j)?.text?.lowercase() in OUNCE_WORDS) return QtyUnit.FL_OZ to (j - at + 1)
        }
        // R2.1: T alone is a tablespoon, t alone a teaspoon.
        if (raw == "T" || raw == "Tb" || raw == "Tbs") return QtyUnit.TBSP to 1
        if (raw == "t") return QtyUnit.TSP to 1
        // "c" and "l" alone are units only straight after a number.
        if ((w == "c" || w == "l") && toks.getOrNull(at - 1)?.kind != Kind.NUM) return null
        val u = Lexicon.units[w] ?: return null
        return u to 1
    }

    private fun entryOf(phrase: String): CatalogEntry? = shelves.entryOf(phrase)

    private fun joinWords(toks: List<Token>): String {
        val sb = StringBuilder()
        for ((k, t) in toks.withIndex()) {
            val glue = k > 0 && (t.kind == Kind.DASH || toks[k - 1].kind == Kind.DASH)
            if (k > 0 && !glue) sb.append(' ')
            sb.append(t.text)
        }
        return sb.toString()
    }

    private companion object {
        /** A bullet, or a dash followed by a space, at the start of a pasted line. */
        val LIST_MARKER = Regex("^\\s*(?:[•·▪◦‣⁃*]|[-–—](?=\\s))\\s*")

        /** R3.3: leading unit words that mean "one of". */
        val R33 = setOf(QtyUnit.PINCH, QtyUnit.DASH, QtyUnit.BUNCH, QtyUnit.SPRIG, QtyUnit.HEAD)

        /** Qualifiers that may come before the amount ("about 2 cups"). */
        val LEADING_QUALIFIERS = setOf("about", "approx", "approximately", "around", "roughly")

        val DOZEN: Rational = Rational.of(12)
        val DECILITRE: Rational = Rational.of(100)

        /** "long- or short-grain": a word, a hyphen, "or", and a hyphenated word sharing its tail. */
        val HYPHEN_OR = Regex("^([a-z]+)-or ([a-z]+)-([a-z]+)")

        val AND_OR = setOf("and", "or")

        val SUB_RECIPE = setOf("recipe", "recipes", "batch", "batches", "quantity")

        val NOT_LETTERS = Regex("[^a-z]+")

        /** Footnote marks after a name ("brown rice*"); "%" and "&" are part of names. */
        const val FOOTNOTE_MARKS = "*†‡"

        /** What "a"/"an" must come before to mean one. */
        val ARTICLE_BEFORE = setOf(Kind.WORD, Kind.NUM)

        /** A name that starts with one of these is a leftover amount. */
        val LEFTOVER = setOf(Kind.NUM, Kind.SLASH)

        /** Words that may sit between a parenthesis and its amount ("(about 2 cups)"). */
        val APPROX_WORDS = setOf("about", "approx", "approximately", "one", "a", "an")

        val OUNCE_WORDS = setOf("oz", "ounce", "ounces")

        /** The longest catalogue name ("peas in the pod"), in words: longer phrases are never looked up. */
        const val MAX_KEY_WORDS = 4

        /** "a few", "a little": not an amount (R6). */
        val VAGUE = setOf("few", "little", "couple", "bit", "touch")

        /** Adjectives a recipe may list with commas before the noun ("large, ripe tomatoes"). */
        val COMMA_ADJECTIVES =
            setOf(
                "boneless", "skinless", "bone-in", "skin-on", "ripe", "fresh", "large", "small", "medium",
                "extra-large", "firm", "juicy", "lean", "organic", "free-range", "thick", "thin", "plump",
            )
    }
}

/** An amount written after the name ("Eggs 2"): where it starts, and what it says. */
private class Trailing(
    val from: Int,
    val qty: Rational,
    val qtyMax: Rational?,
    val unit: QtyUnit?,
    val altQty: Rational?,
    val altUnit: QtyUnit?,
)

/** Small hand-written matchers (no regex backtracking on untrusted lines). */
internal object Regexless {
    /** The parts of a fruit a recipe asks for by the fruit. */
    val PARTS = setOf("juice", "zest", "rind")

    /** "juice of 2 lemons" / "zest from a small orange" → (part, fruit, qty). */
    fun juiceOf(name: String): Triple<String, String, Rational?>? {
        val words = name.split(' ').filter { it.isNotEmpty() }
        val idx = words.indexOfFirst { it == "of" || it == "from" }
        if (idx < 1) return null
        val part = words[idx - 1]
        if (part !in PARTS) return null
        var rest = words.drop(idx + 1)
        // "2", "½", "1/2" (one number token) or a number word; "half a lemon" drops the article too.
        val n =
            rest.firstOrNull()?.let { w ->
                Tokeniser.tokenise(w).singleOrNull()?.takeIf { it.kind == Kind.NUM }?.value ?: Lexicon.numberWords[w]
            }
        if (n != null) rest = rest.drop(if (rest[0] == "half" && rest.getOrNull(1) in setOf("a", "an")) 2 else 1)
        rest = rest.filter { it !in Lexicon.qualifiers && it !in Lexicon.prep && !it.endsWith("-scrubbed") }
        if (rest.isEmpty()) return null
        return Triple(part, rest.joinToString(" "), n)
    }
}

/** [RuleParser.sectionHeader], for code that reads lines without parsing them (a header is not an ingredient). */
fun sectionHeaderOf(line: String): String? {
    val t = line.trim()
    if (t.isEmpty() || t.length > 40 || t.any { it.isDigit() }) return null
    if (t.endsWith(":")) return t.dropLast(1).trim()
    if (t.lowercase().startsWith("for the ")) return t
    val letters = t.filter { it.isLetter() }
    if (letters.length >= 3 && letters.all { it.isUpperCase() }) return t
    return null
}
