package com.openclaw.app.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.openclaw.app.MeshTalkActivity
import com.openclaw.app.audio.*
import com.openclaw.app.mesh.*
import com.openclaw.app.vox.VoxStateMachine
import com.openclaw.app.vox.VoxState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject

class MeshTalkService : Service() {
    companion object {
        private const val TAG = "MeshTalkService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "meshtalk_service"
        private const val SPATIAL_SEND_INTERVAL_MS = 200L
        private const val DEFAULT_PEER_RSSI = -55  // Approximate RSSI for same-network peers
    }

    // Audio pipeline
    lateinit var captureEngine: AudioCaptureEngine
    lateinit var playbackEngine: AudioPlaybackEngine
    lateinit var opusCodec: OpusCodec
    lateinit var speexAec: SpeexAec
    lateinit var vadEngine: VadEngine
    lateinit var voxStateMachine: VoxStateMachine
    lateinit var clickFilter: ClickRemovalFilter
    lateinit var audioMixer: AudioMixer

    // Spatial audio
    lateinit var headTracker: HeadTracker
    lateinit var spatialEngine: SpatialAudioEngine

    // Audio streaming to server (secondary path — Mac Mini relay)
    private var audioStreamClient: AudioStreamClient? = null

    // BLE phone client (primary mesh transport)
    var blePhoneClient: BlePhoneClient? = null
    private var nanTransport: MeshTransport? = null
        private set

    // Mesh (channel manager still used for channel state, but no longer drives NAN transport)
    lateinit var peerManager: PeerManager
    lateinit var channelManager: ChannelManager

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pipelineJob: Job? = null
    private var spatialSendJob: Job? = null
    private var seq = 0
    var isActive = false
        private set
    var isMuted = false
        private set

