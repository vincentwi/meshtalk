package com.meshtalk.app.mesh

import java.net.InetAddress

data class MeshPeer(
    val id: String,
    val address: InetAddress,
    val port: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

interface MeshTransport {
    val peers: List<MeshPeer>

    fun start(channelName: String)
    fun stop()
    fun switchChannel(channelName: String)
    fun sendToAll(data: ByteArray)
    fun sendTo(peerId: String, data: ByteArray)

    var onPeerDiscovered: ((MeshPeer) -> Unit)?
    var onPeerLost: ((String) -> Unit)?
    var onDataReceived: ((String, ByteArray) -> Unit)?  // peerId, data
}
