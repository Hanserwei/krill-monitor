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
                    it.className = "krill-toast"
                    it.setAttribute("role", "status")
                    it.setAttribute("aria-live", "polite")
                    document.body?.appendChild(it)
                })
                as HTMLElement
        toast.textContent = message
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
