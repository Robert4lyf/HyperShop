package com.robert4lyf.hypershop.watch

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.robert4lyf.hypershop.core.sync.WatchProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * [WatchLink] over Garmin's Connect IQ Mobile SDK (step 7.1), modelled on
 * BangGym's `ConnectIqGarminSync`.
 *
 * The SDK never touches Bluetooth: it binds to Garmin Connect, which owns the
 * link (so no Bluetooth permission). Every call can fail because Garmin Connect
 * is missing, old or killed, so every entry point is wrapped and failures
 * become [state], never a crash. SDK callbacks arrive on the main thread, and
 * sends go out on it too.
 *
 * `WIRELESS` in every build: the APK you install is the debug build, and
 * `TETHERED` (the simulator over adb) would cut it off from a real watch.
 * The SDK's own dialogs are off (`autoUI = false`); Settings › Watch says what
 * is wrong instead ([problem]).
 */
class ConnectIqWatchLink(
    context: Context,
    private val appId: String = WatchProtocol.WATCH_APP_ID,
) : WatchLink {
    private val appContext = context.applicationContext
    private val connectIq: ConnectIQ by lazy { ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS) }
    private val s = MutableStateFlow(WatchLinkState.DISCONNECTED)
    override val state: StateFlow<WatchLinkState> = s

    private val _problem = MutableStateFlow<String?>(null)

    override val problem: StateFlow<String?> = _problem

    @Volatile private var device: IQDevice? = null

    @Volatile private var app: IQApp? = null

    @Volatile private var started = false

    @Volatile private var handler: ((Map<*, *>) -> Unit)? = null

    /**
     * One send in flight: the SDK keeps a single completion listener per app,
     * so a second send before the first is answered steals its answer (BangGym).
     */
    private val sendLock = Mutex()

    override fun onReceive(handler: (Map<*, *>) -> Unit) {
        this.handler = handler
    }

    override fun start() {
        if (started) return
        started = true
        // The SDK builds a Handler on the calling thread's looper.
        if (Looper.myLooper() == Looper.getMainLooper()) init() else Handler(Looper.getMainLooper()).post { init() }
    }

    private fun init() {
        try {
            connectIq.initialize(appContext, false, sdkListener)
        } catch (e: Exception) {
            Timber.w(e, "Connect IQ could not initialise")
            started = false
            unavailable("Garmin Connect isn't available")
        }
    }

    override suspend fun send(payload: Map<String, Any>): Boolean {
        val target = device ?: return false
        val watchApp = app ?: return false
        return sendLock.withLock {
            // Bounded: a Garmin Connect killed mid-send never calls back.
            withTimeoutOrNull(SEND_TIMEOUT_MS) { sendOnMain(target, watchApp, payload) } ?: false
        }
    }

    private suspend fun sendOnMain(
        target: IQDevice,
        watchApp: IQApp,
        payload: Map<String, Any>,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val listener =
                    ConnectIQ.IQSendMessageListener { _, _, status ->
                        if (status != ConnectIQ.IQMessageStatus.SUCCESS) Timber.w("Watch send: %s", status)
                        if (cont.isActive) cont.resume(status == ConnectIQ.IQMessageStatus.SUCCESS)
                    }
                try {
                    connectIq.sendMessage(target, watchApp, payload, listener)
                } catch (e: Exception) {
                    Timber.w(e, "Could not reach the watch")
                    if (cont.isActive) cont.resume(false)
                }
            }
        }

    private fun unavailable(why: String) {
        device = null
        app = null
        _problem.value = why
        s.value = WatchLinkState.NO_SDK
    }

    private val sdkListener =
        object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                _problem.value = null
                attach()
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                Timber.w("Connect IQ unavailable: %s", status)
                started = false
                unavailable(
                    when (status) {
                        ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED -> "Install Garmin Connect to use the watch"
                        ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> "Update Garmin Connect to use the watch"
                        ConnectIQ.IQSdkErrorStatus.SERVICE_ERROR -> "Garmin Connect isn't responding"
                    },
                )
            }

            override fun onSdkShutDown() {
                started = false
                unavailable("Garmin Connect stopped")
            }
        }

    private val deviceListener =
        ConnectIQ.IQDeviceEventListener { changed, status ->
            // Another paired watch coming and going must not drop a working link.
            val current = device
            if (current != null && changed.deviceIdentifier != current.deviceIdentifier &&
                (status != IQDevice.IQDeviceStatus.CONNECTED || app != null)
            ) {
                return@IQDeviceEventListener
            }
            when (status) {
                IQDevice.IQDeviceStatus.CONNECTED -> {
                    device = changed
                    s.value = WatchLinkState.CONNECTED
                    // Re-registers for app events: they do not survive a reconnect.
                    requestApp(changed)
                }
                IQDevice.IQDeviceStatus.NOT_PAIRED -> {
                    device = null
                    app = null
                    _problem.value = "No watch paired in Garmin Connect"
                    s.value = WatchLinkState.DISCONNECTED
                }
                else -> {
                    app = null
                    s.value = WatchLinkState.DISCONNECTED
                }
            }
        }

    private fun attach() {
        val known = devices { connectIq.knownDevices }
        if (known.isEmpty()) {
            _problem.value = "No watch paired in Garmin Connect"
            s.value = WatchLinkState.DISCONNECTED
            return
        }
        for (d in known) {
            try {
                connectIq.registerForDeviceEvents(d, deviceListener)
            } catch (e: Exception) {
                Timber.w(e, "Could not watch device status")
            }
        }
        val target = devices { connectIq.connectedDevices }.firstOrNull()
        if (target == null) {
            s.value = WatchLinkState.DISCONNECTED
            return
        }
        device = target
        s.value = WatchLinkState.CONNECTED
        requestApp(target)
    }

    private fun requestApp(target: IQDevice) {
        val listener =
            object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(info: IQApp) {
                    if (info.status == IQApp.IQAppStatus.NOT_INSTALLED || info.status == IQApp.IQAppStatus.NOT_SUPPORTED) {
                        notInstalled()
                        return
                    }
                    app = info
                    register(target, info)
                    _problem.value = null
                    s.value = WatchLinkState.APP_READY
                }

                override fun onApplicationNotInstalled(applicationId: String) = notInstalled()
            }
        try {
            connectIq.getApplicationInfo(appId, target, listener)
        } catch (e: Exception) {
            Timber.w(e, "Could not query the watch app")
        }
    }

    private fun notInstalled() {
        app = null
        _problem.value = "Install HyperShop on the watch"
        s.value = WatchLinkState.NOT_INSTALLED
    }

    private fun register(
        target: IQDevice,
        watchApp: IQApp,
    ) {
        try {
            connectIq.registerForAppEvents(target, watchApp) { _, _, payload, status ->
                if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                    Timber.w("Inbound watch message dropped: %s", status)
                    return@registerForAppEvents
                }
                // One transfer can carry several objects; only maps are messages.
                // What they contain is checked by WatchCodec, not here.
                val h = handler ?: return@registerForAppEvents
                payload.orEmpty().filterIsInstance<Map<*, *>>().forEach(h)
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not subscribe to watch messages")
        }
    }

    private inline fun devices(list: () -> List<IQDevice>?): List<IQDevice> =
        try {
            list().orEmpty()
        } catch (e: Exception) {
            Timber.w(e, "Could not list devices")
            emptyList()
        }

    private companion object {
        /** A round trip through Garmin Connect takes seconds, never minutes. */
        const val SEND_TIMEOUT_MS = 15_000L
    }
}
