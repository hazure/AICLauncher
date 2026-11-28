package com.aliceincradle.launcher.data

import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPreOpenedStreamTest {
    @Test
    fun preOpenedStream_valid7zBytes_completesWithoutHttpReconnect() {
        val tempDir = createTempDir("aicl_preopened_ok")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val payload = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0x00, 0x04, 0x11, 0x22)
            val destFile = File(tempDir, "AliceInCradle_Win_Latest.7z")
            val latch = CountDownLatch(1)
            var completedFile: File? = null
            var failedMsg: String? = null
            var lastPercent = -1

            val manager = DownloadQueueManager(scope = scope, workerCount = 1)
            manager.setListener(object : DownloadProgressListener {
                override fun onStarted(itemId: String) {}
                override fun onProgress(itemId: String, downloadedBytes: Long, totalBytes: Long, percent: Int) {
                    lastPercent = percent
                }
                override fun onCompleted(itemId: String, file: File) {
                    completedFile = file
                    latch.countDown()
                }
                override fun onFailed(itemId: String, errorMessage: String) {
                    failedMsg = errorMessage
                    latch.countDown()
                }
            })

            manager.enqueue(
                DownloadRequest(
                    itemId = "aic-win",
                    url = "https://127.0.0.1:1/unreachable.7z",
                    destFile = destFile,
                    expectedBytes = payload.size.toLong(),
                    sourceChannel = DownloadSourceChannel.WEBVIEW_MAIN,
                    preOpenedStream = ByteArrayInputStream(payload),
                    preOpenedContentLength = payload.size.toLong()
                )
            )

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(null, failedMsg)
            assertEquals(destFile, completedFile)
            assertEquals(100, lastPercent)
            assertArrayEquals(payload, destFile.readBytes())
            manager.close()
        } finally {
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun preOpenedStream_cfChallengeHtml_failsMagicValidationAndCleansPart() {
        val tempDir = createTempDir("aicl_preopened_html")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val html = "<!DOCTYPE html><html><title>Just a moment...</title></html>".toByteArray(Charsets.UTF_8)
            val destFile = File(tempDir, "AliceInCradle_Win_Latest.7z")
            val latch = CountDownLatch(1)
            var failedMsg: String? = null

            val manager = DownloadQueueManager(scope = scope, workerCount = 1)
            manager.setListener(object : DownloadProgressListener {
                override fun onStarted(itemId: String) {}
                override fun onProgress(itemId: String, downloadedBytes: Long, totalBytes: Long, percent: Int) {}
                override fun onCompleted(itemId: String, file: File) {
                    latch.countDown()
                }
                override fun onFailed(itemId: String, errorMessage: String) {
                    failedMsg = errorMessage
                    latch.countDown()
                }
            })

            manager.enqueue(
                DownloadRequest(
                    itemId = "aic-win",
                    url = "https://127.0.0.1:1/unreachable.7z",
                    destFile = destFile,
                    expectedBytes = html.size.toLong(),
                    sourceChannel = DownloadSourceChannel.WEBVIEW_MAIN,
                    preOpenedStream = ByteArrayInputStream(html),
                    preOpenedContentLength = html.size.toLong()
                )
            )

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertTrue(failedMsg?.contains("CF_CHALLENGE_HTML") == true || failedMsg?.contains("HTML") == true)
            assertFalse(destFile.exists())
            assertFalse(File(tempDir, "${destFile.name}.part").exists())
            manager.close()
        } finally {
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }
}
