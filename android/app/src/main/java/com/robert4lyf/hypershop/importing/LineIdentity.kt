package com.robert4lyf.hypershop.importing

import com.robert4lyf.hypershop.core.model.ParsedIngredient
import kotlinx.serialization.Serializable

/**
 * Which ingredient line of the recipe a review line is: the source text it was read
 * from and which occurrence of that text it is (0 for the first). Two "100 g sugar"
 * lines, one for the cake and one for the icing, are (`100 g sugar`, 0) and
 * (`100 g sugar`, 1): raw text alone cannot tell them apart, and that is what let an
 * edit to one land on both. It never changes while the line is edited, so it is what
 * a correction is filed under.
 */
@Serializable
data class LineOrigin(
    val source: String,
    val nth: Int,
)

/** A line the user corrected ([line]) or removed ([removed], [line] as it was), standing over Claude's reading of it. */
@Serializable
data class LineCorrection(
    val origin: LineOrigin,
    val line: ParsedIngredient,
    val removed: Boolean = false,
)

/** Claude's answer with the user's corrections laid over it, and each line's origin. */
data class Reconciled(
    val lines: List<ParsedIngredient>,
    val origins: List<LineOrigin>,
)

object LineIdentity {
    /** Matching text: case, spacing, bullets and a trailing full stop are not what tells two lines apart. */
    fun normal(raw: String): String =
        raw
            .trim()
            .trimStart('-', '*', '•', '·', '–', ' ')
            .trimEnd('.', ' ')
            .replace(WHITESPACE, " ")
            .lowercase()

    /** Origins for freshly read lines: each one's source text and its occurrence number in reading order. */
    fun originsOf(lines: List<ParsedIngredient>): List<LineOrigin> {
        val seen = HashMap<String, Int>()
        return lines.map { l ->
            val s = normal(l.raw)
            val n = seen.getOrDefault(s, 0)
            seen[s] = n + 1
            LineOrigin(s, n)
        }
    }

    /** [r]'s origins, one per line: the ones kept with it, or (an import not yet reviewed) its lines' own. */
    fun originsOf(r: ImportResult): List<LineOrigin> = r.origins.takeIf { it.size == r.lines.size } ?: originsOf(r.lines)

    /** An origin for a line typed in: its text, after every occurrence of that text known so far. */
    fun next(
        raw: String,
        known: Collection<LineOrigin>,
    ): LineOrigin {
        val s = normal(raw)
        return LineOrigin(s, (known.filter { it.source == s }.maxOfOrNull { it.nth } ?: -1) + 1)
    }

