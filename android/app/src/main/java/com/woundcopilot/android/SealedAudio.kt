package com.woundcopilot.android

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * PROTOTYPE (sandbox build only). Application-level encryption for the recording pieces.
 *
 * Plain-words design (full text: AUDIO-ENCRYPTION-DESIGN.md):
 *  - Each recording gets its own random 256-bit key, made when the recording starts and held only in memory.
 *  - That key is "wrapped" (locked) with a key that lives inside the Android Keystore and never leaves it. The locked
 *    copy is saved in the session settings BEFORE the first piece of sound is written.
 *  - Every sound frame is encrypted and signed on its own, together with its place in the recording (segment number and
 *    running frame number). A frame cannot be changed, moved, repeated or silently dropped without being noticed.
 *  - While recording, only the in-memory key is used. The Keystore is not needed again until recovery, so recording
 *    carries on with the screen locked.
 *  - Nothing decrypted is ever written to a temporary file by this code (see the Android glue for the final step).
 */

internal class SessionKey(val key: ByteArray, val noncePrefix: ByteArray, val sessionId: ByteArray) {
    init { require(key.size == 32 && noncePrefix.size == 4 && sessionId.size == 16) }
    fun serialize(): ByteArray = key + noncePrefix + sessionId
    /** Best effort: wipe the key from memory when the session is finished. */
    fun wipe() { key.fill(0); noncePrefix.fill(0); sessionId.fill(0) }
    companion object {
        fun generate(random: SecureRandom = SecureRandom()) = SessionKey(
            ByteArray(32).also(random::nextBytes), ByteArray(4).also(random::nextBytes), ByteArray(16).also(random::nextBytes),
        )
        fun parse(bytes: ByteArray): SessionKey {
            require(bytes.size == 52) { "Unexpected key blob" }
            return SessionKey(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 36), bytes.copyOfRange(36, 52))
        }
    }
}

/** Locks and unlocks the small per-recording key. On the phone this is backed by the Android Keystore. */
internal interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/** One encrypted, signed record per AAC frame:  [u16 length][u64 frame number][encrypted sound + 16-byte signature]. */
internal class SealedCodec(private val session: SessionKey) : FrameCodec {
    override val indexed = true
    private val spec = SecretKeySpec(session.key, "AES")

    private fun cipher(mode: Int, segment: Int, index: Long): Cipher {
        val nonce = ByteBuffer.allocate(12).put(session.noncePrefix).putLong(index).array()
        val aad = ByteBuffer.allocate(16 + 4 + 8).put(session.sessionId).putInt(segment).putLong(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, spec, GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
    }

    override fun encode(segment: Int, index: Long, payload: ByteArray, length: Int): ByteArray {
        require(length in 1..MAX_PAYLOAD)
        val sealed = cipher(Cipher.ENCRYPT_MODE, segment, index).doFinal(payload, 0, length)
        return ByteBuffer.allocate(HEADER + sealed.size).putShort(length.toShort()).putLong(index).put(sealed).array()
    }

    override fun read(input: InputStream, segment: Int, consumed: IntArray): FrameRecord? {
        val head = ByteArray(HEADER)
        if (!input.readFullyOrFalse(head, HEADER)) return null
        val buffer = ByteBuffer.wrap(head)
        val length = buffer.short.toInt() and 0xFFFF
        val index = buffer.long
        if (length !in 1..MAX_PAYLOAD || index < 0) return null
        val sealed = ByteArray(length + TAG)
        if (!input.readFullyOrFalse(sealed, sealed.size)) return null
        return try {
            val plain = cipher(Cipher.DECRYPT_MODE, segment, index).doFinal(sealed)
            consumed[0] = HEADER + sealed.size
            FrameRecord(index, plain)
        } catch (_: GeneralSecurityException) {
            null // changed, moved or damaged record: treated exactly like the end of the usable data
        }
    }

    companion object {
        const val HEADER = 10
        const val TAG = 16
        const val MAX_PAYLOAD = 8184
    }
}

internal object SealedSession {
    const val FORMAT = "sealed-v1"

    /**
     * Starts a sealed session: makes the key, locks it, and saves the locked copy atomically. Only after this returns
     * may the first sound frame be written, so a kill can never leave encrypted sound without its key.
     */
    fun begin(directory: File, wrapper: KeyWrapper, settings: Map<String, String>): SessionKey {
        val key = SessionKey.generate()
        val plain = key.serialize() // a second copy of the key; wiped below whatever happens
        try {
            val locked = wrapper.wrap(plain)
            SessionInfo(directory).write(settings + mapOf("format" to FORMAT, "keyBlob" to Base64.getEncoder().encodeToString(locked)))
        } catch (e: Throwable) {
            key.wipe() // recording must not start; no usable key is left in memory
            throw e
        } finally {
            plain.fill(0)
        }
        return key
    }

    /** Recovers the key of an earlier session (for example after the app was killed). Null if it cannot be unlocked. */
    fun open(info: Map<String, String>, wrapper: KeyWrapper): SessionKey? {
        val blob = info["keyBlob"] ?: return null
        var plain: ByteArray? = null
        return try {
            plain = wrapper.unwrap(Base64.getDecoder().decode(blob))
            SessionKey.parse(plain) // copies the parts, so the unlocked bytes can be wiped straight away
        } catch (_: Exception) { null } finally { plain?.fill(0) }
    }

    fun isSealed(info: Map<String, String>) = info["format"] == FORMAT
}
