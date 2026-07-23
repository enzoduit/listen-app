package com.enzoduit.listen.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Pure GATT wrapper for the Limitless pendant.
 * Adapted from OmiBleManager — stripped to bare essentials for Listen app.
 * Critical: uses ADDRESS_TYPE_RANDOM for FD:04:D0:EB:84:88 (random MAC).
 */
@SuppressLint("MissingPermission")
class PendantBleManager(private val application: Application) {

    companion object {
        private const val TAG = "Listen.BleManager"
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @Volatile var connectionListener: BleConnectionListener? = null
    @Volatile var characteristicValueListener: CharacteristicValueListener? = null

    private val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    val mainHandler = Handler(Looper.getMainLooper())

    val connectedGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val writeCompletions = ConcurrentHashMap<String, (Result<Unit>) -> Unit>()

    private val gattQueue: ConcurrentLinkedQueue<Runnable> = ConcurrentLinkedQueue()
    @Volatile private var isProcessingCommand = false

    private val servicesDiscoveredFor = ConcurrentHashMap.newKeySet<String>()

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun isPeripheralConnected(address: String): Boolean {
        val addr = address.uppercase()
        val gatt = connectedGatts[addr] ?: return false
        return bluetoothManager.getConnectionState(gatt.device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
    }

    /**
     * Connect to device by MAC address.
     * CRITICAL: Uses ADDRESS_TYPE_RANDOM on API 34+ for random BLE addresses (FD:xx prefix).
     */
    fun connectGatt(address: String, autoConnect: Boolean): BluetoothGatt? {
        val addr = address.uppercase()
        val adapter = bluetoothAdapter ?: return null

        val device = if (Build.VERSION.SDK_INT >= 34) {
            adapter.getRemoteLeDevice(addr, BluetoothDevice.ADDRESS_TYPE_RANDOM)
        } else {
            adapter.getRemoteDevice(addr)
        }

        val callback = createGattCallback()
        val gatt = device.connectGatt(application, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt != null) {
            connectedGatts[addr] = gatt
            Log.i(TAG, "connectGatt initiated for $addr (autoConnect=$autoConnect)")
        } else {
            Log.e(TAG, "connectGatt returned null for $addr")
        }
        return gatt
    }

    fun disconnectGatt(address: String) {
        connectedGatts[address.uppercase()]?.disconnect()
    }

    fun closeGatt(address: String) {
        val addr = address.uppercase()
        cleanupPeripheral(addr)
        connectedGatts[addr]?.close()
        connectedGatts.remove(addr)
    }

    fun writeCharacteristic(
        address: String,
        serviceUuid: String,
        characteristicUuid: String,
        data: ByteArray,
        completion: (Result<Unit>) -> Unit
    ) {
        val addr = address.uppercase()
        val gatt = connectedGatts[addr]
        val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
        if (gatt == null || characteristic == null) {
            Log.w(TAG, "writeCharacteristic: not found $serviceUuid / $characteristicUuid on $addr")
            completion(Result.failure(Exception("Characteristic not found")))
            return
        }

        val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val key = "$addr:$serviceUuid:$characteristicUuid".lowercase()
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
            writeCompletions[key] = completion
        }

        enqueueCommand {
            @Suppress("deprecation")
            val success = if (Build.VERSION.SDK_INT >= 33) {
                val result = gatt.writeCharacteristic(characteristic, data, writeType)
                result == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = data
                characteristic.writeType = writeType
                gatt.writeCharacteristic(characteristic)
            }

            if (!success) {
                Log.e(TAG, "writeCharacteristic failed for $key")
                writeCompletions.remove(key)?.invoke(Result.failure(Exception("Write rejected")))
                completeCommand()
            } else if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                completeCommand()
            }
        }

        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            completion(Result.success(Unit))
        }
    }

    fun subscribeCharacteristic(address: String, serviceUuid: String, characteristicUuid: String) {
        val addr = address.uppercase()
        val gatt = connectedGatts[addr] ?: run {
            Log.e(TAG, "subscribeCharacteristic: no GATT for $addr")
            return
        }
        val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid) ?: run {
            Log.e(TAG, "subscribeCharacteristic: characteristic $characteristicUuid not found on $addr")
            // Log all available services/characteristics for debugging
            gatt.services?.forEach { svc ->
                Log.i(TAG, "  Service: ${svc.uuid}")
                svc.characteristics?.forEach { chr ->
                    Log.i(TAG, "    Char: ${chr.uuid} props=${chr.properties}")
                    chr.descriptors?.forEach { d -> Log.i(TAG, "      Desc: ${d.uuid}") }
                }
            }
            return
        }

        Log.i(TAG, "subscribeCharacteristic: found char ${characteristic.uuid} props=${characteristic.properties}")
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        Log.i(TAG, "subscribeCharacteristic: CCCD descriptor = ${descriptor?.uuid ?: "NOT FOUND"}")

        // List all descriptors on this characteristic
        characteristic.descriptors?.forEach { d ->
            Log.i(TAG, "  Available descriptor: ${d.uuid}")
        }

        enqueueCommand {
            val notifySet = gatt.setCharacteristicNotification(characteristic, true)
            Log.i(TAG, "setCharacteristicNotification result: $notifySet")
            if (descriptor != null) {
                Log.i(TAG, "Writing CCCD ENABLE_NOTIFICATION_VALUE")
                writeDescriptorCompat(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                // No CCCD — notifications may still work on some peripherals without it
                Log.w(TAG, "No CCCD descriptor found — relying on setCharacteristicNotification alone")
                completeCommand()
            }
        }
    }

    fun cleanupPeripheral(address: String) {
        val addr = address.uppercase()
        servicesDiscoveredFor.remove(addr)
        for (key in writeCompletions.keys().toList().filter { it.startsWith(addr.lowercase()) }) {
            writeCompletions.remove(key)?.invoke(Result.failure(Exception("Disconnected")))
        }
        gattQueue.clear()
        isProcessingCommand = false
    }

    @Synchronized
    fun enqueueCommand(command: Runnable) {
        gattQueue.add(command)
        processNextCommand()
    }

    @Synchronized
    fun completeCommand() {
        gattQueue.poll()
        isProcessingCommand = false
        processNextCommand()
    }

    @Synchronized
    private fun processNextCommand() {
        if (isProcessingCommand) return
        val cmd = gattQueue.peek() ?: return
        isProcessingCommand = true
        mainHandler.post(cmd)
    }

    private fun findCharacteristic(gatt: BluetoothGatt?, serviceUuid: String, characteristicUuid: String): BluetoothGattCharacteristic? {
        val service = gatt?.getService(UUID.fromString(serviceUuid)) ?: return null
        return service.getCharacteristic(UUID.fromString(characteristicUuid))
    }

    @Suppress("deprecation")
    private fun writeDescriptorCompat(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        val success = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
        if (!success) {
            Log.e(TAG, "writeDescriptor failed for ${descriptor.characteristic.uuid}")
            completeCommand()
        }
    }

    private fun createGattCallback() = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address.uppercase()
            Log.i(TAG, "onConnectionStateChange: $address status=$status newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedGatts[address] = gatt
                    connectionListener?.onGattConnected(address, gatt)
                    enqueueCommand {
                        if (!gatt.discoverServices()) {
                            Log.e(TAG, "discoverServices returned false for $address")
                            completeCommand()
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from $address (status=$status)")
                    cleanupPeripheral(address)
                    connectionListener?.onGattDisconnected(address, gatt.hashCode(), status)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address.uppercase()
            if (servicesDiscoveredFor.contains(address)) {
                completeCommand()
                return
            }
            Log.i(TAG, "Services discovered for $address (status=$status) — ${gatt.services.size} services")
            gatt.services.forEach { svc -> Log.i(TAG, "  svc=${svc.uuid}") }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Check if we got the Limitless service — if not, retry discovery after 2s
                val hasLimitless = gatt.services.any {
                    it.uuid.toString().startsWith("632de001", ignoreCase = true)
                }
                if (!hasLimitless && gatt.services.size <= 2) {
                    Log.w(TAG, "Limitless service not found (only ${gatt.services.size} services) — retrying discovery in 2s")
                    completeCommand()
                    mainHandler.postDelayed({
                        Log.i(TAG, "Retrying discoverServices for $address")
                        enqueueCommand {
                            if (!gatt.discoverServices()) {
                                Log.e(TAG, "Retry discoverServices returned false")
                                completeCommand()
                            }
                        }
                    }, 2000)
                    return
                }
                servicesDiscoveredFor.add(address)
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            }
            completeCommand()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connectionListener?.onGattServicesDiscovered(address)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val address = gatt.device.address.uppercase()
            Log.i(TAG, "MTU changed: $address mtu=$mtu status=$status")
            completeCommand()
            connectionListener?.onMtuChanged(address, mtu, status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val address = gatt.device.address.uppercase()
            val serviceUuid = characteristic.service.uuid.toString().lowercase()
            val charUuid = characteristic.uuid.toString().lowercase()
            characteristicValueListener?.onCharacteristicValue(address, serviceUuid, charUuid, value.copyOf())
        }

        @Suppress("deprecation")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val address = gatt.device.address.uppercase()
            val serviceUuid = characteristic.service.uuid.toString().lowercase()
            val charUuid = characteristic.uuid.toString().lowercase()
            val key = "$address:$serviceUuid:$charUuid".lowercase()
            val completion = writeCompletions.remove(key)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                completion?.invoke(Result.success(Unit))
            } else {
                completion?.invoke(Result.failure(Exception("Write failed status=$status")))
            }
            completeCommand()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Descriptor write failed status=$status")
            }
            completeCommand()
        }
    }
}
