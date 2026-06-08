package com.jm.sillydroid.feature.main.ui.home.download

import android.webkit.JavascriptInterface
import com.jm.sillydroid.feature.main.model.download.BlobDownloadSavedFile
import com.jm.sillydroid.feature.main.model.download.DownloadFailureReport
import kotlinx.coroutines.CoroutineScope
import com.jm.sillydroid.core.common.DispatcherProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AndroidBlobDownloadBridge(
    private val controller: BlobDownloadController,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val unknownErrorMessage: () -> String,
    private val emptyPayloadMessage: () -> String,
    private val onPreparing: (String) -> Unit,
    private val onSaving: (String) -> Unit,
    private val onSaved: (BlobDownloadSavedFile) -> Unit,
    private val onFailure: (DownloadFailureReport) -> Unit,
    private val diagnosticSink: (String) -> Unit = {}
) {
    private fun recordDiagnostic(body: String) {
        runCatching { diagnosticSink(body) }
    }

    @JavascriptInterface
    fun onBlobDownloadPreparing(fileName: String?) {
        val resolvedFileName = controller.resolveFileName(fileName)
        // 这个回调只要出现，就说明页面至少命中了宿主安装的 blob bridge，而不是完全走了页面自有下载实现。
        recordDiagnostic("event=blob_bridge_preparing fileName=$resolvedFileName")
        runOnUiThread {
            onPreparing(resolvedFileName)
        }
    }

    @JavascriptInterface
    fun saveBase64File(payload: String?) {
        val request = try {
            controller.parseDownloadRequest(payload)
        } catch (_: Exception) {
            null
        }

        if (request == null) {
            recordDiagnostic("event=blob_bridge_parse_failed reason=empty_or_invalid_payload")
            runOnUiThread {
                onFailure(
                    DownloadFailureReport(
                        fileName = controller.resolveFileName(null),
                        message = emptyPayloadMessage()
                    )
                )
            }
            return
        }

        scope.launch {
            recordDiagnostic(
                "event=blob_bridge_save_requested fileName=${request.fileName} mime=${request.mimeType} base64Length=${request.base64Data.length}"
            )
            onSaving(request.fileName)

            val result = runCatching {
                withContext(dispatchers.io) {
                    controller.persist(request)
                }
            }

            result.onSuccess { savedFile ->
                recordDiagnostic("event=blob_bridge_saved fileName=${savedFile.fileName} uri=${savedFile.contentUri}")
                onSaved(savedFile)
            }.onFailure { error ->
                recordDiagnostic(
                    "event=blob_bridge_save_failed fileName=${request.fileName} error=${error.message ?: error.javaClass.simpleName}"
                )
                onFailure(
                    DownloadFailureReport(
                        fileName = request.fileName,
                        message = error.message ?: unknownErrorMessage()
                    )
                )
            }
        }
    }

    fun beginBase64File(payload: String?): Boolean {
        val request = controller.parseChunkedStartRequest(payload)
            ?: throw IllegalArgumentException("empty_or_invalid_chunked_start_payload")

        // GeckoView 的 native messaging 需要分块传输较大的 base64 导出；这里先建立宿主侧临时会话，
        // 后续 chunk 按顺序写入 cache，完成时再统一流式解码到下载目录。
        controller.beginChunkedDownload(request)
        recordDiagnostic(
            "event=blob_bridge_chunked_begin downloadId=${request.downloadId} fileName=${request.fileName} " +
                "mime=${request.mimeType} totalBase64Length=${request.totalBase64Length} chunkCount=${request.chunkCount}"
        )
        return true
    }

    fun appendBase64FileChunk(payload: String?): Boolean {
        val request = controller.parseChunkRequest(payload)
            ?: throw IllegalArgumentException("empty_or_invalid_chunk_payload")

        controller.appendChunkedDownload(request)
        recordDiagnostic(
            "event=blob_bridge_chunked_chunk downloadId=${request.downloadId} index=${request.index} " +
                "base64Length=${request.base64Data.length}"
        )
        return true
    }

    fun completeBase64File(payload: String?): Boolean {
        val request = controller.parseChunkedCompleteRequest(payload)
            ?: throw IllegalArgumentException("empty_or_invalid_chunked_complete_payload")

        scope.launch {
            recordDiagnostic(
                "event=blob_bridge_chunked_save_requested downloadId=${request.downloadId} fileName=${request.fileName}"
            )
            onSaving(request.fileName)

            val result = runCatching {
                withContext(dispatchers.io) {
                    controller.persistChunked(request.downloadId)
                }
            }

            result.onSuccess { savedFile ->
                recordDiagnostic(
                    "event=blob_bridge_chunked_saved downloadId=${request.downloadId} " +
                        "fileName=${savedFile.fileName} uri=${savedFile.contentUri}"
                )
                onSaved(savedFile)
            }.onFailure { error ->
                val message = error.message ?: unknownErrorMessage()
                recordDiagnostic(
                    "event=blob_bridge_chunked_save_failed downloadId=${request.downloadId} " +
                        "fileName=${request.fileName} error=$message"
                )
                onFailure(
                    DownloadFailureReport(
                        fileName = request.fileName,
                        message = message
                    )
                )
            }
        }
        return true
    }

    fun cancelBase64File(payload: String?): Boolean {
        val request = controller.parseChunkedCompleteRequest(payload)
            ?: throw IllegalArgumentException("empty_or_invalid_chunked_cancel_payload")

        controller.cancelChunkedDownload(request.downloadId)
        recordDiagnostic(
            "event=blob_bridge_chunked_cancel downloadId=${request.downloadId} fileName=${request.fileName} " +
                "message=${request.message}"
        )
        return true
    }

    @JavascriptInterface
    fun reportDownloadFailure(payload: String?) {
        val report = try {
            controller.parseFailureReport(
                payload = payload,
                unknownMessage = unknownErrorMessage()
            )
        } catch (_: Exception) {
            DownloadFailureReport(
                fileName = controller.resolveFileName(null),
                message = unknownErrorMessage()
            )
        }

        recordDiagnostic("event=blob_bridge_reported_failure fileName=${report.fileName} error=${report.message}")
        runOnUiThread {
            onFailure(report)
        }
    }
}
