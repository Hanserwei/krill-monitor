package com.hanserwei.krill_monitor

import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.ComposeViewport
import com.hanserwei.krill_monitor.resources.Res
import com.hanserwei.krill_monitor.resources.noto_sans_sc
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont

@OptIn(ExperimentalComposeUiApi::class, ExperimentalResourceApi::class)
fun main() {
    ComposeViewport {
        // 字体就绪后才绘制中文，避免先触发外部回退字体下载。
        val font by preloadFont(Res.font.noto_sans_sc)
        if (font != null) App(FontFamily(font!!))
    }
}
