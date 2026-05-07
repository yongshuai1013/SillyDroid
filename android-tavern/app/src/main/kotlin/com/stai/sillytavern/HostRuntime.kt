package com.stai.sillytavern

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object BootConfig {
    const val servicePort = 7888
    const val readinessProbeAttempts = 300
    const val readinessPath = "/"
    const val bootstrapAssetRoot = "bootstrap"
    const val notificationChannelId = "android-tavern-bootstrap"
    const val systemNotificationChannelId = "android-tavern-system-notification"
    const val notificationId = 1101

    val localServiceUrl: String
        get() = "http://127.0.0.1:$servicePort"

    val readinessUrl: String
        get() = "$localServiceUrl$readinessPath"
}

internal enum class StartupPhase {
    IDLE,
    EXTRACTING,
    VALIDATING,
    STARTING_SERVER,
    WAITING_READY,
    READY,
    BLOCKED,
    ERROR
}

internal data class StartupState(
    val phase: StartupPhase = StartupPhase.IDLE,
    val message: String = "正在准备 SillyTavern Android 宿主环境。",
    val details: String = "",
    val localUrl: String = BootConfig.localServiceUrl
) {
    val isReady: Boolean
        get() = phase == StartupPhase.READY

    val canRetry: Boolean
        get() = phase == StartupPhase.BLOCKED || phase == StartupPhase.ERROR
}

internal object StartupRuntimeStore {
    private val mutableState = MutableStateFlow(StartupState())
    val state = mutableState.asStateFlow()

    fun update(state: StartupState) {
        mutableState.value = state
    }
}

internal data class HostPaths(
    val bootstrapRoot: File,
    val scriptsDir: File,
    val rootfsDir: File,
    val serverDir: File,
    val hostPrefixDir: File,
    val hostLibDir: File,
    val hostTmpDir: File,
    val hostProotBinary: File,
    val hostProotLoader: File,
    val hostProotLoader32: File,
    val dataRoot: File,
    val serverDataDir: File,
    val logsDir: File
) {
    companion object {
        fun from(context: Context): HostPaths {
            val bootstrapRoot = File(context.filesDir, "android-tavern/bootstrap")
            val dataRoot = File(context.filesDir, "android-tavern/data")
            val rootfsDir = File(bootstrapRoot, "rootfs")
            val hostPrefixDir = File(context.filesDir, "usr")
            val hostNativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            return HostPaths(
                bootstrapRoot = bootstrapRoot,
                scriptsDir = File(bootstrapRoot, "scripts"),
                rootfsDir = rootfsDir,
                serverDir = File(bootstrapRoot, "server"),
                hostPrefixDir = hostPrefixDir,
                hostLibDir = hostNativeLibDir,
                hostTmpDir = File(hostPrefixDir, "tmp"),
                hostProotBinary = File(hostNativeLibDir, "libproot.so"),
                hostProotLoader = File(hostNativeLibDir, "libproot-loader.so"),
                hostProotLoader32 = File(hostNativeLibDir, "libproot-loader32.so"),
                dataRoot = dataRoot,
                serverDataDir = File(dataRoot, "server"),
                logsDir = File(context.filesDir, "android-tavern/logs")
            )
        }
    }

    fun ensureWorkingDirectories() {
        listOf(bootstrapRoot, scriptsDir, rootfsDir, serverDir, hostPrefixDir, hostTmpDir, dataRoot, serverDataDir, logsDir)
            .forEach { directory ->
                if (!directory.exists()) {
                    directory.mkdirs()
                }
            }
    }
}

