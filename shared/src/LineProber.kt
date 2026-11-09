package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal expect fun platformMonitorHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient

fun defaultMonitorHttpClient() = platformMonitorHttpClient {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
}

/** 只探测固定线路的 HTTP 可达性，不接收或发送任何登录凭据。 */
class LineProber(
    private val http: HttpClient = defaultMonitorHttpClient(),
    private val probeUrl: (RelayLine) -> String = { it.origin + "/v1/models" },
) {
    suspend fun probeLines(): List<LineHealth> = coroutineScope {
        RelayLine.entries.map { line -> async { probe(line) } }.awaitAll()
    }

    fun close() = http.close()

    private suspend fun probe(line: RelayLine): LineHealth {
        val start = TimeSource.Monotonic.markNow()
        return try {
            val status = http.prepareGet(probeUrl(line)).execute { it.status.value }
            val reachable = status == 200 || status == 401
            LineHealth(
                line,
                reachable,
                start.elapsedNow().inWholeMilliseconds,
                status,
                if (reachable) null else "探测接口返回 HTTP $status",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            LineHealth(line, false, start.elapsedNow().inWholeMilliseconds, null, "线路探测失败")
        }
    }
}
