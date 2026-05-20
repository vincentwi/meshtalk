package com.openclaw.app.hud

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * HudRenderer — wraps a WebView to display the MeshTalk AR HUD overlay.
 *
 * Loads a single-file HTML HUD from assets and exposes typed helpers
 * (updateChannel, updateVox, …) that forward to window.HUD.* via JS.
 * Calls made before the page finishes loading are buffered and flushed
 * automatically in onPageFinished.
 */
class HudRenderer(private val webView: WebView) {

    companion object {
        private const val TAG = "HudRenderer"
        private const val HUD_URL = "file:///android_asset/hud/meshtalk.html"
    }

    /** True once onPageFinished fires — guards direct evaluateJavascript. */
    @Volatile
    private var pageReady = false

    /** JS calls queued before the page is ready. */
    private val pendingJs = mutableListOf<String>()

    // ── Initialisation ──────────────────────────────────────────────────

    init {
        configureWebView()
        webView.loadUrl(HUD_URL)
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configureWebView() {
        // Transparent so the camera / world shows through
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // Native bridge so the HTML can signal readiness
        webView.addJavascriptInterface(Bridge(), "NativeBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                flushPendingJs()
            }
        }
    }

    // ── JavaScript bridge ───────────────────────────────────────────────

    inner class Bridge {
        @JavascriptInterface
        fun onWebViewReady() {
            Log.d(TAG, "WebView signalled ready")
        }
    }

    // ── Public HUD API ──────────────────────────────────────────────────

    fun updateChannel(name: String) {
        runJs("HUD.updateChannel('${escapeJs(name)}')")
    }

    fun updateUserCount(count: Int) {
        runJs("HUD.updateUserCount($count)")
    }

    fun updateVox(active: Boolean) {
        runJs("HUD.updateVox(${active})")
    }

    fun updateMute(muted: Boolean) {
        runJs("HUD.updateMute(${muted})")
    }

    fun updateStatus(text: String) {
        runJs("HUD.updateStatus('${escapeJs(text)}')")
    }

    /**
     * Update the spatial radar display with peer direction and distance.
     * @param angle  relative direction in degrees (0 = directly ahead, ±180 = behind)
     * @param distance  normalized distance (0.0 = touching, 1.0 = far)
     */
    fun updatePeerDirection(angle: Float, distance: Float) {
        runJs("HUD.updatePeerDirection($angle, $distance)")
    }

    /**
     * Update the radio state indicator on the HUD.
     * States: DISABLED, WIFI_OFF, AWARE_DISABLED, ATTACHING, ATTACHED,
     *         PUBLISHING, DISCOVERING, CONNECTED, DOZING, RECOVERING, FAILED
     */
    fun updateRadioState(state: String) {
        runJs("HUD.updateRadioState('${escapeJs(state)}')")
    }

    /**
     * Update the BLE connection state indicator on the HUD.
     * States: DISCONNECTED, SCANNING, CONNECTING, DISCOVERING_SERVICES,
     *         ENABLING_NOTIFICATIONS, CONNECTED, RECONNECTING
     */
    fun updateBleState(state: String) {
        runJs("HUD.updateBleState('${escapeJs(state)}')")
    }

    // ── Internal helpers ────────────────────────────────────────────────

    /**
     * Execute JavaScript on the WebView. If the page hasn't finished
     * loading yet the call is buffered and will be flushed in
     * onPageFinished.
     */
    private fun runJs(js: String) {
        if (pageReady) {
            webView.evaluateJavascript(js, null)
        } else {
            synchronized(pendingJs) {
                pendingJs.add(js)
            }
        }
    }

    /** Flush all buffered JS calls — runs on the main thread via post. */
    private fun flushPendingJs() {
        webView.post {
            pageReady = true
            val snapshot: List<String>
            synchronized(pendingJs) {
                snapshot = pendingJs.toList()
                pendingJs.clear()
            }
            for (js in snapshot) {
                webView.evaluateJavascript(js, null)
            }
            Log.d(TAG, "Flushed ${snapshot.size} pending JS call(s)")
        }
    }

    /** Minimal JS string escape — prevents injection via single-quotes. */
    private fun escapeJs(value: String): String =
        value.replace("\\", "\\\\")
             .replace("'", "\\'")
             .replace("\n", "\\n")
             .replace("\r", "")
}
