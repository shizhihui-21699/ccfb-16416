package com.privateime.clipboard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption with a key held in the Android Keystore.
 *
 * The clipboard history is the second-most dangerous store in the product after
 * the learned dictionary: it contains exactly the strings users copy in a hurry,
 * which includes one-time codes. Storing it in cleartext would recreate the
 * original bug with a different storage engine, so history is encrypted at rest.
 *
 * The key is generated on first use, never leaves the Keystore, and is not
 * exportable. Losing it (factory reset, keystore reset) simply loses the history,
 * which is the desired failure mode.
 *
 * Implemented directly on the Keystore rather than via a library so the product
 * has no third-party crypto dependency to audit.
 */
internal object KeystoreCrypto {

    private const val KEY_ALIAS = "private_ime_clipboard_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /**
     * @return base64(iv || ciphertext), or null when the Keystore is unavailable
     *   (some emulators and heavily modified ROMs). Callers must treat null as
     *   "history is unavailable", never as "store it in the clear".
     */
    fun encrypt(plaintext: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        Base64.encodeToString(combined, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    /** @return null when the blob is corrupt or the key has changed. */
    fun decrypt(encoded: String): String? = try {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size <= IV_BYTES) {
            null
        } else {
            val iv = combined.copyOfRange(0, IV_BYTES)
            val ciphertext = combined.copyOfRange(IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    /** Wipes the key, which makes every stored blob permanently unreadable. */
    fun destroyKey() {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // Nothing to destroy, or the Keystore is unavailable.
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not requiring user authentication: the IME runs while
                // the screen is locked (quick replies) and must not be blocked.
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
}
