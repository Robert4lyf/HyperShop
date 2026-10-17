package com.robert4lyf.hypershop.testutil

import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.data.ImportJobEntity
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.ImportResult
import com.robert4lyf.hypershop.importing.JobState

/** A READY import job built from raw lines, as the worker would leave it. */
suspend fun AppContainer.readyJob(
    vararg lines: String,
    title: String = "Weeknight chilli",
    servings: Int? = 4,
    claudePending: Boolean = false,
): Long {
    val id =
        db.dao().insertJob(
            ImportJobEntity(0, JobState.RUNNING.name, """{"url":"https://example.com/chilli"}""", null, null, clock()),
        )
    val raw = lines.toList()
    importJobs.finish(
        id,
        ImportResult(
            url = "https://example.com/chilli",
            title = title,
            servings = servings,
            locale = UnitLocale.METRIC,
            method = "JSON_LD",
            lines = RuleParser().parseLines(raw),
            claudePending = claudePending,
            rawLines = raw,
        ),
    )
    return id
}

val CHILLI =
    arrayOf(
        "2 onions, chopped", "3 garlic cloves, crushed", "500g beef mince", "400g tin chopped tomatoes", "1 tsp ground cumin",
        "Salt, to taste", "1 tbsp olive oil", "200ml beef stock", "Soured cream, to serve",
    )
