package com.robert4lyf.hypershop.core.parse

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Holds a labelled set to its recorded baseline, line by line. The parser is
 * deterministic, so this is exact, not a tolerance: a line read right in the
 * baseline that is now wrong fails, and so does a wrong line the gate now lets
 * through that it did not before (a confident mistake). An improvement fails
 * too, until the baseline is updated in the same change, so the ratchet never
 * goes slack and a later regression cannot hide behind it.
 *
 * The baseline also records the labels' checksum: changing a label to make a
 * line pass changes the checksum, and the baseline cannot be updated without a
 * correction recorded in the set's SOURCES file (see [update]).
 *
 * `HYPERSHOP_EVAL_UPDATE=1 tools/gradle.sh :core:test --tests "*EvalTest*"`
 * rewrites the baseline from the current reading; the diff is the review.
 */
object EvalGate {
    @Serializable
    data class Baseline(
        val labels: String,
        val labelsSha256: String,
        val lines: Int,
        val right: List<String>,
        val acceptedWrong: List<String>,
    )

    private val json = Json { prettyPrint = true }

    fun sha256(path: String): String =
        MessageDigest.getInstance("SHA-256").digest(File("../../$path").readBytes()).joinToString("") { "%02x".format(it) }

    private fun current(eval: ExtractionEval) =
        Baseline(
            eval.path,
            sha256(eval.path),
            eval.all.size,
            eval.all.filter { it.right }.map { it.l.id }.sorted(),
            eval.acceptedWrong.map { it.l.id }.sorted(),
        )

    /** Problems against [baselinePath], empty when the set reads exactly as recorded. */
    fun check(
        eval: ExtractionEval,
        baselinePath: String,
    ): List<String> {
        val now = current(eval)
        if (System.getenv("HYPERSHOP_EVAL_UPDATE") == "1") update(eval, baselinePath, now)
        val was = json.decodeFromString(Baseline.serializer(), File("../../$baselinePath").readText())
        val byId = eval.all.associateBy { it.l.id }

        fun describe(id: String) = byId[id]?.let { "$id \"${it.l.raw}\" → ${it.got} (want ${it.want})" } ?: id
        val problems = ArrayList<String>()
        if (now.labelsSha256 != was.labelsSha256) {
            problems += "the labels in ${eval.path} changed: record why in its SOURCES file, then update the baseline"
        }
        (was.right - now.right.toSet()).forEach { problems += "regressed, was right: ${describe(it)}" }
        (now.acceptedWrong - was.acceptedWrong.toSet()).forEach { problems += "newly accepted while wrong: ${describe(it)}" }
        val better = (now.right - was.right.toSet()) + (was.acceptedWrong - now.acceptedWrong.toSet())
        if (better.isNotEmpty()) problems += "improved (${better.distinct().joinToString()}): update the baseline so it holds"
        return problems
    }

    private fun update(
        eval: ExtractionEval,
        baselinePath: String,
        now: Baseline,
    ) {
        val file = File("../../$baselinePath")
        val sources = File("../../${eval.path.removeSuffix(".json")}-SOURCES.md")
        if (file.exists()) {
            val was = json.decodeFromString(Baseline.serializer(), file.readText())
            check(was.labelsSha256 == now.labelsSha256 || sources.readText().contains(now.labelsSha256.take(12))) {
                "labels changed: add a line to ${sources.name} › Label corrections naming ${now.labelsSha256.take(12)} and the reason first"
            }
        }
        file.writeText(json.encodeToString(Baseline.serializer(), now) + "\n")
    }
}
