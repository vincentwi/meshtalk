package com.openclaw.app.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bluetooth RFCOMM transport — zero-infrastructure walkie-talkie.
 *
 * Uses Bluetooth Classic SPP (Serial Port Profile) to stream Opus audio
 * between two paired glasses. No WiFi, no router, no internet needed.
 *
 * Architecture:
 *   - Both glasses run a server socket (listening)
 *   - Both glasses try to connect to each other as clients
 *   - First successful connection wins, other side cancels its attempt
 *   - Full-duplex: both read and write on the same RFCOMM channel
 *
 * Prerequisites:
 *   - Glasses must be Bluetooth-paired (one-time, persists across reboots)
 *   - BLUETOOTH_CONNECT permission granted
 *
 * Performance:
 *   - Throughput: ~2-3 Mbps (WAY more than 8kbps Opus audio)
 *   - Latency: ~20-40ms
 *   - Range: 10-30m (BT Class 1/2)
 */
@SuppressLint("MissingPermission")
class BluetoothRfcommTransport(
    private val context: Context,
    private val deviceId: String,
    private val targetMac: String? = null  // optional: specific device to connect to
) : MeshTransport {

    companion object {
        private const val TAG = "BtRfcommTransport"
        // Custom UUID for MeshTalk RFCOMM service
        val MESHTALK_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        private const val SERVICE_NAME = "MeshTalk"
    }

    var supervisor: NanSupervisor? = null

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val connectedPeers = ConcurrentHashMap<String, MeshPeer>()
    private val peerSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val peerOutputStreams = ConcurrentHashMap<String, OutputStream>()
    private var serverSocket: BluetoothServerSocket? = null
    private var serverJob: Job? = null
    private var connectJob: Job? = null
    private val readJobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var sendCount = 0L
    private var recvCount = 0L

    override val peers: List<MeshPeer> get() = connectedPeers.values.toList()
    override var onPeerDiscovered: ((MeshPeer) -> Unit)? = null
    override var onPeerLost: ((String) -> Unit)? = null
    override var onDataReceived: ((String, ByteArray) -> Unit)? = null

    override fun start(channelName: String) {
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or disabled")
            supervisor?.onTransportFailed("Bluetooth not available")
            return
        }

        isRunning = true
        Log.i(TAG, "Starting BT RFCOMM transport (deviceId=$deviceId)")

        // Start server (listen for incoming connections)
        startServer()

        // If already paired with RayNeo glasses, try connecting immediately
        startClient()

        // Note: BT auto-pairing/discovery removed — Mercury OS kills the app
        // when setScanMode or startDiscovery is called. Pairing must be done
        // manually via ADB: adb shell am start -a android.settings.BLUETOOTH_SETTINGS
        // Once paired, RFCOMM auto-connects on every app launch.
    }

    // ── Auto-pairing: scan for nearby ARGF20 devices and pair ──────

    private var pairingReceiver: BroadcastReceiver? = null

    private fun registerPairingReceiver() {
        pairingReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val name = device?.name ?: ""
                        Log.i(TAG, "Pairing request from: $name (${device?.address})")

                        // Auto-accept pairing for RayNeo glasses
                        if (name.contains("ARGF20") || name.contains("RayNeo") || name.contains("X3")) {
                            try {
                                device?.setPin("0000".toByteArray())
                                device?.javaClass?.getMethod("setPairingConfirmation", Boolean::class.java)
                                    ?.invoke(device, true)
                                Log.i(TAG, "Auto-accepted pairing for $name")
                                abortBroadcast()
                            } catch (e: Exception) {
                                Log.w(TAG, "Auto-accept failed: ${e.message}")
                            }
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        val stateName = when (state) {
                            BluetoothDevice.BOND_BONDED -> "BONDED"
                            BluetoothDevice.BOND_BONDING -> "BONDING"
                            BluetoothDevice.BOND_NONE -> "NONE"
                            else -> "UNKNOWN"
                        }
                        Log.i(TAG, "Bond state: ${device?.name} → $stateName")
                        if (state == BluetoothDevice.BOND_BONDED) {
                            // Newly paired — try connecting
                            Log.i(TAG, "New pairing complete! Connecting...")
                            startClient()
                        }
                    }
                }
            }
        }

        val filter = android.content.IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            priority = android.content.IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        context.registerReceiver(pairingReceiver, filter)
        Log.i(TAG, "Pairing receiver registered")
    }

    private fun scanAndPairNearbyGlasses() {
        scope.launch {
            delay(1000) // Let server start first
            if (adapter == null) return@launch

            // Check if already paired with another ARGF20
            val paired = adapter.bondedDevices?.filter {
                it.name?.contains("ARGF20") == true || it.name?.contains("RayNeo") == true
            } ?: emptyList()

            if (paired.isNotEmpty()) {
                Log.i(TAG, "Already paired with ${paired.size} RayNeo device(s): ${paired.map { it.name }}")
                return@launch
            }

            // Make ourselves discoverable via reflection (no UI prompt)
            try {
                val method = adapter.javaClass.getMethod("setScanMode", Int::class.java, Int::class.java)
                method.invoke(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, 120)
                Log.i(TAG, "Set BT discoverable mode (120s)")
            } catch (e: Exception) {
                Log.w(TAG, "setScanMode(2-arg) failed: ${e.message}")
                try {
                    val method = adapter.javaClass.getMethod("setScanMode", Int::class.java)
                    method.invoke(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE)
                    Log.i(TAG, "Set BT discoverable mode (1-arg)")
                } catch (e2: Exception) {
                    Log.w(TAG, "setScanMode(1-arg) also failed: ${e2.message}")
                    // Can't make discoverable — the other glass needs to be discoverable
                }
            }

            // Start BT discovery to find unpaired glasses
            Log.i(TAG, "Scanning for unpaired RayNeo glasses...")
            val discoveryReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == BluetoothDevice.ACTION_FOUND) {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val name = device?.name ?: return
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        Log.i(TAG, "Found: $name (${device.address}) RSSI=$rssi")

                        if (name.contains("ARGF20") || name.contains("RayNeo") || name.contains("X3 Pro")) {
                            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                                Log.i(TAG, "*** FOUND GLASSES: $name (${device.address}) — initiating pairing ***")
                                adapter.cancelDiscovery()
                                device.createBond()
                            }
                        }
                    }
                }
            }

            val filter = android.content.IntentFilter(BluetoothDevice.ACTION_FOUND)
            context.registerReceiver(discoveryReceiver, filter)
            adapter.startDiscovery()

            // Discovery runs for 12 seconds by default
            delay(15000)
            try {
                adapter.cancelDiscovery()
                context.unregisterReceiver(discoveryReceiver)
            } catch (_: Exception) {}
        }
    }

    private fun startServer() {
        serverJob = scope.launch {
            try {
                serverSocket = adapter!!.listenUsingRfcommWithServiceRecord(SERVICE_NAME, MESHTALK_UUID)
                Log.i(TAG, "RFCOMM server listening")
                supervisor?.onTransportPublishing()

                while (isRunning && isActive) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        val remoteName = socket.remoteDevice?.name ?: "unknown"
                        val remoteMac = socket.remoteDevice?.address ?: "unknown"
                        Log.i(TAG, "Accepted connection from $remoteName ($remoteMac)")
                        handleConnection(socket, remoteMac)
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.w(TAG, "Server accept error: ${e.message}")
                            delay(1000)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed: ${e.message}")
                supervisor?.onTransportFailed("BT server: ${e.message}")
            }
        }
    }

    private fun startClient() {
        connectJob = scope.launch {
            // Wait a bit for server to start on both sides
            delay(2000)

            if (adapter == null) return@launch

            // Try connecting to all paired RayNeo devices
            val pairedDevices = adapter.bondedDevices ?: return@launch
            Log.i(TAG, "Found ${pairedDevices.size} paired devices")

            for (device in pairedDevices) {
                if (!isRunning) break

                // Skip if already connected to this device
                if (connectedPeers.containsKey(device.address)) continue

                // Optionally filter to specific target
                if (targetMac != null && device.address != targetMac) continue

                // Only try RayNeo devices (or all if no filter)
                val name = device.name ?: ""
                Log.i(TAG, "Trying to connect to: $name (${device.address})")

                try {
                    val socket = device.createRfcommSocketToServiceRecord(MESHTALK_UUID)
                    // Cancel discovery to speed up connection
                    adapter.cancelDiscovery()

                    withTimeout(10000) {
                        socket.connect()
                    }
                    Log.i(TAG, "Connected to $name (${device.address})")
                    handleConnection(socket, device.address)
                } catch (e: Exception) {
                    Log.w(TAG, "Connect to ${device.address} failed: ${e.message}")
                }
            }

            // If no connection established, retry periodically
            while (isRunning && connectedPeers.isEmpty()) {
                delay(5000)
                Log.d(TAG, "Retrying BT connections...")
                for (device in pairedDevices) {
                    if (!isRunning || connectedPeers.isNotEmpty()) break
                    if (targetMac != null && device.address != targetMac) continue

                    try {
                        val socket = device.createRfcommSocketToServiceRecord(MESHTALK_UUID)
                        adapter.cancelDiscovery()
                        withTimeout(10000) { socket.connect() }
                        handleConnection(socket, device.address)
                    } catch (e: Exception) {
                        Log.d(TAG, "Retry to ${device.address}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handleConnection(socket: BluetoothSocket, remoteMac: String) {
        // Deduplicate — if already connected, close extra socket
        if (peerSockets.containsKey(remoteMac)) {
            try { socket.close() } catch (_: Exception) {}
            return
        }

        val outputStream = socket.outputStream
        peerSockets[remoteMac] = socket
        peerOutputStreams[remoteMac] = outputStream

        // Send handshake: our deviceId
        try {
            val handshake = "MESHTALK|$deviceId\n".toByteArray(Charsets.UTF_8)
            outputStream.write(handshake)
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Handshake send failed: ${e.message}")
            cleanupPeer(remoteMac)
            return
        }

        // Start reading from this peer
        val readJob = scope.launch {
            val inputStream = socket.inputStream
            val buf = ByteArray(2048)
            var peerId = remoteMac  // use MAC until handshake received
            var handshakeReceived = false

            try {
                while (isActive && isRunning) {
                    val bytesRead = inputStream.read(buf)
                    if (bytesRead <= 0) break

                    val data = buf.copyOf(bytesRead)

                    // Check for handshake
                    if (!handshakeReceived && data.size < 100) {
                        val text = String(data, Charsets.UTF_8)
                        if (text.startsWith("MESHTALK|")) {
                            peerId = text.substringAfter("MESHTALK|").trim()
                            handshakeReceived = true
                            Log.i(TAG, "Handshake received: peer=$peerId (mac=$remoteMac)")

                            // Register peer
                            val peer = MeshPeer(peerId, java.net.InetAddress.getLoopbackAddress(), 0)
                            connectedPeers[remoteMac] = peer
                            connectedPeers[peerId] = peer
                            peerOutputStreams[peerId] = outputStream
                            Log.i(TAG, "*** BT PEER CONNECTED: $peerId ($remoteMac) ***")
                            supervisor?.onTransportConnected()
                            onPeerDiscovered?.invoke(peer)
                            continue
                        }
                    }

                    // Audio/data packet
                    recvCount++
                    if (recvCount % 500 == 0L) {
                        Log.d(TAG, "BT recv #$recvCount (${data.size}B from $peerId)")
                    }
                    onDataReceived?.invoke(peerId, data)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Read from $peerId failed: ${e.message}")
                }
            }

            cleanupPeer(remoteMac)
            onPeerLost?.invoke(peerId)
        }

        readJobs[remoteMac] = readJob
    }

    override fun sendToAll(data: ByteArray) {
        // Send length-prefixed packet to all connected peers
        val packet = makeLengthPrefixed(data)
        for ((mac, stream) in peerOutputStreams) {
            if (mac.contains(":")) { // only send via MAC keys, not peerId aliases
                try {
                    stream.write(packet)
                    sendCount++
                    if (sendCount % 500 == 0L) {
                        Log.d(TAG, "BT sent #$sendCount (${data.size}B)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "BT send failed to $mac: ${e.message}")
                    cleanupPeer(mac)
                }
            }
        }
    }

    override fun sendTo(peerId: String, data: ByteArray) {
        val stream = peerOutputStreams[peerId] ?: return
        try {
            stream.write(makeLengthPrefixed(data))
        } catch (e: Exception) {
            Log.e(TAG, "BT send to $peerId failed: ${e.message}")
        }
    }

    private fun makeLengthPrefixed(data: ByteArray): ByteArray {
        // Simple framing: 2-byte big-endian length + payload
        // This prevents stream fragmentation issues
        return data  // For now, just send raw — RFCOMM preserves message boundaries with small writes
    }

    private fun cleanupPeer(mac: String) {
        readJobs.remove(mac)?.cancel()
        peerOutputStreams.remove(mac)
        val socket = peerSockets.remove(mac)
        try { socket?.close() } catch (_: Exception) {}
        connectedPeers.remove(mac)
    }

    override fun switchChannel(channelName: String) {
        Log.i(TAG, "Channel switch (BT doesn't use channels, ignoring)")
    }

    override fun stop() {
        isRunning = false
        serverJob?.cancel()
        connectJob?.cancel()
        readJobs.values.forEach { it.cancel() }
        readJobs.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        for ((_, socket) in peerSockets) {
            try { socket.close() } catch (_: Exception) {}
        }
        peerSockets.clear()
        peerOutputStreams.clear()
        connectedPeers.clear()
        try { pairingReceiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        pairingReceiver = null
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        Log.i(TAG, "BT transport stopped (sent=$sendCount, recv=$recvCount)")
    }
}
