package com.openclaw.app.audio

import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * SpatialAudioEngine — mono spatial audio processor for bone-conduction speakers.
 *
 * Since bone conduction is mono (no L/R stereo), spatial cues are achieved via:
 *  • Distance attenuation: RSSI-based volume scaling (closer = louder)
 *  • Head-relative filtering: low-pass when source is behind head, full spectrum in front
 *  • Proximity bass boost: very close peers (RSSI > -40) get slight bass emphasis
 *
 * Per-peer state is tracked so multiple simultaneous speakers each get independent
 * spatial processing based on their own RSSI and head orientation.
 */
class SpatialAudioEngine(private val sampleRate: Int = 16000) {

    companion object {
        private const val TAG = "SpatialAudio"

        // RSSI mapping: -30 dBm (touching) → gain 1.0,  -90 dBm (far) → gain 0.1
        private const val RSSI_NEAR = -30
        private const val RSSI_FAR = -90
        private const val GAIN_NEAR = 1.0f
        private const val GAIN_FAR = 0.1f

        // Proximity threshold for bass boost
        private const val RSSI_PROXIMITY = -40

        // Low-pass filter strength (0 = bypass, 1 = full LPF)
        private const val LPF_STRENGTH = 0.7f

        // LPF alpha for ~2 kHz cutoff at 16 kHz sample rate
        // alpha = 2π·fc / (2π·fc + fs)  ≈  2π·2000 / (2π·2000 + 16000) ≈ 0.44
        private const val LPF_ALPHA = 0.44f

        // Bass boost: 1-pole LPF at ~300 Hz, mixed back in at ~20% boost
        // alpha = 2π·300 / (2π·300 + 16000) ≈ 0.105
        private const val BASS_ALPHA = 0.105f
        private const val BASS_BOOST_GAIN = 0.20f
    }

    /** Current listener head orientation from IMU (degrees, 0 = north, clockwise). */
    @Volatile var listenerYaw: Float = 0f

    /** Current listener head pitch from IMU (degrees). */
    @Volatile var listenerPitch: Float = 0f

    /** Per-peer spatial state. */
    private data class PeerSpatial(
        var rssi: Int = -60,
        var peerYaw: Float = 0f,          // peer's own heading (degrees)
        var lpfState: Float = 0f,         // 1-pole LPF memory (behind-head filter)
        var bassState: Float = 0f,        // 1-pole LPF memory (proximity bass)
        var lastUpdateMs: Long = 0L
    )

    private val peers = mutableMapOf<String, PeerSpatial>()

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Update spatial metadata for a peer.
     * Called when a control message arrives with the peer's RSSI and heading.
     */
    fun updatePeerSpatial(peerId: String, rssi: Int, peerYaw: Float) {
        val state = peers.getOrPut(peerId) { PeerSpatial() }
        state.rssi = rssi.coerceIn(-100, 0)
        state.peerYaw = peerYaw
        state.lastUpdateMs = System.currentTimeMillis()
    }

