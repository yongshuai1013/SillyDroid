package com.jm.sillydroid.data.logs

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ApplicationExitInfoLogStore {
    private const val maxTracePreviewBytes = 240_000
    private const val historyLimit = 16

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var refreshJob: Job? = null

    fun refreshAsync(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (refreshJob?.isActive == true) {
                return
            }

            refreshJob = scope.launch {
                try {
                    refresh(appContext)
                } finally {
                    synchronized(this@ApplicationExitInfoLogStore) {
                        refreshJob = null
                    }
                }
            }
        }
    }

    fun refreshBlocking(context: Context) {
        refresh(context.applicationContext)
    }

    private fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            HostLogManager.writeExitInfoLog(
                context,
                buildUnavailableLogContent(
                    context = context,
                    reason = "unsupported_android_version",
                    details = "ApplicationExitInfo requires Android 11 / API 30; currentSdk=${Build.VERSION.SDK_INT}"
                )
            )
            return
        }

        val content = runCatching {
            buildExitInfoLogContent(context)
        }.getOrElse { error ->
            buildUnavailableLogContent(
                context = context,
                reason = "refresh_failed",
                details = "${error.javaClass.name}: ${error.message.orEmpty()}"
            )
        }

        HostLogManager.writeExitInfoLog(context, content)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun buildExitInfoLogContent(context: Context): String? {
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return buildUnavailableLogContent(
                context = context,
                reason = "activity_manager_unavailable",
                details = "Context did not provide ActivityManager."
            )
        val exitInfos = activityManager.getHistoricalProcessExitReasons(null, 0, historyLimit)
        if (exitInfos.isEmpty()) {
            cleanupTraceDirectory(context, retainedRelativePaths = emptySet())
            return buildUnavailableLogContent(
                context = context,
                reason = "no_historical_exit_reasons",
                details = "System returned zero historical process exit records."
            )
        }

        val exitRecords = exitInfos
            .sortedByDescending { info -> info.timestamp }
            .mapIndexed { index, exitInfo ->
                ExitInfoRecord(
                    index = index,
                    exitInfo = exitInfo,
                    traceArtifact = captureTraceArtifact(context, exitInfo, index)
                )
            }
        cleanupTraceDirectory(
            context = context,
            retainedRelativePaths = exitRecords.mapNotNullTo(mutableSetOf()) { record ->
                record.traceArtifact.relativePath
            }
        )
        val latestHistoricalExit = exitRecords.firstOrNull() ?: return null
        val latestAbnormalExit = exitRecords.firstOrNull { record ->
            !isExpectedExitReason(record.exitInfo.reason)
        }

        return buildString {
            appendLine("generatedAt=${formatTimestamp(System.currentTimeMillis())}")
            appendLine("packageName=${context.packageName}")
            appendLine("historyCount=${exitRecords.size}")
            appendLine("historyOrder=timestamp_desc")
            appendLine("traceArtifactDirectory=${HostLogManager.exitInfoTraceDirectoryName}")
            appendLine("traceArtifactCount=${exitRecords.count { record -> record.traceArtifact.relativePath != null }}")
            appendLine("latestAbnormalExitPresent=${latestAbnormalExit != null}")

            if (latestAbnormalExit != null) {
                appendExitInfoSection("latestAbnormalExit", latestAbnormalExit)
            }

            if (latestHistoricalExit !== latestAbnormalExit) {
                appendExitInfoSection("latestHistoricalExit", latestHistoricalExit)
            }

            appendLine()
            appendLine("[history]")
            appendLine("count=${exitRecords.size}")
            exitRecords.forEach { record ->
                appendExitInfoSection("history.${record.index}", record)
            }
        }.trimEnd()
    }

    private fun buildUnavailableLogContent(
        context: Context,
        reason: String,
        details: String
    ): String {
        // 重大崩溃包不能静默缺失退出信息；即使系统不支持或未返回历史记录，也写入原因方便后续分析。
        return buildString {
            appendLine("generatedAt=${formatTimestamp(System.currentTimeMillis())}")
            appendLine("packageName=${context.packageName}")
            appendLine("historyCount=0")
            appendLine("latestAbnormalExitPresent=false")
            appendLine("unavailableReason=$reason")
            appendLine("unavailableDetails=$details")
        }.trimEnd()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun StringBuilder.appendExitInfoSection(
        sectionName: String,
        record: ExitInfoRecord
    ) {
        val exitInfo = record.exitInfo
        val traceArtifact = record.traceArtifact
        appendLine()
        appendLine("[$sectionName]")
        appendLine("historyIndex=${record.index}")
        appendLine("timestamp=${formatTimestamp(exitInfo.timestamp)}")
        appendLine("reason=${reasonToString(exitInfo.reason)}(${exitInfo.reason})")
        appendLine("status=${exitInfo.status}")
        appendLine("importance=${exitInfo.importance}")
        appendLine("pid=${exitInfo.pid}")
        appendLine("realUid=${exitInfo.realUid}")
        appendLine("packageUid=${exitInfo.packageUid}")
        appendLine("processName=${exitInfo.processName}")
        appendLine("description=${exitInfo.description.orEmpty().ifBlank { "-" }}")
        appendLine("pssKb=${exitInfo.pss}")
        appendLine("rssKb=${exitInfo.rss}")
        appendLine("traceStatus=${traceArtifact.status}")
        appendLine("traceFile=${traceArtifact.relativePath ?: "-"}")
        appendLine("traceBytes=${traceArtifact.bytes}")
        appendLine("traceSha256=${traceArtifact.sha256 ?: "-"}")
        appendLine("traceError=${traceArtifact.error ?: "-"}")

        val tracePreview = traceArtifact.textPreview.orEmpty()
        if (tracePreview.isNotBlank()) {
            appendLine()
            appendLine("[${sectionName}.tracePreview]")
            appendLine(tracePreview)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun captureTraceArtifact(
        context: Context,
        exitInfo: ApplicationExitInfo,
        historyIndex: Int
    ): ExitTraceArtifact {
        val input = runCatching { exitInfo.traceInputStream }
            .getOrElse { error ->
                return ExitTraceArtifact(
                    status = "read_failed",
                    error = "${error.javaClass.name}: ${error.message.orEmpty()}"
                )
            }
            ?: return ExitTraceArtifact(status = "absent")

        val traceDirectory = HostLogManager.exitInfoTraceDirectory(context).apply { mkdirs() }
        val fileName = buildTraceFileName(exitInfo, historyIndex)
        val targetFile = File(traceDirectory, fileName)
        val tempFile = File(traceDirectory, "$fileName.tmp")
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            input.use { source ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) {
                            break
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        byteCount += read
                    }
                }
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val relativePath = targetFile.relativeTo(HostLogManager.logsDirectory(context)).invariantSeparatorsPath
            ExitTraceArtifact(
                status = "saved",
                relativePath = relativePath,
                bytes = byteCount,
                sha256 = digest.digest().toHexString(),
                textPreview = readTextTracePreview(targetFile)
            )
        }.getOrElse { error ->
            tempFile.delete()
            ExitTraceArtifact(
                status = "write_failed",
                error = "${error.javaClass.name}: ${error.message.orEmpty()}"
            )
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun buildTraceFileName(exitInfo: ApplicationExitInfo, historyIndex: Int): String {
        val reason = reasonToString(exitInfo.reason).lowercase(Locale.US)
        val processName = sanitizeTraceFileComponent(exitInfo.processName.orEmpty()).ifBlank { "process" }
        return "history-$historyIndex-${exitInfo.timestamp}-pid-${exitInfo.pid}-reason-$reason-$processName.trace"
    }

    private fun sanitizeTraceFileComponent(value: String): String {
        return value
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '.', '-')
            .take(64)
    }

    private fun readTextTracePreview(traceFile: File): String? {
        if (!traceFile.isFile || traceFile.length() <= 0L) {
            return null
        }

        val maxBytes = traceFile.length().coerceAtMost(maxTracePreviewBytes.toLong()).toInt()
        val sample = traceFile.inputStream().use { input ->
            val buffer = ByteArray(maxBytes)
            val read = input.read(buffer)
            when {
                read <= 0 -> ByteArray(0)
                read == buffer.size -> buffer
                else -> buffer.copyOf(read)
            }
        }
        if (!looksLikeText(sample)) {
            return null
        }

        val text = sample.toString(Charsets.UTF_8).trim()
        if (text.isBlank()) {
            return null
        }

        return if (traceFile.length() <= sample.size) {
            text
        } else {
            buildString {
                appendLine("[preview_truncated raw_trace_file_is_complete]")
                append(text)
            }
        }
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) {
            return false
        }

        val badControlCount = bytes.count { byte ->
            val value = byte.toInt() and 0xFF
            value == 0 || (value < 0x20 && value !in setOf(0x09, 0x0A, 0x0D))
        }
        return badControlCount <= (bytes.size / 20).coerceAtLeast(1)
    }

    private fun cleanupTraceDirectory(context: Context, retainedRelativePaths: Set<String>) {
        val logsDir = HostLogManager.logsDirectory(context)
        val traceDirectory = HostLogManager.exitInfoTraceDirectory(context)
        if (!traceDirectory.isDirectory) {
            return
        }

        traceDirectory.walkBottomUp().forEach { file ->
            when {
                file.isFile -> {
                    val relativePath = file.relativeTo(logsDir).invariantSeparatorsPath
                    if (relativePath !in retainedRelativePaths) {
                        file.delete()
                    }
                }

                file.isDirectory && file != traceDirectory && file.listFiles().isNullOrEmpty() -> {
                    file.delete()
                }
            }
        }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private data class ExitInfoRecord(
        val index: Int,
        val exitInfo: ApplicationExitInfo,
        val traceArtifact: ExitTraceArtifact
    )

    private data class ExitTraceArtifact(
        val status: String,
        val relativePath: String? = null,
        val bytes: Long = 0L,
        val sha256: String? = null,
        val error: String? = null,
        val textPreview: String? = null
    )

    private fun formatTimestamp(epochMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(epochMillis))
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun isExpectedExitReason(reason: Int): Boolean {
        return when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> true
            else -> false
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun reasonToString(reason: Int): String {
        return when (reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            else -> "UNKNOWN"
        }
    }
}
