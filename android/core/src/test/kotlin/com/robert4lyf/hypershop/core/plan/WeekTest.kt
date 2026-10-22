package com.robert4lyf.hypershop.core.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class WeekTest {
    @Test
    fun everyDayOfAWeekIsThatWeeksMonday() {
        val monday = LocalDate.of(2026, 10, 19)
        for (d in 0L..6L) assertEquals(monday, Week.of(monday.plusDays(d)).monday)
        assertEquals(monday.plusDays(7), Week.of(monday.plusDays(7)).monday)
    }

    @Test
    fun aWeekAcrossTheNewYearIsOneWeek() {
        // Monday 29 December 2025 to Sunday 4 January 2026.
        val w = Week.of(LocalDate.of(2026, 1, 1))
        assertEquals(LocalDate.of(2025, 12, 29), w.monday)
        assertEquals(w, Week.of(LocalDate.of(2026, 1, 4)))
        assertEquals("29 Dec 2025 – 4 Jan 2026", w.range())
        assertEquals(LocalDate.of(2026, 1, 5), (w + 1).monday)
    }

    @Test
    fun clockChangesDoNotMoveAMealIntoAnotherWeek() {
        val london = ZoneId.of("Europe/London")
        // Sunday 29 March 2026, 23:30 BST (the clocks went forward that morning) is still that week.
        val sundayNight = ZonedDateTime.of(2026, 3, 29, 23, 30, 0, 0, london).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 3, 23), Week.of(sundayNight, london).monday)
        // Monday 26 October 2026, 00:30 GMT (they went back the night before) is the new week.
        val mondayEarly = ZonedDateTime.of(2026, 10, 26, 0, 30, 0, 0, london).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 26), Week.of(mondayEarly, london).monday)
        // And weeks stay seven days apart across both changes.
        val spring = Week.of(LocalDate.of(2026, 3, 23))
        assertEquals(LocalDate.of(2026, 11, 2), (spring + 32).monday)
    }

    @Test
    fun namesAndRanges() {
        val now = Week.of(LocalDate.of(2026, 10, 22))
        assertEquals("This week", now.name(now))
        assertEquals("Next week", (now + 1).name(now))
        assertEquals("Last week", (now - 1).name(now))
        assertEquals("Week of 5 Oct", (now - 2).name(now))
        assertEquals("19–25 Oct", now.range())
        assertEquals("26 Oct – 1 Nov", (now + 1).range())
    }
}
