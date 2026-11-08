package com.hanserwei.krill_monitor

import kotlin.time.Instant

// 普通 class 避免默认 toString 把令牌带入调试输出。
class AccountSession(val token: String, val email: String?)

sealed interface AccountLoginResult {
    class SignedIn(val session: AccountSession) : AccountLoginResult

    class TotpRequired(val pendingToken: String) : AccountLoginResult
}

data class EmailCodeDispatch(val resendAfterSeconds: Long?)

class AccountSessionExpired : Exception("登录已过期，请重新登录")

class AccountAccessException(message: String) : Exception(message)

data class AccountQuota(
    val label: String,
    val unit: String,
    val limit: Double?,
    val used: Double?,
    val remaining: Double?,
    val resetsAt: Instant? = null,
)

data class AccountSubscription(
    val name: String,
    val status: String,
    val expiresAt: Instant?,
    val quotas: List<AccountQuota>,
)

data class AccountStatistics(
    val requests: Long?,
    val failedRequests: Long?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cachedInputTokens: Long?,
    val cacheWriteTokens: Long?,
    val reasoningTokens: Long?,
    val totalTokens: Long?,
    val costUsd: Double?,
    val tps: Double?,
)

data class AccountDashboard(
    val email: String?,
    val balanceUsd: Double?,
    val welfareUsd: Double?,
    val subscriptions: List<AccountSubscription>,
    val statistics: AccountStatistics?,
    val usage: UsageReport?,
    val fetchedAt: Instant,
    val warnings: List<String> = emptyList(),
)

expect fun accountApiBaseUrl(): String