    /**
     * Claude's fresh reading [again] of the recipe the review shows as [before] (lines
     * with their [origins]), with [corrections] standing over it.
     *
     * Claude reads the same source lines in the same order and returns each one's text
     * as `raw`, so a line is matched by its text and, among identical texts, by its
     * occurrence: the second "100 g sugar" Claude returns is the second one in the
     * recipe (or, when both sides give sections, the one in the same section). What
     * cannot be matched confidently is never guessed at:
     *
     * - a text Claude returns as many times as the recipe has it: paired one to one;
     * - a single line Claude split ("salt and pepper"): its correction replaces the
     *   whole split, a removal drops it, otherwise Claude's split stands;
     * - a repeated text Claude returned a different number of times: with a decision
     *   on any of them, those lines stay exactly as the review had them, and the ones
     *   the user had not touched are marked for checking (with none, Claude's stand);
     * - lines whose text matched nothing on either side (Claude translated them):
     *   paired by position only when the counts agree and no removed line is among
     *   them, otherwise Claude's lines are kept and marked for checking.
     *
     * A corrected line Claude left out stays (where it was in the recipe); an
     * untouched one Claude left out goes, as Claude's reading of the rest does.
     */
    fun reconcile(
        again: List<ParsedIngredient>,
        before: List<ParsedIngredient>,
        origins: List<LineOrigin>,
        corrections: Map<LineOrigin, LineCorrection>,
    ): Reconciled {
        require(before.size == origins.size)
        // Every occurrence the review knows, in recipe order: those on screen, then removed ones.
        val known = origins + corrections.values.filter { it.removed && it.origin !in origins }.map { it.origin }
        val lineOf = origins.indices.associateBy({ origins[it] }, { before[it] }) + corrections.mapValues { it.value.line }
        val bySource = known.groupBy { it.source }.mapValues { (_, os) -> os.sortedBy { it.nth } }
        val claudeBySource = again.indices.groupBy { normal(again[it].raw) }

        val match = arrayOfNulls<LineOrigin>(again.size)
        val ambiguous = HashSet<String>()
        val splitOf = HashMap<Int, LineOrigin>()
        for ((source, idx) in claudeBySource) {
            val os = bySource[source] ?: continue
            when {
                idx.size == os.size -> pairBySection(idx, os, again, lineOf).forEach { (i, o) -> match[i] = o }
                os.size == 1 -> idx.forEach { splitOf[it] = os.single() }
                os.any { it in corrections } -> ambiguous += source
                // Nothing decided among them, so nothing can be misplaced: Claude's reading stands, the origins
                // taken in order (any extra line is a new one).
                else -> idx.zip(os).forEach { (i, o) -> match[i] = o }
            }
        }

        // Lines whose text matched nothing: a translation, most likely. Paired by position only when it is unambiguous.
        val loneClaude = again.indices.filter { normal(again[it].raw) !in bySource }
        val loneKnown = known.filter { it.source !in claudeBySource }
        val byPosition = loneClaude.size == loneKnown.size && loneKnown.none { corrections[it]?.removed == true }
        if (byPosition) loneClaude.zip(loneKnown).forEach { (i, o) -> match[i] = o }
        val unsure = if (byPosition) emptySet() else loneClaude.toSet().takeIf { loneKnown.any { it in corrections } }.orEmpty()

        val out = ArrayList<Pair<ParsedIngredient, LineOrigin>>()
        val used = HashSet<LineOrigin>(known)
        val emitted = HashSet<LineOrigin>()

        fun fresh(raw: String): LineOrigin = next(raw, used).also { used += it }

        for (i in again.indices) {
            val l = again[i]
            val source = normal(l.raw)
            if (source in ambiguous) {
                // As the review had them, once, where the first of them comes.
                if (bySource.getValue(source).any { it in emitted }) continue
                for (o in bySource.getValue(source)) {
                    emitted += o
                    val c = corrections[o]
                    when {
                        c?.removed == true -> Unit
                        c != null -> out += c.line to o
                        else -> lineOf[o]?.let { out += it.marked(CHECK_ALIGN) to o }
                    }
                }
                continue
            }
            val split = splitOf[i]
            if (split != null) {
                val c = corrections[split]
                when {
                    c?.removed == true -> emitted += split
                    c != null -> if (emitted.add(split)) out += c.line to split
                    // Claude's split stands: the first part keeps the line's origin, the rest are new lines.
                    else -> out += l to (if (emitted.add(split)) split else fresh(l.raw))
                }
                continue
            }
            val o = match[i]
            if (o == null) {
                out += (if (i in unsure) l.marked(CHECK_ALIGN) else l) to fresh(l.raw)
                continue
            }
            emitted += o
            val c = corrections[o]
            when {
                c?.removed == true -> Unit
                c != null -> out += c.line to o
                else -> out += l to o
            }
        }

        // A corrected line Claude did not return: kept, after the nearest line before it in the recipe.
        for ((k, o) in origins.withIndex()) {
            val c = corrections[o] ?: continue
            if (c.removed || o in emitted) continue
            val anchor = origins.subList(0, k).lastOrNull { prev -> out.any { it.second == prev } }
            val at = anchor?.let { a -> out.indexOfLast { it.second == a } + 1 } ?: minOf(k, out.size)
            out.add(at, c.line to o)
            emitted += o
        }
        return Reconciled(out.map { it.first }, out.map { it.second })
    }

    /** Identical texts paired in order, within each section when both sides name the same sections. */
    private fun pairBySection(
        claude: List<Int>,
        known: List<LineOrigin>,
        again: List<ParsedIngredient>,
        lineOf: Map<LineOrigin, ParsedIngredient>,
    ): List<Pair<Int, LineOrigin>> {
        fun sec(s: String?) = s?.trim()?.lowercase()?.ifEmpty { null }
        val cs = claude.map { sec(again[it].section) }
        val ks = known.map { sec(lineOf[it]?.section) }
        if (cs.any { it == null } || ks.any { it == null } || cs.filterNotNull().sorted() != ks.filterNotNull().sorted()) {
            return claude.zip(known)
        }
        val left = known.indices.toMutableList()
        return claude.mapIndexed { j, i ->
            val k = left.first { ks[it] == cs[j] }
            left.remove(k)
            i to known[k]
        }
    }

    private fun ParsedIngredient.marked(reason: String) = if (check) this else copy(check = true, checkReason = reason)

    /** Shown on a line Claude's answer could not be lined up with the user's edits. */
    const val CHECK_ALIGN = "check: Claude's reading didn't line up with your edits"

    private val WHITESPACE = Regex("\\s+")
}
