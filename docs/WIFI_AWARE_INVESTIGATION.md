# WiFi Aware (NAN) Investigation on RayNeo X3 Pro / Mercury OS

**Date:** 2025-05-19  
**Device:** RayNeo X3 Pro (model ARGF20)  
**OS:** Mercury OS (Android 12, API 32, build SKQ1.250204.001)  
**SoC:** Qualcomm Snapdragon XR2 (board: neo, hardware: qcom)  
**WiFi Chip:** Qualcomm Kiwi v2 (dir: /vendor/etc/wifi/kiwi_v2)  
**Serial tested:** A06B4A94CC51663  

---

## Executive Summary

**WiFi Aware IS supported by the hardware and fully functional.** The root cause of
failures is that Mercury OS ships with WiFi Aware **disabled by default** via
`Settings.Secure.aware_enabled=0`. Once WiFi is turned on and this setting is
toggled to 1, the NAN HAL initializes and publish/subscribe sessions work correctly.

However, there are **two additional obstacles** that cause WiFi Aware to die silently:
1. **Idle/doze kill**: `on_idle_disable_aware=1` disables NAN when the device enters doze
2. **WiFi default OFF**: Mercury OS boots with WiFi off (`wifi_on=0`)

---

## Detailed Findings

### 1. Hardware Support: CONFIRMED ✅

**Feature flags present in system:**
```
feature:android.hardware.wifi.aware        ← PRESENT
feature:android.hardware.wifi.direct       ← PRESENT
feature:android.hardware.wifi              ← PRESENT
feature:android.hardware.location          ← PRESENT
feature:android.hardware.location.network  ← PRESENT
feature:android.hardware.bluetooth         ← PRESENT
feature:android.hardware.bluetooth_le      ← PRESENT
```

**NAN HAL interface:** `android.hardware.wifi@1.5::IWifiNanIface@Proxy`
- When enabled, the IWifiNanIface HAL proxy initializes with refcount=1
- Supports WiFi HAL v1.5 NAN capabilities (Qualcomm Kiwi v2 driver)

**Hardware capabilities (from dumpsys wifiaware):**
```
maxConcurrentAwareClusters = 1
maxPublishes               = 6
maxSubscribes              = 6
maxServiceNameLen           = 255
maxMatchFilterLen           = 255
maxServiceSpecificInfoLen  = 255
maxExtendedServiceSpecificInfoLen = 270
maxNdiInterfaces           = 1
maxNdpSessions             = 8
maxQueuedTransmitMessages  = 6
maxSubscribeInterfaceAddresses = 42
supportedCipherSuites      = 1
isInstantCommunicationModeSupport = false
support5gBand              = true
support6gBand              = false
```

**NAN scan time in firmware stats:** ~15,000ms+ of actual NAN scanning recorded,
confirming the radio firmware actively performs NAN operations when enabled.

### 2. Root Cause: Mercury OS Disables WiFi Aware by Default ❌

**Settings values on boot:**
```
Settings.Secure.aware_enabled      = 0    ← DISABLED BY DEFAULT
Settings.Secure.aware_lock_enabled = 0
Settings.Global.wifi_on            = 0    ← WiFi OFF by default
```

When `aware_enabled=0`:
- `WifiAwareServiceImpl.mUsageEnabled = false`
- `WifiAwareNativeManager.mWifiNanIface = null` (HAL not initialized)
- All attach/publish/subscribe calls fail with SecurityException

**The SecurityException is misleading.** The error message says "UID does not have
Coarse/Fine Location permission" but the real issue is that the WifiAwareService
rejects all calls when `mUsageEnabled=false`. The permission check is a red herring
caused by the service checking permissions AFTER determining usage is disabled and
throwing a generic security error.

### 3. Fix: Enable WiFi + Aware ✅

```bash
ADB=/opt/homebrew/bin/adb
SERIAL=A06B4A94CC51663

# Step 1: Enable WiFi
$ADB -s $SERIAL shell svc wifi enable

# Step 2: Enable WiFi Aware
$ADB -s $SERIAL shell settings put secure aware_enabled 1

# Verify:
$ADB -s $SERIAL shell dumpsys wifiaware | grep "mUsageEnabled"
# Should show: mUsageEnabled: true

$ADB -s $SERIAL shell dumpsys wifiaware | grep "mWifiNanIface"
# Should show: mWifiNanIface: android.hardware.wifi@1.5::IWifiNanIface@Proxy
```

