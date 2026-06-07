package com.jm.sillydroid.feature.main.ui.home.webview

import android.webkit.WebView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class WebDocumentStartScriptControllerTest {

    @Test
    fun `startup theme script only styles the startup loader area`() {
        val controller = WebDocumentStartScriptController(
            webView = mock<WebView>(),
            systemNotificationBridgeName = "NativeNotificationBridge",
            androidHostBridgeName = "AndroidHostBridge",
            allowedOrigin = { "http://127.0.0.1:8000" }
        )

        val script = controller.documentStartScriptForTest()

        assertTrue(script.contains("--sillydroid-startup-bg"))
        assertTrue(script.contains("#loader.splash-screen"))
        assertFalse(Regex("""html\[data-sillydroid-startup-theme="glass"]\s+#bg1""").containsMatchIn(script))
        assertFalse(Regex("""html\[data-sillydroid-startup-theme="glass"],\s*html\[data-sillydroid-startup-theme="glass"]\s+body""").containsMatchIn(script))
    }

    private fun WebDocumentStartScriptController.documentStartScriptForTest(): String {
        val method = WebDocumentStartScriptController::class.java.getDeclaredMethod("buildDocumentStartScript")
        method.isAccessible = true
        return method.invoke(this) as String
    }
}
