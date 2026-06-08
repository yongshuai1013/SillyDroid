package com.jm.sillydroid.feature.main.ui.home.webview

fun interface HostDiagnosticSink {
    fun record(category: String, body: String)
}
