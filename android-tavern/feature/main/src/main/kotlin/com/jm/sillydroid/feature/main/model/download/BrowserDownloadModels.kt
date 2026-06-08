package com.jm.sillydroid.feature.main.model.download

import android.webkit.WebView
import java.io.InputStream

data class BrowserDownloadRequest(
    // WebView 的 blob/data 下载兜底需要把 URL 回注给当前页面；GeckoView 等其它内核没有这个对象，
    // 普通 HTTP 下载仍可复用同一请求模型交给 DownloadManager。
    val sourceWebView: WebView?,
    val url: String,
    val userAgent: String,
    val contentDisposition: String,
    val mimeType: String
)

data class BrowserResponseDownloadRequest(
    // GeckoView 的 onExternalResponse 已经拿到了浏览器响应体；直接保存可避免 DownloadManager 二次请求丢 Cookie/会话。
    val url: String,
    val contentDisposition: String,
    val mimeType: String,
    val body: InputStream
)

sealed interface BrowserDownloadResult {
    data class Started(
        val downloadId: Long,
        val fileName: String,
        val mimeType: String,
        val localUri: String
    ) : BrowserDownloadResult
    data class Delegated(val fileName: String) : BrowserDownloadResult
    data class Failed(val fileName: String, val message: String) : BrowserDownloadResult
}

data class BlobDownloadRequest(
    val fileName: String,
    val mimeType: String,
    val base64Data: String
)

data class BlobDownloadChunkedStartRequest(
    val downloadId: String,
    val fileName: String,
    val mimeType: String,
    val totalBase64Length: Long,
    val chunkCount: Int
)

data class BlobDownloadChunkRequest(
    val downloadId: String,
    val index: Int,
    val base64Data: String
)

data class BlobDownloadChunkedCompleteRequest(
    val downloadId: String,
    val fileName: String,
    val message: String
)

data class BlobDownloadSavedFile(
    val fileName: String,
    val mimeType: String,
    val contentUri: String,
    val displayPath: String
)

data class DownloadFailureReport(
    val fileName: String,
    val message: String
)
