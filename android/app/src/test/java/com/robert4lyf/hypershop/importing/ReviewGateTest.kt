package com.robert4lyf.hypershop.importing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gate counts screens: one review replacing another never closes it on the new one. */
class ReviewGateTest {
    @Test
    fun openWhileAnyReviewHoldsIt() {
        val gate = ReviewGate()
        assertFalse(gate.visible.value)
        gate.acquire()
        assertTrue(gate.visible.value)
        // "Try again": the new review starts before the old one stops.
        gate.acquire()
        gate.release()
        assertTrue("the new review is still in the foreground", gate.visible.value)
        gate.release()
        assertFalse(gate.visible.value)
    }

    @Test
    fun aStrayReleaseNeverGoesNegative() {
        val gate = ReviewGate()
        gate.release()
        gate.acquire()
        assertTrue(gate.visible.value)
        gate.release()
        assertFalse(gate.visible.value)
    }
}
