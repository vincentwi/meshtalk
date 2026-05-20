package com.openclaw.app.audio

import android.util.Log

class SpeexAec(
    private val frameSize: Int = 160,
    private val filterLength: Int = 1600,
    private val sampleRate: Int = 16000
) {
    companion object {
        private const val TAG = "SpeexAec"
        init { System.loadLibrary("speex_aec_jni") }
    }
    private var initialized = false
    private var referenceBuffer = ShortArray(frameSize)

    fun init(): Boolean {
        val result = nativeInit(frameSize, filterLength, sampleRate)
        initialized = result == 0
        if (!initialized) Log.e(TAG, "Init failed")
        return initialized
    }

    fun feedReference(speakerPcm: ShortArray) { if (speakerPcm.size == frameSize) referenceBuffer = speakerPcm.copyOf() }
    fun process(micPcm: ShortArray): ShortArray { if (!initialized) return micPcm; return nativeProcess(micPcm, referenceBuffer) ?: micPcm }
    fun release() { if (initialized) { nativeRelease(); initialized = false } }

    private external fun nativeInit(frameSize: Int, filterLength: Int, sampleRate: Int): Int
    private external fun nativeProcess(micData: ShortArray, speakerData: ShortArray): ShortArray?
    private external fun nativeRelease()
}
