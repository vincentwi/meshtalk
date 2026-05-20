package com.openclaw.app.vox

enum class VoxState { IDLE, SPEAKING, HANGOVER }

class VoxStateMachine(
    private val startThreshold: Float = 0.45f,
    private val stopThreshold: Float = 0.30f,
    private val onsetMs: Int = 200,
    private val hangoverMs: Int = 700
) {
    var state: VoxState = VoxState.IDLE
        private set
    var muted: Boolean = false
    val shouldTransmit: Boolean
        get() = !muted && (state == VoxState.SPEAKING || state == VoxState.HANGOVER)
    private var speechAccumulatorMs: Int = 0
    private var silenceAccumulatorMs: Int = 0
    var onStateChanged: ((VoxState) -> Unit)? = null

    fun onVadResult(probability: Float, frameDurationMs: Int) {
        if (muted) { if (state != VoxState.IDLE) transition(VoxState.IDLE); return }
        val isSpeech = probability >= startThreshold
        val isSilence = probability < stopThreshold
        when (state) {
            VoxState.IDLE -> {
                if (isSpeech) { speechAccumulatorMs += frameDurationMs; if (speechAccumulatorMs >= onsetMs) { transition(VoxState.SPEAKING); speechAccumulatorMs = 0 } }
                else { speechAccumulatorMs = 0 }
            }
            VoxState.SPEAKING -> { if (isSilence) { silenceAccumulatorMs = frameDurationMs; transition(VoxState.HANGOVER) } }
            VoxState.HANGOVER -> {
                if (isSpeech) { silenceAccumulatorMs = 0; transition(VoxState.SPEAKING) }
                else { silenceAccumulatorMs += frameDurationMs; if (silenceAccumulatorMs >= hangoverMs) { silenceAccumulatorMs = 0; transition(VoxState.IDLE) } }
            }
        }
    }
    private fun transition(newState: VoxState) { if (state != newState) { state = newState; onStateChanged?.invoke(newState) } }
    fun reset() { state = VoxState.IDLE; speechAccumulatorMs = 0; silenceAccumulatorMs = 0 }
}
