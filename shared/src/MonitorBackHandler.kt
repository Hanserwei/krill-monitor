package com.hanserwei.krill_monitor

import androidx.compose.runtime.Composable

@Composable
internal expect fun MonitorBackHandler(enabled: Boolean, onBack: () -> Unit)
