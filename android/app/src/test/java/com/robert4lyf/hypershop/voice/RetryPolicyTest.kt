package com.robert4lyf.hypershop.voice

import android.speech.SpeechRecognizer.ERROR_NO_MATCH
import android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY
import android.speech.SpeechRecognizer.ERROR_SERVER_DISCONNECTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The loop seen on a real phone: SERVER_DISCONNECTED, then RECOGNIZER_BUSY, every couple of seconds. */
class RetryPolicyTest {
    @Test
    fun theDisconnectBusyLoopBacksOffFallsBackAndEnds() {
        val p = RetryPolicy()
        val first = p.onError(ERROR_SERVER_DISCONNECTED, onDevice = true)
        assertEquals(1_000L, first.delayMs)
        // Busy waits at least three seconds for the last recognizer to let go.
        val busy = p.onError(ERROR_RECOGNIZER_BUSY, onDevice = true)
        assertTrue(busy.delayMs >= RetryPolicy.BUSY_MS)
        assertFalse(busy.fallBack)
        p.onError(ERROR_SERVER_DISCONNECTED, onDevice = true)
        p.onError(ERROR_RECOGNIZER_BUSY, onDevice = true)
        // The third disconnect in a row moves to the default recognizer.
        val third = p.onError(ERROR_SERVER_DISCONNECTED, onDevice = true)
        assertTrue(third.fallBack)
        assertTrue(third.delayMs >= 16_000L)
        // The waits never exceed thirty seconds, and the tenth failure ends the test.
        var last = third
        repeat(4) { last = p.onError(ERROR_RECOGNIZER_BUSY, onDevice = false) }
        assertEquals(RetryPolicy.MAX_MS, last.delayMs)
        assertFalse(last.giveUp)
        assertTrue(p.onError(ERROR_SERVER_DISCONNECTED, onDevice = false).giveUp)
    }

    @Test
    fun silenceAndHearingSomethingClearTheStrikes() {
        val p = RetryPolicy()
        repeat(2) { p.onError(ERROR_SERVER_DISCONNECTED, onDevice = true) }
        assertEquals(RetryPolicy.QUICK_MS, p.onError(ERROR_NO_MATCH, onDevice = true).delayMs)
        // Back to the first step, and two more disconnects are not yet three in a row.
        assertEquals(1_000L, p.onError(ERROR_SERVER_DISCONNECTED, onDevice = true).delayMs)
        assertFalse(p.onError(ERROR_SERVER_DISCONNECTED, onDevice = true).fallBack)
        p.succeeded()
        repeat(9) { assertFalse(p.onError(ERROR_RECOGNIZER_BUSY, onDevice = true).giveUp) }
    }
}
