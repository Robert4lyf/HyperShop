package com.robert4lyf.hypershop.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** The voice test's one-off checks, run from the screen (main thread). Each reports to [VoiceTestLog]. */
object VoiceTestTools {
    private const val LANG = "en-GB"

    private fun intent() =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANG)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

    /** (a) Is there an on-device recognizer, and is its English (UK) pack installed? */
    fun checkPack(
        context: Context,
        done: (String) -> Unit,
    ) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            "No on-device recognizer on this phone".also(VoiceTestLog::log).also(done)
            return
        }
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        r.checkRecognitionSupport(
            intent(),
            context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    val state =
                        when {
                            LANG in s.installedOnDeviceLanguages -> "installed"
                            LANG in s.pendingOnDeviceLanguages -> "downloading"
                            LANG in s.supportedOnDeviceLanguages -> "available to download"
                            else -> "not offered"
                        }
                    VoiceTestLog.log("speech pack $LANG: $state (installed: ${s.installedOnDeviceLanguages.take(8)})")
                    done("English (UK) speech pack: $state")
                    r.destroy()
                }

                override fun onError(error: Int) {
                    VoiceTestLog.log("speech pack check failed: ${errorName(error)}")
                    done("Couldn't check: ${errorName(error)}")
                    r.destroy()
                }
            },
        )
    }

    fun downloadPack(
        context: Context,
        done: (String) -> Unit,
    ) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return done("No on-device recognizer on this phone")
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        r.triggerModelDownload(
            intent(),
            context.mainExecutor,
            object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = done("Downloading… $completedPercent%")

                override fun onSuccess() {
                    VoiceTestLog.log("speech pack downloaded")
                    done("English (UK) speech pack: installed")
                    r.destroy()
                }

                override fun onScheduled() {
                    VoiceTestLog.log("speech pack download scheduled (e.g. waiting for Wi-Fi)")
                    done("Download scheduled; check again later")
                    r.destroy()
                }

                override fun onError(error: Int) {
                    VoiceTestLog.log("speech pack download failed: ${errorName(error)}")
                    done("Download failed: ${errorName(error)}")
                    r.destroy()
                }
            },
        )
    }

    /** (a) One phrase: what was heard, and how long after you stopped speaking. */
    fun quickCheck(
        context: Context,
        onDevice: Boolean,
        done: (String) -> Unit,
    ): SpeechRecognizer {
        val local = onDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val r = if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        var endedAt = 0L
        r.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = done("Listening… say \"next three\"")

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    endedAt = System.currentTimeMillis()
                }

                override fun onError(error: Int) {
                    VoiceTestLog.log("quick check (${if (local) "on-device" else "default"}): error ${errorName(error)}")
                    done("Error: ${errorName(error)}")
                    r.destroy()
                }

                override fun onResults(results: Bundle?) {
                    val ms = if (endedAt == 0L) -1 else System.currentTimeMillis() - endedAt
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    VoiceTestLog.log("quick check (${if (local) "on-device" else "default"}, ${ms}ms): ${texts.joinToString(" | ")}")
                    done("Heard \"${texts.firstOrNull().orEmpty()}\" in ${ms}ms (${if (local) "on-device" else "default recognizer"})")
                    r.destroy()
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) = Unit
            },
        )
        r.startListening(intent().putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, local))
        return r
    }

    /** The phone speaks a line, to check it reaches the headphones (and how it sounds). */
    fun speak(
        context: Context,
        text: String,
    ) {
        var tts: TextToSpeech? = null
        tts =
            TextToSpeech(context.applicationContext) { status ->
                val t = tts ?: return@TextToSpeech
                if (status != TextToSpeech.SUCCESS) {
                    VoiceTestLog.log("text to speech failed to start ($status)")
                    return@TextToSpeech
                }
                val lang = t.setLanguage(Locale.UK)
                VoiceTestLog.log("speaking (en-GB ${if (lang >= TextToSpeech.LANG_AVAILABLE) "ok" else "missing: $lang"})")
                t.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) = t.shutdown()

                        @Deprecated("Required by the abstract class")
                        override fun onError(utteranceId: String?) = t.shutdown()
                    },
                )
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice-test")
            }
    }
}
