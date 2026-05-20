# MeshTalk v2 — BLE-to-Phone Mesh Architecture

## Pivot Rationale
WiFi Aware NAN Data Path (NDP) never establishes on Mercury OS despite firmware-level 
confirmation. After solving 6 permission layers and achieving discovery+publish+subscribe,
the NDP interface assignment remains broken (state 103, NdpInfos[] empty, no IPv6).
See docs/NAN_DEEP_INVESTIGATION.md for the full 942-line postmortem.

## New Architecture: BLE Tethered to Phone

```
┌─────────────┐     BLE GATT      ┌─────────────┐
│  Glasses A   │◄─────────────────►│   Phone A    │
│  (Mercury OS)│  Audio + Control  │  (Android)   │
│              │  MTU: 512 bytes   │              │
│  Mic→AEC→   │                   │  BLE Server  │
│  VAD→Opus→  │──audio chunks──►  │  Mesh Client │
│  BLE send   │                   │  (Reticulum) │
│              │  ◄──audio──────  │              │
│  BLE recv→  │                   │  Channel Mgr │
│  Opus→Play  │                   │  Peer Discov │
└─────────────┘                   └──────┬───────┘
                                         │
                                   Reticulum Mesh
                                   (WiFi/BLE/LoRa/
                                    Internet/any)
                                         │
┌─────────────┐     BLE GATT      ┌──────┴───────┐
│  Glasses B   │◄─────────────────►│   Phone B    │
│  (Mercury OS)│  Audio + Control  │  (Android)   │
│              │                   │              │
│  Same as A   │                   │  Same as A   │
└─────────────┘                   └──────────────┘
```

## Why This Is Better

| Aspect | WiFi Aware NAN | BLE to Phone |
|--------|---------------|--------------|
| Mercury OS support | Broken NDP | BLE GATT works perfectly |
| Range (glasses↔hub) | N/A (broken) | ~30ft BLE, always paired |
| Range (hub↔hub) | ~200ft direct | Unlimited (mesh, internet) |
| Mesh networking | None on glasses | Full Reticulum on phone |
| Battery impact | High (NAN radio) | Low (BLE) |
| Setup complexity | 6 permission layers | Standard BLE pairing |
| Multi-hop | Not possible | Reticulum handles it |
| Future LoRa | N/A | Phone + RNode USB-C |

## BLE GATT Protocol (Glasses ↔ Phone)

### Service UUID: `6ba1b218-15a8-461f-9fa8-5dcae273ea00`

### Characteristics:

| UUID | Name | Direction | Properties | Description |
|------|------|-----------|------------|-------------|
| `...ea01` | Audio TX | Glasses→Phone | Write | Opus-encoded audio chunks from glasses mic |
| `...ea02` | Audio RX | Phone→Glasses | Notify | Opus-encoded audio from other users via mesh |
| `...ea03` | Control | Bidirectional | Write+Notify | JSON control messages (channel, mute, status) |
| `...ea04` | Status | Phone→Glasses | Read+Notify | Connection status, peer count, mesh state |

### Audio Frame Format (over BLE):
```
Byte 0:    Sequence number (uint8, wraps at 255)
Byte 1:    Flags (0x01=speech, 0x00=silence/end)
Bytes 2+:  Opus payload (typically 40-80 bytes per 20ms frame)
```

### MTU Negotiation:
- Request MTU 512 on connection (Android 12 BLE 5.0 supports this)
- Opus 20ms frame at 16kbps = ~40 bytes + 2 byte header = 42 bytes
- Well within even default 23-byte MTU with fragmentation
- At 512 MTU: can send ~10 frames per BLE packet for batching

### Control Messages (JSON on ea03):
```json
{"cmd":"channel","ch":0}        // Switch to channel 0 (Alpha)
{"cmd":"mute","muted":true}     // Toggle mute
{"cmd":"status"}                // Request status update
{"cmd":"vox","speaking":true}   // VOX state change
```

### Status (read/notify on ea04):
```json
{"connected":true,"channel":"Alpha","peers":2,"mesh":"connected","rssi":-45}
```

## Companion Phone App

### Stack:
- Kotlin/Android (standard Android, NOT Mercury OS)
- BLE GATT Server (accept glasses connections)
- Reticulum (Python via Chaquopy, or Kotlin RNS port)
- Foreground service for mesh + BLE
- Simple UI: channel selector, peer list, mesh status, glasses connection

### Architecture:
```
Phone App
├── BleGlassesServer.kt      — GATT server, accepts glasses connection
├── MeshTransport.kt          — Reticulum interface for mesh comms
├── AudioRelay.kt             — Routes audio: BLE↔Mesh
├── ChannelManager.kt         — Channel state, peer tracking
├── MeshTalkService.kt        — Foreground service orchestrating everything
├── MainActivity.kt           — UI: channels, peers, status, settings
└── libs/
    └── reticulum/            — Reticulum Python (via Chaquopy) or native
```

## Glasses App Changes (Simplified)

The glasses app becomes much simpler — strip out:
- WiFi Aware transport (WifiAwareTransport.kt)
- NanSupervisor
- PeerManager (phone handles this)
- ChannelManager (phone handles this)
- AudioStreamClient (replaced by BLE)

Add:
- BlePhoneClient.kt — GATT client connecting to phone
- Simpler service — just audio pipeline + BLE

## Phase 2: Reticulum Mesh on Phone

The phone companion app runs Reticulum with:
- AutoInterface (WiFi LAN discovery)
- TCPClientInterface (internet relay)
- BLE transport (phone-to-phone BLE mesh)
- Future: RNode LoRa via USB-C

Audio over Reticulum uses LXST protocol (voice-optimized):
- Opus at 16kHz for WiFi links
- Codec2 at 3200bps for LoRa links
- Dynamic codec switching based on available bandwidth
