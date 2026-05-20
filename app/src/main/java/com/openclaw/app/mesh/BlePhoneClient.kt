package com.openclaw.app.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.util.UUID

/**
 * BlePhoneClient — BLE GATT client that connects glasses to a phone companion app.
 *
 * The phone runs a companion app that advertises a GATT server with the MeshTalk
 * BLE service. This client scans, connects, and exchanges audio + control data
 * over four GATT characteristics:
 *
 *   ea01 — AudioTX (glasses→phone): Write, Opus frames [seq, flags, opus...]
 *   ea02 — AudioRX (phone→glasses): Notify, Opus frames from mesh
 *   ea03 — Control: Write+Notify, JSON commands (channel, mute, VOX)
 *   ea04 — Status:  Read+Notify, JSON status from phone
 *
 * Auto-reconnects with exponential backoff on disconnect.
 */
@SuppressLint("MissingPermission")
class BlePhoneClient(
    private val context: Context,
    private val deviceId: String
) {
    companion object {
        private const val TAG = "BlePhoneClient"

        // MeshTalk BLE Service UUID
        val SERVICE_UUID: UUID       = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273ea00")
        val AUDIO_TX_UUID: UUID      = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273ea01")
        val AUDIO_RX_UUID: UUID      = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273ea02")
        val CONTROL_UUID: UUID       = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273ea03")
        val STATUS_UUID: UUID        = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273ea04")

        // Standard Client Characteristic Configuration Descriptor
        val CCCD_UUID: UUID          = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TARGET_MTU = 512
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val SCAN_TIMEOUT_MS = 30_000L

        // Audio frame flags
        const val FLAG_SPEECH: Byte = 0x01
    }

    // ── Connection state ──────────────────────────────────────────────

    enum class BleState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        DISCOVERING_SERVICES,
        ENABLING_NOTIFICATIONS,
        CONNECTED,
        RECONNECTING
    }

    @Volatile
    var state: BleState = BleState.DISCONNECTED
        private set

    // ── Callbacks ─────────────────────────────────────────────────────

    var onAudioReceived: ((ByteArray) -> Unit)? = null       // raw Opus frame (no header)
    var onStatusUpdate: ((JSONObject) -> Unit)? = null
    var onControlReceived: ((JSONObject) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onStateChanged: ((BleState) -> Unit)? = null

    // ── Internal state ───────────────────────────────────────────────

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val handlerThread = HandlerThread("BlePhone").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var audioTxChar: BluetoothGattCharacteristic? = null
    private var audioRxChar: BluetoothGattCharacteristic? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null

    private var backoffMs = MIN_BACKOFF_MS
    private var consecutiveFailures = 0
    private var started = false
    private var currentMtu = 23  // default BLE MTU
    private var pendingNotifications = mutableListOf<BluetoothGattCharacteristic>()
    private var scanTimeoutRunnable: Runnable? = null

    // Sequence number for audio TX
    private var audioSeq: Byte = 0

    // ── Public API ───────────────────────────────────────────────────

    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "Starting BLE phone client (device=$deviceId)")
        startScan()
    }

    fun stop() {
        started = false
        stopScan()
        disconnect()
        transition(BleState.DISCONNECTED)
        Log.i(TAG, "BLE phone client stopped")
    }

    /**
     * Send an Opus audio frame to the phone via BLE.
     * Format: [seq, flags, opus_data...]
     */
    fun sendAudio(opusFrame: ByteArray, isSpeech: Boolean) {
        val char = audioTxChar ?: return
        val g = gatt ?: return
        if (state != BleState.CONNECTED) return

        val flags: Byte = if (isSpeech) FLAG_SPEECH else 0
        val packet = ByteArray(2 + opusFrame.size)
        packet[0] = audioSeq++
        packet[1] = flags
        System.arraycopy(opusFrame, 0, packet, 2, opusFrame.size)

        // Ensure packet fits in MTU (MTU - 3 for ATT overhead)
        val maxPayload = currentMtu - 3
        if (packet.size > maxPayload) {
            Log.w(TAG, "Audio packet ${packet.size} exceeds MTU payload $maxPayload, truncating")
        }

        char.value = packet
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        try {
            g.writeCharacteristic(char)
        } catch (e: Exception) {
            Log.w(TAG, "Audio TX failed: ${e.message}")
        }
    }

    /**
     * Send a JSON control message to the phone.
     * Used for: channel switch, mute toggle, VOX state, etc.
     */
    fun sendControl(json: JSONObject) {
        val char = controlChar ?: return
        val g = gatt ?: return
        if (state != BleState.CONNECTED) return

        char.value = json.toString().toByteArray(Charsets.UTF_8)
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            g.writeCharacteristic(char)
        } catch (e: Exception) {
            Log.w(TAG, "Control TX failed: ${e.message}")
        }
    }

    /**
     * Send a channel switch command to the phone companion.
     */
    fun sendChannelSwitch(channelId: Int, channelName: String) {
        val json = JSONObject().apply {
            put("cmd", "channel_switch")
            put("channel_id", channelId)
            put("channel_name", channelName)
            put("device_id", deviceId)
        }
        sendControl(json)
    }

    /**
     * Send mute state to the phone companion.
     */
    fun sendMuteState(muted: Boolean) {
        val json = JSONObject().apply {
            put("cmd", "mute")
            put("muted", muted)
            put("device_id", deviceId)
        }
        sendControl(json)
    }

    /**
     * Send VOX state to the phone companion.
     */
    fun sendVoxState(speaking: Boolean) {
        val json = JSONObject().apply {
            put("cmd", "vox")
            put("speaking", speaking)
            put("device_id", deviceId)
        }
        sendControl(json)
    }

    val isConnected: Boolean get() = state == BleState.CONNECTED

    // ── BLE Scanning ─────────────────────────────────────────────────

    private fun startScan() {
        if (!started) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or disabled")
            scheduleReconnect("bluetooth unavailable")
            return
        }

        transition(BleState.SCANNING)
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE scanner not available")
            scheduleReconnect("scanner unavailable")
            return
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        Log.i(TAG, "Starting BLE scan for MeshTalk service...")
        try {
            scanner?.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Scan start failed: ${e.message}")
            scheduleReconnect("scan failed: ${e.message}")
            return
        }

        // Timeout scan after SCAN_TIMEOUT_MS
        scanTimeoutRunnable = Runnable {
            if (state == BleState.SCANNING) {
                Log.w(TAG, "Scan timeout — no phone found, retrying")
                stopScan()
                scheduleReconnect("scan timeout")
            }
        }
        handler.postDelayed(scanTimeoutRunnable!!, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Stop scan error: ${e.message}")
        }
        scanner = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (state != BleState.SCANNING) return
            val device = result.device
            val rssi = result.rssi
            Log.i(TAG, "Found phone: ${device.address} (RSSI=$rssi)")
            stopScan()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
            scheduleReconnect("scan failed: code $errorCode")
        }
    }

    // ── GATT Connection ──────────────────────────────────────────────

    private fun connectToDevice(device: BluetoothDevice) {
        if (!started) return
        transition(BleState.CONNECTING)
        Log.i(TAG, "Connecting to ${device.address}...")

        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M, handler)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, requesting MTU $TARGET_MTU")
                    transition(BleState.DISCOVERING_SERVICES)
                    resetBackoff()
                    // Request higher MTU first, then discover services
                    g.requestMtu(TARGET_MTU)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    cleanupGatt()
                    onDisconnected?.invoke()
                    if (started) {
                        scheduleReconnect("GATT disconnected, status=$status")
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
                Log.i(TAG, "MTU changed to $mtu")
            } else {
                Log.w(TAG, "MTU request failed (status=$status), using default")
            }
            // Now discover services
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                g.disconnect()
                return
            }

            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "MeshTalk service not found on phone")
                g.disconnect()
                return
            }

            audioTxChar = service.getCharacteristic(AUDIO_TX_UUID)
            audioRxChar = service.getCharacteristic(AUDIO_RX_UUID)
            controlChar = service.getCharacteristic(CONTROL_UUID)
            statusChar  = service.getCharacteristic(STATUS_UUID)

            Log.i(TAG, "Service discovered: TX=${audioTxChar != null}, RX=${audioRxChar != null}, " +
                    "CTL=${controlChar != null}, STS=${statusChar != null}")

            if (audioTxChar == null || audioRxChar == null) {
                Log.e(TAG, "Missing required characteristics")
                g.disconnect()
                return
            }

            // Enable notifications on AudioRX and Status
            transition(BleState.ENABLING_NOTIFICATIONS)
            pendingNotifications.clear()
            audioRxChar?.let { pendingNotifications.add(it) }
            statusChar?.let { pendingNotifications.add(it) }
            controlChar?.let {
                // Also enable control notifications if the char supports it
                if (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    pendingNotifications.add(it)
                }
            }
            enableNextNotification(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Notification enabled for ${descriptor.characteristic.uuid}")
            } else {
                Log.w(TAG, "Descriptor write failed for ${descriptor.characteristic.uuid}, status=$status")
            }
            // Enable next pending notification
            enableNextNotification(g)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            when (characteristic.uuid) {
                AUDIO_RX_UUID -> {
                    // Audio from phone: [seq, flags, opus_data...]
                    if (data.size > 2) {
                        val opusFrame = data.copyOfRange(2, data.size)
                        onAudioReceived?.invoke(opusFrame)
                    }
                }

                CONTROL_UUID -> {
                    try {
                        val json = JSONObject(String(data, Charsets.UTF_8))
                        onControlReceived?.invoke(json)
                    } catch (e: Exception) {
                        Log.w(TAG, "Bad control JSON: ${e.message}")
                    }
                }

                STATUS_UUID -> {
                    try {
                        val json = JSONObject(String(data, Charsets.UTF_8))
                        onStatusUpdate?.invoke(json)
                    } catch (e: Exception) {
                        Log.w(TAG, "Bad status JSON: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Enable notifications one at a time (BLE requires sequential descriptor writes).
     */
    private fun enableNextNotification(g: BluetoothGatt) {
        if (pendingNotifications.isEmpty()) {
            // All notifications enabled — we're fully connected
            Log.i(TAG, "All notifications enabled — BLE connected!")
            transition(BleState.CONNECTED)
            onConnected?.invoke()

            // Send initial handshake
            val hello = JSONObject().apply {
                put("cmd", "hello")
                put("device_id", deviceId)
                put("device_type", "glasses")
                put("protocol_version", 1)
            }
            sendControl(hello)
            return
        }

        val char = pendingNotifications.removeAt(0)
        g.setCharacteristicNotification(char, true)

        val descriptor = char.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(descriptor)
        } else {
            Log.w(TAG, "No CCCD for ${char.uuid}, skipping")
            // Try next
            enableNextNotification(g)
        }
    }

    // ── Reconnect with backoff ───────────────────────────────────────

    private fun scheduleReconnect(reason: String) {
        if (!started) return
        consecutiveFailures++
        transition(BleState.RECONNECTING)

        if (consecutiveFailures > 20) {
            backoffMs = MAX_BACKOFF_MS
        }

        Log.w(TAG, "Reconnect in ${backoffMs}ms (attempt $consecutiveFailures, reason: $reason)")

        handler.postDelayed({
            if (started && (state == BleState.RECONNECTING || state == BleState.DISCONNECTED)) {
                startScan()
            }
        }, backoffMs)

        // Exponential backoff: 1s → 2s → 4s → 8s → 16s → 30s cap
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun resetBackoff() {
        backoffMs = MIN_BACKOFF_MS
        consecutiveFailures = 0
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    private fun disconnect() {
        cleanupGatt()
    }

    private fun cleanupGatt() {
        audioTxChar = null
        audioRxChar = null
        controlChar = null
        statusChar = null
        pendingNotifications.clear()

        try {
            gatt?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "GATT disconnect error: ${e.message}")
        }
        try {
            gatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "GATT close error: ${e.message}")
        }
        gatt = null
    }

    // ── State transitions ────────────────────────────────────────────

    private fun transition(newState: BleState) {
        if (state == newState) return
        val old = state
        state = newState
        Log.i(TAG, "BLE: $old → $newState")
        try {
            onStateChanged?.invoke(newState)
        } catch (e: Exception) {
            Log.e(TAG, "State callback error: ${e.message}")
        }
    }
}
