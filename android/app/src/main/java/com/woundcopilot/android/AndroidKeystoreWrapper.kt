package com.woundcopilot.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Locks the small per-recording key with a key that lives inside the Android Keystore and never leaves it.
 *
 * Deliberately NOT tied to the screen lock (no setUserAuthenticationRequired, no setUnlockedDeviceRequired): the key
 * must be usable while the phone is locked, because a visit recording must not stop when the screen turns off, and
 * the per-recording key is only wrapped once, at the start. The Keystore is not touched again while recording.
 * Sandbox only. NOT compiled in the environment that wrote it (no Android SDK).
 */
internal class AndroidKeystoreWrapper(private val alias: String = "wcp.audio.kek.sandbox.v1") : KeyWrapper {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > 12 + 16) { "Wrapped key is too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, wrapped, 0, 12)) }
        return cipher.doFinal(wrapped, 12, wrapped.size - 12)
    }
}
