package com.robert4lyf.hypershop.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Step 0.4: proves JUnit 5 and kotest-property run in :core. */
class HelloTest {
    @Test
    fun kotestPropertyRuns() {
        runBlocking {
            checkAll(Arb.int(-1000..1000), Arb.int(-1000..1000)) { a, b ->
                assertEquals(a + b, b + a)
            }
        }
    }
}