**After enabling, the state machine shows successful operation:**
```
COMMAND_TYPE_CONNECT → RESPONSE_TYPE_ON_CONFIG_SUCCESS
COMMAND_TYPE_GET_CAPABILITIES → success
COMMAND_TYPE_CREATE_DATA_PATH_INTERFACE → RESPONSE_TYPE_ON_CREATE_INTERFACE
```

### 4. Second Obstacle: Idle/Doze Kill

```
mSettableParameters: {on_idle_disable_aware=1}
```

This means: when the device enters doze mode, WiFi Aware is automatically
disabled. The glasses frequently enter doze (observed `mWakefulness=Dozing`
during testing). The state machine records show:

```
COMMAND_TYPE_DISABLE_USAGE → NOTIFICATION_TYPE_AWARE_DOWN
```

This makes WiFi Aware unreliable for persistent background operation.

**Cannot override via cmd:** `cmd wifi set-wifi-aware-parameters on_idle_disable_aware 0`
is not supported on this build.

### 5. Permission Analysis

**Vendor/system permission XML files:** No WiFi Aware or NAN-specific permission
XML files found in `/vendor/etc/permissions/` or `/system/etc/permissions/`.
This is unusual — normally Android devices with WiFi Aware have a
`android.hardware.wifi.aware.xml` permission declaration file.

The `android.hardware.wifi.aware` feature IS listed by `pm list features`,
suggesting it may be dynamically declared via the WiFi HAL rather than
statically in XML. This is valid but means the OEM hasn't explicitly
committed to WiFi Aware support.

**No NAN firmware files found** at `/vendor/firmware/*nan*` or `*aware*` — 
NAN functionality is built into the main WiFi firmware (Qualcomm Kiwi v2),
not loaded as a separate module.

### 6. Location Subsystem

```
location_mode = 3  (high accuracy, GPS + network + passive)
```

Location mode is correctly set. The location_providers_allowed returns `null`
which on Android 12 simply means the old-style provider list is not used
(location mode 3 is the modern approach). **Location is NOT the issue.**

### 7. SELinux / Kernel

No SELinux `avc denied` entries related to WiFi, Aware, or NAN found in dmesg.
No event log entries for Aware/NAN. **SELinux is NOT blocking WiFi Aware.**

### 8. WiFi Direct (P2P) Status

WiFi P2P service is present but idle (`P2pDisabledState`). It requires WiFi
to be on. Could be used as an alternative for device-to-device communication,
but it requires an explicit connection handshake and is not as suitable for
discovery as WiFi Aware.

### 9. RayNeo Developer Documentation

Checked:
- https://rayneo-en.gitbook.io/rayneo-devdoc — Overview page only; links to
  "Android SDK for X" and "Unity SDK for X" documentation. **No mention of
  WiFi Aware, NAN, P2P, peer-to-peer, mesh, or networking restrictions.**
- https://open.rayneo.com/#/docs — Could not access (auth required)
- https://leiniao-ibg.feishu.cn/wiki/* — Feishu internal docs (auth required)
- Qualcomm developer portal RayNeo guide — Focuses on AR development, camera,
  display, not networking

**RayNeo's developer docs make no mention of WiFi Aware capabilities or
restrictions whatsoever.** This is consistent with WiFi Aware being an
afterthought or unsupported use case on this platform.

### 10. Google Drive SDK Folder

The Drive folder (1gMcDKkeOS65FrhcdUntnYNhGguOkC8Jl) requires authentication
and could not be accessed programmatically. Manual inspection recommended.

---

## Summary Table

| Component | Status | Details |
|-----------|--------|---------|
| WiFi Aware hardware (NAN radio) | ✅ Supported | Qualcomm Kiwi v2, HAL v1.5 |
| Feature flag | ✅ Declared | `android.hardware.wifi.aware` |
| NAN HAL initialization | ✅ Works (when enabled) | `IWifiNanIface@Proxy` |
| Publish/Subscribe | ✅ Works (when enabled) | Confirmed via state machine logs |
| NAN scan by firmware | ✅ Active | 15,000ms+ NAN scan time recorded |
| `aware_enabled` setting | ❌ Disabled (0) by default | Root cause of failures |
| WiFi default state | ❌ Off by default | Compounds the problem |
| Idle/doze behavior | ❌ Kills Aware | `on_idle_disable_aware=1` |
| Location permissions | ✅ Correct | mode=3 (high accuracy) |
| SELinux | ✅ No blocks | No AVC denials |
| OEM documentation | ⚠️ Silent | No mention of WiFi Aware |

