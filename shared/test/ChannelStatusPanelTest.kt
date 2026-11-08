package com.hanserwei.krill_monitor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ChannelStatusPanelTest {
    @Test
    fun hourlyHistoryUsesWorstKnownStateAndLeavesMissingHoursUnknown() {
        val end = Instant.parse("2026-11-09T00:00:00Z")
        fun point(time: String, status: Int?) = ChannelStatusPoint(Instant.parse(time), status)
        val buckets =
            channelHistoryBuckets(
                listOf(
                    point("2026-11-07T23:59:59Z", 0),
                    point("2026-11-08T00:00:00Z", 1),
                    point("2026-11-08T00:10:00Z", 2),
                    point("2026-11-08T00:20:00Z", 0),
                    point("2026-11-08T01:00:00Z", null),
                    point("2026-11-08T22:59:59Z", 1),
                    point("2026-11-09T00:00:00Z", 2),
                    point("2026-11-09T00:00:01Z", 0),
                ),
                end,
            )
        assertEquals(24, buckets.size)
        assertEquals(0, buckets.first())
        assertNull(buckets[1])
        assertEquals(1, buckets[22])
        assertEquals(2, buckets.last())
        assertEquals(List<Int?>(24) { null }, channelHistoryBuckets(emptyList(), end))
    }

    @Test
    fun unknownStatusesAndInvalidMetricsNeverLookHealthyOrZero() {
        assertEquals("未知", channelStatusText(99))
        assertEquals("未知", channelStatusText(null))
        assertEquals("正常", channelStatusText(1))
        assertEquals("34.2", channelMetricText(34.2418))
        assertEquals("0.0", channelMetricText(0.0))
        assertEquals("—", channelMetricText(null))
        assertEquals("—", channelMetricText(-1.0))
        assertEquals("—", channelMetricText(Double.NaN))
        assertEquals("—", channelMetricText(Double.MAX_VALUE))
    }
}
