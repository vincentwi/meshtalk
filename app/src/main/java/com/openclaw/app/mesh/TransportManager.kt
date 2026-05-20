package com.openclaw.app.mesh

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Transport Manager — tries all available transports and uses the first that connects.
 *
 * Priority order (best → fallback):
 *   1. WiFi Direct P2P    — no router, 200m range, <5ms latency
 *   2. WiFi LAN (NAN+UDP) — needs shared WiFi, 100m range
 *   3. Bluetooth RFCOMM   — always works, 30m range, 40ms latency
 *
 * All three run simultaneously. Audio is sent/received on whichever connects first.
 * If a higher-priority transport connects later, it takes over.
 */
class TransportManager(
    private val context: Context,
    private val deviceId: String
) : MeshTransport {

    companion object {
        private const val TAG = "TransportManager"
    }

    var supervisor: NanSupervisor? = null

    // Transports in priority order
    private val hotspotTransport = HotspotTransport(context, deviceId)
    private val wifiDirectTransport = WifiDirectTransport(context, deviceId)
    private val wifiLanTransport = WifiLanTransport(context, deviceId)
    private val btRfcommTransport = BluetoothRfcommTransport(context, deviceId)

    private var activeTransport: MeshTransport? = null
    private var activeTransportName: String = "none"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val peers: List<MeshPeer> get() = activeTransport?.peers ?: emptyList()
    override var onPeerDiscovered: ((MeshPeer) -> Unit)? = null
    override var onPeerLost: ((String) -> Unit)? = null
    override var onDataReceived: ((String, ByteArray) -> Unit)? = null

    override fun start(channelName: String) {
        Log.i(TAG, "Starting transports (Hotspot + WiFi LAN + BT RFCOMM)...")

        // Wire all transports
        wireTransport("Hotspot", hotspotTransport, priority = 1)
        wireTransport("WiFiLAN", wifiLanTransport, priority = 2)
        wireTransport("BT_RFCOMM", btRfcommTransport, priority = 3)

        // Start WiFi LAN first (works if on shared WiFi)
        wifiLanTransport.start(channelName)
        btRfcommTransport.start(channelName)

        // Start hotspot transport after a delay — only if WiFi LAN hasn't connected
        // This avoids creating a hotspot when already on shared WiFi
        scope.launch {
            delay(15000) // Give WiFi LAN 15s to connect
            if (isRunning && activeTransportName == "none") {
                Log.i(TAG, "No WiFi LAN connection after 15s — starting hotspot transport")
                hotspotTransport.start(channelName)
            } else if (activeTransportName != "none") {
                Log.i(TAG, "Already connected via $activeTransportName — hotspot not needed")
            }
        }
    }

    private var isRunning = true

    private fun wireTransport(name: String, transport: MeshTransport, priority: Int) {
        transport.onPeerDiscovered = { peer ->
            Log.i(TAG, "$name: peer discovered: ${peer.id}")

            // Use this transport if it's higher priority than current
            val currentPriority = when (activeTransportName) {
                "WiFiDirect" -> 1
                "WiFiLAN" -> 2
                "BT_RFCOMM" -> 3
                else -> 99
            }

            if (priority <= currentPriority) {
                if (activeTransportName != name) {
                    Log.i(TAG, "*** SWITCHING to $name (priority $priority, was $activeTransportName/$currentPriority) ***")
                }
                activeTransport = transport
                activeTransportName = name
            }

            onPeerDiscovered?.invoke(peer)
        }

        transport.onPeerLost = { peerId ->
            Log.w(TAG, "$name: peer lost: $peerId")
            if (activeTransportName == name) {
                activeTransport = null
                activeTransportName = "none"
                Log.w(TAG, "Active transport lost connection — waiting for reconnect")
            }
            onPeerLost?.invoke(peerId)
        }

        transport.onDataReceived = { peerId, data ->
            // Only deliver data from the active transport
            if (activeTransportName == name) {
                onDataReceived?.invoke(peerId, data)
            }
        }
    }

    override fun sendToAll(data: ByteArray) {
        activeTransport?.sendToAll(data)
    }

    override fun sendTo(peerId: String, data: ByteArray) {
        activeTransport?.sendTo(peerId, data)
    }

    override fun switchChannel(channelName: String) {
        wifiDirectTransport.switchChannel(channelName)
        wifiLanTransport.switchChannel(channelName)
        btRfcommTransport.switchChannel(channelName)
    }

    override fun stop() {
        isRunning = false
        hotspotTransport.stop()
        wifiDirectTransport.stop()
        wifiLanTransport.stop()
        btRfcommTransport.stop()
        activeTransport = null
        activeTransportName = "none"
        Log.i(TAG, "All transports stopped")
    }

    fun getActiveTransportName(): String = activeTransportName
}
