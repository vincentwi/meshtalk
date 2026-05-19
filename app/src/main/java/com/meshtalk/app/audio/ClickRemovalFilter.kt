package com.meshtalk.app.audio

class ClickRemovalFilter(private val threshold: Int = 3500, private val windowSize: Int = 32) {
    private val history = ShortArray(windowSize)
    private var historyPos = 0

    fun process(frame: ShortArray): ShortArray {
        val result = frame.copyOf()
        for (i in result.indices) {
            val sample = result[i].toInt()
            val avg = history.map { it.toInt() }.sum() / windowSize
            val diff = kotlin.math.abs(sample - avg)
            if (diff > threshold) { result[i] = avg.toShort() }
            history[historyPos] = result[i]
            historyPos = (historyPos + 1) % windowSize
        }
        return result
    }
}
