package com.jm.sillydroid.data.runtime

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BootstrapAssetDeleteTest {

    @Test
    fun `asset directory refresh deletes third party symlink without deleting persistent extensions`() {
        val rootDirectory = createTempDirectory(prefix = "asset-delete-third-party-link").toFile()
        try {
            val serverDir = File(rootDirectory, "bootstrap/server").apply { mkdirs() }
            val persistentExtensions = File(rootDirectory, "data/server/extensions").apply { mkdirs() }
            val sentinelExtension = File(persistentExtensions, "__sillydroid_sentinel_ext").apply { mkdirs() }
            File(sentinelExtension, "manifest.json").writeText("""{"display_name":"Sentinel"}""")
            File(sentinelExtension, "keep.txt").writeText("keep")

            val thirdPartyLink = File(serverDir, "public/scripts/extensions/third-party")
            thirdPartyLink.parentFile?.mkdirs()
            assumeTrue(createDirectorySymlink(thirdPartyLink, persistentExtensions))

            deleteRecursivelyWithoutFollowingSymlinks(serverDir)

            assertFalse(thirdPartyLink.exists())
            assertTrue(File(sentinelExtension, "manifest.json").isFile)
            assertTrue(File(sentinelExtension, "keep.txt").isFile)
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `asset directory refresh keeps persistent plugins and extensions behind server symlinks`() {
        val rootDirectory = createTempDirectory(prefix = "asset-delete-persistent-data-links").toFile()
        try {
            val serverDir = File(rootDirectory, "bootstrap/server").apply { mkdirs() }
            val persistentPlugins = File(rootDirectory, "data/server/plugins").apply { mkdirs() }
            val persistentExtensions = File(rootDirectory, "data/server/extensions").apply { mkdirs() }
            val sentinelPluginFile = File(persistentPlugins, "__sillydroid_plugin_sentinel/keep.txt")
                .apply {
                    parentFile?.mkdirs()
                    writeText("keep-plugin")
                }
            val sentinelExtensionFile = File(persistentExtensions, "__sillydroid_extension_sentinel/keep.txt")
                .apply {
                    parentFile?.mkdirs()
                    writeText("keep-extension")
                }

            val pluginsLink = File(serverDir, "plugins")
            val thirdPartyLink = File(serverDir, "public/scripts/extensions/third-party")
            thirdPartyLink.parentFile?.mkdirs()
            assumeTrue(createDirectorySymlink(pluginsLink, persistentPlugins))
            assumeTrue(createDirectorySymlink(thirdPartyLink, persistentExtensions))

            deleteRecursivelyWithoutFollowingSymlinks(serverDir)

            assertFalse(pluginsLink.exists())
            assertFalse(thirdPartyLink.exists())
            assertTrue(sentinelPluginFile.isFile)
            assertTrue(sentinelExtensionFile.isFile)
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `recursive asset delete removes symlink itself instead of linked directory contents`() {
        val rootDirectory = createTempDirectory(prefix = "asset-delete-link-only").toFile()
        try {
            val targetDirectory = File(rootDirectory, "persistent-target").apply { mkdirs() }
            val sentinelFile = File(targetDirectory, "keep.txt").apply { writeText("keep") }
            val assetDirectory = File(rootDirectory, "asset-root").apply { mkdirs() }
            val linkDirectory = File(assetDirectory, "linked-directory")
            assumeTrue(createDirectorySymlink(linkDirectory, targetDirectory))

            deleteRecursivelyWithoutFollowingSymlinks(assetDirectory)

            assertFalse(assetDirectory.exists())
            assertTrue(targetDirectory.isDirectory)
            assertTrue(sentinelFile.isFile)
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `recursive asset delete removes broken symlink`() {
        val rootDirectory = createTempDirectory(prefix = "asset-delete-broken-link").toFile()
        try {
            val assetDirectory = File(rootDirectory, "asset-root").apply { mkdirs() }
            val missingTargetDirectory = File(rootDirectory, "missing-target")
            val brokenLink = File(assetDirectory, "broken-link")
            assumeTrue(createDirectorySymlink(brokenLink, missingTargetDirectory))

            deleteRecursivelyWithoutFollowingSymlinks(assetDirectory)

            assertFalse(assetDirectory.exists())
            assertFalse(Files.exists(brokenLink.toPath()))
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    private fun createDirectorySymlink(link: File, target: File): Boolean {
        return runCatching {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        }.getOrDefault(false)
    }
}
