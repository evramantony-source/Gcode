package dev.gcode.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import dev.gcode.core.data.secret.EncryptedSecretStore
import dev.gcode.core.data.secret.SecretCipher
import dev.gcode.core.data.secret.SecretKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class EncryptedSecretStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Same AES-GCM layout as the Keystore cipher, with an in-memory key (no Keystore on the JVM). */
    private class JvmCipher : SecretCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return iv + c.doFinal(plain)
        }
        override fun decrypt(sealed: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
            return c.doFinal(sealed, 12, sealed.size - 12)
        }
    }

    @Test
    fun `stores only ciphertext and round trips values`() = runTest(UnconfinedTestDispatcher()) {
        val file = tmp.newFile("secrets.preferences_pb").also { it.delete() }
        val ds = PreferenceDataStoreFactory.create(scope = TestScope(UnconfinedTestDispatcher(testScheduler)).backgroundScope) { file }
        val store = EncryptedSecretStore(ds, JvmCipher())
        assertThat(store.get(SecretKey.GEMINI_API_KEY)).isNull()
        assertThat(store.observePresent(SecretKey.GEMINI_API_KEY).first()).isFalse()

        store.put(SecretKey.GEMINI_API_KEY, "AIza-test-value-123")
        assertThat(store.get(SecretKey.GEMINI_API_KEY)).isEqualTo("AIza-test-value-123")
        assertThat(store.observePresent(SecretKey.GEMINI_API_KEY).first()).isTrue()
        assertThat(file.readBytes().toString(Charsets.ISO_8859_1)).doesNotContain("AIza-test-value-123")

        store.remove(SecretKey.GEMINI_API_KEY)
        assertThat(store.get(SecretKey.GEMINI_API_KEY)).isNull()
    }
}
