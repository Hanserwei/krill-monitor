package com.hanserwei.krill_monitor

import androidx.compose.runtime.Composable

/** 操作反馈不占据表单空间；页面销毁后成功提示仍需可见。 */
@Composable internal expect fun rememberAppToast(): (String) -> Unit
