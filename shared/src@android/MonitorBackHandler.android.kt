package com.hanserwei.krill_monitor

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
internal actual fun MonitorBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled, onBack)
}

internal actual fun initialMonitorRoute(): String? = null

@Composable
internal actual fun MonitorBrowserNavigation(route: String, title: String, onRoute: (String) -> Unit) = Unit
