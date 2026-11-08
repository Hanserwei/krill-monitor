package com.hanserwei.krill_monitor

import kotlin.test.*
import kotlinx.browser.localStorage

class SessionStoreWasmTest {
    @Test
    fun accountSessionSurvivesANewStoreAndCanBeRemoved() {
        val store = createSessionStore()
        val previous = store.load()
        try {
            assertFalse(store.isSecure)
            assertTrue(store.clear())
            assertNull(store.load())
            assertTrue(store.save("test-account-session"))
            assertEquals("test-account-session", createSessionStore().load())
            assertTrue(store.save("replacement-session"))
            assertEquals("replacement-session", store.load())
            assertTrue(store.clear())
            assertTrue(store.clear())
            assertNull(createSessionStore().load())
        } finally {
            if (previous == null) store.clear() else store.save(previous)
        }
    }

    @Test
    fun migrationDeletesOnlyTheLegacyKeyAndIsIdempotent() {
        val store = createSessionStore()
        val previousSession = store.load()
        val previousKey = localStorage.getItem("krill_monitor_key")
        try {
            assertTrue(store.save("existing-account-session"))
            for (legacy in listOf("legacy-test-key", "corrupt legacy value", "")) {
                localStorage.setItem("krill_monitor_key", legacy)
                assertTrue(store.clearLegacyKey())
                assertNull(localStorage.getItem("krill_monitor_key"))
                assertEquals("existing-account-session", createSessionStore().load())
                assertTrue(store.clearLegacyKey())
            }
            assertTrue(store.clear())
            assertNull(store.load())
        } finally {
            if (previousSession == null) store.clear() else store.save(previousSession)
            if (previousKey == null) localStorage.removeItem("krill_monitor_key")
            else localStorage.setItem("krill_monitor_key", previousKey)
        }
    }
}
