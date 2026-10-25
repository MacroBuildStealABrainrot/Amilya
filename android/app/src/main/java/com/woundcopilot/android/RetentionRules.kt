package com.woundcopilot.android

import java.io.File
import java.io.FileInputStream
import java.util.Properties

/**
 * Rules for what the 24-hour / 7-day clean-up must NEVER delete. Plain logic with no Android code, so it is tested on a PC.
 * It only ever says "keep". The existing age limits (24 hours, 7 days) are not changed here.
 */
internal object RetentionRules {
    /** Folder name prefix of an experimental crash-resistant recording session inside `recordings/` (sandbox build only). */
    const val SESSION_PREFIX = "safe-"

    /**
     * True when [file] must be kept no matter how old it is:
     *  - it is an unfinished (or unreadable) crash-resistant recording session, or one whose finished recording or
     *    recovery note still exists, because those are the only copy of audio that may not be uploaded yet;
     *  - it is the recovery note (`*.m4a.json`) of a recording that is still protected or still waiting to be sent,
     *    because without the note the app can no longer offer that recording for recovery.
     */
    fun mustKeep(
        file: File, recordingsRoot: File, protectedPaths: Set<String>, pendingPaths: Set<String>,
        retainUnresolved: Boolean = false,
    ): Boolean {
        if (file.name.endsWith(".m4a.json")) {
            val media = File(file.path.removeSuffix(".json")).canonicalPath
            if (media in protectedPaths || media in pendingPaths) return true
        }
        if (retainUnresolved && keepBecauseUnresolved(file, protectedPaths, pendingPaths)) return true
        val session = sessionFolderOf(file, recordingsRoot) ?: return false
        return sessionStillNeeded(session, recordingsRoot)
    }

    /**
     * Sandbox rule ("no automatic deletion while unresolved"): a recording is unresolved from the moment it is made until the app
     * itself removes its recovery note (after the report is saved, or when the clinician explicitly discards it). While unresolved,
     * neither the sound, the note, nor any temporary upload file is deleted because of age, however old it is.
     */
    fun keepBecauseUnresolved(file: File, protectedPaths: Set<String>, pendingPaths: Set<String>): Boolean {
        val path = file.canonicalPath
        if (path in protectedPaths || path in pendingPaths) return true
        val media = when {
            file.name.endsWith(".m4a.json") -> File(file.path.removeSuffix(".json"))
            file.extension == "m4a" -> file
            else -> return false
        }
        return File(media.path + ".json").exists()
    }

    /**
     * The one place that decides whether the clean-up may delete [file]. With [retainUnresolved] false and no crash-resistant
     * sessions this gives exactly the answer the original clean-up gave (a test compares the two on thousands of random files).
     */
    fun shouldDelete(
        file: File, recordingsRoot: File, now: Long, mediaAgeMillis: Long, recoveryAgeMillis: Long,
        protectedPaths: Set<String>, pendingPaths: Set<String>, retainUnresolved: Boolean = false,
    ): Boolean {
        if (mustKeep(file, recordingsRoot, protectedPaths, pendingPaths, retainUnresolved)) return false
        val age = if (file.canonicalPath in pendingPaths || file.extension == "json" || File(file.path + ".json").exists()) recoveryAgeMillis else mediaAgeMillis
        return file.canonicalPath !in protectedPaths && file.canonicalPath.startsWith(recordingsRoot.path + File.separator) && now - file.lastModified() > age
    }

    /** The `safe-*` folder directly inside [recordingsRoot] that contains [file], or null. */
    fun sessionFolderOf(file: File, recordingsRoot: File): File? {
        var current: File? = file.canonicalFile.parentFile
        val root = recordingsRoot.canonicalFile
        while (current != null) {
            val parent = current.parentFile
            if (parent == root) return if (current.name.startsWith(SESSION_PREFIX)) current else null
            current = parent
        }
        return null
    }

    /** A session is still needed unless it finished AND both its recording file and recovery note are already gone. */
    fun sessionStillNeeded(session: File, recordingsRoot: File): Boolean {
        val settings = File(session, "session.properties")
        val values = try {
            if (!settings.isFile) return true
            Properties().also { p -> FileInputStream(settings).use { p.load(it) } }
        } catch (_: Exception) { return true }
        if (values.getProperty("status") != "finished") return true
        val name = values.getProperty("finalName") ?: return true
        if (name.contains('/') || name.contains('\\')) return true
        return File(recordingsRoot, name).exists() || File(recordingsRoot, "$name.json").exists()
    }
}
