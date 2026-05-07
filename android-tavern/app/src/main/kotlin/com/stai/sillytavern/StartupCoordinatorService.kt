package com.stai.sillytavern

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StartupCoordinatorService : Service() {
    companion object {
        private const val ACTION_START = "com.stai.sillytavern.action.START"
        private const val ACTION_RETRY = "com.stai.sillytavern.action.RETRY"

        fun createStartIntent(context: Context, retry: Boolean = false): Intent {
            return Intent(context, StartupCoordinatorService::class.java).apply {
                action = if (retry) ACTION_RETRY else ACTION_START
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bootstrapJob: Job? = null
    private var serverMonitorJob: Job? = null
    private var serverProcess: ManagedProcess? = null
    private val startupLogFile: File
        get() = File(filesDir, "android-tavern/logs/startup.log")

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val retry = intent?.action == ACTION_RETRY
        val notification = buildNotification(StartupRuntimeStore.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                BootConfig.notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(BootConfig.notificationId, notification)
        }
        startBootstrap(retry)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        bootstrapJob?.cancel()
        stopManagedProcesses()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startBootstrap(forceRestart: Boolean) {
        if (!forceRestart) {
            if (bootstrapJob?.isActive == true) {
                return
            }

            val currentState = StartupRuntimeStore.state.value
            val serverAlive = serverProcess?.isAlive() == true
            if (currentState.isReady && serverAlive) {
                return
            }

            if (currentState.canRetry) {
                return
            }
        }

        bootstrapJob?.cancel()
        if (forceRestart) {
            stopManagedProcesses()
        }

        bootstrapJob = serviceScope.launch {
            runBootstrap()
        }
    }

    private suspend fun runBootstrap() {
        resetStartupLog()
        try {
            val paths = HostPaths.from(applicationContext)
            updateState(StartupState(StartupPhase.EXTRACTING, "正在解包 Tavern bootstrap 资产。"))
            AssetExtractor(applicationContext).extractBootstrap(paths)

            updateState(StartupState(StartupPhase.VALIDATING, "正在校验 Tavern runtime 与启动脚本。"))
            BootstrapLayoutVerifier(paths).verify()
            AndroidDnsConfigWriter(applicationContext).write(paths)

            val launcher = LinuxRuntimeLauncher(paths)
            updateState(StartupState(StartupPhase.VALIDATING, "正在校验离线 Linux 运行时。"))
            RootfsRuntimeProvisioner(launcher, paths).ensure()

            updateState(StartupState(StartupPhase.STARTING_SERVER, "正在拉起 SillyTavern。"))
            serverProcess = ServerController(launcher, paths).start()

            updateState(StartupState(StartupPhase.WAITING_READY, "正在等待本地 Tavern 服务就绪。"))
            if (!HealthProbe.awaitReady(BootConfig.readinessUrl)) {
                throw BootstrapException("本地 Tavern 服务在等待窗口内未就绪。")
            }

            updateState(
                StartupState(
                    phase = StartupPhase.READY,
                    message = "本地 Tavern 服务已就绪，正在打开 WebView。",
                    localUrl = BootConfig.localServiceUrl
                )
            )
            startServerMonitor()
        } catch (exception: BootstrapException) {
            appendStartupLog("BootstrapException: ${exception.message ?: exception.javaClass.simpleName}")
            appendStartupLog(formatThrowable(exception))
            updateState(
                StartupState(
                    phase = StartupPhase.BLOCKED,
                    message = "Tavern bootstrap 资产还不完整。",
                    details = exception.message.orEmpty()
                )
            )
        } catch (exception: Exception) {
            appendStartupLog("Exception: ${exception.message ?: exception.javaClass.simpleName}")
            appendStartupLog(formatThrowable(exception))
            updateState(
                StartupState(
                    phase = StartupPhase.ERROR,
                    message = "本地 Tavern 服务启动失败。",
                    details = exception.message ?: exception.javaClass.simpleName
                )
            )
        }
    }

    private fun updateState(state: StartupState) {
        appendStartupLog(
            buildString {
                append("state=")
                append(state.phase)
                append(" message=")
                append(state.message)
                if (state.details.isNotBlank()) {
                    append(" details=")
                    append(state.details)
                }
            }
        )
        StartupRuntimeStore.update(state)
        NotificationManagerCompat.from(this).notify(BootConfig.notificationId, buildNotification(state))
    }

    private fun resetStartupLog() {
        startupLogFile.parentFile?.mkdirs()
        startupLogFile.writeText("")
    }

    private fun appendStartupLog(message: String) {
        startupLogFile.parentFile?.mkdirs()
        startupLogFile.appendText("${System.currentTimeMillis()} $message\n")
    }

    private fun formatThrowable(throwable: Throwable): String {
        val stringWriter = StringWriter()
        PrintWriter(stringWriter).use { writer ->
            throwable.printStackTrace(writer)
        }

        return stringWriter.toString().trimEnd()
    }

    private fun buildNotification(state: StartupState): Notification {
        return NotificationCompat.Builder(this, BootConfig.notificationChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.bootstrap_notification_title))
            .setContentText(state.message)
            .setOngoing(!state.isReady)
            .setOnlyAlertOnce(true)
            .setContentIntent(createContentIntent())
            .build()
    }

    private fun createContentIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, launchIntent, flags)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            BootConfig.notificationChannelId,
            getString(R.string.bootstrap_notification_title),
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startServerMonitor() {
        val currentServerProcess = serverProcess ?: return
        serverMonitorJob?.cancel()
        serverMonitorJob = serviceScope.launch {
            val exitCode = currentServerProcess.waitFor()
            if (!isActive) {
                return@launch
            }

            appendStartupLog("Server process exited unexpectedly. exitCode=$exitCode")
            serverProcess = null
            updateState(
                StartupState(
                    phase = StartupPhase.ERROR,
                    message = "本地 Tavern 服务已退出。",
                    details = "SillyTavern 进程退出码：$exitCode"
                )
            )
        }
    }

    private fun stopManagedProcesses() {
        serverMonitorJob?.cancel()
        serverMonitorJob = null
        serverProcess?.stop()
        serverProcess = null
    }
}