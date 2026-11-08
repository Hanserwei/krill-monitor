package com.hanserwei.krill_monitor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

private var toastTimeout: Int? = null

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@Composable
internal actual fun rememberAppToast(): (String) -> Unit = remember {
    { message: String ->
        toastTimeout?.let(window::clearTimeout)
        val toast =
            (document.getElementById("krill-toast")
                ?: document.createElement("div").also {
                    it.id = "krill-toast"
                    it.setAttribute("role", "status")
                    it.setAttribute("aria-live", "polite")
                    document.body?.appendChild(it)
                })
                as HTMLElement
        toast.textContent = message
        toast.style.cssText =
            "position:fixed;left:50%;bottom:calc(96px + env(safe-area-inset-bottom));transform:translateX(-50%);max-width:min(480px,85vw);padding:14px 20px;border-radius:16px;background:#102d3a;color:#fff;box-shadow:0 8px 32px #001c2b33;font:14px/1.5 system-ui,sans-serif;z-index:9999;pointer-events:none;"
        toastTimeout =
            window.setTimeout(
                {
                    toast.remove()
                    toastTimeout = null
                    null
                },
                4500,
            )
    }
}
