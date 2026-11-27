package dev.gcode.github

import com.google.common.truth.Truth.assertThat
import dev.gcode.github.crypto.SealedBox
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class SealedBoxTest {
    private val b64 = Base64.getDecoder()

    // Generated with PyNaCl (libsodium): SealedBox(PublicKey).encrypt(b"GCode secret value").
    private val secretKey = b64.decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
    private val publicKey = b64.decode("j0DFrbaPJWJK5bIU6nZ6bslNgp09e14a0bpvPiE4KF8=")
    private val sealedByLibsodium = b64.decode("dD6xOjDfwSlR9gqbp1GJaCojELYe6/SOE9B19Uv5ZzK7YYWEfrqaiAor+yJ8qZjczIo1Tssovid635QisqCl2TF6")

    @Test
    fun `derives the libsodium public key`() {
        assertThat(SealedBox.publicKey(secretKey)).isEqualTo(publicKey)
    }

    @Test
    fun `opens a box sealed by libsodium`() {
        assertThat(String(SealedBox.open(sealedByLibsodium, secretKey))).isEqualTo("GCode secret value")
    }

    @Test
    fun `seal round trips and has the libsodium layout`() {
        val sealed = SealedBox.seal("token-123".toByteArray(), publicKey)
        assertThat(sealed.size).isEqualTo(32 + 16 + 9)
        assertThat(String(SealedBox.open(sealed, secretKey))).isEqualTo("token-123")
    }

    @Test
    fun `tampering is detected`() {
        val sealed = SealedBox.seal("x".toByteArray(), publicKey)
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { SealedBox.open(sealed, secretKey) }
    }
}
