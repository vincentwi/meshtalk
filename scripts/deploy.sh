#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# deploy.sh — Build MeshTalk APK and deploy to all connected ARGF20
#              (RayNeo X3 Pro) glasses via ADB.
#
# Usage:  ./scripts/deploy.sh
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

ADB="/opt/homebrew/bin/adb"
PKG="com.meshtalk.app"
ACTIVITY="com.meshtalk.app.MeshTalkActivity"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# ── Colours ───────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Colour

info()  { echo -e "${GREEN}[✓]${NC} $*"; }
warn()  { echo -e "${YELLOW}[!]${NC} $*"; }
fail()  { echo -e "${RED}[✗]${NC} $*"; exit 1; }

# ── Pre-flight checks ────────────────────────────────────────────────
command -v "$ADB" >/dev/null 2>&1 || fail "adb not found at $ADB"

# ── Build APK ─────────────────────────────────────────────────────────
info "Building debug APK …"
cd "$PROJECT_DIR"
./gradlew assembleDebug --quiet || fail "Gradle build failed"

APK=$(find "$PROJECT_DIR/app/build/outputs/apk/debug" -name '*.apk' | head -1)
[ -f "$APK" ] || fail "APK not found after build"
info "APK: $APK"

# ── Discover ARGF20 glasses ──────────────────────────────────────────
# ARGF20 is the RayNeo X3 Pro model identifier.
# We look for any connected device (the glasses typically report as the
# only adb device). Filter by model if multiple devices are attached.
DEVICES=()
while IFS= read -r serial; do
    [ -z "$serial" ] && continue
    model=$("$ADB" -s "$serial" shell getprop ro.product.model 2>/dev/null || echo "unknown")
    # Accept all devices — but log the model so we know what we hit
    info "Found device: $serial (model: $model)"
    DEVICES+=("$serial")
done < <("$ADB" devices | tail -n +2 | awk '/device$/{print $1}')

[ ${#DEVICES[@]} -eq 0 ] && fail "No ADB devices found. Plug in the glasses and try again."

# ── Deploy to each device ────────────────────────────────────────────
for serial in "${DEVICES[@]}"; do
    echo ""
    info "━━━ Deploying to $serial ━━━"

    # Install APK (replace existing)
    info "Installing APK …"
    "$ADB" -s "$serial" install -r "$APK" || { warn "Install failed on $serial"; continue; }

    # Grant runtime permissions
    info "Granting permissions …"
    "$ADB" -s "$serial" shell pm grant "$PKG" android.permission.RECORD_AUDIO         2>/dev/null || true
    "$ADB" -s "$serial" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION  2>/dev/null || true
    "$ADB" -s "$serial" shell pm grant "$PKG" android.permission.NEARBY_WIFI_DEVICES   2>/dev/null || true

    # Ensure WiFi is enabled
    info "Enabling WiFi …"
    "$ADB" -s "$serial" shell svc wifi enable 2>/dev/null || true

    # Launch the activity
    info "Launching MeshTalk …"
    "$ADB" -s "$serial" shell am start -n "$PKG/$ACTIVITY" 2>/dev/null || warn "Launch failed on $serial"

    info "Done with $serial"
done

echo ""
info "All devices deployed! 🎙️"
