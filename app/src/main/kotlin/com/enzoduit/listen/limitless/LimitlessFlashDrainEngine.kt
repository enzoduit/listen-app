package com.enzoduit.listen.limitless

import android.util.Log
import com.enzoduit.listen.audio.LimitlessBatchAudioWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Native flash-drain engine for the Limitless pendant.
 * Adapted from OmiBleManager's LimitlessFlashDrainEngine:
 *  - Removed OmiBleManager singleton dependency (injected writeFn instead)
 *  - Removed Flutter SharedPreferences (config injected directly)
 *  - Removed Pigeon/Flutter prefs publishing
 *  - Added logFn callback for UI updates
 */
class LimitlessFlashDrainEngine(
    private val deviceAddress: String,
    private val serviceUuid: String,
    private val rxCharUuid: String,
    private val writer: LimitlessBatchAudioWriter,
    private val writeFn: (address: String, data: ByteArray) -> Unit,
    private val logFn: (msg: String) -> Unit = {},
) {
    companion object {
        private const val TAG = "Listen.DrainEngine"
        private const val CYCLE_MS = 90_000L
        private const val FIRST_CYCLE_DELAY_MS = 5_000L
        private const val STATUS_TIMEOUT_MS = 8_000L
        private const val STALL_MS = 30_000L
        private const val STALL_CHECK_MS = 5_000L
        private const val ACK_EVERY_PAGES = 25
    }

    private enum class Phase { IDLE, AWAITING_STATUS, DRAINING }

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "limitless-drain").apply { isDaemon = true }
    }

    // All fields below are executor-confined.
    private var phase = Phase.IDLE
    private var activeAddress: String? = null
    private var messageIndex = 0
    private var requestId = 0L
    private val fragmentBuffer = mutableMapOf<Int, MutableMap<Int, ByteArray>>()
    private var endPage = 0
    private var maxSeenPageIndex = -1
    private var lastAppendedPageIndex = -1
    private var lastAckedPageIndex = -1
    private var pagesSinceAck = 0
    private var lastPageAtMs = 0L
    private var cycleTask: ScheduledFuture<*>? = null
    private var statusTimeoutTask: ScheduledFuture<*>? = null
    private var stallCheckTask: ScheduledFuture<*>? = null

    fun onDeviceReady(address: String) {
        executor.execute {
            if (!address.equals(deviceAddress, ignoreCase = true)) return@execute
            activeAddress = address
            cycleTask?.cancel(false)
            cycleTask = executor.scheduleWithFixedDelay(
                { runCycle() },
                FIRST_CYCLE_DELAY_MS,
                CYCLE_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun onDeviceDisconnected(address: String) {
        executor.execute {
            if (!address.equals(deviceAddress, ignoreCase = true)) return@execute
            cycleTask?.cancel(false)
            cycleTask = null
            resetDrainState("disconnected")
            activeAddress = null
            messageIndex = 0
            requestId = 0
            writer.stop("ble_disconnected")
        }
    }

    fun stop(reason: String) {
        executor.execute {
            cycleTask?.cancel(false)
            cycleTask = null
            resetDrainState(reason)
        }
        writer.stop(reason)
    }

    fun handleCharacteristic(address: String, serviceUuid: String, characteristicUuid: String, value: ByteArray) {
        if (!address.equals(deviceAddress, ignoreCase = true)) {
            logFn("RX ignored: wrong address $address (expected $deviceAddress)")
            return
        }
        if (!serviceUuid.equals(this.serviceUuid, ignoreCase = true)) {
            logFn("RX ignored: wrong service $serviceUuid")
            return
        }
        if (!characteristicUuid.equals(rxCharUuid, ignoreCase = true)) {
            logFn("RX ignored: wrong char $characteristicUuid")
            return
        }
        executor.execute { processPacket(value) }
    }

    // ── Cycle (executor-confined) ──

    private fun runCycle() {
        val address = activeAddress ?: return
        if (phase != Phase.IDLE) return

        logFn("Cycle: sending time-sync + GetDeviceStatus to $address")
        phase = Phase.AWAITING_STATUS
        write(address, LimitlessProtocol.encodeSetCurrentTime(messageIndex++, ++requestId, System.currentTimeMillis()))
        write(address, LimitlessProtocol.encodeGetDeviceStatus(messageIndex++, ++requestId))

        statusTimeoutTask?.cancel(false)
        statusTimeoutTask = executor.schedule({
            if (phase == Phase.AWAITING_STATUS) {
                Log.w(TAG, "storage status timed out — retrying next cycle")
                logFn("Status timeout — will retry")
                phase = Phase.IDLE
            }
        }, STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun processPacket(data: ByteArray) {
        if (phase == Phase.AWAITING_STATUS) {
            LimitlessProtocol.parseDeviceStatus(data)?.let { onStorageState(it) }
        }

        val packet = LimitlessProtocol.parseBlePacket(data) ?: return
        fragmentBuffer.getOrPut(packet.index) { mutableMapOf() }[packet.seq] = packet.payload
        val fragments = fragmentBuffer[packet.index] ?: return
        if (fragments.size != packet.numFrags) return

        // Reassemble all fragments in order
        var totalSize = 0
        for (i in 0 until packet.numFrags) totalSize += fragments[i]?.size ?: 0
        val complete = ByteArray(totalSize)
        var offset = 0
        for (i in 0 until packet.numFrags) {
            val fragment = fragments[i] ?: continue
            fragment.copyInto(complete, offset)
            offset += fragment.size
        }
        fragmentBuffer.remove(packet.index)

        if (phase == Phase.DRAINING) {
            for (page in LimitlessProtocol.parsePendantMessage(complete)) {
                processFlashPage(page)
            }
        }
    }

    private fun onStorageState(state: LimitlessProtocol.StorageState) {
        if (phase != Phase.AWAITING_STATUS) return
        statusTimeoutTask?.cancel(false)

        val pageCount = state.newestFlashPage - state.oldestFlashPage + 1
        val msg = "Storage: oldest=${state.oldestFlashPage} newest=${state.newestFlashPage} free=${state.freeCapturePages}/${state.totalCapturePages}"
        Log.i(TAG, msg)
        logFn(msg)

        if (state.newestFlashPage < state.oldestFlashPage || pageCount <= 0) {
            Log.i(TAG, "No pages to drain — going idle")
            logFn("Nothing to drain")
            phase = Phase.IDLE
            return
        }

        val address = activeAddress ?: run { phase = Phase.IDLE; return }
        fragmentBuffer.clear()
        endPage = state.newestFlashPage
        maxSeenPageIndex = -1
        lastAppendedPageIndex = -1
        lastAckedPageIndex = -1
        pagesSinceAck = 0
        lastPageAtMs = System.currentTimeMillis()
        phase = Phase.DRAINING

        val drainMsg = "Drain start: pages ${state.oldestFlashPage}..${state.newestFlashPage} ($pageCount)"
        Log.i(TAG, drainMsg)
        logFn(drainMsg)

        write(address, LimitlessProtocol.encodeDownloadFlashPages(messageIndex++, ++requestId, batchMode = true, realTime = false))

        stallCheckTask?.cancel(false)
        stallCheckTask = executor.scheduleWithFixedDelay({
            if (phase == Phase.DRAINING && System.currentTimeMillis() - lastPageAtMs > STALL_MS) {
                finishDrain("stall")
            }
        }, STALL_CHECK_MS, STALL_CHECK_MS, TimeUnit.MILLISECONDS)
    }

    private fun processFlashPage(page: LimitlessProtocol.FlashPage) {
        lastPageAtMs = System.currentTimeMillis()
        val index = page.index ?: return

        if (page.opusFrames.isNotEmpty()) {
            if (!writer.append(page.opusFrames, page.timestampMs)) {
                Log.w(TAG, "append failed — pausing drain")
                logFn("Write failed — pausing drain")
                finishDrain("append_failed")
                return
            }
        }
        lastAppendedPageIndex = maxOf(lastAppendedPageIndex, index)
        maxSeenPageIndex = maxOf(maxSeenPageIndex, index)
        pagesSinceAck++

        if (pagesSinceAck >= ACK_EVERY_PAGES) {
            if (!ackWritten()) {
                finishDrain("fsync_failed")
                return
            }
        }
        if (maxSeenPageIndex >= endPage) {
            finishDrain("caught_up")
        }
    }

    /**
     * fsync barrier then ACK. Never ACKs un-fsynced pages.
     * ACK deletes pendant's copy — must be durable first.
     */
    private fun ackWritten(): Boolean {
        val address = activeAddress ?: return true
        if (lastAppendedPageIndex <= lastAckedPageIndex) return true
        if (!writer.sync()) {
            Log.w(TAG, "fsync failed — rolling back ACK watermark")
            lastAppendedPageIndex = lastAckedPageIndex
            return false
        }
        write(address, LimitlessProtocol.encodeAcknowledgeProcessedData(messageIndex++, ++requestId, lastAppendedPageIndex))
        lastAckedPageIndex = lastAppendedPageIndex
        pagesSinceAck = 0
        return true
    }

    private fun finishDrain(reason: String) {
        if (phase != Phase.DRAINING) return
        stallCheckTask?.cancel(false)
        stallCheckTask = null
        ackWritten()
        val address = activeAddress
        if (address != null) {
            // Return pendant to record-to-flash mode
            write(address, LimitlessProtocol.encodeDownloadFlashPages(messageIndex++, ++requestId, batchMode = false, realTime = false))
        }
        phase = Phase.IDLE
        val msg = "Drain finished ($reason): appended<=$lastAppendedPageIndex acked<=$lastAckedPageIndex end=$endPage"
        Log.i(TAG, msg)
        logFn(msg)
    }

    private fun resetDrainState(reason: String) {
        statusTimeoutTask?.cancel(false)
        stallCheckTask?.cancel(false)
        statusTimeoutTask = null
        stallCheckTask = null
        fragmentBuffer.clear()
        if (phase == Phase.DRAINING) {
            val msg = "Drain aborted ($reason): acked<=$lastAckedPageIndex"
            Log.i(TAG, msg)
            logFn(msg)
        }
        phase = Phase.IDLE
    }

    private fun write(address: String, data: ByteArray) {
        try {
            writeFn(address, data)
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
        }
    }
}