internal class AssetExtractor(private val context: Context) {
    fun extractBootstrap(paths: HostPaths) {
        paths.ensureWorkingDirectories()
        val skippedAssetRoots = mutableSetOf(
            "${BootConfig.bootstrapAssetRoot}/rootfs",
            "${BootConfig.bootstrapAssetRoot}/server"
        )
        val rootfsDirectoryRefreshed = refreshAssetDirectoryIfNeeded(
            manifestAssetPath = "${BootConfig.bootstrapAssetRoot}/rootfs/rootfs-manifest.json",
            installedManifestFile = File(paths.rootfsDir, "rootfs-manifest.json"),
            targetDirectory = paths.rootfsDir,
            replaceOnIncomplete = false,
            requiredRelativePaths = listOf(
                "fs/bin/sh",
                "fs/usr/lib/aarch64-linux-gnu/libatomic.so.1",
                "rootfs-manifest.json"
            )
        )
        if (rootfsDirectoryRefreshed) {
            extractArchiveAsset(
                assetPath = "${BootConfig.bootstrapAssetRoot}/rootfs/rootfs-fs.zip",
                targetDirectory = File(paths.rootfsDir, "fs"),
                shouldSetExecutable = { true }
            )
            copyFile(
                AssetCopySpec(
                    assetPath = "${BootConfig.bootstrapAssetRoot}/rootfs/rootfs-manifest.json",
                    targetPath = File(paths.rootfsDir, "rootfs-manifest.json")
                )
            )
        }

        val serverDirectoryRefreshed = refreshAssetDirectoryIfNeeded(
            manifestAssetPath = "${BootConfig.bootstrapAssetRoot}/server/bootstrap-manifest.json",
            installedManifestFile = File(paths.serverDir, "bootstrap-manifest.json"),
            targetDirectory = paths.serverDir,
            replaceOnIncomplete = true,
            requiredRelativePaths = listOf(
                "bootstrap-manifest.json",
                "tavern-entrypoint.sh",
                "node/bin/node"
            ),
            requiredExecutableRelativePaths = listOf(
                "tavern-entrypoint.sh",
                "node/bin/node"
            )
        )
        if (serverDirectoryRefreshed) {
            extractArchiveAsset(
                assetPath = "${BootConfig.bootstrapAssetRoot}/server/server-payload.zip",
                targetDirectory = paths.serverDir,
                shouldSetExecutable = { relativePath ->
                    // Tavern payload 里的 Node runtime 是 zip 解包出来的，必须在这里补可执行位，否则入口脚本无法 exec node。
                    relativePath.endsWith(".sh", ignoreCase = true) ||
                        relativePath == "tavern-entrypoint.sh" ||
                        relativePath == "node/bin/node"
                }
            )
            copyFile(
                AssetCopySpec(
                    assetPath = "${BootConfig.bootstrapAssetRoot}/server/bootstrap-manifest.json",
                    targetPath = File(paths.serverDir, "bootstrap-manifest.json")
                )
            )
        }

        copyNode(AssetCopySpec(BootConfig.bootstrapAssetRoot, paths.bootstrapRoot), skippedAssetRoots)
        refreshHostPrefixDirectory(paths, rootfsDirectoryRefreshed)
    }

    private fun refreshAssetDirectoryIfNeeded(
        manifestAssetPath: String,
        installedManifestFile: File,
        targetDirectory: File,
        replaceOnIncomplete: Boolean,
        requiredRelativePaths: List<String>,
        requiredExecutableRelativePaths: List<String> = emptyList()
    ): Boolean {
        val bundledManifest = context.assets.open(manifestAssetPath).bufferedReader().use { reader ->
            reader.readText()
        }

        if (installedManifestFile.exists() && installedManifestFile.readText() == bundledManifest) {
            if (
                targetDirectory.containsRequiredFiles(requiredRelativePaths) &&
                targetDirectory.containsExecutableFiles(requiredExecutableRelativePaths)
            ) {
                return false
            }

            if (!replaceOnIncomplete) {
                targetDirectory.mkdirs()
                return true
            }
        }

        if (targetDirectory.exists()) {
            targetDirectory.deleteRecursively()
        }
        targetDirectory.mkdirs()
        return true
    }

    private fun File.containsRequiredFiles(requiredRelativePaths: List<String>): Boolean {
        return requiredRelativePaths.all { relativePath ->
            File(this, relativePath).exists()
        }
    }

    private fun File.containsExecutableFiles(requiredRelativePaths: List<String>): Boolean {
        return requiredRelativePaths.all { relativePath ->
            File(this, relativePath).canExecute()
        }
    }

