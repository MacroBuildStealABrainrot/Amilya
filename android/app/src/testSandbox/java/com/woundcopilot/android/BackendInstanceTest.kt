package com.woundcopilot.android

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 1: the way the app finds the experimental recorder by name must work with a Kotlin `object` and no hand-made INSTANCE field. */
class BackendInstanceTest {
    private companion object { const val RECORDER_SOURCE = "src/main/java/com/woundcopilot/android/SafeRecorderService.kt" }
    @Test fun anObjectIsFoundByNameThroughItsOwnGeneratedInstanceField() {
        val found = Class.forName("com.woundcopilot.android.FakeBackendObject").getField("INSTANCE").get(null)
        assertSame(FakeBackendObject, found)
        assertTrue(found is FakeBackend)
    }

    @Test fun theSandboxRecorderSourceHasNoHandWrittenInstanceField() {
        val source = moduleFile("sandbox.src", RECORDER_SOURCE).readText()
        assertTrue("no explicit INSTANCE declaration may remain", !Regex("""(val|var)\s+INSTANCE\b""").containsMatchIn(source))
    }

    /**
     * Compile blocker found in review: a class that extends ByteArrayOutputStream must not re-declare size() (the compiler rejects it).
     * The sandbox recorder no longer needs such a class; this keeps the pattern from coming back anywhere in the sandbox sources.
     */
    @Test fun noSandboxClassHidesAnInheritedByteArrayOutputStreamMethod() {
        val folder = moduleFile("sandbox.src", RECORDER_SOURCE).parentFile
        val files = folder.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("sandbox sources must be found", files.size >= 5)
        for (file in files) {
            val text = file.readText()
            for (match in Regex("""class\s+\w+[^{]*:\s*(?:java\.io\.)?ByteArrayOutputStream\b[^{]*\{""").findAll(text)) {
                var depth = 1; var i = match.range.last + 1
                while (depth > 0 && i < text.length) { if (text[i] == '{') depth++ else if (text[i] == '}') depth--; i++ }
                val body = text.substring(match.range.last + 1, i)
                for (name in listOf("size", "reset", "toByteArray", "write", "toString", "writeTo", "close", "flush")) {
                    val declared = Regex("""fun\s+$name\s*\(""").containsMatchIn(body)
                    val overridden = Regex("""override\s+fun\s+$name\s*\(""").containsMatchIn(body)
                    assertTrue("${file.name}: $name() hides an inherited method", !declared || overridden)
                }
            }
        }
    }
}

internal interface FakeBackend
internal object FakeBackendObject : FakeBackend
