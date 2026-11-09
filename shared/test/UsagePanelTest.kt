package com.hanserwei.krill_monitor

import kotlin.test.Test
import kotlin.test.assertEquals

class UsagePanelTest {
    @Test
    fun rankingUsesSelectedMetricAndKeepsUnknownValuesLast() {
        fun model(name: String, cost: Double?, tokens: Long?) =
            ModelUsage(name, cost, null, null, null, null, tokens)
        val models = listOf(model("unknown", null, null), model("first", 2.0, 10), model("second", 2.0, 30), model("invalid", Double.NaN, -1))
        assertEquals(listOf("first", "second", "unknown", "invalid"), sortedUsageModels(models, false).map { it.model })
        assertEquals(listOf("second", "first", "unknown", "invalid"), sortedUsageModels(models, true).map { it.model })
    }
}
