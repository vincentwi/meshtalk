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
 * WiFi Direct (P2P) transport — no router needed.
 *
 * One glass becomes Group Owner (soft AP), the other connects.
 * After P2P group formation, both get IP addresses and use UDP for audio.
 *
 * Architecture:
 *   1. Both glasses call discoverPeers() simultaneously
 *   2. On peer found, the device with the higher deviceId becomes GO
 *   3. GO creates group, client connects
 *   4. UDP audio streams on the P2P network
 *
 * Benefits over WiFi LAN:
 *   - NO router needed — works anywhere, outdoors, etc.
 *   - ~250 Mbps throughput (massive overkill for audio)
 *   - ~200m range
 *   - <5ms local latency
 *
 * Limitations:
 *   - Initial connection takes 5-15 seconds
 *   - May disconnect from existing WiFi (device-dependent)
 *   - User consent dialog on some devices (Mercury OS unknown)
 */
@SuppressLint("MissingPermission")
class WifiDirectTransport(
    private val context: Context,
    private val deviceId: String,
    private val audioPort: Int = 18431  // different from WiFi LAN to avoid collision
) : MeshTransport {

    companion object {
        private const val TAG = "WifiDirectTransport"
        private const val SERVICE_NAME = "meshtalk"
    }

    var supervisor: NanSupervisor? = null

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private val connectedPeers = ConcurrentHashMap<String, MeshPeer>()
    private var udpSocket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isGroupOwner = false
    private var groupOwnerAddress: InetAddress? = null
    private var isRunning = false
    private var sendCount = 0L
    private var receiver: BroadcastReceiver? = null

    override val peers: List<MeshPeer> get() = connectedPeers.values.toList()
    override var onPeerDiscovered: ((MeshPeer) -> Unit)? = null
    override var onPeerLost: ((String) -> Unit)? = null
    override var onDataReceived: ((String, ByteArray) -> Unit)? = null

    override fun start(channelName: String) {
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            Log.e(TAG, "WiFi Direct not available")
            supervisor?.onTransportFailed("WiFi Direct not available")
            return
        }

        channel = manager!!.initialize(context, Looper.getMainLooper(), null)
        isRunning = true

        Log.i(TAG, "Starting WiFi Direct transport (deviceId=$deviceId)")

        registerReceiver()
        discoverPeers()
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
                        Log.i(TAG, "P2P state: ${if (state == WIFI_P2P_STATE_ENABLED) "ENABLED" else "DISABLED"}")
                        if (state != WIFI_P2P_STATE_ENABLED) {
                            supervisor?.onTransportFailed("WiFi Direct disabled")
                        }
                    }

                    WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        Log.i(TAG, "Peers changed — requesting peer list")
                        manager?.requestPeers(channel!!) { peers ->
                            handlePeerList(peers)
                        }
                    }

                    WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            Log.i(TAG, "P2P connected — requesting connection info")
                            manager?.requestConnectionInfo(channel!!) { info ->
                                handleConnectionInfo(info)
                            }
                        } else {
                            Log.w(TAG, "P2P disconnected")
                            connectedPeers.clear()
                            supervisor?.onTransportNetworkLost()
                        }
                    }

                    WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = intent.getParcelableExtra<WifiP2pDevice>(EXTRA_WIFI_P2P_DEVICE)
                        Log.i(TAG, "This device: ${device?.deviceName} (${device?.deviceAddress})")
                    }
                }
            }
        }

        context.registerReceiver(receiver, filter)
        Log.i(TAG, "P2P broadcast receiver registered")
    }

    private fun discoverPeers() {
        manager?.discoverPeers(channel!!, object : ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Peer discovery started")
                supervisor?.onTransportDiscovering()
            }

            override fun onFailure(reason: Int) {
                val reasonStr = when (reason) {
                    P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                    ERROR -> "ERROR"
                    BUSY -> "BUSY"
                    else -> "UNKNOWN($reason)"
                }
                Log.e(TAG, "Peer discovery failed: $reasonStr")

                // Retry after delay
                scope.launch {
                    delay(5000)
                    if (isRunning) discoverPeers()
                }
            }
        })
    }

    private fun handlePeerList(peers: WifiP2pDeviceList) {
        val deviceList = peers.deviceList
        Log.i(TAG, "Found ${deviceList.size} P2P peers")

        for (device in deviceList) {
            Log.i(TAG, "  Peer: ${device.deviceName} (${device.deviceAddress}) status=${device.status}")

            // Connect to first available peer
            if (device.status == WifiP2pDevice.AVAILABLE) {
                connectToPeer(device)
                break
            }
        }
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Let the system decide group owner
            groupOwnerIntent = 0  // 0 = low preference, let other side be GO
        }

        manager?.connect(channel!!, config, object : ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "P2P connect initiated to ${device.deviceName}")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "P2P connect failed: reason=$reason")
            }
        })
    }

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        isGroupOwner = info.isGroupOwner
        groupOwnerAddress = info.groupOwnerAddress

        Log.i(TAG, "P2P connection info: isGO=$isGroupOwner, goAddr=$groupOwnerAddress")

        if (groupOwnerAddress == null) {
            Log.e(TAG, "No group owner address!")
            return
        }

        // Start UDP for audio
        startUdpReceiver()

        if (isGroupOwner) {
            // GO waits for client to send first UDP packet to learn client's IP
            Log.i(TAG, "I am Group Owner — waiting for client UDP on port $audioPort")
            supervisor?.onTransportPublishing()
        } else {
            // Client knows GO's IP — register it and start sending
            val goAddr = groupOwnerAddress!!
            val peerId = "p2p-go"  // will be updated by handshake
            val peer = MeshPeer(peerId, goAddr, audioPort)
            connectedPeers[peerId] = peer
            Log.i(TAG, "*** P2P CONNECTED to GO at $goAddr ***")
            supervisor?.onTransportConnected()
            onPeerDiscovered?.invoke(peer)

            // Send handshake so GO learns our deviceId
            scope.launch {
                delay(500)
                val handshake = "P2P_HELLO|$deviceId".toByteArray(Charsets.UTF_8)
                try {
                    val packet = DatagramPacket(handshake, handshake.size, goAddr, audioPort)
                    udpSocket?.send(packet)
                    Log.i(TAG, "Sent P2P handshake to GO")
                } catch (e: Exception) {
                    Log.e(TAG, "Handshake send failed: ${e.message}")
                }
            }
        }
    }

    // ── UDP audio streaming ────────────────────────────────────

    override fun sendToAll(data: ByteArray) {
        for ((_, peer) in connectedPeers) {
            sendTo(peer.id, data)
        }
    }

    override fun sendTo(peerId: String, data: ByteArray) {
        val peer = connectedPeers[peerId] ?: return
        scope.launch {
            try {
                val packet = DatagramPacket(data, data.size, peer.address, peer.port)
                udpSocket?.send(packet)
                sendCount++
                if (sendCount % 500 == 0L) {
                    Log.d(TAG, "P2P sent #$sendCount (${data.size}B → $peerId)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "P2P send failed: ${e.message}")
            }
        }
    }

    private fun startUdpReceiver() {
        if (receiveJob != null) return
        try {
            udpSocket?.close()
        } catch (_: Exception) {}

        try {
            udpSocket = DatagramSocket(audioPort)
            udpSocket?.soTimeout = 0
            udpSocket?.reuseAddress = true
        } catch (e: Exception) {
            Log.e(TAG, "UDP bind failed: ${e.message}")
            return
        }

        var recvCount = 0L
        receiveJob = scope.launch {
            val buf = ByteArray(2048)
            Log.i(TAG, "P2P UDP receiver on port $audioPort")
            while (isActive && isRunning) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(packet)
                    val data = buf.copyOf(packet.length)
                    val senderAddr = packet.address

                    // Check for handshake
                    if (data.size < 100) {
                        val text = String(data, Charsets.UTF_8)
                        if (text.startsWith("P2P_HELLO|")) {
                            val peerId = text.substringAfter("P2P_HELLO|").trim()
                            Log.i(TAG, "Received P2P handshake from $peerId at $senderAddr")
                            val peer = MeshPeer(peerId, senderAddr, audioPort)
                            connectedPeers[peerId] = peer
                            supervisor?.onTransportConnected()
                            onPeerDiscovered?.invoke(peer)

                            // Send handshake back
                            val reply = "P2P_HELLO|$deviceId".toByteArray(Charsets.UTF_8)
                            val replyPacket = DatagramPacket(reply, reply.size, senderAddr, audioPort)
                            udpSocket?.send(replyPacket)
                            continue
                        }
                    }

                    // Find peer by address
                    val peerId = connectedPeers.entries
                        .firstOrNull { it.value.address.hostAddress == senderAddr.hostAddress }
                        ?.key ?: "unknown"

                    recvCount++
                    if (recvCount % 500 == 0L) {
                        Log.d(TAG, "P2P recv #$recvCount (${data.size}B from $peerId)")
                    }
                    onDataReceived?.invoke(peerId, data)
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "P2P recv error: ${e.message}")
                }
            }
        }
    }

    override fun switchChannel(channelName: String) {
        Log.i(TAG, "Channel switch (P2P doesn't use channels)")
    }

    override fun stop() {
        isRunning = false
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        receiver = null
        receiveJob?.cancel()
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        manager?.removeGroup(channel!!, null)
        manager?.cancelConnect(channel!!, null)
        connectedPeers.clear()
        Log.i(TAG, "P2P transport stopped (sent=$sendCount)")
    }
}
