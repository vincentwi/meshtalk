package com.openclaw.app.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.aware.WifiAwareManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * NanSupervisor — self-healing WiFi Aware (NAN) radio manager.
 *
 * Mercury OS on RayNeo X3 Pro ships with `aware_enabled=0` and
 * `on_idle_disable_aware=1`, meaning the NAN radio is off by default
 * and gets killed on doze. This supervisor:
 *
 *  1. Auto-enables aware_enabled=1 via Settings.Secure (requires
 *     WRITE_SECURE_SETTINGS, granted via ADB).
 *  2. Monitors WiFi, WiFi Aware, and doze state via BroadcastReceivers.
 *  3. Drives a radio state machine with exponential-backoff reconnect.
 *  4. Re-enables aware + re-attaches after doze exit or WiFi toggle.
 *  5. Fires state callbacks so the HUD can display radio health.
 */
class NanSupervisor(
    private val context: Context,
    private val transport: WifiAwareTransport
) {

    companion object {
        private const val TAG = "NanSupervisor"
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }

    // ── Radio state machine ──────────────────────────────────────────

    enum class RadioState {
        DISABLED,          // Not started
        WIFI_OFF,          // WiFi radio is off
        AWARE_DISABLED,    // WiFi on but NAN setting is 0
        ATTACHING,         // WifiAwareManager.attach() in flight
        ATTACHED,          // Attached, about to publish/subscribe
        PUBLISHING,        // Publish session active
        DISCOVERING,       // Subscribe session active (fully operational)
        CONNECTED,         // Data-path peer connected
        DOZING,            // Device entered doze — NAN killed by OS
        RECOVERING,        // Reconnect in progress after failure/doze
        FAILED             // Repeated failures — waiting for backoff
    }

    @Volatile
    var state: RadioState = RadioState.DISABLED
        private set

    var onStateChanged: ((RadioState) -> Unit)? = null

    // ── Internal state ───────────────────────────────────────────────

    private var currentChannel = ""
    private var backoffMs = MIN_BACKOFF_MS
    private var consecutiveFailures = 0
    private val handlerThread = HandlerThread("NanSupervisor").also { it.start() }
    private val handler = Handler(handlerThread.looper)
    private var watchdogRunnable: Runnable? = null
    private var started = false

    // ── Broadcast receivers ──────────────────────────────────────────

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)) {
                WifiManager.WIFI_STATE_ENABLED -> {
                    Log.i(TAG, "WiFi turned ON")
                    handler.postDelayed({ onWifiAvailable() }, 500)
                }
                WifiManager.WIFI_STATE_DISABLED -> {
                    Log.w(TAG, "WiFi turned OFF")
                    transition(RadioState.WIFI_OFF)
                    cleanupTransport()
                }
            }
        }
    }

    private val awareReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val mgr = context.getSystemService(WifiAwareManager::class.java)
            if (mgr?.isAvailable == true) {
                Log.i(TAG, "WiFi Aware became available")
                if (state == RadioState.AWARE_DISABLED || state == RadioState.RECOVERING) {
                    attemptAttach()
                }
            } else {
                Log.w(TAG, "WiFi Aware became unavailable")
                if (state != RadioState.DOZING && state != RadioState.WIFI_OFF) {
                    transition(RadioState.AWARE_DISABLED)
                }
            }
        }
    }

    private val dozeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm.isDeviceIdleMode) {
                Log.w(TAG, "Device entered doze — NAN will be killed by Mercury OS")
                transition(RadioState.DOZING)
            } else {
                Log.i(TAG, "Device exited doze — recovering NAN")
                transition(RadioState.RECOVERING)
                handler.postDelayed({ recoverFromDoze() }, 1_000)
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Start the supervisor. Enables WiFi Aware, registers monitors,
     * and kicks off the transport on [channelName].
     */
    fun start(channelName: String) {
        if (started) {
            Log.w(TAG, "Already started, stopping first")
            stop()
        }
        started = true
        currentChannel = channelName
        backoffMs = MIN_BACKOFF_MS
        consecutiveFailures = 0

        // Wire supervisor into transport
        transport.supervisor = this

        registerReceivers()
        startWatchdog()

        // Kick off: ensure WiFi Aware is enabled, then attach
        handler.post { bootstrap() }
    }

    fun stop() {
        started = false
        stopWatchdog()
        unregisterReceivers()
        cleanupTransport()
        transport.supervisor = null
        transition(RadioState.DISABLED)
    }

    fun switchChannel(channelName: String) {
        Log.i(TAG, "Switching channel: $currentChannel → $channelName")
        currentChannel = channelName
        cleanupTransport()
        handler.post { bootstrap() }
    }

    // ── Transport callbacks (called by WifiAwareTransport) ───────────

    fun onTransportAttached() {
        Log.i(TAG, "Transport attached")
        transition(RadioState.ATTACHED)
        resetBackoff()
    }

    fun onTransportAttachFailed() {
        Log.e(TAG, "Transport attach failed")
        scheduleReconnect("attach failed")
    }

    fun onTransportPublishing() {
        Log.i(TAG, "Transport publishing")
        transition(RadioState.PUBLISHING)
    }

    fun onTransportDiscovering() {
        Log.i(TAG, "Transport discovering")
        // Only upgrade state if we were publishing or attached
        if (state == RadioState.PUBLISHING || state == RadioState.ATTACHED) {
            transition(RadioState.DISCOVERING)
        }
    }

    fun onTransportConnected() {
        Log.i(TAG, "Transport peer connected (data path established)")
        transition(RadioState.CONNECTED)
        resetBackoff()
    }

    fun onTransportFailed(reason: String) {
        Log.e(TAG, "Transport failed: $reason")
        scheduleReconnect(reason)
    }

    fun onTransportNetworkLost() {
        Log.w(TAG, "Transport network lost")
        if (state == RadioState.CONNECTED) {
            // Downgrade to discovering — we still have pub/sub sessions
            transition(RadioState.DISCOVERING)
        }
    }

    // ── Bootstrap ────────────────────────────────────────────────────

    private fun bootstrap() {
        if (!started) return

        // Step 1: Check WiFi
        val wifiMgr = context.getSystemService(WifiManager::class.java)
        if (!wifiMgr.isWifiEnabled) {
            Log.w(TAG, "WiFi is off — cannot start NAN")
            transition(RadioState.WIFI_OFF)
            return
        }

        // Step 2: Auto-enable WiFi Aware setting
        if (!ensureAwareEnabled()) {
            transition(RadioState.AWARE_DISABLED)
            return
        }

        // Step 3: Check if WiFi Aware hardware is available
        val awareMgr = context.getSystemService(WifiAwareManager::class.java)
        if (awareMgr == null || !awareMgr.isAvailable) {
            Log.w(TAG, "WiFi Aware not available yet, waiting for broadcast")
            transition(RadioState.AWARE_DISABLED)
            return
        }

        // Step 4: Attach
        attemptAttach()
    }

    /**
     * Auto-enable `aware_enabled=1` in Settings.Secure.
     * Requires WRITE_SECURE_SETTINGS (granted via ADB).
     */
    private fun ensureAwareEnabled(): Boolean {
        try {
            val current = Settings.Secure.getInt(
                context.contentResolver, "aware_enabled", 0
            )
            if (current == 0) {
                val success = Settings.Secure.putInt(
                    context.contentResolver, "aware_enabled", 1
                )
                if (success) {
                    Log.i(TAG, "Auto-enabled WiFi Aware (aware_enabled=1)")
                } else {
                    Log.e(TAG, "Failed to set aware_enabled=1 — WRITE_SECURE_SETTINGS not granted?")
                    return false
                }
            } else {
                Log.d(TAG, "WiFi Aware already enabled (aware_enabled=1)")
            }

            // Also disable the doze kill setting if possible
            try {
                val idleDisable = Settings.Secure.getInt(
                    context.contentResolver, "on_idle_disable_aware", 1
                )
                if (idleDisable == 1) {
                    Settings.Secure.putInt(
                        context.contentResolver, "on_idle_disable_aware", 0
                    )
                    Log.i(TAG, "Disabled on_idle_disable_aware (set to 0)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not modify on_idle_disable_aware: ${e.message}")
            }

            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "WRITE_SECURE_SETTINGS not granted: ${e.message}")
            Log.e(TAG, "Run: adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling WiFi Aware: ${e.message}")
            return false
        }
    }

    private fun attemptAttach() {
        if (!started) return
        transition(RadioState.ATTACHING)
        try {
            transport.start(currentChannel)
        } catch (e: Exception) {
            Log.e(TAG, "Transport start threw: ${e.message}")
            scheduleReconnect("start exception: ${e.message}")
        }
    }

    // ── Doze recovery ────────────────────────────────────────────────

    private fun recoverFromDoze() {
        if (!started) return
        Log.i(TAG, "Recovering from doze — re-enabling aware and re-attaching")

        // Mercury OS sets aware_enabled=0 on doze with on_idle_disable_aware=1
        // Re-enable it
        ensureAwareEnabled()

        // Give the NAN radio a moment to come up after re-enable
        handler.postDelayed({
            if (started && state == RadioState.RECOVERING) {
                cleanupTransport()
                bootstrap()
            }
        }, 2_000)
    }

    // ── WiFi state ───────────────────────────────────────────────────

    private fun onWifiAvailable() {
        if (!started) return
        if (state == RadioState.WIFI_OFF || state == RadioState.AWARE_DISABLED) {
            Log.i(TAG, "WiFi available — bootstrapping NAN")
            bootstrap()
        }
    }

    // ── Reconnect with backoff ───────────────────────────────────────

    private fun scheduleReconnect(reason: String) {
        if (!started) return
        consecutiveFailures++

        if (consecutiveFailures > 10) {
            Log.e(TAG, "Too many consecutive failures ($consecutiveFailures), staying in FAILED")
            transition(RadioState.FAILED)
            // Still schedule a retry but at max backoff
            backoffMs = MAX_BACKOFF_MS
        } else {
            transition(RadioState.RECOVERING)
        }

        Log.w(TAG, "Scheduling reconnect in ${backoffMs}ms (attempt $consecutiveFailures, reason: $reason)")

        handler.postDelayed({
            if (started && (state == RadioState.RECOVERING || state == RadioState.FAILED)) {
                cleanupTransport()
                bootstrap()
            }
        }, backoffMs)

        // Exponential backoff: 1s → 2s → 4s → 8s → 16s → 30s cap
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun resetBackoff() {
        backoffMs = MIN_BACKOFF_MS
        consecutiveFailures = 0
    }

    // ── Watchdog ─────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogRunnable = object : Runnable {
            override fun run() {
                if (!started) return
                checkHealth()
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
        handler.postDelayed(watchdogRunnable!!, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun checkHealth() {
        // Check if WiFi Aware is still available
        val awareMgr = context.getSystemService(WifiAwareManager::class.java)

        when {
            // WiFi off
            !(context.getSystemService(WifiManager::class.java)?.isWifiEnabled ?: false) -> {
                if (state != RadioState.WIFI_OFF && state != RadioState.DISABLED) {
                    Log.w(TAG, "Watchdog: WiFi went off")
                    transition(RadioState.WIFI_OFF)
                    cleanupTransport()
                }
            }

            // Aware disappeared (doze or system killed it)
            awareMgr == null || !awareMgr.isAvailable -> {
                if (state == RadioState.CONNECTED || state == RadioState.DISCOVERING ||
                    state == RadioState.PUBLISHING || state == RadioState.ATTACHED) {
                    Log.w(TAG, "Watchdog: WiFi Aware disappeared while in $state")
                    scheduleReconnect("watchdog: aware unavailable")
                }
            }

            // Check if aware_enabled got flipped back to 0 (Mercury OS quirk)
            else -> {
                try {
                    val enabled = Settings.Secure.getInt(
                        context.contentResolver, "aware_enabled", 0
                    )
                    if (enabled == 0) {
                        Log.w(TAG, "Watchdog: aware_enabled was reset to 0, re-enabling")
                        ensureAwareEnabled()
                    }
                } catch (_: Exception) {}
            }
        }

        // Log periodic state for debugging
        Log.d(TAG, "Watchdog: state=$state, failures=$consecutiveFailures, peers=${transport.peers.size}")
    }

    // ── State transitions ────────────────────────────────────────────

    private fun transition(newState: RadioState) {
        if (state == newState) return
        val oldState = state
        state = newState
        Log.i(TAG, "Radio: $oldState → $newState")
        try {
            onStateChanged?.invoke(newState)
        } catch (e: Exception) {
            Log.e(TAG, "State callback error: ${e.message}")
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    private fun cleanupTransport() {
        try {
            transport.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Transport cleanup error: ${e.message}")
        }
    }

    // ── Receiver management ──────────────────────────────────────────

    private fun registerReceivers() {
        context.registerReceiver(
            wifiReceiver,
            IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
            null, handler
        )
        context.registerReceiver(
            awareReceiver,
            IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED),
            null, handler
        )
        context.registerReceiver(
            dozeReceiver,
            IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
            null, handler
        )
        Log.d(TAG, "Broadcast receivers registered")
    }

    private fun unregisterReceivers() {
        try { context.unregisterReceiver(wifiReceiver) } catch (_: Exception) {}
        try { context.unregisterReceiver(awareReceiver) } catch (_: Exception) {}
        try { context.unregisterReceiver(dozeReceiver) } catch (_: Exception) {}
        Log.d(TAG, "Broadcast receivers unregistered")
    }
}
