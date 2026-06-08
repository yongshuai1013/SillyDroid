package com.jm.sillydroid.core.model.settings

/**
 * 主界面浏览器引擎选择。
 *
 * SYSTEM_WEBVIEW 保留现有 Android WebView 链路；GECKOVIEW 用内嵌 GeckoView 绕开系统 WebView provider，
 * 并通过 WebExtension 接管宿主插件桥、下载、通知、文件导入和浏览器诊断。
 */
enum class BrowserEngine {
    SYSTEM_WEBVIEW,
    GECKOVIEW;

    companion object {
        fun fromStorageValue(rawValue: String?): BrowserEngine {
            return entries.firstOrNull { entry ->
                entry.name.equals(rawValue.orEmpty().trim(), ignoreCase = true)
            } ?: SYSTEM_WEBVIEW
        }
    }
}
