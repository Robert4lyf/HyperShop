package com.robert4lyf.hypershop.importing

import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.parse.sectionHeaderOf
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

/**
 * The review's lines as they were when Claude was asked, kept with an answer saved
 * on the job ([ImportResult.claudeBefore]) so a review opened later lays the
 * corrections over it ([LineIdentity.reconcile]) instead of showing Claude's raw reading.
 */
@Serializable
data class ReviewLines(
    val lines: List<ParsedIngredient>,
    val origins: List<LineOrigin>,
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

    /**
     * The source lines for Claude to read, from lines already parsed (a photo's, a saved recipe's): one per line of
     * the recipe, never one per ingredient. A line Claude read as several ("salt and pepper" → salt, pepper) carries
     * its text on each part and is sent once; two lines that only read the same ("100 g sugar" for the cake and
     * again for the icing) are two lines and both are sent. Which is which is the lines' [ParsedIngredient.sourceLine],
     * never their text: see [sourceKeys]. A section heading goes before its first line, as the recipe had it.
     */
    fun sourceLines(lines: List<ParsedIngredient>): List<String> = reread(lines).second

    /**
     * [lines] and the source lines they were read from ([sourceLines]), with each line's
     * [ParsedIngredient.sourceLine] made its index in that list: what a review of lines read
     * earlier starts from, so a line typed in later (at the end) or Claude's next reading
     * ([attach]) is numbered the same way.
     */
    fun reread(lines: List<ParsedIngredient>): Pair<List<ParsedIngredient>, List<String>> {
        val keys = sourceKeys(lines)
        val raw = ArrayList<String>()
        val at = HashMap<Any, Int>()
        var section: String? = null
        val out =
            lines.mapIndexed { i, l ->
                val n =
                    at.getOrPut(keys[i]) {
                        val s = l.section?.trim()?.ifEmpty { null }
                        if (s != null && s != section) headerFor(s)?.let { raw += it }
                        section = s ?: section
                        raw += l.raw
                        raw.size - 1
                    }
                l.copy(sourceLine = n)
            }
        return out to raw
    }

    /**
     * Claude's reading [lines] of the source lines [raw] it was sent, each line given the index of the source
     * line it came from. Matched by text, in order, one source occurrence per ingredient, except that within a
     * run of lines with one text a name that has not come up yet in the run is a further part of the same source
     * line (Claude's split), and a name that has is the next occurrence. A line whose text is in none of [raw]
     * (Claude rephrased it), or one more than [raw] has, is left without one.
     */
    fun attach(
        lines: List<ParsedIngredient>,
        raw: List<String>,
    ): List<ParsedIngredient> {
        val free = HashMap<String, ArrayDeque<Int>>()
        for ((i, r) in raw.withIndex()) if (sectionHeaderOf(r) == null) free.getOrPut(normal(r)) { ArrayDeque() }.addLast(i)
        val groups = splitGroups(lines)
        val of = HashMap<Int, Int?>()
        return lines.mapIndexed { i, l ->
            val n = of.getOrPut(groups[i]) { free[normal(l.raw)]?.removeFirstOrNull() }
            l.copy(sourceLine = n)
        }
    }

    /**
     * One key per line, equal for the lines read from one source line: its [ParsedIngredient.sourceLine]
     * where it has one. A line stored before lines carried it is grouped as [splitGroups] does, which keeps
     * two identical lines apart (their parts read the same name) and the parts of one split together.
     */
    private fun sourceKeys(lines: List<ParsedIngredient>): List<Any> {
        val groups = splitGroups(lines)
        return lines.mapIndexed { i, l -> l.sourceLine ?: "unnumbered-${groups[i]}" }
    }

    /**
     * Lines with no [ParsedIngredient.sourceLine], grouped into source lines by what Claude does when it splits
     * one: the parts are adjacent, share the line's text and name different ingredients. A name already in the
     * group starts the next occurrence ("salt and pepper" twice, each split, is two groups of two). Lines with a
     * source line are each their own group here; the group of line i is the index of its first line.
     */
    private fun splitGroups(lines: List<ParsedIngredient>): IntArray {
        val group = IntArray(lines.size)
        val names = HashSet<String>()
        for ((i, l) in lines.withIndex()) {
            val prev = lines.getOrNull(i - 1)
            val name = l.name.trim().lowercase()
            val joins =
                prev != null && prev.sourceLine == null && l.sourceLine == null &&
                    normal(prev.raw) == normal(l.raw) && name !in names
            if (joins) {
                group[i] = group[i - 1]
            } else {
                group[i] = i
                names.clear()
            }
            names += name
        }
        return group
    }

    /** "For the icing" as the heading line the rule parser reads back as that section, or null if it would not. */
    private fun headerFor(section: String): String? = "$section:".takeIf { sectionHeaderOf(it) == section }

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
     *   them; otherwise, if the user decided anything about one of them, those lines
     *   stay as the review had them (untouched ones marked) and Claude's are not
     *   added, and if not, Claude's stand.
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
        // Not pairable, and a decision among them: which of Claude's lines is the corrected or removed one cannot be
        // told, so those lines stay exactly as the review had them (untouched ones marked) and Claude's unmatched lines
        // are not added beside them — adding them would buy a corrected ingredient twice, or bring a removed one back.
        val keepReview = !byPosition && loneKnown.any { it in corrections }
        val dropped = if (keepReview) loneClaude.toSet() else emptySet()

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
            if (i in dropped) continue
            val o = match[i]
            if (o == null) {
                out += l to fresh(l.raw)
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

        // A corrected line Claude did not return, and (when Claude's could not be lined up) every line of the review
        // it would have replaced: kept, after the nearest line before it in the recipe.
        val kept = if (keepReview) loneKnown.toSet() else emptySet()
        for ((k, o) in origins.withIndex()) {
            if (o in emitted) continue
            val c = corrections[o]
            val line =
                when {
                    c?.removed == true -> continue
                    c != null -> c.line
                    o in kept -> before[k].marked(CHECK_ALIGN)
                    else -> continue
                }
            val anchor = origins.subList(0, k).lastOrNull { prev -> out.any { it.second == prev } }
            val at = anchor?.let { a -> out.indexOfLast { it.second == a } + 1 } ?: minOf(k, out.size)
            out.add(at, line to o)
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
