package com.robert4lyf.hypershop.voice

import android.speech.SpeechRecognizer

/**
 * What the voice test does after a recognizer error. On a real phone the
 * on-device recognizer dropped its service (SERVER_DISCONNECTED) every few
 * seconds, and a new one created straight after was refused as busy while the
 * old one was still shutting down: a loop of two errors a second, for ever.
 * So retries back off, three disconnects in a row move to the default
 * recognizer, and ten failures in a row end the test rather than the battery.
 */
class RetryPolicy {
    /** [delayMs] before trying again; [fallBack] to the default recognizer; [giveUp] stops the test. */
    data class Decision(
        val delayMs: Long,
        val fallBack: Boolean = false,
        val giveUp: Boolean = false,
    )

    private var failures = 0
    private var disconnects = 0

    /** Something was heard (or the session ended normally): the recognizer works. */
    fun succeeded() {
        failures = 0
        disconnects = 0
    }

    fun onError(
        error: Int,
        onDevice: Boolean,
    ): Decision {
        // Silence is the recognizer working: a quick restart, and no strike.
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            succeeded()
            return Decision(QUICK_MS)
        }
        failures++
        if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_CLIENT) disconnects++
        if (failures >= GIVE_UP_AFTER) return Decision(0, giveUp = true)
        val fallBack = onDevice && disconnects >= FALL_BACK_AFTER
        if (fallBack) disconnects = 0
        val backoff = (BASE_MS shl (failures - 1).coerceAtMost(5)).coerceAtMost(MAX_MS)
        val floor =
            when (error) {
                // Busy: the last recognizer is still letting go; give it time rather than another one.
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> BUSY_MS
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> MAX_MS
                else -> 0
            }
        return Decision(maxOf(backoff, floor), fallBack = fallBack)
    }

    companion object {
        const val QUICK_MS = 300L
        const val BASE_MS = 1_000L
        const val BUSY_MS = 3_000L
        const val MAX_MS = 30_000L
        const val FALL_BACK_AFTER = 3
        const val GIVE_UP_AFTER = 10
    }
}
