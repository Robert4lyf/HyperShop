package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.model.QtyUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Y.18: "jar" is a unit, as "tin" is (owner: "Jars should be an accepted unit"). */
class JarUnitTest {
    private val p = RuleParser()

    @Test
    fun aJarIsAUnit() {
        listOf("1 jar pesto", "2 jars roasted red peppers", "1 (16-ounce) jar salsa").forEach { line ->
            assertEquals(QtyUnit.JAR, p.parse(line).unit, line)
        }
        assertEquals("pesto", p.parse("1 jar pesto").name)
    }
}
