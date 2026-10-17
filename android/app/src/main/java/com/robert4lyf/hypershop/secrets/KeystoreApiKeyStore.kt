package com.robert4lyf.hypershop.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** File `datastore/claude_key.preferences_pb`, excluded from backup (res/xml/backup_rules.xml). */
val Context.claudeKeyDataStore: DataStore<Preferences> by preferencesDataStore(name = "claude_key")

/** Encrypts the key. The real one is AES-GCM in the Android Keystore; tests use a plain JCA key. */
interface KeyCipher {
    /** Returns (iv, ciphertext). */
    fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray>

    /** Throws [GeneralSecurityException] or [ProviderException] when the key can't be used. */
    fun decrypt(
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray

    fun reset()
}

/** AES-256-GCM, not tied to the screen lock (PLAN §2 Secrets). */
class AndroidKeystoreCipher(
    private val alias: String = "hypershop_claude_key",
) : KeyCipher {
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv to c.doFinal(plain)
    }

    override fun decrypt(
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val k = keyStore().getKey(alias, null) as? SecretKey ?: throw GeneralSecurityException("no key")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
        return c.doFinal(data)
    }

    override fun reset() {
        runCatching { keyStore().deleteEntry(alias) }
    }
}

/**
 * The Claude key, encrypted at rest (PLAN §3.7). Decrypted once and kept in
 * memory until "Remove key". When the Keystore can't decrypt it
 * (KeyPermanentlyInvalidatedException, AEADBadTagException, ProviderException)
 * the key is wiped, Claude switches off, and [onLost] raises the one-time notice.
 */
class KeystoreApiKeyStore(
    private val store: DataStore<Preferences>,
    private val cipher: KeyCipher,
    scope: CoroutineScope,
    private val onLost: suspend () -> Unit,
) : ApiKeyStore {
    private val has = MutableStateFlow(false)
    override val hasKey: StateFlow<Boolean> = has
    private val lock = Mutex()
    private var cached: String? = null

    init {
        scope.launch { has.value = store.data.first()[BLOB] != null }
    }

    override suspend fun get(): String? =
        lock.withLock {
            cached?.let { return it }
            val blob = store.data.first()[BLOB] ?: return null
            try {
                val (iv, data) = blob.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
                String(cipher.decrypt(iv, data), Charsets.UTF_8).also {
                    cached = it
                    has.value = true
                }
            } catch (e: GeneralSecurityException) {
                lost()
            } catch (e: ProviderException) {
                lost()
            } catch (e: IllegalArgumentException) {
                lost()
            } catch (e: IndexOutOfBoundsException) {
                lost()
            }
        }

    private suspend fun lost(): String? {
        wipe()
        onLost()
        return null
    }

    override suspend fun set(key: String) =
        lock.withLock {
            val (iv, data) = cipher.encrypt(key.trim().toByteArray(Charsets.UTF_8))
            val blob = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
            store.edit { it[BLOB] = blob }
            cached = key.trim()
            has.value = true
        }

    override suspend fun clear() = lock.withLock { wipe() }

    private suspend fun wipe() {
        cached = null
        store.edit { it.remove(BLOB) }
        cipher.reset()
        has.value = false
    }

    private companion object {
        val BLOB = stringPreferencesKey("blob")
    }
}
