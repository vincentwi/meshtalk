package com.openclaw.app.mesh
import org.junit.Assert.*
import org.junit.Test

class PacketCodecTest {
    @Test fun encodeDecodeAudioPacket() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = PacketCodec.encodeAudio(channelId = 0, seq = 42, payload = payload)
        assertEquals(6 + 5, encoded.size)
        assertEquals(PacketCodec.TYPE_AUDIO, encoded[0])
        val decoded = PacketCodec.decode(encoded)!!
        assertEquals(PacketCodec.TYPE_AUDIO, decoded.type); assertEquals(0, decoded.channelId); assertEquals(42, decoded.seq)
        assertArrayEquals(payload, decoded.payload)
    }
    @Test fun encodeDecodeControlPacket() {
        val json = """{"cmd":"announce","user":"glass_A"}"""
        val encoded = PacketCodec.encodeControl(channelId = 1, seq = 7, json = json)
        val decoded = PacketCodec.decode(encoded)!!
        assertEquals(PacketCodec.TYPE_CONTROL, decoded.type); assertEquals(1, decoded.channelId)
        assertEquals(json, String(decoded.payload))
    }
    @Test fun encodeDecodePing() {
        val ping = PacketCodec.encodePing(channelId = 0, seq = 99)
        assertEquals(6, ping.size)
        val decoded = PacketCodec.decode(ping)!!
        assertEquals(PacketCodec.TYPE_PING, decoded.type); assertEquals(99, decoded.seq)
    }
    @Test fun rejectsShortPackets() { assertNull(PacketCodec.decode(byteArrayOf(1, 2, 3))) }
}
