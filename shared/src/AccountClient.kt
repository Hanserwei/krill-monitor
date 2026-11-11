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

/** 官网账号 JWT 与中转 Key 不互通；账号请求只发送到固定账号服务。 */
class AccountClient(
    private val http: HttpClient = defaultMonitorHttpClient(),
    private val baseUrl: String = "https://www.krill-code.com",
    private val now: () -> Instant = { Clock.System.now() },
) {
    private data class CachedResponse(val fetchedAt: Instant, val data: JsonObject)
    private data class WalletBalance(val balanceUsd: Double?, val welfareUsd: Double?)
    private data class RetryDelay(val retryAt: Instant, val message: String)

    private var cachedToken: String? = null
    private val cachedDashboards = mutableMapOf<Int, AccountDashboard>()
    private var cachedWallet: WalletBalance? = null
    private var cachedSubscriptions: JsonObject? = null
    private val cachedStats = mutableMapOf<Int, CachedResponse>()
    private val cachedModels = mutableMapOf<Int, CachedResponse>()
    private data class ModelCacheKey(val model: String, val days: Int, val date: String)
    private val cachedModelStatistics = mutableMapOf<ModelCacheKey, ModelStatisticsReport>()
    private val modelStatisticsLocks = mutableMapOf<ModelCacheKey, Mutex>()
    private var cacheGeneration = 0L
    private val retries = mutableMapOf<String, RetryDelay>()
    private val codeSendLock = Mutex()
    private val keySecretLock = Mutex()
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
                ?.takeIf { it.retryAt > now() }
                ?.let { throw AccountAccessException(it.message) }
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
                retries[path] = RetryDelay(
                    retryAt = Instant.fromEpochSeconds(now().epochSeconds + seconds.coerceAtMost(maximum)),
                    message = "验证码申请间隔未结束，请稍后重发",
                )
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
        if (!safeCredential(session.token)) {
            clearCache()
            throw AccountSessionExpired()
        }
        if (days !in listOf(7, 30)) throw AccountAccessException("查询范围仅支持 7 或 30 天")
        val time = now()
        val day = beijingDate(time)
        if (cachedToken != session.token) {
            clearCache()
            cachedToken = session.token
        }
        val old = cachedDashboards[days]?.takeIf { beijingDate(it.fetchedAt) == day }
        if (retries["account"]?.let { it.retryAt > time } == true) {
            if (old != null) {
                return@coroutineScope old.copy(
                    usage = old.usage?.copy(fromCache = true, stale = true),
                    warnings = old.warnings + "账号接口请求受限，请稍后重试",
                )
            }
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
            !refresh && old != null && old.warnings.isEmpty() && old.usage?.warnings?.isEmpty() == true &&
                !old.usage.stale && time.epochSeconds - old.fetchedAt.epochSeconds in 0 until 300
        )
            return@coroutineScope old.copy(
                email = me.text("email"),
                usage = old.usage?.copy(fromCache = true),
            )
        val warnings = mutableListOf<String>()
        // 单项查询失败可回退；会话失效和协程取消必须继续向外传播。
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
            // 统计缓存只用于同一北京时间日期、同一查询天数，避免混入旧范围。
            val previousStats = cachedStats[days]?.takeIf { beijingDate(it.fetchedAt) == day }
            val previousModels = cachedModels[days]?.takeIf { beijingDate(it.fetchedAt) == day }
            if (subscriptions == null && cachedSubscriptions != null) warnings += "套餐使用上次成功数据"
            if (stats == null && previousStats != null) warnings += "统计使用上次成功数据"
            if (models == null && previousModels != null) warnings += "模型统计使用上次成功数据"
            val freshStats = stats?.let { CachedResponse(time, it) }
            val effectiveStats = freshStats ?: previousStats
            val effectiveModels = models ?: previousModels?.data
            val usage = effectiveStats?.let { response ->
                val report = parseAccountUsage(response.data, effectiveModels, response.fetchedAt, days)
                if (stats == null || (models == null && previousModels != null))
                    report.copy(fromCache = true, stale = true)
                else report
            }
            val balance = credits?.amount("balance_usd") ?: subscriptions?.amount("credit_balance_usd")
            val welfare = credits?.amount("welfare_balance_usd") ?: subscriptions?.amount("welfare_balance_usd")
            // 钱包接口成功时不沿用旧余额；当前钱包和套餐中都缺失的字段表示未知。
            val previousWallet = if (credits == null) cachedWallet else null
            val wallet = WalletBalance(
                balanceUsd = balance ?: previousWallet?.balanceUsd,
                welfareUsd = welfare ?: previousWallet?.welfareUsd,
            )
            val usesCachedWallet =
                (balance == null && previousWallet?.balanceUsd != null) ||
                    (welfare == null && previousWallet?.welfareUsd != null)
            if (usesCachedWallet) warnings += "钱包使用上次成功数据"
            val result = AccountDashboard(
                email = me.text("email") ?: session.email,
                balanceUsd = wallet.balanceUsd,
                welfareUsd = wallet.welfareUsd,
                subscriptions = parseSubscriptions(subscriptions ?: cachedSubscriptions, warnings),
                statistics = effectiveStats?.data?.let(::parseAccountStatistics),
                usage = usage,
                fetchedAt = time,
                warnings = warnings.toList(),
            )
            if (credits != null || balance != null || welfare != null) cachedWallet = wallet
            if (subscriptions != null) cachedSubscriptions = subscriptions
            if (freshStats != null) cachedStats[days] = freshStats
            if (models != null) cachedModels[days] = CachedResponse(time, models)
            cachedDashboards[days] = result
            result
        } catch (e: AccountSessionExpired) {
            clearCache()
            throw e
        }
    }

    suspend fun loadModelStatistics(
        session: AccountSession,
        model: String,
        days: Int,
        rangeAt: Instant,
        refresh: Boolean = false,
    ): ModelStatisticsReport {
        if (!safeCredential(session.token)) {
            clearCache()
            throw AccountSessionExpired()
        }
        if (days !in listOf(7, 30)) throw AccountAccessException("查询范围仅支持 7 或 30 天")
        if (model.isBlank() || model.length > 512 || model.any { it.code < 32 || it.code in 127..159 })
            throw AccountAccessException("模型名称无效")
        if (cachedToken != session.token) {
            clearCache()
            cachedToken = session.token
        }
        val generation = cacheGeneration
        val key = ModelCacheKey(model, days, beijingDate(rangeAt))
        val lock = modelStatisticsLocks.getOrPut(key) { Mutex() }
        if (!lock.tryLock()) throw AccountAccessException("模型统计查询进行中，请稍候")
        try {
            fun checkGeneration() {
                if (generation != cacheGeneration || cachedToken != session.token) throw AccountSessionExpired()
            }
            try {
                val me = request("/api/auth/me", session.token)
                checkGeneration()
                validateUser(me)
                val old = cachedModelStatistics[key]
                if (!refresh && old != null && !old.stale && old.fetchedAt >= rangeAt &&
                    now().epochSeconds - old.fetchedAt.epochSeconds in 0 until 300)
                    return old
                val body = buildJsonObject {
                    accountRange(rangeAt, days).forEach { (name, value) -> put(name, value) }
                    put("model", model)
                }
                val stats = request("/api/request-logs/stats", session.token, body)
                checkGeneration()
                return ModelStatisticsReport(parseAccountStatistics(stats), now()).also {
                    cachedModelStatistics[key] = it
                }
            } catch (e: AccountAccessException) {
                checkGeneration()
                if (!e.transient) throw e
                val old = cachedModelStatistics[key] ?: throw e
                return old.copy(stale = true, warning = "模型统计查询失败，显示上次成功数据；请稍后重试").also {
                    cachedModelStatistics[key] = it
                }
            }
        } finally {
            lock.unlock()
            if (modelStatisticsLocks[key] === lock) modelStatisticsLocks.remove(key)
        }
    }

    suspend fun listApiKeys(session: AccountSession): ManagedKeyList {
        checkKeySession(session)
        val user = request("/api/auth/me", session.token)
        validateUser(user)
        val accountId = user.count("id")!!
        val data = requestElement("/api/tokens", session.token)
        val items = data as? JsonArray ?: throw AccountAccessException("账号接口响应格式无效")
        return ManagedKeyList(accountId, items.map { parseManagedKey(it) })
    }

    suspend fun listApiKeyChannels(session: AccountSession): List<ApiKeyChannel> {
        checkKeySession(session)
        val items =
            requestElement("/api/my/channels", session.token) as? JsonArray
                ?: throw AccountAccessException("账号接口响应格式无效")
        val channels = items.map { value ->
            val obj = value as? JsonObject ?: throw AccountAccessException("账号接口响应格式无效")
            val id =
                obj.count("id")?.takeIf { it > 0 } ?: throw AccountAccessException("账号接口响应格式无效")
            val name = obj.text("name") ?: throw AccountAccessException("账号接口响应格式无效")
            val routed =
                (obj["default_routed"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    ?: throw AccountAccessException("账号接口响应格式无效")
            ApiKeyChannel(id, name, routed, obj.amount("billing_multiplier"))
        }
        if (channels.map { it.id }.toSet().size != channels.size)
            throw AccountAccessException("账号接口响应格式无效")
        return channels
    }

    suspend fun getApiKeyChannels(session: AccountSession, id: Long): Set<Long> {
        checkKeySession(session)
        checkKeyId(id)
        val data =
            requestElement("/api/tokens/$id/channels", session.token) as? JsonObject
                ?: throw AccountAccessException("账号接口响应格式无效")
        val ids = data["channel_ids"] as? JsonArray ?: throw AccountAccessException("账号接口响应格式无效")
        return ids.map { value ->
                (value as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw AccountAccessException("账号接口响应格式无效")
            }
            .toSet()
    }

    suspend fun setApiKeyChannels(session: AccountSession, id: Long, channelIds: Set<Long>) {
        checkKeySession(session)
        checkKeyId(id)
        channelIds.forEach(::checkKeyId)
        try {
            requestElement(
                "/api/tokens/$id/channels",
                session.token,
                buildJsonObject { putJsonArray("provider_ids") { channelIds.forEach { add(it) } } },
                HttpMethod.Put,
                allowEmpty = true,
            )
        } catch (e: AccountAccessException) {
            throw AccountAccessException("${e.message}；渠道保存结果未确认，请重新读取配置")
        }
    }

    suspend fun createApiKey(session: AccountSession, name: String): ApiKeySecret {
        checkKeySession(session)
        return secretMutation {
            parseKeySecret(
                requestElement(
                    "/api/tokens",
                    session.token,
                    keyName(name),
                    HttpMethod.Post,
                    uncertain = true,
                )
            )
        }
    }

    suspend fun renameApiKey(session: AccountSession, id: Long, name: String): ManagedApiKey {
        checkKeySession(session)
        checkKeyId(id)
        val item =
            parseManagedKey(
                requestElement("/api/tokens/$id", session.token, keyName(name), HttpMethod.Put)
            )
        if (item.id != id) throw AccountAccessException("账号接口响应格式无效")
        return item
    }

    suspend fun rotateApiKey(session: AccountSession, id: Long): ApiKeySecret {
        checkKeySession(session)
        checkKeyId(id)
        return secretMutation {
            parseKeySecret(
                requestElement(
                    "/api/tokens/$id/rotate",
                    session.token,
                    method = HttpMethod.Post,
                    uncertain = true,
                ),
                id,
            )
        }
    }

    suspend fun revokeApiKey(session: AccountSession, id: Long) {
        checkKeySession(session)
        checkKeyId(id)
        requestElement(
            "/api/tokens/$id",
            session.token,
            method = HttpMethod.Delete,
            allowEmpty = true,
        )
    }

    suspend fun destroyApiKey(session: AccountSession, id: Long) {
        checkKeySession(session)
        checkKeyId(id)
        requestElement(
            "/api/tokens/$id/destroy",
            session.token,
            method = HttpMethod.Delete,
            allowEmpty = true,
        )
    }

    private fun checkKeySession(session: AccountSession) {
        if (!safeCredential(session.token)) throw AccountSessionExpired()
    }

    private fun checkKeyId(id: Long) {
        if (id <= 0) throw AccountAccessException("Key 标识无效")
    }

    private fun keyName(name: String): JsonObject = buildJsonObject {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) put("name", JsonNull) else put("name", trimmed)
    }

    private suspend fun secretMutation(action: suspend () -> ApiKeySecret): ApiKeySecret {
        if (!keySecretLock.tryLock()) throw AccountAccessException("Key 操作进行中，请稍候")
        try {
            return action()
        } finally {
            keySecretLock.unlock()
        }
    }

    private fun parseKeySecret(data: JsonElement, expectedId: Long? = null): ApiKeySecret {
        try {
            val obj = data as? JsonObject ?: throw AccountAccessException("响应无效")
            val item = parseManagedKey(obj["item"] ?: JsonNull)
            val secret =
                obj.text("token")?.takeIf(::safeCredential) ?: throw AccountAccessException("响应无效")
            if (expectedId != null && item.id != expectedId) throw AccountAccessException("响应无效")
            return ApiKeySecret(item, secret)
        } catch (_: AccountAccessException) {
            throw AccountAccessException("Key 操作可能已生效，但无法确认新 Key；请先刷新列表，勿重复创建或轮换")
        }
    }

    private fun parseManagedKey(value: JsonElement): ManagedApiKey {
        val obj = value as? JsonObject ?: throw AccountAccessException("账号接口响应格式无效")
        val id = obj.count("id")?.takeIf { it > 0 } ?: throw AccountAccessException("账号接口响应格式无效")
        return ManagedApiKey(
            id = id,
            name = obj.text("name"),
            prefix = obj.text("token_prefix")?.take(16),
            createdAt = obj.instant("created_at"),
            lastUsedAt = obj.instant("last_used_at"),
            revoked = obj["revoked_at"]?.let { it != JsonNull } ?: false,
        )
    }

    fun close() {
        clearCache()
        retries.clear()
        unknownCodeResends.clear()
        http.close()
    }

    private fun clearCache() {
        cacheGeneration++
        cachedModelStatistics.clear()
        modelStatisticsLocks.clear()
        cachedToken = null
        cachedDashboards.clear()
        cachedWallet = null
        cachedSubscriptions = null
        cachedStats.clear()
        cachedModels.clear()
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
    ): JsonObject =
        requestElement(path, token, body) as? JsonObject
            ?: throw AccountAccessException("账号接口响应格式无效")

    private suspend fun requestElement(
        path: String,
        token: String? = null,
        body: JsonObject? = null,
        method: HttpMethod = if (body == null) HttpMethod.Get else HttpMethod.Post,
        allowEmpty: Boolean = false,
        uncertain: Boolean = false,
    ): JsonElement {
        var definiteFailure = false
        val requestGeneration = cacheGeneration
        val retryKey = if (token == null) path else "account"
        retries[retryKey]
            ?.takeIf { it.retryAt > now() }
            ?.let { throw AccountAccessException(it.message, transient = token != null) }
        try {
            return http
                .prepareRequest(baseUrl.trimEnd('/') + path) {
                    this.method = method
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
                    val bufferWasEmpty = buffer.size == 0L
                    val root =
                        try {
                            Json.parseToJsonElement(buffer.readString()) as? JsonObject
                        } catch (_: Exception) {
                            null
                        }
                    if (status == 429) {
                        definiteFailure = true
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
                        if (requestGeneration == cacheGeneration && (token == null || cachedToken == null || cachedToken == token))
                            retries[retryKey] = RetryDelay(retryAt, retryMessage)
                        throw AccountAccessException(retryMessage, transient = token != null)
                    }
                    if (status == 403) {
                        definiteFailure = true
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
                    if (status !in 200..299)
                        throw AccountAccessException("账号接口暂不可用（HTTP $status）", transient = status in 500..599)
                    if (allowEmpty && root == null && bufferWasEmpty) return@execute JsonNull
                    if ((root?.get("success") as? JsonPrimitive)?.booleanOrNull != true)
                        throw AccountAccessException("账号请求未成功，请检查输入或到官网确认账户状态")
                    if (path == "/api/auth/login/send-code")
                        return@execute root["data"] as? JsonObject ?: JsonObject(emptyMap())
                    root["data"]?.takeIf { allowEmpty || it != JsonNull }
                        ?: if (allowEmpty) JsonNull else throw AccountAccessException("账号接口响应格式无效")
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountSessionExpired) {
            if (token != null && requestGeneration == cacheGeneration && (cachedToken == null || cachedToken == token)) clearCache()
            throw e
        } catch (e: AccountAccessException) {
            if (uncertain && !definiteFailure)
                throw AccountAccessException("Key 操作可能已生效，但无法确认新 Key；请先刷新列表，勿重复创建或轮换")
            throw e
        } catch (_: Throwable) {
            if (uncertain) throw AccountAccessException("Key 操作可能已生效，但无法确认新 Key；请先刷新列表，勿重复创建或轮换")
            throw AccountAccessException("无法连接账号服务，请检查网络后重试。", transient = true)
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
        requests = stats.count("total_requests"),
        failedRequests = stats.count("failed_requests"),
        inputTokens = stats.count("input_tokens"),
        outputTokens = stats.count("output_tokens"),
        cachedInputTokens = stats.count("cache_read_input_tokens"),
        cacheWriteTokens = stats.count("cache_creation_input_tokens"),
        reasoningTokens = stats.count("reasoning_tokens"),
        totalTokens = stats.count("total_tokens"),
        costUsd = stats.amount("total_cost_usd"),
        tps = stats.amount("overall_tps"),
        cacheRateReadTokens = stats.count("cache_rate_read_tokens"),
        cacheRateEligibleTokens = stats.count("cache_rate_eligible_tokens"),
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
        val billingType = plan.text("billing_type")
        val creditLimit = quota.amount("limit_credits") ?: plan.amount("total_credits")
        val quotas = mutableListOf<AccountQuota>()
        fun addUsdQuota(
            label: String,
            source: JsonObject,
            limitField: String,
            usedField: String,
            remainingField: String,
            resetsAt: Instant? = null,
            startsAt: Instant? = null,
            weekly: Boolean = false,
        ) {
            val limit = source.amount(limitField)
            val used = source.amount(usedField)
            // 周额度按上限减已用计算；任一字段缺失时仍显示未知，不补成零。
            val remaining = if (weekly) {
                if (limit != null && used != null) (limit - used).coerceAtLeast(0.0) else null
            } else source.amount(remainingField)
            quotas += AccountQuota(
                label = label,
                unit = "USD",
                limit = limit,
                used = used,
                remaining = remaining,
                resetsAt = resetsAt,
                startsAt = startsAt,
            )
        }
        when {
            billingType == "request_count" -> {
                val counts = data["request_count_quota"] as? JsonObject ?: JsonObject(emptyMap())
                listOf("5h" to "5 小时", "weekly" to "本周", "monthly" to "本月").forEach {
                    (suffix, label) ->
                    quotas +=
                        AccountQuota(
                            label = label,
                            unit = "次",
                            limit = counts.amount("limit_$suffix") ?: plan.amount("rate_limit_$suffix"),
                            used = counts.amount("used_$suffix"),
                            remaining = null,
                        )
                }
            }
            creditLimit != null ->
                quotas +=
                    AccountQuota(
                        label = "Credits",
                        unit = "credits",
                        limit = creditLimit,
                        used = null,
                        remaining = quota.amount("remaining_credits"),
                    )
            billingType == "usd_daily" ->
                addUsdQuota("总额度", item, "total_limit_usd", "total_used_usd", "total_remaining_usd")
            billingType == "usd_weekly" -> {
                addUsdQuota(
                    "本周额度",
                    quota,
                    "daily_limit_usd",
                    "used_usd",
                    "remaining_usd",
                    resetsAt = quota.instant("window_reset_at"),
                    startsAt = quota.instant("window_start_at"),
                    weekly = true,
                )
                if ((plan.amount("duration_days") ?: 0.0) >= 14)
                    addUsdQuota("月额度", item, "total_limit_usd", "total_used_usd", "total_remaining_usd")
            }
            billingType == "usd_monthly" ->
                addUsdQuota(
                    "总额度",
                    quota,
                    "daily_limit_usd",
                    "used_usd",
                    "remaining_usd",
                    resetsAt = quota.instant("window_reset_at"),
                    startsAt = quota.instant("window_start_at"),
                )
            else -> {
                warnings += "部分套餐计费类型未识别，未推算额度"
            }
        }
        if ((quota.amount("forwarded_limit_usd") ?: 0.0) > 0)
            addUsdQuota(
                "结转额度",
                quota,
                "forwarded_limit_usd",
                "forwarded_used_usd",
                "forwarded_remaining_usd",
            )
        // 套餐有效期与额度窗口各自来自接口，不能用订阅起止时间补出月额度窗口。
        AccountSubscription(
            name = plan.text("name") ?: "未命名套餐",
            status = item.text("status") ?: "未知",
            expiresAt = item.instant("subscription_end_at"),
            quotas = quotas,
            startsAt = item.instant("subscription_start_at"),
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
    // 官网桶以 UTC 返回，先转为北京时间并分组，避免为每个日期重复扫描整份趋势。
    val rowsByDate = trend
        ?.mapNotNull { it as? JsonObject }
        ?.groupBy { it.instant("bucket_start")?.let(::beijingDate) }
    val daily =
        (0 until days).map { index ->
            val date =
                beijingDate(
                    Instant.fromEpochSeconds(time.epochSeconds - (days - 1 - index) * 86400L)
                )
            val rows = rowsByDate?.get(date).orEmpty()
            // 只有整份趋势有效时，缺少某天的记录才表示当天用量为零。
            val cost = when {
                rows.isNotEmpty() -> sumAmounts(rows.map { it.amount("total_cost_usd") })
                trendValid -> 0.0
                else -> null
            }
            val requests = when {
                rows.isNotEmpty() -> sumCounts(rows.map { it.count("request_count") })
                trendValid -> 0L
                else -> null
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
        costNote = "费用来自官网请求日志统计；模型排行仅提供总 Token，展开后按需查询细分，日级统计未提供细分",
        timezone = "UTC+8",
    )
}
