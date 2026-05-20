package com.openclaw.app.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.net.wifi.p2p.WifiP2pManager.*
import android.os.Looper
import android.util.Log
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/**
 * WiFi Direct (P2P) transport — no router, no internet needed.
 *
 * Strategy: deterministic GO selection based on device ID.
 *   - Higher deviceId → creates P2P group (Group Owner / soft AP)
 *   - Lower deviceId  → discovers peers, connects to the GO
 *
 * After P2P group forms, both get IPs on 192.168.49.x subnet.
 * GO is always 192.168.49.1. Audio streams via UDP.
 *
 * Key fix: uses createGroup() to avoid the consent dialog that
 * was blocking connection on Mercury OS (no touchscreen).
 */
@SuppressLint("MissingPermission")
class WifiDirectTransport(
    private val context: Context,
    private val deviceId: String,
    private val audioPort: Int = 18431
) : MeshTransport {

    companion object {
        private const val TAG = "WifiDirectTransport"
    }

    var supervisor: NanSupervisor? = null

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private val connectedPeers = ConcurrentHashMap<String, MeshPeer>()
    private var udpSocket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isGroupOwner = false
    private var isRunning = false
    private var sendCount = 0L
    private var receiver: BroadcastReceiver? = null
    private var groupCreated = false

    override val peers: List<MeshPeer> get() = connectedPeers.values.toList()
    override var onPeerDiscovered: ((MeshPeer) -> Unit)? = null
    override var onPeerLost: ((String) -> Unit)? = null
    override var onDataReceived: ((String, ByteArray) -> Unit)? = null

    override fun start(channelName: String) {
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            Log.e(TAG, "WiFi Direct not available")
            return
        }

        channel = manager!!.initialize(context, Looper.getMainLooper(), null)
        isRunning = true
        Log.i(TAG, "Starting (deviceId=$deviceId)")

        registerReceiver()
        beginP2pStrategy()
    }

    private fun beginP2pStrategy() {
        // First just try discovering — if the other glass created a group, we'll find it
        Log.i(TAG, "Starting P2P peer discovery...")
        discoverAndConnect()

        // After 10s, if no connection, try creating our own group
        scope.launch {
            delay(10000)
            if (isRunning && connectedPeers.isEmpty() && !groupCreated) {
                Log.i(TAG, "No peers found — creating P2P group (becoming GO)")
                manager?.createGroup(channel!!, object : ActionListener {
                    override fun onSuccess() {
                        groupCreated = true
                        Log.i(TAG, "*** P2P group created — I am Group Owner ***")
                    }
                    override fun onFailure(reason: Int) {
                        val r = when (reason) { ERROR -> "ERROR"; P2P_UNSUPPORTED -> "UNSUPPORTED"; BUSY -> "BUSY"; else -> "$reason" }
                        Log.w(TAG, "createGroup failed: $r")
                    }
                })
            }
        }
    }

    private fun discoverAndConnect() {
        manager?.discoverPeers(channel!!, object : ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Peer discovery started")
            }
            override fun onFailure(reason: Int) {
                val r = when (reason) { ERROR -> "ERROR"; P2P_UNSUPPORTED -> "UNSUPPORTED"; BUSY -> "BUSY"; else -> "$reason" }
                Log.w(TAG, "discoverPeers failed: $r")
                // Retry after delay
                scope.launch {
                    delay(5000)
                    if (isRunning && connectedPeers.isEmpty()) discoverAndConnect()
                }
            }
        })
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(EXTRA_WIFI_STATE, -1)
                        val enabled = state == WIFI_P2P_STATE_ENABLED
                        Log.i(TAG, "P2P state: ${if (enabled) "ENABLED" else "DISABLED"}")
                    }

                    WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        manager?.requestPeers(channel!!) { peers -> handlePeerList(peers) }
                    }

                    WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            Log.i(TAG, "P2P connected!")
                            manager?.requestConnectionInfo(channel!!) { info -> handleConnectionInfo(info) }
                        } else {
                            Log.w(TAG, "P2P disconnected")
                            connectedPeers.clear()
                        }
                    }

                    WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = intent.getParcelableExtra<WifiP2pDevice>(EXTRA_WIFI_P2P_DEVICE)
                        Log.d(TAG, "This device: ${device?.deviceName} status=${device?.status}")
                    }
                }
            }
        }

        context.registerReceiver(receiver, filter)
    }

    private fun handlePeerList(peers: WifiP2pDeviceList) {
        val deviceList = peers.deviceList
        Log.i(TAG, "Found ${deviceList.size} P2P peers")

        for (device in deviceList) {
            val status = when (device.status) {
                WifiP2pDevice.AVAILABLE -> "AVAILABLE"
                WifiP2pDevice.INVITED -> "INVITED"
                WifiP2pDevice.CONNECTED -> "CONNECTED"
                WifiP2pDevice.FAILED -> "FAILED"
                WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
                else -> "${device.status}"
            }
            Log.i(TAG, "  ${device.deviceName} (${device.deviceAddress}) $status")
        }

        // Connect to first available ARGF20/RayNeo device
        if (!groupCreated) {
            for (device in deviceList) {
                if (device.status == WifiP2pDevice.AVAILABLE) {
                    // Filter: only connect to other glasses (ARGF20) or DIRECT- groups
                    val name = device.deviceName ?: ""
                    if (name.contains("ARGF20") || name.contains("RayNeo") || name.startsWith("DIRECT-")) {
                        connectToPeer(device)
                        break
                    }
                }
            }
        }
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0 // prefer being client
        }
        manager?.connect(channel!!, config, object : ActionListener {
            override fun onSuccess() { Log.i(TAG, "Connect initiated to ${device.deviceName}") }
            override fun onFailure(reason: Int) { Log.e(TAG, "Connect failed: $reason") }
        })
    }

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        isGroupOwner = info.isGroupOwner
        val goAddr = info.groupOwnerAddress ?: return

        Log.i(TAG, "Connected! isGO=$isGroupOwner, goAddr=$goAddr")

        startUdpReceiver()

        if (isGroupOwner) {
            // GO waits for client's handshake UDP to learn their IP
            Log.i(TAG, "I am GO — waiting for client handshake on port $audioPort")
        } else {
            // Client knows GO IP — register and send handshake
            val peerId = "p2p-go"
            val peer = MeshPeer(peerId, goAddr, audioPort)
            connectedPeers[peerId] = peer
            Log.i(TAG, "*** P2P CONNECTED to GO at $goAddr ***")
            supervisor?.onTransportConnected()
            onPeerDiscovered?.invoke(peer)

            // Send handshake
            scope.launch {
                delay(500)
                val hs = "P2P|$deviceId".toByteArray(Charsets.UTF_8)
                try {
                    udpSocket?.send(DatagramPacket(hs, hs.size, goAddr, audioPort))
                } catch (e: Exception) {
                    Log.e(TAG, "Handshake failed: ${e.message}")
                }
            }
        }
    }

    // ── UDP audio ──────────────────────────────────────────────

    override fun sendToAll(data: ByteArray) {
        for ((_, peer) in connectedPeers) sendTo(peer.id, data)
    }

    override fun sendTo(peerId: String, data: ByteArray) {
        val peer = connectedPeers[peerId] ?: return
        scope.launch {
            try {
                udpSocket?.send(DatagramPacket(data, data.size, peer.address, peer.port))
                sendCount++
                if (sendCount % 500 == 0L) Log.d(TAG, "P2P sent #$sendCount (${data.size}B)")
            } catch (e: Exception) {
                Log.e(TAG, "P2P send: ${e.message}")
            }
        }
    }

    private fun startUdpReceiver() {
        if (receiveJob != null) return
        try { udpSocket?.close() } catch (_: Exception) {}
        try {
            udpSocket = DatagramSocket(audioPort).apply { reuseAddress = true; soTimeout = 0 }
        } catch (e: Exception) {
            Log.e(TAG, "UDP bind: ${e.message}")
            return
        }

        var recvCount = 0L
        receiveJob = scope.launch {
            val buf = ByteArray(2048)
            Log.i(TAG, "P2P UDP listening on $audioPort")
            while (isActive && isRunning) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(pkt)
                    val data = buf.copyOf(pkt.length)
                    val from = pkt.address

                    // Handshake check
                    if (data.size < 50) {
                        val txt = String(data, Charsets.UTF_8)
                        if (txt.startsWith("P2P|")) {
                            val pid = txt.substringAfter("P2P|").trim()
                            Log.i(TAG, "Handshake from $pid at $from")
                            val peer = MeshPeer(pid, from, audioPort)
                            connectedPeers[pid] = peer
                            supervisor?.onTransportConnected()
                            onPeerDiscovered?.invoke(peer)
                            // Send back
                            val reply = "P2P|$deviceId".toByteArray(Charsets.UTF_8)
                            udpSocket?.send(DatagramPacket(reply, reply.size, from, audioPort))
                            continue
                        }
                    }

                    val peerId = connectedPeers.entries
                        .firstOrNull { it.value.address.hostAddress == from.hostAddress }
                        ?.key ?: "unknown"
                    recvCount++
                    if (recvCount % 500 == 0L) Log.d(TAG, "P2P recv #$recvCount (${data.size}B from $peerId)")
                    onDataReceived?.invoke(peerId, data)
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "P2P recv: ${e.message}")
                }
            }
        }
    }

    override fun switchChannel(channelName: String) {}

    override fun stop() {
        isRunning = false
        try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        receiver = null
        receiveJob?.cancel()
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        if (groupCreated) {
            manager?.removeGroup(channel!!, null)
            groupCreated = false
        }
        manager?.cancelConnect(channel!!, null)
        connectedPeers.clear()
        Log.i(TAG, "P2P transport stopped (sent=$sendCount)")
    }
}
