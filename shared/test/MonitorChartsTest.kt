package com.hanserwei.krill_monitor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonitorChartsTest {
    private fun model(name: String, cost: Double?) = ModelUsage(name, cost, null, null, null, null)

    @Test
    fun costsKeepZeroAndRejectMissingOrInvalidValues() {
        assertEquals(0.0, chartCost(0.0))
        assertNull(chartCost(null))
        assertNull(chartCost(-1.0))
        assertNull(chartCost(Double.NaN))
        assertNull(chartCost(Double.POSITIVE_INFINITY))
    }

    @Test
    fun sharesUseKnownCostsAndGroupRemainingModels() {
        val shares =
            modelShares(
                listOf(
                    model("a", 5.0),
                    model("b", 4.0),
                    model("c", 3.0),
                    model("d", 2.0),
                    model("e", 1.0),
                    model("missing", null),
                )
            )
        assertTrue(shares.incomplete)
        assertEquals(listOf("a", "b", "c", "d", "其他"), shares.entries.map { it.label })
        assertEquals(1.0, shares.entries.sumOf { it.fraction }, 1e-12)
        assertEquals(1.0 / 15, shares.entries.last().fraction, 1e-12)
        assertTrue(modelShares(listOf(model("zero", 0.0))).entries.isEmpty())
        assertTrue(modelShares(emptyList()).entries.isEmpty())
        assertTrue(modelShares(listOf(model("invalid", Double.NaN))).incomplete)
    }

    @Test
    fun sharesAndQuotaAvoidOverflowAndUnknownPercentages() {
        val shares = modelShares(listOf(model("a", Double.MAX_VALUE), model("b", Double.MAX_VALUE)))
        assertFalse(shares.incomplete)
        assertEquals(0.5, shares.entries.first().fraction)
        assertNull(quotaRatio(null, 1.0))
        assertNull(quotaRatio(0.0, 0.0))
        assertNull(quotaRatio(Double.NaN, 1.0))
        assertNull(quotaRatio(1.0, Double.POSITIVE_INFINITY))
        assertNull(quotaRatio(-1.0, 1.0))
        assertEquals(0.0, quotaRatio(0.0, 10.0))
        assertEquals(1.5, quotaRatio(15.0, 10.0))
        assertEquals(Double.MAX_VALUE, quotaRatio(Double.MAX_VALUE, Double.MIN_VALUE))
        assertEquals("—", compactCount(null))
        assertEquals("0", compactCount(0))
        assertEquals("1.2B", compactCount(1_273_750_644))
        assertEquals("9223372T", compactCount(Long.MAX_VALUE))
        val description =
            spendChartDescription(
                listOf(
                    DailyUsage("2026-11-01", null, 7, null, null, null),
                    DailyUsage("2026-11-02", 0.0, 0, null, null, null),
                )
            )
        assertTrue(description.contains("2026-11-01 费用 —，请求 7 次"))
        assertTrue(description.contains("2026-11-02 费用 $0.00，请求 0 次"))
    }
}
