#!/bin/bash
# MeshTalk ADB watchdog — keeps the app alive on Mercury OS
# Usage: ./scripts/watchdog.sh
# Runs forever, checks every 3 seconds, relaunches if killed

ADB=/opt/homebrew/bin/adb
PKG=com.meshtalk.app

echo "MeshTalk Watchdog — monitoring both glasses"
echo "Press Ctrl+C to stop"
echo ""

while true; do
    for SERIAL in $($ADB devices -l | grep ARGF20 | awk '{print $1}'); do
        PID=$($ADB -s $SERIAL shell pidof $PKG 2>/dev/null)
        if [ -z "$PID" ]; then
            SHORT=${SERIAL: -6}
            echo "[$(date +%H:%M:%S)] $SHORT: App died — relaunching..."
            $ADB -s $SERIAL shell am start -n $PKG/.MeshTalkActivity >/dev/null 2>&1
        fi
    done
    sleep 3
done
