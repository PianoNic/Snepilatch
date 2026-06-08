package ch.snepilatch.app.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Confirms on a real Android runtime that the platform crypto provider supports
 * "Blowfish/CBC/NoPadding" and round-trips a full 2048-byte block with the fixed
 * IV the Deezer scheme uses — the one thing [DeezerDecryptProxy] depends on that
 * the JVM unit tests can't prove. Verified passing on API 35.
 */
@RunWith(AndroidJUnit4::class)
class BlowfishDecryptInstrumentedTest {

    @Test
    fun blowfishCbc_roundTripsA2048Block() {
        val iv = ByteArray(8) { it.toByte() }
        val key = ByteArray(16) { it.toByte() }
        val plain = ByteArray(2048) { (it % 251).toByte() }

        val encrypted = Cipher.getInstance("Blowfish/CBC/NoPadding").also {
            it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(iv))
        }.doFinal(plain)

        // Mirror the proxy: a fresh cipher with the same IV decrypts the block.
        val decrypted = Cipher.getInstance("Blowfish/CBC/NoPadding").also {
            it.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(iv))
        }.doFinal(encrypted)

        assertArrayEquals(plain, decrypted)
    }
}
