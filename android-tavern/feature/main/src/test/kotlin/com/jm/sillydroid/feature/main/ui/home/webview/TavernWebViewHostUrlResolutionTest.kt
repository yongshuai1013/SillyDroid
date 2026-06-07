package com.jm.sillydroid.feature.main.ui.home.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernWebViewHostUrlResolutionTest {

    @Test
    fun `isTavernUrlForBaseUrl only matches urls under the same local site`() {
        assertTrue(isTavernUrlForBaseUrl("http://127.0.0.1:8000/#/chat", "http://127.0.0.1:8000"))
        assertTrue(isTavernUrlForBaseUrl("http://127.0.0.1:8000/settings/theme", "http://127.0.0.1:8000"))
        assertFalse(isTavernUrlForBaseUrl("https://127.0.0.1:8000/", "http://127.0.0.1:8000"))
        assertFalse(isTavernUrlForBaseUrl("http://127.0.0.1:9000/", "http://127.0.0.1:8000"))
    }

    @Test
    fun `hasLoadedCurrentWebViewPageForBaseUrl does not trust remembered route when real webview url is blank`() {
        assertFalse(hasLoadedCurrentWebViewPageForBaseUrl("", "http://127.0.0.1:8000"))
        assertFalse(hasLoadedCurrentWebViewPageForBaseUrl("   ", "http://127.0.0.1:8000"))
        assertFalse(hasLoadedCurrentWebViewPageForBaseUrl("about:blank", "http://127.0.0.1:8000"))
        assertFalse(hasLoadedCurrentWebViewPageForBaseUrl("chrome://crash", "http://127.0.0.1:8000"))
        assertFalse(hasLoadedCurrentWebViewPageForBaseUrl("http://127.0.0.1:9000/", "http://127.0.0.1:8000"))
        assertTrue(
            hasLoadedCurrentWebViewPageForBaseUrl(
                "http://127.0.0.1:8000/characters/1",
                "http://127.0.0.1:8000"
            )
        )
    }
}
