package com.hanserwei.krill_monitor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class MonitorFormatTest {
    @Test
    fun moneyIsRoundedAndGroupedForDisplay() {
        assertEquals("$0.00", usdText(0.0))
        assertEquals("$1,234.57", usdText(1234.567))
        assertEquals("$2.50", usdText(250.0 / 100))
        assertEquals("$1.02", usdText(1.015))
        assertEquals("$1.01", usdText(1.005))
        assertEquals("$2.68", usdText(2.675))
        assertEquals("$0.01", usdText(0.005))
        assertEquals("$0.00", usdText(1e-8))
        assertEquals("$100,000,000.00", usdText(1e8))
        assertEquals("—", usdText(null))
        assertEquals("—", usdText(Double.NaN))
        assertEquals("—", usdText(Double.POSITIVE_INFINITY))
        assertEquals("—", usdText(-1.0))
        assertEquals("—", usdText(Double.MAX_VALUE))
    }

    @Test
    fun missingValuesAreNotShownAsZeroOrCompleteTotals() {
        assertEquals("1,234,567", countText(1234567))
        assertEquals("0", countText(0))
        assertEquals("—", countText(null))
        assertNull(sumAmounts(listOf(1.0, null)))
        assertNull(sumAmounts(emptyList()))
        assertEquals(3.5, sumAmounts(listOf(1.0, 2.5)))
        assertNull(sumCounts(listOf(1L, null)))
        assertNull(sumCounts(listOf(Long.MAX_VALUE, 1L)))
        assertEquals(3L, sumCounts(listOf(1L, 2L)))
    }

    @Test
    fun cacheRatioRequiresConsistentCounts() {
        assertEquals("30.0%", cacheRatioText(30, 100))
        assertEquals("0.0%", cacheRatioText(0, 100))
        assertEquals("100.0%", cacheRatioText(100, 100))
        assertEquals("—", cacheRatioText(null, 100))
        assertEquals("—", cacheRatioText(0, 0))
        assertEquals("—", cacheRatioText(101, 100))
    }

    @Test
    fun timestampsAreExplicitlyUtc() {
        assertEquals("2026-12-31 23:59 UTC", utcTimeText(Instant.parse("2026-12-31T23:59:59Z")))
    }

    @Test
    fun quotaFormattingKeepsLargeValuesAndUnits() {
        assertEquals("$12.50", quotaValueText(12.5, "USD"))
        assertEquals("1,000", quotaValueText(1000.0, "credits"))
        assertEquals((1e20).toString(), quotaValueText(1e20, "次"))
        assertEquals("—", quotaValueText(null, "credits"))
        assertEquals("—", quotaValueText(Double.POSITIVE_INFINITY, "credits"))
    }
}
