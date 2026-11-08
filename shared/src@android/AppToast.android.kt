package com.hanserwei.krill_monitor

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberAppToast(): (String) -> Unit {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        var current: Toast? = null
        { message: String ->
            current?.cancel()
            current = Toast.makeText(context, message, Toast.LENGTH_LONG).also { it.show() }
        }
    }
}
