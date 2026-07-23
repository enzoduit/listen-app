package com.enzoduit.listen.ble

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.enzoduit.listen.R
import com.enzoduit.listen.audio.LimitlessBatchAudioWriter
import com.enzoduit.listen.limitless.LimitlessFlashDrainEngine
import com.enzoduit.listen.ui.MainActivity
import com.enzoduit.listen.upload.HttpUploader

/**
 * Foreground service that manages BLE connection to the Limitless pendant.
 * Lifecycle: connect → subscribe RX → drain flash → upload files → repeat.
 * Auto-reconnects on disconnect.
 */
@SuppressLint("MissingPermission")
class PendantBleForegroundService : Service() {

    companion object {
        private const val TAG = "Listen.Service"
        const val CHANNEL_ID = "listen_ble_channel"
        const val NOTIF_ID = 1001
        const val DEFAULT_ADDRESS = "FD:04:D0:EB:84:88"
        var targetAddress: String = DEFAULT_ADDRESS
        const val LIMITLESS_SERVICE_UUID = "632de001-604c-446b-a80f-7963e950f3fb"
        const val LIMITLESS_RX_UUID = "632de003-604c-446b-a80f-7963e950f3fb"

        const val ACTION_START = "com.enzoduit.listen.START"
        const val ACTION_STOP = "com.enzoduit.listen.STOP"

        // Broadcast actions for UI updates
        const val BROADCAST_STATUS = "com.enzoduit.listen.STATUS"
        const val EXTRA_BLE_STATUS = "ble_status"
        const val EXTRA_LOG_LINE = "log_line"
        const val EXTRA_UPLOAD_COUNT = "upload_count"

        private const val RECONNECT_DELAY_MS = 5_000L
        private const val MTU_SIZE = 512
    }

    private lateinit var bleManager: PendantBleManager
    private lateinit var writer: LimitlessBatchAudioWriter
    private lateinit var drainEngine: LimitlessFlashDrainEngine
    private lateinit var uploader: HttpUploader
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connected = false
    private var reconnectRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting..."))

        bleManager = PendantBleManager(application)
        writer = LimitlessBatchAudioWriter(this) { fileName ->
            log("File finalized: $fileName — triggering upload")
            triggerUpload()
        }
        drainEngine = LimitlessFlashDrainEngine(
            deviceAddress = targetAddress,
            serviceUuid = LIMITLESS_SERVICE_UUID,
            rxCharUuid = LIMITLESS_RX_UUID,
            writer = writer,
            writeFn = { address, data ->
                bleManager.writeCharacteristic(
                    address,
                    LIMITLESS_SERVICE_UUID,
                    "632de002-604c-446b-a80f-7963e950f3fb",
                    data
                ) { result ->
                    result.exceptionOrNull()?.let { Log.w(TAG, "TX write failed: ${it.message}") }
                }
            },
            logFn = { msg -> log(msg) }
        )
        uploader = HttpUploader(this) { count ->
            sendBroadcast(Intent(BROADCAST_STATUS).apply {
                putExtra(EXTRA_UPLOAD_COUNT, count)
                `package` = packageName
            })
        }

        bleManager.connectionListener = object : BleConnectionListener {
            override fun onGattConnected(address: String, gatt: BluetoothGatt) {
                log("GATT connected: $address")
            }

            override fun onGattDisconnected(address: String, gattHash: Int, status: Int) {
                log("GATT disconnected: $address status=$status")
                connected = false
                drainEngine.onDeviceDisconnected(address)
                updateNotification("Disconnected — reconnecting...")
                broadcastStatus("Disconnected")
                scheduleReconnect()
            }

            override fun onGattServicesDiscovered(address: String) {
                // Log all services to UI
                val gatt = bleManager.connectedGatts[address]
                gatt?.services?.forEach { svc ->
                    log("Service: ${svc.uuid.toString().take(8)}... (${svc.characteristics.size} chars)")
                }
                log("Services discovered → requesting MTU 512")
                bleManager.enqueueCommand {
                    bleManager.connectedGatts[address]?.requestMtu(MTU_SIZE) ?: bleManager.completeCommand()
                }
            }

            override fun onMtuChanged(address: String, mtu: Int, status: Int) {
                log("MTU=$mtu status=$status → subscribing")
                mainHandler.postDelayed({
                    subscribeAndStart(address)
                }, 1000)
            }
        }

        bleManager.characteristicValueListener = object : CharacteristicValueListener {
            override fun onCharacteristicValue(address: String, serviceUuid: String, characteristicUuid: String, value: ByteArray) {
                val hex = value.take(16).joinToString(" ") { "%02x".format(it) }
                log("RX ${value.size}b: $hex${if (value.size > 16) "..." else ""}")
                drainEngine.handleCharacteristic(address, serviceUuid, characteristicUuid, value)
            }
        }

        log("Service started, connecting to $targetAddress")
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        drainEngine.stop("service_destroyed")
        bleManager.disconnectGatt(targetAddress)
        bleManager.closeGatt(targetAddress)
        log("Service destroyed")
    }

    private fun connect() {
        if (!bleManager.isBluetoothEnabled()) {
            updateNotification("Bluetooth off — waiting")
            broadcastStatus("Bluetooth off")
            scheduleReconnect()
            return
        }
        updateNotification("Connecting to pendant...")
        broadcastStatus("Connecting...")
        log("Connecting to $targetAddress")
        bleManager.connectGatt(targetAddress, autoConnect = false)
    }

    private fun subscribeAndStart(address: String) {
        log("Subscribing to RX notifications...")
        bleManager.subscribeCharacteristic(address, LIMITLESS_SERVICE_UUID, LIMITLESS_RX_UUID)
        // Small delay then fire drain engine
        mainHandler.postDelayed({
            connected = true
            drainEngine.onDeviceReady(address)
            updateNotification("Connected — syncing...")
            broadcastStatus("Connected")
            log("Device ready, drain engine started — waiting 5s for first cycle")
        }, 1000)
    }

    private fun scheduleReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = Runnable { connect() }
        mainHandler.postDelayed(reconnectRunnable!!, RECONNECT_DELAY_MS)
    }

    private fun triggerUpload() {
        val audioDir = getExternalFilesDir("audio")?.absolutePath ?: return
        uploader.uploadPending(audioDir)
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        com.enzoduit.listen.util.RemoteLogger.log(msg)
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_LOG_LINE, msg)
            `package` = packageName
        })
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_BLE_STATUS, status)
            `package` = packageName
        })
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Listen")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Listen BLE Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Syncing Limitless pendant audio"
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