    private fun extractArchiveAsset(
        assetPath: String,
        targetDirectory: File,
        shouldSetExecutable: (String) -> Boolean
    ) {
        if (targetDirectory.exists()) {
            targetDirectory.deleteRecursively()
        }
        targetDirectory.mkdirs()

        val canonicalTargetDirectory = targetDirectory.canonicalFile
        val canonicalTargetPrefix = canonicalTargetDirectory.path + File.separator

        context.assets.open(assetPath).use { input ->
            ZipInputStream(input.buffered()).use { zipInput ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val relativePath = entry.name.removePrefix("./").trimStart('/')
                    if (relativePath.isBlank()) {
                        zipInput.closeEntry()
                        continue
                    }

                    val outputFile = File(targetDirectory, relativePath).canonicalFile
                    val outputPath = outputFile.path
                    if (outputPath != canonicalTargetDirectory.path && !outputPath.startsWith(canonicalTargetPrefix)) {
                        throw BootstrapException("bootstrap 归档包含非法路径：$relativePath")
                    }

                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        outputFile.outputStream().use { output ->
                            zipInput.copyTo(output)
                        }
                        if (shouldSetExecutable(relativePath)) {
                            outputFile.setExecutable(true)
                        }
                    }

                    zipInput.closeEntry()
                }
            }
        }
    }

    private fun copyNode(spec: AssetCopySpec, skippedAssetRoots: Set<String>) {
        if (spec.assetPath in skippedAssetRoots) {
            return
        }

        val entries = context.assets.list(spec.assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            copyFile(spec)
            return
        }

        if (!spec.targetPath.exists()) {
            spec.targetPath.mkdirs()
        }

        entries.forEach { child ->
            copyNode(spec.child(child), skippedAssetRoots)
        }
    }

    private fun copyFile(spec: AssetCopySpec) {
        if (spec.shouldPreserveExistingRootfsFile() && spec.targetPath.exists()) {
            return
        }

        spec.targetPath.parentFile?.mkdirs()
        context.assets.open(spec.assetPath).use { input ->
            spec.targetPath.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (spec.shouldSetExecutable()) {
            spec.targetPath.setExecutable(true)
        }
    }

    private fun refreshHostPrefixDirectory(paths: HostPaths, rootfsDirectoryRefreshed: Boolean) {
        val hostPrefixRequiredFiles = listOf(
            "bin/proot",
            "libexec/proot/loader"
        )
        val shouldRefreshHostPrefix = rootfsDirectoryRefreshed || !paths.hostPrefixDir.containsRequiredFiles(hostPrefixRequiredFiles)
        if (!shouldRefreshHostPrefix) {
            paths.hostTmpDir.mkdirs()
            return
        }

        if (paths.hostPrefixDir.exists()) {
            paths.hostPrefixDir.deleteRecursively()
        }

        extractArchiveAsset(
            assetPath = "${BootConfig.bootstrapAssetRoot}/rootfs/rootfs-usr.zip",
            targetDirectory = paths.hostPrefixDir,
            shouldSetExecutable = { relativePath ->
                relativePath.endsWith(".sh", ignoreCase = true) ||
                    relativePath == "bin/proot" ||
                    relativePath == "libexec/proot/loader" ||
                    relativePath == "libexec/proot/loader32"
            }
        )
        paths.hostTmpDir.mkdirs()
    }
}

internal data class AssetCopySpec(
    val assetPath: String,
    val targetPath: File
) {
    fun child(name: String): AssetCopySpec {
        return AssetCopySpec(
            assetPath = "$assetPath/$name",
            targetPath = File(targetPath, name)
        )
    }

    fun shouldPreserveExistingRootfsFile(): Boolean {
        return assetPath.startsWith("${BootConfig.bootstrapAssetRoot}/rootfs/fs/")
    }

    fun shouldSetExecutable(): Boolean {
        return shouldPreserveExistingRootfsFile() ||
            targetPath.extension.equals("sh", ignoreCase = true) ||
            targetPath.name == "proot" ||
            targetPath.name == "loader" ||
            targetPath.name == "loader32" ||
            targetPath.name == "tavern-entrypoint.sh"
    }
}

internal class BootstrapLayoutVerifier(private val paths: HostPaths) {
    fun verify() {
        val missingEntries = requiredEntries()
            .filterNot { (_, file) -> file.exists() }
            .map { (displayPath, _) -> displayPath }

        if (missingEntries.isNotEmpty()) {
            throw BootstrapException(
                "bootstrap 资产缺少关键文件：${missingEntries.joinToString()}。请先同步 android-tavern 下的离线运行时与 payload 产物。"
            )
        }
    }

    private fun requiredEntries(): List<Pair<String, File>> {
        return listOf(
            "scripts/ensure-rootfs-runtime.sh" to File(paths.scriptsDir, "ensure-rootfs-runtime.sh"),
            "scripts/start-server.sh" to File(paths.scriptsDir, "start-server.sh"),
            "rootfs/fs/bin/sh" to File(paths.rootfsDir, "fs/bin/sh"),
            "rootfs/rootfs-manifest.json" to File(paths.rootfsDir, "rootfs-manifest.json"),
            "nativeLibraryDir/libtalloc_2.so" to File(paths.hostLibDir, "libtalloc_2.so"),
            "nativeLibraryDir/libproot.so" to paths.hostProotBinary,
            "nativeLibraryDir/libproot-loader.so" to paths.hostProotLoader,
            "server/bootstrap-manifest.json" to File(paths.serverDir, "bootstrap-manifest.json"),
            "server/tavern-entrypoint.sh" to File(paths.serverDir, "tavern-entrypoint.sh")
        )
    }
}

internal class AndroidDnsConfigWriter(private val context: Context) {
    fun write(paths: HostPaths) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: throw IllegalStateException("Android 无法获取 ConnectivityManager，无法为 rootfs 生成 DNS 配置。")
        val dnsServers = connectivityManager.activeNetwork
            ?.let { activeNetwork ->
                connectivityManager.getLinkProperties(activeNetwork)
                    ?.dnsServers
                    ?.mapNotNull { address -> address.hostAddress }
                    ?.distinct()
            }
            .orEmpty()

