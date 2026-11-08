package com.hanserwei.krill_monitor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyStoreWasmTest {
    @Test
    fun savedKeySurvivesANewStoreAndCanBeRemoved() {
        // 测试运行在独立浏览器来源；仍恢复旧值，避免覆盖已有测试数据。
        val store = createMonitorKeyStore()
        val previous = store.load()
        try {
            assertFalse(store.isSecure)
            assertTrue(store.clear())
            assertNull(store.load())
            assertTrue(store.save("test-only-key"))
            assertEquals("test-only-key", createMonitorKeyStore().load())
            assertTrue(store.save("replacement-test-key"))
            assertEquals("replacement-test-key", store.load())
            assertTrue(store.clear())
            assertTrue(store.clear())
            assertNull(createMonitorKeyStore().load())
        } finally {
            if (previous == null) store.clear() else store.save(previous)
        }
    }
}
