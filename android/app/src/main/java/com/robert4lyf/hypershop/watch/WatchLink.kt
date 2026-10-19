package com.robert4lyf.hypershop.watch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class WatchLinkState { NO_SDK, NOT_INSTALLED, DISCONNECTED, CONNECTED, APP_READY }

/**
 * The phone's side of the Connect IQ link (Run 2: `WatchSyncImpl`, modelled on
 * BangGym's `ConnectIqGarminSync`). App-scoped; no permanent service.
 */
interface WatchLink {
    val state: StateFlow<WatchLinkState>

    /** Why the link is down, in words for Settings, or null. */
    val problem: StateFlow<String?> get() = NO_PROBLEM

    fun start()

    /**
     * Tries again from wherever the link stopped (the SDK, the watch, the
     * app): Settings › Watch's "Try again", safe to call at any time.
     */
    fun retry() = start()

    /** Sends one payload (a snapshot page). Only Connect IQ types inside. */
    suspend fun send(payload: Map<String, Any>): Boolean

    /** Inbound watch payloads (untrusted; decoded by WatchCodec). */
    fun onReceive(handler: (Map<*, *>) -> Unit)
}

private val NO_PROBLEM: StateFlow<String?> = MutableStateFlow(null)

/** Run 1: no watch. */
object NoWatchLink : WatchLink {
    override val state: StateFlow<WatchLinkState> = MutableStateFlow(WatchLinkState.NO_SDK)

    override fun start() = Unit

    override suspend fun send(payload: Map<String, Any>) = false

    override fun onReceive(handler: (Map<*, *>) -> Unit) = Unit
}

/** A watch in memory: records what the phone sent and lets a test or the debug screen talk back. */
class FakeWatchLink : WatchLink {
    private val s = MutableStateFlow(WatchLinkState.APP_READY)
    override val state: StateFlow<WatchLinkState> = s

    /** Safe to read while the coordinator's scope is still sending. */
    val sent: MutableList<Map<String, Any>> = java.util.concurrent.CopyOnWriteArrayList()
    private var handler: ((Map<*, *>) -> Unit)? = null

    override fun start() = Unit

    override suspend fun send(payload: Map<String, Any>): Boolean {
        sent += payload
        return true
    }

    override fun onReceive(handler: (Map<*, *>) -> Unit) {
        this.handler = handler
    }

    fun deliver(payload: Map<*, *>) = handler?.invoke(payload)

    fun setState(state: WatchLinkState) {
        s.value = state
    }
}
