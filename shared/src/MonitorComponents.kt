@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hanserwei.krill_monitor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.hanserwei.krill_monitor.resources.*
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun MonitorIcon(
    symbol: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    tint: Color = LocalContentColor.current,
) {
    val resource = when (symbol) {
        "dashboard" -> if (filled) Res.drawable.symbol_dashboard_filled else Res.drawable.symbol_dashboard
        "bar_chart" -> if (filled) Res.drawable.symbol_bar_chart_filled else Res.drawable.symbol_bar_chart
        "hub" -> if (filled) Res.drawable.symbol_hub_filled else Res.drawable.symbol_hub
        "key" -> if (filled) Res.drawable.symbol_key_filled else Res.drawable.symbol_key
        "settings" -> if (filled) Res.drawable.symbol_settings_filled else Res.drawable.symbol_settings
        "refresh" -> Res.drawable.symbol_refresh
        "check" -> Res.drawable.symbol_check
        "check_circle" -> Res.drawable.symbol_check_circle
        "redeem" -> Res.drawable.symbol_redeem
        "auto_awesome" -> Res.drawable.symbol_auto_awesome
        "public" -> Res.drawable.symbol_public
        "language" -> Res.drawable.symbol_language
        "error_outline" -> Res.drawable.symbol_error_outline
        "expand_more" -> Res.drawable.symbol_expand_more
        "expand_less" -> Res.drawable.symbol_expand_less
        "key_off" -> Res.drawable.symbol_key_off
        "more_vert" -> Res.drawable.symbol_more_vert
        "edit" -> Res.drawable.symbol_edit
        "add" -> Res.drawable.symbol_add
        "content_copy" -> Res.drawable.symbol_content_copy
        "verified_user" -> Res.drawable.symbol_verified_user
        "lock" -> Res.drawable.symbol_lock
        "schedule" -> Res.drawable.symbol_schedule
        "account_balance_wallet" -> Res.drawable.symbol_account_balance_wallet
        "shield" -> Res.drawable.symbol_shield
        "logout" -> Res.drawable.symbol_logout
        "mail" -> Res.drawable.symbol_mail
        "login" -> Res.drawable.symbol_login
        else -> error("Unknown Material Symbol: $symbol")
    }
    Icon(painterResource(resource), contentDescription, modifier, tint)
}

internal fun groupedShape(index: Int, count: Int): RoundedCornerShape = RoundedCornerShape(
    topStart = if (index == 0) 28.dp else 8.dp,
    topEnd = if (index == 0) 28.dp else 8.dp,
    bottomStart = if (index == count - 1) 28.dp else 8.dp,
    bottomEnd = if (index == count - 1) 28.dp else 8.dp,
)

@Composable
internal fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press scale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}
