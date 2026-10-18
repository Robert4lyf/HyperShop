package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.catalog.Catalog
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
    fun parseLines(lines: List<String>): List<ParsedIngredient> {
        var section: String? = null
        val out = ArrayList<ParsedIngredient>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val header = sectionHeader(line)
            if (header != null) {
                section = header
                continue
            }
            out += parse(line).copy(section = section)
        }
        return out
    }

    /** R8: ends with ':' or is "For the …" or all caps, with no digits and ≤ 40 characters. */
    fun sectionHeader(line: String): String? {
        val t = line.trim()
        if (t.isEmpty() || t.length > 40 || t.any { it.isDigit() }) return null
        if (t.endsWith(":")) return t.dropLast(1).trim()
        if (t.lowercase().startsWith("for the ")) return t
        val letters = t.filter { it.isLetter() }
        if (letters.length >= 3 && letters.all { it.isUpperCase() }) return t
        return null
    }

    fun parse(raw: String): ParsedIngredient = Line(raw.take(Tokeniser.MAX_LINE)).parse()

    /** One parse; holds the cursor over the tokens. */
    private inner class Line(
        val text: String,
    ) {
        val toks: List<Token> = Tokeniser.tokenise(text)
        var i = 0
        var check = false
        var optional = false
        val notes = ArrayList<String>()

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
            val qtyEnd = i
            var unit: QtyUnit? = null
            var altQty: Rational? = null
            var altUnit: QtyUnit? = null
            var sizedCount = false

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
                        check = true
                    }
                } else if (word(i) == "x") {
                    // "4 x chicken thighs": a bare multiplier, not part of the name.
                    i++
                    unit = readUnit()
                } else {
                    unit = readUnit()
                    // R3.4: 2 cups (300g) … / 1 (14-ounce) tin
                    if (kind(i) == Kind.LPAREN) {
                        val close = matching(i)
                        val alt = if (close > i) amountIn(i + 1, close) else null
                        if (alt != null) {
                            altQty = alt.first
                            altUnit = alt.second
                            i = close + 1
                            if (unit == null) unit = readUnit()
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
                // R3.6: 1 tablespoon plus 1 teaspoon sugar → the extra amount to the note.
                if (unit != null && (word(i) == "plus" || word(i) == "and") && kind(i + 1) == Kind.NUM) {
                    val save = i
                    i++
                    if (readNumber() != null && readUnit() != null) notes += span(save, i) else i = save
                }
            } else {
                // No number. R3.3: "Pinch of cayenne" is 1 pinch; R2.4: any other
                // leading unit word ("Strip of …", "Few sprigs …") goes to the note.
                val start = i
                skipSizes()
                val u = unitAt(i)
                if (u != null && u.first in R33 && i == start && start == 0) {
                    i += u.second
                    qty = Rational.ONE
                    unit = u.first
                } else if (u != null || word(i) in Lexicon.otherUnits) {
                    notes += toks[i].text.lowercase()
                    i += u?.second ?: 1
                }
            }
            if (word(i) == "of") i++ // R4.5

            val (name, trailingUnit, impliedQty) = nameAndNote(unit == null && qty != null && !sizedCount)
            if (trailingUnit != null) unit = trailingUnit
            if (qty == null && impliedQty != null) qty = impliedQty
            if (unit == QtyUnit.OTHER) check = true

            if ("optional" in lower || "if desired" in lower || "if you like" in lower) optional = true
            val quantityText = if (qtyEnd > qtyStart) span(qtyStart, qtyEnd).trim() else null
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
            )
        }

        val lower = text.lowercase()

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
            // "a"/"an" count before a unit or a name ("a pinch of salt", "an egg"), not "a few".
            if ((w == "a" || w == "an") && (kind(i + 1) !in ARTICLE_BEFORE || word(i + 1) in VAGUE)) return null
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
            check = true
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
        fun nameAndNote(mayTrailUnit: Boolean): Triple<String, QtyUnit?, Rational?> {
            val head = ArrayList<Token>()
            var depth = 0
            var k = i
            var cut = toks.size
            while (k < toks.size) {
                val t = toks[k]
                when (t.kind) {
                    Kind.LPAREN -> {
                        val close = matching(k).takeIf { it > k } ?: toks.size // unclosed: to the end
                        val inner = span(k + 1, close).trim()
                        if (inner.lowercase() == "optional") {
                            optional = true
                        } else if (inner.isNotEmpty()) {
                            notes += inner
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
            if (cut < toks.size) notes += span(cut + 1, toks.size)

            // Words of the name: prep words and qualifiers to the note (R4.1) unless
            // they start a catalogue name (R4.2); a postnominal phrase ends the name.
            val words = head.filter { it.kind == Kind.WORD || it.kind == Kind.DASH || it.kind == Kind.NUM || it.kind == Kind.OTHER }
            val kept = ArrayList<Token>()
            val moved = ArrayList<String>()
            var idx = 0
            while (idx < words.size) {
                val t = words[idx]
                val w = t.text.lowercase()
                if (kept.isNotEmpty() && (w in Lexicon.nameEnders || w in Lexicon.prep) && !startsCatalogName(words, idx)) {
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
            // A leftover amount ("1 lb. 4 oz. beef", "1/0 cup sugar") is not a name: R7.
            if (head.firstOrNull()?.kind in LEFTOVER && catalog.lookup(joinWords(kept)) == null) {
                check = true
            }

            var name = joinWords(kept).lowercase().trim()
            name = name.removePrefix("of ").trim()

            // R7: juice/zest of N lemons.
            var implied: Rational? = null
            Regexless.juiceOf(name)?.let { (part, fruit, n) ->
                check = true
                name = fruit
                implied = n
                notes.add(0, part)
            }
            // R4.4: "A or B" keeps A; "safflower or peanut oil" shares the head noun.
            val orAt = " $name ".indexOf(" or ")
            if (orAt >= 0) {
                check = true
                val a = name.substring(0, maxOf(0, orAt - 1)).trim()
                val b = name.substring(minOf(name.length, orAt + 3)).trim()
                val bWords = b.split(' ')
                // A lone modifier before "or" borrows B's head noun.
                val sharesHead = !a.contains(' ') && bWords.size >= 2 && catalog.lookup(a) == null
                name = if (sharesHead) "$a ${bWords.drop(1).joinToString(" ")}" else a
                notes.add(0, "or $b")
            } else if (" and " in " $name " || " & " in " $name ") {
                check = true // R7: "X and Y" is Claude's to split
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
                if (catalog.lookup(phrase) != null) return true
            }
            return false
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
        /** R3.3: leading unit words that mean "one of". */
        val R33 = setOf(QtyUnit.PINCH, QtyUnit.DASH, QtyUnit.BUNCH, QtyUnit.SPRIG, QtyUnit.HEAD)

        /** Qualifiers that may come before the amount ("about 2 cups"). */
        val LEADING_QUALIFIERS = setOf("about", "approx", "approximately", "around", "roughly")

        val DOZEN: Rational = Rational.of(12)

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

/** Small hand-written matchers (no regex backtracking on untrusted lines). */
internal object Regexless {
    /** "juice of 2 lemons" / "zest from a small orange" → (part, fruit, qty). */
    fun juiceOf(name: String): Triple<String, String, Rational?>? {
        val words = name.split(' ').filter { it.isNotEmpty() }
        val idx = words.indexOfFirst { it == "of" || it == "from" }
        if (idx < 1) return null
        val part = words[idx - 1]
        if (part != "juice" && part != "zest" && part != "rind") return null
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
