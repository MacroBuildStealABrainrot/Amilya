package com.woundcopilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 2: when the in-memory CSRF token is missing, fetch it once before the first write, and only then. */
class CsrfBootstrapTest {
    private val cookie = "wcp_session=synthetic"
    private val never = Long.MAX_VALUE

    @Test fun fetchesTheTokenBeforeAWriteWhenItIsMissing() =
        assertTrue(needsCsrfBootstrap("", "POST", cookie, "/quick/start", never))

    @Test fun appliesToEveryWritingMethod() {
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            assertTrue(method, needsCsrfBootstrap("", method, cookie, "/encounters/x/audio-parts", never))
        }
    }

    @Test fun neverForReads() =
        assertFalse(needsCsrfBootstrap("", "GET", cookie, "/work", never))

    @Test fun neverWhenATokenIsAlreadyHeld() =
        assertFalse(needsCsrfBootstrap("token", "POST", cookie, "/quick/start", never))

    @Test fun neverWhenSignedOut() =
        assertFalse(needsCsrfBootstrap("", "POST", "", "/quick/start", never))

    @Test fun neverForRoutesThatCreateOrRenewTheSession() {
        for (path in listOf("/auth/login", "/auth/signup", "/auth/refresh", "/auth/session")) {
            assertFalse(path, needsCsrfBootstrap("", "POST", cookie, path, never))
        }
    }

    @Test fun signOutAndLegalAcceptanceDoNeedTheToken() {
        assertTrue(needsCsrfBootstrap("", "POST", cookie, "/auth/logout", never))
        assertTrue(needsCsrfBootstrap("", "POST", cookie, "/auth/accept-legal", never))
    }

    @Test fun retriedAtMostOnceEveryFiveSeconds() {
        assertFalse(needsCsrfBootstrap("", "POST", cookie, "/quick/start", 0L))
        assertFalse(needsCsrfBootstrap("", "POST", cookie, "/quick/start", 4_999L))
        assertTrue(needsCsrfBootstrap("", "POST", cookie, "/quick/start", 5_000L))
    }
}
