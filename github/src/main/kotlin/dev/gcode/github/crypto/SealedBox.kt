package dev.gcode.github.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * libsodium `crypto_box_seal` (X25519 + XSalsa20-Poly1305, nonce = BLAKE2b-192(ephemeral_pk || recipient_pk)),
 * as GitHub requires for Actions secrets ("Encrypting secrets for the REST API"). Built from BouncyCastle
 * primitives; verified against a libsodium (PyNaCl) test vector.
 */
object SealedBox {
    private const val KEY = 32
    private const val MAC = 16
    private const val NONCE = 24

    fun seal(message: ByteArray, recipientPublicKey: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        require(recipientPublicKey.size == KEY) { "Public key must be 32 bytes" }
        val ephemeralSecret = ByteArray(KEY).also(random::nextBytes)
        val ephemeralPublic = publicKey(ephemeralSecret)
        val nonce = nonce(ephemeralPublic, recipientPublicKey)
        val key = boxKey(ephemeralSecret, recipientPublicKey)
        return ephemeralPublic + secretbox(message, nonce, key)
    }

    fun open(sealed: ByteArray, recipientSecretKey: ByteArray): ByteArray {
        require(sealed.size >= KEY + MAC) { "Sealed box too short" }
        val ephemeralPublic = sealed.copyOfRange(0, KEY)
        val recipientPublic = publicKey(recipientSecretKey)
        val nonce = nonce(ephemeralPublic, recipientPublic)
        val key = boxKey(recipientSecretKey, ephemeralPublic)
        val mac = sealed.copyOfRange(KEY, KEY + MAC)
        val cipher = sealed.copyOfRange(KEY + MAC, sealed.size)
        val engine = XSalsa20Engine().apply { init(true, ParametersWithIV(KeyParameter(key), nonce)) }
        val polyKey = ByteArray(KEY).also { engine.processBytes(ByteArray(KEY), 0, KEY, it, 0) }
        if (!MessageDigest.isEqual(poly1305(polyKey, cipher), mac)) throw IllegalArgumentException("Sealed box authentication failed")
        return ByteArray(cipher.size).also { engine.processBytes(cipher, 0, cipher.size, it, 0) }
    }

    fun publicKey(secretKey: ByteArray): ByteArray = ByteArray(KEY).also { X25519.scalarMultBase(secretKey, 0, it, 0) }

    /** crypto_box_beforenm: HSalsa20 of the X25519 shared secret with a zero nonce. */
    private fun boxKey(secret: ByteArray, public: ByteArray): ByteArray {
        val shared = ByteArray(KEY)
        X25519.scalarMult(secret, 0, public, 0, shared, 0)
        return hsalsa20(shared, ByteArray(16))
    }

    /** crypto_secretbox_xsalsa20poly1305: returns mac || ciphertext. */
    private fun secretbox(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val engine = XSalsa20Engine().apply { init(true, ParametersWithIV(KeyParameter(key), nonce)) }
        val polyKey = ByteArray(KEY).also { engine.processBytes(ByteArray(KEY), 0, KEY, it, 0) }
        val cipher = ByteArray(message.size).also { engine.processBytes(message, 0, message.size, it, 0) }
        return poly1305(polyKey, cipher) + cipher
    }

    private fun poly1305(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Poly1305().apply { init(KeyParameter(key)) }
        mac.update(data, 0, data.size)
        return ByteArray(MAC).also { mac.doFinal(it, 0) }
    }

    private fun nonce(ephemeralPublic: ByteArray, recipientPublic: ByteArray): ByteArray {
        val digest = Blake2bDigest(NONCE * 8)
        digest.update(ephemeralPublic, 0, KEY)
        digest.update(recipientPublic, 0, KEY)
        return ByteArray(NONCE).also { digest.doFinal(it, 0) }
    }

    internal fun hsalsa20(key: ByteArray, nonce16: ByteArray): ByteArray {
        fun le(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        val x = IntArray(16)
        x[0] = 0x61707865; x[5] = 0x3320646e; x[10] = 0x79622d32; x[15] = 0x6b206574
        for (i in 0 until 4) {
            x[1 + i] = le(key, i * 4)
            x[11 + i] = le(key, 16 + i * 4)
            x[6 + i] = le(nonce16, i * 4)
        }
        fun r(a: Int, n: Int) = (a shl n) or (a ushr (32 - n))
        repeat(10) {
            x[4] = x[4] xor r(x[0] + x[12], 7); x[8] = x[8] xor r(x[4] + x[0], 9)
            x[12] = x[12] xor r(x[8] + x[4], 13); x[0] = x[0] xor r(x[12] + x[8], 18)
            x[9] = x[9] xor r(x[5] + x[1], 7); x[13] = x[13] xor r(x[9] + x[5], 9)
            x[1] = x[1] xor r(x[13] + x[9], 13); x[5] = x[5] xor r(x[1] + x[13], 18)
            x[14] = x[14] xor r(x[10] + x[6], 7); x[2] = x[2] xor r(x[14] + x[10], 9)
            x[6] = x[6] xor r(x[2] + x[14], 13); x[10] = x[10] xor r(x[6] + x[2], 18)
            x[3] = x[3] xor r(x[15] + x[11], 7); x[7] = x[7] xor r(x[3] + x[15], 9)
            x[11] = x[11] xor r(x[7] + x[3], 13); x[15] = x[15] xor r(x[11] + x[7], 18)
            x[1] = x[1] xor r(x[0] + x[3], 7); x[2] = x[2] xor r(x[1] + x[0], 9)
            x[3] = x[3] xor r(x[2] + x[1], 13); x[0] = x[0] xor r(x[3] + x[2], 18)
            x[6] = x[6] xor r(x[5] + x[4], 7); x[7] = x[7] xor r(x[6] + x[5], 9)
            x[4] = x[4] xor r(x[7] + x[6], 13); x[5] = x[5] xor r(x[4] + x[7], 18)
            x[11] = x[11] xor r(x[10] + x[9], 7); x[8] = x[8] xor r(x[11] + x[10], 9)
            x[9] = x[9] xor r(x[8] + x[11], 13); x[10] = x[10] xor r(x[9] + x[8], 18)
            x[12] = x[12] xor r(x[15] + x[14], 7); x[13] = x[13] xor r(x[12] + x[15], 9)
            x[14] = x[14] xor r(x[13] + x[12], 13); x[15] = x[15] xor r(x[14] + x[13], 18)
        }
        val out = ByteArray(32)
        intArrayOf(x[0], x[5], x[10], x[15], x[6], x[7], x[8], x[9]).forEachIndexed { i, v ->
            out[i * 4] = v.toByte(); out[i * 4 + 1] = (v ushr 8).toByte(); out[i * 4 + 2] = (v ushr 16).toByte(); out[i * 4 + 3] = (v ushr 24).toByte()
        }
        return out
    }
}
