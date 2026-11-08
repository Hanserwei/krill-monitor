@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readBuffer
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.readString
import kotlinx.serialization.json.*

fun defaultMonitorHttpClient() = HttpClient {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
}

private data class Reply(val status: Int, val body: JsonObject?, val retrySeconds: Long? = null)

private class Unauthorized : Exception()

private class Unavailable(
    val limited: Boolean = false,
    val retrySeconds: Long? = null,
    val missing: Boolean = false,
) : Exception()

private data class Pages(val buckets: List<JsonObject>, val incomplete: Boolean)

private data class CacheEntry(val report: UsageReport, val day: Long)

/** 会话内的只读客户端；调用方在配置变化时取消旧查询，并在离开页面时关闭。 */
class MonitorClient(
    private val http: HttpClient = defaultMonitorHttpClient(),
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val cache = mutableMapOf<Pair<String, Int>, CacheEntry>()
    private val retryUntil = mutableMapOf<String, Instant>()

    suspend fun load(key: String, line: RelayLine, days: Int = 7): DashboardSnapshot {
        if (key.isEmpty() || key.any { it.isWhitespace() || it.code < 32 || it.code in 127..159 })
            throw MonitorException("Key 不能为空或包含空白、控制字符")
        if (days !in 1..31) throw MonitorException("查询天数必须在 1 至 31 天之间")
        return try {
            coroutineScope {
                val account = async { account(key, line) }
                val usage = async { usage(key, line, days) }
                val lines = RelayLine.entries.map { candidate -> async { probe(candidate) } }
                val (report, warnings) = usage.await()
                DashboardSnapshot(account.await(), report, lines.awaitAll(), now(), warnings)
            }
        } catch (e: Unauthorized) {
            cache.keys.filter { it.first == key }.forEach { cache.remove(it) }
            retryUntil.remove(key)
            throw MonitorException("Key 无效或已失效，请检查后重试")
        }
    }

    fun close() {
        cache.clear()
        retryUntil.clear()
        http.close()
    }

    private suspend fun request(
        line: RelayLine,
        path: String,
        key: String?,
        params: Map<String, String> = emptyMap(),
    ): Reply {
        if (key != null && retryUntil[key]?.let { now() < it } == true) throw Unavailable(true)
        try {
            return http
                .prepareGet(line.origin + path) {
                    header("Accept", "application/json")
                    if (key != null) header("Authorization", "Bearer $key")
                    params.forEach { (name, value) -> parameter(name, value) }
                }
                .execute { response ->
                    val status = response.status.value
                    if (key != null && status == 401) throw Unauthorized()
                    val retry = retrySeconds(response.headers["Retry-After"], now())
                    if (key != null && status == 429) {
                        val time = now().epochSeconds
                        val seconds = retry ?: 1800L
                        val maximum = 253402300799L
                        val deadline =
                            Instant.fromEpochSeconds(
                                if (time >= maximum || seconds >= maximum - time) maximum
                                else time + seconds
                            )
                        if (retryUntil[key]?.let { it > deadline } != true)
                            retryUntil[key] = deadline
                    }
                    val body =
                        if (key != null && status in 200..299) {
                            // 先限制读取量，再解析 JSON，避免异常响应占用无限内存。
                            val buffer = response.bodyAsChannel().readBuffer(2 * 1024 * 1024 + 1)
                            if (buffer.size > 2 * 1024 * 1024) null
                            else
                                try {
                                    Json.parseToJsonElement(buffer.readString()) as? JsonObject
                                } catch (_: Exception) {
                                    null
                                }
                        } else null
                    Reply(status, body, retry)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Unauthorized) {
            throw e
        } catch (_: Exception) {
            throw Unavailable()
        }
    }

    private fun Reply.requireBody(): JsonObject {
        if (status == 429) throw Unavailable(true, retrySeconds)
        if (status !in 200..299) throw Unavailable(missing = status in listOf(404, 405, 501))
        return body ?: throw Unavailable(missing = true)
    }

    private suspend fun billing(
        line: RelayLine,
        key: String,
        endpoint: String,
        params: Map<String, String>,
        firstV1: Boolean,
    ): Pair<JsonObject, Boolean> {
        val prefixes = if (firstV1) listOf("/v1", "") else listOf("", "/v1")
        for (prefix in prefixes) {
            val reply = request(line, "$prefix/dashboard/billing/$endpoint", key, params)
            if (reply.status == 404 || reply.status == 405 || reply.status == 501) continue
            if (reply.status in 200..299) {
                val body = reply.body?.unwrapped()
                val valid =
                    if (endpoint == "subscription") {
                        body != null &&
                            (body.string("object") == "billing_subscription" ||
                                body.number("hard_limit_usd") != null ||
                                body.number("system_hard_limit_usd") != null ||
                                body.number("soft_limit_usd") != null)
                    } else body?.number("total_usage") != null
                if (!valid) continue
                return body!! to (prefix == "/v1")
            }
            reply.requireBody()
        }
        throw Unavailable(missing = true)
    }

    private suspend fun account(key: String, line: RelayLine): AccountSummary = coroutineScope {
        val warnings = mutableListOf<String>()
        val identity = async {
            try {
                request(line, "/v1/user-auth-credential/whoami", key).requireBody().unwrapped() to
                    false
            } catch (e: Unavailable) {
                null to e.limited
            }
        }
        val subscription = async {
            try {
                billing(line, key, "subscription", emptyMap(), true) to false
            } catch (e: Unavailable) {
                null to e.limited
            }
        }
        val (sub, subscriptionLimited) = subscription.await()
        val today = dayOf(now().epochSeconds)
        val date = dateOf(today * 86400)
        val month = date.substring(0, 8) + "01"
        val tomorrow = dateOf((today + 1) * 86400)
        val (bill, billLimited) =
            try {
                billing(
                        line,
                        key,
                        "usage",
                        mapOf("start_date" to month, "end_date" to tomorrow),
                        sub?.second ?: true,
                    )
                    .first to false
            } catch (e: Unavailable) {
                null to e.limited
            }
        val (who, identityLimited) = identity.await()
        val email = who?.string("email")
        val planObj = sub?.first?.get("plan") as? JsonObject
        val plan =
            planObj?.string("title")
                ?: planObj?.string("name")
                ?: planObj?.string("id")
                ?: sub?.first?.string("plan")
        val limit =
            sub?.first?.number("hard_limit_usd") ?: sub?.first?.number("system_hard_limit_usd")
        // 账单 total_usage 的单位是美分，展示时换算成美元。
        val used = bill?.number("total_usage")?.div(100)
        val expiryValue = sub?.first?.get("access_until") as? JsonPrimitive
        val expiry =
            expiryValue?.contentOrNull?.let { value ->
                val numeric = value.toLongOrNull()
                try {
                    when {
                        numeric == null -> Instant.parse(value)
                        numeric == 0L -> null
                        numeric < 0L -> null
                        numeric >= 100_000_000_000L -> Instant.fromEpochMilliseconds(numeric)
                        else -> Instant.fromEpochSeconds(numeric)
                    }
                } catch (_: Exception) {
                    null
                }
            }
        if (who == null || email == null) warnings += "账户邮箱不可用"
        if (
            sub == null ||
                plan == null ||
                limit == null ||
                (expiry == null && expiryValue?.contentOrNull != "0")
        ) {
            warnings += "套餐、额度或到期信息不完整"
        }
        if (used == null) warnings += "当月费用不可用"
        if (identityLimited || subscriptionLimited || billLimited) warnings += "账户接口受限，请稍后重试"
        val unlimited = limit != null && limit >= 100_000_000
        val remaining =
            if (!unlimited && limit != null && used != null) (limit - used).coerceAtLeast(0.0)
            else null
        AccountSummary(email, plan, limit, used, remaining, expiry, unlimited, warnings)
    }

    private suspend fun probe(line: RelayLine): LineHealth {
        val mark = TimeSource.Monotonic.markNow()
        return try {
            // 匿名探测不发送 Key，只表示接口可达性。
            val reply = request(line, "/v1/models", null)
            val reachable = reply.status == 200 || reply.status == 401
            LineHealth(
                line,
                reachable,
                mark.elapsedNow().inWholeMilliseconds,
                reply.status,
                if (reachable) null else "探测接口返回 HTTP ${reply.status}",
            )
        } catch (e: Unavailable) {
            LineHealth(line, false, mark.elapsedNow().inWholeMilliseconds, null, "线路探测失败")
        }
    }

    private suspend fun pages(
        line: RelayLine,
        key: String,
        path: String,
        days: Int,
        end: Long,
        grouped: Boolean,
    ): Pages {
        val buckets = mutableListOf<JsonObject>()
        var page: String? = null
        val cursors = mutableSetOf<String>()
        repeat(3) { index ->
            val params =
                mutableMapOf(
                    "start_time" to (end - days * 86400L).toString(),
                    "end_time" to end.toString(),
                    "bucket_width" to "1d",
                    "limit" to days.toString(),
                )
            if (grouped) params["group_by"] = "model"
            if (page != null) params["page"] = page
            val root = request(line, path, key, params).requireBody().unwrapped()
            val data = root["data"] as? JsonArray ?: throw Unavailable(missing = true)
            for (item in data) buckets += item as? JsonObject ?: throw Unavailable()
            val moreValue = root["has_more"]
            val more =
                if (moreValue == null) false
                else (moreValue as? JsonPrimitive)?.booleanOrNull ?: throw Unavailable()
            if (!more) return Pages(buckets, false)
            page = root.string("next_page")
            // 最多三页；重复或缺失游标同样表示数据不完整。
            if (index == 2 || page.isNullOrBlank() || !cursors.add(page))
                return Pages(buckets, true)
        }
        return Pages(buckets, true)
    }

    private suspend fun usage(
        key: String,
        line: RelayLine,
        days: Int,
    ): Pair<UsageReport?, List<String>> = coroutineScope {
        val time = now()
        val day = dayOf(time.epochSeconds)
        val cached = cache[key to days]?.takeIf { it.day == day }
        // 同一 Key 和日期范围的缓存可跨线路复用；限流期不再次发送请求。
        if (
            cached != null &&
                time.epochSeconds - cached.report.fetchedAt.epochSeconds in 0L until 1800L
        ) {
            return@coroutineScope cached.report.copy(fromCache = true) to emptyList()
        }
        if (retryUntil[key]?.let { time < it } == true) {
            val report =
                cached
                    ?.report
                    ?.copy(
                        fromCache = true,
                        stale = true,
                        warnings = cached.report.warnings + "请求受限，请稍后重试",
                    )
            return@coroutineScope report to
                if (report == null) listOf("用量请求受限，请稍后重试") else emptyList()
        }
        val end = (day + 1) * 86400
        suspend fun fetch(path: String, grouped: Boolean): Pair<Pages?, Unavailable?> =
            try {
                pages(line, key, path, days, end, grouped) to null
            } catch (e: Unavailable) {
                null to e
            }
        val costJob = async { fetch("/v1/organization/costs", false) }
        val usageJob = async { fetch("/v1/organization/usage/completions", true) }
        val (costs, costError) = costJob.await()
        val (usage, usageError) = usageJob.await()
        val errors = listOfNotNull(costError, usageError)
        val limited = errors.any { it.limited }
        if (errors.any { !it.missing } && cached != null) {
            val warning = if (limited) "用量请求受限，显示缓存" else "用量暂时不可用，显示缓存"
            return@coroutineScope cached.report.copy(
                fromCache = true,
                stale = true,
                warnings = cached.report.warnings + warning,
            ) to emptyList()
        }
        val warnings = mutableListOf<String>()
        if (costs == null) warnings += if (limited) "费用接口受限，尝试用量费用" else "费用接口不可用，尝试用量费用"
        if (usage == null) warnings += if (limited) "模型用量接口受限" else "模型用量接口不可用"
        if (costs?.incomplete == true || usage?.incomplete == true) warnings += "分页未完成，数据不完整"
        if (costs == null && usage == null) return@coroutineScope null to warnings
        val report = aggregate(costs, usage, time, days, warnings)
        // 可选端点缺失时也缓存已有数据，避免每次刷新都消耗查询配额。
        if (errors.all { it.missing } && costs?.incomplete != true && usage?.incomplete != true)
            cache[key to days] = CacheEntry(report, day)
        report to emptyList()
    }
}

private fun dayOf(seconds: Long): Long =
    if (seconds >= 0) seconds / 86400 else (seconds - 86399) / 86400

private fun dateOf(seconds: Long): String =
    Instant.fromEpochSeconds(dayOf(seconds) * 86400).toString().substring(0, 10)

private fun retrySeconds(value: String?, now: Instant): Long? {
    val text = value?.trim() ?: return null
    text.toLongOrNull()?.let {
        return it.takeIf { seconds -> seconds >= 0 }
    }
    if (text.isNotEmpty() && text.all { it in '0'..'9' }) return Long.MAX_VALUE
    val match =
        Regex("^[A-Za-z]{3}, (\\d{2}) ([A-Za-z]{3}) (\\d{4}) (\\d{2}:\\d{2}:\\d{2}) GMT$")
            .matchEntire(text) ?: return null
    val month =
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            .indexOfFirst { it.equals(match.groupValues[2], true) } + 1
    if (month == 0) return null
    return try {
        val instant =
            Instant.parse(
                "${match.groupValues[3]}-${month.toString().padStart(2, '0')}-${match.groupValues[1]}T${match.groupValues[4]}Z"
            )
        (instant.epochSeconds - now.epochSeconds).coerceAtLeast(0)
    } catch (_: Exception) {
        null
    }
}

private fun JsonObject.unwrapped(): JsonObject = (this["data"] as? JsonObject) ?: this

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.number(name: String): Double? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf {
        it.isFinite() && it >= 0
    }

private fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.takeIf { it >= 0 }

private class MoneySum {
    private var value = 0.0
    private var valid = true

    fun add(number: Double?) {
        if (number == null) valid = false
        else {
            value += number
            if (!value.isFinite()) valid = false
        }
    }

    fun get(): Double? = value.takeIf { valid }
}

private class CountSum {
    private var value = 0L
    private var valid = true

    fun add(number: Long?) {
        if (number == null || number > Long.MAX_VALUE - value) valid = false else value += number
    }

    fun get(): Long? = value.takeIf { valid }
}

private class Totals {
    val cost = MoneySum()
    val requests = CountSum()
    val input = CountSum()
    val cached = CountSum()
    val output = CountSum()

    fun add(row: JsonObject?) {
        cost.add(row?.number("cost_usd"))
        requests.add(row?.long("num_model_requests"))
        input.add(row?.long("input_tokens"))
        cached.add(row?.long("input_cached_tokens"))
        output.add(row?.long("output_tokens"))
    }
}

private fun aggregate(
    costs: Pages?,
    usage: Pages?,
    time: Instant,
    days: Int,
    warnings: MutableList<String>,
): UsageReport {
    val daily = mutableMapOf<String, Totals>()
    val models = mutableMapOf<String, Totals>()
    val costDays = mutableMapOf<String, MoneySum>()
    val end = (dayOf(time.epochSeconds) + 1) * 86400
    val start = end - days * 86400L
    var badCosts = false
    var badUsage = false

    fun rows(bucket: JsonObject): Pair<String, JsonArray>? {
        val timestamp = bucket.long("start_time") ?: return null
        if (timestamp !in start until end) return "" to JsonArray(emptyList())
        val date =
            try {
                dateOf(timestamp)
            } catch (_: Exception) {
                return null
            }
        val value = bucket["results"] ?: bucket["result"]
        val result =
            when (value) {
                is JsonArray -> value
                is JsonObject -> JsonArray(listOf(value))
                else -> return null
            }
        return date to result
    }

    costs?.buckets?.forEach { bucket ->
        val parsed = rows(bucket)
        if (parsed == null) {
            badCosts = true
            bucket
                .long("start_time")
                ?.takeIf { it in start until end }
                ?.let {
                    costDays.getOrPut(dateOf(it)) { MoneySum() }.add(null)
                }
            warnings += "费用数据结构不完整"
        } else if (parsed.first.isNotEmpty()) {
            val sum = costDays.getOrPut(parsed.first) { MoneySum() }
            parsed.second.forEach { item ->
                val amount = (item as? JsonObject)?.get("amount") as? JsonObject
                val currency = amount?.string("currency")
                if (currency != null && !currency.equals("usd", true)) {
                    sum.add(null)
                    warnings += "费用包含非美元币种"
                } else sum.add(amount?.number("value"))
            }
        }
    }
    usage?.buckets?.forEach { bucket ->
        val parsed = rows(bucket)
        if (parsed == null) {
            badUsage = true
            bucket
                .long("start_time")
                ?.takeIf { it in start until end }
                ?.let {
                    daily.getOrPut(dateOf(it)) { Totals() }.add(null)
                }
            warnings += "用量数据结构不完整"
        } else if (parsed.first.isNotEmpty()) {
            val total = daily.getOrPut(parsed.first) { Totals() }
            parsed.second.forEach { item ->
                val row = item as? JsonObject
                total.add(row)
                if (row == null) {
                    badUsage = true
                    warnings += "用量数据结构不完整"
                } else {
                    val model = row.string("model") ?: "未命名模型".also { warnings += "部分模型名称缺失" }
                    models.getOrPut(model) { Totals() }.add(row)
                }
            }
        }
    }
    val costsComplete = costs != null && !costs.incomplete && !badCosts
    val usageComplete = usage != null && !usage.incomplete && !badUsage
    val dailyRows =
        (0 until days).map { offset ->
            val date = dateOf(start + offset * 86400L)
            val total = daily[date]
            val usageCost = total?.cost?.get() ?: if (total == null && usageComplete) 0.0 else null
            val costSum = costDays[date]
            var cost = costSum?.get() ?: if (costSum == null && costsComplete) 0.0 else null
            if (cost == null && usageCost != null) {
                cost = usageCost
                warnings += "部分日期费用使用用量接口数据"
            }
            val emptyUsage = total == null && usageComplete
            DailyUsage(
                date,
                cost,
                total?.requests?.get() ?: if (emptyUsage) 0L else null,
                total?.input?.get() ?: if (emptyUsage) 0L else null,
                total?.cached?.get() ?: if (emptyUsage) 0L else null,
                total?.output?.get() ?: if (emptyUsage) 0L else null,
            )
        }
    val modelRows =
        models
            .map { (model, total) ->
                ModelUsage(
                    model,
                    total.cost.get(),
                    total.requests.get(),
                    total.input.get(),
                    total.cached.get(),
                    total.output.get(),
                )
            }
            .sortedWith(
                compareByDescending<ModelUsage> { it.costUsd ?: -1.0 }
                    .thenByDescending { it.requests ?: -1L }
                    .thenBy { it.model }
            )
    if (
        dailyRows.any {
            it.costUsd == null ||
                it.requests == null ||
                it.inputTokens == null ||
                it.cachedInputTokens == null ||
                it.outputTokens == null
        }
    ) {
        warnings += "部分费用或用量字段缺失或无效"
    }
    return UsageReport(dailyRows, modelRows, time, warnings = warnings.distinct())
}
