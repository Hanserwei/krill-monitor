package com.hanserwei.krill_monitor

import io.ktor.client.request.HttpRequestBuilder

actual fun accountApiBaseUrl(): String = "https://www.krill-code.com"

internal actual fun configureAccountRequest(request: HttpRequestBuilder) = Unit
