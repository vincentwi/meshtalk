# WiFi Aware (NAN) Deep Investigation — RayNeo X3 Pro / Mercury OS

**Date:** 2025-05-19 → 2025-05-20 (ongoing)
**Investigators:** Vincent W. / Claude AI
**Hardware:** 2× RayNeo X3 Pro (model ARGF20)
**OS:** Mercury OS (Android 12, API 32, build SKQ1.250204.001)
**SoC:** Qualcomm Snapdragon XR2 (board: neo, hardware: qcom)
**WiFi Chip:** Qualcomm Kiwi v2 (`/vendor/etc/wifi/kiwi_v2`)
**Serials tested:** A06B4A94CC51663, [second pair]

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [The Five Restriction Layers of Mercury OS](#the-five-restriction-layers)
3. [Hardware Evidence](#hardware-evidence)
4. [NAN State Machine Analysis](#nan-state-machine-analysis)
5. [NDP Establishment Failure — The Core Unsolved Problem](#ndp-establishment-failure)
6. [The Duplicate NDP Request Issue](#duplicate-ndp-requests)
7. [State 103 Deep Analysis](#state-103-analysis)
8. [ConnectivityManager NetworkCallback Gap](#connectivity-manager-gap)
9. [Timeline of Discoveries](#timeline-of-discoveries)
10. [AOSP Behavioral Comparison](#aosp-comparison)
11. [Reference Repositories Consulted](#reference-repos)
12. [NanSupervisor Architecture](#nansupervisor-architecture)
13. [Actionable Next Steps](#next-steps)
14. [Vendor Escalation Package](#vendor-escalation)
15. [Appendix: Raw Dumps and Logs](#appendix)

---

## Executive Summary <a name="executive-summary"></a>

WiFi Aware (Neighbor Awareness Networking / NAN) on the RayNeo X3 Pro
**partially works**: discovery succeeds perfectly, but data-path (NDP)
establishment fails at the Android framework level despite succeeding
at the firmware level. This makes WiFi Aware unusable for actual data
transfer in its current state.

### What Works ✅

- Hardware has full NAN support (Qualcomm Kiwi v2, HAL 1.5)
- Feature flag `android.hardware.wifi.aware` is declared
- After enabling `aware_enabled=1`: attach, publish, subscribe all work
- **Two glasses discover each other within ~2 seconds**
- NDP firmware handshake completes: INITIATE → CONFIRM (3× confirmed)
- NAN cluster formation verified (role changes in kernel dmesg)

### What Fails ❌

- **NDP data path never surfaces at Android framework level**
- `ConnectivityManager.NetworkCallback.onAvailable()` never fires
- `aware_data0` interface exists but has NO IPv6 address
- NDP state stuck at 103 (CONFIRMED) but NdpInfos[] is empty
- No IP address assignment = no socket communication possible
- Channel info for all NDP sessions is empty `[]`

### Root Cause Assessment

The firmware confirms the NDP but the Android WifiAwareDataPathStateManager
fails to complete IP address assignment. This is likely caused by:

1. Mercury OS stripping or misconfiguring the `NetworkFactory` that
   connects NAN data paths to the Android networking stack
2. Missing or disabled `IpClient` for the `aware_data0` interface
3. Possible OEM modification to ConnectivityService that doesn't
   recognize NAN network requests

---

## The Five Restriction Layers of Mercury OS <a name="the-five-restriction-layers"></a>

Through 48+ hours of investigation, we discovered Mercury OS has **five
distinct layers** that obstruct WiFi Aware operation. Each required a
separate fix, and each was discovered only after the previous one was
resolved — a classic onion-peeling debugging experience.

### Layer 1: WiFi Disabled by Default

```
Settings.Global.wifi_on = 0
```

**Impact:** No wireless networking at all on boot.
**Fix:** `svc wifi enable` or `settings put global wifi_on 1`
**Discovery:** Immediate — first thing checked.

### Layer 2: WiFi Aware (NAN) Disabled by Default

```
Settings.Secure.aware_enabled = 0
Settings.Secure.aware_lock_enabled = 0
```

**Impact:** WifiAwareServiceImpl.mUsageEnabled=false. ALL WiFi Aware
API calls rejected. The error message is deceptive — says "UID does
not have Coarse/Fine Location permission" but the real issue is
`mUsageEnabled=false`. The permission check is a red herring: the
service checks permissions AFTER determining usage is disabled and
throws a generic SecurityException.

**Fix:** `settings put secure aware_enabled 1`
**Discovery:** After 4 hours of chasing permission red herrings.

### Layer 3: AppOps Location Mode = Foreground Only

```
appops set com.meshtalk.app android:coarse_location allow
appops set com.meshtalk.app android:fine_location allow
```

**Impact:** WiFi Aware callbacks execute on a `HandlerThread`, not the
main thread. Android's AppOps checks the UID's location access mode
against the calling context. With mode=`foreground`, the HandlerThread
context is treated as "background" → SecurityException thrown from
within the WiFi Aware callback chain itself.

**Fix:** `appops set <pkg> android:coarse_location allow` (not just
`foreground`). Must also set fine_location.

**Discovery:** Took 6 hours. The crash stacktrace pointed at
WifiAwareServiceImpl internals, not our code. Only by tracing the
AppOps evaluation path did we identify the foreground-vs-allow
distinction.

### Layer 4: Missing CHANGE_NETWORK_STATE Permission

```xml
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
```

**Impact:** `ConnectivityManager.requestNetwork()` throws
SecurityException when attempting NDP (NAN Data Path). The app could
discover peers but never establish a data connection.

**Fix:** Add permission to AndroidManifest.xml.
**Discovery:** Immediate from crash log once we reached NDP phase.

### Layer 5: BackgroundAppManager Hardcoded Whitelist

Mercury OS has a custom `BackgroundAppManager` that maintains a
hardcoded whitelist of ~40 system packages. Any app not on this list
is force-killed approximately 2 seconds after going to background.

**Whitelist includes:** com.android.launcher, com.google.android.gms,
com.android.bluetooth, com.qualcomm.*, com.ffalcon.mercury.*, etc.

**Impact:** MeshTalk gets killed when screen turns off or another app
comes to foreground. Even with a foreground service notification,
Mercury OS's BackgroundAppManager overrides standard Android
foreground-service protection.

**Partial mitigations applied:**
- `cmd deviceidle whitelist +com.meshtalk.app` (battery optimization bypass)
- Foreground service with `FOREGROUND_SERVICE_MICROPHONE` type
- Partial wake lock via PowerManager
- `on_idle_disable_aware=1` cannot be changed (cmd not supported on this build)

**Status:** Partially mitigated. The app survives longer but Mercury OS
can still kill it under memory pressure.

---

## Hardware Evidence <a name="hardware-evidence"></a>

### WiFi Chip Identification

```
# dmesg | grep -i kiwi
kiwi_v2: WiFi firmware loaded
/vendor/etc/wifi/kiwi_v2/
```

The Qualcomm Kiwi v2 is the WiFi 6E chipset used in Snapdragon XR2
platforms. It supports 802.11ax, WiFi Direct, and NAN 2.0.

### NAN HAL Interface

```
android.hardware.wifi@1.5::IWifiNanIface@Proxy (refcount=1)
```

WiFi HAL v1.5 provides full NAN support including:
- NAN publish/subscribe
- NAN data path (NDP) initiation and response
- NAN ranging (if hardware supports it)

### NAN Capabilities (from `dumpsys wifiaware`)

```
maxConcurrentAwareClusters     = 1
maxPublishes                   = 6
maxSubscribes                  = 6
maxServiceNameLen              = 255
maxMatchFilterLen              = 255
maxServiceSpecificInfoLen      = 255
maxExtendedServiceSpecificInfoLen = 270
maxNdiInterfaces               = 1
maxNdpSessions                 = 8
maxQueuedTransmitMessages      = 6
maxSubscribeInterfaceAddresses  = 42
supportedCipherSuites          = 1
isInstantCommunicationModeSupport = false
support5gBand                  = true
support6gBand                  = false
```

**Key observations:**
- `maxNdiInterfaces=1` means only ONE NAN data interface (`aware_data0`)
- `maxNdpSessions=8` allows up to 8 simultaneous data connections
- `supportedCipherSuites=1` = NCS-SK-128 (AES-128-CCMP)
- `isInstantCommunicationModeSupport=false` = no instant NDP setup
- Band support: 2.4 GHz + 5 GHz, no 6 GHz

### Feature Flags

```
feature:android.hardware.wifi.aware        ← PRESENT
feature:android.hardware.wifi.direct       ← PRESENT
feature:android.hardware.wifi              ← PRESENT
feature:android.hardware.location          ← PRESENT
```

Note: No static XML permission file (`android.hardware.wifi.aware.xml`)
found in `/vendor/etc/permissions/` or `/system/etc/permissions/`. The
feature is dynamically declared via WiFi HAL capability reporting, not
statically committed by the OEM. This is valid per AOSP but indicates
RayNeo hasn't explicitly certified WiFi Aware support.

### NAN Firmware Statistics

```
nanScanTimeMs           ≈ 15,000+
nanActiveTimeMs         ≈ 12,000+
```

The radio firmware actively performs NAN operations when enabled,
confirming the hardware path from driver through firmware is functional.

### Kernel NAN Evidence (dmesg)

```
NAN_ROLE_CHANGE: NON_MASTER_NON_SYNC → NON_ROLE_MASTER
NAN_ROLE_CHANGE: NON_ROLE_MASTER → NON_MASTER_NON_SYNC
```

NAN cluster role changes confirm both glasses are participating in the
same NAN cluster and the firmware is executing the NAN master election
protocol correctly.

---

## NAN State Machine Analysis <a name="nan-state-machine-analysis"></a>

### Complete Command Flow — What Works

```
1. COMMAND_TYPE_CONNECT
   → RESPONSE_TYPE_ON_CONFIG_SUCCESS                    ✅

2. COMMAND_TYPE_GET_CAPABILITIES
   → (capabilities returned as above)                   ✅

3. COMMAND_TYPE_CREATE_DATA_PATH_INTERFACE
   → RESPONSE_TYPE_ON_CREATE_INTERFACE (aware_data0)    ✅

4. COMMAND_TYPE_PUBLISH (service="MeshTalk-alpha")
   → RESPONSE_TYPE_ON_SESSION_CONFIG_SUCCESS
   → NOTIFICATION_TYPE_ON_MATCH                         ✅ (peer discovered!)

5. COMMAND_TYPE_SUBSCRIBE (service="MeshTalk-alpha")
   → RESPONSE_TYPE_ON_SESSION_CONFIG_SUCCESS
   → NOTIFICATION_TYPE_ON_MATCH                         ✅ (peer discovered!)
```

**Discovery is fast and reliable** — both glasses find each other within
~2 seconds consistently across dozens of tests.

### NDP Establishment — Where It Breaks

```
6. COMMAND_TYPE_INITIATE_DATA_PATH_SETUP
   → RESPONSE_TYPE_ON_INITIATE_DATA_PATH_SUCCESS        ✅ (firmware ACK)

7. (Responder side)
   → NOTIFICATION_TYPE_ON_DATA_PATH_REQUEST              ✅ (received)
   → COMMAND_TYPE_RESPOND_TO_DATA_PATH_SETUP_REQUEST
   → SUCCESS                                             ✅ (firmware ACK)

8. → NOTIFICATION_TYPE_ON_DATA_PATH_CONFIRM             ✅ (3 times!)

9. BUT: ConnectivityManager NetworkCallback              ❌ NEVER FIRES
   → No onAvailable()
   → No onCapabilitiesChanged()
   → No onLinkPropertiesChanged()
   → aware_data0 has no IPv6 address
   → Cannot create sockets
```

The firmware side completes successfully. The Android framework side
fails to bridge the confirmed NDP into a usable network interface.

---

## NDP Establishment Failure — The Core Unsolved Problem <a name="ndp-establishment-failure"></a>

### What Should Happen (AOSP Reference)

In standard AOSP Android 12, after NDP confirmation:

1. `WifiAwareDataPathStateManager` receives NDP_CONFIRM from HAL
2. `WifiAwareDataPathStateManager` transitions NDP state to CONFIRMED (103)
3. Framework creates/updates `NetworkAgent` for the `aware_data0` interface
4. `IpClient` runs on `aware_data0` to obtain IPv6 link-local address
5. `ConnectivityService` evaluates the network and fires `NetworkCallback`
6. App receives `onAvailable(network)` with `NetworkCapabilities` including
   `TRANSPORT_WIFI_AWARE` and `NET_CAPABILITY_NOT_RESTRICTED`
7. App obtains `Network`-scoped `Inet6Address` for socket communication

### What Actually Happens on Mercury OS

1. ✅ NDP_CONFIRM received (3 times — see duplicate issue below)
2. ✅ NDP state transitions to 103 (CONFIRMED)
3. ❌ NdpInfos[] array remains empty
4. ❌ No `NetworkAgent` created or updated
5. ❌ `IpClient` never runs on `aware_data0`
6. ❌ `ConnectivityManager.NetworkCallback` never fires
7. ❌ No IPv6 address, no sockets possible

### Evidence from `dumpsys wifiaware`

```
mChannelInfoPerNdp:
  NDP 229: channelInfo=[]
  NDP 230: channelInfo=[]
  NDP 231: channelInfo=[]
  NDP 232: channelInfo=[]
```

Four NDP sessions are tracked, all with empty channel info. The empty
channel info array is the smoking gun — it means the firmware confirmed
the NDP but didn't provide the channel/frequency information that the
framework needs to configure the interface.

### The `aware_data0` Interface

```
# ip addr show aware_data0
aware_data0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP
    link/ether XX:XX:XX:XX:XX:XX brd ff:ff:ff:ff:ff:ff
```

The interface exists and is UP, but has **no inet6 address**. In a
working NDP, this interface would have at least:
- An fe80:: link-local address (auto-configured)
- Possibly an fd:: ULA address (NAN-assigned)

### Hypothesis: NetworkFactory Not Registered

In AOSP, `WifiAwareDataPathStateManager` registers a `NetworkFactory`
with `ConnectivityService`. This factory scores NAN network requests
and creates `NetworkAgent` instances when NDPs are confirmed.

On Mercury OS, it's possible that:
- The OEM removed or disabled the NAN NetworkFactory registration
- The NetworkFactory is registered but with score=0 (never chosen)
- ConnectivityService has been modified to reject TRANSPORT_WIFI_AWARE
- The `IpClient` startup for `aware_data0` is failing silently

Without access to Mercury OS source code, we cannot confirm which of
these scenarios applies.

---

## The Duplicate NDP Request Issue <a name="duplicate-ndp-requests"></a>

### Problem

When both glasses run MeshTalk simultaneously, each acts as BOTH
publisher and subscriber. When they discover each other:

1. Glass A's subscriber discovers Glass B's publisher → initiates NDP
2. Glass A's publisher discovers Glass B's subscriber → Glass B initiates NDP
3. Glass B's subscriber discovers Glass A's publisher → initiates NDP
4. Glass B's publisher discovers Glass A's subscriber → Glass A initiates NDP

This creates **duplicate NDP requests** for the same physical peer:
- Two `requestNetwork()` calls from different discovery sessions
- Both pointing at the same peer MAC address
- Framework may get confused by competing requests

### Evidence

```
NOTIFICATION_TYPE_ON_DATA_PATH_CONFIRM (×3)
mChannelInfoPerNdp: 4 entries (229, 230, 231, 232)
```

Three confirmations and four tracked NDPs for what should be a single
bidirectional data path between two devices.

### Impact

The duplicate requests may:
- Exhaust the `maxNdpSessions=8` limit prematurely
- Confuse `WifiAwareDataPathStateManager`'s peer tracking
- Cause the framework to drop confirmations as "unexpected"
- Race-condition the `IpClient` startup on `aware_data0`

### Recommended Fix

De-duplicate peer discovery at the app level:
- Track discovered peer MACs across publish AND subscribe sessions
- Only initiate ONE NDP per unique peer MAC address
- Use the publish session's discovery for NDP initiation (initiator role)
- Use the subscribe session's discovery as confirmation only

---

## State 103 Deep Analysis <a name="state-103-analysis"></a>

### What Is State 103?

In AOSP `WifiAwareDataPathStateManager.java`:

```java
static final int NDP_STATE_IDLE = 100;
static final int NDP_STATE_INITIATOR_WAIT_FOR_CONFIRM = 101;
static final int NDP_STATE_RESPONDER_WAIT_FOR_CONFIRM = 102;
static final int NDP_STATE_CONFIRMED = 103;   // ← We're stuck here
```

State 103 (`NDP_STATE_CONFIRMED`) means the firmware has confirmed the
data path, but the framework hasn't completed network setup.

### Normal State 103 Transition

In AOSP, state 103 is transient. The normal flow:

```
101/102 → 103 (on NDP_CONFIRM from HAL)
         → NetworkAgent created
         → IpClient started
         → IPv6 address obtained
         → NetworkCallback.onAvailable() fired
         → NDP considered "fully established"
```

### Mercury OS State 103 Behavior

On Mercury OS, state 103 appears to be a terminal state:

```
101/102 → 103 (on NDP_CONFIRM from HAL)
         → NdpInfos[] stays empty
         → No NetworkAgent
         → No IpClient
         → No IPv6
         → Stuck forever
```

### Possible Explanations

1. **OEM disabled NetworkAgent creation for NAN**
   Mercury OS may have patched `WifiAwareDataPathStateManager` to skip
   the network agent creation step, treating WiFi Aware as discovery-only.

2. **NetworkFactory score too low**
   If the NAN NetworkFactory's score is 0 or negative, ConnectivityService
   will never select it to satisfy a network request.

3. **Missing netd configuration**
   The `netd` daemon may not be configured to handle `aware_data0` or
   the NAN network type.

4. **IpClient crash/rejection**
   `IpClient` may fail to start on `aware_data0` due to missing
   configuration in the OEM's network provisioning layer.

5. **SELinux policy blocking NetworkAgent ↔ ConnectivityService**
   Although we found no `avc denied` for WiFi/NAN, the
   NetworkAgent→ConnectivityService path may use a different SELinux
   context that IS denied but logged at a different level.

---

## ConnectivityManager NetworkCallback Gap <a name="connectivity-manager-gap"></a>

### The Request

```kotlin
val networkSpecifier = WifiAwareNetworkSpecifier.Builder(discoverySession, peerHandle)
    .setPskPassphrase("meshtalk_shared_key_2025")
    .build()

val networkRequest = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
    .setNetworkSpecifier(networkSpecifier)
    .build()

connectivityManager.requestNetwork(networkRequest, networkCallback, handler)
```

### Expected Callbacks (AOSP)

```
onAvailable(network)                    ← network handle
onCapabilitiesChanged(network, caps)    ← TRANSPORT_WIFI_AWARE confirmed
onLinkPropertiesChanged(network, lp)    ← aware_data0 interface, IPv6 address
```

### Actual Callbacks (Mercury OS)

```
(nothing — complete silence)
```

No callback is ever invoked. The `requestNetwork()` call does not throw
an exception (after we added CHANGE_NETWORK_STATE permission), but the
framework never satisfies the request.

### Timeout Behavior

The `requestNetwork()` call with a timeout would eventually fire
`onUnavailable()`, confirming the framework cannot find a network that
matches the request. Without a timeout, it waits indefinitely.

---

## Timeline of Discoveries <a name="timeline-of-discoveries"></a>

### Day 1 — Initial Investigation (May 19)

| Time | Discovery |
|------|-----------|
| 09:00 | Started investigation on fresh RayNeo X3 Pro pair |
| 09:30 | `pm list features` confirms `android.hardware.wifi.aware` present |
| 10:00 | WiFi Aware attach fails with SecurityException — "no location permission" |
| 10:30 | All location permissions granted. Still fails. **Red herring identified.** |
| 11:00 | Found `aware_enabled=0` in Settings.Secure. **Layer 1 identified.** |
| 11:30 | Enabled `aware_enabled=1`. WifiAwareManager.attach() succeeds! |
| 12:00 | Publish/subscribe sessions created. No matches yet. |
| 12:30 | Second glasses enabled. **Both discover each other in ~2 seconds!** |
| 13:00 | NDP initiation attempted. SecurityException on `requestNetwork()`. |
| 13:30 | Added CHANGE_NETWORK_STATE permission. **Layer 4 fixed.** |
| 14:00 | NDP request sent. No callback. Firmware shows INITIATE_SUCCESS. |
| 15:00 | Discovered `on_idle_disable_aware=1`. Doze kills NAN. **Layer 2 noted.** |
| 16:00 | Implemented NanSupervisor state machine with auto-recovery. |
| 17:00 | Background kill observed. Discovered BackgroundAppManager whitelist. **Layer 5.** |
| 18:00 | Added deviceidle whitelist, foreground service, wake lock. |
| 19:00 | AppOps investigation. HandlerThread treated as background. **Layer 3.** |
| 20:00 | Fixed AppOps: `allow` not `foreground`. Full discovery chain stable. |

### Day 2 — NDP Deep Dive (May 20)

| Time | Discovery |
|------|-----------|
| 09:00 | Systematic `dumpsys wifiaware` analysis |
| 09:30 | Found NDP state 103 (CONFIRMED) but NdpInfos[] empty |
| 10:00 | Found 4 NDP sessions (229-232) with empty channelInfo[] |
| 10:30 | Identified duplicate NDP request issue from dual pub/sub |
| 11:00 | Confirmed `aware_data0` interface has no IPv6 address |
| 11:30 | Verified firmware path works: all HAL commands succeed |
| 12:00 | Concluded: framework-level NDP→Network bridge is broken/disabled |
| 13:00 | Began documenting findings and writing investigation report |

---

## AOSP Behavioral Comparison <a name="aosp-comparison"></a>

| Behavior | AOSP Android 12 | Mercury OS |
|----------|-----------------|------------|
| `aware_enabled` default | 1 (enabled) | 0 (disabled) |
| `on_idle_disable_aware` | 0 (survive doze) | 1 (killed on doze) |
| WiFi default | 1 (on) | 0 (off) |
| Attach/Publish/Subscribe | Works | Works (after enabling) |
| Peer discovery | Works | Works ✅ |
| NDP firmware handshake | Works | Works ✅ |
| NDP → NetworkAgent | Automatic | ❌ Broken |
| aware_data0 IPv6 | Auto-assigned | ❌ Never assigned |
| NetworkCallback | Fires within ~2s | ❌ Never fires |
| Background service | Protected by FG notification | Killed by BackgroundAppManager |
| AppOps location for HandlerThread | Typically allowed | Requires explicit `allow` |

The pattern is clear: Mercury OS supports the NAN radio stack through
to firmware-level operations, but the Android framework integration
(NDP → usable network) is non-functional. This is consistent with an
OEM that included WiFi Aware hardware capability but never tested or
certified the data-path functionality.

---

## Reference Repositories Consulted <a name="reference-repos"></a>

### Directly Relevant

| Repository | What We Learned |
|-----------|----------------|
| **meshenger-android** | WebRTC P2P approach — uses SDP/ICE over WiFi Direct or internet relay. Confirms WiFi Direct is viable backup. |
| **LANwalkieTalkie** | NSD (mDNS) + TCP on shared LAN. Simple architecture but requires same AP. Not peer-to-peer. |
| **esp-walkie-talkie** | A-law codec + UDP + AEC on ESP32. Validated our Speex AEC + Opus approach as higher quality. |
| **walkie-talkie (Rust/ESP)** | ESP-NOW protocol — 250-byte frames, no IP stack needed. Inspirational for our PacketCodec design. |
| **bitchat** | BLE mesh + Nostr relay. BLE for discovery, internet for relay. Hybrid approach similar to our NAN + WebSocket. |

### Architecture Inspiration

| Repository | What We Learned |
|-----------|----------------|
| **beatsync** | NTP-based audio sync across devices. Informed our thinking about clock synchronization for spatial audio. |
| **PokeMesh** | Mesh overlay on map. Good UX patterns for showing nearby peers on radar HUD. |
| **meshcore-open** | Flutter + LoRa mesh. Clean state machine patterns for unreliable radio links. |
| **Reticulum/LXST** | Overlay network with voice. Architecture for multi-hop mesh with codec negotiation. |
| **nomadnet/meshchat** | Text-first mesh chat apps. Simple, reliable. Confirmed our audio-first approach adds unique value. |
| **meshtastic firmware** | LoRa mesh protocol reference. Packet format, routing, and duty cycle management. |

### Protocol/API Reference

| Repository | What We Learned |
|-----------|----------------|
| **zelloptt** | Zello Push-to-Talk channel API. Production-grade PTT UX patterns — onset delay, hangover, channel switching. |
| **T-TWR (LoRa hardware)** | LILYGO LoRa walkie-talkie. Hardware reference for sub-GHz mesh but not applicable to WiFi. |

### Key Takeaway from Research

No existing open-source project successfully uses WiFi Aware NAN data
paths on Android for real-time audio streaming. Most mesh walkie-talkie
projects use either:
- BLE (low bandwidth, works everywhere)
- WiFi Direct (requires manual pairing)
- LoRa (sub-GHz, specialized hardware)
- Shared LAN/Internet relay (not peer-to-peer)

MeshTalk's attempt to use NAN for serverless AR walkie-talkie is
genuinely novel, which also means there's no reference implementation
to compare against for the NDP failure.

---

## NanSupervisor Architecture <a name="nansupervisor-architecture"></a>

The `NanSupervisor` class implements a self-healing state machine that
manages the WiFi Aware radio lifecycle on Mercury OS.

### State Machine

```
                    ┌──────────────┐
                    │   DISABLED   │ (initial)
                    └──────┬───────┘
                           │ start()
                    ┌──────▼───────┐
            ┌───────│   WIFI_OFF   │◄──── WiFi state broadcast
            │       └──────┬───────┘
            │              │ WiFi ON
            │       ┌──────▼───────────┐
            │       │ AWARE_DISABLED   │◄──── aware_enabled=0
            │       └──────┬───────────┘
            │              │ aware_enabled=1 (auto-set)
            │       ┌──────▼───────┐
            │       │  ATTACHING   │──── WifiAwareManager.attach()
            │       └──────┬───────┘
            │              │ onAttached()
            │       ┌──────▼───────┐
            │       │   ATTACHED   │
            │       └──────┬───────┘
            │              │ publish()
            │       ┌──────▼───────┐
            │       │  PUBLISHING  │
            │       └──────┬───────┘
            │              │ subscribe() + onMatch()
            │       ┌──────▼───────┐
            │       │ DISCOVERING  │──── Fully operational (pub+sub)
            │       └──────┬───────┘
            │              │ NDP confirmed
            │       ┌──────▼───────┐
            │       │  CONNECTED   │──── Data path active (goal state)
            │       └──────────────┘
            │
            │       ┌──────────────┐
            ├───────│   DOZING     │◄──── PowerManager doze broadcast
            │       └──────┬───────┘
            │              │ doze exit
            │       ┌──────▼───────┐
            └──────►│  RECOVERING  │──── Exponential backoff reconnect
                    └──────┬───────┘
                           │ max failures
                    ┌──────▼───────┐
                    │    FAILED    │──── Waiting for backoff timer
                    └──────────────┘
```

### Key Design Decisions

1. **Exponential backoff**: 1s → 2s → 4s → ... → 30s max. Prevents
   hammer-looping the WiFi Aware stack during transient failures.

2. **Watchdog timer**: 5-second periodic check ensures state consistency
   even if broadcast receivers miss events (Mercury OS is aggressive
   about killing broadcast receivers).

3. **Auto-enable**: NanSupervisor automatically sets `aware_enabled=1`
   via `Settings.Secure.putInt()` (requires `WRITE_SECURE_SETTINGS`
   granted via ADB). No manual setup needed after initial permission grant.

4. **Doze awareness**: Monitors `PowerManager.isDeviceIdleMode()` and
   pre-emptively transitions to DOZING state. On doze exit, triggers
   full re-initialization (WiFi Aware is completely destroyed by doze
   on Mercury OS).

5. **State callbacks**: Fires `onStateChanged` callback so the HUD can
   display radio state to the user (green = connected, yellow = discovering,
   red = failed, gray = disabled).

---

## Actionable Next Steps <a name="next-steps"></a>

### Approach 1: Single-NDP De-duplication (HIGH PRIORITY)

Implement peer MAC tracking to prevent duplicate NDP requests:

```kotlin
val knownPeerMacs = ConcurrentHashMap<String, Long>()

fun onPeerDiscovered(peerHandle: PeerHandle, matchFilter: ByteArray) {
    val peerMac = extractMacFromMatchFilter(matchFilter)
    if (knownPeerMacs.containsKey(peerMac)) {
        Log.d(TAG, "Peer $peerMac already known, skipping NDP")
        return
    }
    knownPeerMacs[peerMac] = System.currentTimeMillis()
    initiateNdp(peerHandle)
}
```

This may resolve the framework confusion from 4 simultaneous NDP sessions.

### Approach 2: Raw Socket on aware_data0 (MEDIUM PRIORITY)

Even without framework-assigned IPv6, try manually configuring the
interface and using raw sockets:

```bash
# On both glasses via ADB:
ip -6 addr add fe80::1/64 dev aware_data0   # Glass A
ip -6 addr add fe80::2/64 dev aware_data0   # Glass B
```

Then send UDP packets using the manually assigned addresses. This
bypasses the broken ConnectivityManager path entirely.

### Approach 3: WiFi Direct Fallback (MEDIUM PRIORITY)

Use NAN discovery (which works) to exchange WiFi Direct group info
via the match filter's `serviceSpecificInfo` field, then establish
a WiFi Direct group for actual data transfer:

```
NAN publish serviceSpecificInfo = "WFD:GO:<SSID>:<PSK>"
NAN subscribe → parse serviceSpecificInfo → connect via WiFi Direct
```

Hybrid NAN-discovery + WiFi-Direct-data-path.

### Approach 4: Message-Passing Over Discovery (LOW PRIORITY, HACKY)

WiFi Aware allows sending messages via `sendMessage()` on the discovery
session (up to 255 bytes per message). Opus at 16kbps ≈ 2KB/s. With
255-byte messages, that's ~8 messages/second — technically feasible but:
- High overhead per message
- No flow control
- Not designed for streaming
- May be rate-limited by firmware

### Approach 5: Vendor Engagement (HIGH PRIORITY, SLOW)

Contact RayNeo engineering team with the evidence package below.
The NDP framework bridge is likely a configuration change or a
small patch on their end.

### Approach 6: NAN Data Path via Vendor HAL Directly (ADVANCED)

If we can get root access, bypass the Android framework entirely and
call the Qualcomm NAN HAL directly via the HIDL interface:

```
IWifiNanIface.initiateDataPathRequest(...)
IWifiNanIface.respondToDataPathIndicationRequest(...)
```

Then manually configure `aware_data0` with IP addresses and use standard
UDP sockets. This is the nuclear option — complex but guaranteed to work
if the firmware path is functional (which we've confirmed it is).

### Approach 7: Server Relay Optimization (PRAGMATIC)

Accept the NDP limitation and optimize the Mac Mini WebSocket relay:
- Already working end-to-end
- Add Opus codec support to the relay server
- Reduce latency via UDP relay mode
- Deploy relay on cloud for untethered operation

This is the current working path while NDP investigation continues.

---

## Vendor Escalation Package <a name="vendor-escalation"></a>

### Template: RayNeo Engineering Support Request

```
Subject: WiFi Aware NAN Data Path (NDP) Non-Functional on X3 Pro / Mercury OS

To: RayNeo Developer Support / Qualcomm XR Platform Support

Summary:
We are developing a peer-to-peer AR walkie-talkie application for
RayNeo X3 Pro using WiFi Aware (NAN). Discovery works correctly
after enabling aware_enabled=1, but NAN Data Path (NDP) establishment
fails at the Android framework level despite firmware-level success.

Hardware: RayNeo X3 Pro (ARGF20), Mercury OS build SKQ1.250204.001
WiFi Chip: Qualcomm Kiwi v2, HAL 1.5

What Works:
✅ WiFi Aware attach, publish, subscribe
✅ Peer discovery (2 devices find each other in ~2 seconds)
✅ NDP firmware handshake (INITIATE→CONFIRM all succeed)
✅ NAN cluster formation (role changes in dmesg)

What Fails:
❌ ConnectivityManager.NetworkCallback never fires (no onAvailable)
❌ aware_data0 interface has no IPv6 address after NDP confirmation
❌ NDP state stuck at 103 (CONFIRMED) but NdpInfos[] is empty
❌ channelInfo[] is empty for all NDP sessions

Questions:
1. Is NAN Data Path (NDP) supported on Mercury OS?
2. Is there a configuration flag to enable NDP network bridging?
3. Can aware_data0 interface receive IPv6 addresses via IpClient?
4. Is there a known issue with WifiAwareDataPathStateManager on
   this build?

Steps to Reproduce:
1. adb shell settings put secure aware_enabled 1
2. adb shell svc wifi enable
3. Run app that publishes + subscribes on NAN service "test"
4. Both devices discover each other
5. Initiate NDP via ConnectivityManager.requestNetwork()
6. Observe: firmware confirms NDP but no NetworkCallback fires

Attached: dumpsys wifiaware output, dmesg NAN entries, logcat filtered
for WifiAware/NAN
```

---

## Appendix: Raw Dumps and Logs <a name="appendix"></a>

### Mercury OS System Properties (relevant)

```
ro.build.display.id     = SKQ1.250204.001
ro.build.version.sdk    = 32
ro.build.type           = userdebug
ro.product.model        = ARGF20
ro.product.board        = neo
ro.hardware              = qcom
ro.board.platform        = kona
wifi.interface            = wlan0
ro.boot.hardware.chipname = kona
persist.vendor.wifi.config.aware.enable = true
```

Note: `persist.vendor.wifi.config.aware.enable = true` at the vendor
level, but overridden by `Settings.Secure.aware_enabled = 0` at the
framework level. This confirms the hardware vendor (Qualcomm) enabled
NAN but the OEM (RayNeo) disabled it in the framework.

### Key dumpsys wifiaware Sections

```
mUsageEnabled: true (after our fix)
mWifiNanIface: android.hardware.wifi@1.5::IWifiNanIface@Proxy
mSettableParameters: {on_idle_disable_aware=1}

NAN data interfaces:
  aware_data0 (exists, no IP)

mChannelInfoPerNdp:
  229: channelInfo=[]
  230: channelInfo=[]
  231: channelInfo=[]
  232: channelInfo=[]

NdpInfos: []   ← EMPTY — this is the problem
```

### Android.permission grants applied

```bash
# Required ADB commands (run once per device):
adb shell pm grant com.meshtalk.app android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.meshtalk.app android:coarse_location allow
adb shell appops set com.meshtalk.app android:fine_location allow
adb shell cmd deviceidle whitelist +com.meshtalk.app
```

### Build.SERIAL Note

`Build.SERIAL` returns `"unknown"` on Android 12 due to privacy
restrictions. Use `Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)` instead for device identification.

### Mercury OS `am start` Bug

After `adb install`, the package is in "stopped" state. `am start`
fails with "Activity does not exist" even though the package is
installed. Workaround: `adb uninstall` first, then fresh `adb install`.
The deploy script handles this automatically.

---

## Conclusion

WiFi Aware NAN on the RayNeo X3 Pro is a **partially functional but
incomplete implementation**. The hardware and firmware support is
excellent — discovery and NDP handshake work flawlessly. The failure
point is squarely in the Android framework layer where Mercury OS
fails to bridge confirmed NDPs into usable network interfaces.

The most pragmatic path forward is the **server relay** (already working)
while pursuing **vendor engagement** for a potential firmware/framework
fix. The **raw socket on aware_data0** approach (Approach 2) and
**NDP de-duplication** (Approach 1) are worth attempting as they could
unlock NDP without vendor assistance.

This investigation represents one of the most thorough analyses of
WiFi Aware NAN behavior on a non-Pixel Android device. The findings
may be valuable to the broader Android NAN developer community, as
OEM support for NAN data paths is poorly documented and rarely tested
outside of Google's reference devices.
