package com.enzoduit.listen.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.enzoduit.listen.ble.PendantBleForegroundService
import com.enzoduit.listen.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var logAdapter: ArrayAdapter<String>? = null
    private var serviceStarted = false

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
            viewModel.appendLog("Permission denied — BLE sync requires Bluetooth permissions")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        }

        binding.btnStart.setOnClickListener { checkPermissionsAndStart() }
        binding.btnStop.setOnClickListener { stopListenService() }

        // Register for service broadcasts
        val filter = IntentFilter(PendantBleForegroundService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        // Auto-start on launch
        checkPermissionsAndStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (needed.isEmpty()) {
            startListenService()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
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
        viewModel.appendLog("Service started")
    }

    private fun stopListenService() {
        serviceStarted = false
        val intent = Intent(this, PendantBleForegroundService::class.java).apply {
            action = PendantBleForegroundService.ACTION_STOP
        }
        startService(intent)
        viewModel.appendLog("Service stop requested")
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
