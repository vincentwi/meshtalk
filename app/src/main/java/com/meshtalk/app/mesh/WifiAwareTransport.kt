package com.meshtalk.app.mesh

import android.content.Context
import android.net.*
import android.net.wifi.aware.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

class WifiAwareTransport(
    private val context: Context,
    private val deviceId: String,
    private val udpPort: Int = 18430
) : MeshTransport {

    companion object {
        private const val TAG = "WifiAwareTransport"
        private const val PSK = "meshtalk_shared_key_2025"
    }

    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val connectedPeers = ConcurrentHashMap<String, MeshPeer>()
    private var udpSocket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private val handlerThread = HandlerThread("WifiAware").also { it.start() }
    private val handler = Handler(handlerThread.looper)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var currentChannel = ""

    override val peers: List<MeshPeer> get() = connectedPeers.values.toList()
    override var onPeerDiscovered: ((MeshPeer) -> Unit)? = null
    override var onPeerLost: ((String) -> Unit)? = null
    override var onDataReceived: ((String, ByteArray) -> Unit)? = null

    override fun start(channelName: String) {
        currentChannel = channelName
        val wifiAwareManager = context.getSystemService(WifiAwareManager::class.java)
        if (wifiAwareManager == null) {
            Log.e(TAG, "WiFi Aware not available")
            return
        }

        wifiAwareManager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                Log.i(TAG, "WiFi Aware attached")
                awareSession = session
                startUdpReceiver()
                publish(channelName)
                subscribe(channelName)
            }
            override fun onAttachFailed() {
                Log.e(TAG, "WiFi Aware attach failed")
            }
        }, handler)
    }

    private fun publish(channelName: String) {
        val config = PublishConfig.Builder()
            .setServiceName(channelName)
            .setServiceSpecificInfo(deviceId.toByteArray(Charsets.UTF_8))
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .build()

        awareSession?.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                publishSession = session
                Log.i(TAG, "Publishing on $channelName")
            }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val peerId = String(message, Charsets.UTF_8)
                Log.i(TAG, "Message from peer: $peerId, requesting network")
                publishSession?.let { requestNetwork(it, peerHandle, peerId) }
            }
        }, handler)
    }

    private fun subscribe(channelName: String) {
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
                val peerId = String(serviceSpecificInfo, Charsets.UTF_8)
                if (peerId == deviceId) return // Ignore self
                Log.i(TAG, "Discovered peer: $peerId")
                // Send our ID so the publisher can identify us
                subscribeSession?.sendMessage(peerHandle, 0, deviceId.toByteArray(Charsets.UTF_8))
                requestNetwork(subscribeSession!!, peerHandle, peerId)
            }

            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                subscribeSession = session
                Log.i(TAG, "Subscribed to $channelName")
            }
        }, handler)
    }

    private fun requestNetwork(session: DiscoverySession, peerHandle: PeerHandle, peerId: String) {
        if (connectedPeers.containsKey(peerId)) return

        val specifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
            .setPskPassphrase(PSK)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val peerInfo = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                val peerAddr = peerInfo.peerIpv6Addr ?: return
                val peer = MeshPeer(peerId, peerAddr, udpPort)
                connectedPeers[peerId] = peer
                Log.i(TAG, "Connected to peer $peerId at $peerAddr")
                onPeerDiscovered?.invoke(peer)
            }

            override fun onLost(network: Network) {
                connectedPeers.entries.removeIf { true } // simplified
                Log.w(TAG, "Network lost")
            }
        }, handler)
    }

    private fun startUdpReceiver() {
        udpSocket = DatagramSocket(udpPort)
        udpSocket?.soTimeout = 0 // blocking

        receiveJob = scope.launch {
            val buf = ByteArray(2048)
            Log.i(TAG, "UDP receiver started on port $udpPort")
            while (isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(packet)
                    val data = buf.copyOf(packet.length)
                    val senderAddr = packet.address
                    // Find peer by address
                    val peerId = connectedPeers.entries
                        .firstOrNull { it.value.address.address.contentEquals(senderAddr.address) }
                        ?.key ?: "unknown"
                    onDataReceived?.invoke(peerId, data)
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Receive error: ${e.message}")
                }
            }
        }
    }

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
            } catch (e: Exception) {
                Log.e(TAG, "Send to $peerId failed: ${e.message}")
            }
        }
    }

    override fun switchChannel(channelName: String) {
        Log.i(TAG, "Switching from $currentChannel to $channelName")
        publishSession?.close()
        subscribeSession?.close()
        connectedPeers.clear()
        currentChannel = channelName
        publish(channelName)
        subscribe(channelName)
    }

    override fun stop() {
        receiveJob?.cancel()
        udpSocket?.close()
        publishSession?.close()
        subscribeSession?.close()
        awareSession?.close()
        connectedPeers.clear()
        scope.cancel()
        Log.i(TAG, "Transport stopped")
    }
}
