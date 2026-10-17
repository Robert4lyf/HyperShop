package com.robert4lyf.hypershop.claude

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Step 4.5: one real call per model with HYPERSHOP_ANTHROPIC_API_KEY. Skipped unless run
 * with -Deval=true and the key is set. Each parsed result is saved, redacted, as
 * testdata/claude/live-<model>.json.
 * Run: `tools/eval-claude/run.sh` (runs this and the eval).
 */
class LiveSmokeTest {
    private val key = System.getenv("HYPERSHOP_ANTHROPIC_API_KEY")

    @Test
    fun oneCallPerModel() {
        assumeTrue(System.getProperty("eval") == "true", "run with tools/eval-claude/run.sh")
        assumeTrue(!key.isNullOrBlank(), "HYPERSHOP_ANTHROPIC_API_KEY not set")
        val client = ClaudeClient(apiKey = { key })
        val input =
            ClaudeInput.Lines(
                listOf("2 ½ cups (300g) plain flour, sifted", "salt and pepper", "juice of 1 lemon", "400g tin chopped tomatoes"),
                title = "Smoke test",
                servings = 4,
            )
        val out = File("../../testdata/claude").apply { mkdirs() }
        for (m in ClaudeModel.entries) {
            val r = runBlocking { client.parse(input, m) }
            assertTrue(r is ClaudeResult.Parsed, "${m.id}: $r")
            val p = r as ClaudeResult.Parsed
            val text =
                buildString {
                    append("{\n  \"model\": \"${p.recipe.model}\",\n  \"usage\": \"${p.usage}\",\n  \"ingredients\": [\n")
                    append(p.recipe.ingredients.joinToString(",\n") { "    \"" + it.toString().replace("\"", "'") + "\"" })
                    append("\n  ]\n}\n")
                }
            File(out, "live-${m.name.lowercase()}.json").writeText(Redactor.redact(text))
        }
    }
}