    var onVoxStateChanged: ((VoxState) -> Unit)? = null
    var onPeerCountChanged: ((Int) -> Unit)? = null
    var onChannelChanged: ((String) -> Unit)? = null
    var onPeerDirectionChanged: ((Float, Float) -> Unit)? = null  // angle, distance
    var onBleStateChanged: ((String) -> Unit)? = null
    var onRadioStateChanged: ((String) -> Unit)? = null

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): MeshTalkService = this@MeshTalkService
    }
    override fun onBind(intent: Intent?) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        acquireWakeLock()

        if (!isActive) {
            initPipeline()
        }

        return START_STICKY
    }

    private fun initPipeline() {
        // Use ANDROID_ID as unique device identifier
        val androidId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        val deviceId = androidId?.takeLast(6) ?: "glass_${System.currentTimeMillis() % 100000}"

        // Audio components
        captureEngine = AudioCaptureEngine()
        playbackEngine = AudioPlaybackEngine()
        opusCodec = OpusCodec()
        speexAec = SpeexAec()
        vadEngine = VadEngine(this)
        voxStateMachine = VoxStateMachine()
        clickFilter = ClickRemovalFilter()
        audioMixer = AudioMixer()

        // Spatial audio components
        headTracker = HeadTracker(this)
        spatialEngine = SpatialAudioEngine()

        // ── Transport Manager (WiFi Direct > WiFi LAN > BT RFCOMM) ──────
        val transportManager = TransportManager(this, deviceId)
        nanTransport = transportManager

        // Wire transport data to audio pipeline
        transportManager.onDataReceived = { peerId, data ->
            peerManager.onPeerDataReceived(peerId)
            handleIncomingPacket(peerId, data)
        }
        transportManager.onPeerDiscovered = { peer ->
            peerManager.onPeerDataReceived(peer.id)
            Log.i(TAG, "*** PEER CONNECTED: ${peer.id} ***")
            onRadioStateChanged?.invoke("CONNECTED")
        }
        transportManager.onPeerLost = { peerId ->
            peerManager.onPeerLost(peerId)
            audioMixer.removePeer(peerId)
            onRadioStateChanged?.invoke("DISCOVERING")
        }

        peerManager = PeerManager(transportManager)
        peerManager.deviceId = deviceId
        channelManager = ChannelManager(transportManager, peerManager)

        // Init native libs
        opusCodec.init()
        speexAec.init()

        // Callbacks
        voxStateMachine.onStateChanged = { state ->
            onVoxStateChanged?.invoke(state)
            // Notify phone of VOX state changes
            blePhoneClient?.sendVoxState(state != VoxState.IDLE)
        }
        peerManager.onPeerCountChanged = { count ->
            onPeerCountChanged?.invoke(count)
        }
        channelManager.onChannelChanged = { channel ->
            onChannelChanged?.invoke(channel.displayName)
            // Notify phone of channel switch
            blePhoneClient?.sendChannelSwitch(channel.id, channel.displayName)
        }

        // ── BLE Phone Client (primary mesh transport) ──────────────────
        val ble = BlePhoneClient(this, deviceId)
        blePhoneClient = ble

        // Wire BLE state changes to HUD
        ble.onStateChanged = { bleState ->
            val stateName = bleState.name
            Log.i(TAG, "BLE state: $stateName")
            onBleStateChanged?.invoke(stateName)
        }

        // Wire BLE audio receive → spatial → playback
        ble.onAudioReceived = { opusFrame ->
            val decoded = opusCodec.decode(opusFrame)
            if (decoded != null) {
                val spatialProcessed = spatialEngine.processSpatial("ble_peer", decoded)
                val filtered = clickFilter.process(spatialProcessed)
                audioMixer.submitFrame("ble_peer", filtered)
                val mixed = audioMixer.mix(filtered.size)
                if (mixed != null) {
                    playbackEngine.play(mixed)
                }
            }
        }

        // Wire BLE status updates
        ble.onStatusUpdate = { json ->
            Log.d(TAG, "Phone status: $json")
            // Extract peer count from phone's mesh status
            val meshPeers = json.optInt("mesh_peers", -1)
            if (meshPeers >= 0) {
                onPeerCountChanged?.invoke(meshPeers + 1) // +1 for self
            }
        }

        // Wire BLE control messages (from phone)
        ble.onControlReceived = { json ->
            val cmd = json.optString("cmd", "")
            Log.d(TAG, "Phone control: $json")
            when (cmd) {
                "spatial" -> {
                    val peerId = json.optString("from", "ble_peer")
                    val peerYaw = json.optDouble("yaw", 0.0).toFloat()
                    val rssi = json.optInt("rssi", DEFAULT_PEER_RSSI)
                    spatialEngine.updatePeerSpatial(peerId, rssi, peerYaw)
                    spatialEngine.updatePeerSpatial("ble_peer", rssi, peerYaw)

                    val direction = spatialEngine.getPeerDirection(peerId)
                    val distance = spatialEngine.getPeerDistance(peerId)
                    if (direction != null && distance != null) {
                        onPeerDirectionChanged?.invoke(direction, distance)
                    }
                }
            }
        }

        ble.onConnected = {
            Log.i(TAG, "BLE connected to phone companion")
        }

        ble.onDisconnected = {
            Log.w(TAG, "BLE disconnected from phone companion")
        }

        // Playback feeds AEC reference
        playbackEngine.onFramePlayed = { frame ->
            speexAec.feedReference(frame)
        }

        // Start everything
        Log.i(TAG, "Starting capture engine...")
        captureEngine.start()
        Log.i(TAG, "Starting playback engine...")
        playbackEngine.start()
        Log.i(TAG, "Starting head tracker...")
        headTracker.start()
        Log.i(TAG, "Starting peer manager...")
        peerManager.start(scope)

        // Start transport manager (starts all transports in parallel)
        nanTransport?.start(ChannelManager.CHANNELS[0].serviceName)

        // Start BLE client — scans and connects to phone
        channelManager.joinChannel(0)
        ble.start()

        // Start capture pipeline
        pipelineJob = scope.launch {
            captureEngine.audioFrames.collectLatest { micFrame ->
                processCaptureFrame(micFrame)
            }
        }

        // Start audio stream client (secondary path — Mac Mini relay, runs independently)
        audioStreamClient = AudioStreamClient(this, captureEngine.audioFrames, deviceId).also {
            it.onAudioReceived = { pcmShorts ->
                val spatialProcessed = spatialEngine.processSpatial("server_peer", pcmShorts)
                val filtered = clickFilter.process(spatialProcessed)
                playbackEngine.play(filtered)
            }
            it.onTextMessageReceived = { text ->
                handleServerTextMessage(text)
            }
            it.start()
        }
        Log.i(TAG, "Audio stream client started (bidirectional relay via server)")

        // Start periodic spatial data sender (every 200ms)
        startSpatialSender()

        // Feed head tracker orientation to spatial engine
        scope.launch {
            headTracker.orientationFlow.collect { orientation ->
                spatialEngine.listenerYaw = orientation.yaw
                spatialEngine.listenerPitch = orientation.pitch
            }
        }

        isActive = true
        Log.i(TAG, "Pipeline initialized with BLE transport + spatial audio, device=$deviceId")
    }

    /**
     * Send head orientation to server every SPATIAL_SEND_INTERVAL_MS.
     */
    private fun startSpatialSender() {
        spatialSendJob?.cancel()
        spatialSendJob = scope.launch {
            while (isActive) {
                try {
                    val msg = JSONObject().apply {
                        put("type", "spatial")
                        put("yaw", headTracker.headYaw)
                        put("pitch", headTracker.headPitch)
                        put("roll", headTracker.headRoll)
                    }
                    audioStreamClient?.sendText(msg.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Spatial send error: ${e.message}")
                }
                delay(SPATIAL_SEND_INTERVAL_MS)
            }
        }
    }

    /**
     * Handle incoming text (JSON) messages from the server (secondary relay path).
     */
    private fun handleServerTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "spatial" -> {
                    val peerId = json.optString("from", "unknown")
                    val peerYaw = json.optDouble("yaw", 0.0).toFloat()
                    spatialEngine.updatePeerSpatial(peerId, DEFAULT_PEER_RSSI, peerYaw)
                    spatialEngine.updatePeerSpatial("server_peer", DEFAULT_PEER_RSSI, peerYaw)

                    val direction = spatialEngine.getPeerDirection(peerId)
                    val distance = spatialEngine.getPeerDistance(peerId)
                    if (direction != null && distance != null) {
                        onPeerDirectionChanged?.invoke(direction, distance)
                    }
                }
                "control" -> {
                    val from = json.optString("from", "unknown")
                    Log.d(TAG, "Control message from $from: $text")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse server text message: ${e.message}")
        }
    }

    private var audioRecvCount = 0L

    private fun handleIncomingPacket(peerId: String, data: ByteArray) {
        val packet = PacketCodec.decode(data)
        if (packet == null) {
            if (data.isNotEmpty() && data[0] == '{'.code.toByte()) return
            return
        }
        when (packet.type) {
            PacketCodec.TYPE_AUDIO -> {
                audioRecvCount++
                if (audioRecvCount % 200 == 0L) {
                    Log.i(TAG, "AUDIO RECV #$audioRecvCount from $peerId (${packet.payload.size}B opus)")
                }
                val decoded = opusCodec.decode(packet.payload)
                if (decoded == null) {
                    Log.w(TAG, "Opus decode failed for ${packet.payload.size}B payload")
                    return
                }
                val spatialProcessed = spatialEngine.processSpatial(peerId, decoded)
                val filtered = clickFilter.process(spatialProcessed)
                audioMixer.submitFrame(peerId, filtered)
                val mixed = audioMixer.mix(filtered.size) ?: return
                playbackEngine.play(mixed)
            }
            PacketCodec.TYPE_CONTROL -> {
                Log.d(TAG, "NAN control from $peerId: ${String(packet.payload)}")
            }
            PacketCodec.TYPE_PING -> {
                val pong = PacketCodec.encodePong(packet.channelId, packet.seq)
                // Reply via transport
            }
            PacketCodec.TYPE_PONG -> { /* keepalive ack */ }
        }
    }

    private var captureFrameCount = 0L

    private fun processCaptureFrame(micFrame: ShortArray) {
        captureFrameCount++
        if (captureFrameCount % 500 == 0L) {
            Log.d(TAG, "Capture frames: $captureFrameCount, VOX=${voxStateMachine.shouldTransmit}, muted=$isMuted")
        }

        // 1. AEC
        val aecFrame = speexAec.process(micFrame)

        // 2. VAD (accumulates 160→512)
        val vadResult = vadEngine.feedFrame(aecFrame)

        // 3. VOX state machine
        if (vadResult != null) {
            voxStateMachine.muted = isMuted
            voxStateMachine.onVadResult(vadResult, 32) // 32ms per VAD window
        }

        // 4. If speaking (or force-transmit for walkie-talkie), encode and send
        // For walkie-talkie: always transmit when not muted (tap to mute/unmute)
        val shouldSend = voxStateMachine.shouldTransmit || !isMuted
        if (shouldSend) {
            val encoded = opusCodec.encode(aecFrame, aecFrame.size) ?: return
            // Send via NAN transport (glasses-to-glasses direct)
            val packet = PacketCodec.encodeAudio(channelManager.currentChannel.id, seq++, encoded)
            nanTransport?.sendToAll(packet)
            // Also send via BLE to phone (secondary path)
            blePhoneClient?.sendAudio(encoded, isSpeech = true)
        }
    }

    fun toggleActive(): Boolean {
        isActive = !isActive
        if (isActive) {
            initPipeline()
        } else {
            stopPipeline()
        }
        return isActive
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        voxStateMachine.muted = isMuted
        // Notify phone companion
        blePhoneClient?.sendMuteState(isMuted)
        return isMuted
    }

    fun switchChannel() {
        audioMixer.clear()
        channelManager.switchChannel()
        // Phone companion is notified via channelManager.onChannelChanged callback
    }

    fun stopPipeline() {
        pipelineJob?.cancel()
        spatialSendJob?.cancel()
        audioStreamClient?.stop()
        audioStreamClient = null
        blePhoneClient?.stop()
        blePhoneClient = null
        captureEngine.stop()
        playbackEngine.stop()
        headTracker.stop()
        peerManager.stop()
        opusCodec.release()
        speexAec.release()
        vadEngine.release()
        audioMixer.clear()
        Log.i(TAG, "Pipeline stopped")
    }

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeshTalk::ServiceLock")
                .apply { acquire(10 * 60 * 1000L) }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "MeshTalk Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "MeshTalk walkie-talkie active" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MeshTalkActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshTalk")
            .setContentText("Walkie-talkie active — BLE + spatial audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopPipeline()
        wakeLock?.release()
        scope.cancel()
        super.onDestroy()
    }
}
