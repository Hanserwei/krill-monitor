package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readBuffer
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.readString
import kotlinx.serialization.json.*

internal expect fun configureAccountRequest(request: HttpRequestBuilder)

/** 官网账号 JWT 与中转 Key 不互通；只向固定账号服务发送登录和只读请求。 */
class AccountClient(
    private val http: HttpClient = defaultMonitorHttpClient(),
    private val baseUrl: String = "https://www.krill-code.com",
    private val now: () -> Instant = { Clock.System.now() },
) {
    private var cachedToken: String? = null
    private var cachedDays = 0
    private var cachedDay: String? = null
    private var cached: AccountDashboard? = null
    private val retries = mutableMapOf<String, Pair<Instant, String>>()
    private val codeSendLock = Mutex()
    // 间隔未知时按邮箱保留本会话申请记录，编辑邮箱或切页不能解除保护。
    private val unknownCodeResends = mutableSetOf<String>()

    suspend fun login(email: String, password: String): AccountLoginResult {
        val address = emailAddress(email)
        if (password.isEmpty()) throw AccountAccessException("请输入密码")
        val data =
            request(
                "/api/auth/login",
                body =
                    buildJsonObject {
                        put("email", address)
                        put("password", password)
                    },
            )
        return parseLoginResult(data)
    }

    internal fun isCodeResendUnknown(email: String): Boolean =
        email.trim().lowercase() in unknownCodeResends

    suspend fun sendLoginCode(email: String): EmailCodeDispatch {
        val address = emailAddress(email)
        if (!codeSendLock.tryLock()) throw AccountAccessException("验证码申请进行中，请稍候")
        try {
            if (isCodeResendUnknown(address))
                throw AccountAccessException("此邮箱已申请验证码，但官网未提供重发时间；请先检查邮箱或在官网重新申请")
            val path = "/api/auth/login/send-code"
            retries[path]
                ?.takeIf { it.first > now() }
                ?.let { throw AccountAccessException(it.second) }
            val settings = request("/api/public/auth-settings")
            val enforced =
                (settings["turnstile_enforced"] as? JsonPrimitive)?.booleanOrNull
                    ?: throw AccountAccessException("无法确认验证码安全设置，请在官网申请验证码")
            // shortcut: 未嵌入 Turnstile，官网强制时改用浏览器授权并由用户完成验证。
            if (enforced) throw AccountAccessException("官网要求人机验证，请在 Krill 官网完成登录")
            val data = request(path, body = buildJsonObject { put("email", address) })
            val seconds = data.count("resend_after_seconds")
            if (seconds != null) {
                val maximum = (253402300799L - now().epochSeconds).coerceAtLeast(0)
                retries[path] =
                    Instant.fromEpochSeconds(now().epochSeconds + seconds.coerceAtMost(maximum)) to
                        "验证码申请间隔未结束，请稍后重发"
            } else unknownCodeResends += address.lowercase()
            return EmailCodeDispatch(seconds)
        } finally {
            codeSendLock.unlock()
        }
    }

    suspend fun loginWithCode(email: String, code: String): AccountLoginResult {
        val address = emailAddress(email)
        val verification = code.trim()
        if (
            verification.isEmpty() ||
                verification.length > 6 ||
                verification.any { it.isWhitespace() || it.code < 32 }
        )
            throw AccountAccessException("请输入邮箱收到的验证码（最多 6 位）")
        return parseLoginResult(
            request(
                "/api/auth/login/verify",
                body =
                    buildJsonObject {
                        put("email", address)
                        put("verification_code", verification)
                    },
            )
        )
    }

    suspend fun verifyTotp(pendingToken: String, code: String): AccountSession {
        if (!safeCredential(pendingToken) || code.length != 6 || code.any { it !in '0'..'9' })
            throw AccountAccessException("请输入 6 位动态验证码")
        return parseSession(
            request(
                "/api/auth/login/totp",
                body =
                    buildJsonObject {
                        put("pending_token", pendingToken)
                        put("totp_code", code)
                    },
            )
        )
    }

    suspend fun load(
        session: AccountSession,
        days: Int = 7,
        refresh: Boolean = false,
    ): AccountDashboard = coroutineScope {
        if (!safeCredential(session.token)) throw AccountSessionExpired()
        if (days !in listOf(7, 30)) throw AccountAccessException("查询范围仅支持 7 或 30 天")
        val time = now()
        val day = beijingDate(time)
        if (cachedToken != session.token) {
            clearCache()
            cachedToken = session.token
        }
        val old = cached?.takeIf { cachedDays == days && cachedDay == day }
        retries["account"]
            ?.takeIf { it.first > time }
            ?.let {
                if (old != null)
                    return@coroutineScope old.copy(
                        usage = old.usage?.copy(fromCache = true, stale = true),
                        warnings = old.warnings + "账号接口请求受限，请稍后重试",
                    )
                throw AccountAccessException("账号接口请求受限，请稍后重试")
            }
        // 即使命中统计缓存也验证身份，失效会话不能继续显示旧账户数据。
        val me =
            try {
                request("/api/auth/me", session.token)
            } catch (e: AccountSessionExpired) {
                clearCache()
                throw e
            }
        validateUser(me)
        if (
            !refresh && old != null && time.epochSeconds - old.fetchedAt.epochSeconds in 0 until 300
        )
            return@coroutineScope old.copy(
                email = me.text("email"),
                usage = old.usage?.copy(fromCache = true),
            )
        val warnings = mutableListOf<String>()
        suspend fun optional(path: String, label: String, body: JsonObject? = null): JsonObject? =
            try {
                request(path, session.token, body)
            } catch (e: AccountAccessException) {
                warnings += "$label：${e.message}"
                null
            }
        val range = accountRange(time, days)
        try {
            val subscriptionsJob = async { optional("/api/subscription", "套餐查询失败") }
            val creditsJob = async { optional("/api/credits", "钱包查询失败") }
            val statsJob = async { optional("/api/request-logs/stats", "统计查询失败", range) }
            val modelsJob = async { optional("/api/request-logs/model-stats", "模型统计失败", range) }
            val subscriptions = subscriptionsJob.await()
            val credits = creditsJob.await()
            val stats = statsJob.await()
            val models = modelsJob.await()
            val usage =
                if (stats != null) parseAccountUsage(stats, models, time, days)
                else old?.usage?.copy(fromCache = true, stale = true)
            val result =
                AccountDashboard(
                    email = me.text("email") ?: session.email,
                    balanceUsd =
                        credits?.amount("balance_usd")
                            ?: subscriptions?.amount("credit_balance_usd"),
                    welfareUsd =
                        credits?.amount("welfare_balance_usd")
                            ?: subscriptions?.amount("welfare_balance_usd"),
                    subscriptions = parseSubscriptions(subscriptions, warnings),
                    statistics = stats?.let(::parseAccountStatistics),
                    usage = usage,
                    fetchedAt = time,
                    warnings = warnings.toList(),
                )
            if (warnings.isEmpty() && usage?.warnings?.isEmpty() == true) {
                cached = result
                cachedDays = days
                cachedDay = day
            }
            result
        } catch (e: AccountSessionExpired) {
            clearCache()
            throw e
        }
    }

    fun close() {
        clearCache()
        retries.clear()
        unknownCodeResends.clear()
        http.close()
    }

    private fun clearCache() {
        cachedToken = null
        cached = null
        retries.remove("account")
    }

    private fun parseLoginResult(data: JsonObject): AccountLoginResult {
        if ((data["requires_totp"] as? JsonPrimitive)?.booleanOrNull == true) {
            val pending =
                data.text("pending_token")?.takeIf(::safeCredential)
                    ?: throw AccountAccessException("二次验证响应无效，请重新登录")
            return AccountLoginResult.TotpRequired(pending)
        }
        return AccountLoginResult.SignedIn(parseSession(data))
    }

    private fun emailAddress(value: String): String {
        val address = value.trim()
        if (
            address.isEmpty() ||
                !address.contains('@') ||
                address.any { it.isWhitespace() || it.code < 32 }
        )
            throw AccountAccessException("请输入有效邮箱")
        return address
    }

    private fun parseSession(data: JsonObject): AccountSession {
        val token =
            data.text("token")?.takeIf(::safeCredential)
                ?: throw AccountAccessException("登录响应缺少会话令牌，请重试")
        val user = data["user"] as? JsonObject ?: throw AccountAccessException("登录响应缺少账户信息，请重试")
        validateUser(user)
        return AccountSession(token, user.text("email"))
    }

    private fun validateUser(user: JsonObject) {
        if ((user["tos_accepted"] as? JsonPrimitive)?.booleanOrNull == false)
            throw AccountAccessException("请先在 Krill 官网阅读并确认服务条款，再重新登录")
        if ((user.count("id") ?: 0) <= 0) throw AccountAccessException("账户身份响应无效，请重新登录")
    }

    private suspend fun request(
        path: String,
        token: String? = null,
        body: JsonObject? = null,
    ): JsonObject {
        val retryKey = if (token == null) path else "account"
        retries[retryKey]
            ?.takeIf { it.first > now() }
            ?.let { throw AccountAccessException(it.second) }
        try {
            return http
                .prepareRequest(baseUrl.trimEnd('/') + path) {
                    method = if (body == null) HttpMethod.Get else HttpMethod.Post
                    configureAccountRequest(this)
                    header("Accept", "application/json")
                    header("X-Language", "zh-CN")
                    if (token != null) header("Authorization", "Bearer $token")
                    if (body != null) {
                        header("Content-Type", "application/json")
                        setBody(body.toString())
                    }
                }
                .execute { response ->
                    val status = response.status.value
                    if (status == 401) {
                        if (token != null) throw AccountSessionExpired()
                        throw AccountAccessException(
                            when (path) {
                                "/api/auth/login/verify" -> "邮箱验证码无效或已过期，请重新获取"
                                "/api/auth/login/totp" -> "动态验证码不正确或二次验证已过期，请重试"
                                else -> "邮箱或密码不正确，请重试"
                            }
                        )
                    }
                    val buffer = response.bodyAsChannel().readBuffer(2 * 1024 * 1024 + 1)
                    if (buffer.size > 2 * 1024 * 1024) throw AccountAccessException("账号接口响应过大")
                    val root =
                        try {
                            Json.parseToJsonElement(buffer.readString()) as? JsonObject
                        } catch (_: Exception) {
                            null
                        }
                    if (status == 429) {
                        val headerWait =
                            response.headers["Retry-After"]?.toLongOrNull()?.takeIf { it >= 0 }
                        val message =
                            root?.text("message")
                                ?: (root?.get("error") as? JsonObject)?.text("message").orEmpty()
                        val locked =
                            token == null &&
                                (message.contains(
                                    "account temporarily locked",
                                    ignoreCase = true,
                                ) ||
                                    message.contains(
                                        "too many failed attempts",
                                        ignoreCase = true,
                                    ) ||
                                    message.contains("账号") && message.contains("锁定"))
                        val retryMessage =
                            when {
                                locked ->
                                    if (headerWait != null) {
                                        "官网暂时锁定账号（登录失败次数过多）。服务端建议 $headerWait 秒后重试，当前请暂停登录。"
                                    } else {
                                        "官网暂时锁定账号（登录失败次数过多）。请暂停重试，等待官网解除限制；服务端未提供解锁时间。"
                                    }
                                headerWait != null -> "请求受限，服务端建议在 $headerWait 秒后重试。"
                                else -> "请求受限，服务端未提供重试时间，请稍后再试。"
                            }
                        // 无 Retry-After 时只做本地防重复请求，不能据此推断官网解锁时间。
                        val maximumWait = (253402300799L - now().epochSeconds).coerceAtLeast(0)
                        val retryAt =
                            Instant.fromEpochSeconds(
                                now().epochSeconds + (headerWait ?: 60L).coerceAtMost(maximumWait)
                            )
                        retries[retryKey] = retryAt to retryMessage
                        throw AccountAccessException(retryMessage)
                    }
                    if (status == 403) {
                        val type =
                            (root?.get("data") as? JsonObject)?.text("type")
                                ?: (root?.get("error") as? JsonObject)?.text("type")
                        throw AccountAccessException(
                            if (type == "email_binding_required") "请先在 Krill 官网绑定邮箱，再重新登录"
                            else "账号权限受限，请在 Krill 官网完成验证或确认服务条款"
                        )
                    }
                    if (path == "/api/auth/login/verify" && status in listOf(400, 422))
                        throw AccountAccessException("邮箱验证码校验未通过，请检查邮箱和验证码，或重新获取")
                    if (status !in 200..299) throw AccountAccessException("账号接口暂不可用（HTTP $status）")
                    if ((root?.get("success") as? JsonPrimitive)?.booleanOrNull != true)
                        throw AccountAccessException("账号请求未成功，请检查输入或到官网确认账户状态")
                    if (path == "/api/auth/login/send-code")
                        return@execute root["data"] as? JsonObject ?: JsonObject(emptyMap())
                    root["data"] as? JsonObject ?: throw AccountAccessException("账号接口响应格式无效")
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountSessionExpired) {
            throw e
        } catch (e: AccountAccessException) {
            throw e
        } catch (_: Throwable) {
            throw AccountAccessException("无法连接账号服务，请检查网络；Web 版请使用同源账号服务启动命令")
        }
    }
}

private fun safeCredential(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= 16384 &&
        value.none { it.isWhitespace() || it.code < 32 || it.code in 127..159 }

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.amount(name: String): Double? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf {
        it.isFinite() && it >= 0
    }

private fun JsonObject.count(name: String): Long? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.takeIf { it >= 0 }

private fun JsonObject.instant(name: String): Instant? =
    text(name)?.let { value ->
        try {
            Instant.parse(
                if (value.endsWith('Z') || Regex("[+-]\\d\\d:\\d\\d$").containsMatchIn(value)) value
                else value + "+08:00"
            )
        } catch (_: Exception) {
            null
        }
    }

internal fun beijingDate(time: Instant): String =
    Instant.fromEpochSeconds(time.epochSeconds + 8 * 3600).toString().take(10)

internal fun accountRange(time: Instant, days: Int): JsonObject {
    val endDay = (time.epochSeconds + 8 * 3600) / 86400 * 86400
    val start = Instant.fromEpochSeconds(endDay - (days - 1) * 86400L).toString().take(10)
    val end = Instant.fromEpochSeconds(endDay).toString().take(10)
    return buildJsonObject {
        put("start_time", start + "T00:00:00")
        put("end_time", end + "T23:59:59")
    }
}

private fun parseAccountStatistics(stats: JsonObject) =
    AccountStatistics(
        stats.count("total_requests"),
        stats.count("failed_requests"),
        stats.count("input_tokens"),
        stats.count("output_tokens"),
        stats.count("cache_read_input_tokens"),
        stats.count("cache_creation_input_tokens"),
        stats.count("reasoning_tokens"),
        stats.count("total_tokens"),
        stats.amount("total_cost_usd"),
        stats.amount("overall_tps"),
    )

private fun parseSubscriptions(
    data: JsonObject?,
    warnings: MutableList<String>,
): List<AccountSubscription> {
    if (data == null) return emptyList()
    val items =
        data["subscriptions"] as? JsonArray
            ?: run {
                warnings += "套餐列表未提供"
                return emptyList()
            }
    return items.mapNotNull { value ->
        val item =
            value as? JsonObject
                ?: run {
                    warnings += "部分套餐格式无效"
                    return@mapNotNull null
                }
        val plan = item["plan"] as? JsonObject ?: JsonObject(emptyMap())
        val quota = item["quota"] as? JsonObject ?: JsonObject(emptyMap())
        val quotas = mutableListOf<AccountQuota>()
        fun usd(
            label: String,
            source: JsonObject,
            limit: String,
            used: String,
            remaining: String,
            reset: Instant? = null,
            start: Instant? = null,
            weekly: Boolean = false,
        ) {
            val limitAmount = source.amount(limit)
            val usedAmount = source.amount(used)
            quotas +=
                AccountQuota(
                    label,
                    "USD",
                    limitAmount,
                    usedAmount,
                    if (weekly) {
                        if (limitAmount != null && usedAmount != null)
                            (limitAmount - usedAmount).coerceAtLeast(0.0)
                        else null
                    } else source.amount(remaining),
                    reset,
                    start,
                )
        }
        when {
            plan.text("billing_type") == "request_count" -> {
                val counts = data["request_count_quota"] as? JsonObject ?: JsonObject(emptyMap())
                listOf("5h" to "5 小时", "weekly" to "本周", "monthly" to "本月").forEach {
                    (suffix, label) ->
                    quotas +=
                        AccountQuota(
                            label,
                            "次",
                            counts.amount("limit_$suffix") ?: plan.amount("rate_limit_$suffix"),
                            counts.amount("used_$suffix"),
                            null,
                        )
                }
            }
            quota.amount("limit_credits") != null || plan.amount("total_credits") != null ->
                quotas +=
                    AccountQuota(
                        "Credits",
                        "credits",
                        quota.amount("limit_credits") ?: plan.amount("total_credits"),
                        null,
                        quota.amount("remaining_credits"),
                    )
            plan.text("billing_type") == "usd_daily" ->
                usd("总额度", item, "total_limit_usd", "total_used_usd", "total_remaining_usd")
            plan.text("billing_type") == "usd_weekly" -> {
                usd(
                    "本周额度",
                    quota,
                    "daily_limit_usd",
                    "used_usd",
                    "remaining_usd",
                    quota.instant("window_reset_at"),
                    quota.instant("window_start_at"),
                    weekly = true,
                )
                if ((plan.amount("duration_days") ?: 0.0) >= 14)
                    usd("月额度", item, "total_limit_usd", "total_used_usd", "total_remaining_usd")
            }
            plan.text("billing_type") == "usd_monthly" ->
                usd(
                    "总额度",
                    quota,
                    "daily_limit_usd",
                    "used_usd",
                    "remaining_usd",
                    quota.instant("window_reset_at"),
                )
            else -> {
                warnings += "部分套餐计费类型未识别，未推算额度"
            }
        }
        if (
            quota.amount("forwarded_limit_usd") != null &&
                (quota.amount("forwarded_limit_usd") ?: 0.0) > 0
        )
            usd(
                "结转额度",
                quota,
                "forwarded_limit_usd",
                "forwarded_used_usd",
                "forwarded_remaining_usd",
            )
        AccountSubscription(
            plan.text("name") ?: "未命名套餐",
            item.text("status") ?: "未知",
            item.instant("subscription_end_at"),
            quotas,
        )
    }
}

private fun parseAccountUsage(
    stats: JsonObject,
    models: JsonObject?,
    time: Instant,
    days: Int,
): UsageReport {
    val warnings = mutableListOf<String>()
    val trend = stats["trend"] as? JsonArray
    val trendValid =
        trend != null &&
            trend.all { value ->
                val row = value as? JsonObject
                row != null &&
                    row.instant("bucket_start") != null &&
                    row.amount("total_cost_usd") != null &&
                    row.count("request_count") != null
            }
    val daily =
        (0 until days).map { index ->
            val date =
                beijingDate(
                    Instant.fromEpochSeconds(time.epochSeconds - (days - 1 - index) * 86400L)
                )
            val rows =
                trend
                    ?.mapNotNull { it as? JsonObject }
                    // 官网桶以 UTC 返回，不能直接截取日期当作北京时间。
                    ?.filter { it.instant("bucket_start")?.let(::beijingDate) == date }
            val cost = rows?.let {
                if (it.isEmpty()) {
                    if (trendValid) 0.0 else null
                } else sumAmounts(it.map { r -> r.amount("total_cost_usd") })
            }
            val requests = rows?.let {
                if (it.isEmpty()) {
                    if (trendValid) 0L else null
                } else sumCounts(it.map { r -> r.count("request_count") })
            }
            DailyUsage(date, cost, requests, null, null, null)
        }
    if (trend == null || daily.any { it.costUsd == null || it.requests == null })
        warnings += "每日统计字段未完整提供"
    val modelItems = models?.get("items") as? JsonArray
    if (modelItems == null) warnings += "模型统计未提供"
    val modelRows =
        modelItems
            ?.mapNotNull { value ->
                val row =
                    value as? JsonObject
                        ?: run {
                            warnings += "部分模型统计格式无效"
                            return@mapNotNull null
                        }
                ModelUsage(
                    row.text("model") ?: "未命名模型",
                    row.amount("total_cost_usd"),
                    row.count("request_count"),
                    null,
                    null,
                    null,
                    row.count("total_tokens"),
                )
            }
            .orEmpty()
            .sortedWith(
                compareByDescending<ModelUsage> { it.costUsd ?: -1.0 }
                    .thenByDescending { it.requests ?: -1 }
            )
    if (modelRows.any { it.costUsd == null || it.requests == null || it.totalTokens == null })
        warnings += "部分模型统计字段未提供"
    return UsageReport(
        daily,
        modelRows,
        time,
        warnings = warnings,
        costNote = "费用来自官网请求日志统计；每日及模型接口仅提供总 Token，细分 Token 见汇总",
        timezone = "UTC+8",
    )
}
