package com.jm.sillydroid.feature.main.ui.home.bootstrap

import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.jm.sillydroid.core.model.bootstrap.BootstrapLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.bootstrap.displayText

class BootstrapOverlayRenderer(
    private val views: BootstrapOverlayViews,
    private val text: BootstrapOverlayText,
    private val syncSettingsEntryState: (BootstrapSessionSnapshot) -> Unit,
    private val showBrowser: (String) -> Unit,
    private val shouldLaunchWebViewOnReady: () -> Boolean,
    private val openExternalBrowserForBackgroundOnly: (BootstrapSessionSnapshot) -> Unit,
    private val updateWebViewRefreshLayoutEnabled: () -> Unit,
    private val setPullGestureRefreshing: (Boolean) -> Unit,
    private val onReadyMonitoring: () -> Unit
) {
    fun render(snapshot: BootstrapSessionSnapshot) {
        renderStatusText(snapshot)
        val shouldShowWebView = snapshot.derivedUiFlags.showWebView && shouldLaunchWebViewOnReady()

        views.retryButton.isVisible = snapshot.derivedUiFlags.canRetry
        views.retryButton.text = if (snapshot.lifecycle == BootstrapLifecycle.CONFIGURING) {
            text.resumeLabel()
        } else {
            text.retryLabel()
        }
        views.progress.isVisible = snapshot.derivedUiFlags.showProgress
        views.progressLabel.isVisible = views.progress.isVisible
        // 更新入口和设置入口属于启动 overlay 顶栏，只在启动/配置/失败阶段显示；
        // Tavern 浏览器就绪后必须整体隐藏，避免更新提示漂浮覆盖 WebView/GeckoView 内容。
        views.topActionBar.isVisible = !shouldShowWebView
        views.settingsButton.isVisible = views.topActionBar.isVisible
        syncSettingsEntryState(snapshot)
        renderProgress(snapshot)

        if (snapshot.lifecycle == BootstrapLifecycle.READY_MONITORING && shouldShowWebView) {
            showBrowser(snapshot.localUrl)
            onReadyMonitoring()
        } else if (snapshot.lifecycle == BootstrapLifecycle.READY_MONITORING &&
            snapshot.derivedUiFlags.showWebView &&
            !shouldLaunchWebViewOnReady()
        ) {
            openExternalBrowserForBackgroundOnly(snapshot)
            onReadyMonitoring()
            renderHiddenBrowser()
        } else if (shouldShowWebView) {
            views.browserContainer.isVisible = true
            views.browserSurface().isVisible = true
            updateWebViewRefreshLayoutEnabled()
        } else {
            renderHiddenBrowser()
        }
    }

    private fun renderHiddenBrowser() {
        views.overlay.isVisible = true
        views.browserContainer.isVisible = false
        views.browserContainer.isEnabled = false
        setPullGestureRefreshing(false)
        views.browserSurface().isVisible = false
    }

    private fun renderStatusText(snapshot: BootstrapSessionSnapshot) {
        val displayMessage = if (snapshot.lifecycle == BootstrapLifecycle.CONFIGURING) {
            text.pausedMessage()
        } else {
            snapshot.statusMessage
        }
        val baseDetails = if (snapshot.lifecycle == BootstrapLifecycle.CONFIGURING) {
            // CONFIGURING 不止可能来自“主动进设置”，也可能来自启动期暂停；
            // 因此除了固定引导文案，还要把 runtime 给出的暂停原因继续展示出来。
            listOf(text.pausedDetails(), snapshot.statusDetails)
                .filter { detail -> detail.isNotBlank() }
                .joinToString(separator = "\n")
        } else {
            snapshot.statusDetails
        }
        val displayDetails = buildDisplayDetails(
            snapshot = snapshot,
            baseDetails = baseDetails
        )

        val renderedText = if (displayDetails.isBlank()) {
            displayMessage
        } else {
            buildString {
                append(displayMessage)
                append('\n')
                append(displayDetails)
            }
        }
        if (views.status.text?.toString() != renderedText) {
            views.status.text = renderedText
        }
    }

    private fun buildDisplayDetails(
        snapshot: BootstrapSessionSnapshot,
        baseDetails: String
    ): String {
        val sections = mutableListOf<String>()
        if (baseDetails.isNotBlank()) {
            sections += baseDetails
        }

        if (snapshot.derivedUiFlags.showWaitingTimer) {
            val elapsedSeconds = snapshot.currentStepElapsedSeconds
            if (elapsedSeconds != null) {
                sections += text.startupElapsed(elapsedSeconds)
            }
        }

        if (snapshot.derivedUiFlags.showTavernStartupTail) {
            val tavernLogLine = resolveDisplayedTavernLogLine(snapshot)
            if (tavernLogLine != null) {
                sections += text.tavernLogTail(tavernLogLine)
            }
        }

        val failureDiagnosis = snapshot.lastFailure
            ?.takeIf {
                snapshot.lifecycle == BootstrapLifecycle.CONFIGURING ||
                    snapshot.lifecycle == BootstrapLifecycle.FAILED_BLOCKED ||
                    snapshot.lifecycle == BootstrapLifecycle.FAILED_ERROR
            }
            ?.diagnosis
            ?.displayText()
            .orEmpty()
        if (failureDiagnosis.isNotBlank()) {
            // 失败诊断必须跟随 overlay 主提示展示，保证用户不用先打开实时日志也能看到阶段、原因和处理方案。
            sections += failureDiagnosis
        }

        return sections.joinToString(separator = "\n")
    }

    private fun renderProgress(snapshot: BootstrapSessionSnapshot) {
        views.progress.max = 100
        val progressPercent = snapshot.progressPercent.coerceIn(0, 100)
        views.progress.isIndeterminate = progressPercent <= 0
        if (!views.progress.isIndeterminate) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                views.progress.setProgress(progressPercent, true)
            } else {
                views.progress.progress = progressPercent
            }
            views.progressLabel.text = text.progressLabel(progressPercent)
        } else {
            views.progressLabel.text = text.progressIndeterminate()
        }
    }

    private fun resolveDisplayedTavernLogLine(snapshot: BootstrapSessionSnapshot): String? {
        if (!snapshot.derivedUiFlags.showTavernStartupTail) {
            return null
        }

        return snapshot.currentTavernServerLogLine.takeIf { it.isNotBlank() }
    }
}

/**
 * `browserSurface` 用 provider 形式暴露：系统 WebView renderer crash 后会替换底层 WebView 实例，
 * GeckoView 切换分支也会暴露另一种 View，这里每次访问都取最新引用，避免操作已 destroy 的旧浏览器视图。
 * `browserContainer`、`overlay` 等容器视图在布局里不会被替换，仍用直接引用。
 */
data class BootstrapOverlayViews(
    val overlay: View,
    val status: TextView,
    val retryButton: Button,
    val topActionBar: View,
    val settingsButton: ImageButton,
    val progress: ProgressBar,
    val progressLabel: TextView,
    val browserContainer: View,
    val browserSurface: () -> View
)

data class BootstrapOverlayText(
    val pausedMessage: () -> String,
    val pausedDetails: () -> String,
    val resumeLabel: () -> String,
    val retryLabel: () -> String,
    val progressLabel: (percent: Int) -> String,
    val progressIndeterminate: () -> String,
    val startupElapsed: (seconds: Int) -> String,
    val tavernLogTail: (line: String) -> String
)
