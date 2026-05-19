package com.meshtalk.app.audio

import android.media.*
import android.util.Log

class AudioPlaybackEngine(
    private val sampleRate: Int = 16000,
    private val volumeBoost: Float = 15f
) {
    companion object { private const val TAG = "AudioPlayback" }

    private var audioTrack: AudioTrack? = null
    var onFramePlayed: ((ShortArray) -> Unit)? = null  // For AEC reference signal

    fun start() {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(minBuf * 4)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
        Log.i(TAG, "Playback started: ${sampleRate}Hz, boost=${volumeBoost}x")
    }

    fun play(pcm: ShortArray) {
        val boosted = applyVolumeBoost(pcm)
        audioTrack?.write(boosted, 0, boosted.size, AudioTrack.WRITE_NON_BLOCKING)
        onFramePlayed?.invoke(boosted)
    }

    private fun applyVolumeBoost(pcm: ShortArray): ShortArray {
        val result = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val sample = (pcm[i].toInt() * volumeBoost).toInt()
            result[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return result
    }

    fun stop() {
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        Log.i(TAG, "Playback stopped")
    }
}
