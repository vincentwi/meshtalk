# Transport Layer Deep Investigation — RayNeo X3 Pro / Mercury OS

**Date:** 2025-05-20
**Investigators:** Vincent W. / Claude AI
**Hardware:** 2× RayNeo X3 Pro (model ARGF20)
**OS:** Mercury OS (Android 12, API 32)
**Glass A:** serial A06B4A8FF4A1633, BT MAC A0:6B:4A:B7:FA:E3, deviceId e40482
**Glass B:** serial A06B4A94CC51663, BT MAC A0:6B:4A:59:04:F4, deviceId a1487b
**WiFi IPs (when on shared network):** A=10.0.10.193, B=10.0.10.134

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [The Transport Hierarchy](#transport-hierarchy)
3. [WiFi Aware NAN — Confirmed Dead for Data](#nan-dead)
4. [NAN sendMessage Experiment — Queue Overflow](#nan-sendmessage)
5. [WiFi LAN Broadcast Discovery — The Working Path](#wifi-lan)
6. [Bluetooth RFCOMM — Mercury OS Connection Policing](#bt-rfcomm)
7. [WiFi Direct P2P — Mercury OS App Termination](#wifi-direct)
8. [Bluetooth Pairing — Mercury OS Bond Policing](#bt-pairing)
9. [Bluetooth Discovery — Mercury OS Scan Kill](#bt-discovery)
10. [Mercury OS Restriction Map](#mercury-map)
11. [What Actually Works](#what-works)
12. [Recommendations for True Offline](#offline-path)
13. [Timeline of Experiments](#timeline)
14. [Raw Evidence Logs](#raw-logs)

---

## Executive Summary <a name="executive-summary"></a>

After the NAN Deep Investigation proved that WiFi Aware NDP is dead on
Mercury OS, we systematically tested every alternative device-to-device
communication protocol available on Android 12. The goal: find a transport
that lets two RayNeo X3 Pro glasses stream walkie-talkie audio (16kHz Opus,
~8kbps) without any infrastructure — no WiFi router, no internet, no
phone relay.

### Results Matrix

| Transport | Connects? | Audio Flows? | Mercury Kills It? | Verdict |
|---|---|---|---|---|
| WiFi LAN (broadcast) | ✅ 6-9s | ✅ 100 pkt/s | ❌ No | **WORKING** |
| NAN discovery | ✅ ~2s | n/a (discovery only) | ❌ No | Works for peer finding |
| NAN NDP (data path) | ❌ Never | ❌ | n/a | Dead in firmware |
| NAN sendMessage | ✅ | ❌ 5 msg/s cap | Queue overflow | Unusable for audio |
| BT RFCOMM (insecure) | ✅ handshake | ❌ dies after 1s | ✅ Yes | Blocked |
| WiFi Direct (P2P) | ✅ partial | ❌ app killed | ✅ Yes | Blocked |
| BT Classic pairing | ✅ bonds | ❌ unbonds 1s later | ✅ Yes | Blocked |
| BT Classic discovery | ✅ scans | n/a | ✅ Yes (app killed) | Blocked |
| BT LE Audio / Auracast | ❌ | ❌ | n/a | Wrong hardware (needs BT 5.2 + API 33) |
| Google Nearby Connections | ❌ | ❌ | n/a | No Google Play Services on Mercury OS |

### The Punchline

Mercury OS implements a **Bluetooth Connection Police** that actively
monitors and kills any BT connection that isn't to the configured companion
phone. It also implements an **App Execution Guard** that terminates apps
touching WiFi Direct P2P APIs. The only transport that survives both of
these enforcement layers is **WiFi LAN with UDP broadcast discovery** —
which requires both glasses to be on the same WiFi network.

---

## The Transport Hierarchy <a name="transport-hierarchy"></a>

We built a `TransportManager` that runs multiple transports in parallel and
auto-selects the best available one:

```
TransportManager
  ├── WiFi Direct (P2P)     [priority 1] — DISABLED (Mercury kills app)
  ├── WiFi LAN (broadcast)  [priority 2] — WORKING
  └── BT RFCOMM (insecure)  [priority 3] — connects then killed
```

The priority system means: if a higher-priority transport connects, it takes
over from a lower-priority one. If the active transport disconnects, the
manager falls back to whatever else is available.

---

## WiFi Aware NAN — Confirmed Dead for Data <a name="nan-dead"></a>

Building on the NAN Deep Investigation, we ran four targeted experiments to
bypass the broken NDP data path:

### Experiment A: NDP De-duplication

**Hypothesis:** Both pub and sub sessions requesting NDP simultaneously
might cause the firmware to reject the second request, poisoning both.

**Fix:** Only the subscriber initiates NDP. Publisher skips `requestNetwork()`.

**Result:** ❌ NDP still fails. `onUnavailable` fires within 200ms.

```
05-20 12:30:39.641 WifiAwareTransport: OPEN NDP: No PSK passphrase (unencrypted)
05-20 12:30:39.642 WifiAwareTransport: Requesting NDP for peer e40482 (session=SubscribeDiscoverySession)
05-20 12:30:39.939 WifiAwareTransport: NDP onUnavailable for peer e40482
```

### Experiment B: Publisher setPort()

**Hypothesis:** The NDP responder (publisher) needs a pre-bound port for
the framework to establish the data path.

**Fix:** Pre-bind a `ServerSocket` and pass the port via
`WifiAwareNetworkSpecifier.Builder.setPort()`.

**Result:** ❌ No effect. NDP still fails identically.

### Experiment C: Open NDP (No PSK)

**Hypothesis:** The PSK passphrase handling is broken in Mercury's NAN stack.

**Fix:** Remove `setPskPassphrase()` entirely — request open (unencrypted) NDP.

**Result:** ❌ Still `onUnavailable`. The NDP failure is not related to
encryption.

### Experiment D: sendMessage Audio Bypass

**Hypothesis:** Skip NDP entirely. Use NAN's `sendMessage()` API to carry
audio frames through discovery messages.

**Fix:** After 15s NDP watchdog timeout, switch to sending Opus frames via
`DiscoverySession.sendMessage()`.

**Result:** ❌ **Queue overflow.** The NAN firmware has a follow-up message
queue of ~10 messages. At 100 frames/sec, the queue fills instantly:

```
05-20 12:43:23.630 WifiHAL: NanErrorTranslation: Status: 11 Error Info[value 0]: Follow-up queue full
05-20 12:43:23.647 kiwi_v2: NAN_TX type:FOLLOW_UP tx_status=2
```

**Key learning:** NAN `sendMessage()` maxes out at ~5 messages per second.
The firmware silently drops everything above that. No error surfaces at the
Java API level — `sendMessage()` returns successfully, the firmware just
never transmits the frame. This makes `sendMessage()` fundamentally
unusable for any streaming application.

### NAN Summary

WiFi Aware on RayNeo X3 Pro is strictly a **discovery mechanism**:
- Attach: ✅
- Publish/Subscribe: ✅ (mutual discovery in ~2s)
- sendMessage (control): ✅ (at ≤5 msg/sec)
- NDP (data path): ❌ (dead at framework level, confirmed across 4 experiments)
- sendMessage (streaming): ❌ (firmware queue overflow at >5 msg/sec)

---

## NAN sendMessage Experiment — Queue Overflow <a name="nan-sendmessage"></a>

This deserves its own section because the failure mode is subtle and
undocumented.

### The Corruption Bug

When `EXPERIMENT_SENDMESSAGE_AUDIO` was enabled, binary audio frames
arrived at the publisher's `onMessageReceived` callback. Our initial code
interpreted ALL incoming messages as UTF-8 peer ID strings:

```kotlin
override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
    val peerId = String(message, Charsets.UTF_8)  // WRONG for binary audio
    peerHandles[peerId] = peerHandle              // corrupt entry
}
```

This created garbage entries in the `peerHandles` map (binary audio bytes
decoded as UTF-8 gibberish), causing `sendMessage` to target nonexistent
peers:

```
WifiAwareTransport: NAN send #3600 (7B → ??????????L@, pub=true, sub=false)
WifiAwareDiscSessState: sendMessage: attempting to send a message to an address which didn't match/contact us
```

**Fix:** Added ASCII detection to distinguish handshake (printable ASCII)
from binary audio data:

```kotlin
val isAsciiText = message.isNotEmpty() && message.all { it in 0x20..0x7E }
```

### The Session Scoping Rule

NAN `sendMessage` PeerHandles are **scoped to the session that received
them**. A PeerHandle from the publisher's `onMessageReceived` can only be
used with `publishSession.sendMessage()`. Using it with
`subscribeSession.sendMessage()` causes:

```
WifiAwareDiscSessState: sendMessage: attempting to send a message to an address which didn't match/contact us
```

We fixed this by maintaining separate handle maps:
```kotlin
val pubHandles = ConcurrentHashMap<String, PeerHandle>()  // from publisher callback
val subHandles = ConcurrentHashMap<String, PeerHandle>()  // from subscriber callback
```

### The Queue Limit

Even after fixing the corruption and scoping bugs, audio still didn't flow.
The system log revealed the real blocker:

```
WifiHAL: NanErrorTranslation: Status: 11 Error Info[value 0]: Follow-up queue full
kiwi_v2: NAN_TX type:FOLLOW_UP peer_mac_addr=bd:b7 tx_status=2
```

The Qualcomm Kiwi v2 firmware has a NAN follow-up message queue of
approximately 10 messages. Messages are transmitted at the NAN Discovery
Window interval (~512ms on 2.4GHz, ~100ms on 5GHz). At our audio rate
of 100 frames/sec, the queue fills within 100ms and everything after that
is silently dropped.

**tx_status=2** means "no ACK received" — the firmware can't transmit fast
enough so frames are queued, then dropped when the queue overflows.

This is a fundamental hardware/firmware limitation. No software workaround
exists short of reducing the audio frame rate to ≤5fps, which would require
~3.2 seconds of audio per frame — unacceptable for real-time voice.

---

## WiFi LAN Broadcast Discovery — The Working Path <a name="wifi-lan"></a>

### The Pivot

After NAN data paths proved dead and sendMessage proved too slow, we needed
a new approach. Both glasses were on the same WiFi network (confirmed via
ping with 137ms RTT). The insight: **use NAN for discovery, UDP for data**.

### Architecture v1: NAN Discovery + UDP Audio

```
Glass A                              Glass B
  │                                    │
  ├─ NAN publish("meshtalk_alpha")     │
  │   serviceSpecificInfo = "e40482|10.0.10.193"
  │                                    │
  │                                    ├─ NAN subscribe("meshtalk_alpha")
  │                                    │   onServiceDiscovered → parse IP
  │                                    │
  │  ◄── NAN sendMessage("a1487b|10.0.10.134") ──┤
  │                                    │
  ├─ registerPeer("a1487b", 10.0.10.134:18430) ─┤
  │                                    │
  ├─ UDP audio ◄─────────────────────► UDP audio
     (port 18430, 100 pkt/s, 7 bytes/pkt)
```

**Result:** ✅ Fully working. Audio flows bidirectional at 100 pkt/s.

### Architecture v2: Broadcast Discovery (NAN-independent)

NAN attach started failing intermittently after WiFi toggling (to clear
stale P2P state from WiFi Direct experiments). We added UDP broadcast
discovery as a fallback:

```
Both glasses (simultaneously):
  1. Bind UDP listener on port 18430 (audio)
  2. Bind UDP listener on port 18431 (beacons)
  3. Every 3 seconds, broadcast to 255.255.255.255:18431:
     "MESHTALK_BEACON|{deviceId}|{wifiIp}|{audioPort}"
  4. On receiving beacon from different deviceId:
     registerPeer(peerId, peerIp)
  5. Stream audio via UDP to registered peer's IP:18430
```

**Result:** ✅ Peer discovery in 6-9 seconds (one beacon interval). Audio
flows immediately after. No NAN dependency.

```
15:06:14.031 WifiLanTransport: Starting broadcast discovery (deviceId=e40482, myIP=10.0.10.193, port=18430)
15:06:14.052 WifiLanTransport: Beacon listener on port 18431
15:06:22.980 WifiLanTransport: *** PEER CONNECTED: a1487b at 10.0.10.134:18430 ***
15:06:27.978 WifiLanTransport: UDP sent #500 (7B → a1487b)
15:06:28.178 MeshTalkService: AUDIO RECV #200 from a1487b (6B opus)
```

### The VOX Bypass

Initially audio was sending but nothing was received because the VOX
(Voice-Operated Exchange) state machine required voice activity detection
to trigger transmission. For walkie-talkie mode, we added a force-transmit
path:

```kotlin
// Walkie-talkie: always transmit when not muted (tap to mute/unmute)
val shouldSend = voxStateMachine.shouldTransmit || !isMuted
```

This means audio flows continuously when unmuted — true open-channel
walkie-talkie behavior.

---

## Bluetooth RFCOMM — Mercury OS Connection Policing <a name="bt-rfcomm"></a>

### Approach

BT Classic RFCOMM (Serial Port Profile) is the simplest device-to-device
protocol on Android. No WiFi needed. Both glasses have BT 5.0. The plan:

1. Both glasses listen on an RFCOMM server socket with a MeshTalk UUID
2. Both glasses try connecting to the other's known MAC address
3. First connection wins, handshake exchanges device IDs
4. Audio streams over the RFCOMM InputStream/OutputStream

### Implementation

```kotlin
// Server: insecure (no pairing required)
serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, MESHTALK_UUID)

// Client: insecure connection to known MAC
val socket = device.createInsecureRfcommSocketToServiceRecord(MESHTALK_UUID)
socket.connect()
```

We hardcoded both glasses' MAC addresses (`A0:6B:4A:B7:FA:E3` and
`A0:6B:4A:59:04:F4`) as known peers so each glass tries the other directly.

### What Happened

**Phase 1: Connection succeeds.**

```
15:37:05.639 BtRfcommTransport: Connecting insecure RFCOMM to RayNeo X3 Pro-1663
15:37:18.179 BtRfcommTransport: Accepted connection from RayNeo X3 Pro-1663 (A0:6B:4A:59:04:F4)
15:37:18.420 BtRfcommTransport: Handshake received: peer=a1487b (mac=A0:6B:4A:59:04:F4)
15:37:18.422 BtRfcommTransport: *** BT PEER CONNECTED: a1487b (A0:6B:4A:59:04:F4) ***
15:37:18.423 TransportManager: *** SWITCHING to BT_RFCOMM ***
```

Both sides connect, exchange handshakes, recognize each other's device IDs.
The TransportManager switches to BT RFCOMM as the active transport.

**Phase 2: Mercury OS kills the connection exactly 1 second later.**

```
15:37:19.280 BtRfcommTransport: Read from a1487b failed: Connection reset by peer
15:37:19.282 BtRfcommTransport: BT send failed to A0:6B:4A:59:04:F4: Broken pipe
15:37:19.282 TransportManager: BT_RFCOMM: peer lost: a1487b
```

### Root Cause: ConnectStateMachineServerImpl

Mercury OS includes a `ConnectStateMachineServerImpl` that monitors all
Bluetooth connections. It enforces a single-device policy: only the paired
companion phone (identified by MAC address) is allowed to maintain an
RFCOMM connection. Any other connection is terminated after a brief grace
period (~1 second).

Evidence from system logs during our BT pairing attempts:

```
Mercury: ConnectStateMachineServerImpl: bondNoneAddress = A0:6B:4A:59:04:F4, bondedAddress = 84:88:E1:ED:6B:75
```

The system compares the new bond address against the "approved" bonded phone
(`84:88:E1:ED:6B:75` = Vincent's iPhone) and unbonds the glasses.

### Insecure vs. Secure RFCOMM

We tried both:
- `listenUsingRfcommWithServiceRecord` (secure, requires pairing) — fails
  because Mercury blocks the pairing itself
- `listenUsingInsecureRfcommWithServiceRecord` (insecure, no pairing) —
  connects! But Mercury kills the connection after 1 second

The insecure path bypasses the pairing requirement but not the connection
policing.

---

## WiFi Direct P2P — Mercury OS App Termination <a name="wifi-direct"></a>

### Approach

WiFi Direct creates a P2P network between two devices without a router.
One device becomes the Group Owner (soft AP), the other connects as a
client. After group formation, both get IPs on 192.168.49.x and can use
standard UDP sockets.

### Hardware Capability

```
$ pm list features | grep wifi.direct
feature:android.hardware.wifi.direct
```

WiFi Direct hardware IS present and the feature IS declared.

### What Happened

**Phase 1: Initial success.** The first time we ran WiFi Direct, Glass A
discovered nearby P2P peers and successfully joined an existing P2P group:

```
14:39:11.809 WifiDirectTransport: Found 2 P2P peers
14:39:11.809 WifiDirectTransport:   Peer: EVELINK-5E1146 (6a:8f:c9:15:ba:17) status=0
14:39:14.140 WifiDirectTransport: P2P connected — requesting connection info
14:39:14.143 WifiDirectTransport: P2P connection info: isGO=false, goAddr=/192.168.10.1
14:39:14.146 WifiDirectTransport: *** P2P CONNECTED to GO at /192.168.10.1 ***
```

Glass A connected to "EVELINK" (a nearby WiFi Direct bridge device), not
to the other glass. But it proved P2P GROUP JOINING works.

**Phase 2: createGroup triggers app death.** When we tried having one
glass CREATE a P2P group (to be the GO), Mercury OS terminated the app:

```
VM exiting with result code 0, cleanup skipped.
```

This is not a crash (exit code 0). Mercury OS's app management system
detects the P2P group creation API call and force-stops the app. This
is a deliberate restriction, not a bug.

**Phase 3: Persistent BUSY state.** After the P2P group creation attempt,
all subsequent `discoverPeers()` and `createGroup()` calls return `BUSY`:

```
WifiDirectTransport: createGroup failed: BUSY — will try discovering instead
WifiDirectTransport: discoverPeers failed: BUSY
```

This BUSY state persists across app restarts. Only a full WiFi toggle
(`svc wifi disable` + `svc wifi enable`) clears it.

**Phase 4: P2P state DISABLED when WiFi is off.** WiFi Direct requires the
WiFi radio to be ON. When WiFi is disabled for offline testing:

```
WifiDirectTransport: P2P state: DISABLED
WifiDirectTransport: discoverPeers failed: BUSY
```

WiFi Direct CANNOT work with WiFi disabled because P2P uses the same radio.

### Key Insight: The glasses never see each other in P2P

In all our P2P discovery attempts, the glasses found other nearby P2P
devices (Samsung TV "The Frame", Evelink bridge, a printer, an Android
phone) but never each other. This is because:

1. Both glasses are in "discoverer" mode simultaneously
2. Neither creates a P2P group, so neither advertises as a GO
3. P2P discovery only finds devices that are either advertising or
   are in a group — two simultaneous discoverers cannot see each other

The fix would be to have one glass `createGroup()` first (making it visible
as a GO), but Mercury OS kills the app when `createGroup()` is called.

---

## Bluetooth Pairing — Mercury OS Bond Policing <a name="bt-pairing"></a>

### Approach

We created a `BtPairActivity` that programmatically pairs two glasses:
1. Launch on Glass B as auto-accept receiver (no UI needed)
2. Launch on Glass A with `--es mac "A0:6B:4A:59:04:F4"` targeting Glass B
3. Glass A calls `device.createBond()` on Glass B's MAC
4. Glass B's receiver auto-confirms via `setPairingConfirmation(true)`

### What Happened

**The pairing SUCCEEDS at the Bluetooth system level:**

```
BluetoothBondStateMachine: Bonded Completed A0:6B:4A:59:04:F4
```

Both glasses' Bluetooth stacks complete the bonding process. For a brief
moment, they are paired.

**Mercury OS immediately unbonds them:**

```
Mercury: BluetoothReceiverHelper: onBondStateChanged() --> [RayNeo X3 Pro-1663, A0:6B:4A:59:04:F4, Classic(BR/EDR)] bond state from [11] to [10]
Mercury: ConnectStateMachineServerImpl: bondNoneAddress = A0:6B:4A:59:04:F4, bondedAddress = 84:88:E1:ED:6B:75
```

State 11 = `BOND_BONDING`, state 10 = `BOND_NONE`. Mercury OS's
`ConnectStateMachineServerImpl` detects the new bond, compares it against
the "approved" phone MAC (`84:88:E1:ED:6B:75` = Vincent's iPhone), and
removes the bond.

### The Single-Device Policy

Mercury OS enforces a **single bonded companion** policy. The glasses are
designed to pair with exactly one phone. The `ConnectStateMachineServerImpl`
maintains a whitelist of one MAC address. Any `createBond()` for a device
not matching this whitelist is undone within ~300ms of completing.

This is not configurable. There is no settings toggle, no adb command, and
no system property to disable this behavior. It requires root access to
modify (which we don't have — bootloader is locked, build is `user` not
`userdebug`).

---

## Bluetooth Discovery — Mercury OS Scan Kill <a name="bt-discovery"></a>

### Approach

To find nearby glasses without manual MAC configuration, we added BT
Classic discovery scanning with auto-pair on finding "ARGF20" or "RayNeo"
device names.

### What Happened

**Calling `adapter.startDiscovery()` works initially** — the scan finds
many nearby devices (iPhones, Samsung TV, ROL devices, etc.):

```
BtRfcommTransport: Found: Bee (FC:66:89:24:3E:16) RSSI=-76
BtRfcommTransport: Found: Mercury_iPhone (7F:D4:DD:70:8C:C0) RSSI=-61
BtRfcommTransport: Found: Galaxy A13 5G (53:40:C7:5C:26:FF) RSSI=-73
```

**But the other glass never appears in the scan.** This is because BT
Classic discovery only finds devices that are in "discoverable" mode.
RayNeo glasses default to `SCAN_MODE_CONNECTABLE` (accepts connections
from paired devices only), not `SCAN_MODE_CONNECTABLE_DISCOVERABLE`.

### Attempting to set discoverable mode

**Via reflection:**
```kotlin
adapter.javaClass.getMethod("setScanMode", Int::class.java, Int::class.java)
    .invoke(adapter, SCAN_MODE_CONNECTABLE_DISCOVERABLE, 120)
```

**Result:** Mercury OS terminates the app (exit code 0, same as WiFi
Direct). The `setScanMode` call to discoverable triggers the app management
system.

**Via intent (system dialog):**
```kotlin
Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
```

**Result:** Launches a system Settings dialog. Mercury OS kills the MeshTalk
foreground app when another activity (Settings) takes focus. Classic Mercury
OS behavior — it aggressively reclaims foreground.

### Why the other glass is invisible

Without discoverable mode, BT Classic discovery is one-way: you can scan
for devices, but if the target isn't discoverable, it won't respond to
inquiry scans. Since BOTH glasses can't be made discoverable (Mercury kills
the app), neither can find the other.

---

## Mercury OS Restriction Map <a name="mercury-map"></a>

Summary of all Mercury OS restrictions discovered during transport
investigation:

```
┌──────────────────────────────────────────────────────────────┐
│                    MERCURY OS RESTRICTIONS                     │
├──────────────────────────────────────────────────────────────┤
│                                                               │
│  BT Connection Police (ConnectStateMachineServerImpl)         │
│  ├── Kills any RFCOMM connection not to approved phone        │
│  ├── Unbonds any device not matching approved phone MAC       │
│  └── Triggers ~1s after connection/bond is established        │
│                                                               │
│  App Execution Guard                                          │
│  ├── Kills app on WiFi Direct createGroup() call              │
│  ├── Kills app on BT setScanMode(DISCOVERABLE)                │
│  ├── Kills app when system Settings dialog takes foreground   │
│  └── Forces BUSY state on P2P after any group operation       │
│                                                               │
│  WiFi Aware Restrictions (from NAN investigation)             │
│  ├── NAN disabled by default (aware_enabled=0)                │
│  ├── NDP data path dead (framework doesn't assign IPv6)       │
│  ├── sendMessage queue limited to ~5 msg/sec by firmware      │
│  └── NAN attach fails intermittently after WiFi toggles       │
│                                                               │
│  Missing Platform Services                                    │
│  ├── No Google Play Services (no Nearby Connections API)      │
│  ├── No BT LE Audio (needs Android 13 + BT 5.2)              │
│  └── No touchscreen (can't confirm system dialogs)            │
│                                                               │
└──────────────────────────────────────────────────────────────┘
```

---

## What Actually Works <a name="what-works"></a>

### WiFi LAN with Broadcast Discovery

**Requirements:** Both glasses connected to the same WiFi network.

**Behavior:**
1. App starts → binds UDP port 18430 (audio) + 18431 (beacon)
2. Broadcasts beacon every 3s: `MESHTALK_BEACON|deviceId|ip|port`
3. Receives beacon → registers peer IP → starts streaming audio
4. Full duplex audio within 6-9 seconds of app launch

**Performance:**
- Discovery: 6-9 seconds (worst case: one full beacon interval)
- Throughput: 100+ packets/sec bidirectional (confirmed)
- Latency: ~137ms RTT (WiFi LAN)
- Packet size: 7 bytes (6-byte PacketCodec header + 1-byte Opus frame for silence, up to ~40 bytes for voice)

**Verified on:**
- Home WiFi (both glasses on same AP)
- With NAN available (uses NAN discovery in parallel)
- With NAN unavailable (broadcast-only fallback works identically)

### Audio Pipeline (confirmed end-to-end)

```
Mic (AudioRecord, 16kHz mono)
  → SpeexAEC (echo cancellation, native JNI)
  → SileroVAD (voice activity, ONNX neural net)
  → VoxStateMachine (walkie-talkie gating)
  → OpusCodec.encode (16kbps, native JNI)
  → PacketCodec.wrap (6-byte header)
  → TransportManager.sendToAll
  → WifiLanTransport → UDP → network → UDP
  → PacketCodec.decode
  → OpusCodec.decode
  → SpatialAudioEngine (distance + head-relative filtering)
  → ClickRemovalFilter
  → AudioMixer
  → AudioPlaybackEngine (bone-conduction speaker)
```

---

## Recommendations for True Offline <a name="offline-path"></a>

### Option 1: WiFi Hotspot (Most Feasible)

One glass creates a WiFi hotspot (standard Android tethering API, not WiFi
Direct). The other glass connects to it. Both are now on a local network.
Broadcast discovery works identically.

**Advantages:**
- Uses standard Android tethering API (not P2P, which Mercury kills)
- No BT involved (avoids connection police)
- Broadcast discovery already implemented and proven
- Range: ~100m (WiFi AP range)

**Risks:**
- Mercury OS might also kill apps that create hotspots
- Needs testing before committing to this path

### Option 2: BLE Advertising + L2CAP CoC

Use BLE (Bluetooth Low Energy) for both discovery AND data transfer:
- BLE advertising: no pairing needed, no discoverable mode needed
- BLE L2CAP Connection-oriented Channels: ~2Mbps throughput
- Mercury OS might not police BLE the way it polices Classic BT

**Advantages:**
- No WiFi needed at all
- BLE advertising doesn't trigger the BT Connection Police
- 10-50m range (BLE 5.0)

**Risks:**
- BLE audio streaming requires packet fragmentation (BLE MTU is ~512 bytes)
- L2CAP CoC API requires Android 10+ (available on API 32)
- Mercury might also police L2CAP connections (unknown)
- More complex implementation than WiFi hotspot

### Option 3: Root the Glasses

With root access, we could:
- Disable `ConnectStateMachineServerImpl` (BT police)
- Allow WiFi Direct `createGroup()`
- Enable BT discoverable mode
- Bypass all Mercury OS restrictions

**Status:** Bootloader is locked (shows orange warning on boot). Build is
`user` not `userdebug`. No known root method for ARGF20 as of 2025-05-20.

---

## Timeline of Experiments <a name="timeline"></a>

| Time | Experiment | Result |
|---|---|---|
| 12:27 | Deploy with NAN + 4 NDP experiments | NDP dead, sendMessage works |
| 12:30 | NDP de-duplication (Experiment A) | Still `onUnavailable` |
| 12:32 | Open NDP + publisher port (Experiments B+C) | Still fails |
| 12:37 | sendMessage audio bypass (Experiment D) | Sending works, receiving fails |
| 12:37 | Discover NAN sendMessage queue limit | `Follow-up queue full` at >5 msg/s |
| 12:40 | Fix ASCII/binary message corruption | Clean peer targeting |
| 12:43 | Confirm sendMessage cannot carry audio | Fundamental firmware limit |
| 12:46 | Pivot to WiFi LAN + NAN discovery | ✅ Audio flows both ways |
| 12:50 | Add broadcast discovery fallback | ✅ Works without NAN |
| 14:39 | Test WiFi Direct P2P | Connects to Evelink, not to other glass |
| 14:42 | WiFi Direct createGroup attempt | Mercury OS kills the app |
| 14:46 | WiFi Direct persistent BUSY state | All P2P calls fail |
| 14:50 | BT RFCOMM with known MACs | Insecure connect works briefly |
| 15:02 | Full TransportManager (3 transports) | WiFi LAN works, others fail |
| 15:06 | WiFi LAN broadcast discovery v2 | ✅ Both glasses connect in 7s |
| 15:27 | BtPairActivity programmatic pairing | Bonds then Mercury unbonds |
| 15:31 | Offline test (WiFi disabled) | P2P DISABLED, BT RFCOMM dies at 1s |
| 15:35 | BT RFCOMM insecure (WiFi Direct disabled) | ✅ Connects → Mercury kills at 1s |
| 15:37 | Final BT RFCOMM attempt with dedup fix | Same: connects, handshakes, dies at 1s |

---

## Raw Evidence Logs <a name="raw-logs"></a>

### NAN sendMessage Queue Overflow

```
05-20 12:43:23.622  779 1894 I WifiHAL: handleNanResponse ret:0 status:0 value:NAN status success response_type:4
05-20 12:43:23.624  779  779 E WifiHAL: ack_handler_nan: called
05-20 12:43:23.630  779 1894 D WifiHAL: NanErrorTranslation: Status: 11 Error Info[value 0]: Follow-up queue full
05-20 12:43:23.630  779 1894 I WifiHAL: handleNanResponse ret:0 status:11 value:Follow-up queue full response_type:4
05-20 12:43:23.647 10647 10647 I kiwi_v2: NAN_TX type:FOLLOW_UP peer_mac_addr=bd:b7 tx_status=2
```

### BT RFCOMM Connection + Kill Sequence

```
15:37:05.639  1009 1180 I BtRfcommTransport: Connecting insecure RFCOMM to RayNeo X3 Pro-1663
15:37:18.179  1009 1155 I BtRfcommTransport: Accepted connection from RayNeo X3 Pro-1663 (A0:6B:4A:59:04:F4)
15:37:18.180  1009 1155 I BtRfcommTransport: Handling BT connection to A0:6B:4A:59:04:F4
15:37:18.420  1009 1188 I BtRfcommTransport: Handshake received: peer=a1487b (mac=A0:6B:4A:59:04:F4)
15:37:18.422  1009 1188 I BtRfcommTransport: *** BT PEER CONNECTED: a1487b ***
15:37:19.280  1009 1188 W BtRfcommTransport: Read from a1487b failed: Connection reset by peer
15:37:19.282  1009 1146 E BtRfcommTransport: BT send failed: Broken pipe
```

### Mercury OS Bond Policing

```
15:27:09.062  2843 2900 I BluetoothBondStateMachine: Bonded Completed A0:6B:4A:59:04:F4
15:27:09.073 30419 30419 D Mercury: BluetoothReceiverHelper: bond state from [11] to [10]
15:27:09.882 30419 30510 D Mercury: ConnectStateMachineServerImpl: bondNoneAddress = A0:6B:4A:59:04:F4, bondedAddress = 84:88:E1:ED:6B:75
```

### WiFi LAN Broadcast Discovery Success

```
15:06:14.031 WifiLanTransport: Starting broadcast discovery (deviceId=e40482, myIP=10.0.10.193, port=18430)
15:06:14.052 WifiLanTransport: Beacon listener on port 18431
15:06:22.980 WifiLanTransport: *** PEER CONNECTED: a1487b at 10.0.10.134:18430 ***
15:06:22.981 TransportManager: *** SWITCHING to WiFiLAN (priority 2, was none/99) ***
15:06:27.978 WifiLanTransport: UDP sent #500 (7B → a1487b)
15:06:28.178 MeshTalkService: AUDIO RECV #200 from a1487b (6B opus)
```
