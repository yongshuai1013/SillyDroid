package com.jm.sillydroid.feature.main.ui.home.download

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import android.util.Base64
import android.util.Base64InputStream
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jm.sillydroid.feature.main.components.download.resolveDownloadFileName
import com.jm.sillydroid.feature.main.model.download.BlobDownloadSavedFile
import com.jm.sillydroid.feature.main.model.download.BlobDownloadChunkRequest
import com.jm.sillydroid.feature.main.model.download.BlobDownloadChunkedCompleteRequest
import com.jm.sillydroid.feature.main.model.download.BlobDownloadChunkedStartRequest
import com.jm.sillydroid.feature.main.model.download.BlobDownloadRequest
import com.jm.sillydroid.feature.main.model.download.BrowserDownloadRequest
import com.jm.sillydroid.feature.main.model.download.DownloadFailureReport
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import org.json.JSONObject

class BlobDownloadController(
    private val contentResolver: ContentResolver,
    private val chunkTempDirectory: File
) {
    internal companion object {
        // 每块按原始字节切片而不是先生成完整 base64；144 KiB 是 3 的倍数，
        // 拼接后的 base64 没有中间 padding，宿主侧可以严格校验总长度。
        const val WEBVIEW_BLOB_RAW_CHUNK_SIZE_BYTES: Int = 144 * 1024
    }

    private data class ChunkedDownloadSession(
        val downloadId: String,
        val fileName: String,
        val mimeType: String,
        val totalBase64Length: Long,
        val chunkCount: Int,
        val tempFile: File,
        var receivedChunkCount: Int = 0,
        var receivedBase64Length: Long = 0L
    )

    private var documentStartScriptHandler: ScriptHandler? = null
    private val chunkedDownloadSessions = LinkedHashMap<String, ChunkedDownloadSession>()

    fun installBridgeScript(webView: WebView, bridgeName: String, allowedOrigin: String) {
        installDocumentStartScript(webView, bridgeName, allowedOrigin)
        // 文档启动脚本只能覆盖“后续进入”的页面；当前已经可见的页面仍需要立刻补打一遍，
        // 这样冷启动后的首个 Tavern 页面和恢复态页面都能拿到同一份下载桥逻辑。
        webView.evaluateJavascript(buildBridgeScript(bridgeName), null)
    }

    fun close() {
        documentStartScriptHandler?.remove()
        documentStartScriptHandler = null
        cancelAllChunkedDownloads()
    }

    private fun installDocumentStartScript(
        webView: WebView,
        bridgeName: String,
        allowedOrigin: String
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return
        }

        documentStartScriptHandler?.remove()
        documentStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            buildBridgeScript(bridgeName),
            setOf(allowedOrigin)
        )
    }

    internal fun buildBridgeScript(bridgeName: String): String {
        return """
            (function() {
              const nativeBridge = window.$bridgeName;
              if (
                !nativeBridge ||
                typeof nativeBridge.beginBase64File !== 'function' ||
                typeof nativeBridge.appendBase64FileChunk !== 'function' ||
                typeof nativeBridge.completeBase64File !== 'function' ||
                typeof nativeBridge.cancelBase64File !== 'function'
              ) {
                return;
              }

              if (window.__staiAndroidDownloadBridgeInstalled) {
                return;
              }

              window.__staiAndroidDownloadBridgeInstalled = true;

              const blobStore = new Map();
              const blobCleanupTimers = new Map();
              window.__staiAndroidDownloadBlobStore = blobStore;
              const originalCreateObjectUrl = URL.createObjectURL.bind(URL);
              const originalRevokeObjectUrl = URL.revokeObjectURL.bind(URL);
              const originalElementClick = HTMLElement.prototype.click;
              const originalResponseBlob = Response.prototype.blob;

              Response.prototype.blob = function() {
                return originalResponseBlob.call(this).then(function(blob) {
                  // 真机已经证明仅靠 blob:url -> Blob 映射不够稳定；
                  // 这里把最近一次 Response.blob() 的真实 Blob 也缓存下来，
                  // 供后续导出点击在 URL 映射缺失时直接复用同一个响应体对象。
                  window.__staiAndroidRecentResponseBlob = {
                    blob: blob,
                    capturedAt: Date.now()
                  };
                  return blob;
                });
              };

              URL.createObjectURL = function(object) {
                const objectUrl = originalCreateObjectUrl(object);
                // 真机已经证明这里不能依赖 instanceof Blob：
                // createObjectURL 收到的对象虽然来自导出 blob，但在当前 WebView 上 realm 判等并不稳定，
                // 导致 blobStore 漏记，后续宿主只能退回 fetch(blob:) 并失败。
                const previousCleanupTimer = blobCleanupTimers.get(objectUrl);
                if (previousCleanupTimer) {
                  clearTimeout(previousCleanupTimer);
                  blobCleanupTimers.delete(objectUrl);
                }
                blobStore.set(objectUrl, object);
                return objectUrl;
              };

              URL.revokeObjectURL = function(objectUrl) {
                // 页面导出代码会在 a.click() 后立刻 revokeObjectURL；
                // 真机上 DownloadListener 回注发生在这之后，因此这里必须保留一个短暂缓存窗口，
                // 让宿主仍能从 blobStore 取到原始 Blob，而不是只能对已失效的 blob URL fetch 失败。
                const cleanupTimer = setTimeout(function() {
                  blobStore.delete(objectUrl);
                  blobCleanupTimers.delete(objectUrl);
                }, 15000);
                const previousCleanupTimer = blobCleanupTimers.get(objectUrl);
                if (previousCleanupTimer) {
                  clearTimeout(previousCleanupTimer);
                }
                blobCleanupTimers.set(objectUrl, cleanupTimer);
                return originalRevokeObjectUrl(objectUrl);
              };

              const rawChunkSize = $WEBVIEW_BLOB_RAW_CHUNK_SIZE_BYTES;

              function readBlobSliceAsBase64(blobSlice) {
                return new Promise(function(resolve, reject) {
                  const reader = new FileReader();
                  reader.onloadend = function() {
                    const result = typeof reader.result === 'string' ? reader.result : '';
                    const commaIndex = result.indexOf(',');
                    if (commaIndex < 0) {
                      reject(new Error('无法解析导出数据'));
                      return;
                    }
                    resolve(result.slice(commaIndex + 1));
                  };
                  reader.onerror = function() {
                    reject(reader.error || new Error('无法读取导出数据'));
                  };
                  reader.readAsDataURL(blobSlice);
                });
              }

              function resolveDownloadId() {
                return 'webview-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
              }

              function expectedBase64Length(byteLength) {
                return Math.ceil(byteLength / 3) * 4;
              }

              async function saveBlobChunked(fileName, mimeType, blob) {
                if (!blob || typeof blob.slice !== 'function') {
                  throw new Error('导出数据不是可读取文件');
                }
                const totalBytes = Number.isFinite(blob.size) ? blob.size : 0;
                const chunkCount = Math.ceil(totalBytes / rawChunkSize);
                const downloadId = resolveDownloadId();
                const basePayload = {
                  downloadId: downloadId,
                  fileName: fileName,
                  mimeType: mimeType || 'application/octet-stream'
                };
                try {
                  nativeBridge.beginBase64File(JSON.stringify({
                    downloadId: downloadId,
                    fileName: basePayload.fileName,
                    mimeType: basePayload.mimeType,
                    totalBase64Length: expectedBase64Length(totalBytes),
                    chunkCount: chunkCount
                  }));
                  for (let index = 0; index < chunkCount; index += 1) {
                    const start = index * rawChunkSize;
                    const end = Math.min(totalBytes, start + rawChunkSize);
                    const base64 = await readBlobSliceAsBase64(blob.slice(start, end));
                    nativeBridge.appendBase64FileChunk(JSON.stringify({
                      downloadId: downloadId,
                      index: index,
                      base64: base64
                    }));
                  }
                  nativeBridge.completeBase64File(JSON.stringify(basePayload));
                } catch (error) {
                  try {
                    nativeBridge.cancelBase64File(JSON.stringify({
                      downloadId: downloadId,
                      fileName: fileName,
                      message: error && error.message ? error.message : '导出失败'
                    }));
                  } catch (_) {}
                  throw error;
                }
              }

              function resolveBlob(href) {
                if (href.startsWith('blob:')) {
                  const cachedBlob = blobStore.get(href);
                  if (cachedBlob) {
                    return Promise.resolve(cachedBlob);
                  }
                  const recentResponseBlob = window.__staiAndroidRecentResponseBlob;
                  if (recentResponseBlob && recentResponseBlob.blob && (Date.now() - recentResponseBlob.capturedAt) <= 15000) {
                    return Promise.resolve(recentResponseBlob.blob);
                  }
                  return fetch(href).then(function(response) {
                    return response.blob();
                  });
                }

                if (href.startsWith('data:')) {
                  return fetch(href).then(function(response) {
                    return response.blob();
                  });
                }

                return Promise.resolve(null);
              }

              function saveBlobUrl(downloadUrl, fileName) {
                nativeBridge.onBlobDownloadPreparing(fileName);

                resolveBlob(downloadUrl)
                  .then(function(blob) {
                    if (!blob) {
                      throw new Error('未找到导出数据');
                    }

                    return saveBlobChunked(fileName, blob.type || '', blob);
                  })
                  .catch(function(error) {
                    nativeBridge.reportDownloadFailure(JSON.stringify({
                      fileName: fileName,
                      message: error && error.message ? error.message : '导出失败'
                    }));
                  });
              }

              window.__staiAndroidDownloadBridgeCaptureUrl = function(downloadUrl, fileNameHint) {
                if (!downloadUrl || (!downloadUrl.startsWith('blob:') && !downloadUrl.startsWith('data:'))) {
                  return false;
                }

                const fileName = (fileNameHint || '').trim() || 'download';
                saveBlobUrl(downloadUrl, fileName);
                return true;
              };

              function interceptAnchorDownload(anchor) {
                if (!anchor) {
                  return false;
                }

                const href = anchor.href || '';
                if (!href.startsWith('blob:') && !href.startsWith('data:')) {
                  return false;
                }

                const fileName = (anchor.getAttribute('download') || '').trim() || 'download';
                window.__staiAndroidDownloadBridgeCaptureUrl(href, fileName);

                return true;
              }

              // 真机已经证明确实存在“a.click() 触发了 DownloadListener，但没有命中旧桥脚本”的路径；
              // 这里改拦 HTMLElement.prototype.click，确保程序化点击也走同一条 blob 保存链。
              HTMLElement.prototype.click = function() {
                if (this instanceof HTMLAnchorElement && interceptAnchorDownload(this)) {
                  return;
                }

                return originalElementClick.call(this);
              };

              document.addEventListener('click', function(event) {
                const target = event.target;
                const anchor = target && typeof target.closest === 'function' ? target.closest('a[href]') : null;
                if (!anchor || !interceptAnchorDownload(anchor)) {
                  return;
                }

                event.preventDefault();
                event.stopPropagation();
              }, true);
            })();
            """.trimIndent()
    }

    fun captureFromDownloadListener(
        webView: WebView,
        bridgeName: String,
        request: BrowserDownloadRequest,
        diagnosticSink: (String) -> Unit = {}
    ) {
        val fileNameHint = resolveFileName(
            URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)
        )
        val captureScript = """
            (function() {
              const nativeBridge = window[${
            JSONObject.quote(bridgeName)
        }];
              const downloadUrl = ${JSONObject.quote(request.url)};
              const fileNameHint = ${JSONObject.quote(fileNameHint)};

              if (
                !nativeBridge ||
                typeof nativeBridge.beginBase64File !== 'function' ||
                typeof nativeBridge.appendBase64FileChunk !== 'function' ||
                typeof nativeBridge.completeBase64File !== 'function' ||
                typeof nativeBridge.cancelBase64File !== 'function'
              ) {
                return JSON.stringify({ status: 'bridge_missing' });
              }

              const installedCapture = window.__staiAndroidDownloadBridgeCaptureUrl;
              if (typeof installedCapture === 'function') {
                return JSON.stringify({
                  status: installedCapture(downloadUrl, fileNameHint) ? 'capture_started' : 'capture_rejected',
                  source: 'installed_helper'
                });
              }

              const rawChunkSize = $WEBVIEW_BLOB_RAW_CHUNK_SIZE_BYTES;

              function readBlobSliceAsBase64(blobSlice) {
                return new Promise(function(resolve, reject) {
                  const reader = new FileReader();
                  reader.onloadend = function() {
                    const result = typeof reader.result === 'string' ? reader.result : '';
                    const commaIndex = result.indexOf(',');
                    if (commaIndex < 0) {
                      reject(new Error('无法解析导出数据'));
                      return;
                    }
                    resolve(result.slice(commaIndex + 1));
                  };
                  reader.onerror = function() {
                    reject(reader.error || new Error('无法读取导出数据'));
                  };
                  reader.readAsDataURL(blobSlice);
                });
              }

              function resolveDownloadId() {
                return 'webview-listener-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
              }

              function expectedBase64Length(byteLength) {
                return Math.ceil(byteLength / 3) * 4;
              }

              async function saveBlobChunked(fileName, mimeType, blob) {
                if (!blob || typeof blob.slice !== 'function') {
                  throw new Error('导出数据不是可读取文件');
                }
                const totalBytes = Number.isFinite(blob.size) ? blob.size : 0;
                const chunkCount = Math.ceil(totalBytes / rawChunkSize);
                const downloadId = resolveDownloadId();
                const basePayload = {
                  downloadId: downloadId,
                  fileName: fileName,
                  mimeType: mimeType || 'application/octet-stream'
                };
                try {
                  nativeBridge.beginBase64File(JSON.stringify({
                    downloadId: downloadId,
                    fileName: basePayload.fileName,
                    mimeType: basePayload.mimeType,
                    totalBase64Length: expectedBase64Length(totalBytes),
                    chunkCount: chunkCount
                  }));
                  for (let index = 0; index < chunkCount; index += 1) {
                    const start = index * rawChunkSize;
                    const end = Math.min(totalBytes, start + rawChunkSize);
                    const base64 = await readBlobSliceAsBase64(blob.slice(start, end));
                    nativeBridge.appendBase64FileChunk(JSON.stringify({
                      downloadId: downloadId,
                      index: index,
                      base64: base64
                    }));
                  }
                  nativeBridge.completeBase64File(JSON.stringify(basePayload));
                } catch (error) {
                  try {
                    nativeBridge.cancelBase64File(JSON.stringify({
                      downloadId: downloadId,
                      fileName: fileName,
                      message: error && error.message ? error.message : '导出失败'
                    }));
                  } catch (_) {}
                  throw error;
                }
              }

              nativeBridge.onBlobDownloadPreparing(fileNameHint);
              fetch(downloadUrl)
                .then(function(response) { return response.blob(); })
                .then(function(blob) {
                  return saveBlobChunked(fileNameHint, blob.type || ${JSONObject.quote(request.mimeType)}, blob);
                })
                .catch(function(error) {
                  nativeBridge.reportDownloadFailure(JSON.stringify({
                    fileName: fileNameHint,
                    message: error && error.message ? error.message : '导出失败'
                  }));
                });
              return JSON.stringify({ status: 'capture_started', source: 'direct_fetch' });
            })();
        """.trimIndent()

        webView.evaluateJavascript(captureScript) { result ->
            runCatching {
                diagnosticSink(
                    "event=download_listener_blob_capture_eval fileName=$fileNameHint result=${result.orEmpty().ifBlank { "null" }}"
                )
            }
        }
    }

    fun resolveFileName(rawName: String?): String {
        return resolveDownloadFileName(rawName, fallbackUrl = null)
    }

    fun parseDownloadRequest(payload: String?): BlobDownloadRequest? {
        if (payload.isNullOrBlank()) {
            return null
        }

        val json = JSONObject(payload)
        val fileName = resolveFileName(json.optString("fileName"))
        val mimeType = json.optString("mimeType").trim().ifBlank { "application/octet-stream" }
        val base64Data = json.optString("base64").trim()
        if (base64Data.isBlank()) {
            return null
        }

        return BlobDownloadRequest(fileName = fileName, mimeType = mimeType, base64Data = base64Data)
    }

    fun parseChunkedStartRequest(payload: String?): BlobDownloadChunkedStartRequest? {
        if (payload.isNullOrBlank()) {
            return null
        }

        val json = JSONObject(payload)
        val downloadId = json.optString("downloadId").trim()
        val fileName = resolveFileName(json.optString("fileName"))
        val mimeType = json.optString("mimeType").trim().ifBlank { "application/octet-stream" }
        val totalBase64Length = json.optLong("totalBase64Length", 0L)
        val chunkCount = json.optInt("chunkCount", 0)
        if (downloadId.isBlank() || totalBase64Length < 0L || chunkCount < 0) {
            return null
        }

        return BlobDownloadChunkedStartRequest(
            downloadId = downloadId,
            fileName = fileName,
            mimeType = mimeType,
            totalBase64Length = totalBase64Length,
            chunkCount = chunkCount
        )
    }

    fun parseChunkRequest(payload: String?): BlobDownloadChunkRequest? {
        if (payload.isNullOrBlank()) {
            return null
        }

        val json = JSONObject(payload)
        val downloadId = json.optString("downloadId").trim()
        val index = json.optInt("index", -1)
        val base64Data = json.optString("base64").trim()
        if (downloadId.isBlank() || index < 0 || base64Data.isBlank()) {
            return null
        }

        return BlobDownloadChunkRequest(downloadId = downloadId, index = index, base64Data = base64Data)
    }

    fun parseChunkedCompleteRequest(payload: String?): BlobDownloadChunkedCompleteRequest? {
        if (payload.isNullOrBlank()) {
            return null
        }

        val json = JSONObject(payload)
        val downloadId = json.optString("downloadId").trim()
        if (downloadId.isBlank()) {
            return null
        }

        return BlobDownloadChunkedCompleteRequest(
            downloadId = downloadId,
            fileName = resolveFileName(json.optString("fileName")),
            message = json.optString("message").trim()
        )
    }

    fun parseFailureReport(payload: String?, unknownMessage: String): DownloadFailureReport {
        if (payload.isNullOrBlank()) {
            return DownloadFailureReport(
                fileName = resolveFileName(null),
                message = unknownMessage
            )
        }

        val json = JSONObject(payload)
        return DownloadFailureReport(
            fileName = resolveFileName(json.optString("fileName")),
            message = json.optString("message").trim().ifBlank { unknownMessage }
        )
    }

    suspend fun persist(request: BlobDownloadRequest): BlobDownloadSavedFile {
        return persistBytes(
            fileName = request.fileName,
            mimeType = request.mimeType,
            writeBytes = { output ->
                output.write(Base64.decode(request.base64Data, Base64.DEFAULT))
            }
        )
    }

    @Synchronized
    fun beginChunkedDownload(request: BlobDownloadChunkedStartRequest) {
        val existingSession = chunkedDownloadSessions.remove(request.downloadId)
        existingSession?.tempFile?.delete()
        chunkTempDirectory.mkdirs()
        val safeDownloadId = request.downloadId
            .filter { char -> char.isLetterOrDigit() || char == '-' || char == '_' }
            .take(24)
            .ifBlank { "download" }
        val tempFile = File.createTempFile("blob-$safeDownloadId-", ".base64", chunkTempDirectory)
        chunkedDownloadSessions[request.downloadId] = ChunkedDownloadSession(
            downloadId = request.downloadId,
            fileName = request.fileName,
            mimeType = request.mimeType,
            totalBase64Length = request.totalBase64Length,
            chunkCount = request.chunkCount,
            tempFile = tempFile
        )
    }

    @Synchronized
    fun appendChunkedDownload(request: BlobDownloadChunkRequest) {
        val session = chunkedDownloadSessions[request.downloadId]
            ?: throw IllegalStateException("chunked download session not found")
        if (request.index != session.receivedChunkCount) {
            throw IllegalStateException("chunked download out of order")
        }
        if (request.index >= session.chunkCount) {
            throw IllegalStateException("chunked download index overflow")
        }

        session.tempFile.appendBytes(request.base64Data.toByteArray(StandardCharsets.US_ASCII))
        session.receivedChunkCount += 1
        session.receivedBase64Length += request.base64Data.length.toLong()
        if (session.receivedBase64Length > session.totalBase64Length) {
            throw IllegalStateException("chunked download exceeded expected length")
        }
    }

    @Synchronized
    fun cancelChunkedDownload(downloadId: String) {
        val session = chunkedDownloadSessions.remove(downloadId)
        session?.tempFile?.delete()
    }

    @Synchronized
    private fun takeCompletedChunkedDownload(downloadId: String): ChunkedDownloadSession {
        val session = chunkedDownloadSessions.remove(downloadId)
            ?: throw IllegalStateException("chunked download session not found")
        if (session.receivedChunkCount != session.chunkCount) {
            session.tempFile.delete()
            throw IllegalStateException("chunked download incomplete")
        }
        if (session.receivedBase64Length != session.totalBase64Length) {
            session.tempFile.delete()
            throw IllegalStateException("chunked download length mismatch")
        }
        return session
    }

    suspend fun persistChunked(downloadId: String): BlobDownloadSavedFile {
        val session = takeCompletedChunkedDownload(downloadId)
        return try {
            persistBytes(
                fileName = session.fileName,
                mimeType = session.mimeType,
                writeBytes = { output ->
                    FileInputStream(session.tempFile).use { rawInput ->
                        Base64InputStream(rawInput, Base64.DEFAULT).use { decodedInput ->
                            decodedInput.copyTo(output)
                        }
                    }
                }
            )
        } finally {
            session.tempFile.delete()
        }
    }

    @Synchronized
    private fun cancelAllChunkedDownloads() {
        chunkedDownloadSessions.values.forEach { session -> session.tempFile.delete() }
        chunkedDownloadSessions.clear()
    }

    suspend fun persistStream(
        rawFileName: String?,
        fallbackUrl: String?,
        mimeType: String,
        inputStream: InputStream
    ): BlobDownloadSavedFile {
        val fileName = resolveFileName(
            rawName = rawFileName ?: URLUtil.guessFileName(fallbackUrl.orEmpty(), null, mimeType)
        )
        val resolvedMimeType = mimeType.trim().ifBlank { "application/octet-stream" }
        return persistBytes(
            fileName = fileName,
            mimeType = resolvedMimeType,
            writeBytes = { output ->
                inputStream.use { input -> input.copyTo(output) }
            }
        )
    }

    private fun persistBytes(
        fileName: String,
        mimeType: String,
        writeBytes: (java.io.OutputStream) -> Unit
    ): BlobDownloadSavedFile {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val targetUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建下载文件")

        try {
            contentResolver.openOutputStream(targetUri)?.use { output ->
                writeBytes(output)
                output.flush()
            } ?: throw IllegalStateException("无法写入下载文件")

            val completedValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            contentResolver.update(targetUri, completedValues, null, null)
            val savedFileName = resolvePersistedDisplayName(targetUri, fileName)
            return BlobDownloadSavedFile(
                fileName = savedFileName,
                mimeType = mimeType,
                contentUri = targetUri.toString(),
                displayPath = buildDownloadsDisplayPath(savedFileName)
            )
        } catch (error: Exception) {
            contentResolver.delete(targetUri, null, null)
            throw error
        }
    }

    private fun resolvePersistedDisplayName(targetUri: android.net.Uri, fallbackFileName: String): String {
        return contentResolver.query(
            targetUri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)?.trim()?.takeIf { name -> name.isNotEmpty() }
            } else {
                null
            }
        } ?: fallbackFileName
    }

    @Suppress("DEPRECATION")
    private fun buildDownloadsDisplayPath(fileName: String): String {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .resolve(fileName)
            .absolutePath
    }
}
