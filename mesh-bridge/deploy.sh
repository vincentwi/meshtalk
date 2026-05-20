#!/usr/bin/env bash
set -euo pipefail

IMAGE="meshtalk-bridge:latest"
PORT=8440

echo "=== MeshTalk Bridge — Docker Build ==="
echo ""

docker build -t "$IMAGE" "$(dirname "$0")"

echo ""
echo "✅ Built $IMAGE"
echo ""
echo "=== Deploy Instructions ==="
echo ""
echo "1. Run locally:"
echo "   docker run -d --name meshtalk-bridge -p ${PORT}:${PORT} $IMAGE"
echo ""
echo "2. Deploy to any VPS:"
echo "   # Save image"
echo "   docker save $IMAGE | gzip > meshtalk-bridge.tar.gz"
echo "   # Copy to VPS"
echo "   scp meshtalk-bridge.tar.gz user@your-vps:~/"
echo "   # On VPS"
echo "   ssh user@your-vps 'gunzip -c meshtalk-bridge.tar.gz | docker load && \\"
echo "     docker run -d --restart unless-stopped --name meshtalk-bridge -p ${PORT}:${PORT} $IMAGE'"
echo ""
echo "3. Or push to a registry:"
echo "   docker tag $IMAGE your-registry.com/$IMAGE"
echo "   docker push your-registry.com/$IMAGE"
echo ""
echo "Bridge will be available at http://<host>:${PORT}"
echo "WebSocket endpoint: ws://<host>:${PORT}/ws/talk"
