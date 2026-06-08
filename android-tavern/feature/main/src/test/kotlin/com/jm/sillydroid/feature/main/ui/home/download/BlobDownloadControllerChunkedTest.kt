package com.jm.sillydroid.feature.main.ui.home.download

import android.content.ContentResolver
import com.jm.sillydroid.feature.main.model.download.BlobDownloadChunkRequest
import com.jm.sillydroid.feature.main.model.download.BlobDownloadChunkedStartRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock

class BlobDownloadControllerChunkedTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `chunked download accepts sequential chunks`() {
        val controller = newController()
        val startRequest = BlobDownloadChunkedStartRequest(
            downloadId = "gecko-1",
            fileName = "world.json",
            mimeType = "application/json",
            totalBase64Length = 8L,
            chunkCount = 2
        )
        val firstChunk = BlobDownloadChunkRequest(downloadId = "gecko-1", index = 0, base64Data = "AAAA")
        val secondChunk = BlobDownloadChunkRequest(downloadId = "gecko-1", index = 1, base64Data = "BBBB")

        controller.beginChunkedDownload(startRequest)
        controller.appendChunkedDownload(firstChunk)
        controller.appendChunkedDownload(secondChunk)

        assertEquals(1, tempFolder.root.resolve("chunks").listFiles()?.size)
    }

    @Test
    fun `chunked download rejects out of order chunks`() {
        val controller = newController()
        val startRequest = BlobDownloadChunkedStartRequest(
            downloadId = "gecko-2",
            fileName = "chat.jsonl",
            mimeType = "application/jsonl",
            totalBase64Length = 8L,
            chunkCount = 2
        )
        val secondChunk = BlobDownloadChunkRequest(downloadId = "gecko-2", index = 1, base64Data = "BBBB")

        controller.beginChunkedDownload(startRequest)

        assertThrows(IllegalStateException::class.java) {
            controller.appendChunkedDownload(secondChunk)
        }
    }

    @Test
    fun `chunked download can be cancelled and removes session`() {
        val controller = newController()
        val startRequest = BlobDownloadChunkedStartRequest(
            downloadId = "gecko-3",
            fileName = "empty.json",
            mimeType = "application/json",
            totalBase64Length = 0L,
            chunkCount = 0
        )
        val chunkRequest = BlobDownloadChunkRequest(downloadId = "gecko-3", index = 0, base64Data = "AAAA")

        controller.beginChunkedDownload(startRequest)
        controller.cancelChunkedDownload("gecko-3")

        assertThrows(IllegalStateException::class.java) {
            controller.appendChunkedDownload(chunkRequest)
        }
    }

    private fun newController(): BlobDownloadController {
        return BlobDownloadController(
            contentResolver = mock<ContentResolver>(),
            chunkTempDirectory = tempFolder.newFolder("chunks")
        )
    }
}
