package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestBuilder
import okhttp3.RequestBody
import okio.BufferedSink

actual fun accountApiBaseUrl(): String = "https://www.krill-code.com"

internal actual fun configureAccountRequest(request: HttpRequestBuilder) = Unit

internal actual fun platformMonitorHttpClient(
    configure: HttpClientConfig<*>.() -> Unit
): HttpClient =
    HttpClient(OkHttp) {
        configure(this)
        engine {
            config {
                retryOnConnectionFailure(false)
                addInterceptor { chain ->
                    val request = chain.request()
                    val path = request.url.encodedPath
                    val body = request.body
                    // 503 Retry-After: 0 也会自动重放；创建和轮换必须只发送一次。
                    if (
                        request.method == "POST" &&
                            body != null &&
                            (path == "/api/tokens" ||
                                Regex("/api/tokens/[1-9][0-9]*/rotate").matches(path))
                    ) {
                        val once =
                            object : RequestBody() {
                                override fun contentType() = body.contentType()

                                override fun contentLength() = body.contentLength()

                                override fun writeTo(sink: BufferedSink) = body.writeTo(sink)

                                override fun isOneShot() = true
                            }
                        chain.proceed(request.newBuilder().method(request.method, once).build())
                    } else chain.proceed(request)
                }
            }
        }
    }
