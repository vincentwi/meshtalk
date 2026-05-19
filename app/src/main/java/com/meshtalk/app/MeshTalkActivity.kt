package com.meshtalk.app

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
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.temple.TempleAction
import com.ffalcon.mercury.android.sdk.view.BaseMirrorActivity
import com.meshtalk.app.databinding.ActivityMeshtalkBinding
import com.meshtalk.app.hud.HudRenderer
import com.meshtalk.app.service.MeshTalkService
import com.meshtalk.app.vox.VoxState
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBindingPair.updateView {
            // Hide the status text — HUD WebView takes over
            tvStatus.visibility = View.GONE

            // Create HUD renderer from the WebView in layout
            hudRenderer = HudRenderer(wvHud)
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
                            // Ignore other gesture types
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
