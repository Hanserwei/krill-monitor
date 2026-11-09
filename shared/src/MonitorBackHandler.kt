package com.hanserwei.krill_monitor

import androidx.compose.runtime.Composable

@Composable
internal expect fun MonitorBackHandler(enabled: Boolean, onBack: () -> Unit)

internal expect fun initialMonitorRoute(): String?

@Composable
internal expect fun MonitorBrowserNavigation(route: String, title: String, onRoute: (String) -> Unit)
