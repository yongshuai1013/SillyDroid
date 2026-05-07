package com.stai.sillytavern

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.absoluteValue

class MainActivity : AppCompatActivity() {
    private data class BrowserDownloadRequest(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String
    )

    private data class BlobDownloadRequest(
        val fileName: String,
        val mimeType: String,
        val base64Data: String
    )

    private data class DownloadFailureReport(
        val fileName: String,
        val message: String
    )

    private data class SystemNotificationRequest(
        val notificationId: String,
        val title: String,
        val body: String,
        val tag: String
    )

    companion object {
        private const val downloadBridgeName = "AndroidDownloadBridge"
        private const val webViewStateKey = "tavern.webview.state"
        private const val loadedUrlStateKey = "tavern.webview.loadedUrl"
        private const val webSessionBridgeName = "StaiWebSessionBridge"
        private const val systemNotificationBridgeName = "AndroidSystemNotificationBridge"
        private const val webSessionStoragePreferencesName = "stai-webview-session"
        private const val webSessionStorageSnapshotKey = "session-storage"
    }

    private lateinit var contentRoot: View
    private lateinit var webView: WebView
    private lateinit var bootstrapOverlay: View
    private lateinit var bootstrapStatus: TextView
    private lateinit var bootstrapRetry: Button
    private lateinit var bootstrapSettingsButton: ImageButton
    private lateinit var bootstrapProgress: ProgressBar
    private lateinit var backPressCallback: OnBackPressedCallback
    private var webSessionScriptHandler: ScriptHandler? = null
    private var loadedUrl = ""
    private var hasRestoredWebViewState = false
    private var skipNextWebViewStateRestore = false
    private var isOpeningBootstrapSettings = false
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val webSessionStoragePreferences by lazy {
        getSharedPreferences(webSessionStoragePreferencesName, MODE_PRIVATE)
    }
    private val downloadManager by lazy { getSystemService(DownloadManager::class.java) }
    // Android 13+ 的宿主通知需要显式运行时授权，否则 NotificationManager 会直接拒发。
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingFileChooserCallback ?: return@registerForActivityResult
        pendingFileChooserCallback = null
        callback.onReceiveValue(resolveFileChooserUris(result.resultCode, result.data))
    }
    private val bootstrapSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isOpeningBootstrapSettings = false
        if (result.resultCode == Activity.RESULT_OK && BootstrapSettingsActivity.shouldStartBootstrap(result.data)) {
            skipNextWebViewStateRestore = true
            hasRestoredWebViewState = false
            loadedUrl = ""
            recreate()
            return@registerForActivityResult
        }

        if (StartupRuntimeStore.state.value.phase == StartupPhase.CONFIGURING) {
            startBootstrap(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        bindViews()
        applySystemBarInsets()
        ensureSystemNotificationChannel()
        requestNotificationPermissionIfNeeded()
        configureWebView()
        registerBackPressHandler()
        restoreWebViewState(savedInstanceState)
        observeBootstrapState()
        bootstrapRetry.setOnClickListener { startBootstrap(true) }
        bootstrapSettingsButton.setOnClickListener { openBootstrapSettings() }
        startBootstrap(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (skipNextWebViewStateRestore) {
            outState.remove(webViewStateKey)
            outState.remove(loadedUrlStateKey)
            return
        }

        // Activity 被系统回收后，优先恢复 WebView 现有会话，避免重新 load baseUrl 把页面打回首页。
        val webViewState = Bundle()
        webView.saveState(webViewState)
        outState.putBundle(webViewStateKey, webViewState)
        outState.putString(loadedUrlStateKey, loadedUrl)
    }

    override fun onDestroy() {
        webSessionScriptHandler?.remove()
        webSessionScriptHandler = null
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = null
        skipNextWebViewStateRestore = false
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun registerBackPressHandler() {
        backPressCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 酒馆宿主与 server apk 一样，返回键默认退到桌面保留当前 task，避免 finish Activity 后重进直接冷启动 WebView。
                moveTaskToBack(true)
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressCallback)
    }

    private fun bindViews() {
        contentRoot = findViewById(R.id.contentRoot)
        webView = findViewById(R.id.webView)
        bootstrapOverlay = findViewById(R.id.bootstrapOverlay)
        bootstrapStatus = findViewById(R.id.bootstrapStatus)
        bootstrapRetry = findViewById(R.id.bootstrapRetry)
        bootstrapSettingsButton = findViewById(R.id.bootstrapSettingsButton)
        bootstrapProgress = findViewById(R.id.bootstrapProgress)
    }

    private fun applySystemBarInsets() {
        val initialLeftPadding = contentRoot.paddingLeft
        val initialTopPadding = contentRoot.paddingTop
        val initialRightPadding = contentRoot.paddingRight
        val initialBottomPadding = contentRoot.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { view, insets ->
            val systemBarsInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initialLeftPadding + systemBarsInsets.left,
                initialTopPadding + systemBarsInsets.top,
                initialRightPadding + systemBarsInsets.right,
                initialBottomPadding + systemBarsInsets.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(contentRoot)
    }

    private fun configureWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 退到后台时不要把 WebView renderer 主动降成 waived，尽量降低返回前台后整页被系统重载、前端重新初始化的概率。
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
        }
        installWebSessionPersistenceBridge()
        // Tavern 页面里的导出既可能是普通 URL，也可能是 blob/data；宿主在这里统一接管保存到系统下载目录。
        webView.addJavascriptInterface(BlobDownloadBridge(), downloadBridgeName)
        // 浏览器通知统一走宿主桥，避免 Android WebView 里再退回不可用的 Notification API。
        webView.addJavascriptInterface(SystemNotificationBridge(), systemNotificationBridgeName)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 冷启动后主动把 WebView 新写入的 cookie 落盘，避免后台回收前只保存在内存里。
                CookieManager.getInstance().flush()
                installBlobDownloadBridge()
                if (!url.isNullOrBlank()) {
                    loadedUrl = url
                }
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handlePageDownload(
                BrowserDownloadRequest(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType
                )
            )
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) {
                    return false
                }

                // WebView 的 input[type=file] 只会经由 WebChromeClient 回调到宿主；这里把系统文件选择结果原样回传给页面。
                pendingFileChooserCallback?.onReceiveValue(null)
                pendingFileChooserCallback = filePathCallback
                fileChooserLauncher.launch(fileChooserParams.createIntent())
                return true
            }
        }
    }

    private fun installWebSessionPersistenceBridge() {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "当前设备的 Android System WebView 不支持 WebMessageListener，无法固化 WebView sessionStorage。"
        }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            "当前设备的 Android System WebView 不支持 document-start script，无法在页面初始化前恢复 sessionStorage。"
        }

        val allowedOriginRules = setOf(BootConfig.localServiceUrl(this))
        // 宿主只在本地 Tavern 页面注入这条桥，避免把 sessionStorage 固化能力暴露到其他来源。
        WebViewCompat.addWebMessageListener(
            webView,
            webSessionBridgeName,
            allowedOriginRules,
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                        if (!isMainFrame || sourceOrigin.toString() != BootConfig.localServiceUrl(this@MainActivity)) {
                        return
                    }

                    persistWebStateChange(message.data)
                }
            }
        )
        refreshWebSessionPersistenceScript()
    }

    private fun refreshWebSessionPersistenceScript() {
        webSessionScriptHandler?.remove()
        // 这段脚本必须跑在 Tavern 业务脚本之前，先恢复上次的 sessionStorage，再监听后续变化持久化回宿主。
        webSessionScriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            buildWebSessionPersistenceScript(),
            setOf(BootConfig.localServiceUrl(this))
        )
    }

    private fun buildWebSessionPersistenceScript(): String {
        val persistedSnapshot = JSONObject.quote(readPersistedWebSessionSnapshot())

        return """
            (function() {
                const persistedPayload = $persistedSnapshot;
                const nativeBridge = globalThis.$webSessionBridgeName;

                const restoredEntries = persistedPayload ? JSON.parse(persistedPayload) : {};
                sessionStorage.clear();
                for (const [key, value] of Object.entries(restoredEntries)) {
                    if (typeof value === 'string') {
                        sessionStorage.setItem(key, value);
                    }
                }

                if (globalThis.__staiAndroidSessionPersistenceInstalled) {
                    return;
                }

                globalThis.__staiAndroidSessionPersistenceInstalled = true;

                const collectSessionStorage = function() {
                    const snapshot = {};
                    for (let index = 0; index < sessionStorage.length; index += 1) {
                        const key = sessionStorage.key(index);
                        if (typeof key === 'string') {
                            snapshot[key] = sessionStorage.getItem(key) ?? '';
                        }
                    }
                    return snapshot;
                };

                const publishStateChange = function(type) {
                    nativeBridge.postMessage(JSON.stringify({
                        type,
                        sessionStorage: collectSessionStorage()
                    }));
                };

                const originalSetItem = sessionStorage.setItem.bind(sessionStorage);
                sessionStorage.setItem = function(key, value) {
                    const result = originalSetItem(key, value);
                    publishStateChange('sessionStorage');
                    return result;
                };

                const originalRemoveItem = sessionStorage.removeItem.bind(sessionStorage);
                sessionStorage.removeItem = function(key) {
                    const result = originalRemoveItem(key);
                    publishStateChange('sessionStorage');
                    return result;
                };

                const originalClear = sessionStorage.clear.bind(sessionStorage);
                sessionStorage.clear = function() {
                    const result = originalClear();
                    publishStateChange('sessionStorage');
                    return result;
                };

                addEventListener('pagehide', function() {
                    publishStateChange('pagehide');
                }, true);
                document.addEventListener('visibilitychange', function() {
                    if (document.visibilityState === 'hidden') {
                        publishStateChange('visibilitychange');
                    }
                }, true);

                ${buildAndroidHostNotificationShimScript()}
            })();
        """.trimIndent()
    }

    private fun buildAndroidHostNotificationShimScript(): String {
        return """
            const nativeNotificationBridge = globalThis.$systemNotificationBridgeName;
            if (nativeNotificationBridge && !globalThis.__staiAndroidHostNotificationInstalled) {
                globalThis.__staiAndroidHostNotificationInstalled = true;

                // 第三方 Tavern 前端只认识浏览器 Notification API；这里把它直接映射成宿主原生通知。
                const createNotificationEvent = function(type) {
                    return typeof Event === 'function' ? new Event(type) : { type: type };
                };

                class AndroidHostNotification {
                    constructor(title, options) {
                        const normalizedOptions = options && typeof options === 'object' ? options : {};
                        this.title = String(title ?? '');
                        this.body = typeof normalizedOptions.body === 'string' ? normalizedOptions.body : '';
                        this.tag = typeof normalizedOptions.tag === 'string' ? normalizedOptions.tag : '';
                        this.data = normalizedOptions.data;
                        this.icon = typeof normalizedOptions.icon === 'string' ? normalizedOptions.icon : '';
                        this.onclick = null;
                        this.onerror = null;
                        this.onshow = null;
                        this.onclose = null;
                        this.listeners = new Map();

                        const shown = nativeNotificationBridge.showNotification(JSON.stringify({
                            notificationId: this.tag || this.title,
                            title: this.title,
                            body: this.body,
                            tag: this.tag
                        }));

                        Promise.resolve().then(() => {
                            this.dispatchEvent(createNotificationEvent(shown ? 'show' : 'error'));
                        });
                    }

                    addEventListener(type, listener) {
                        if (typeof listener !== 'function') {
                            return;
                        }

                        const currentListeners = this.listeners.get(type) || [];
                        currentListeners.push(listener);
                        this.listeners.set(type, currentListeners);
                    }

                    removeEventListener(type, listener) {
                        const currentListeners = this.listeners.get(type) || [];
                        this.listeners.set(type, currentListeners.filter((item) => item !== listener));
                    }

                    dispatchEvent(event) {
                        const currentListeners = this.listeners.get(event.type) || [];
                        for (const listener of currentListeners) {
                            listener.call(this, event);
                        }

                        const handler = this['on' + event.type];
                        if (typeof handler === 'function') {
                            handler.call(this, event);
                        }

                        return true;
                    }

                    close() {
                        this.dispatchEvent(createNotificationEvent('close'));
                    }

                    static requestPermission(callback) {
                        const permission = nativeNotificationBridge.requestPermission();
                        if (typeof callback === 'function') {
                            callback(permission);
                        }
                        return Promise.resolve(permission);
                    }
                }

                Object.defineProperty(AndroidHostNotification, 'permission', {
                    configurable: true,
                    enumerable: true,
                    get() {
                        return nativeNotificationBridge.permissionState();
                    }
                });
                Object.defineProperty(AndroidHostNotification, 'maxActions', {
                    configurable: true,
                    enumerable: true,
                    value: 0
                });

                globalThis.Notification = AndroidHostNotification;
                if (typeof window !== 'undefined') {
                    window.Notification = AndroidHostNotification;
                }
            }
        """.trimIndent()
    }

    private fun readPersistedWebSessionSnapshot(): String {
        return webSessionStoragePreferences.getString(webSessionStorageSnapshotKey, "{}") ?: "{}"
    }

    private fun persistWebStateChange(payload: String?) {
        val changeEnvelope = if (payload.isNullOrBlank()) JSONObject() else JSONObject(payload)
        val sessionStorageSnapshot = changeEnvelope.optJSONObject("sessionStorage") ?: JSONObject()

        persistWebSessionSnapshot(sessionStorageSnapshot)
        // WebView 状态变更后顺手 flush cookie，尽量避免后台被系统回收前 cookie 仍只留在内存里。
        CookieManager.getInstance().flush()
    }

    private fun persistWebSessionSnapshot(snapshot: JSONObject) {
        val normalizedSnapshot = JSONObject()
        val keys = snapshot.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            normalizedSnapshot.put(key, snapshot.optString(key))
        }

        // 宿主只保存当前 Tavern origin 的 sessionStorage 快照；下次 WebView 冷启动会在页面业务脚本执行前恢复这份状态。
        webSessionStoragePreferences.edit()
            .putString(webSessionStorageSnapshotKey, normalizedSnapshot.toString())
            .commit()
    }

    private fun installBlobDownloadBridge() {
        webView.evaluateJavascript(
            """
            (function() {
              const nativeBridge = window.$downloadBridgeName;
              if (!nativeBridge || typeof nativeBridge.saveBase64File !== 'function') {
                return;
              }

              if (window.__staiAndroidDownloadBridgeInstalled) {
                return;
              }

              window.__staiAndroidDownloadBridgeInstalled = true;

              const blobStore = new Map();
              const originalCreateObjectUrl = URL.createObjectURL.bind(URL);
              const originalRevokeObjectUrl = URL.revokeObjectURL.bind(URL);

              URL.createObjectURL = function(object) {
                const objectUrl = originalCreateObjectUrl(object);
                if (object instanceof Blob) {
                  blobStore.set(objectUrl, object);
                }
                return objectUrl;
              };

              URL.revokeObjectURL = function(objectUrl) {
                blobStore.delete(objectUrl);
                return originalRevokeObjectUrl(objectUrl);
              };

              function readBlobAsBase64(blob) {
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
                  reader.readAsDataURL(blob);
                });
              }

              function resolveBlob(href) {
                if (href.startsWith('blob:')) {
                  return Promise.resolve(blobStore.get(href) || null);
                }

                if (href.startsWith('data:')) {
                  return fetch(href).then(function(response) {
                    return response.blob();
                  });
                }

                return Promise.resolve(null);
              }

              document.addEventListener('click', function(event) {
                const target = event.target;
                const anchor = target && typeof target.closest === 'function' ? target.closest('a[href]') : null;
                if (!anchor) {
                  return;
                }

                const href = anchor.href || '';
                if (!href.startsWith('blob:') && !href.startsWith('data:')) {
                  return;
                }

                event.preventDefault();
                event.stopPropagation();

                const fileName = (anchor.getAttribute('download') || '').trim() || 'download';
                nativeBridge.onBlobDownloadPreparing(fileName);

                resolveBlob(href)
                  .then(function(blob) {
                    if (!blob) {
                      throw new Error('未找到导出数据');
                    }

                    return readBlobAsBase64(blob).then(function(base64) {
                      nativeBridge.saveBase64File(JSON.stringify({
                        fileName: fileName,
                        mimeType: blob.type || '',
                        base64: base64
                      }));
                    });
                  })
                  .catch(function(error) {
                    nativeBridge.reportDownloadFailure(JSON.stringify({
                      fileName: fileName,
                      message: error && error.message ? error.message : '导出失败'
                    }));
                  });
              }, true);
            })();
            """.trimIndent(),
            null
        )
    }

    @Suppress("DEPRECATION")
    private fun handlePageDownload(request: BrowserDownloadRequest) {
        val targetUrl = request.url.trim()
        if (targetUrl.isBlank() || targetUrl.startsWith("blob:") || targetUrl.startsWith("data:")) {
            return
        }

        val fileName = resolveDownloadFileName(
            rawName = URLUtil.guessFileName(targetUrl, request.contentDisposition, request.mimeType),
            fallbackUrl = targetUrl
        )

        try {
            val systemRequest = DownloadManager.Request(Uri.parse(targetUrl)).apply {
                // 普通 URL 下载直接交给系统 DownloadManager，避免 WebView 自己吞掉下载请求。
                setTitle(fileName)
                setDescription(getString(R.string.download_status_pending, fileName))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setVisibleInDownloadsUi(true)
                setMimeType(request.mimeType.ifBlank { "application/octet-stream" })

                val cookieHeader = CookieManager.getInstance().getCookie(targetUrl)
                if (!cookieHeader.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookieHeader)
                }

                if (request.userAgent.isNotBlank()) {
                    addRequestHeader("User-Agent", request.userAgent)
                }

                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            downloadManager.enqueue(systemRequest)
            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            showDownloadFailure(
                DownloadFailureReport(
                    fileName = fileName,
                    message = error.message ?: getString(R.string.download_failed_unknown)
                )
            )
        }
    }

    private fun resolveDownloadFileName(rawName: String?, fallbackUrl: String?): String {
        val candidate = rawName.orEmpty().trim()
        if (candidate.isNotBlank()) {
            return candidate.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        }

        if (!fallbackUrl.isNullOrBlank()) {
            return URLUtil.guessFileName(fallbackUrl, null, null)
        }

        return "download"
    }

    private fun buildDownloadFailureMessage(report: DownloadFailureReport): String {
        val details = report.message.trim().ifBlank { getString(R.string.download_failed_unknown) }
        return getString(R.string.download_failed, report.fileName, details)
    }

    private fun showDownloadFailure(report: DownloadFailureReport) {
        Toast.makeText(this, buildDownloadFailureMessage(report), Toast.LENGTH_LONG).show()
    }

    private fun parseBlobDownloadRequest(payload: String?): BlobDownloadRequest? {
        if (payload.isNullOrBlank()) {
            return null
        }

        val json = JSONObject(payload)
        val fileName = resolveDownloadFileName(json.optString("fileName"), null)
        val mimeType = json.optString("mimeType").trim().ifBlank { "application/octet-stream" }
        val base64Data = json.optString("base64").trim()
        if (base64Data.isBlank()) {
            return null
        }

        return BlobDownloadRequest(fileName = fileName, mimeType = mimeType, base64Data = base64Data)
    }

    private fun parseDownloadFailureReport(payload: String?): DownloadFailureReport {
        if (payload.isNullOrBlank()) {
            return DownloadFailureReport(
                fileName = resolveDownloadFileName(null, null),
                message = getString(R.string.download_failed_unknown)
            )
        }

        val json = JSONObject(payload)
        return DownloadFailureReport(
            fileName = resolveDownloadFileName(json.optString("fileName"), null),
            message = json.optString("message").trim().ifBlank { getString(R.string.download_failed_unknown) }
        )
    }

    private suspend fun persistBlobDownload(request: BlobDownloadRequest): String {
        return withContext(Dispatchers.IO) {
            val resolver = applicationContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, request.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, request.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            // blob/data 导出没有真实 URL，宿主直接写入系统 Downloads 提供者，保证用户能在下载目录里看到文件。
            val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建下载文件")

            try {
                resolver.openOutputStream(targetUri)?.use { output ->
                    output.write(Base64.decode(request.base64Data, Base64.DEFAULT))
                    output.flush()
                } ?: throw IllegalStateException("无法写入下载文件")

                val completedValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(targetUri, completedValues, null, null)
                request.fileName
            } catch (error: Exception) {
                resolver.delete(targetUri, null, null)
                throw error
            }
        }
    }

    private inner class BlobDownloadBridge {
        @JavascriptInterface
        fun onBlobDownloadPreparing(fileName: String?) {
            val resolvedFileName = resolveDownloadFileName(fileName, null)
            runOnUiThread {
                Toast.makeText(this@MainActivity, getString(R.string.download_status_preparing, resolvedFileName), Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun saveBase64File(payload: String?) {
            val request = try {
                parseBlobDownloadRequest(payload)
            } catch (error: Exception) {
                null
            }

            if (request == null) {
                reportDownloadFailure(
                    JSONObject()
                        .put("fileName", resolveDownloadFileName(null, null))
                        .put("message", getString(R.string.download_failed_empty_payload))
                        .toString()
                )
                return
            }

            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, getString(R.string.download_status_saving, request.fileName), Toast.LENGTH_SHORT).show()

                val result = runCatching {
                    persistBlobDownload(request)
                }

                result.onSuccess { fileName ->
                    Toast.makeText(this@MainActivity, getString(R.string.download_saved, fileName), Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    showDownloadFailure(
                        DownloadFailureReport(
                            fileName = request.fileName,
                            message = error.message ?: getString(R.string.download_failed_unknown)
                        )
                    )
                }
            }
        }

        @JavascriptInterface
        fun reportDownloadFailure(payload: String?) {
            val report = try {
                parseDownloadFailureReport(payload)
            } catch (error: Exception) {
                DownloadFailureReport(
                    fileName = resolveDownloadFileName(null, null),
                    message = getString(R.string.download_failed_unknown)
                )
            }

            runOnUiThread {
                showDownloadFailure(report)
            }
        }
    }

    private inner class SystemNotificationBridge {
        @JavascriptInterface
        fun showNotification(payload: String?): Boolean {
            val request = parseSystemNotificationRequest(payload) ?: return false

            if (!canPostSystemNotifications()) {
                runOnUiThread { requestNotificationPermissionIfNeeded() }
                return false
            }

            ensureSystemNotificationChannel()
            return showSystemNotification(request)
        }

        @JavascriptInterface
        fun permissionState(): String {
            return resolveNotificationPermissionState()
        }

        @JavascriptInterface
        fun requestPermission(): String {
            runOnUiThread { requestNotificationPermissionIfNeeded() }
            return resolveNotificationPermissionState()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun ensureSystemNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            BootConfig.systemNotificationChannelId,
            getString(R.string.system_notification_channel_title),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.system_notification_channel_description)
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun canPostSystemNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolveNotificationPermissionState(): String {
        return if (canPostSystemNotifications()) "granted" else "default"
    }

    private fun parseSystemNotificationRequest(payload: String?): SystemNotificationRequest? {
        val normalizedPayload = payload?.trim().orEmpty()
        if (normalizedPayload.isBlank()) {
            return null
        }

        val json = JSONObject(normalizedPayload)
        val title = json.optString("title").trim().ifBlank { "通知" }
        val body = json.optString("body").trim()
        val notificationId = json.optString("notificationId").trim()
        val tag = json.optString("tag").trim()
        return SystemNotificationRequest(
            notificationId = notificationId,
            title = title,
            body = body,
            tag = tag
        )
    }

    private fun showSystemNotification(request: SystemNotificationRequest): Boolean {
        val notification = NotificationCompat.Builder(this, BootConfig.systemNotificationChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(request.title)
            .setContentText(request.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.body))
            .setAutoCancel(true)
            .setContentIntent(createSystemNotificationIntent())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val notifyTag = request.tag.ifBlank { request.notificationId }
        NotificationManagerCompat.from(this).notify(notifyTag.ifBlank { null }, resolveNotificationRequestCode(request), notification)
        return true
    }

    private fun createSystemNotificationIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, BootConfig.notificationId, launchIntent, flags)
    }

    private fun resolveNotificationRequestCode(request: SystemNotificationRequest): Int {
        val seed = request.notificationId
            .ifBlank { request.tag }
            .ifBlank { request.title }
            .hashCode()
        return if (seed == Int.MIN_VALUE) 0 else seed.absoluteValue
    }

    private fun restoreWebViewState(savedInstanceState: Bundle?) {
        val webViewState = savedInstanceState?.getBundle(webViewStateKey) ?: return
        val restoredState = webView.restoreState(webViewState)
        val restoredUrl = restoredState?.currentItem?.url.orEmpty()
            .ifBlank { savedInstanceState.getString(loadedUrlStateKey).orEmpty() }

        if (restoredUrl.isBlank()) {
            return
        }

        loadedUrl = restoredUrl
        hasRestoredWebViewState = true
    }

    private fun observeBootstrapState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StartupRuntimeStore.state.collect { state ->
                    renderBootstrapState(state)
                }
            }
        }
    }

    private fun renderBootstrapState(state: StartupState) {
        val details = state.details.takeIf { it.isNotBlank() }
        bootstrapStatus.text = if (details == null) {
            state.message
        } else {
            buildString {
                append(state.message)
                append('\n')
                append(details)
            }
        }

        bootstrapRetry.isVisible = state.canRetry || state.phase == StartupPhase.CONFIGURING
        bootstrapRetry.text = if (state.phase == StartupPhase.CONFIGURING) {
            getString(R.string.bootstrap_resume)
        } else {
            getString(R.string.bootstrap_retry)
        }
        bootstrapProgress.isVisible = !state.canRetry && state.phase != StartupPhase.CONFIGURING
        bootstrapSettingsButton.isVisible = !state.isReady
        bootstrapSettingsButton.isEnabled = !state.isReady && !isOpeningBootstrapSettings

        if (state.isReady) {
            showWebView(state.localUrl)
        } else {
            bootstrapOverlay.isVisible = true
            webView.isVisible = false
        }
    }

    private fun showWebView(baseUrl: String) {
        bootstrapOverlay.isVisible = false
        webView.isVisible = true
        if (hasRestoredWebViewState) {
            // 已恢复出原来的 WebView 会话时，不再重新 load baseUrl，避免把前端状态重置到首页。
            hasRestoredWebViewState = false
            return
        }

        if (isCurrentWebViewPageFor(baseUrl)) {
            return
        }

        val targetUrl = buildInitialWebViewUrl(baseUrl)
        loadedUrl = targetUrl
        webView.loadUrl(targetUrl)
    }

    private fun startBootstrap(forceRestart: Boolean) {
        val intent = StartupCoordinatorService.createStartIntent(this, forceRestart)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun openBootstrapSettings() {
        if (isOpeningBootstrapSettings) {
            return
        }

        isOpeningBootstrapSettings = true
        bootstrapSettingsButton.isEnabled = false
        bootstrapSettingsLauncher.launch(BootstrapSettingsActivity.createIntent(this))
    }

    private fun isCurrentWebViewPageFor(baseUrl: String): Boolean {
        val currentUrl = webView.url.orEmpty().ifBlank { loadedUrl }
        if (currentUrl.isBlank()) {
            return false
        }

        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedCurrentUrl = currentUrl.trim()

        // 回到前台时只要 WebView 还停留在同一个本地 Tavern 站点，就复用现有页面，避免再次 loadUrl 触发前端初始化。
        return normalizedCurrentUrl == normalizedBaseUrl ||
            normalizedCurrentUrl == "$normalizedBaseUrl/" ||
            normalizedCurrentUrl.startsWith("$normalizedBaseUrl/#") ||
            normalizedCurrentUrl.startsWith("$normalizedBaseUrl/?") ||
            normalizedCurrentUrl.startsWith("$normalizedBaseUrl/")
    }

    private fun buildInitialWebViewUrl(baseUrl: String): String {
        return "${baseUrl.trim().trimEnd('/')}/"
    }

    private fun resolveFileChooserUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK) {
            return null
        }

        val clipData = data?.clipData
        if (clipData != null) {
            return Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
        }

        val selectedUri = data?.data ?: return emptyArray()
        return arrayOf(selectedUri)
    }
}