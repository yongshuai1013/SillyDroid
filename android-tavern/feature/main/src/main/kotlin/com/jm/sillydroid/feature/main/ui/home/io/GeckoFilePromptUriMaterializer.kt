package com.jm.sillydroid.feature.main.ui.home.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

/**
 * GeckoView 151 的 FilePrompt 会先把 Uri 解析成真实文件路径再交给页面。
 *
 * Android SAF 返回的 content:// Uri 经常没有 _data 路径，直接 confirm 给 Gecko 后页面可能只收到空文件。
 * 因此 Gecko 专用路径在回传 prompt 前先把内容物化到 App cache，再把 file:// 交给 Gecko。
 */
class GeckoFilePromptUriMaterializer(
    private val context: Context,
    private val cacheDirectory: File = File(context.cacheDir, "gecko-file-prompts")
) {
    fun materialize(selectedUris: Array<Uri>): MaterializeResult {
        cleanPreparedFiles()
        cacheDirectory.mkdirs()
        val usedCacheFileNames = linkedSetOf<String>()
        val preparedUris = selectedUris.mapIndexed { index, uri ->
            materialize(uri = uri, index = index, usedCacheFileNames = usedCacheFileNames)
        }.toTypedArray()
        return MaterializeResult(uris = preparedUris)
    }

    fun cleanPreparedFiles() {
        cacheDirectory.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
    }

    private fun materialize(uri: Uri, index: Int, usedCacheFileNames: MutableSet<String>): Uri {
        if (uri.scheme.equals(ContentResolver.SCHEME_FILE, ignoreCase = true)) {
            return uri
        }
        val displayName = resolveDisplayName(uri).orEmpty()
        // Tavern 导入逻辑可能读取 File.name；临时缓存名要尽量保持用户选择的原始文件名语义。
        val targetFile = cacheDirectory.resolve(
            resolveUniqueCacheFileName(
                fileName = resolveCacheFileName(displayName, index),
                usedFileNames = usedCacheFileNames
            )
        )
        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Cannot open selected file")
        return Uri.fromFile(targetFile)
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)?.trim()?.takeIf { name -> name.isNotEmpty() }
            } else {
                null
            }
        } ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf { name -> name.isNotEmpty() }
    }

    data class MaterializeResult(
        val uris: Array<Uri>
    )

    companion object {
        fun resolveCacheFileName(displayName: String, index: Int): String {
            val originalName = File(displayName).name.trim()
            val extension = originalName.substringAfterLast('.', missingDelimiterValue = "")
                .takeIf { value ->
                    value.isNotBlank() &&
                        value.length <= 24 &&
                        originalName.lastIndexOf('.') > 0 &&
                        value.all { char -> char.isLetterOrDigit() }
                }
                ?.lowercase(Locale.ROOT)
                ?.let { value -> ".$value" }
                .orEmpty()
            val rawNameWithoutExtension = if (extension.isNotBlank()) {
                originalName.dropLast(extension.length)
            } else {
                originalName
            }
            val safeNameWithoutExtension = rawNameWithoutExtension
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_', '.', '-')
                .take(96)
            val fallbackName = "selected-file-${index + 1}"
            val nameWithoutExtension = safeNameWithoutExtension.ifBlank { fallbackName }
            return "$nameWithoutExtension$extension"
        }

        fun resolveUniqueCacheFileName(fileName: String, usedFileNames: MutableSet<String>): String {
            val safeFileName = fileName.ifBlank { "selected-file" }
            if (usedFileNames.add(safeFileName)) {
                return safeFileName
            }
            val extensionStart = safeFileName.lastIndexOf('.').takeIf { index -> index > 0 } ?: safeFileName.length
            val nameWithoutExtension = safeFileName.substring(0, extensionStart).ifBlank { "selected-file" }
            val extension = safeFileName.substring(extensionStart)
            var sequence = 2
            while (true) {
                val candidate = "$nameWithoutExtension-$sequence$extension"
                if (usedFileNames.add(candidate)) {
                    return candidate
                }
                sequence += 1
            }
        }
    }
}
