package com.meshtalk.app.audio

import java.util.concurrent.ConcurrentHashMap

class AudioMixer {
    private val peerBuffers = ConcurrentHashMap<String, ShortArray>()

    fun submitFrame(peerId: String, pcm: ShortArray) { peerBuffers[peerId] = pcm }
    fun removePeer(peerId: String) { peerBuffers.remove(peerId) }

    fun mix(frameSize: Int): ShortArray? {
        if (peerBuffers.isEmpty()) return null
        val mixed = ShortArray(frameSize)
        for ((_, buffer) in peerBuffers) {
            val len = minOf(buffer.size, frameSize)
            for (i in 0 until len) {
                val sum = mixed[i].toInt() + buffer[i].toInt()
                mixed[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return mixed
    }

    fun clear() { peerBuffers.clear() }
    val peerCount: Int get() = peerBuffers.size
}
