package com.meshtalk.app.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.meshtalk.app.MeshTalkActivity
import com.meshtalk.app.audio.*
import com.meshtalk.app.mesh.*
import com.meshtalk.app.vox.VoxStateMachine
import com.meshtalk.app.vox.VoxState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class MeshTalkService : Service() {
    companion object {
        private const val TAG = "MeshTalkService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "meshtalk_service"
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

    // Audio streaming to server
    private var audioStreamClient: AudioStreamClient? = null

    // Mesh
    lateinit var transport: MeshTransport
    lateinit var peerManager: PeerManager
    lateinit var channelManager: ChannelManager

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pipelineJob: Job? = null
    private var seq = 0
    var isActive = false
        private set
    var isMuted = false
        private set

    var onVoxStateChanged: ((VoxState) -> Unit)? = null
    var onPeerCountChanged: ((Int) -> Unit)? = null
    var onChannelChanged: ((String) -> Unit)? = null

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
        // Use ANDROID_ID as unique device identifier (Build.SERIAL is "unknown" on modern Android)
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

        // Mesh components
        transport = WifiAwareTransport(this, deviceId)
        peerManager = PeerManager(transport)
        peerManager.deviceId = deviceId
        channelManager = ChannelManager(transport, peerManager)

        // Init native libs
        opusCodec.init()
        speexAec.init()

        // Callbacks
        voxStateMachine.onStateChanged = { state ->
            onVoxStateChanged?.invoke(state)
        }
        peerManager.onPeerCountChanged = { count ->
            onPeerCountChanged?.invoke(count)
        }
        channelManager.onChannelChanged = { channel ->
            onChannelChanged?.invoke(channel.displayName)
        }

        // Mesh data handler
        transport.onDataReceived = { peerId, data ->
            peerManager.onPeerDataReceived(peerId)
            handleIncomingPacket(peerId, data)
        }
        transport.onPeerDiscovered = { peer ->
            peerManager.onPeerDataReceived(peer.id)
            Log.i(TAG, "Peer connected: ${peer.id}")
        }
        transport.onPeerLost = { peerId ->
            peerManager.onPeerLost(peerId)
            audioMixer.removePeer(peerId)
        }

        // Playback feeds AEC reference
        playbackEngine.onFramePlayed = { frame ->
            speexAec.feedReference(frame)
        }

        // Start everything
        captureEngine.start()
        playbackEngine.start()
        peerManager.start(scope)
        channelManager.joinChannel(0) // Default: Alpha

        // Start capture pipeline
        pipelineJob = scope.launch {
            captureEngine.audioFrames.collectLatest { micFrame ->
                processCaptureFrame(micFrame)
            }
        }

        // Start audio stream client (runs independently of VOX state)
        audioStreamClient = AudioStreamClient(this, captureEngine.audioFrames, deviceId).also {
            it.onAudioReceived = { pcmShorts ->
                val filtered = clickFilter.process(pcmShorts)
                playbackEngine.play(filtered)
            }
            it.start()
        }
        Log.i(TAG, "Audio stream client started (bidirectional relay via server)")

        isActive = true
        Log.i(TAG, "Pipeline initialized, device=$deviceId")
    }

    private fun processCaptureFrame(micFrame: ShortArray) {
        // 1. AEC
        val aecFrame = speexAec.process(micFrame)

        // 2. VAD (accumulates 160→512)
        val vadResult = vadEngine.feedFrame(aecFrame)

        // 3. VOX state machine
        if (vadResult != null) {
            voxStateMachine.muted = isMuted
            voxStateMachine.onVadResult(vadResult, 32) // 32ms per VAD window
        }

        // 4. If speaking, encode and send
        if (voxStateMachine.shouldTransmit) {
            // Accumulate to 320 samples (20ms) for Opus
            // For simplicity, send every AEC frame through Opus
            // (Opus handles variable input sizes internally)
            val encoded = opusCodec.encode(aecFrame, aecFrame.size) ?: return
            val packet = PacketCodec.encodeAudio(
                channelManager.currentChannel.id, seq++, encoded
            )
            transport.sendToAll(packet)
        }
    }

    private fun handleIncomingPacket(peerId: String, data: ByteArray) {
        val packet = PacketCodec.decode(data) ?: return
        when (packet.type) {
            PacketCodec.TYPE_AUDIO -> {
                val decoded = opusCodec.decode(packet.payload) ?: return
                val filtered = clickFilter.process(decoded)
                audioMixer.submitFrame(peerId, filtered)
                val mixed = audioMixer.mix(filtered.size) ?: return
                playbackEngine.play(mixed)
            }
            PacketCodec.TYPE_CONTROL -> {
                // Handle control messages (announce, mute, etc.)
                Log.d(TAG, "Control from $peerId: ${String(packet.payload)}")
            }
            PacketCodec.TYPE_PING -> {
                val pong = PacketCodec.encodePong(packet.channelId, packet.seq)
                transport.sendTo(peerId, pong)
            }
            PacketCodec.TYPE_PONG -> { /* keepalive acknowledged */ }
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
        return isMuted
    }

    fun switchChannel() {
        audioMixer.clear()
        channelManager.switchChannel()
    }

    fun stopPipeline() {
        pipelineJob?.cancel()
        audioStreamClient?.stop()
        audioStreamClient = null
        captureEngine.stop()
        playbackEngine.stop()
        channelManager.leaveChannel()
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
            .setContentText("Walkie-talkie active")
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
