package com.enzoduit.listen.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Shared file mechanics for batch audio capture.
 * Adapted from Omi's BaseBatchAudioWriter:
 *  - Removed OmiBleManager/Flutter/Pigeon dependencies
 *  - notifyFinalized replaced by a callback lambda
 *  - Storage full flag stored in local SharedPreferences (not flutter prefs)
 */
abstract class BaseBatchAudioWriter(
    protected val context: Context,
    private val tag: String,
    private val recoveryPrefix: String,
    private val onFinalized: ((fileName: String) -> Unit)? = null,
) {
    companion object {
        const val PART_SUFFIX = ".part"
        private const val FSYNC_INTERVAL_MS = 2_000L
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024 // 200 MB
    }

    protected val lock = Any()
    private var raf: RandomAccessFile? = null
    private var currentFile: File? = null

    protected var currentStartSec: Long = 0
        private set
    protected var currentBytes: Long = 0
        private set
    protected var currentFrames: Long = 0
        private set

    private var lastFsyncMs: Long = 0
    private var storageFull = false
    private var recovered = false
    private var closeSyncFailed = false

    protected val isOpenLocked: Boolean get() = raf != null

    fun stop(reason: String) {
        synchronized(lock) { closeCurrentLocked(reason) }
    }

    /** Close and finalize current file (triggers upload), but stay ready for new data. */
    fun flush(reason: String) {
        synchronized(lock) { closeCurrentLocked(reason) }
        // Do NOT set stopped — writer stays open for next append
    }

    protected fun openLocked(dirPath: String, fileName: String, startSec: Long, nowMs: Long): Boolean {
        if (raf != null) return true

        val dir = File(dirPath)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(tag, "cannot create batch dir $dirPath")
            return false
        }

        if (!recovered) {
            recovered = true
            recoverStalePartFiles(dir)
        }

        if (dir.usableSpace < MIN_FREE_BYTES) {
            if (!storageFull) {
                Log.w(tag, "storage low (${dir.usableSpace} bytes free) — pausing capture")
                storageFull = true
            }
            return false
        }
        storageFull = false

        val file = File(dir, fileName)
        return try {
            val out = RandomAccessFile(file, "rw")
            out.seek(out.length())
            raf = out
            currentFile = file
            currentStartSec = startSec
            currentBytes = file.length()
            currentFrames = 0
            lastFsyncMs = nowMs
            Log.i(tag, "opened batch file $fileName")
            true
        } catch (e: Exception) {
            Log.e(tag, "open failed for $fileName: ${e.message}")
            raf = null
            currentFile = null
            false
        }
    }

    /**
     * Write frames with 4-byte LE length prefix per frame.
     * On failure: finalizes what was written so far, returns false.
     */
    protected fun writeFramesLocked(frames: List<ByteArray>): Boolean {
        val out = raf ?: return false
        return try {
            for (frame in frames) {
                val len = frame.size
                val header = byteArrayOf(
                    (len and 0xFF).toByte(),
                    ((len shr 8) and 0xFF).toByte(),
                    ((len shr 16) and 0xFF).toByte(),
                    ((len shr 24) and 0xFF).toByte(),
                )
                out.write(header)
                out.write(frame)
                currentBytes += 4 + len
                currentFrames++
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "write failed: ${e.message}")
            try { out.setLength(currentBytes) } catch (_: Exception) {}
            closeCurrentLocked("write_error")
            false
        }
    }

    protected fun maybeFsyncLocked(nowMs: Long) {
        if (nowMs - lastFsyncMs >= FSYNC_INTERVAL_MS) {
            fsyncLocked()
            lastFsyncMs = nowMs
        }
    }

    protected fun fsyncLocked(): Boolean =
        try {
            raf?.fd?.sync()
            true
        } catch (e: Exception) {
            Log.w(tag, "fsync failed: ${e.message}")
            false
        }

    protected fun consumeCloseSyncFailureLocked(): Boolean {
        val failed = closeSyncFailed
        closeSyncFailed = false
        return failed
    }

    protected fun closeCurrentLocked(reason: String) {
        val out = raf
        if (out != null) {
            val partFile = currentFile
            var synced = true
            try { out.fd.sync() } catch (_: Exception) { synced = false }
            try { out.close() } catch (_: Exception) {}

            if (partFile != null) {
                when {
                    currentBytes > 0 && synced -> {
                        val finalFile = File(partFile.parentFile, partFile.name.removeSuffix(PART_SUFFIX))
                        if (partFile.renameTo(finalFile)) {
                            Log.i(tag, "finalized ${finalFile.name} ($currentFrames frames, $currentBytes bytes, reason=$reason)")
                            onFinalized?.invoke(finalFile.name)
                        } else {
                            Log.w(tag, "failed to rename ${partFile.name}")
                        }
                    }
                    currentBytes > 0 -> {
                        closeSyncFailed = true
                        Log.w(tag, "close fsync failed — leaving ${partFile.name} as .part")
                    }
                    else -> partFile.delete()
                }
            }
            raf = null
            currentFile = null
            currentStartSec = 0
            currentBytes = 0
            currentFrames = 0
        }
        onClosedLocked()
    }

    protected open fun onClosedLocked() {}

    private fun recoverStalePartFiles(dir: File) {
        try {
            val parts = dir.listFiles { f ->
                f.isFile && f.name.startsWith(recoveryPrefix) && f.name.endsWith(".bin$PART_SUFFIX")
            } ?: return
            for (p in parts) {
                if (p.length() > 0L) {
                    val finalFile = File(dir, p.name.removeSuffix(PART_SUFFIX))
                    if (p.renameTo(finalFile)) Log.i(tag, "recovered stale: ${finalFile.name}")
                } else {
                    p.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "recoverStalePartFiles failed: ${e.message}")
        }
    }
}
