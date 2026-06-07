package com.jm.sillydroid.feature.main.ui.home.webview

import android.webkit.WebSettings
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeWebViewControllerTest {

    @Test
    fun `renderer priority diagnostic names are stable`() {
        assertEquals(
            "RENDERER_PRIORITY_IMPORTANT",
            resolveWebViewRendererPriorityName(WebView.RENDERER_PRIORITY_IMPORTANT)
        )
        assertEquals(
            "RENDERER_PRIORITY_BOUND",
            resolveWebViewRendererPriorityName(WebView.RENDERER_PRIORITY_BOUND)
        )
        assertEquals(
            "RENDERER_PRIORITY_WAIVED",
            resolveWebViewRendererPriorityName(WebView.RENDERER_PRIORITY_WAIVED)
        )
        assertEquals("UNKNOWN", resolveWebViewRendererPriorityName(Int.MIN_VALUE))
    }

    @Test
    fun `web settings cache mode diagnostic names are stable`() {
        assertEquals("LOAD_DEFAULT", resolveWebSettingsCacheModeName(WebSettings.LOAD_DEFAULT))
        assertEquals(
            "LOAD_CACHE_ELSE_NETWORK",
            resolveWebSettingsCacheModeName(WebSettings.LOAD_CACHE_ELSE_NETWORK)
        )
        assertEquals("LOAD_NO_CACHE", resolveWebSettingsCacheModeName(WebSettings.LOAD_NO_CACHE))
        assertEquals("LOAD_CACHE_ONLY", resolveWebSettingsCacheModeName(WebSettings.LOAD_CACHE_ONLY))
        assertEquals("UNKNOWN", resolveWebSettingsCacheModeName(Int.MIN_VALUE))
    }

    @Test
    fun `local load error info keeps webview network failure evidence`() {
        val info = WebViewLocalLoadErrorInfo(
            failingUrl = "http://127.0.0.1:8000/",
            method = "GET",
            errorCode = -6,
            description = "net::ERR_CONNECTION_REFUSED"
        )

        assertEquals("http://127.0.0.1:8000/", info.failingUrl)
        assertEquals("GET", info.method)
        assertEquals(-6, info.errorCode)
        assertEquals("net::ERR_CONNECTION_REFUSED", info.description)
    }
}
