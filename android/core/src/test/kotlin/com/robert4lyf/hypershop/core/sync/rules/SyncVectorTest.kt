package com.robert4lyf.hypershop.core.sync.rules

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Replays every JSON vector in testdata/sync through the pure phone and watch rules. */
class SyncVectorTest {
    @TestFactory
    fun vectors(): List<DynamicTest> {
        val files = SyncHarness.dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue(files.size >= 15, "expected the sync vectors in ${SyncHarness.dir.absolutePath}")
        return files.map { f -> DynamicTest.dynamicTest(f.nameWithoutExtension) { SyncHarness.load(f).run() } }
    }
}
