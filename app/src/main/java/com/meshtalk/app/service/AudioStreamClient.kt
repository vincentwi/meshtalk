package com.meshtalk.app.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.meshtalk.app.audio.AudioCaptureEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Streams raw PCM16 audio from AudioCaptureEngine to a remote WebSocket relay server.
 *
 * Connects to ws://SERVER_IP:8435/ws/glasses and sends every audio frame as a binary
 * WebSocket message (little-endian PCM16, 16kHz mono).
 *
 * Runs independently of the walkie-talkie VOX pipeline — even if walkie-talkie is
 * toggled off, the stream continues as long as AudioCaptureEngine is capturing.
 */
class AudioStreamClient(
    private val context: Context,
    private val audioFrames: SharedFlow<ShortArray>,
    private val deviceId: String = "unknown",
) {
    companion object {
        private const val TAG = "AudioStreamClient"
        private const val PREFS_NAME = "meshtalk_stream"
        private const val KEY_SERVER_IP = "server_ip"
        private const val DEFAULT_PORT = 8435
        private const val RECONNECT_DELAY_MS = 5000L
        private const val PING_INTERVAL_S = 15L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var collectJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var isConnected = false

    /** Callback invoked when audio is received from another glasses via the server. */
    var onAudioReceived: ((ShortArray) -> Unit)? = null

    var serverIp: String
        get() = prefs.getString(KEY_SERVER_IP, detectDefaultIp()) ?: detectDefaultIp()
        set(value) = prefs.edit().putString(KEY_SERVER_IP, value).apply()

    /** Start the streaming client. Safe to call multiple times. */
    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Starting audio stream client → ${serverIp}:$DEFAULT_PORT")
        connect()
    }

    /** Stop the streaming client and release resources. */
    fun stop() {
        isRunning = false
        reconnectJob?.cancel()
        collectJob?.cancel()
        webSocket?.close(1000, "Client stopping")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        isConnected = false
        Log.i(TAG, "Audio stream client stopped")
    }

    private fun connect() {
        if (!isRunning) return

        client?.dispatcher?.executorService?.shutdown()
        client = OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MINUTES) // indefinite for WebSocket
            .build()

        val url = "ws://${serverIp}:$DEFAULT_PORT/ws/glasses?id=$deviceId"
        val request = Request.Builder().url(url).build()

        Log.i(TAG, "Connecting to $url")
        client!!.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to $url")
                this@AudioStreamClient.webSocket = webSocket
                isConnected = true
                startCollecting()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Received audio from another glasses relayed by the server
                val byteArray = bytes.toByteArray()
                if (byteArray.size < 2) return
                val shorts = ShortArray(byteArray.size / 2)
                ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                onAudioReceived?.invoke(shorts)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                isConnected = false
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                isConnected = false
                this@AudioStreamClient.webSocket = null
                scheduleReconnect()
            }
        })
    }

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = scope.launch {
            Log.i(TAG, "Collecting audio frames for streaming")
            audioFrames.collect { frame ->
                if (!isConnected || webSocket == null) return@collect
                val bytes = shortsToBytes(frame)
                try {
                    webSocket?.send(bytes.toByteString(0, bytes.size))
                } catch (e: Exception) {
                    Log.w(TAG, "Send failed: ${e.message}")
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Reconnecting in ${RECONNECT_DELAY_MS}ms...")
            delay(RECONNECT_DELAY_MS)
            connect()
        }
    }

    /** Convert ShortArray (PCM16) to ByteArray (little-endian). */
    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in shorts) buf.putShort(s)
        return buf.array()
    }

    /**
     * Detect default server IP.
     * When connected via USB ADB, the host is typically reachable at 10.0.0.1 on the
     * USB tethering interface gateway, or we fall back to a sensible LAN default.
     * Users can override via SharedPreferences or the settings UI.
     */
    private fun detectDefaultIp(): String {
        // Try to find the USB tethering gateway (common for adb-connected Macs)
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                // rndis0/usb0 are typical USB tethering interfaces
                if (iface.name.startsWith("rndis") || iface.name.startsWith("usb")) {
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            // Gateway is usually .1 on the USB subnet
                            val parts = addr.hostAddress?.split(".") ?: continue
                            if (parts.size == 4) {
                                return "${parts[0]}.${parts[1]}.${parts[2]}.1"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect USB gateway: ${e.message}")
        }
        // Fallback: typical Mac Mini LAN address (user should configure)
        return "127.0.0.1"  // Use ADB reverse tunnel: glasses:8435 → Mac Mini:8435
    }
}
