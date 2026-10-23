package com.robert4lyf.hypershop.claude

import com.robert4lyf.hypershop.core.model.ParsedIngredient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * U.1: Claude's reading of the labelled sets in `testdata/eval`, recorded once so it can be scored
 * offline as often as wanted (`ClaudeSetEvalTest` in `:core`). Each recipe page of a set is sent as one
 * import would send it — its lines, in order, one call — to the app's default model, and each label is
 * paired with the reading of its own line (by text, in order; Claude may split a line, and the first
 * part is what is scored). Skipped unless run with -Deval=true and HYPERSHOP_ANTHROPIC_API_KEY set; stops
 * before going past [BUDGET_USD].
 *
 * Run: `tools/gradle.sh :claude:test --tests '*ClaudeSetRecorder*' -Deval=true --rerun`
 */
class ClaudeSetRecorder {
    private val key = System.getenv("HYPERSHOP_ANTHROPIC_API_KEY")
    private val json = Json { prettyPrint = true }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    @Test
    fun recordTheSets() {
        assumeTrue(System.getProperty("eval") == "true", "a live run, on request only")
        assumeTrue(!key.isNullOrBlank(), "HYPERSHOP_ANTHROPIC_API_KEY not set")
        val client = ClaudeClient(apiKey = { key })
        val model = ClaudeModel.DEFAULT
        var spent = 0.0
        val log = StringBuilder()
        for (set in SETS) {
            val labels = Json.parseToJsonElement(File("../../testdata/eval/$set.json").readText()).jsonArray.map { it.jsonObject }
            val read = LinkedHashMap<String, ParsedIngredient?>()
            for ((source, lines) in labels.groupBy { it.getValue("source").jsonPrimitive.content }) {
                assertTrue(spent < BUDGET_USD, "budget reached at $spent USD")
                val raws = lines.map { it.getValue("raw").jsonPrimitive.content }
                val r = runBlocking { client.parse(ClaudeInput.Lines(raws), model) }
                spent += r.usage?.let(model::costUsd) ?: 0.0
                val got = (r as? ClaudeResult.Parsed)?.recipe?.ingredients.orEmpty().toMutableList()
                log.append("$set $source: ${raws.size} lines, ${if (r is ClaudeResult.Parsed) got.size else r} \n")
                for (l in lines) {
                    val raw = norm(l.getValue("raw").jsonPrimitive.content)
                    val i = got.indexOfFirst { norm(it.raw) == raw }
                    read[l.getValue("id").jsonPrimitive.content] = if (i >= 0) got.removeAt(i) else null
                    // A split line ("salt and pepper"): the rest of it is not another label's reading.
                    if (i >= 0) got.removeAll { norm(it.raw) == raw }
                }
            }
            val text = json.encodeToString(MapSerializer(String.serializer(), ParsedIngredient.serializer().nullable), read)
            File("../../testdata/claude/eval-${model.name.lowercase()}-$set.json").writeText(Redactor.redact(text) + "\n")
        }
        log.append("model ${model.id}, spent about ${"%.4f".format(spent)} USD\n")
        File("../../testdata/claude/eval-${model.name.lowercase()}-run.txt").writeText(log.toString())
        println(log)
    }

    private companion object {
        val SETS = listOf("t-2026", "final-2026", "release-c-2026")
        const val BUDGET_USD = 0.50
    }
}
