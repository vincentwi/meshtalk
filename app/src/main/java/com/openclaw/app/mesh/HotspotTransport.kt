package com.openclaw.app.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback
import android.net.wifi.WifiManager.LocalOnlyHotspotReservation
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/**
 * Hotspot transport — one glass becomes a local WiFi hotspot, the other joins.
 *
 * Uses Android's LocalOnlyHotspot API (no internet sharing, no system permissions).
 * The hotspot creates a DHCP-served local network between the two glasses.
 * Once both are on the hotspot network, UDP broadcast discovery kicks in
 * automatically (same mechanism as WiFi LAN transport).
 *
 * Strategy (both glasses run this simultaneously):
 *   Phase 1: Scan for existing MeshTalk hotspots (DIRECT-xx-MeshTalk pattern)
 *   Phase 2: If none found after 8s, create our own hotspot
 *   Phase 3: Once on same network (either way), broadcast discovery + UDP audio
 *
 * The LocalOnlyHotspot SSID is system-generated (DIRECT-xx-{device} format).
 * We identify MeshTalk hotspots by scanning for SSIDs that match known patterns
 * or by trying all DIRECT-xx networks.
 */
@SuppressLint("MissingPermission")
class HotspotTransport(
    private val context: Context,
    private val deviceId: String,
    private val audioPort: Int = 18430
) : MeshTransport {

    companion object {
        private const val TAG = "HotspotTransport"
        // Beacon port for discovery (same as WifiLanTransport)
        private const val BEACON_PORT = 18431
    }

    var supervisor: NanSupervisor? = null

    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)!!
    private val handlerThread = HandlerThread("Hotspot").also { it.start() }
    private val handler = Handler(handlerThread.looper)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectedPeers = ConcurrentHashMap<String, MeshPeer>()
    private var udpSocket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var broadcastJob: Job? = null
    private var beaconListenerJob: Job? = null
    private var hotspotReservation: LocalOnlyHotspotReservation? = null
    private var isRunning = false
    private var isHotspotOwner = false
    private var sendCount = 0L
    private var scanJob: Job? = null
    private var originalNetworkId: Int = -1

    override val peers: List<MeshPeer> get() = connectedPeers.values.toList()
    override var onPeerDiscovered: ((MeshPeer) -> Unit)? = null
    override var onPeerLost: ((String) -> Unit)? = null
    override var onDataReceived: ((String, ByteArray) -> Unit)? = null

    override fun start(channelName: String) {
        isRunning = true
        Log.i(TAG, "Starting hotspot transport (deviceId=$deviceId)")
        originalNetworkId = wifiManager.connectionInfo?.networkId ?: -1

        // The HotspotTransport only creates/joins hotspots.
        // Once both glasses are on the same network (hotspot or WiFi),
        // WifiLanTransport's broadcast beacons handle discovery + audio.
        scanForMeshTalkHotspots()
    }

    // ── Phase 1: Scan for hotspots ─────────────────────────────

    private fun scanForMeshTalkHotspots() {
        Log.i(TAG, "Phase 1: Scanning for MeshTalk hotspots...")

        scanJob = scope.launch {
            // Trigger a WiFi scan
            @Suppress("DEPRECATION")
            wifiManager.startScan()
            delay(4000) // Wait for scan results

            @Suppress("DEPRECATION")
            val results = wifiManager.scanResults ?: emptyList()
            Log.i(TAG, "Scan found ${results.size} networks")

            // Look for DIRECT-xx networks (LocalOnlyHotspot creates these)
            val meshHotspots = results.filter { sr ->
                val ssid = sr.SSID ?: ""
                ssid.startsWith("DIRECT-") && !ssid.contains("EVELINK") && !ssid.contains("Frame")
            }

            if (meshHotspots.isNotEmpty()) {
                Log.i(TAG, "Found ${meshHotspots.size} potential hotspots:")
                for (hs in meshHotspots) {
                    Log.i(TAG, "  ${hs.SSID} (${hs.BSSID}) RSSI=${hs.level}")
                }
                // Try connecting to the strongest one
                val best = meshHotspots.maxByOrNull { it.level }
                if (best != null) {
                    connectToHotspot(best.SSID, best.BSSID)
                    return@launch
                }
            }

            Log.i(TAG, "No MeshTalk hotspots found — scanning once more...")
            delay(5000)

            @Suppress("DEPRECATION")
            wifiManager.startScan()
            delay(4000)

            @Suppress("DEPRECATION")
            val results2 = wifiManager.scanResults ?: emptyList()
            val meshHotspots2 = results2.filter { sr ->
                val ssid = sr.SSID ?: ""
                ssid.startsWith("DIRECT-")  && !ssid.contains("EVELINK") && !ssid.contains("Frame")
            }

            if (meshHotspots2.isNotEmpty()) {
                val best = meshHotspots2.maxByOrNull { it.level }
                if (best != null) {
                    connectToHotspot(best.SSID, best.BSSID)
                    return@launch
                }
            }

            // No hotspot found — create our own (Phase 2)
            Log.i(TAG, "Phase 2: No hotspots found — creating our own")
            createHotspot()
        }
    }

    // ── Phase 2: Create hotspot ────────────────────────────────

    private fun createHotspot() {
        try {
            wifiManager.startLocalOnlyHotspot(object : LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    isHotspotOwner = true
                    @Suppress("DEPRECATION")
                    val config = reservation.wifiConfiguration
                    val ssid = config?.SSID ?: "unknown"
                    val pass = config?.preSharedKey ?: ""

                    // Store the SSID/password so the other glass can be told to join
                    // (via NAN sendMessage, BLE advertisement, or manual config)
                    hotspotSsid = ssid
                    hotspotPassword = pass
                    Log.i(TAG, "*** HOTSPOT CREATED: SSID=$ssid pass=$pass ***")

                    scope.launch {
                        delay(3000) // Wait for interface to come up
                        val myIp = getLocalIp()
                        Log.i(TAG, "Hotspot owner IP: $myIp")
                        startBeaconBroadcast()

                        // Also broadcast the SSID/password via NAN if available
                        broadcastHotspotInfoViaNan()
                    }
                }

                override fun onStopped() {
                    Log.w(TAG, "Hotspot stopped")
                    hotspotReservation = null
                    isHotspotOwner = false
                }

                override fun onFailed(reason: Int) {
                    val r = when (reason) {
                        ERROR_NO_CHANNEL -> "NO_CHANNEL"
                        ERROR_GENERIC -> "GENERIC"
                        ERROR_INCOMPATIBLE_MODE -> "INCOMPATIBLE_MODE"
                        ERROR_TETHERING_DISALLOWED -> "TETHERING_DISALLOWED"
                        else -> "$reason"
                    }
                    Log.e(TAG, "Hotspot failed: $r — retrying scan")
                    scope.launch {
                        delay(5000)
                        if (isRunning && connectedPeers.isEmpty()) scanForMeshTalkHotspots()
                    }
                }
            }, handler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Hotspot SecurityException: ${e.message}")
            Log.e(TAG, "Need ACCESS_FINE_LOCATION permission for hotspot")
        } catch (e: Exception) {
            Log.e(TAG, "Hotspot failed: ${e.message}")
        }
    }

    private var hotspotSsid: String = ""
    private var hotspotPassword: String = ""

    /**
     * Use NAN sendMessage to broadcast our hotspot SSID/password to nearby glasses.
     * This lets the other glass join our hotspot even though the SSID is random.
     */
    private fun broadcastHotspotInfoViaNan() {
        if (hotspotSsid.isEmpty()) return

        // Try WiFi Aware if available
        val wam = context.getSystemService(android.net.wifi.aware.WifiAwareManager::class.java) ?: return
        if (!wam.isAvailable) {
            Log.w(TAG, "NAN not available for hotspot info broadcast")
            return
        }

        Log.i(TAG, "Broadcasting hotspot info via NAN: SSID=$hotspotSsid")
        // The WifiLanTransport's NAN publish session already includes our info
        // We just need to update the service-specific info to include hotspot details
        // For now, log the info — the other glass can be configured via ADB
        Log.i(TAG, "=== HOTSPOT JOIN COMMAND ===")
        Log.i(TAG, "adb shell cmd wifi connect-network \"$hotspotSsid\" wpa2 \"$hotspotPassword\"")
    }

    // ── Connect to discovered hotspot ──────────────────────────

    @Suppress("DEPRECATION")
    private fun connectToHotspot(ssid: String, bssid: String?) {
        Log.i(TAG, "Connecting to hotspot: $ssid")

        try {
            // For LocalOnlyHotspot networks (DIRECT-xx), they're open or WPA2
            // Try open first, then WPA2 with empty passphrase
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                // Try open network first
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                if (bssid != null) BSSID = bssid
            }

            val netId = wifiManager.addNetwork(config)
            if (netId != -1) {
                wifiManager.disconnect()
                wifiManager.enableNetwork(netId, true)
                wifiManager.reconnect()
                Log.i(TAG, "Connecting to $ssid (netId=$netId)")

                // Wait for connection and start beacons
                scope.launch {
                    delay(5000) // Wait for DHCP
                    val ip = getLocalIp()
                    if (ip != "0.0.0.0") {
                        Log.i(TAG, "Connected to hotspot! IP: $ip")
                        startBeaconBroadcast()
                    } else {
                        Log.w(TAG, "Connected but no IP yet, waiting...")
                        delay(5000)
                        val ip2 = getLocalIp()
                        if (ip2 != "0.0.0.0") {
                            Log.i(TAG, "Got IP: $ip2")
                            startBeaconBroadcast()
                        } else {
                            Log.e(TAG, "Failed to get IP on hotspot network")
                            // Try creating our own hotspot instead
                            createHotspot()
                        }
                    }
                }
            } else {
                Log.e(TAG, "addNetwork failed for $ssid")
                // Fall through to create our own hotspot
                createHotspot()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connect to hotspot failed: ${e.message}")
            createHotspot()
        }
    }

    // ── Beacon broadcast (same as WifiLanTransport) ────────────

    private fun startBeaconBroadcast() {
        if (broadcastJob != null) return
        val myIp = getLocalIp()
        if (myIp == "0.0.0.0") {
            Log.w(TAG, "No IP — beacon broadcast delayed")
            return
        }

        Log.i(TAG, "Starting beacon broadcast (ip=$myIp)")
        broadcastJob = scope.launch {
            val beacon = "MESHTALK_BEACON|$deviceId|$myIp|$audioPort".toByteArray(Charsets.UTF_8)
            val broadcastAddr = InetAddress.getByName("255.255.255.255")

            while (isActive && isRunning) {
                try {
                    val sock = DatagramSocket().apply { broadcast = true }
                    sock.send(DatagramPacket(beacon, beacon.size, broadcastAddr, BEACON_PORT))
                    sock.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Beacon send: ${e.message}")
                }
                delay(3000)
            }
        }
    }

    private fun startBeaconListener() {
        beaconListenerJob = scope.launch {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(BEACON_PORT))
                }
                val buf = ByteArray(256)
                Log.i(TAG, "Beacon listener on port $BEACON_PORT")

                while (isActive && isRunning) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val text = String(buf, 0, pkt.length, Charsets.UTF_8)
                    if (text.startsWith("MESHTALK_BEACON|")) {
                        val parts = text.split("|")
                        if (parts.size >= 4) {
                            val peerId = parts[1]
                            val peerIp = parts[2]
                            if (peerId != deviceId && !connectedPeers.containsKey(peerId)) {
                                registerPeer(peerId, peerIp)
                            }
                        }
                    }
                }
                sock.close()
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Beacon listener: ${e.message}")
            }
        }
    }

    private fun registerPeer(peerId: String, ipString: String) {
        if (connectedPeers.containsKey(peerId)) return
        try {
            val addr = InetAddress.getByName(ipString)
            val peer = MeshPeer(peerId, addr, audioPort)
            connectedPeers[peerId] = peer
            Log.i(TAG, "*** HOTSPOT PEER CONNECTED: $peerId at $ipString ***")
            supervisor?.onTransportConnected()
            onPeerDiscovered?.invoke(peer)
        } catch (e: Exception) {
            Log.e(TAG, "Register peer: ${e.message}")
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
                if (sendCount % 500 == 0L) {
                    Log.d(TAG, "Hotspot sent #$sendCount (${data.size}B → $peerId)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP send: ${e.message}")
            }
        }
    }

    private fun startUdpReceiver() {
        try { udpSocket?.close() } catch (_: Exception) {}
        try {
            udpSocket = DatagramSocket(audioPort).apply { reuseAddress = true; soTimeout = 0 }
        } catch (e: Exception) {
            Log.e(TAG, "UDP bind port $audioPort: ${e.message}")
            return
        }

        var recvCount = 0L
        receiveJob = scope.launch {
            val buf = ByteArray(2048)
            Log.i(TAG, "UDP receiver on port $audioPort")
            while (isActive && isRunning) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(pkt)
                    val data = buf.copyOf(pkt.length)
                    val from = pkt.address
                    val peerId = connectedPeers.entries
                        .firstOrNull { it.value.address.hostAddress == from.hostAddress }
                        ?.key ?: "unknown"
                    recvCount++
                    if (recvCount % 500 == 0L) {
                        Log.d(TAG, "Hotspot recv #$recvCount (${data.size}B from $peerId)")
                    }
                    onDataReceived?.invoke(peerId, data)
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "UDP recv: ${e.message}")
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun getLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                // Check wlan0, swlan0, ap0, softap0 — hotspot might use different interface
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip != "0.0.0.0") return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }

    override fun switchChannel(channelName: String) {}

    override fun stop() {
        isRunning = false
        scanJob?.cancel()
        broadcastJob?.cancel()
        beaconListenerJob?.cancel()
        receiveJob?.cancel()
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null

        // Close hotspot
        try { hotspotReservation?.close() } catch (_: Exception) {}
        hotspotReservation = null

        // Restore original WiFi network
        if (originalNetworkId != -1) {
            @Suppress("DEPRECATION")
            wifiManager.enableNetwork(originalNetworkId, true)
        }

        connectedPeers.clear()
        Log.i(TAG, "Hotspot transport stopped (owner=$isHotspotOwner, sent=$sendCount)")
    }
}
