package com.jm.sillydroid.feature.main.ui.home.io

import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserFileChooserSelectionPolicyTest {

    @Test
    fun `geckoview character import keeps extension accept tokens usable`() {
        val acceptTokens = BrowserFileChooserSelectionPolicy.normalizeAcceptTokens(
            arrayOf(".json,image/png,.yaml,.yml,.charx,.byaf")
        )

        assertTrue(BrowserFileChooserSelectionPolicy.shouldForceSelectionFilter(acceptTokens, true))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "Seraphina.png", "image/png"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "Default.json", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "preset.yaml", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "card.charx", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "backup.byaf", "application/octet-stream"))
        assertFalse(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "notes.txt", "text/plain"))
    }

    @Test
    fun `jsonl imports still accept Android mime aliases`() {
        val acceptTokens = BrowserFileChooserSelectionPolicy.normalizeAcceptTokens(
            arrayOf("application/json,.jsonl")
        )

        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "chat.jsonl", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "download.bin", "application/x-ndjson"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "download.bin", "text/plain"))
    }

    @Test
    fun `geckoview mime only character prompt expands tavern import extensions`() {
        val acceptTokens = BrowserFileChooserSelectionPolicy.expandGeckoPromptAcceptTypes(
            arrayOf("application/json", "image/png")
        ).toList()

        assertTrue(acceptTokens.contains(".json"))
        assertTrue(acceptTokens.contains(".jsonl"))
        assertTrue(acceptTokens.contains(".settings"))
        assertTrue(acceptTokens.contains(".preset"))
        assertTrue(acceptTokens.contains(".lorebook"))
        assertTrue(acceptTokens.contains(".charx"))
        assertTrue(acceptTokens.contains(".byaf"))
        assertTrue(BrowserFileChooserSelectionPolicy.shouldForceSelectionFilter(acceptTokens, true))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "Default.json", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "Lorebook.jsonl", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "kobold.settings", "text/plain"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "novel.preset", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "world.lorebook", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "Seraphina.charx", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "Backup.byaf", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "Card.png", "image/png"))
    }

    @Test
    fun `geckoview mime only yaml prompt expands world book extensions`() {
        val acceptTokens = BrowserFileChooserSelectionPolicy.expandGeckoPromptAcceptTypes(
            arrayOf("application/x-yaml")
        ).toList()

        assertTrue(acceptTokens.contains(".yaml"))
        assertTrue(acceptTokens.contains(".yml"))
        assertTrue(BrowserFileChooserSelectionPolicy.shouldForceSelectionFilter(acceptTokens, true))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "world.yaml", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "preset.yml", "application/octet-stream"))
        assertFalse(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "avatar.png", "image/png"))
    }

    @Test
    fun `preset and world book imports accept json container extensions by file name`() {
        val acceptTokens = BrowserFileChooserSelectionPolicy.normalizeAcceptTokens(
            arrayOf(".json, .settings, .preset, .lorebook")
        )

        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "openai.json", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "kobold.settings", "text/plain"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "novel.preset", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "world.lorebook", "application/octet-stream"))
        assertFalse(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "avatar.png", "image/png"))
    }

    @Test
    fun `wildcard extension syntax is normalized for extension plugins`() {
        val acceptTokens = BrowserFileChooserSelectionPolicy.normalizeAcceptTokens(arrayOf("*.json"))

        assertTrue(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "regex.json", "application/octet-stream"))
        assertFalse(BrowserFileChooserSelectionPolicy.accepts(acceptTokens, "regex.yaml", "application/octet-stream"))
    }

    @Test
    fun `image and zip mime prompts also accept known file extensions`() {
        val imageTokens = BrowserFileChooserSelectionPolicy.normalizeAcceptTokens(arrayOf("image/*"))
        val zipTokens = BrowserFileChooserSelectionPolicy.normalizeAcceptTokens(arrayOf("application/zip"))

        assertTrue(BrowserFileChooserSelectionPolicy.accepts(imageTokens, "avatar.jpg", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(imageTokens, "sprite.gif", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(imageTokens, "portrait.webp", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(zipTokens, "expressions.zip", "application/octet-stream"))
        assertFalse(BrowserFileChooserSelectionPolicy.accepts(zipTokens, "expressions.json", "application/json"))
    }

    @Test
    fun `background and tts media prompts accept known file extensions by name`() {
        val mixedMediaTokens = BrowserFileChooserSelectionPolicy.normalizeAcceptTokens(arrayOf("image/*,video/*"))
        val audioTokens = BrowserFileChooserSelectionPolicy.normalizeAcceptTokens(arrayOf("audio/*"))

        assertTrue(BrowserFileChooserSelectionPolicy.accepts(mixedMediaTokens, "bg.mp4", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(mixedMediaTokens, "scene.webm", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(mixedMediaTokens, "loop.mov", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(audioTokens, "voice.mp3", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(audioTokens, "voice.wav", "application/octet-stream"))
        assertTrue(BrowserFileChooserSelectionPolicy.accepts(audioTokens, "voice.opus", "application/octet-stream"))
        assertFalse(BrowserFileChooserSelectionPolicy.accepts(audioTokens, "world.json", "application/json"))
    }

    @Test
    fun `geckoview chooser mime types split comma separated prompt tokens`() {
        val mimeTypes = BrowserFileChooserSelectionPolicy.resolveAndroidIntentMimeTypes(
            arrayOf("application/json,image/png,.charx")
        )

        assertArrayEquals(arrayOf("application/json", "image/png"), mimeTypes)
    }
}
