package com.meshtalk.app.audio

import android.content.Context
import android.util.Log
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VadEngine(context: Context) {
    companion object {
        private const val TAG = "VadEngine"
        const val VAD_FRAME_SIZE = 512  // Required by Silero at 16kHz
    }

    private val vad = VadSilero(
        context,
        sampleRate = SampleRate.SAMPLE_RATE_16K,
        frameSize = FrameSize.FRAME_SIZE_512,
        mode = Mode.NORMAL,
        speechDurationMs = 50,
        silenceDurationMs = 300
    )

    private val accumulator = ShortArray(VAD_FRAME_SIZE)
    private var accumulatedSamples = 0

    /**
     * Feed a 160-sample (10ms) AEC frame. Returns speech probability
     * when enough samples accumulated (512), or null if still accumulating.
     */
    fun feedFrame(aecFrame: ShortArray): Float? {
        if (closed) return null
        val toCopy = minOf(aecFrame.size, VAD_FRAME_SIZE - accumulatedSamples)
        System.arraycopy(aecFrame, 0, accumulator, accumulatedSamples, toCopy)
        accumulatedSamples += toCopy

        if (accumulatedSamples >= VAD_FRAME_SIZE) {
            val isSpeech = vad.isSpeech(shortsToBytes(accumulator))
            accumulatedSamples = 0
            val leftover = aecFrame.size - toCopy
            if (leftover > 0) {
                System.arraycopy(aecFrame, toCopy, accumulator, 0, leftover)
                accumulatedSamples = leftover
            }
            return if (isSpeech) 1.0f else 0.0f
        }
        return null
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in shorts) buf.putShort(s)
        return buf.array()
    }

    private var closed = false

    fun release() {
        if (!closed) {
            closed = true
            try { vad.close() } catch (_: Exception) {}
        }
    }
}
