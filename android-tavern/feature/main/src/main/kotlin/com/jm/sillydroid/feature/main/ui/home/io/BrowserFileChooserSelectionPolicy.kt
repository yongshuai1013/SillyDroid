package com.jm.sillydroid.feature.main.ui.home.io

internal object BrowserFileChooserSelectionPolicy {
    private val jsonlMimeAliases = setOf(
        "application/x-ndjson",
        "application/jsonl",
        "application/json",
        "text/plain"
    )
    private val extensionMimeAliases = mapOf(
        ".json" to setOf("application/json", "text/json"),
        ".settings" to setOf("application/json", "text/json", "text/plain"),
        ".preset" to setOf("application/json", "text/json", "text/plain"),
        ".lorebook" to setOf("application/json", "text/json", "text/plain"),
        ".yaml" to setOf("application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml"),
        ".yml" to setOf("application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml"),
        ".png" to setOf("image/png"),
        ".jpg" to setOf("image/jpeg", "image/jpg"),
        ".jpeg" to setOf("image/jpeg", "image/jpg"),
        ".gif" to setOf("image/gif"),
        ".bmp" to setOf("image/bmp"),
        ".webp" to setOf("image/webp"),
        ".mp4" to setOf("video/mp4"),
        ".webm" to setOf("video/webm"),
        ".mov" to setOf("video/quicktime"),
        ".m4v" to setOf("video/mp4", "video/x-m4v"),
        ".ogg" to setOf("video/ogg", "audio/ogg", "application/ogg"),
        ".ogv" to setOf("video/ogg"),
        ".mp3" to setOf("audio/mpeg", "audio/mp3"),
        ".wav" to setOf("audio/wav", "audio/x-wav"),
        ".m4a" to setOf("audio/mp4", "audio/x-m4a"),
        ".aac" to setOf("audio/aac"),
        ".flac" to setOf("audio/flac"),
        ".opus" to setOf("audio/opus", "audio/ogg"),
        ".zip" to setOf("application/zip", "application/x-zip-compressed")
    )
    private val mimeExtensionAliases = mapOf(
        "application/json" to setOf(".json", ".jsonl", ".settings", ".preset", ".lorebook"),
        "text/json" to setOf(".json", ".jsonl", ".settings", ".preset", ".lorebook"),
        "text/plain" to setOf(".json", ".jsonl", ".settings", ".preset", ".lorebook", ".yaml", ".yml"),
        "application/x-ndjson" to setOf(".jsonl"),
        "application/jsonl" to setOf(".jsonl"),
        "application/yaml" to setOf(".yaml", ".yml"),
        "application/x-yaml" to setOf(".yaml", ".yml"),
        "text/yaml" to setOf(".yaml", ".yml"),
        "text/x-yaml" to setOf(".yaml", ".yml"),
        "application/zip" to setOf(".zip"),
        "application/x-zip-compressed" to setOf(".zip"),
        "image/png" to setOf(".png"),
        "image/jpeg" to setOf(".jpg", ".jpeg"),
        "image/jpg" to setOf(".jpg", ".jpeg"),
        "image/gif" to setOf(".gif"),
        "image/bmp" to setOf(".bmp"),
        "image/webp" to setOf(".webp"),
        "video/mp4" to setOf(".mp4", ".m4v"),
        "video/webm" to setOf(".webm"),
        "video/quicktime" to setOf(".mov"),
        "video/x-m4v" to setOf(".m4v"),
        "video/ogg" to setOf(".ogg", ".ogv"),
        "audio/mpeg" to setOf(".mp3"),
        "audio/mp3" to setOf(".mp3"),
        "audio/wav" to setOf(".wav"),
        "audio/x-wav" to setOf(".wav"),
        "audio/mp4" to setOf(".m4a"),
        "audio/x-m4a" to setOf(".m4a"),
        "audio/aac" to setOf(".aac"),
        "audio/flac" to setOf(".flac"),
        "audio/opus" to setOf(".opus"),
        "audio/ogg" to setOf(".ogg", ".opus"),
        "application/ogg" to setOf(".ogg", ".ogv", ".opus")
    )
    private val jsonLikeMimeTypes = setOf(
        "application/json",
        "application/x-ndjson",
        "application/jsonl",
        "text/json",
        "text/plain"
    )
    private val yamlLikeMimeTypes = setOf(
        "application/yaml",
        "application/x-yaml",
        "text/yaml",
        "text/x-yaml"
    )

    fun normalizeAcceptTokens(acceptTypes: Array<String>): List<String> {
        return acceptTypes
            .asSequence()
            .flatMap { acceptValue -> acceptValue.split(',').asSequence() }
            .map { acceptToken -> normalizeAcceptToken(acceptToken) }
            .filter { acceptToken -> acceptToken.isNotEmpty() }
            .toList()
    }

