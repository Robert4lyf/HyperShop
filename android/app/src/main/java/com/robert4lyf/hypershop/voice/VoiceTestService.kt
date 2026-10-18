package com.robert4lyf.hypershop.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import com.robert4lyf.hypershop.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The voice test's background part (docs/design/voice-mode.md §5, step 0): a
 * microphone foreground service that keeps recognising with the screen locked
 * and logs what happens, so the plan's open questions are answered on the
 * real phone and headsets. Nothing here ticks the list or calls Claude.
 */
class VoiceTestService : Service() {
    /** How listening is kept going. */
    enum class Mode { SEGMENTED, RESTART }

    data class Options(
        val headsetMic: Boolean,
        val onDevice: Boolean,
        val mode: Mode,
    )

    companion object {
        private const val ACTION_START = "com.robert4lyf.hypershop.voice.START"
        private const val ACTION_STOP = "com.robert4lyf.hypershop.voice.STOP"
        private const val CHANNEL = "voice-test"
        private const val NOTIFICATION_ID = 42

        /** Android may end a segmented session sooner; it is restarted when it does. */
        private const val SEGMENT_SESSION_MS = 10 * 60 * 1000L
        private const val HEARTBEAT_MS = 60_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(
            context: Context,
            o: Options,
        ) {
            val i =
                Intent(context, VoiceTestService::class.java)
                    .setAction(ACTION_START)
                    .putExtra("headsetMic", o.headsetMic)
                    .putExtra("onDevice", o.onDevice)
                    .putExtra("mode", o.mode.name)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VoiceTestService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var session: MediaSession? = null
    private var options = Options(headsetMic = false, onDevice = true, mode = Mode.SEGMENTED)
    private var startedAt = 0L
    private var heard = 0
    private var restarts = 0
    private var errors = 0
    private var routed = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        VoiceTestLog.init(this)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (_running.value) return START_NOT_STICKY
        options =
            Options(
                headsetMic = intent?.getBooleanExtra("headsetMic", false) ?: false,
                onDevice = intent?.getBooleanExtra("onDevice", true) ?: true,
                mode = runCatching { Mode.valueOf(intent?.getStringExtra("mode") ?: "") }.getOrDefault(Mode.SEGMENTED),
            )
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android 14: a microphone service must start while the app is on screen.
            VoiceTestLog.log("START FAILED: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        _running.value = true
        startedAt = System.currentTimeMillis()
        VoiceTestLog.log("— background test started: $options")
        logState("start")
        if (options.headsetMic) routeToHeadset()
        startMediaSession()
        playSilence()
        listen()
        main.postDelayed(heartbeat, HEARTBEAT_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        session?.release()
        session = null
        if (routed) {
            val am = getSystemService(AudioManager::class.java)
            am.clearCommunicationDevice()
            am.mode = AudioManager.MODE_NORMAL
        }
        if (_running.value) {
            VoiceTestLog.log("— stopped after ${minutes()} min: $heard heard, $restarts restarts, $errors errors")
            logState("stop")
        }
        _running.value = false
        super.onDestroy()
    }

    // ---- listening ------------------------------------------------------------------

    private fun listen() {
        if (stopping) return
        recognizer?.destroy()
        val onDevice = options.onDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        if (options.onDevice && !onDevice) VoiceTestLog.log("on-device recognizer unavailable; using the default one")
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(listener)
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
        if (options.mode == Mode.SEGMENTED) {
            intent
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SEGMENT_SESSION_MS)
        }
        r.startListening(intent)
    }

    private fun restart(delayMs: Long) {
        if (stopping) return
        restarts++
        main.postDelayed({ listen() }, delayMs)
    }

    private fun results(
        b: Bundle?,
        what: String,
    ) {
        val texts = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val scores = b?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        if (texts.isEmpty() || texts.all { it.isBlank() }) return
        heard++
        val shown = texts.mapIndexed { i, t -> scores?.getOrNull(i)?.takeIf { it >= 0 }?.let { "$t (${"%.2f".format(it)})" } ?: t }
        VoiceTestLog.log("$what: ${shown.joinToString(" | ")}")
    }

    private val listener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onEvent(
                eventType: Int,
                params: Bundle?,
            ) = Unit

            override fun onSegmentResults(segmentResults: Bundle) = results(segmentResults, "heard")

            override fun onEndOfSegmentedSession() {
                VoiceTestLog.log("session ended by Android after ${minutes()} min; restarting")
                restart(300)
            }

            override fun onResults(b: Bundle?) {
                results(b, "heard")
                restart(300)
            }

            override fun onError(error: Int) {
                // Silence is normal: no log line, quick restart.
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (!quiet) {
                    errors++
                    VoiceTestLog.log("error ${errorName(error)}")
                }
                restart(
                    when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 2_000
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> 10_000
                        else -> if (quiet) 300 else 1_000
                    },
                )
            }
        }

