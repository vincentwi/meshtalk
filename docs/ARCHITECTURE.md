# MeshTalk Architecture

**Version:** 0.1.0 (Alpha)
**Platform:** RayNeo X3 Pro / Mercury OS (Android 12)
**Language:** Kotlin + C/C++ (native codecs)

---

## Table of Contents

1. [System Overview](#system-overview)
2. [Audio Pipeline](#audio-pipeline)
3. [Transport Layer](#transport-layer)
4. [NanSupervisor State Machine](#nansupervisor)
5. [Spatial Audio Engine](#spatial-audio)
6. [HUD Rendering](#hud-rendering)
7. [Server Relay Architecture](#server-relay)
8. [Mercury OS Survival Strategies](#mercury-os-survival)
9. [Packet Format](#packet-format)
10. [Build and Deploy Pipeline](#build-deploy)
11. [Module Inventory](#module-inventory)
12. [Data Flow Diagrams](#data-flow)

---

## System Overview <a name="system-overview"></a>

```
┌─────────────────────────────────────────────────────────────────┐
│                    RayNeo X3 Pro (Glass A)                       │
│                                                                  │
│  ┌──────────┐   ┌────────┐   ┌──────────┐   ┌──────────────┐   │
│  │   Mic    │──▶│Speex   │──▶│ Silero   │──▶│     VOX      │   │
│  │(16kHz)   │   │  AEC   │   │   VAD    │   │State Machine │   │
│  └──────────┘   └────────┘   └──────────┘   └──────┬───────┘   │
│                                                      │           │
│                              ┌────────────────┐      │           │
│                              │  Opus Encoder  │◀─────┘           │
│                              │ (16kbps mono)  │                  │
│                              └───────┬────────┘                  │
│                                      │                           │
│  ┌───────────────────────────────────▼───────────────────────┐   │
│  │              Transport Layer (dual path)                   │   │
│  │                                                            │   │
│  │  ┌─────────────────┐      ┌──────────────────────────┐   │   │
│  │  │  WiFi Aware NAN │      │  WebSocket Relay Client  │   │   │
│  │  │  (P2P direct)   │      │  (via Mac Mini server)   │   │   │
│  │  │                 │      │                           │   │   │
│  │  │  NanSupervisor  │      │  ws://server:8435/ws/    │   │   │
│  │  │  manages radio  │      │  glasses                  │   │   │
│  │  └────────┬────────┘      └─────────────┬────────────┘   │   │
│  │           │                              │                │   │
│  └───────────┼──────────────────────────────┼────────────────┘   │
│              │                              │                    │
│  ┌───────────▼──────────────────────────────▼────────────────┐   │
│  │                   Audio Mixer                              │   │
│  │          (merges streams from all peers)                   │   │
│  └──────────────────────┬────────────────────────────────────┘   │
│                          │                                       │
│  ┌──────────────────────▼────────────────────────────────────┐   │
│  │              Spatial Audio Engine                           │   │
│  │  RSSI distance attenuation + head-relative filtering       │   │
│  └──────────────────────┬────────────────────────────────────┘   │
│                          │                                       │
│  ┌──────────────────────▼────┐   ┌───────────────────────────┐   │
│  │   Opus Decoder            │   │   HUD Renderer (WebView)  │   │
│  │   + Click Removal Filter  │   │   Channel / VOX / Radar   │   │
│  │   + Bone Conduction Out   │   │   Peer count / Radio      │   │
│  └───────────────────────────┘   └───────────────────────────┘   │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
         ▲                                          ▲
         │ NAN (direct, ~2m range)                  │ WebSocket
         │ UDP on aware_data0                       │ (relay path)
         ▼                                          ▼
┌────────────────────────┐         ┌──────────────────────────┐
│  RayNeo X3 Pro         │         │     Mac Mini Server      │
│  (Glass B)             │         │                          │
│  [identical stack]     │         │  Python WebSocket relay  │
│                        │         │  + audio bridge          │
└────────────────────────┘         │  ws://0.0.0.0:8435      │
                                   │                          │
                                   │  ADB reverse tunnel:     │
                                   │  glasses:8435 → Mac:8435 │
                                   └──────────────────────────┘
```

---

## Audio Pipeline <a name="audio-pipeline"></a>

### Signal Chain

```
Mic (AudioRecord)
 │  16 kHz, mono, PCM16
 │  160 samples/frame (10ms)
 │
 ▼
Speex AEC (libspeexdsp)
 │  Cancels speaker echo from bone-conduction output
 │  Native JNI: libspeexdsp.so (arm64-v8a)
 │  Frame size: 160 samples
 │
 ▼
Silero VAD
 │  Neural-network voice activity detection
 │  Accumulates to 512 samples (32ms) for inference
 │  Returns probability [0.0, 1.0]
 │  Mode: NORMAL, speech=50ms, silence=300ms
 │
 ▼
VOX State Machine
 │  Three states: IDLE → SPEAKING → HANGOVER
 │  Onset: 200ms of speech above 0.45 threshold
 │  Hangover: 700ms of silence below 0.30 threshold
 │  Prevents choppy cut-in/cut-out
 │
 ▼
Opus Encoder (libopus, native)
 │  16 kbps, mono, 16 kHz
 │  20ms frames = 320 samples → ~40 bytes compressed
 │  Complexity: 5 (balanced quality/CPU)
 │
 ▼
PacketCodec
 │  6-byte header: type(1) + channelId(1) + seq(4)
 │  Wraps Opus frame for transport
 │
 ▼
Transport (NAN direct / WebSocket relay)
```

### Components

**AudioCaptureEngine** (`audio/AudioCaptureEngine.kt`)
- Opens `AudioRecord` with 16kHz, mono, PCM16LE
- Reads 160-sample (10ms) frames in a coroutine loop
- Emits frames via `SharedFlow<ShortArray>` for both:
  - The walkie-talkie VOX pipeline
  - The server streaming pipeline (AudioStreamClient)

**SpeexAec** (`audio/SpeexAec.kt`)
- JNI wrapper around libspeexdsp's AEC module
- `processFrame(mic: ShortArray, speaker: ShortArray): ShortArray`
- Requires reference speaker signal to cancel echo
- Cross-compiled for arm64-v8a via NDK

**VadEngine** (`audio/VadEngine.kt`)
- Wraps `com.konovalov.vad.silero.VadSilero`
- Silero is a compact ONNX neural network (~400KB)
- Accumulates 160-sample AEC frames into 512-sample VAD frames
- Returns speech probability on each 512-sample boundary
- Null return when still accumulating

**VoxStateMachine** (`vox/VoxStateMachine.kt`)
- Implements VOX (Voice-Operated Exchange) logic
- Prevents transmitting noise or brief sounds
- State transitions driven by VAD probability
- `shouldTransmit` property gates the Opus encoder
- Mute support: forced IDLE when muted

**OpusCodec** (`audio/OpusCodec.kt`)
- JNI wrapper around libopus
- Encode: 320 PCM16 samples → ~40 bytes Opus
- Decode: Opus bytes → 320 PCM16 samples
- Cross-compiled for arm64-v8a via NDK

**ClickRemovalFilter** (`audio/ClickRemovalFilter.kt`)
- Detects and smooths audio discontinuities
- Applied to decoded audio before playback
- Prevents pops/clicks at packet boundaries

**AudioMixer** (`audio/AudioMixer.kt`)
- Merges multiple peer audio streams into one
- Simple additive mixing with clipping prevention
- Per-peer volume controlled by SpatialAudioEngine

**AudioPlaybackEngine** (`audio/AudioPlaybackEngine.kt`)
- Opens `AudioTrack` for bone-conduction speaker output
- 16kHz, mono, PCM16
- Provides reference signal to SpeexAec for echo cancellation

---

## Transport Layer <a name="transport-layer"></a>

### Dual Transport Architecture

MeshTalk uses two simultaneous transport paths:

```
┌─────────────────────────────────────────────┐
│             MeshTransport Interface          │
│                                              │
│  start(channel)  stop()  switchChannel()     │
│  sendToAll(data)  sendTo(peerId, data)       │
│  onPeerDiscovered  onPeerLost  onDataReceived│
└────────────┬───────────────────┬─────────────┘
             │                   │
    ┌────────▼──────┐   ┌───────▼──────────┐
    │ WifiAware     │   │ AudioStreamClient│
    │ Transport     │   │ (WebSocket)      │
    │               │   │                  │
    │ NAN pub/sub   │   │ ws://server:8435 │
    │ NDP data path │   │ PCM16 binary     │
    │ UDP on NAN    │   │ JSON control     │
    └───────────────┘   └──────────────────┘
```

### WifiAwareTransport (`mesh/WifiAwareTransport.kt`)

Implements peer-to-peer communication via WiFi Aware NAN:

1. **Attach** to WifiAwareManager
2. **Publish** service "MeshTalk-{channel}" with solicited type
3. **Subscribe** to same service name for peer discovery
4. **On match**: extract peer handle, initiate NDP
5. **On NDP confirm**: obtain IPv6 address, open UDP socket
6. **Send/receive** Opus packets via UDP on aware_data0

**Current status:** Steps 1-4 work. Step 5 fails (see NAN_DEEP_INVESTIGATION.md).

### AudioStreamClient (`service/AudioStreamClient.kt`)

Streams audio to/from Mac Mini relay server via WebSocket:

- Connects to `ws://{server_ip}:8435/ws/glasses`
- Server IP stored in SharedPreferences, set via ADB reverse tunnel
- Binary messages: raw PCM16 audio frames (little-endian, 16kHz mono)
- Text messages: JSON for spatial data and control
- Auto-reconnect with 5-second delay on disconnection
- OkHttp WebSocket client with 15-second ping interval

**Current status:** Fully functional. This is the working transport path.

### PeerManager (`mesh/PeerManager.kt`)

Tracks discovered peers and their metadata:
- Peer ID (derived from ANDROID_ID, not Build.SERIAL)
- Last seen timestamp
- RSSI (when available from NAN)
- Connection state (discovered / connecting / connected / lost)

### ChannelManager (`mesh/ChannelManager.kt`)

Manages virtual walkie-talkie channels:
- Channel name → service name mapping
- Channel switching triggers pub/sub restart
- Default channel: "alpha"

---

## NanSupervisor State Machine <a name="nansupervisor"></a>

See [NAN_DEEP_INVESTIGATION.md](NAN_DEEP_INVESTIGATION.md#nansupervisor-architecture)
for the full state diagram.

### States

| State | Meaning |
|-------|---------|
| DISABLED | Not started yet |
| WIFI_OFF | WiFi radio is off |
| AWARE_DISABLED | WiFi on but aware_enabled=0 |
| ATTACHING | WifiAwareManager.attach() in flight |
| ATTACHED | Session attached, about to publish |
| PUBLISHING | Publish session active |
| DISCOVERING | Pub + Sub active, finding peers |
| CONNECTED | Data path established (goal state) |
| DOZING | Device in doze mode, NAN killed |
| RECOVERING | Re-initializing after failure/doze |
| FAILED | Repeated failures, waiting for backoff |

### Recovery Logic

```
Failure detected
    │
    ▼
consecutiveFailures++
backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)  // 1s → 2s → 4s → ... → 30s
    │
    ▼
handler.postDelayed(reconnect, backoffMs)
    │
    ▼
On success: consecutiveFailures = 0, backoffMs = MIN_BACKOFF_MS
```

### Broadcast Receivers

- `WifiManager.WIFI_STATE_CHANGED_ACTION` → WiFi on/off
- `WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED` → NAN available/unavailable
- `PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED` → doze enter/exit

### Watchdog

5-second periodic check via `Handler.postDelayed`:
- Verifies current state matches reality
- Re-triggers recovery if stuck
- Guards against missed broadcasts (Mercury OS kills receivers)

---

## Spatial Audio Engine <a name="spatial-audio"></a>

### Design Constraints

RayNeo X3 Pro uses **bone-conduction speakers** — mono output only,
no stereo left/right separation. Traditional spatial audio (HRTF,
binaural) is not applicable.

### Spatial Cues (Mono)

```
┌────────────────────────────────────────────────┐
│              Spatial Audio Engine               │
│                                                 │
│  Input: PCM16 audio + peer RSSI + head yaw     │
│  Output: spatially-processed PCM16              │
│                                                 │
│  ┌─────────────────────────────────────────┐   │
│  │  1. Distance Attenuation (RSSI-based)   │   │
│  │     -30 dBm → gain 1.0 (touching)      │   │
│  │     -90 dBm → gain 0.1 (far away)      │   │
│  │     Linear interpolation between        │   │
│  └─────────────────────┬───────────────────┘   │
│                        ▼                        │
│  ┌─────────────────────────────────────────┐   │
│  │  2. Head-Relative Filtering             │   │
│  │     Source in front → full spectrum      │   │
│  │     Source behind → low-pass filter      │   │
│  │     (simulates head shadow effect)       │   │
│  │     LPF cutoff: ~2kHz, alpha=0.44       │   │
│  └─────────────────────┬───────────────────┘   │
│                        ▼                        │
│  ┌─────────────────────────────────────────┐   │
│  │  3. Proximity Bass Boost                │   │
│  │     RSSI > -40 dBm → add bass at 20%   │   │
│  │     1-pole LPF at ~300Hz (alpha=0.105)  │   │
│  │     Mixed back at BASS_BOOST_GAIN=0.20  │   │
│  └─────────────────────────────────────────┘   │
│                                                 │
│  Per-peer state: rssi, yaw, lpfState, bassState│
│  Head orientation: listenerYaw from IMU         │
│                                                 │
└────────────────────────────────────────────────┘
```

### Head Tracker (`audio/HeadTracker.kt`)

- Reads IMU sensor data (gyroscope + accelerometer)
- Provides yaw/pitch orientation in degrees
- Feeds `SpatialAudioEngine.listenerYaw` for head-relative filtering
- 0° = north, clockwise

### Distance Model

```
RSSI → Gain mapping (linear interpolation):

  Gain
  1.0 ┤───•
      │    \
      │     \
      │      \
  0.1 ┤       ─────•
      └──┬──────────┬──
       -30        -90  RSSI (dBm)
      (near)      (far)
```

---

## HUD Rendering <a name="hud-rendering"></a>

### Architecture

The HUD is a single-file HTML/CSS/JS application rendered in an Android
WebView overlaid on the AR camera pass-through.

```
┌──────────────────────────────────────────────┐
│  HudRenderer.kt (native side)                │
│                                               │
│  updateChannel(name)  → HUD.updateChannel()  │
│  updateUserCount(n)   → HUD.updateUserCount()│
│  updateVox(active)    → HUD.updateVox()      │
│  updateRadar(peers)   → HUD.updateRadar()    │
│  updateRadioState(s)  → HUD.updateRadio()    │
│  updateMute(muted)    → HUD.updateMute()     │
│                                               │
│  JS calls buffered until onPageFinished       │
│  NativeBridge interface for reverse calls     │
│                                               │
│  ┌───────────────────────────────────────┐   │
│  │  WebView (transparent background)      │   │
│  │                                        │   │
│  │  file:///android_asset/hud/            │   │
│  │          meshtalk.html                 │   │
│  │                                        │   │
│  │  ┌──────┐ ┌────────┐ ┌──────────┐    │   │
│  │  │Channel│ │  VOX   │ │  Radar   │    │   │
│  │  │ name  │ │indicator│ │ (peers)  │    │   │
│  │  └──────┘ └────────┘ └──────────┘    │   │
│  │                                        │   │
│  │  ┌──────┐ ┌────────┐ ┌──────────┐    │   │
│  │  │Radio │ │  Mute  │ │  Peer    │    │   │
│  │  │state │ │ status │ │  count   │    │   │
│  │  └──────┘ └────────┘ └──────────┘    │   │
│  └───────────────────────────────────────┘   │
└──────────────────────────────────────────────┘
```

### Temple Gesture Controls

RayNeo X3 Pro has capacitive temple touch panels. MeshTalk maps gestures:

| Gesture | Action |
|---------|--------|
| Single tap (right temple) | Toggle walkie-talkie active |
| Double tap (right temple) | Switch channel |
| Long press (right temple) | Toggle mute |
| Swipe back (right temple) | Exit app |

Implemented via Mercury SDK's `TempleAction` in `BaseMirrorActivity`.

---

## Server Relay Architecture <a name="server-relay"></a>

### Current Working Path

Since NAN data path is non-functional, audio currently flows through
a Mac Mini relay server via ADB reverse tunnel:

```
Glass A                    Mac Mini                     Glass B
  │                          │                            │
  │  adb reverse tcp:8435    │   adb reverse tcp:8435     │
  │  localhost:8435          │   localhost:8435            │
  │                          │                            │
  │  ws://localhost:8435/    │                            │
  │     ws/glasses     ──────▶  Python WebSocket Server  │
  │                          │                            │
  │                          │  Receives audio from A    │
  │                          │  Forwards to B (and vice  │
  │                          │  versa)                    │
  │                          │                            │
  │                     ◀────── ws://localhost:8435/      │
  │                          │     ws/glasses             │
  │                          │                            │
```

### ADB Reverse Tunnel

```bash
# On host Mac:
adb -s $SERIAL_A reverse tcp:8435 tcp:8435
adb -s $SERIAL_B reverse tcp:8435 tcp:8435
```

This makes `localhost:8435` on each glasses device route to the Mac's
port 8435, where the WebSocket relay server listens.

### WebSocket Protocol

**Binary messages:** Raw PCM16 audio frames
- Little-endian, 16kHz mono
- 160 samples × 2 bytes = 320 bytes per frame
- ~100 frames/second (10ms each)

**Text messages:** JSON control/spatial data

```json
{
  "type": "spatial",
  "deviceId": "a1b2c3d4",
  "yaw": 45.2,
  "pitch": -5.1,
  "rssi": -52
}
```

```json
{
  "type": "config",
  "serverIp": "192.168.1.100"
}
```

---

## Mercury OS Survival Strategies <a name="mercury-os-survival"></a>

### Problem

Mercury OS aggressively kills background processes. Even with a
foreground service, the custom `BackgroundAppManager` can terminate
non-whitelisted apps.

### Multi-Layer Defense

```
┌─────────────────────────────────────────────┐
│  Layer 1: Foreground Service                 │
│  - Persistent notification                   │
│  - FOREGROUND_SERVICE_MICROPHONE type        │
│  - Standard Android background protection    │
├─────────────────────────────────────────────┤
│  Layer 2: Wake Lock                          │
│  - PARTIAL_WAKE_LOCK via PowerManager        │
│  - Prevents CPU sleep during audio capture   │
├─────────────────────────────────────────────┤
│  Layer 3: Battery Optimization Bypass        │
│  - cmd deviceidle whitelist +pkg             │
│  - REQUEST_IGNORE_BATTERY_OPTIMIZATIONS      │
├─────────────────────────────────────────────┤
│  Layer 4: Boot Receiver                      │
│  - BootReceiver listens for BOOT_COMPLETED   │
│  - Auto-restarts service after reboot        │
├─────────────────────────────────────────────┤
│  Layer 5: WiFi Aware Auto-Enable             │
│  - NanSupervisor sets aware_enabled=1        │
│  - Requires WRITE_SECURE_SETTINGS (ADB)      │
│  - Re-enables after doze kills NAN           │
├─────────────────────────────────────────────┤
│  Layer 6: Deploy Script Automation           │
│  - Uninstall-first (Mercury OS stopped bug)  │
│  - Grants WRITE_SECURE_SETTINGS              │
│  - Sets AppOps location to allow             │
│  - Adds to deviceidle whitelist              │
│  - Enables WiFi and WiFi Aware               │
└─────────────────────────────────────────────┘
```

### Deploy Script (abbreviated)

```bash
#!/bin/bash
ADB=/opt/homebrew/bin/adb
PKG=com.meshtalk.app

for SERIAL in $SERIAL_A $SERIAL_B; do
  # Uninstall first (Mercury OS stopped-state bug workaround)
  $ADB -s $SERIAL uninstall $PKG 2>/dev/null

  # Install
  $ADB -s $SERIAL install -r app-debug.apk

  # Grant permissions
  $ADB -s $SERIAL shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS

  # AppOps — MUST be 'allow' not 'foreground' for HandlerThread safety
  $ADB -s $SERIAL shell appops set $PKG android:coarse_location allow
  $ADB -s $SERIAL shell appops set $PKG android:fine_location allow

  # Battery optimization bypass
  $ADB -s $SERIAL shell cmd deviceidle whitelist +$PKG

  # Enable WiFi + Aware
  $ADB -s $SERIAL shell svc wifi enable
  $ADB -s $SERIAL shell settings put secure aware_enabled 1

  # ADB reverse tunnel for server relay
  $ADB -s $SERIAL reverse tcp:8435 tcp:8435

  # Launch
  $ADB -s $SERIAL shell am start -n $PKG/.MeshTalkActivity
done
```

---

## Packet Format <a name="packet-format"></a>

### MeshTalk Packet (PacketCodec)

```
┌──────────┬───────────┬──────────────┬─────────────────┐
│  Type    │ ChannelID │   Sequence   │    Payload      │
│  (1B)   │   (1B)    │    (4B)      │  (variable)     │
├──────────┼───────────┼──────────────┼─────────────────┤
│  0x01   │   0-255   │  big-endian  │  Opus frame     │
│  AUDIO  │           │  uint32      │  (~40 bytes)    │
├──────────┼───────────┼──────────────┼─────────────────┤
│  0x02   │   0-255   │  uint32      │  UTF-8 JSON     │
│  CTRL   │           │              │                  │
├──────────┼───────────┼──────────────┼─────────────────┤
│  0x03   │   0-255   │  uint32      │  (empty)        │
│  PING   │           │              │                  │
├──────────┼───────────┼──────────────┼─────────────────┤
│  0x04   │   0-255   │  uint32      │  (empty)        │
│  PONG   │           │              │                  │
└──────────┴───────────┴──────────────┴─────────────────┘

Header: 6 bytes total (big-endian)
Audio payload: ~40 bytes (Opus @ 16kbps, 20ms frame)
Total audio packet: ~46 bytes
```

### Packet Types

| Type | Value | Payload | Purpose |
|------|-------|---------|---------|
| AUDIO | 0x01 | Opus-encoded frame | Voice data |
| CONTROL | 0x02 | UTF-8 JSON string | Spatial data, metadata |
| PING | 0x03 | Empty | Keepalive request |
| PONG | 0x04 | Empty | Keepalive response |

---

## Build and Deploy Pipeline <a name="build-deploy"></a>

### Prerequisites

```
- Android Studio (AGP 8.x)
- Kotlin 2.2.0
- NDK (for libopus + libspeexdsp cross-compilation)
- Android SDK 35 (compileSdk) / minSdk 32 (Android 12)
- RayNeo Mercury SDK (BaseMirrorActivity, TempleAction)
- ADB with USB debugging enabled on both glasses
```

### Native Libraries

Cross-compiled for `arm64-v8a` (Snapdragon XR2):

```
app/src/main/jniLibs/arm64-v8a/
  libopus.so          # Opus audio codec
  libspeexdsp.so      # Speex AEC (echo cancellation)
```

Build with NDK:
```bash
# From project root:
cd native/opus && ndk-build
cd native/speexdsp && ndk-build
```

### Gradle Configuration

```groovy
android {
    compileSdk = 35
    defaultConfig {
        minSdk = 32
        targetSdk = 34
        applicationId = "com.meshtalk.app"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Mercury SDK (RayNeo)
    implementation("com.ffalcon.mercury.android:sdk:...")

    // Audio
    implementation("com.konovalov.vad:silero:...")   // Silero VAD

    // Network
    implementation("com.squareup.okhttp3:okhttp:...")  // WebSocket client

    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:...")
}
```

### Deploy Sequence

```
1. ./gradlew assembleDebug
2. ./deploy.sh              (runs full deploy script above)
3. Monitor: adb logcat -s MeshTalk*,NanSupervisor,WifiAware*
```

---

## Module Inventory <a name="module-inventory"></a>

### Source Tree

```
app/src/main/java/com/meshtalk/app/
├── MeshTalkActivity.kt          # Main AR activity, gesture handling
├── MeshTalkApplication.kt       # App initialization
│
├── audio/
│   ├── AudioCaptureEngine.kt    # Mic capture → SharedFlow<ShortArray>
│   ├── AudioPlaybackEngine.kt   # Speaker output → bone conduction
│   ├── AudioMixer.kt            # Multi-peer stream mixing
│   ├── ClickRemovalFilter.kt    # Audio discontinuity smoothing
│   ├── HeadTracker.kt           # IMU → yaw/pitch orientation
│   ├── OpusCodec.kt             # JNI: libopus encode/decode
│   ├── SpatialAudioEngine.kt    # RSSI distance + head filtering
│   ├── SpeexAec.kt              # JNI: libspeexdsp echo cancel
│   └── VadEngine.kt             # Silero VAD wrapper
│
├── hud/
│   └── HudRenderer.kt           # WebView HUD controller
│
├── mesh/
│   ├── ChannelManager.kt        # Channel name ↔ service name
│   ├── MeshTransport.kt         # Transport interface + MeshPeer
│   ├── NanSupervisor.kt         # Self-healing NAN state machine
│   ├── PacketCodec.kt           # 6-byte header packet format
│   ├── PeerManager.kt           # Peer tracking and lifecycle
│   └── WifiAwareTransport.kt    # WiFi Aware NAN transport
│
├── service/
│   ├── AudioStreamClient.kt     # WebSocket streaming to relay
│   ├── BootReceiver.kt          # Auto-start on boot
│   └── MeshTalkService.kt       # Foreground service, pipeline
│
└── vox/
    └── VoxStateMachine.kt       # VOX: IDLE→SPEAKING→HANGOVER

app/src/main/assets/hud/
└── meshtalk.html                # Single-file HUD (HTML+CSS+JS)

app/src/test/java/com/meshtalk/app/
├── mesh/PacketCodecTest.kt      # Packet encode/decode tests
└── vox/VoxStateMachineTest.kt   # VOX state transition tests
```

### Module Dependency Graph

```
MeshTalkActivity
    │
    ├──▶ MeshTalkService (foreground service)
    │       │
    │       ├──▶ AudioCaptureEngine ──▶ SpeexAec
    │       │                              │
    │       │                        VadEngine ──▶ VoxStateMachine
    │       │                                         │
    │       ├──▶ OpusCodec ◀──────────────────────────┘
    │       │       │
    │       ├──▶ WifiAwareTransport ◀── NanSupervisor
    │       │       │
    │       ├──▶ AudioStreamClient (WebSocket)
    │       │       │
    │       ├──▶ PeerManager ◀── ChannelManager
    │       │
    │       ├──▶ AudioMixer ──▶ SpatialAudioEngine ──▶ HeadTracker
    │       │                      │
    │       │                ClickRemovalFilter
    │       │                      │
    │       └──▶ AudioPlaybackEngine
    │
    └──▶ HudRenderer (WebView)
```

---

## Data Flow Diagrams <a name="data-flow"></a>

### Transmit Path (Local Speaker)

```
Mic (10ms frames, 160 samples @ 16kHz)
    │
    ▼
SharedFlow<ShortArray> ─────────────┐
    │                                │
    ▼                                ▼
SpeexAec.processFrame()      AudioStreamClient
    │                        (raw PCM to server)
    ▼
VadEngine.feedFrame()
    │ (accumulates to 512 samples)
    ▼
VoxStateMachine.onVadResult()
    │
    ├── shouldTransmit = false → (discard, don't encode)
    │
    ├── shouldTransmit = true ──▶ OpusCodec.encode()
    │                                   │
    │                                   ▼
    │                            PacketCodec.encodeAudio()
    │                                   │
    │                                   ▼
    │                            transport.sendToAll()
    │                                   │
    │                    ┌──────────────┼──────────────┐
    │                    ▼              ▼              ▼
    │               NAN UDP      WebSocket       (future:
    │               (broken)     (working)        BLE)
    │
    └── State → HudRenderer.updateVox()
```

### Receive Path (Remote Speaker)

```
transport.onDataReceived(peerId, bytes)
    │
    ▼
PacketCodec.decode()
    │
    ├── TYPE_AUDIO ──▶ OpusCodec.decode()
    │                       │
    │                       ▼
    │                SpatialAudioEngine.process(peerId, pcm)
    │                       │ (RSSI attenuation + head filter)
    │                       ▼
    │                ClickRemovalFilter.process()
    │                       │
    │                       ▼
    │                AudioMixer.addPeerFrame(peerId, pcm)
    │                       │
    │                       ▼
    │                AudioPlaybackEngine.write(mixed)
    │                       │
    │                       ▼
    │                SpeexAec (reference signal)
    │
    ├── TYPE_CONTROL ──▶ JSON parse → PeerManager update
    │                       │
    │                       ▼
    │                SpatialAudioEngine.updatePeer(rssi, yaw)
    │                HudRenderer.updateRadar()
    │
    ├── TYPE_PING ──▶ sendTo(peerId, encodePong())
    │
    └── TYPE_PONG ──▶ PeerManager.updateLatency()
```

---

## Future Architecture Considerations

### NDP Resolution

If NAN data path is fixed (via vendor patch or raw-socket workaround):

```
Current:  Mic → Opus → WebSocket → Server → WebSocket → Opus → Speaker
Future:   Mic → Opus → UDP/NAN → aware_data0 → UDP/NAN → Opus → Speaker
```

This would eliminate the server relay, reduce latency from ~50-100ms
to ~5-10ms, and enable untethered operation.

### Multi-Hop Mesh

With working NDP, future architecture could support store-and-forward:

```
Glass A ──NAN──▶ Glass B ──NAN──▶ Glass C
         (direct)         (relay)
```

Glass B receives audio from A and retransmits to C if C is not in
A's direct NAN range. Requires packet TTL and duplicate detection
(already supported by PacketCodec's sequence numbers).

### Cloud Relay Deployment

For untethered operation without NDP:
- Deploy WebSocket relay on AWS/GCP/DigitalOcean
- Use glasses' WiFi (connected to internet AP) for relay path
- Latency: ~100-200ms (acceptable for walkie-talkie use)
- No ADB reverse tunnel needed
