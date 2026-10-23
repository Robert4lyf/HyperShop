package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.model.ParsedIngredient
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * U.1: Claude's recorded reading of the labelled sets (`testdata/claude/eval-haiku-<set>.json`, made by
 * `ClaudeSetRecorder` in `:claude`, one live run), scored offline against the same labels, by the same
 * rules, as the rule parser is. Nothing here calls Claude. The report goes to build/reports/claude-sets.md.
 */
class ClaudeSetEvalTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val alignment = ClaudeAlignment()

    private fun recorded(set: String): Map<String, ParsedIngredient?> =
        json.decodeFromString(
            MapSerializer(String.serializer(), ParsedIngredient.serializer().nullable),
            File("../../testdata/claude/eval-haiku-$set.json").readText(),
        )

    private fun claude(set: String): ExtractionEval {
        val read = recorded(set)
        // Through today's alignment: the recording was made once, the post-processing may have moved on since.
        return ExtractionEval("testdata/eval/$set.json") { read[it.id]?.let(alignment::align) }
    }

    @Test
    fun report() {
        val text =
            SETS.joinToString("\n\n") { set ->
                val rules = ExtractionEval("testdata/eval/$set.json")
                val c = claude(set)

                fun line(
                    who: String,
                    e: ExtractionEval,
                ): String {
                    val a = e.all
                    val wrong = a.count { !it.right }
                    return "| $who | ${a.count { it.right }} | ${a.count { it.identityOk }} | ${e.acceptedWrong.size} | " +
                        "${a.count { it.flagged && !it.right }} of $wrong | ${a.count { it.flagged && it.right }} |"
                }
                "## $set (${c.all.size} lines)\n\n" +
                    "| reader | right | catalogue match | accepted wrong | wrong and flagged | right but flagged |\n" +
                    "|---|---|---|---|---|---|\n" +
                    line("rule parser", rules) + "\n" + line("Claude Haiku 5.5", c) + "\n\n" +
                    c.report("Claude on $set").substringAfter("## Incorrectly accepted").let { "### Claude, incorrectly accepted$it" }
            }
        File("build/reports").mkdirs()
        File("build/reports/claude-sets.md").writeText(text)
        println(text)
    }

    @Test
    fun everyLabelHasAReadingRecorded() {
        for (set in SETS) assertEquals(ExtractionEval("testdata/eval/$set.json").all.map { it.l.id }.toSet(), recorded(set).keys)
    }

    /** Exactly, as [EvalFloors]: a fall is a regression in the post-processing, a rise means raising these. */
    @Test
    fun claudeTotalsHold() {
        for ((set, want) in TOTALS) {
            val e = claude(set)
            val got = listOf(e.all.count { it.right }, e.acceptedWrong.size, e.all.count { it.flagged && it.right })
            assertEquals(want, got, "$set: right, accepted wrong, right but flagged")
        }
    }

    private companion object {
        /** U.2: right, accepted wrong, right but flagged — Claude Haiku 5.5's recording, through today's alignment. */
        val TOTALS =
            mapOf(
                "t-2026" to listOf(126, 7, 0),
                "final-2026" to listOf(123, 9, 1),
                "release-c-2026" to listOf(259, 15, 2),
            )
        val SETS = listOf("t-2026", "final-2026", "release-c-2026")
    }
}
