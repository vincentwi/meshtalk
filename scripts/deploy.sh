#!/bin/bash
set -e

ADB=/opt/homebrew/bin/adb
PKG=com.meshtalk.app
APK=app/build/outputs/apk/debug/app-debug.apk

echo "=== Building MeshTalk ==="
cd "$(dirname "$0")/.."
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug 2>&1 | tail -3

SERIALS=($($ADB devices -l | grep 'ARGF20' | awk '{print $1}'))
echo "=== Found ${#SERIALS[@]} glasses ==="

for SERIAL in "${SERIALS[@]}"; do
    echo ""
    echo "=== Deploying to $SERIAL ==="

    # Uninstall first to avoid Mercury OS stopped-state bug
    $ADB -s $SERIAL shell pm uninstall $PKG 2>/dev/null || true
    sleep 2

    $ADB -s $SERIAL shell settings put global mercury_install_allowed 1
    $ADB -s $SERIAL install $APK

    # Grant runtime permissions
    $ADB -s $SERIAL shell pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null || true
    $ADB -s $SERIAL shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    $ADB -s $SERIAL shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
    $ADB -s $SERIAL shell pm grant $PKG android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || true
    $ADB -s $SERIAL shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true

    # CRITICAL: AppOps allow (not foreground-only) for WiFi Aware
    $ADB -s $SERIAL shell appops set $PKG android:coarse_location allow
    $ADB -s $SERIAL shell appops set $PKG android:fine_location allow

    # Battery optimization whitelist
    $ADB -s $SERIAL shell dumpsys deviceidle whitelist +$PKG 2>/dev/null || true

    # Enable WiFi + WiFi Aware
    $ADB -s $SERIAL shell svc wifi enable
    $ADB -s $SERIAL shell settings put secure aware_enabled 1

    # ADB reverse tunnel for streaming server
    $ADB -s $SERIAL reverse tcp:8435 tcp:8435

    sleep 2
    $ADB -s $SERIAL shell am start -n $PKG/.MeshTalkActivity
    echo "    ✓ Deployed on $SERIAL"
done

echo ""
echo "=== All glasses deployed ==="
echo ""
echo "Streaming server: http://localhost:8435"
echo "WiFi Aware enabled, AppOps location=allow, battery whitelist set"
echo ""
echo "TIP: Run scripts/watchdog.sh to keep app alive against Mercury OS kills"
echo ""

if curl -s http://localhost:8435/api/status >/dev/null 2>&1; then
    echo "Server status: $(curl -s http://localhost:8435/api/status)"
else
    echo "⚠ Streaming server not running. Start with: cd server && .venv/bin/python3 server.py"
fi
