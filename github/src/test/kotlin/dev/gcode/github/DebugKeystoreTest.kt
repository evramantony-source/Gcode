package dev.gcode.github

import com.google.common.truth.Truth.assertThat
import dev.gcode.github.crypto.DebugKeystore
import org.junit.Test
import java.security.KeyStore
import java.security.cert.X509Certificate

class DebugKeystoreTest {
    @Test
    fun `creates a PKCS12 debug keystore AGP can use`() {
        val bytes = DebugKeystore.create()
        val store = KeyStore.getInstance("PKCS12").apply { load(bytes.inputStream(), "android".toCharArray()) }
        assertThat(store.containsAlias("androiddebugkey")).isTrue()
        val cert = store.getCertificate("androiddebugkey") as X509Certificate
        assertThat(cert.subjectX500Principal.name).contains("CN=Android Debug")
        assertThat(store.getKey("androiddebugkey", "android".toCharArray()).algorithm).isEqualTo("RSA")
        assertThat(cert.notAfter.time - System.currentTimeMillis()).isGreaterThan(20L * 365 * 24 * 3600 * 1000)
    }
}
