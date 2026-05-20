# MeshTalk & Friend Tracker — Session Log (May 20, 2026)

## Overview

This session focused on two parallel workstreams:
1. **MeshTalk AR Walkie-Talkie App** — fixing the double-tap exit bug on RayNeo X3 Pro glasses
2. **Emotional Friend Tracker** — stabilizing the backend, fixing location flush cycles, and preparing for tonight's commute test

---

## Hardware & Infrastructure

### Devices Connected (all via USB/ADB to Mac Mini)
- **G1** (serial: `A06B4A8FF4A1633`) — RayNeo X3 Pro glasses
- **G2** (serial: `A06B4A94CC51663`) — RayNeo X3 Pro glasses
- **Phone** (serial: `R5CT4000J6P`) — Samsung

### Machines
- **Mac Mini** (`vincent@100.116.27.60`, Tailscale: `claws-mac-mini.tail401c6.ts.net`) — sole server, builds Android APKs
- **MacBook Pro** (`vinceroy@100.81.56.107`) — no Java installed, cannot build. Used only for ADB forwarding if needed.

### Build Toolchain (Mac Mini)
- Java 21: `/opt/homebrew/opt/openjdk@21`
- Android SDK: `/opt/homebrew/share/android-commandlinetools` (platforms 34/35, build-tools 34.0.0)
- Gradle: 8.11.1, AGP: 8.7.3, Kotlin: 2.0.21
- Mercury SDK: `~/meshtalk/app/libs/MercuryAndroidSDK-v0.2.5-release.aar`

### Running Services (via launchd)
| Service | LaunchAgent | Port/Details |
|---------|-------------|--------------|
| Friend Tracker API | `com.friendtracker.api` | `localhost:8420` |
| FindMy Flush Loop | `com.friendtracker.flush` | 10-min cycle |
| Fast Refresh Sidecar | `com.friendtracker.fast-refresh` | 30s polling |
| Cloudflare Tunnel | `com.friendtracker.tunnel` | Ephemeral URL |

### Current Public URL
`https://snap-expect-stakeholders-post.trycloudflare.com` (EPHEMERAL — changes on tunnel restart)

---

## What Was Done This Session

### 1. Diagnosed the Double-Tap Exit Bug

**Problem:** MeshTalk app on RayNeo X3 Pro glasses could not be exited via double-tap on the temple touchpad. Users had to force-kill the app via ADB, which is unacceptable for daily use.

**Root Cause Investigation:**
- Extracted Mercury SDK AAR classes and confirmed `BaseEventActivity` provides `templeActionViewModel` and `BaseMirrorActivity` extends it
- Discovered the touch event pipeline: temple touchpad → `cyttsp5_mt`/`cyttsp6_mt` input devices → `BaseTouchActivity.onTouchEvent` → `TouchDispatcher` → gesture detection
- **Found the bug:** The WebView in `activity_meshtalk.xml` was consuming touch DOWN events before the SDK's gesture detector could see them. Only MOVE and UP events were reaching `BaseTouchActivity.onTouchEvent`
- Confirmed that the BACK key (`adb shell input keyevent KEYCODE_BACK`) triggers `exitApp()` successfully — app exits cleanly and Mercury returns to launcher (does NOT re-launch the app)

**Key finding:** Mercury does NOT re-launch killed apps. When `exitApp()` is called, Mercury logs `[top_app_leave]` and shows the launcher normally.

### 2. Applied the Fix (4 patches)

#### Patch A: XML Layout (`activity_meshtalk.xml`)
- Added `focusable="false"`, `focusableInTouchMode="false"`, `clickable="false"` to the WebView element
- This prevents the WebView from claiming touch focus at the Android framework level

#### Patch B: WebView Touch Passthrough (`MeshTalkActivity.kt` — onCreate)
- Added `webView.setOnTouchListener { _, _ -> false }` — always returns false so touches pass through
- Set `webView.isClickable = false` and `webView.isFocusable = false` programmatically
- Belt-and-suspenders approach: XML attributes + code-level enforcement

