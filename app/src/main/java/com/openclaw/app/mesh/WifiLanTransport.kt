package com.openclaw.app.mesh

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.aware.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/**
 * WiFi LAN transport: uses NAN for peer discovery, then streams audio via
 * UDP over the shared WiFi network.
 *
 * NDP is dead on Mercury OS, and NAN sendMessage maxes out at ~5 msg/sec.
 * But both glasses share a WiFi network and can reach each other directly
 * via their WiFi LAN IPs. This transport:
 *
 * 1. Publishes/subscribes on NAN to find peers
 * 2. Exchanges WiFi LAN IPs via NAN serviceSpecificInfo
 * 3. Opens a UDP socket on the WiFi interface for audio streaming
 *
 * This bypasses NDP entirely — no data path negotiation needed.
 */
class WifiLanTransport(
    private val context: Context,
    private val deviceId: String,
    private val audioPort: Int = 18430
) : MeshTransport {

    companion object {
        private const val TAG = "WifiLanTransport"
    }

    var supervisor: NanSupervisor? = null

    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val connectedPeers = ConcurrentHashMap<String, MeshPeer>()
    private var udpSocket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private val handlerThread = HandlerThread("WifiLan").also { it.start() }
    private val handler = Handler(handlerThread.looper)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentChannel = ""

    override val peers: List<MeshPeer> get() = connectedPeers.values.toList()
    override var onPeerDiscovered: ((MeshPeer) -> Unit)? = null
    override var onPeerLost: ((String) -> Unit)? = null
    override var onDataReceived: ((String, ByteArray) -> Unit)? = null

    override fun start(channelName: String) {
        currentChannel = channelName

        // Start UDP listener first
        startUdpReceiver()

        // Then start NAN discovery to find peers and exchange IPs
        val wifiAwareManager = context.getSystemService(WifiAwareManager::class.java)
        if (wifiAwareManager == null || !wifiAwareManager.isAvailable) {
            Log.e(TAG, "WiFi Aware not available — cannot discover peers")
            supervisor?.onTransportFailed("WiFi Aware not available")
            return
        }

        val myIp = getWifiIp()
        Log.i(TAG, "Starting: deviceId=$deviceId, myIP=$myIp, port=$audioPort")

        wifiAwareManager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                Log.i(TAG, "WiFi Aware attached")
                awareSession = session
                supervisor?.onTransportAttached()
                publish(channelName)
                subscribe(channelName)
            }
            override fun onAttachFailed() {
                Log.e(TAG, "WiFi Aware attach failed")
                supervisor?.onTransportAttachFailed()
            }
        }, handler)
    }

    /**
     * Service-specific info format: "deviceId|ipAddress"
     * e.g. "e40482|10.0.10.193"
     */
    private fun makeServiceInfo(): ByteArray {
        val ip = getWifiIp()
        return "$deviceId|$ip".toByteArray(Charsets.UTF_8)
    }

    private fun parseServiceInfo(data: ByteArray): Pair<String, String>? {
        val text = String(data, Charsets.UTF_8)
        val parts = text.split("|", limit = 2)
        if (parts.size != 2) return null
        return parts[0] to parts[1]
    }

    private fun publish(channelName: String) {
        try {
            val config = PublishConfig.Builder()
                .setServiceName(channelName)
                .setServiceSpecificInfo(makeServiceInfo())
                .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                .build()

            awareSession?.publish(config, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishSession = session
                    Log.i(TAG, "Publishing on $channelName")
                    supervisor?.onTransportPublishing()
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    // Subscriber sends their IP info
                    val info = parseServiceInfo(message) ?: return
                    val (peerId, peerIp) = info
                    if (peerId == deviceId) return
                    Log.i(TAG, "PUB: received IP from $peerId: $peerIp")
                    registerPeer(peerId, peerIp)
                }

                override fun onSessionTerminated() {
                    Log.w(TAG, "Publish session terminated")
                    publishSession = null
                    supervisor?.onTransportFailed("publish session terminated")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Publish failed: ${e.message}")
            supervisor?.onTransportFailed("publish: ${e.message}")
        }
    }

    private fun subscribe(channelName: String) {
        try {
            val config = SubscribeConfig.Builder()
                .setServiceName(channelName)
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                .build()

            awareSession?.subscribe(config, object : DiscoverySessionCallback() {
                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: List<ByteArray>
                ) {
                    val info = parseServiceInfo(serviceSpecificInfo) ?: return
                    val (peerId, peerIp) = info
                    if (peerId == deviceId) return
                    Log.i(TAG, "SUB: discovered $peerId at IP $peerIp")

                    // Register peer with their WiFi IP
                    registerPeer(peerId, peerIp)

                    // Send our IP back to the publisher
                    subscribeSession?.sendMessage(peerHandle, 0, makeServiceInfo())
                }

                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeSession = session
                    Log.i(TAG, "Subscribed to $channelName")
                    supervisor?.onTransportDiscovering()
                }

                override fun onSessionTerminated() {
                    Log.w(TAG, "Subscribe session terminated")
                    subscribeSession = null
                    supervisor?.onTransportFailed("subscribe session terminated")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Subscribe failed: ${e.message}")
            supervisor?.onTransportFailed("subscribe: ${e.message}")
        }
    }

    private fun registerPeer(peerId: String, ipString: String) {
        if (connectedPeers.containsKey(peerId)) return
        try {
            val addr = InetAddress.getByName(ipString)
            val peer = MeshPeer(peerId, addr, audioPort)
            connectedPeers[peerId] = peer
            Log.i(TAG, "*** PEER CONNECTED: $peerId at $ipString:$audioPort ***")
            supervisor?.onTransportConnected()
            onPeerDiscovered?.invoke(peer)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register peer $peerId at $ipString: ${e.message}")
        }
    }

    // ── UDP audio streaming ────────────────────────────────────

    private var sendCount = 0L

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
                    Log.d(TAG, "UDP sent #$sendCount (${data.size}B → $peerId)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP send to $peerId failed: ${e.message}")
            }
        }
    }

    private fun startUdpReceiver() {
        try { udpSocket?.close() } catch (_: Exception) {}
        try {
            udpSocket = DatagramSocket(audioPort)
            udpSocket?.soTimeout = 0
            udpSocket?.reuseAddress = true
        } catch (e: Exception) {
            Log.e(TAG, "UDP bind on port $audioPort failed: ${e.message}")
            return
        }

        var recvCount = 0L
        receiveJob = scope.launch {
            val buf = ByteArray(2048)
            Log.i(TAG, "UDP receiver listening on port $audioPort")
            while (isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(packet)
                    val data = buf.copyOf(packet.length)
                    val senderAddr = packet.address
                    val peerId = connectedPeers.entries
                        .firstOrNull { it.value.address.hostAddress == senderAddr.hostAddress }
                        ?.key ?: "unknown"
                    recvCount++
                    if (recvCount % 500 == 0L) {
                        Log.d(TAG, "UDP recv #$recvCount (${data.size}B from $peerId)")
                    }
                    onDataReceived?.invoke(peerId, data)
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "UDP receive error: ${e.message}")
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun getWifiIp(): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            val wifiInfo = wifiManager?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xFF,
                    (ipInt shr 8) and 0xFF,
                    (ipInt shr 16) and 0xFF,
                    (ipInt shr 24) and 0xFF
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiManager IP failed: ${e.message}")
        }

        // Fallback: enumerate interfaces
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.name != "wlan0") continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NetworkInterface fallback failed: ${e.message}")
        }
        return "0.0.0.0"
    }

    override fun switchChannel(channelName: String) {
        Log.i(TAG, "Switching to $channelName")
        publishSession?.close()
        subscribeSession?.close()
        connectedPeers.clear()
        currentChannel = channelName
        publish(channelName)
        subscribe(channelName)
    }

    override fun stop() {
        receiveJob?.cancel()
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        publishSession?.close()
        publishSession = null
        subscribeSession?.close()
        subscribeSession = null
        awareSession?.close()
        awareSession = null
        connectedPeers.clear()
        Log.i(TAG, "Transport stopped (sent=$sendCount)")
    }
}
