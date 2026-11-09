package com.hanserwei.krill_monitor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MonitorNavigationWasmTest {
    @Test
    fun hashParsingLeavesValidationToTheApp() {
        assertNull(monitorRouteFromHash(""))
        assertNull(monitorRouteFromHash("#"))
        assertEquals("usage", monitorRouteFromHash("#usage"))
        assertEquals("unknown", monitorRouteFromHash("#unknown"))
    }

    @Test
    fun historyNormalizesOnceAndDoesNotPushOnBrowserNavigation() {
        assertEquals(MonitorHistoryAction.Replace, monitorHistoryAction("#unknown", "overview", true))
        assertEquals(MonitorHistoryAction.Replace, monitorHistoryAction("", "overview", true))
        assertEquals(MonitorHistoryAction.None, monitorHistoryAction("#usage", "usage", true))
        assertEquals(MonitorHistoryAction.Push, monitorHistoryAction("#overview", "usage", false))
        assertEquals(MonitorHistoryAction.None, monitorHistoryAction("#overview", "overview", false))
    }
}
