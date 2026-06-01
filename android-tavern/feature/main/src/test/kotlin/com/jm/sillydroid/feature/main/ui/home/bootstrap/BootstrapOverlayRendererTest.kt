package com.jm.sillydroid.feature.main.ui.home.bootstrap

import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import com.jm.sillydroid.core.model.bootstrap.BootstrapDerivedUiFlags
import com.jm.sillydroid.core.model.bootstrap.BootstrapLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * 覆盖 renderer 重建后 [BootstrapOverlayRenderer] 是否会通过 provider 切换到新 WebView。
 * 防止再次回归"overlay 状态机与真实 WebView 分叉"的 P1 问题。
 */
class BootstrapOverlayRendererTest {

    private fun newRenderer(
        webViewRef: () -> WebView,
        overlay: View = mock(),
        refreshLayout: View = mock(),
        onShowWebView: (String) -> Unit = {},
        shouldLaunchWebViewOnReady: () -> Boolean = { true },
        onReadyMonitoring: () -> Unit = {},
        updateRefreshLayoutEnabled: () -> Unit = {}
    ): BootstrapOverlayRenderer {
        val views = BootstrapOverlayViews(
            overlay = overlay,
            status = mock<TextView>().also { whenever(it.text).thenReturn("") },
            retryButton = mock<Button>(),
            settingsButton = mock<ImageButton>(),
            progress = mock<ProgressBar>(),
            progressLabel = mock<TextView>(),
            webViewRefreshLayout = refreshLayout,
            webView = webViewRef
        )
        val text = BootstrapOverlayText(
            pausedMessage = { "paused" },
            pausedDetails = { "paused-details" },
            resumeLabel = { "resume" },
            retryLabel = { "retry" },
            progressLabel = { "$it%" },
            progressIndeterminate = { "indeterminate" },
            startupElapsed = { "elapsed $it" },
            tavernLogTail = { "tail $it" }
        )
        return BootstrapOverlayRenderer(
            views = views,
            text = text,
            syncSettingsEntryState = { /* no-op */ },
            showWebView = onShowWebView,
            shouldLaunchWebViewOnReady = shouldLaunchWebViewOnReady,
            updateWebViewRefreshLayoutEnabled = updateRefreshLayoutEnabled,
            setPullGestureRefreshing = { /* no-op */ },
            onReadyMonitoring = onReadyMonitoring
        )
    }

    @Test
    fun `hidden state toggles isVisible on latest webView after recreate`() {
        val oldWebView = mock<WebView>()
        val newWebView = mock<WebView>()
        var current: WebView = oldWebView

        val renderer = newRenderer(webViewRef = { current })

        // 模拟 renderer 重建：current 切到新 webview。
        current = newWebView

        val snapshot = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.RUNNING,
            derivedUiFlags = BootstrapDerivedUiFlags(showWebView = false, showBootstrapOverlay = true)
        )
        renderer.render(snapshot)

        verify(newWebView).visibility = View.GONE
        verify(oldWebView, never()).visibility = View.GONE
    }

    @Test
    fun `visible state toggles isVisible on latest webView after recreate`() {
        val oldWebView = mock<WebView>()
        val newWebView = mock<WebView>()
        var current: WebView = oldWebView

        val renderer = newRenderer(webViewRef = { current })

        current = newWebView

        val snapshot = BootstrapSessionSnapshot(
            lifecycle = BootstrapLifecycle.CONFIGURING,
            derivedUiFlags = BootstrapDerivedUiFlags(showWebView = true, showBootstrapOverlay = false)
        )
        renderer.render(snapshot)

        verify(newWebView).visibility = View.VISIBLE
        verify(oldWebView, never()).visibility = View.VISIBLE
    }

    @Test
    fun `ready state launches webView by default`() {
        val webView = mock<WebView>()
        var shownUrl: String? = null

        val renderer = newRenderer(
            webViewRef = { webView },
            onShowWebView = { url -> shownUrl = url }
        )

        renderer.render(
            BootstrapSessionSnapshot(
                lifecycle = BootstrapLifecycle.READY_MONITORING,
                localUrl = "http://127.0.0.1:8000/",
                derivedUiFlags = BootstrapDerivedUiFlags(showWebView = true, showBootstrapOverlay = false)
            )
        )

        org.junit.Assert.assertEquals("http://127.0.0.1:8000/", shownUrl)
    }

    @Test
    fun `ready state keeps webView hidden when background only mode is enabled`() {
        val webView = mock<WebView>()
        val overlay = mock<View>()
        val refreshLayout = mock<View>()
        var shownUrl: String? = null

        val renderer = newRenderer(
            webViewRef = { webView },
            overlay = overlay,
            refreshLayout = refreshLayout,
            onShowWebView = { url -> shownUrl = url },
            shouldLaunchWebViewOnReady = { false }
        )

        renderer.render(
            BootstrapSessionSnapshot(
                lifecycle = BootstrapLifecycle.READY_MONITORING,
                localUrl = "http://127.0.0.1:8000/",
                derivedUiFlags = BootstrapDerivedUiFlags(showWebView = true, showBootstrapOverlay = false)
            )
        )

        org.junit.Assert.assertNull(shownUrl)
        verify(overlay).visibility = View.VISIBLE
        verify(refreshLayout).visibility = View.GONE
        verify(webView).visibility = View.GONE
    }
}
