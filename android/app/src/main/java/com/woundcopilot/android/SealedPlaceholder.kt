package com.woundcopilot.android

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/*
 * PROTOTYPE (sandbox build only). The rest of the app finds a finished recording by the name of its .m4a file. In the sandbox the
 * sound is never kept as a plain .m4a, so that name points to a small "placeholder" file that holds NO sound at all: only a label
 * saying where the encrypted pieces are. The audio is decrypted in memory when it is played or uploaded.
 */
internal object SealedPlaceholder {
    private const val MAGIC = "WOUNDCOPILOT-SEALED-RECORDING-V1"
    private const val MIN_BYTES = 256
    private const val MAX_BYTES = 4096

    /** Folder name of the encrypted pieces that belong to the recording called [finalName] (e.g. recording-123-abc.m4a). */
    fun sessionName(finalName: String): String = RetentionRules.SESSION_PREFIX + finalName.removeSuffix(".m4a")

    fun write(file: File, sessionName: String) {
        val text = StringBuilder(MAGIC).append('\n')
            .append("This file contains no sound. The recording is stored encrypted in the folder ").append(sessionName).append(".\n")
        while (text.length < MIN_BYTES) text.append(' ')
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary).use { it.write(text.toString().toByteArray(Charsets.US_ASCII)); it.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun isPlaceholder(file: File): Boolean {
        if (!file.isFile || file.length() !in MIN_BYTES.toLong()..MAX_BYTES.toLong()) return false
        return try {
            val head = ByteArray(MAGIC.length)
            FileInputStream(file).use { input -> input.readFullyOrFalse(head, head.size) } && String(head, Charsets.US_ASCII) == MAGIC
        } catch (_: java.io.IOException) { false }
    }
}
