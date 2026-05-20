#!/bin/bash
# MeshTalk Bridge — start script
# Starts rnsd (if not running) + mesh bridge server

set -e
cd "$(dirname "$0")"

echo "🔊 MeshTalk Mesh Bridge"
echo "========================"

# Check / start rnsd
if pgrep -x rnsd > /dev/null 2>&1; then
    echo "✓ rnsd already running (PID $(pgrep -x rnsd))"
else
    echo "Starting rnsd in background…"
    rnsd &
    RNSD_PID=$!
    echo "✓ rnsd started (PID $RNSD_PID)"
    sleep 2
fi

# Activate venv
if [ -d ".venv" ]; then
    source .venv/bin/activate
else
    echo "ERROR: .venv not found. Run: uv venv .venv --python 3.13 && uv sync"
    exit 1
fi

# Start bridge
echo ""
echo "Starting mesh bridge on port 8440…"
exec python bridge.py --host 0.0.0.0 --port 8440
