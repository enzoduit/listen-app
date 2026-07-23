package com.enzoduit.listen.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.enzoduit.listen.ble.PendantBleForegroundService
import com.enzoduit.listen.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var logAdapter: ArrayAdapter<String>? = null
    private var serviceStarted = false

    // BLE scan
    private var bluetoothAdapter: BluetoothAdapter? = null
    @SuppressLint("MissingPermission")
    private val scanResults = mutableMapOf<String, String>() // address -> display name
    private val scanHandler = Handler(Looper.getMainLooper())
    private var isScanning = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(PendantBleForegroundService.EXTRA_BLE_STATUS)?.let {
                viewModel.updateBleStatus(it)
            }
            intent.getStringExtra(PendantBleForegroundService.EXTRA_LOG_LINE)?.let {
                viewModel.appendLog(it)
            }
            val uploadCount = intent.getIntExtra(PendantBleForegroundService.EXTRA_UPLOAD_COUNT, -1)
            if (uploadCount >= 0) viewModel.setUploadCount(uploadCount)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.all { it.value }) {
            startListenService()
        } else {
            viewModel.appendLog("❌ Permission denied — BLE sync requires Bluetooth permissions")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        logAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.listLog.adapter = logAdapter

        viewModel.bleStatus.observe(this) { status ->
            binding.textBleStatus.text = "BLE: $status"
        }
        viewModel.uploadCount.observe(this) { count ->
            binding.textUploadCount.text = "Uploads: $count"
        }
        viewModel.lastUploadTime.observe(this) { time ->
            binding.textLastUpload.text = "Last upload: $time"
        }
        viewModel.logLines.observe(this) { lines ->
            logAdapter?.clear()
            logAdapter?.addAll(lines)
            // Auto-scroll to bottom
            binding.listLog.smoothScrollToPosition(lines.size - 1)
        }

        binding.btnStart.setOnClickListener {
            checkPermissionsAndStart()
        }
        binding.btnStop.setOnClickListener {
            stopListenService()
        }
        binding.btnScan.setOnClickListener {
            checkPermissionsAndScan()
        }

        val filter = IntentFilter(PendantBleForegroundService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        viewModel.appendLog("App started. Target: ${PendantBleForegroundService.targetAddress}")
        viewModel.appendLog("Press 'Scan' to find nearby BLE devices, or 'Start Sync' to connect directly.")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
        stopBleScan()
    }

    private fun checkPermissionsAndStart() {
        val needed = neededPermissions()
        if (needed.isEmpty()) {
            startListenService()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun checkPermissionsAndScan() {
        val needed = neededPermissions()
        if (needed.isEmpty()) {
            startBleScan()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun neededPermissions(): List<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            viewModel.appendLog("❌ BLE scanner not available — is Bluetooth on?")
            return
        }
        if (isScanning) {
            viewModel.appendLog("Already scanning...")
            return
        }

        scanResults.clear()
        isScanning = true
        viewModel.appendLog("🔍 Scanning for BLE devices (10s)...")
        viewModel.updateBleStatus("Scanning...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)

        // Stop after 10 seconds and show results
        scanHandler.postDelayed({
            stopBleScan()
            showScanResults()
        }, 10_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        isScanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "Unknown"
            val rssi = result.rssi
            val display = "$name  [$address]  ${rssi}dBm"
            if (!scanResults.containsKey(address)) {
                viewModel.appendLog("  Found: $display")
            }
            scanResults[address] = display
        }

        override fun onScanFailed(errorCode: Int) {
            viewModel.appendLog("❌ Scan failed: error $errorCode")
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun showScanResults() {
        viewModel.updateBleStatus("Scan done")
        if (scanResults.isEmpty()) {
            viewModel.appendLog("No BLE devices found. Is pendant on?")
            return
        }

        val items = scanResults.values.toList()
        val addresses = scanResults.keys.toList()

        AlertDialog.Builder(this)
            .setTitle("BLE Devices Found")
            .setItems(items.toTypedArray()) { _, idx ->
                val selected = addresses[idx]
                viewModel.appendLog("✅ Selected: ${items[idx]}")
                viewModel.appendLog("Starting sync to $selected...")
                // Update target and start service
                PendantBleForegroundService.targetAddress = selected
                checkPermissionsAndStart()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startListenService() {
        if (serviceStarted) return
        serviceStarted = true
        val intent = Intent(this, PendantBleForegroundService::class.java).apply {
            action = PendantBleForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        viewModel.appendLog("▶ Service started → connecting to ${PendantBleForegroundService.targetAddress}")
    }

    private fun stopListenService() {
        serviceStarted = false
        val intent = Intent(this, PendantBleForegroundService::class.java).apply {
            action = PendantBleForegroundService.ACTION_STOP
        }
        startService(intent)
        viewModel.appendLog("⏹ Service stop requested")
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