    /**
     * Process a mono PCM16 frame with spatial effects for the given peer.
     * Returns a new ShortArray with distance attenuation, directional filtering,
     * and proximity bass boost applied.
     */
    fun processSpatial(peerId: String, pcm: ShortArray): ShortArray {
        val state = peers[peerId] ?: return pcm  // no spatial data yet — pass through

        val gain = mapRssiToGain(state.rssi)
        val relAngleRad = computeRelativeAngleRad(state)
        val behindFactor = (1f - cos(relAngleRad).toFloat()) / 2f  // 0=front, 1=behind
        val isProximity = state.rssi > RSSI_PROXIMITY

        val result = ShortArray(pcm.size)
        var lpfY = state.lpfState
        var bassY = state.bassState

        for (i in pcm.indices) {
            val x = pcm[i].toFloat()

            // 1-pole low-pass for behind-head muffling
            lpfY = LPF_ALPHA * x + (1f - LPF_ALPHA) * lpfY

            // Blend: full spectrum when in front, LPF when behind
            val blendStr = behindFactor * LPF_STRENGTH
            var sample = x * (1f - blendStr) + lpfY * blendStr

            // Apply distance gain
            sample *= gain

            // Proximity bass boost for very close peers
            if (isProximity) {
                bassY = BASS_ALPHA * sample + (1f - BASS_ALPHA) * bassY
                sample += bassY * BASS_BOOST_GAIN
            } else {
                bassY = BASS_ALPHA * sample + (1f - BASS_ALPHA) * bassY
            }

            result[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        // Persist filter state for continuity across frames
        state.lpfState = lpfY
        state.bassState = bassY

        return result
    }

    /**
     * Get the relative direction to a peer in degrees (0 = dead ahead, ±180 = behind).
     * Returns null if peer has no spatial data.
     */
    fun getPeerDirection(peerId: String): Float? {
        val state = peers[peerId] ?: return null
        return computeRelativeAngleDeg(state)
    }

    /**
     * Get normalized distance to a peer (0.0 = touching, 1.0 = far).
     * Based on RSSI mapping. Returns null if peer has no spatial data.
     */
    fun getPeerDistance(peerId: String): Float? {
        val state = peers[peerId] ?: return null
        // Invert gain mapping: near(1.0) → 0.0, far(0.1) → 1.0
        val gain = mapRssiToGain(state.rssi)
        return 1f - ((gain - GAIN_FAR) / (GAIN_NEAR - GAIN_FAR)).coerceIn(0f, 1f)
    }

    /** Remove peer state (when peer disconnects). */
    fun removePeer(peerId: String) {
        peers.remove(peerId)
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    /**
     * Map RSSI to gain: linear interpolation.
     * -30 dBm → 1.0 (very close), -90 dBm → 0.1 (far away)
     */
    private fun mapRssiToGain(rssi: Int): Float {
        val clamped = rssi.coerceIn(RSSI_FAR, RSSI_NEAR)
        val t = (clamped - RSSI_FAR).toFloat() / (RSSI_NEAR - RSSI_FAR).toFloat()
        return GAIN_FAR + t * (GAIN_NEAR - GAIN_FAR)
    }

    /**
     * Compute relative angle between listener and peer in radians [0, π].
     *
     * Direction estimation: if the listener faces toward the peer (their yaws are
     * ~180° apart, meaning they face each other), the peer is "in front" (angle ≈ 0).
     * If the listener faces the same direction as the peer (yaws similar), the peer
     * is "behind" (angle ≈ π).
     *
     * This is a simplification — we don't know absolute positions, only headings.
     * The heuristic: two people facing each other means the peer is ahead of you.
     */
    private fun computeRelativeAngleRad(state: PeerSpatial): Double {
        // Difference in heading — if ~180° apart they face each other
        var diff = abs(listenerYaw - state.peerYaw) % 360f
        if (diff > 180f) diff = 360f - diff

        // diff ≈ 180 → facing each other → peer is in front → relative angle = 0
        // diff ≈ 0   → facing same way → peer is behind → relative angle = π
        val relDeg = 180f - diff  // 0 = front, 180 = behind
        return Math.toRadians(relDeg.toDouble().coerceIn(0.0, 180.0))
    }

    /**
     * Compute relative angle in degrees for HUD display.
     * 0 = directly ahead, positive = clockwise, range [-180, 180].
     */
    private fun computeRelativeAngleDeg(state: PeerSpatial): Float {
        // Signed difference: where is the peer relative to listener's heading?
        // The peer's "position" relative to us is approximated by their facing direction + 180°
        // (if they face us, they're in front of us)
        val peerApparentBearing = (state.peerYaw + 180f) % 360f
        var delta = peerApparentBearing - listenerYaw
        // Normalize to [-180, 180]
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return delta
    }
}
