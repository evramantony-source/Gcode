package dev.gcode.github.crypto

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date

/**
 * Creates a stable Android debug keystore (PKCS#12, alias androiddebugkey, passwords "android") for the
 * DEBUG_KEYSTORE_BASE64 secret, so CI debug builds keep one signature and can update each other.
 * The BouncyCastle provider is passed as an instance, never by name, to avoid Android's built-in "BC".
 */
object DebugKeystore {
    const val ALIAS = "androiddebugkey"
    const val PASSWORD = "android"

    fun create(random: SecureRandom = SecureRandom(), years: Int = 30): ByteArray {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        val subject = X500Name("CN=Android Debug,O=Android,C=US")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger(64, random).abs().add(BigInteger.ONE),
            Date(now - 60_000), Date(now + years * 365L * 24 * 3600 * 1000), subject, keyPair.public,
        )
        val bc = BouncyCastleProvider()
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(keyPair.private)
        val certificate = JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry(ALIAS, keyPair.private, PASSWORD.toCharArray(), arrayOf(certificate))
        return ByteArrayOutputStream().use { out -> store.store(out, PASSWORD.toCharArray()); out.toByteArray() }
    }
}
