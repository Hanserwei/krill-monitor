package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readBuffer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readString
import kotlinx.serialization.json.*

data class ChannelStatusPoint(val at: Instant, val status: Int?)

data class PublicChannelStatus(
    val key: String,
    val provider: String,
    val model: String,
    val channel: String,
    val status: Int?,
    val history: List<ChannelStatusPoint>,
    val throughputTps: Double?,
    val cacheRate: Double?,
    val imageTimeMillis: Double?,
)

data class ChannelStatusSnapshot(
    val channels: List<PublicChannelStatus>,
    val fetchedAt: Instant,
    val stale: Boolean = false,
    val warning: String? = null,
)

class ChannelStatusClient(
    private val http: HttpClient = defaultMonitorHttpClient(),
    private val baseUrl: String = accountApiBaseUrl(),
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val lock = Mutex()
    private var cached: ChannelStatusSnapshot? = null
    private var retryAt: Instant? = null

    suspend fun load(refresh: Boolean = false): ChannelStatusSnapshot = lock.withLock {
        val time = now()
        if (retryAt?.let { it > time } == true) return@withLock unavailable("无法获取官网渠道状态：请求受限，请稍后重试")
        cached?.let {
            if (!refresh && !it.stale && time >= it.fetchedAt && time < it.fetchedAt + 60.seconds)
                return@withLock it
        }
        try {
            val channels =
                http
                    .prepareGet(baseUrl.trimEnd('/') + "/api/public/channel-status?hours=24") {
                        configureAccountRequest(this)
                        header("Accept", "application/json")
                    }
                    .execute { response ->
                        val status = response.status.value
                        if (status == 429) {
                            val seconds =
                                response.headers["Retry-After"]?.toLongOrNull()?.coerceIn(1, 3600)
                                    ?: 60
                            retryAt = now() + seconds.seconds
                            throw AccountAccessException("无法获取官网渠道状态：请求受限，请稍后重试")
                        }
                        if (status !in 200..299)
                            throw AccountAccessException("无法获取官网渠道状态（HTTP $status）")
                        val buffer = response.bodyAsChannel().readBuffer(8 * 1024 * 1024 + 1)
                        if (buffer.size > 8 * 1024 * 1024)
                            throw AccountAccessException("无法获取官网渠道状态：响应过大")
                        parseChannelStatus(Json.parseToJsonElement(buffer.readString()))
                    }
            ChannelStatusSnapshot(
                    channels,
                    now(),
                    warning = if (channels.isEmpty()) "官网未提供渠道状态" else null,
                )
                .also {
                    cached = it
                    retryAt = null
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unavailable(
                if (e is AccountAccessException) e.message ?: "无法获取官网渠道状态"
                else "无法获取官网渠道状态，请检查网络或响应格式"
            )
        }
    }

    private fun unavailable(message: String): ChannelStatusSnapshot =
        cached?.copy(stale = true, warning = message)?.also { cached = it }
            ?: throw AccountAccessException(message)

    fun close() {
        cached = null
        retryAt = null
        http.close()
    }
}

private fun invalidChannelStatus(): Nothing =
    throw AccountAccessException("无法获取官网渠道状态：响应格式无效")

private fun parseChannelStatus(value: JsonElement): List<PublicChannelStatus> {
    val data = channelStatusData(value)
    val items = data["channels"] as? JsonArray ?: invalidChannelStatus()
    val perf = data["perf"] as? JsonArray ?: invalidChannelStatus()
    val performance = indexChannelPerformance(perf)
    val keys = mutableSetOf<String>()
    return items.map { entry -> parseChannel(entry, performance, keys) }
}

private fun channelStatusData(value: JsonElement): JsonObject {
    val root = value as? JsonObject ?: invalidChannelStatus()
    if (
        (root["success"] as? JsonPrimitive)?.booleanOrNull != true ||
            (root["code"] as? JsonPrimitive)?.intOrNull != 0
    )
        invalidChannelStatus()
    return root["data"] as? JsonObject ?: invalidChannelStatus()
}

private fun indexChannelPerformance(perf: JsonArray): Map<String, JsonObject> {
    val performance = mutableMapOf<String, JsonObject>()
    for (entry in perf) {
        val obj = entry as? JsonObject ?: invalidChannelStatus()
        val key = obj.statusText("channel_key") ?: invalidChannelStatus()
        if (performance.put(key, obj) != null) invalidChannelStatus()
    }
    return performance
}

private fun parseChannel(
    entry: JsonElement,
    performance: Map<String, JsonObject>,
    keys: MutableSet<String>,
): PublicChannelStatus {
    val obj = entry as? JsonObject ?: invalidChannelStatus()
    val key = obj.statusText("channel_key") ?: invalidChannelStatus()
    if (!keys.add(key)) invalidChannelStatus()
    val provider = obj.statusText("provider") ?: invalidChannelStatus()
    val model = obj.statusText("model_name") ?: invalidChannelStatus()
    val history = obj["history"] as? JsonArray ?: invalidChannelStatus()
    val metrics = performance[key]
    // 固定状态只要非 null 就优先，即使其值无效也不回退到当前状态。
    val status =
        if (obj["fixed_status"] != null && obj["fixed_status"] != JsonNull)
            statusValue(obj["fixed_status"])
        else statusValue(obj["current_status"])
    return PublicChannelStatus(
        key,
        provider,
        model,
        obj.statusText("channel") ?: "未分类渠道",
        status,
        parseChannelHistory(history),
        metrics?.statusNumber("throughput_p50_tps")
            ?: metrics?.statusNumber("throughput_avg_tps"),
        metrics?.statusNumber("cache_rate")?.takeIf { it <= 1 },
        metrics?.statusNumber("avg_image_time_ms"),
    )
}

private fun parseChannelHistory(history: JsonArray): List<ChannelStatusPoint> =
    history.mapNotNull { point ->
        val obj = point as? JsonObject ?: return@mapNotNull null
        val text = obj.statusText("ts") ?: return@mapNotNull null
        // 兼容空格分隔的时间；没有时区时按 UTC 处理。
        val normalized =
            text.replace(' ', 'T').let {
                if (it.endsWith('Z') || Regex("[+-]\\d\\d:\\d\\d$").containsMatchIn(it)) it
                else it + "Z"
            }
        val at =
            try {
                Instant.parse(normalized)
            } catch (_: Exception) {
                return@mapNotNull null
            }
        ChannelStatusPoint(at, statusValue(obj["s"]))
    }.sortedBy { it.at }

private fun JsonObject.statusText(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun statusValue(value: JsonElement?): Int? =
    (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..2 }

private fun JsonObject.statusNumber(name: String): Double? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf {
        it.isFinite() && it >= 0
    }