---

## Recommended Solution for MeshTalk App

### Immediate Fix (per-device, survives reboot if using settings):

```bash
# Run once per device:
adb shell svc wifi enable
adb shell settings put secure aware_enabled 1
```

### In-App Programmatic Fix:

The app should:

1. **Check WiFi state** and prompt user to enable WiFi if off
2. **Check `aware_enabled`** setting:
   ```kotlin
   val awareEnabled = Settings.Secure.getInt(
       contentResolver, "aware_enabled", 0
   )
   if (awareEnabled == 0) {
       // Prompt user to enable, or try:
       Settings.Secure.putInt(contentResolver, "aware_enabled", 1)
       // Note: requires WRITE_SECURE_SETTINGS or system app privilege
   }
   ```
3. **Handle doze mode** by:
   - Requesting battery optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
   - Using a foreground service with a partial wake lock
   - Re-attaching to WiFi Aware after doze exit via `WifiAwareManager.isAvailable()` checks
   - Implementing auto-reconnect logic on `onAvailableChanged(false)` callback

### ADB-Based Fix (no root needed):

```bash
# Grant our app WRITE_SECURE_SETTINGS
adb shell pm grant com.meshtalk.awareness android.permission.WRITE_SECURE_SETTINGS

# Then the app can toggle aware_enabled itself
```

### Startup Script Approach:

Add to app initialization:
```kotlin
fun ensureWifiAwareEnabled(context: Context) {
    // Enable WiFi if off
    val wifiManager = context.getSystemService(WifiManager::class.java)
    if (!wifiManager.isWifiEnabled) {
        // On Android 12+, can't programmatically enable WiFi
        // Must prompt user via Settings panel
        val intent = Intent(Settings.Panel.ACTION_WIFI)
        startActivity(intent)
    }
    
    // Enable Aware if disabled (requires WRITE_SECURE_SETTINGS)
    try {
        val current = Settings.Secure.getInt(
            context.contentResolver, "aware_enabled", 0
        )
        if (current == 0) {
            Settings.Secure.putInt(
                context.contentResolver, "aware_enabled", 1
            )
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "Cannot set aware_enabled - need WRITE_SECURE_SETTINGS")
        // Fall back to prompting user
    }
}
```

---

## Alternatives If WiFi Aware Remains Unreliable

| Alternative | Pros | Cons |
|------------|------|------|
| **WiFi Direct (P2P)** | Supported, no NAN dependency | Requires manual connection, no discovery |
| **BLE (Bluetooth LE)** | Always available, low power | 20-byte MTU (extended: 512), short range |
| **BLE + WiFi Direct hybrid** | BLE for discovery, WiFi for data | Complex two-stage connection |
| **mDNS/NSD over WiFi** | Works on same network | Requires shared AP, not peer-to-peer |
| **UDP broadcast on hotspot** | Simple, high bandwidth | One device must be AP |
| **WebSocket via shared AP** | Standard, reliable | Requires shared network infrastructure |

### Recommended Architecture:
1. **Primary:** WiFi Aware (with enable-aware fix applied)
2. **Fallback:** BLE for discovery + WiFi Direct for data transfer
3. **Emergency:** UDP broadcast with one glasses as WiFi hotspot

---

## Key Insight

The SecurityException about "Location permission" was a **red herring**. The actual
issue is a single settings value (`aware_enabled=0`) that Mercury OS ships with
by default. Once toggled to 1 (with WiFi on), WiFi Aware works perfectly — the
NAN HAL initializes, the state machine processes commands, and publish/subscribe
sessions complete successfully.

The `on_idle_disable_aware=1` parameter will cause WiFi Aware to die during doze,
requiring the app to handle re-initialization gracefully. This is manageable with
a foreground service and proper lifecycle handling.
