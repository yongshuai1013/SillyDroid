package com.jm.sillydroid.feature.main.ui.home.webview

import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.feature.main.diagnostics.normalizeDiagnosticValue

/**
 * 当前主界面真正承载页面渲染的浏览器内核信息。
 *
 * 系统 WebView provider 仍然会被单独记录；GeckoView 模式下不能把系统 WebView 版本误当成当前内核，
 * 否则页面插件会按 WebView 兼容规则做错误降级。
 */
data class BrowserRuntimeInfo(
    val engine: BrowserEngine,
    val runtimeName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val coreName: String,
    val coreVersion: String,
    val coreMajorVersion: Int,
    val recommendedCoreMajorVersion: Int,
    val outdated: Boolean,
    val userAgent: String,
) {
    fun toDiagnosticText(): String {
        return buildString {
            append("browserEngine=${engine.name}")
            append(" browserRuntimeName=${normalizeDiagnosticValue(runtimeName)}")
            append(" browserPackageName=${normalizeDiagnosticValue(packageName)}")
            append(" browserVersionName=${normalizeDiagnosticValue(versionName)}")
            append(" browserVersionCode=${normalizeDiagnosticValue(versionCode)}")
            append(" browserCoreName=${normalizeDiagnosticValue(coreName)}")
            append(" browserCoreVersion=${normalizeDiagnosticValue(coreVersion)}")
            append(" browserCoreMajorVersion=${coreMajorVersion.takeIf { it > 0 } ?: "-"}")
            append(" browserRecommendedCoreMajorVersion=${recommendedCoreMajorVersion.takeIf { it > 0 } ?: "-"}")
            append(" browserOutdated=$outdated")
            append(" browserUserAgent=${normalizeDiagnosticValue(userAgent)}")
        }
    }
}
