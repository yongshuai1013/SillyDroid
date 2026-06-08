package com.jm.sillydroid.feature.main.ui.home.io

import org.junit.Assert.assertEquals
import org.junit.Test

class GeckoFilePromptUriMaterializerTest {

    @Test
    fun `cache file name keeps tavern import extension`() {
        val fileName = GeckoFilePromptUriMaterializer.resolveCacheFileName("Seraphina.card.charx", 0)

        assertEquals("Seraphina.card.charx", fileName)
    }

    @Test
    fun `cache file name strips path traversal segments`() {
        val fileName = GeckoFilePromptUriMaterializer.resolveCacheFileName("../world.lorebook", 2)

        assertEquals("world.lorebook", fileName)
    }

    @Test
    fun `blank display name falls back to stable indexed name`() {
        val fileName = GeckoFilePromptUriMaterializer.resolveCacheFileName("", 1)

        assertEquals("selected-file-2", fileName)
    }

    @Test
    fun `cache file name keeps extension when original name is non ascii`() {
        val fileName = GeckoFilePromptUriMaterializer.resolveCacheFileName("世界书.json", 0)

        assertEquals("selected-file-1.json", fileName)
    }

    @Test
    fun `duplicate cache file names receive suffix only when needed`() {
        val usedNames = linkedSetOf<String>()

        val firstName = GeckoFilePromptUriMaterializer.resolveUniqueCacheFileName("same.json", usedNames)
        val secondName = GeckoFilePromptUriMaterializer.resolveUniqueCacheFileName("same.json", usedNames)

        assertEquals("same.json", firstName)
        assertEquals("same-2.json", secondName)
    }
}