    fun expandGeckoPromptAcceptTypes(acceptTypes: Array<String>): Array<String> {
        val acceptTokens = normalizeAcceptTokens(acceptTypes)
            .ifEmpty { listOf("*/*") }
        if (acceptTokens.any { acceptToken -> acceptToken == "*/*" }) {
            return acceptTokens.toTypedArray()
        }

        val hasJsonLikeMime = acceptTokens.any(::isJsonLikeMimeToken)
        val hasYamlLikeMime = acceptTokens.any(::isYamlLikeMimeToken)
        val hasImageMime = acceptTokens.any { acceptToken ->
            acceptToken.equals("image/*", ignoreCase = true) ||
                acceptToken.equals("image/png", ignoreCase = true)
        }
        if (!hasJsonLikeMime && !hasYamlLikeMime) {
            return acceptTokens.toTypedArray()
        }

        val expandedAcceptTokens = LinkedHashSet<String>()
        expandedAcceptTokens += acceptTokens
        if (hasJsonLikeMime) {
            expandedAcceptTokens += ".json"
            expandedAcceptTokens += ".jsonl"
            // Tavern 的预设、UI 预设和世界书导入有多种 JSON 容器扩展；
            // Gecko 部分版本只回传 application/json，必须把这些扩展补回选后校验。
            expandedAcceptTokens += ".settings"
            expandedAcceptTokens += ".preset"
            expandedAcceptTokens += ".lorebook"
        }
        if (hasYamlLikeMime) {
            expandedAcceptTokens += ".yaml"
            expandedAcceptTokens += ".yml"
        }
        if (hasImageMime) {
            // Gecko 文件 prompt 可能只给 MIME，丢掉 input accept 中的 .charx/.byaf 扩展；
            // 角色导入同时允许图片和结构化数据时，把 Tavern 常见角色包扩展补回宿主选后校验。
            expandedAcceptTokens += ".charx"
            expandedAcceptTokens += ".byaf"
        }
        return expandedAcceptTokens.toTypedArray()
    }

    fun resolveAndroidIntentMimeTypes(acceptTypes: Array<String>): Array<String> {
        val mimeTypes = normalizeAcceptTokens(acceptTypes)
            .filter { acceptToken -> acceptToken.isAndroidIntentMimeType() }
            .ifEmpty { listOf("*/*") }
        return mimeTypes.toTypedArray()
    }

    fun shouldForceSelectionFilter(acceptTokens: List<String>, forceAcceptTokenSelectionFilter: Boolean): Boolean {
        return forceAcceptTokenSelectionFilter && acceptTokens.any { acceptToken -> acceptToken.startsWith(".") }
    }

    fun accepts(
        acceptTokens: List<String>,
        displayName: String?,
        mimeType: String
    ): Boolean {
        val normalizedDisplayName = displayName?.trim().orEmpty()
        val normalizedMimeType = mimeType.trim().lowercase()
        return acceptTokens.any { acceptToken ->
            when {
                acceptToken == "*/*" -> true
                acceptToken.equals(".jsonl", ignoreCase = true) -> {
                    normalizedDisplayName.endsWith(".jsonl", ignoreCase = true) ||
                        normalizedMimeType in jsonlMimeAliases
                }

                acceptToken.startsWith(".") -> {
                    normalizedDisplayName.endsWith(acceptToken, ignoreCase = true) ||
                        normalizedMimeType in extensionMimeAliases[acceptToken.lowercase()].orEmpty()
                }

                acceptToken.endsWith("/*") -> {
                    val mimePrefix = acceptToken.removeSuffix("*")
                    normalizedMimeType.startsWith(mimePrefix, ignoreCase = true) ||
                        displayNameMatchesKnownMimeFamily(normalizedDisplayName, mimePrefix)
                }

                acceptToken.contains('/') -> {
                    normalizedMimeType.equals(acceptToken, ignoreCase = true) ||
                        displayNameMatchesMimeAlias(normalizedDisplayName, acceptToken)
                }
                else -> false
            }
        }
    }

    private fun normalizeAcceptToken(acceptToken: String): String {
        val token = acceptToken.trim()
        if (token.startsWith("*.") && token.length > 2) {
            return token.drop(1)
        }
        return token
    }

    private fun String.isAndroidIntentMimeType(): Boolean {
        val token = trim()
        return token == "*/*" || (token.contains('/') && !token.startsWith(".") && !token.contains(','))
    }

    private fun displayNameMatchesMimeAlias(displayName: String, mimeType: String): Boolean {
        if (displayName.isBlank()) {
            return false
        }
        return mimeExtensionAliases[mimeType.trim().lowercase()].orEmpty()
            .any { extension -> displayName.endsWith(extension, ignoreCase = true) }
    }

    private fun displayNameMatchesKnownMimeFamily(displayName: String, mimePrefix: String): Boolean {
        val normalizedPrefix = mimePrefix.trim().lowercase()
        if (displayName.isBlank()) {
            return false
        }
        return mimeExtensionAliases
            .filterKeys { mimeType -> mimeType.startsWith(normalizedPrefix) }
            .values
            .flatten()
            .any { extension -> displayName.endsWith(extension, ignoreCase = true) }
    }

    private fun isJsonLikeMimeToken(acceptToken: String): Boolean {
        val normalizedToken = acceptToken.trim().lowercase()
        return normalizedToken in jsonLikeMimeTypes ||
            normalizedToken.endsWith("+json")
    }

    private fun isYamlLikeMimeToken(acceptToken: String): Boolean {
        return acceptToken.trim().lowercase() in yamlLikeMimeTypes
    }
}
