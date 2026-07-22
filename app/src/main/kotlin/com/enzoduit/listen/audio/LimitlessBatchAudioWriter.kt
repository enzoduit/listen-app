package com.enzoduit.listen.audio

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.abs

/**
 * Batch audio sink for the Limitless pendant.
 * Adapted from Omi's LimitlessBatchAudioWriter:
 *  - batchAudioDir injected via context.getExternalFilesDir("audio") instead of Flutter prefs
 *  - onFinalized callback replaces Pigeon notify
 */
class LimitlessBatchAudioWriter(
    context: Context,
    onFinalized: ((fileName: String) -> Unit)? = null,
) : BaseBatchAudioWriter(context, TAG, "audio_${DEVICE_MARKER}_", onFinalized) {

    companion object {
        private const val TAG = "Listen.Writer"
        const val DEVICE_MARKER = "omibatchlimitless"
        private const val SESSION_GAP_MS = 120_000L   // >120s gap = new session
        private const val MAX_AUDIO_SECONDS = 900L     // 15 min per file
        private const val FRAMES_PER_SECOND = 50L      // opus_fs320 = 20ms frames
        private const val MIN_VALID_TS_MS = 1_577_836_800_000L // 2020-01-01 sanity gate
    }

    private val batchAudioDir: String
    private var lastPageTimestampMs: Long = 0

    init {
        // Use app-private external storage (no READ_EXTERNAL_STORAGE needed on API 29+)
        val dir = context.getExternalFilesDir("audio")
        batchAudioDir = dir?.absolutePath ?: context.filesDir.absolutePath + "/audio"
        Log.i(TAG, "Batch audio dir: $batchAudioDir")
    }

    /**
     * Append opus frames from one flash page.
     * [pageTimestampMs] is the pendant-clock capture time.
     * Returns true once frames are written (call [sync] before ACKing).
     */
    fun append(frames: List<ByteArray>, pageTimestampMs: Long): Boolean {
        if (frames.isEmpty()) return true
        val now = System.currentTimeMillis()

        synchronized(lock) {
            // Pendant clock sanity: bogus epoch pages get fallback timestamp
            val ts = when {
                pageTimestampMs > MIN_VALID_TS_MS -> pageTimestampMs
                lastPageTimestampMs > 0 -> lastPageTimestampMs
                else -> now
            }

            // Session gap detection
            if (isOpenLocked && lastPageTimestampMs > 0 && abs(ts - lastPageTimestampMs) > SESSION_GAP_MS) {
                closeCurrentLocked("session_gap")
            }

            // File rotation at max length
            if (isOpenLocked && currentFrames >= MAX_AUDIO_SECONDS * FRAMES_PER_SECOND) {
                closeCurrentLocked("rotate")
            }

            // Open new file if needed
            if (!isOpenLocked) {
                var startSec = ts / 1000
                // Never reuse an existing filename
                while (File(batchAudioDir, "audio_${DEVICE_MARKER}_opus_fs320_16000_1_fs320_${startSec}.bin").exists()) {
                    startSec++
                }
                val name = "audio_${DEVICE_MARKER}_opus_fs320_16000_1_fs320_${startSec}.bin${PART_SUFFIX}"
                if (!openLocked(batchAudioDir, name, startSec, now)) return false
            }

            if (!writeFramesLocked(frames)) return false
            lastPageTimestampMs = ts
            maybeFsyncLocked(now)
            return true
        }
    }

    /**
     * fsync barrier — call this before ACKing pages to the pendant.
     * Returns true when everything appended so far is durable on disk.
     */
    fun sync(): Boolean = synchronized(lock) {
        !consumeCloseSyncFailureLocked() && (!isOpenLocked || fsyncLocked())
    }

    override fun onClosedLocked() {
        lastPageTimestampMs = 0
    }
}
