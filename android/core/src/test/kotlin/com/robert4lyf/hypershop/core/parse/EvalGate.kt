package com.robert4lyf.hypershop.core.parse

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Holds a labelled set to its recorded baseline, line by line and field by field.
 * The parser is deterministic, so this is exact, not a tolerance: any field of any
 * line that was right and is now wrong fails (name, amount, unit, catalogue item,
 * aisle, optional, garnish, "A or B", the shown amount), a line whose flag changed
 * fails (a newly flagged line sends its recipe to Claude; a newly unflagged wrong
 * one is a confident mistake), and an improvement fails too until the baseline is
 * updated in the same change, so the ratchet never goes slack.
 *
 * Labels: their checksum is recorded. A label changed after the gate was set
 * ([labelsAtGate]) must be named, by the first 12 characters of the new checksum,
 * in the set's SOURCES file under a "Label corrections" heading.
 *
 * `HYPERSHOP_EVAL_UPDATE=1 tools/gradle.sh :core:test --tests "*EvalTest*"`
 * rewrites the baseline and still fails that run, listing what it accepted; the
 * diff is the review. `tools/verify.sh` refuses to run with it set.
 */
object EvalGate {
    @Serializable
    data class Baseline(
        val labels: String,
        val labelsSha256: String,
        val lines: Int,
        val right: List<String>,
        val acceptedWrong: List<String>,
        /** Per line: a letter for each field read right (n q u c a o g x s), and F when flagged. */
        val fields: Map<String, String> = emptyMap(),
    )

    private val json = Json { prettyPrint = true }

    fun sha256(path: String): String =
        MessageDigest.getInstance("SHA-256").digest(File("../../$path").readBytes()).joinToString("") { "%02x".format(it) }

    private fun signature(s: ExtractionEval.Scored): String =
        listOf(
            'n' to s.nameOk,
            'q' to s.qtyOk,
            'u' to (s.unitOk || s.unitEquivalent),
            'c' to s.identityOk,
            'a' to s.aisleOk,
            'o' to s.optionalOk,
            'g' to s.garnishOk,
            'x' to s.altOk,
            's' to s.displayOk,
        ).filter { it.second }.map { it.first }.joinToString("") + if (s.flagged) "F" else ""

    private fun current(eval: ExtractionEval) =
        Baseline(
            eval.path,
            sha256(eval.path),
            eval.all.size,
            eval.all.filter { it.right }.map { it.l.id }.sorted(),
            eval.acceptedWrong.map { it.l.id }.sorted(),
            eval.all.associate { it.l.id to signature(it) }.toSortedMap(),
        )

    /** Problems against [baselinePath], empty when the set reads exactly as recorded. */
    fun check(
        eval: ExtractionEval,
        baselinePath: String,
        labelsAtGate: String,
    ): List<String> {
        val now = current(eval)
        val file = File("../../$baselinePath")
        val was = json.decodeFromString(Baseline.serializer(), file.readText())
        val byId = eval.all.associateBy { it.l.id }

        fun describe(id: String) = byId[id]?.let { "$id \"${it.l.raw}\" → ${it.got} (want ${it.want})" } ?: id
        val problems = ArrayList<String>()
        if (now.labelsSha256 != was.labelsSha256) problems += "the labels in ${eval.path} changed: update the baseline"
        if (!now.labelsSha256.startsWith(labelsAtGate) && !correctionRecorded(eval.path, now.labelsSha256)) {
            problems += "labels changed since the gate was set, with no entry naming ${now.labelsSha256.take(12)} under " +
                "\"Label corrections\" in the set's SOURCES file"
        }
        for ((id, sig) in now.fields) {
            val before = was.fields[id] ?: continue
            val lost = before.filter { it != 'F' && it !in sig }
            if (lost.isNotEmpty()) problems += "regressed ($lost): ${describe(id)}"
            if (('F' in before) != ('F' in sig)) problems += "flag ${if ('F' in sig) "raised" else "dropped"}: ${describe(id)}"
        }
        (was.right - now.right.toSet()).forEach { problems += "regressed, was right: ${describe(it)}" }
        (now.acceptedWrong - was.acceptedWrong.toSet()).forEach { problems += "newly accepted while wrong: ${describe(it)}" }
        val better =
            now.fields.filter { (id, sig) -> was.fields[id]?.let { b -> sig.any { it != 'F' && it !in b } } ?: true }.keys +
                (was.acceptedWrong - now.acceptedWrong.toSet())
        if (better.isNotEmpty()) problems += "improved (${better.distinct().joinToString()}): update the baseline so it holds"
        if (System.getenv("HYPERSHOP_EVAL_UPDATE") == "1") {
            file.writeText(json.encodeToString(Baseline.serializer(), now) + "\n")
            problems += "baseline rewritten (HYPERSHOP_EVAL_UPDATE=1); it accepted the above. Run again without it."
        }
        return problems
    }

    private fun correctionRecorded(
        path: String,
        sha: String,
    ): Boolean {
        val sources = File("../../${path.removeSuffix(".json")}-SOURCES.md").readText()
        val section = sources.substringAfter("Label corrections", "")
        return section.contains(sha.take(12))
    }
}
