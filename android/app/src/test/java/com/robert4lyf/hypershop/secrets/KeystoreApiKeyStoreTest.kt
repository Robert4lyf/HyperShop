package com.robert4lyf.hypershop.secrets

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Robolectric has no AndroidKeyStore; the same AES-GCM with a plain JCA key stands in. */
private class JcaCipher : KeyCipher {
    var key: SecretKey = gen()
    var failWith: Exception? = null

    private fun gen() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv to c.doFinal(plain)
    }

    override fun decrypt(
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray {
        failWith?.let { throw it }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return c.doFinal(data)
    }

    override fun reset() {
        key = gen()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class KeystoreApiKeyStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.cacheDir, "k-${System.nanoTime()}/claude_key.preferences_pb")
    private val ds = PreferenceDataStoreFactory.create { file }
    private var lost = 0

    private fun store(cipher: KeyCipher) = KeystoreApiKeyStore(ds, cipher, CoroutineScope(Dispatchers.IO), onLost = { lost++ })

    private val secret = "test-key-0123456789"

    @Test
    fun encryptedAtRestAndReadBack() =
        runBlocking {
            val cipher = JcaCipher()
            store(cipher).set(" $secret ")
            assertFalse("not stored in clear", file.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
            // A fresh instance (a new process) decrypts it.
            val again = store(cipher)
            assertEquals(secret, again.get())
            assertTrue(again.hasKey.value)
            again.clear()
            assertNull(again.get())
            assertFalse(again.hasKey.value)
            assertNull(store(cipher).get())
        }

    @Test
    fun undecryptableKeyIsWipedWithOneNotice() =
        runBlocking {
            for (failure in listOf(AEADBadTagException(), ProviderException("keystore"))) {
                val cipher = JcaCipher()
                store(cipher).set(secret)
                cipher.failWith = failure
                val s = store(cipher)
                assertNull(s.get())
                assertFalse(s.hasKey.value)
                // Wiped: asking again doesn't raise the notice twice.
                assertNull(s.get())
            }
            assertEquals(2, lost)
        }

    @Test
    fun missingAfterRestoreIsJustNoKey() =
        runBlocking {
            val s = store(JcaCipher())
            assertNull(s.get())
            assertEquals(0, lost)
        }
}
