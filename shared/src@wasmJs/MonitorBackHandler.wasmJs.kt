@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.hanserwei.krill_monitor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.js.JsAny
import kotlin.js.js

@Composable
internal actual fun MonitorBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

internal fun monitorRouteFromHash(hash: String): String? = hash.removePrefix("#").ifEmpty { null }

internal enum class MonitorHistoryAction { None, Replace, Push }

internal fun monitorHistoryAction(hash: String, route: String, initial: Boolean): MonitorHistoryAction =
    when {
        monitorRouteFromHash(hash) == route -> MonitorHistoryAction.None
        initial -> MonitorHistoryAction.Replace
        else -> MonitorHistoryAction.Push
    }

internal actual fun initialMonitorRoute(): String? = monitorRouteFromHash(window.location.hash)

private fun addMonitorHashListener(callback: () -> Unit): JsAny =
    js("(() => { const listener = () => callback(); window.addEventListener('hashchange', listener); return listener; })()")

private fun removeMonitorHashListener(listener: JsAny) {
    js("window.removeEventListener('hashchange', listener)")
}

@Composable
internal actual fun MonitorBrowserNavigation(route: String, title: String, onRoute: (String) -> Unit) {
    val currentOnRoute by rememberUpdatedState(onRoute)
    val initial = remember { booleanArrayOf(true) }

    SideEffect { document.title = "$title · Krill Monitor" }
    DisposableEffect(Unit) {
        val listener = addMonitorHashListener { currentOnRoute(initialMonitorRoute() ?: "") }
        onDispose { removeMonitorHashListener(listener) }
    }
    LaunchedEffect(route) {
        when (monitorHistoryAction(window.location.hash, route, initial[0])) {
            MonitorHistoryAction.None -> Unit
            MonitorHistoryAction.Replace -> window.history.replaceState(window.history.state, "", "#$route")
            MonitorHistoryAction.Push -> window.history.pushState(null, "", "#$route")
        }
        initial[0] = false
    }
}