    // ---- headset microphone, buttons --------------------------------------------------

    /** The headset's microphone (Bluetooth or wired) as the communication device. */
    private fun routeToHeadset() {
        val am = getSystemService(AudioManager::class.java)
        val devices =
            runCatching { am.availableCommunicationDevices }.getOrElse { e ->
                VoiceTestLog.log("can't list devices: $e")
                emptyList()
            }
        VoiceTestLog.log("communication devices: " + devices.joinToString { "${typeName(it.type)} '${it.productName}'" })
        val headset =
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        if (headset == null) {
            VoiceTestLog.log("no headset microphone found: using the phone's")
            return
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val ok =
            runCatching { am.setCommunicationDevice(headset) }
                .onFailure { VoiceTestLog.log("setCommunicationDevice failed: $it") }
                .getOrDefault(false)
        routed = ok
        val now = am.communicationDevice?.let { typeName(it.type) }
        VoiceTestLog.log("headset mic ${if (ok) "ON" else "refused"}: ${typeName(headset.type)} '${headset.productName}'; now $now")
    }

    /** Headset buttons: logged, so we learn what the Shokz click and the Bose tap send. */
    private fun startMediaSession() {
        val s = MediaSession(this, "HyperShopVoiceTest")
        s.setCallback(
            object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val e = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    if (e?.action == KeyEvent.ACTION_DOWN) VoiceTestLog.log("button: ${KeyEvent.keyCodeToString(e.keyCode)}")
                    return true
                }
            },
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build(),
        )
        s.isActive = true
        session = s
    }

    /** A moment of silence, so Android counts us as the app that last played audio (headset buttons follow it). */
    private fun playSilence() {
        runCatching {
            val rate = 16_000
            val samples = ShortArray(rate / 5)
            val track =
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    ).setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * 2)
                    .build()
            track.write(samples, 0, samples.size)
            track.play()
            main.postDelayed({ track.release() }, 1_000)
        }.onFailure { VoiceTestLog.log("silent clip failed: $it") }
    }

    // ---- state ------------------------------------------------------------------------

    private val heartbeat =
        object : Runnable {
            override fun run() {
                logState("${minutes()} min: $heard heard, $restarts restarts, $errors errors")
                main.postDelayed(this, HEARTBEAT_MS)
            }
        }

    private fun logState(what: String) {
        val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val screen = if (getSystemService(PowerManager::class.java).isInteractive) "on" else "off"
        VoiceTestLog.log("$what · battery $battery% · screen $screen")
    }

    private fun minutes() = (System.currentTimeMillis() - startedAt) / 60_000

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice test", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, VoiceTestService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice test listening")
            .setContentText("Speak commands; the log is in Settings › Voice test.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }
}

internal fun errorName(e: Int): String =
    when (e) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
        else -> "code $e"
    }

private fun typeName(t: Int): String =
    when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth (call)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth (media)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth LE headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        else -> "type $t"
    }