#### Patch C: Fallback Double-Tap Detector (`MeshTalkActivity.kt` — dispatchTouchEvent)
- Added `override fun dispatchTouchEvent(ev: MotionEvent): Boolean` method
- Implements a manual double-tap detector: on `ACTION_DOWN`, checks if two taps occur within 400ms
- If double-tap detected → calls `exitApp()` (the same method the SDK's gesture system calls)
- Always calls `super.dispatchTouchEvent(ev)` so the SDK's own gesture pipeline still works for swipes, long-press, etc.

#### Patch D: Gesture Logging (`MeshTalkActivity.kt` — temple gesture observer)
- Changed the `else` branch in the temple gesture handler from silent ignore to logging `action::class.simpleName`
- Now we can see in logcat if unrecognized gestures are arriving

### 3. Built the APK

```
cd ~/meshtalk && ./gradlew clean assembleDebug
```
- BUILD SUCCESSFUL (43 tasks, 3 seconds)
- Output: `~/meshtalk/app/build/outputs/apk/debug/app-debug.apk` (82MB)

### 4. Installed on Both Glasses

```
adb -s A06B4A8FF4A1633 install -r app-debug.apk  → Success
adb -s A06B4A94CC51663 install -r app-debug.apk  → Success
```

### 5. Launched & Configured Mercury

- Force-stopped old instances on both glasses
- Started `com.openclaw.app/.MeshTalkActivity` on both
- Set `mercury_background_whitelist` and `mercury_app_unlock` for `com.openclaw.app` on both glasses

### 6. Friend Tracker Backend Stabilization

#### Fixed Location Flush Cycles
- **Old:** 2.5-min flush cycle — too aggressive, missed commute data
- **New:** 10-min flush window in `scripts/findmy_flush_loop.sh`
- **Critical fix:** DB is now copied BEFORE quitting FindMy (not after), preventing WAL data loss

#### Fixed /debug Endpoint
- StaticFiles mount was intercepting all paths including `/debug` → 404
- Moved StaticFiles mount to end of file so API routes take priority

#### Fixed Fast Refresh Sidecar
- Old sidecar tried to write to FindMy's DB directly → "Operation not permitted" (no FDA for launchd agents)
- New sidecar calls local API endpoint instead: `GET /api/friends/{name}/refresh`

#### Set Up 4 Persistent Daemons
All running via launchd LaunchAgents:
- `com.friendtracker.api` — uvicorn on port 8420
- `com.friendtracker.flush` — 10-min FindMy flush loop
- `com.friendtracker.fast-refresh` — 30s polling sidecar
- `com.friendtracker.tunnel` — cloudflared quick tunnel

### 7. RayNeo Carousel Crash Investigation (from prior work, still relevant)
- GlassPay at position 11 in the Mercury carousel has empty `packageName` and 0-dimension icon → crashes `RecyclerView.onMeasure`
- **Fix applied:** All sideloaded APKs use `DEFAULT` category (not `LAUNCHER`) in their manifests — hidden from carousel, launched via ADB only
- MeshTalk manifest (`com.openclaw.app`) was fixed in commit `ac53030`

---

## What's Left To Do

### Immediate (Today/Tonight)

#### A. Test Double-Tap Exit on Physical Glasses
- [ ] Put on glasses, launch MeshTalk, double-tap temple to exit
- [ ] Verify app exits cleanly (Mercury shows launcher)
- [ ] Verify app does NOT get re-launched by Mercury
- [ ] Check logcat for gesture events: `adb -s <serial> logcat -s MeshTalk:V`
- [ ] If double-tap still doesn't work, check if `dispatchTouchEvent` is firing at all (look for logs)
- [ ] Test on BOTH G1 and G2

#### B. Validate 10-Min Flush Captures Commute Data
- [ ] Monitor Sussman's drive (currently active)
- [ ] Tonight: Ethan and Kheli commute test
- [ ] Verify location updates appear in the API within the 10-min window
- [ ] Check `scripts/findmy_flush_loop.sh` logs for errors

#### C. Run setup_pyicloud.py Interactively
- **BLOCKED on user action** — needs Apple ID password + 2FA
- Command: `cd ~/projects/friend-tracker && .venv311/bin/python3 scripts/setup_pyicloud.py`
- iCloud account: `vincentscoreone@gmail.com`

### Short-Term (This Week)

#### D. Permanent Public URL
- Current cloudflare quick tunnel URL is ephemeral (changes on restart)
- **Options:**
  1. Named Cloudflare tunnel with custom domain
  2. Tailscale Funnel (if available on plan)
- Need stable URL for glasses WebView apps and shared friend-tracker links

#### E. Audio Pipeline for Glasses
- [ ] Implement VAD (Voice Activity Detection) audio capture on glasses
- [ ] Must use `VOICE_RECOGNITION` audio source (not `MIC`) alongside Camera2
- [ ] Stream audio to Mac Mini backend
- [ ] Integrate with Hume AI for emotional analysis
- [ ] End-to-end validation: glasses → backend → emotion results

#### F. Glasses LiveStream App
- [ ] Build and install the refactored glasses livestream APK
- [ ] Video frames need 90° CW rotation for web viewers
- [ ] POV watcher daemon: `pov_adb_watcher.py` (launchd: `com.friendtracker.pov-watcher`)
- [ ] Set WebSocket URL via `run-as com.glasses.livestream` SharedPreferences
- [ ] Test stream quality and latency

#### G. Codec Gap Resolution
- Android glasses encode audio as **Opus**
- iOS (MeshTalk companion) expects **PCM16**
- Need transcoding layer or unified codec

### Medium-Term (Ongoing)

#### H. MeshTalk Feature Completeness
- Per the `ar-glasses-master-sdk` spec:
  - [ ] Walkie-talkie push-to-talk via temple gesture
  - [ ] Proximity-based peer discovery
  - [ ] HUD overlay for active call status
  - [ ] Battery-aware audio codec selection

#### I. Friend Tracker Web Frontend
- [ ] Map visualization with friend locations
- [ ] Real-time updates via SSE or WebSocket
- [ ] Emotion overlays from Hume AI analysis
- [ ] Mobile-responsive for phone + glasses WebView

#### J. Apple Signing
- **BLOCKED:** Inflection AI org has PLA issue — Developer role (Kheli's access) can't provision
- Need to resolve with Apple or use a different signing identity
- Currently deploying unsigned debug APKs via ADB (works fine for dev)

---

## Key Files Modified This Session

| File | What Changed |
|------|-------------|
| `~/meshtalk/app/src/main/java/com/openclaw/app/MeshTalkActivity.kt` | Added `dispatchTouchEvent()` fallback double-tap detector, WebView touch passthrough, gesture logging |
| `~/meshtalk/app/src/main/res/layout/activity_meshtalk.xml` | WebView: `focusable=false`, `focusableInTouchMode=false`, `clickable=false` |
| `~/meshtalk/app/build/outputs/apk/debug/app-debug.apk` | Rebuilt APK with all fixes (82MB) |
| `~/projects/friend-tracker/scripts/findmy_flush_loop.sh` | 10-min flush, DB copy before quit |
| `~/projects/friend-tracker/scripts/fast_refresh_sidecar.py` | Calls local API instead of direct DB access |
| `~/projects/friend-tracker/backend/main.py` | StaticFiles mount moved to EOF, /debug fix |

## Known Gotchas & Pitfalls

1. **Mercury SDK auto-reinstalls zombie** — always `disable + suspend` Mercury's own background service
2. **ADB input CANNOT reproduce carousel crash** — false negatives; must test on physical device
3. **Android notifications do NOT render on Mercury HUD** — must use in-app overlays for any user-facing messages
4. **GCP project `serene-snowfall-345113` is SUSPENDED** — appeal sent 2026-05-20, no Cloud Run deploys possible
5. **`friend-map.apk` is CORRUPT** — do not attempt to install
6. **Glasses touchpad devices:** `cyttsp5_mt` (G1 right temple), `cyttsp6_mt` (G1 left temple) — useful for debugging touch events via `getevent`
7. **Cloudflare tunnel log:** `/tmp/friend-tracker-tunnel.log` — check here if URL changes
8. **Python venv:** always use `~/projects/friend-tracker/.venv311` (Python 3.11)
