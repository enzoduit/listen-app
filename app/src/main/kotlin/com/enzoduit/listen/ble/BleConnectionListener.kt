package com.enzoduit.listen.ble

import android.bluetooth.BluetoothGatt

interface BleConnectionListener {
    fun onGattConnected(address: String, gatt: BluetoothGatt)
    fun onGattDisconnected(address: String, gattHash: Int, status: Int)
    fun onGattServicesDiscovered(address: String)
    fun onMtuChanged(address: String, mtu: Int, status: Int)
}

interface CharacteristicValueListener {
    fun onCharacteristicValue(address: String, serviceUuid: String, characteristicUuid: String, value: ByteArray)
}
