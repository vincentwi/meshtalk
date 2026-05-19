package com.meshtalk.app.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * HeadTracker — IMU-based head orientation tracking for RayNeo X3 Pro.
 *
 * Uses TYPE_ROTATION_VECTOR (fused gyro + accel + mag at ~200 Hz on XR2)
 * and converts to euler angles (yaw / pitch / roll) via SensorManager.
 *
 * A complementary filter (alpha = 0.85) smooths the output. A recenter()
 * method stores the current yaw as a reference offset so the user can
 * zero-out their heading at any time.
 *
 * Exposes orientation as a StateFlow for reactive collection.
 */
class HeadTracker(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "HeadTracker"

        /** Complementary filter coefficient — 0.85 favours new sensor data, smooths jitter. */
        private const val ALPHA = 0.85f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Smoothed orientation in degrees
    @Volatile var headYaw: Float = 0f       // 0-360, clockwise from north
        private set
    @Volatile var headPitch: Float = 0f     // degrees, nose-up positive
        private set
    @Volatile var headRoll: Float = 0f      // degrees, right-ear-down positive
        private set

    /** Reference yaw stored by recenter(). Subtracted from raw yaw. */
    @Volatile private var yawOffset: Float = 0f

    /** Raw (unfiltered) euler from last sensor event. */
    private var rawYaw: Float = 0f
    private var rawPitch: Float = 0f
    private var rawRoll: Float = 0f
    private var initialised = false

    // Scratch arrays — avoid allocation in the hot path
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Reactive flow for observers who want euler updates. */
    data class Orientation(val yaw: Float, val pitch: Float, val roll: Float)

    private val _orientationFlow = MutableStateFlow(Orientation(0f, 0f, 0f))
    val orientationFlow: StateFlow<Orientation> = _orientationFlow

    // ── Public API ───────────────────────────────────────────────────────

    /** Register sensor listener. Call from onStart / service init. */
    fun start() {
        if (rotationSensor == null) {
            Log.w(TAG, "TYPE_ROTATION_VECTOR sensor not available")
            return
        }
        sensorManager.registerListener(
            this, rotationSensor, SensorManager.SENSOR_DELAY_GAME  // ~200 Hz on XR2
        )
        Log.i(TAG, "HeadTracker started (rotation vector sensor)")
    }

    /** Unregister sensor listener. Call from onStop / service destroy. */
    fun stop() {
        sensorManager.unregisterListener(this)
        Log.i(TAG, "HeadTracker stopped")
    }

    /**
     * Store current yaw as the reference "forward" direction.
     * Subsequent headYaw values are relative to this reference.
     */
    fun recenter() {
        yawOffset = rawYaw
        Log.i(TAG, "Recentered: yaw offset = $yawOffset°")
    }

    // ── SensorEventListener ──────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        // Convert rotation vector → rotation matrix → euler angles
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        // orientation[0] = azimuth (yaw), [1] = pitch, [2] = roll — all in radians
        val newYaw = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)
        val newPitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        val newRoll = Math.toDegrees(orientation[2].toDouble()).toFloat()

        if (!initialised) {
            // Seed the filter with the first reading
            rawYaw = newYaw
            rawPitch = newPitch
            rawRoll = newRoll
            initialised = true
        }

        // Complementary filter: smooth = α * new + (1-α) * old
        // Special handling for yaw wrap-around (0↔360)
        rawYaw = lerpAngle(rawYaw, newYaw, ALPHA)
        rawPitch = ALPHA * newPitch + (1f - ALPHA) * rawPitch
        rawRoll = ALPHA * newRoll + (1f - ALPHA) * rawRoll

        // Apply recenter offset to yaw
        headYaw = ((rawYaw - yawOffset) + 360f) % 360f
        headPitch = rawPitch
        headRoll = rawRoll

        _orientationFlow.value = Orientation(headYaw, headPitch, headRoll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Interpolate between two angles with wrap-around handling.
     * Ensures smooth transition across the 0/360 boundary.
     */
    private fun lerpAngle(from: Float, to: Float, alpha: Float): Float {
        var delta = to - from
        // Shortest path
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return ((from + alpha * delta) + 360f) % 360f
    }
}
