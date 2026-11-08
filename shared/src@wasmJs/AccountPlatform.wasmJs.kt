package com.hanserwei.krill_monitor

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.fetchOptions
import io.ktor.client.request.HttpRequestBuilder
import kotlin.js.toJsString
import kotlinx.browser.window

actual fun accountApiBaseUrl(): String = window.location.origin + "/krill-account"

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
internal actual fun configureAccountRequest(request: HttpRequestBuilder) {
    // 仅用本应用的 Bearer 会话，不能串用浏览器中已有的官网 Cookie。
    request.fetchOptions { credentials = "omit".toJsString() }
}

internal actual fun platformMonitorHttpClient(
    configure: HttpClientConfig<*>.() -> Unit
): HttpClient = HttpClient { configure(this) }
