package com.openclaw.app.audio

import android.util.Log

class OpusCodec(
    private val sampleRate: Int = 16000,
    private val channels: Int = 1
) {
    companion object {
        private const val TAG = "OpusCodec"
        const val FRAME_SIZE_20MS = 320 // 16000 * 0.020
        init { System.loadLibrary("opus_jni") }
    }
    private var initialized = false

    fun init(): Boolean {
        val result = nativeInit(sampleRate, channels)
        initialized = result == 0
        if (!initialized) Log.e(TAG, "Init failed with code $result")
        return initialized
    }

    fun encode(pcm: ShortArray, frameSize: Int = FRAME_SIZE_20MS): ByteArray? {
        if (!initialized) return null
        return nativeEncode(pcm, frameSize)
    }

    fun decode(opusData: ByteArray, frameSize: Int = FRAME_SIZE_20MS): ShortArray? {
        if (!initialized) return null
        return nativeDecode(opusData, frameSize)
    }

    fun release() { if (initialized) { nativeRelease(); initialized = false } }

    private external fun nativeInit(sampleRate: Int, channels: Int): Int
    private external fun nativeEncode(pcmData: ShortArray, frameSize: Int): ByteArray?
    private external fun nativeDecode(opusData: ByteArray, frameSize: Int): ShortArray?
    private external fun nativeRelease()
}
