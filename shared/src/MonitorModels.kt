@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.hanserwei.krill_monitor

import kotlin.time.Instant

enum class RelayLine(val label: String, val origin: String) {
    Overseas("海外", "https://api.kbsfdxc.net"),
    China("国内优化", "https://api-slb.kbsfdxc.net"),
    Cdn("三网优化 CDN", "https://api.kbsfdxc.com"),
}

data class DailyUsage(
    // 北京时间日期，格式为 YYYY-MM-DD。
    val date: String,
    val costUsd: Double?,
    val requests: Long?,
    val inputTokens: Long?,
    val cachedInputTokens: Long?,
    val outputTokens: Long?,
)

data class ModelUsage(
    val model: String,
    val costUsd: Double?,
    val requests: Long?,
    val inputTokens: Long?,
    val cachedInputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long? = null,
)

data class UsageReport(
    val daily: List<DailyUsage>,
    val models: List<ModelUsage>,
    val fetchedAt: Instant,
    val fromCache: Boolean = false,
    val stale: Boolean = false,
    val warnings: List<String> = emptyList(),
    val costNote: String? = null,
    val timezone: String = "UTC+8",
)

data class LineHealth(
    val line: RelayLine,
    val reachable: Boolean,
    val latencyMillis: Long?,
    val status: Int?,
    val message: String?,
)