        val resolvConf = File(paths.rootfsDir, "android-resolv.conf")
        val resolvConfContent = if (dnsServers.isEmpty()) {
            ""
        } else {
            dnsServers.joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator()) { dnsServer ->
                "nameserver $dnsServer"
            }
        }
        resolvConf.writeText(resolvConfContent)
    }
}

internal class BootstrapException(message: String) : IllegalStateException(message)

internal data class LaunchRequest(
    val name: String,
    val scriptFile: File,
    val workingDirectory: File,
    val environment: Map<String, String>
)

internal class ManagedProcess(
    val name: String,
    private val process: Process
) {
    fun isAlive(): Boolean {
        return process.isAlive
    }

    fun waitFor(): Int {
        return process.waitFor()
    }

    fun stop() {
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
        }
    }
}

internal class LinuxRuntimeLauncher(private val paths: HostPaths) {
    fun start(request: LaunchRequest): ManagedProcess {
        if (!request.scriptFile.exists()) {
            throw BootstrapException("启动脚本不存在：${request.scriptFile.absolutePath}")
        }

        if (!paths.hostProotBinary.exists()) {
            throw BootstrapException("缺少 host proot：${paths.hostProotBinary.absolutePath}")
        }

        if (!paths.hostProotLoader.exists()) {
            throw BootstrapException("缺少 host proot loader：${paths.hostProotLoader.absolutePath}")
        }

        if (!paths.hostLibDir.exists()) {
            throw BootstrapException("缺少 host proot 依赖目录：${paths.hostLibDir.absolutePath}")
        }

        request.scriptFile.setExecutable(true)

        val logFile = File(paths.logsDir, "${request.name}.log")
        logFile.parentFile?.mkdirs()

        val processBuilder = ProcessBuilder("/system/bin/sh", request.scriptFile.absolutePath)
            .directory(request.workingDirectory)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))

        val environment = processBuilder.environment()
        environment.putAll(request.environment)
        environment["BOOTSTRAP_ROOT"] = paths.bootstrapRoot.absolutePath
        environment["ROOTFS_DIR"] = paths.rootfsDir.absolutePath
        environment["SERVER_DIR"] = paths.serverDir.absolutePath
        environment["LOGS_DIR"] = paths.logsDir.absolutePath
        environment["HOST_PROOT_BIN"] = paths.hostProotBinary.absolutePath
        environment["HOST_PROOT_LIB_DIR"] = paths.hostLibDir.absolutePath
        environment["HOST_PROOT_LOADER"] = paths.hostProotLoader.absolutePath
        if (paths.hostProotLoader32.exists()) {
            environment["HOST_PROOT_LOADER_32"] = paths.hostProotLoader32.absolutePath
        }
        environment["HOST_TMP_DIR"] = paths.hostTmpDir.absolutePath

        return ManagedProcess(request.name, processBuilder.start())
    }
}

internal class RootfsRuntimeProvisioner(
    private val launcher: LinuxRuntimeLauncher,
    private val paths: HostPaths
) {
    fun ensure() {
        val request = LaunchRequest(
            name = "rootfs-runtime",
            scriptFile = File(paths.scriptsDir, "ensure-rootfs-runtime.sh"),
            workingDirectory = paths.bootstrapRoot,
            environment = emptyMap()
        )
        val process = launcher.start(request)
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("Linux 离线运行时校验失败，请查看 android-tavern/logs/rootfs-runtime.log。")
        }
    }
}

internal class ServerController(
    private val launcher: LinuxRuntimeLauncher,
    private val paths: HostPaths
) {
    fun start(): ManagedProcess {
        val request = LaunchRequest(
            name = "sillytavern-server",
            scriptFile = File(paths.scriptsDir, "start-server.sh"),
            workingDirectory = paths.serverDir,
            environment = mapOf(
                "APP_DATA_ROOT" to paths.serverDataDir.absolutePath,
                "TAVERN_PORT" to BootConfig.servicePort.toString()
            )
        )
        return launcher.start(request)
    }
}

internal object HealthProbe {
    suspend fun awaitReady(targetUrl: String): Boolean {
        repeat(BootConfig.readinessProbeAttempts) {
            if (checkOnce(targetUrl)) {
                return true
            }
            delay(1000)
        }
        return false
    }

    private fun checkOnce(targetUrl: String): Boolean {
        val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 1000
            readTimeout = 1000
        }

        return try {
            connection.connect()
            connection.responseCode in 200..299
        } catch (_: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }
}