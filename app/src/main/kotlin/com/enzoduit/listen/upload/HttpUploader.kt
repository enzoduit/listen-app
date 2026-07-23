package com.enzoduit.listen.upload

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Uploads .bin audio files to the pendant sync webhook.
 * POST multipart/form-data to Railway backend.
 * On HTTP 200: deletes local file.
 * On failure: leaves file for retry.
 */
class HttpUploader(
    private val context: Context,
    private val onUploadCountChanged: ((Int) -> Unit)? = null,
    private val logFn: ((String) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "Listen.Uploader"
        private const val ENDPOINT = "http://188.245.214.39:8899/v2/sync-local-files"
        private const val API_KEY = "pendant-ed-2026"
        private const val TIMEOUT_MS = 60_000
        private const val BOUNDARY = "----ListenBoundary7MA4YWxkTrZu0gW"
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "listen-uploader").apply { isDaemon = true }
    }
    private val totalUploaded = AtomicInteger(0)

    fun uploadPending(audioDir: String) {
        executor.submit { doUpload(audioDir) }
    }

    private fun doUpload(audioDir: String) {
        val dir = File(audioDir)
        if (!dir.exists()) return

        val files = dir.listFiles { f ->
            f.isFile &&
            f.name.startsWith("audio_omibatchlimitless_") &&
            f.name.endsWith(".bin") &&
            !f.name.endsWith(".bin.part")
        } ?: return

        if (files.isEmpty()) return
        Log.i(TAG, "Found ${files.size} file(s) to upload")

        for (file in files) {
            try {
                val sizeKb = file.length() / 1024
                val msg = "⬆ Upload: ${file.name.takeLast(30)} (${sizeKb}KB)"
                Log.i(TAG, msg)
                logFn?.invoke(msg)

                val success = uploadFile(file)
                if (success) {
                    val ok = "✅ Upload OK: ${file.name.takeLast(20)}"
                    Log.i(TAG, ok)
                    logFn?.invoke(ok)
                    file.delete()
                    val count = totalUploaded.incrementAndGet()
                    onUploadCountChanged?.invoke(count)
                } else {
                    val fail = "❌ Upload FAILED: ${file.name.takeLast(20)}"
                    Log.w(TAG, fail)
                    logFn?.invoke(fail)
                }
            } catch (e: Exception) {
                val ex = "💥 Upload EX: ${e.message}"
                Log.e(TAG, ex)
                logFn?.invoke(ex)
            }
        }
    }

    private fun uploadFile(file: File): Boolean {
        val url = URL(ENDPOINT)
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("X-API-Key", API_KEY)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")

            val fileBytes = file.readBytes()
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val header = (twoHyphens + BOUNDARY + lineEnd +
                "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"" + lineEnd +
                "Content-Type: application/octet-stream" + lineEnd +
                lineEnd).toByteArray()

            val footer = (lineEnd + twoHyphens + BOUNDARY + twoHyphens + lineEnd).toByteArray()

            connection.setFixedLengthStreamingMode((header.size + fileBytes.size + footer.size).toLong())

            connection.outputStream.use { out ->
                out.write(header)
                out.write(fileBytes)
                out.write(footer)
                out.flush()
            }

            val responseCode = connection.responseCode
            val responseBody = try {
                connection.inputStream.bufferedReader().readText().take(100)
            } catch (e: Exception) {
                try { connection.errorStream?.bufferedReader()?.readText()?.take(100) ?: "" } catch (_: Exception) { "" }
            }
            val resp = "HTTP $responseCode: ${responseBody.take(80)}"
            Log.i(TAG, resp)
            logFn?.invoke(resp)
            responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }
}
