package com.enzoduit.listen.util

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * Fire-and-forget remote logger.
 * POSTs log lines to /v1/debug/log so Eddie can see what's happening live.
 */
object RemoteLogger {
    private const val TAG = "Listen.RemoteLog"
    private const val ENDPOINT = "https://pendant-backend-production-e97c.up.railway.app/v1/debug/log"
    private val queue = LinkedBlockingQueue<String>(200)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "remote-logger").apply { isDaemon = true }
    }

    init {
        executor.submit {
            while (true) {
                try {
                    val msg = queue.take()
                    send(msg)
                } catch (_: InterruptedException) { break }
                catch (e: Exception) { Log.w(TAG, "send failed: ${e.message}") }
            }
        }
    }

    fun log(msg: String) {
        queue.offer(msg)
    }

    private fun send(msg: String) {
        try {
            val url = URL(ENDPOINT)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val escaped = msg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            val json = """{"msg":"$escaped","src":"listen-app"}"""
            conn.outputStream.use { it.write(json.toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }
}
