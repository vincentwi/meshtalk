package com.openclaw.app

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.openclaw.app.databinding.ActivityMeshtalkBinding
import com.openclaw.app.hud.HudRenderer
import com.openclaw.app.service.MeshTalkService
import com.openclaw.app.vox.VoxState
import kotlinx.coroutines.launch

/**
 * MeshTalkActivity — main AR activity for the MeshTalk walkie-talkie.
 *
 * Binds to [MeshTalkService], wires RayNeo temple gestures to
 * toggle-active / switch-channel / toggle-mute / exit, and drives
 * the HUD overlay via [HudRenderer].
 */
class MeshTalkActivity : BaseMirrorActivity<ActivityMeshtalkBinding>() {

    companion object {
        private const val TAG = "MeshTalkActivity"
    }

    private lateinit var hudRenderer: HudRenderer
    private var service: MeshTalkService? = null
    private var bound = false

    // ── Fallback double-tap detector ────────────────────────────────────
    // The SDK's TempleActionViewModel sometimes doesn't fire DoubleClick
    // when the WebView consumes touch events. This raw detector catches
    // double-taps directly from dispatchTouchEvent as a safety net.
    private var lastTapTime = 0L
    private val doubleTapThresholdMs = 400L  // max gap between taps

    // ── Service connection ────────────────────────────────────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as MeshTalkService.LocalBinder
            service = localBinder.getService()
            bound = true
            Log.i(TAG, "Service bound")
            setupServiceCallbacks()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            Log.w(TAG, "Service disconnected")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBindingPair.updateView {
            // Hide the status text — HUD WebView takes over
            tvStatus.visibility = View.GONE

            // Create HUD renderer from the WebView in layout
            hudRenderer = HudRenderer(wvHud)

            // CRITICAL: Prevent WebView from consuming touch events.
            // The HUD is display-only — it must not intercept taps that
            // the temple gesture detector needs to see.
            wvHud.isClickable = false
            wvHud.isFocusable = false
            wvHud.isFocusableInTouchMode = false
            wvHud.setOnTouchListener { _, _ -> false }  // pass through
        }

        // Start and bind to the foreground service
        val serviceIntent = Intent(this, MeshTalkService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Wire temple gestures
        setupTempleGestures()

        Log.i(TAG, "onCreate complete")
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        exitApp()
    }

    /**
     * Intercept ALL touch events before any child view can consume them.
     * This ensures the SDK's TouchDispatcher always sees taps, AND provides
     * a fallback double-tap detector in case the SDK's TempleActionViewModel
     * fails to fire DoubleClick.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_UP) {
            val now = System.currentTimeMillis()
            val gap = now - lastTapTime
            Log.d(TAG, "Touch UP — gap=${gap}ms (threshold=${doubleTapThresholdMs}ms)")
            if (gap in 1..doubleTapThresholdMs) {
                Log.i(TAG, "Fallback double-tap detected → exitApp()")
                lastTapTime = 0L
                exitApp()
                return true  // consume — we're exiting
            }
            lastTapTime = now
        }
        // Always let the parent handle the event so the SDK's gesture
        // detector still works for single-tap, slide, etc.
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        if (bound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "unbindService failed", e)
            }
            bound = false
        }
        super.onDestroy()
    }

    // ── Temple gesture handling ───────────────────────────────────────────

    private fun setupTempleGestures() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    when (action) {
                        is TempleAction.Click -> {
                            Log.d(TAG, "Gesture: Click -> toggleActive")
                            val active = service?.toggleActive() ?: false
                            hudRenderer.updateStatus(if (active) "ACTIVE" else "STANDBY")
                        }

                        is TempleAction.SlideContinuous -> {
                            val delta = action.delta
                            if (delta > 0.1f) {
                                Log.d(TAG, "Gesture: SlideForward -> switchChannel")
                                vibrateShort()
                                service?.switchChannel()
                            } else if (delta < -0.1f) {
                                Log.d(TAG, "Gesture: SlideBack -> toggleMute")
                                vibrateShort()
                                val muted = service?.toggleMute() ?: false
                                hudRenderer.updateMute(muted)
                            }
                        }

                        is TempleAction.DoubleClick -> {
                            Log.d(TAG, "Gesture: DoubleClick -> exitApp")
                            exitApp()
                        }

                        is TempleAction.TripleClick -> {
                            Log.d(TAG, "Gesture: TripleClick -> exitApp")
                            exitApp()
                        }

                        else -> {
                            Log.d(TAG, "Gesture: ${action::class.simpleName} (ignored)")
                        }
                    }
                }
            }
        }
    }

    // ── Service callbacks → HUD ───────────────────────────────────────────

    private fun setupServiceCallbacks() {
        val svc = service ?: return

        svc.onVoxStateChanged = { voxState ->
            runOnUiThread {
                hudRenderer.updateVox(voxState != VoxState.IDLE)
            }
        }

        svc.onPeerCountChanged = { count ->
            runOnUiThread {
                hudRenderer.updateUserCount(count)
            }
        }

        svc.onChannelChanged = { channelName ->
            runOnUiThread {
                hudRenderer.updateChannel(channelName)
            }
        }

        svc.onPeerDirectionChanged = { angle, distance ->
            runOnUiThread {
                hudRenderer.updatePeerDirection(angle, distance)
            }
        }

        svc.onBleStateChanged = { state ->
            runOnUiThread {
                hudRenderer.updateBleState(state)
            }
        }
    }

    // ── Exit ──────────────────────────────────────────────────────────────

    private fun exitApp() {
        Log.i(TAG, "exitApp()")

        // Stop the audio pipeline
        service?.stopPipeline()

        // Unbind from service
        if (bound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "unbindService failed in exitApp", e)
            }
            bound = false
        }

        // Stop the foreground service
        val serviceIntent = Intent(this, MeshTalkService::class.java)
        stopService(serviceIntent)

        // Cancel all notifications
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()

        // Close all activities
        finishAffinity()

        // Kill the process after a short delay to allow cleanup
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 200)
    }

    // ── Haptic feedback ──────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun vibrateShort() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    vibrator.vibrate(50)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }
}
