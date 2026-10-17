package com.robert4lyf.hypershop.secrets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the Claude key lives. The real one (Step 5.8) encrypts it with the Android Keystore. */
interface ApiKeyStore {
    /** True when a key is stored (it may still turn out to be wrong). */
    val hasKey: StateFlow<Boolean>

    /** The key, or null when none is stored or it could not be decrypted. */
    suspend fun get(): String?

    /** False when the key could not be stored (the keystore failed); nothing changes then. */
    suspend fun set(key: String): Boolean

    /** "Remove key": also clears it from memory. */
    suspend fun clear()
}

/** For tests and previews. */
class InMemoryApiKeyStore(
    initial: String? = null,
) : ApiKeyStore {
    private var key: String? = initial
    private val has = MutableStateFlow(initial != null)
    override val hasKey: StateFlow<Boolean> = has

    override suspend fun get(): String? = key

    override suspend fun set(key: String): Boolean {
        this.key = key
        has.value = true
        return true
    }

    override suspend fun clear() {
        key = null
        has.value = false
    }
}
