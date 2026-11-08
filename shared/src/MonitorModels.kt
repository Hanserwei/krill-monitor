@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.hanserwei.krill_monitor

import kotlin.time.Instant

enum class RelayLine(val label: String, val origin: String) {
    Overseas("海外", "https://api.kbsfdxc.net"),
    China("国内优化", "https://api-slb.kbsfdxc.net"),
    Cdn("三网优化 CDN", "https://api.kbsfdxc.com"),
}

// null 表示服务端未提供或无法确认；不能将缺失字段展示为零。
// 统计可能属于整个账户，Key 仅用于鉴权，不保证按 Key 隔离。
data class AccountSummary(
    val email: String?,
    val plan: String?,
    val limitUsd: Double?,
    val usedUsd: Double?,
    val remainingUsd: Double?,
    val expiresAt: Instant?,
    val unlimited: Boolean,
    val warnings: List<String> = emptyList(),
)

data class DailyUsage(
    // UTC 日期，格式为 YYYY-MM-DD。
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
)

data class UsageReport(
    val daily: List<DailyUsage>,
    val models: List<ModelUsage>,
    val fetchedAt: Instant,
    val fromCache: Boolean = false,
    val stale: Boolean = false,
    val warnings: List<String> = emptyList(),
)

data class LineHealth(
    val line: RelayLine,
    val reachable: Boolean,
    val latencyMillis: Long?,
    val status: Int?,
    val message: String?,
)

data class DashboardSnapshot(
    val account: AccountSummary?,
    val usage: UsageReport?,
    val lines: List<LineHealth>,
    val updatedAt: Instant,
    val warnings: List<String> = emptyList(),
)

class MonitorException(message: String) : Exception(message)
