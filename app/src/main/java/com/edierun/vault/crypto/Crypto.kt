package com.edierun.vault.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitivas de cifra. Tudo em AES-256-GCM; o PIN passa por PBKDF2-SHA256.
 */
object Crypto {
    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    const val TAG_LEN = 16
    const val SALT_LEN = 16
    private const val PBKDF2_ITERATIONS = 210_000

    val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun hex(bytes: ByteArray): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(chars[v ushr 4]).append(chars[v and 0x0f])
        }
        return sb.toString()
    }

    fun deriveKey(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LEN * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Cifra com nonce aleatório. Resultado: nonce(12) + cifrado + tag(16). */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = randomBytes(NONCE_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
        if (aad != null) c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }

    /** Decifra o que [seal] produziu. Lança excepção se a chave estiver errada ou os dados alterados. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        require(sealed.size >= NONCE_LEN + TAG_LEN) { "dados cifrados curtos demais" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, sealed, 0, NONCE_LEN))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(sealed, NONCE_LEN, sealed.size - NONCE_LEN)
    }
}
