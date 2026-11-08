package com.hanserwei.krill_monitor

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformWasmJsTest {
    @Test
    fun `platform name is reported`() {
        assertTrue(getPlatform().name.isNotBlank())
    }
}
