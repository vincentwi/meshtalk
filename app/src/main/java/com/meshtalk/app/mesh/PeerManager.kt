package com.meshtalk.app.mesh

import android.util.Log
import kotlinx.coroutines.*

class PeerManager(
    private val transport: MeshTransport,
    private val timeoutMs: Long = 15000,
    private val announceIntervalMs: Long = 15000,
    private val keepaliveIntervalMs: Long = 5000
) {
    companion object {
        private const val TAG = "PeerManager"
    }

    private val activePeers = mutableMapOf<String, Long>() // peerId → lastSeen
    private var keepaliveJob: Job? = null
    private var announceJob: Job? = null
    private var seq = 0

    var onPeerCountChanged: ((Int) -> Unit)? = null
    var deviceId: String = "unknown"
    var currentChannelId: Int = 0

    fun start(scope: CoroutineScope) {
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(keepaliveIntervalMs)
                sendKeepalive()
                pruneStale()
            }
        }

        announceJob = scope.launch {
            while (isActive) {
                delay(announceIntervalMs)
                sendAnnounce()
            }
        }
    }

    fun onPeerDataReceived(peerId: String) {
        val isNew = !activePeers.containsKey(peerId)
        activePeers[peerId] = System.currentTimeMillis()
        if (isNew) {
            Log.i(TAG, "New peer: $peerId (total: ${activePeers.size + 1})")
            onPeerCountChanged?.invoke(activePeers.size + 1) // +1 for self
        }
    }

    fun onPeerLost(peerId: String) {
        activePeers.remove(peerId)
        Log.i(TAG, "Peer lost: $peerId (total: ${activePeers.size + 1})")
        onPeerCountChanged?.invoke(activePeers.size + 1)
    }

    private fun pruneStale() {
        val now = System.currentTimeMillis()
        val stale = activePeers.filter { now - it.value > timeoutMs }.keys
        for (id in stale) {
            activePeers.remove(id)
            Log.i(TAG, "Peer timed out: $id")
            onPeerCountChanged?.invoke(activePeers.size + 1)
        }
    }

    private fun sendKeepalive() {
        val packet = PacketCodec.encodePing(currentChannelId, seq++)
        transport.sendToAll(packet)
    }

    fun sendAnnounce() {
        val json = """{"cmd":"announce","user":"$deviceId","channel":$currentChannelId,"muted":false}"""
        val packet = PacketCodec.encodeControl(currentChannelId, seq++, json)
        transport.sendToAll(packet)
    }

    val peerCount: Int get() = activePeers.size + 1 // +1 for self

    fun stop() {
        keepaliveJob?.cancel()
        announceJob?.cancel()
        activePeers.clear()
    }
}
