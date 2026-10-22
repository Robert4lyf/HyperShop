package com.robert4lyf.hypershop.core.plan

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * A meal-plan week: Monday to Sunday, named by its Monday's epoch day. A date,
 * not an instant, so a clock change or a trip abroad cannot move a meal into
 * another week.
 */
@JvmInline
value class Week(
    val startEpochDay: Long,
) : Comparable<Week> {
    val monday: LocalDate get() = LocalDate.ofEpochDay(startEpochDay)
    val sunday: LocalDate get() = monday.plusDays(6)

    operator fun plus(weeks: Int): Week = Week(startEpochDay + 7L * weeks)

    operator fun minus(weeks: Int): Week = plus(-weeks)

    override fun compareTo(other: Week): Int = startEpochDay.compareTo(other.startEpochDay)

    /** "20–26 Oct", "27 Oct – 2 Nov", "29 Dec 2025 – 4 Jan 2026" when it spans a new year. */
    fun range(): String {
        val a = monday
        val b = sunday

        fun month(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, Locale.UK)
        return when {
            a.year != b.year -> "${a.dayOfMonth} ${month(a)} ${a.year} – ${b.dayOfMonth} ${month(b)} ${b.year}"
            a.month != b.month -> "${a.dayOfMonth} ${month(a)} – ${b.dayOfMonth} ${month(b)}"
            else -> "${a.dayOfMonth}–${b.dayOfMonth} ${month(b)}"
        }
    }

    /** "This week", "Next week", "Last week", else "Week of 13 Oct". */
    fun name(current: Week): String =
        when (startEpochDay - current.startEpochDay) {
            0L -> "This week"
            7L -> "Next week"
            -7L -> "Last week"
            else -> "Week of ${monday.dayOfMonth} ${monday.month.getDisplayName(TextStyle.SHORT, Locale.UK)}"
        }

    companion object {
        fun of(date: LocalDate): Week = Week(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay())

        fun of(
            epochMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): Week = of(Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate())
    }
}
