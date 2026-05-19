package com.meshtalk.app.mesh

import android.util.Log

data class Channel(
    val id: Int,
    val serviceName: String,
    val displayName: String
)

class ChannelManager(
    private val transport: MeshTransport,
    private val peerManager: PeerManager
) {
    companion object {
        private const val TAG = "ChannelManager"
        val CHANNELS = listOf(
            Channel(0, "meshtalk_alpha", "Alpha"),
            Channel(1, "meshtalk_bravo", "Bravo")
        )
    }

    var currentChannel: Channel = CHANNELS[0]
        private set

    var onChannelChanged: ((Channel) -> Unit)? = null

    fun joinChannel(channelId: Int) {
        val channel = CHANNELS.getOrNull(channelId) ?: return
        currentChannel = channel
        peerManager.currentChannelId = channelId
        transport.start(channel.serviceName)
        peerManager.sendAnnounce()
        onChannelChanged?.invoke(channel)
        Log.i(TAG, "Joined channel: ${channel.displayName}")
    }

    fun switchChannel() {
        val nextId = (currentChannel.id + 1) % CHANNELS.size
        val nextChannel = CHANNELS[nextId]
        Log.i(TAG, "Switching: ${currentChannel.displayName} → ${nextChannel.displayName}")
        currentChannel = nextChannel
        peerManager.currentChannelId = nextId
        transport.switchChannel(nextChannel.serviceName)
        peerManager.sendAnnounce()
        onChannelChanged?.invoke(nextChannel)
    }

    fun leaveChannel() {
        transport.stop()
        Log.i(TAG, "Left channel: ${currentChannel.displayName}")
    }
}
