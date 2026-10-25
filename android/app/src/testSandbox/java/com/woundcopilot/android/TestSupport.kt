package com.woundcopilot.android

import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Resolves checked-in test fixtures and source relative to Gradle's app module, with optional override. */
internal fun moduleFile(property: String, relativeToModule: String): File =
    System.getProperty(property)?.takeIf { it.isNotBlank() }?.let(::File) ?: File(relativeToModule)

/** Stand-in for the Android Keystore on a PC: locks small keys with a fixed test key. NOT used on the phone. */
internal class TestKeyWrapper(private val kek: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }) : KeyWrapper {
    override fun wrap(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(plain)
    }
    override fun unwrap(wrapped: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, wrapped.copyOfRange(0, 12))) }
        return c.doFinal(wrapped, 12, wrapped.size - 12)
    }
}

/** Splits a genuine ADTS file (made by a real AAC encoder) into its frames' raw sound payloads. */
internal object AdtsFile {
    fun payloads(bytes: ByteArray, sampleRate: Int, channels: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var position = 0
        while (position < bytes.size) {
            val length = Adts.frameLength(bytes, position, bytes.size - position, sampleRate, channels)
            require(length > 0 && position + length <= bytes.size) { "Not a valid ADTS frame at $position" }
            out += bytes.copyOfRange(position + Adts.HEADER_BYTES, position + length)
            position += length
        }
        return out
    }
}
