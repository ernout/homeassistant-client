package com.thelightphone.homeassistant

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts credentials at rest with a hardware-backed AES key.
 *
 * A long-lived Home Assistant token is a key to the house: it can unlock doors
 * and disarm alarms, and it stays valid for years. The key never leaves the
 * Android Keystore, so the stored blob is useless if the file is copied off
 * the device — through a backup, a debug bridge, or the storage itself.
 *
 * The key deliberately does not require user authentication: background
 * reporting has to work while the screen is locked.
 */
class SecretVault(private val keyAlias: String = KEY_ALIAS) {

    fun encrypt(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray())
        // Prefix the IV; GCM needs a fresh one per message and it isn't secret.
        Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }.getOrNull()

    fun decrypt(payload: String): String? = runCatching {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        if (bytes.size <= IV_LENGTH) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, bytes, 0, IV_LENGTH),
        )
        String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH))
    }.getOrNull()

    /** Drops the key, which makes every stored secret permanently unreadable. */
    fun destroy() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "ha_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
