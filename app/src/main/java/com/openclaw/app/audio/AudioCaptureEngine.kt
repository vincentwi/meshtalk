package com.openclaw.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class AudioCaptureEngine {
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16000
        const val AEC_FRAME_SIZE = 160  // 10ms for Speex AEC
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val _audioFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
    val audioFrames: SharedFlow<ShortArray> = _audioFrames
    private val captureDispatcher = Dispatchers.Default.limitedParallelism(1)

    fun start() {
        Log.i(TAG, "start() called")
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        Log.i(TAG, "minBuf=$minBuf")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
        ).also {
            if (it.state != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "AudioRecord init failed, state=${it.state}"); return }
            it.startRecording()
            Log.i(TAG, "AudioRecord startRecording OK")
        }
        captureJob = CoroutineScope(captureDispatcher).launch {
            val buffer = ShortArray(AEC_FRAME_SIZE)
            Log.i(TAG, "Capture started: ${SAMPLE_RATE}Hz, frame=$AEC_FRAME_SIZE")
            while (isActive) {
                val read = audioRecord?.read(buffer, 0, AEC_FRAME_SIZE) ?: break
                if (read == AEC_FRAME_SIZE) { _audioFrames.emit(buffer.copyOf()) }
            }
        }
    }

    fun stop() {
        captureJob?.cancel(); captureJob = null
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        Log.i(TAG, "Capture stopped")
    }
}
