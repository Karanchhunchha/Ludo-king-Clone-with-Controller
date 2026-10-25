package com.bletestclient.ble

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * BleClient — runs on the controller phone.
 *
 * Scans ALL nearby BLE devices (any brand, any phone), reports them for the
 * device picker, connects to whichever the user picks, verifies the Ludo
 * GATT service, subscribes to ACK notifications and sends dice commands.
 */
class BleClient(private val context: Context, private val listener: BleClientListener) {

    companion object {
        const val TAG = "BleClient"

        val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val CMD_CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789001")
        val ACK_CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789002")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var cmdCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false
    private var isConnecting = false
    private val handler = Handler(Looper.getMainLooper())

    private val connectTimeout = Runnable {
        if (isConnecting) {
            listener.onError("Connection timed out — is the game on the board screen?")
            cleanup()
            isConnecting = false
            listener.onDisconnected("Connection timeout")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val isLudoHost = record?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            val name = result.device.name ?: record?.deviceName ?: ""
            listener.onDeviceDiscovered(name, result.device.address, result.rssi, isLudoHost)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            isScanning = false
            listener.onError("Scan failed (error code $errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isConnecting = false
                    handler.removeCallbacks(connectTimeout)
                    Log.d(TAG, "Connected to GATT server. Discovering services...")
                    listener.onConnected()
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isConnecting = false
                    handler.removeCallbacks(connectTimeout)
                    Log.d(TAG, "Disconnected from GATT server.")
                    cleanup()
                    listener.onDisconnected("Disconnected from host")
                }
            } else {
                isConnecting = false
                handler.removeCallbacks(connectTimeout)
                Log.e(TAG, "GATT connection failure with status: $status")
                cleanup()
                listener.onDisconnected("Connection error ($status)")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Service discovery failed ($status)")
                gatt.disconnect()
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                // Connected to a non-Ludo device (earbuds, watch, other phone…) — bail out cleanly.
                listener.onError("Not a Ludo game host — pick the phone running the game")
                gatt.disconnect()
                return
            }
            cmdCharacteristic = service.getCharacteristic(CMD_CHAR_UUID)
            if (cmdCharacteristic == null) {
                listener.onError("Ludo service found but dice command is missing")
                gatt.disconnect()
                return
            }
            val ackChar = service.getCharacteristic(ACK_CHAR_UUID)
            if (ackChar != null) {
                enableNotifications(gatt, ackChar)
            } else {
                // Older game build without ACK characteristic: commands still work.
                listener.onReadyForCommands()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == CMD_CHAR_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val sentStr = String(characteristic.value ?: ByteArray(0), Charsets.UTF_8)
                    Log.d(TAG, "Command sent successfully: $sentStr")
                    listener.onCommandSent(sentStr)
                } else {
                    Log.e(TAG, "Command write failed with status: $status")
                    listener.onError("Command write failed ($status)")
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == ACK_CHAR_UUID) {
                val response = String(characteristic.value ?: ByteArray(0), Charsets.UTF_8)
                Log.d(TAG, "Notification received: $response")
                listener.onResponseReceived(response)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Notifications successfully subscribed!")
                    listener.onReadyForCommands()
                } else {
                    listener.onError("ACK subscription failed ($status)")
                    gatt.disconnect()
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onRssiUpdated(rssi)
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        } else {
            listener.onError("CCCD descriptor not found for ACK characteristic")
        }
    }

    fun startScan(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onError("Bluetooth LE scanner not available")
            return
        }
        if (isScanning) {
            stopScan()
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        listener.onScanStarted()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            isScanning = false
            listener.onError("Failed to start scan: ${e.message}")
            return
        }

        // Stop scanning automatically after 25s; the picker keeps whatever was found.
        handler.postDelayed({
            if (isScanning) stopScan()
        }, 25000)
    }

    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        listener.onScanStopped()
    }

    fun connectDevice(address: String) {
        // Never stack connectGatt calls: stop any scan, kill the timeout, and
        // close a previous (possibly stuck) GATT before dialing again — leaked
        // handles jam the phone's BLE stack until Bluetooth is toggled.
        if (isScanning) stopScan()
        handler.removeCallbacks(connectTimeout)
        if (isConnecting) {
            cleanup()
            isConnecting = false
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            listener.onError("Bluetooth not available")
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (device == null) {
            listener.onError("Device not found: $address")
            return
        }
        cleanup()
        listener.onConnecting(address)
        isConnecting = true
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed(connectTimeout, 15000)
    }

    fun sendCommand(cmd: String): Boolean {
        val gatt = bluetoothGatt ?: run {
            listener.onError("Not connected to host")
            return false
        }
        val char = cmdCharacteristic ?: run {
            listener.onError("CMD characteristic not ready")
            return false
        }

        char.value = cmd.toByteArray(Charsets.UTF_8)
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return gatt.writeCharacteristic(char)
    }

    fun readRssi() {
        bluetoothGatt?.readRemoteRssi()
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

    private fun cleanup() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        cmdCharacteristic = null
    }
}

interface BleClientListener {
    fun onScanStarted()
    fun onScanStopped()
    fun onDeviceDiscovered(name: String, address: String, rssi: Int, isLudoHost: Boolean)
    fun onConnecting(address: String)
    fun onConnected()
    fun onReadyForCommands()
    fun onCommandSent(command: String)
    fun onResponseReceived(response: String)
    fun onRssiUpdated(rssi: Int)
    fun onDisconnected(reason: String)
    fun onError(message: String)
}
