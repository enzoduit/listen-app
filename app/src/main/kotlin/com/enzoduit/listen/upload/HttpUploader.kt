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
 * POST multipart/form-data to https://pendant.enzoduit.com/v2/sync-local-files
 * On HTTP 200: deletes local file.
 * On failure: leaves file for retry.
 * Skips .bin.part files (still being written).
 */
class HttpUploader(
    private val context: Context,
    private val onUploadCountChanged: ((Int) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "Listen.Uploader"
        private const val ENDPOINT = "https://pendant-backend-production-e97c.up.railway.app/v2/sync-local-files"
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
                val sizeMb = file.length() / 1024.0 / 1024.0
                Log.i(TAG, "Uploading ${file.name} (${String.format("%.2f", sizeMb)}MB) to $ENDPOINT")
                com.enzoduit.listen.util.RemoteLogger.log("Upload start: ${file.name} (${String.format("%.2f", sizeMb)}MB)")
                val success = uploadFile(file)
                if (success) {
                    Log.i(TAG, "Uploaded ${file.name} ✅ — deleting")
                    com.enzoduit.listen.util.RemoteLogger.log("Upload success: ${file.name}")
                    file.delete()
                    val count = totalUploaded.incrementAndGet()
                    onUploadCountChanged?.invoke(count)
                } else {
                    Log.w(TAG, "Upload failed for ${file.name} — will retry next cycle")
                    com.enzoduit.listen.util.RemoteLogger.log("Upload FAILED: ${file.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload exception for ${file.name}: ${e.message}")
                com.enzoduit.listen.util.RemoteLogger.log("Upload EXCEPTION: ${file.name}: ${e.message}")
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
            val responseBody = try { connection.inputStream.bufferedReader().readText().take(200) } catch (e: Exception) { 
                try { connection.errorStream?.bufferedReader()?.readText()?.take(200) ?: "" } catch (_: Exception) { "" }
            }
            Log.i(TAG, "Upload ${file.name}: HTTP $responseCode body=$responseBody")
            com.enzoduit.listen.util.RemoteLogger.log("Upload HTTP $responseCode: $responseBody")
            responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }
}
