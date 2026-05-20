package com.openclaw.app.mesh

import android.content.Context
import android.net.*
import android.net.wifi.aware.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/**
 * WiFi Aware NAN transport.
 *
 * NDP is dead on Mercury OS — the transport falls back to NAN sendMessage()
 * for data transfer after a 15s watchdog timeout.
 *
 * sendMessage rules:
 *   - A PeerHandle is scoped to the session that received it
 *   - Subscriber's onServiceDiscovered gives a PeerHandle usable by subscribeSession.sendMessage()
 *   - Publisher's onMessageReceived gives a PeerHandle usable by publishSession.sendMessage()
 *   - Cross-session usage causes "address which didn't match/contact us" error
 */
class WifiAwareTransport(
    private val context: Context,
    private val deviceId: String,
    private val udpPort: Int = 18430
) : MeshTransport {

    companion object {
        private const val TAG = "WifiAwareTransport"
        private const val MAX_SENDMSG_PAYLOAD = 254  // NAN sendMessage limit is 255 bytes
    }

    var supervisor: NanSupervisor? = null

    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val connectedPeers = ConcurrentHashMap<String, MeshPeer>()
    private var udpSocket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private val handlerThread = HandlerThread("WifiAware").also { it.start() }
    private val handler = Handler(handlerThread.looper)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentChannel = ""

    // ── Peer handle tracking: session-scoped ───────────────────
    // Key = peerId, Value = pair(peerHandle, session that owns it)
    data class SessionHandle(val handle: PeerHandle, val session: DiscoverySession)
    private val pubHandles = ConcurrentHashMap<String, PeerHandle>()   // handles from publisher callback
    private val subHandles = ConcurrentHashMap<String, PeerHandle>()   // handles from subscriber callback
    private val handleToPeerId = ConcurrentHashMap<Int, String>()

    // ── NDP watchdog ───────────────────────────────────────────
    private var useSendMessageMode = false
    private var ndpCallbackFired = false
    private var ndpWatchdogJob: Job? = null

    // ── Stats ──────────────────────────────────────────────────
    private var sendCount = 0L
    private var recvCount = 0L

    override val peers: List<MeshPeer> get() = connectedPeers.values.toList()
    override var onPeerDiscovered: ((MeshPeer) -> Unit)? = null
    override var onPeerLost: ((String) -> Unit)? = null
    override var onDataReceived: ((String, ByteArray) -> Unit)? = null

    override fun start(channelName: String) {
        currentChannel = channelName
        val wifiAwareManager = context.getSystemService(WifiAwareManager::class.java)
        if (wifiAwareManager == null) {
            Log.e(TAG, "WiFi Aware not available")
            supervisor?.onTransportFailed("WiFi Aware not available")
            return
        }
        if (!wifiAwareManager.isAvailable) {
            Log.e(TAG, "WiFi Aware radio not available")
            supervisor?.onTransportFailed("WiFi Aware radio not available")
            return
        }

        Log.i(TAG, "Starting NAN transport (deviceId=$deviceId)")

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

    private fun publish(channelName: String) {
        try {
            val config = PublishConfig.Builder()
                .setServiceName(channelName)
                .setServiceSpecificInfo(deviceId.toByteArray(Charsets.UTF_8))
                .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                .build()

            awareSession?.publish(config, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishSession = session
                    Log.i(TAG, "Publishing on $channelName")
                    supervisor?.onTransportPublishing()
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    // Distinguish handshake from audio:
                    // Handshake = ASCII device ID (6 hex chars, e.g. "a1487b")
                    // Audio/data = PacketCodec binary (starts with type byte 0x01-0x04)
                    val isAsciiText = message.isNotEmpty() && message.all { it in 0x20..0x7E }
                    
                    if (useSendMessageMode && !isAsciiText) {
                        // Binary data — route to audio handler
                        recvCount++
                        val peerId = handleToPeerId[peerHandle.hashCode()] ?: "unknown"
                        if (recvCount % 100 == 0L) {
                            Log.d(TAG, "PUB recv audio #$recvCount (${message.size}B from $peerId)")
                        }
                        onDataReceived?.invoke(peerId, message)
                        return
                    }

                    if (!isAsciiText) return  // Binary but not in sendMessage mode — discard

                    // Handshake message — register this peer handle for the PUBLISH session
                    val peerId = String(message, Charsets.UTF_8).trim()
                    if (peerId.length > 20 || peerId.isEmpty()) return  // reject garbage

                    Log.i(TAG, "PUB: handshake from $peerId (handle=${peerHandle.hashCode()})")
                    pubHandles[peerId] = peerHandle
                    handleToPeerId[peerHandle.hashCode()] = peerId

                    // Try NDP (subscriber will also try)
                    tryNdp(publishSession!!, peerHandle, peerId)
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
                    val peerId = String(serviceSpecificInfo, Charsets.UTF_8).trim()
                    if (peerId == deviceId || peerId.isEmpty()) return
                    Log.i(TAG, "SUB: discovered $peerId (handle=${peerHandle.hashCode()})")

                    // Register for the SUBSCRIBE session
                    subHandles[peerId] = peerHandle
                    handleToPeerId[peerHandle.hashCode()] = peerId

                    // Send handshake (our device ID) to publisher
                    subscribeSession?.sendMessage(peerHandle, 0, deviceId.toByteArray(Charsets.UTF_8))

                    // Try NDP
                    tryNdp(subscribeSession!!, peerHandle, peerId)
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val isAsciiText = message.isNotEmpty() && message.all { it in 0x20..0x7E }
                    if (useSendMessageMode && !isAsciiText) {
                        recvCount++
                        val peerId = handleToPeerId[peerHandle.hashCode()] ?: "unknown"
                        if (recvCount % 100 == 0L) {
                            Log.d(TAG, "SUB recv audio #$recvCount (${message.size}B from $peerId)")
                        }
                        onDataReceived?.invoke(peerId, message)
                    }
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

    private fun tryNdp(session: DiscoverySession, peerHandle: PeerHandle, peerId: String) {
        if (connectedPeers.containsKey(peerId)) return
        if (useSendMessageMode) {
            // Already in sendMessage mode — skip NDP, just register peer
            registerSendMessagePeer(peerId)
            return
        }

        try {
            val specifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                .build()  // open NDP, no PSK
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()

            Log.i(TAG, "Trying NDP for $peerId")
            ndpCallbackFired = false

            // Watchdog: if NDP doesn't fire in 15s, fall back to sendMessage
            ndpWatchdogJob?.cancel()
            ndpWatchdogJob = scope.launch {
                delay(15000)
                if (!ndpCallbackFired) {
                    Log.w(TAG, "NDP WATCHDOG: No callback after 15s — falling back to sendMessage mode")
                    useSendMessageMode = true
                    registerSendMessagePeer(peerId)
                }
            }

            connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "NDP onAvailable for $peerId")
                    ndpCallbackFired = true
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    ndpCallbackFired = true
                    val peerInfo = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                    val peerAddr = peerInfo.peerIpv6Addr ?: return
                    val peer = MeshPeer(peerId, peerAddr, udpPort)
                    connectedPeers[peerId] = peer
                    Log.i(TAG, "NDP CONNECTED to $peerId at $peerAddr")
                    startUdpReceiver()
                    supervisor?.onTransportConnected()
                    onPeerDiscovered?.invoke(peer)
                }
                override fun onUnavailable() {
                    Log.w(TAG, "NDP onUnavailable for $peerId — will fall back via watchdog")
                }
                override fun onLost(network: Network) {
                    connectedPeers.clear()
                    supervisor?.onTransportNetworkLost()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "NDP request failed: ${e.message}")
        }
    }

    private fun registerSendMessagePeer(peerId: String) {
        if (connectedPeers.containsKey(peerId)) return
        // Create a fake peer with loopback — data flows via sendMessage, not UDP
        val peer = MeshPeer(peerId, InetAddress.getLoopbackAddress(), 0)
        connectedPeers[peerId] = peer
        Log.i(TAG, "Registered sendMessage peer: $peerId (pub=${pubHandles.containsKey(peerId)}, sub=${subHandles.containsKey(peerId)})")
        supervisor?.onTransportConnected()
        onPeerDiscovered?.invoke(peer)
    }

    // ── Sending ────────────────────────────────────────────────

    override fun sendToAll(data: ByteArray) {
        if (useSendMessageMode) {
            sendViaNan(data)
        } else {
            for ((_, peer) in connectedPeers) {
                sendTo(peer.id, data)
            }
        }
    }

    override fun sendTo(peerId: String, data: ByteArray) {
        if (useSendMessageMode) {
            sendNanTo(peerId, data)
        } else {
            val peer = connectedPeers[peerId] ?: return
            scope.launch {
                try {
                    val packet = DatagramPacket(data, data.size, peer.address, peer.port)
                    udpSocket?.send(packet)
                } catch (e: Exception) {
                    Log.e(TAG, "UDP send failed: ${e.message}")
                }
            }
        }
    }

    private fun sendViaNan(data: ByteArray) {
        // Truncate if > max payload
        val payload = if (data.size > MAX_SENDMSG_PAYLOAD) data.copyOf(MAX_SENDMSG_PAYLOAD) else data

        for ((peerId, _) in connectedPeers) {
            if (peerId == deviceId) continue
            sendNanTo(peerId, payload)
        }
    }

    private fun sendNanTo(peerId: String, data: ByteArray) {
        // Use the correct session+handle pair for this peer
        // Publisher sends via publishSession to handles received in pub callback
        // Subscriber sends via subscribeSession to handles received in sub callback
        val pubHandle = pubHandles[peerId]
        val subHandle = subHandles[peerId]

        try {
            if (pubHandle != null && publishSession != null) {
                publishSession!!.sendMessage(pubHandle, 0, data)
                sendCount++
            }
            if (subHandle != null && subscribeSession != null) {
                subscribeSession!!.sendMessage(subHandle, 0, data)
                sendCount++
            }
            if (sendCount % 200 == 0L) {
                Log.d(TAG, "NAN send #$sendCount (${data.size}B → $peerId, pub=${pubHandle!=null}, sub=${subHandle!=null})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "NAN send to $peerId failed: ${e.message}")
        }
    }

    // ── UDP receiver (only used if NDP succeeds) ───────────────

    private fun startUdpReceiver() {
        if (receiveJob != null) return
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = DatagramSocket(udpPort)
        udpSocket?.soTimeout = 0
        receiveJob = scope.launch {
            val buf = ByteArray(2048)
            Log.i(TAG, "UDP receiver started on port $udpPort")
            while (isActive) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(packet)
                    val data = buf.copyOf(packet.length)
                    val senderAddr = packet.address
                    val peerId = connectedPeers.entries
                        .firstOrNull { it.value.address.address.contentEquals(senderAddr.address) }
                        ?.key ?: "unknown"
                    onDataReceived?.invoke(peerId, data)
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "UDP receive error: ${e.message}")
                }
            }
        }
    }

    override fun switchChannel(channelName: String) {
        Log.i(TAG, "Switching to $channelName")
        publishSession?.close()
        subscribeSession?.close()
        connectedPeers.clear()
        pubHandles.clear()
        subHandles.clear()
        handleToPeerId.clear()
        currentChannel = channelName
        useSendMessageMode = false
        publish(channelName)
        subscribe(channelName)
    }

    override fun stop() {
        ndpWatchdogJob?.cancel()
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
        pubHandles.clear()
        subHandles.clear()
        handleToPeerId.clear()
        Log.i(TAG, "Transport stopped (sent=$sendCount, recv=$recvCount)")
    }
}
