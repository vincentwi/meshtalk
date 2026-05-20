package com.openclaw.app.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MeshPacket(
    val type: Byte, val channelId: Int, val seq: Int, val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true; if (other !is MeshPacket) return false
        return type == other.type && channelId == other.channelId && seq == other.seq && payload.contentEquals(other.payload)
    }
    override fun hashCode() = 31 * type.hashCode() + channelId + seq + payload.contentHashCode()
}

object PacketCodec {
    const val HEADER_SIZE = 6
    const val TYPE_AUDIO: Byte = 0x01
    const val TYPE_CONTROL: Byte = 0x02
    const val TYPE_PING: Byte = 0x03
    const val TYPE_PONG: Byte = 0x04

    fun encodeAudio(channelId: Int, seq: Int, payload: ByteArray): ByteArray = encode(TYPE_AUDIO, channelId, seq, payload)
    fun encodeControl(channelId: Int, seq: Int, json: String): ByteArray = encode(TYPE_CONTROL, channelId, seq, json.toByteArray(Charsets.UTF_8))
    fun encodePing(channelId: Int, seq: Int): ByteArray = encode(TYPE_PING, channelId, seq, ByteArray(0))
    fun encodePong(channelId: Int, seq: Int): ByteArray = encode(TYPE_PONG, channelId, seq, ByteArray(0))

    fun decode(data: ByteArray): MeshPacket? {
        if (data.size < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val type = buf.get(); val channelId = buf.get().toInt() and 0xFF; val seq = buf.getInt()
        val payload = ByteArray(data.size - HEADER_SIZE); if (payload.isNotEmpty()) buf.get(payload)
        return MeshPacket(type, channelId, seq, payload)
    }

    private fun encode(type: Byte, channelId: Int, seq: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(type); buf.put(channelId.toByte()); buf.putInt(seq); buf.put(payload)
        return buf.array()
    }
}
