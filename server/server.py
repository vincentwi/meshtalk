import asyncio
import json
import struct
import time
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse
import uvicorn

app = FastAPI(title="MeshTalk Monitor")

# State
glasses_connections: dict[str, WebSocket] = {}
viewer_connections: set[WebSocket] = set()
audio_stats = {"chunks_received": 0, "last_chunk_time": 0.0, "connected_glasses": 0}

VIEWER_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>MeshTalk Monitor</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'SF Mono', 'Menlo', monospace;
    background: #0a0a0f;
    color: #e0e0e0;
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 100vh;
  }
  .container {
    width: 420px;
    background: #12121a;
    border: 1px solid #1e1e2e;
    border-radius: 16px;
    padding: 32px;
    box-shadow: 0 8px 32px rgba(0,0,0,0.6);
  }
  .header {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 28px;
  }
  .header h1 {
    font-size: 20px;
    font-weight: 600;
    color: #ffffff;
  }
  .header .subtitle {
    font-size: 12px;
    color: #666;
    margin-top: 2px;
  }
  .status-dot {
    width: 12px;
    height: 12px;
    border-radius: 50%;
    background: #ff3b30;
    flex-shrink: 0;
    transition: background 0.3s;
  }
  .status-dot.connected {
    background: #30d158;
    box-shadow: 0 0 8px rgba(48,209,88,0.5);
    animation: pulse 2s infinite;
  }
  @keyframes pulse {
    0%, 100% { box-shadow: 0 0 8px rgba(48,209,88,0.3); }
    50% { box-shadow: 0 0 16px rgba(48,209,88,0.6); }
  }
  .status-text {
    font-size: 13px;
    color: #888;
    margin-bottom: 24px;
  }
  .status-text.active { color: #30d158; }

  .meter-section {
    margin-bottom: 24px;
  }
  .meter-label {
    font-size: 11px;
    color: #555;
    text-transform: uppercase;
    letter-spacing: 1px;
    margin-bottom: 8px;
  }
  .meter-bar-bg {
    width: 100%;
    height: 8px;
    background: #1a1a2e;
    border-radius: 4px;
    overflow: hidden;
  }
  .meter-bar {
    height: 100%;
    width: 0%;
    background: linear-gradient(90deg, #30d158, #34c759, #ffd60a, #ff9500, #ff3b30);
    border-radius: 4px;
    transition: width 0.05s ease-out;
  }
  .db-value {
    font-size: 11px;
    color: #555;
    text-align: right;
    margin-top: 4px;
  }

  .stats {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
    margin-bottom: 24px;
  }
  .stat {
    background: #1a1a2e;
    border-radius: 8px;
    padding: 12px;
  }
  .stat-label {
    font-size: 10px;
    color: #555;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }
  .stat-value {
    font-size: 22px;
    font-weight: 700;
    color: #fff;
    margin-top: 4px;
  }

  .volume-section {
    margin-bottom: 8px;
  }
  .volume-label {
    font-size: 11px;
    color: #555;
    text-transform: uppercase;
    letter-spacing: 1px;
    margin-bottom: 8px;
    display: flex;
    justify-content: space-between;
  }
  input[type="range"] {
    -webkit-appearance: none;
    width: 100%;
    height: 6px;
    background: #1a1a2e;
    border-radius: 3px;
    outline: none;
  }
  input[type="range"]::-webkit-slider-thumb {
    -webkit-appearance: none;
    width: 18px;
    height: 18px;
    border-radius: 50%;
    background: #30d158;
    cursor: pointer;
    border: 2px solid #0a0a0f;
  }
  .footer {
    text-align: center;
    font-size: 10px;
    color: #333;
    margin-top: 16px;
  }
</style>
</head>
<body>
<div class="container">
  <div class="header">
    <div class="status-dot" id="statusDot"></div>
    <div>
      <h1>MeshTalk Monitor</h1>
      <div class="subtitle">Passive Audio Stream</div>
    </div>
  </div>

  <div class="status-text" id="statusText">Connecting...</div>

  <div class="meter-section">
    <div class="meter-label">Audio Level</div>
    <div class="meter-bar-bg">
      <div class="meter-bar" id="meterBar"></div>
    </div>
    <div class="db-value" id="dbValue">-∞ dB</div>
  </div>

  <div class="stats">
    <div class="stat">
      <div class="stat-label">Glasses</div>
      <div class="stat-value" id="glassesCount">0</div>
    </div>
    <div class="stat">
      <div class="stat-label">Chunks</div>
      <div class="stat-value" id="chunkCount">0</div>
    </div>
    <div class="stat">
      <div class="stat-label">Latency</div>
      <div class="stat-value" id="latencyVal">—</div>
    </div>
    <div class="stat">
      <div class="stat-label">Uptime</div>
      <div class="stat-value" id="uptimeVal">0s</div>
    </div>
  </div>

  <div class="volume-section">
    <div class="volume-label">
      <span>Volume</span>
      <span id="volumeVal">80%</span>
    </div>
    <input type="range" id="volumeSlider" min="0" max="100" value="80">
  </div>

  <div class="footer">PCM16 · 16kHz · Mono · WebSocket</div>
</div>

<script>
(function() {
  const SAMPLE_RATE = 16000;
  const RECONNECT_DELAY = 5000;

  let ws = null;
  let audioCtx = null;
  let gainNode = null;
  let chunkCount = 0;
  let startTime = Date.now();
  let lastChunkTime = 0;
  let connected = false;

  // Audio playback buffer queue
  let nextPlayTime = 0;
  let initialized = false;

  const statusDot = document.getElementById('statusDot');
  const statusText = document.getElementById('statusText');
  const meterBar = document.getElementById('meterBar');
  const dbValue = document.getElementById('dbValue');
  const glassesCount = document.getElementById('glassesCount');
  const chunkCountEl = document.getElementById('chunkCount');
  const latencyVal = document.getElementById('latencyVal');
  const uptimeVal = document.getElementById('uptimeVal');
  const volumeSlider = document.getElementById('volumeSlider');
  const volumeVal = document.getElementById('volumeVal');

  function initAudio() {
    if (audioCtx) return;
    audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: SAMPLE_RATE });
    gainNode = audioCtx.createGain();
    gainNode.gain.value = volumeSlider.value / 100;
    gainNode.connect(audioCtx.destination);
    nextPlayTime = audioCtx.currentTime;
    initialized = true;
  }

  function playPCM16(arrayBuffer) {
    if (!audioCtx || !initialized) return;

    // Resume if suspended (autoplay policy)
    if (audioCtx.state === 'suspended') {
      audioCtx.resume();
    }

    const int16 = new Int16Array(arrayBuffer);
    const numSamples = int16.length;
    if (numSamples === 0) return;

    // Convert Int16 to Float32
    const float32 = new Float32Array(numSamples);
    for (let i = 0; i < numSamples; i++) {
      float32[i] = int16[i] / 32768.0;
    }

    // Calculate RMS for level meter
    let sumSq = 0;
    for (let i = 0; i < numSamples; i++) {
      sumSq += float32[i] * float32[i];
    }
    const rms = Math.sqrt(sumSq / numSamples);
    const db = rms > 0 ? 20 * Math.log10(rms) : -100;
    const pct = Math.max(0, Math.min(100, (db + 60) / 60 * 100));
    meterBar.style.width = pct + '%';
    dbValue.textContent = db > -100 ? db.toFixed(1) + ' dB' : '-∞ dB';

    // Schedule playback
    const buffer = audioCtx.createBuffer(1, numSamples, SAMPLE_RATE);
    buffer.getChannelData(0).set(float32);

    const source = audioCtx.createBufferSource();
    source.buffer = buffer;
    source.connect(gainNode);

    const now = audioCtx.currentTime;
    // If we've fallen behind, catch up
    if (nextPlayTime < now) {
      nextPlayTime = now + 0.01;
    }
    source.start(nextPlayTime);
    nextPlayTime += numSamples / SAMPLE_RATE;
  }

  function connect() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = proto + '//' + location.host + '/ws/viewer';
    ws = new WebSocket(url);
    ws.binaryType = 'arraybuffer';

    ws.onopen = function() {
      connected = true;
      statusDot.classList.add('connected');
      statusText.textContent = 'Listening to MeshTalk glasses...';
      statusText.classList.add('active');
      startTime = Date.now();
      initAudio();
    };

    ws.onmessage = function(event) {
      if (event.data instanceof ArrayBuffer) {
        chunkCount++;
        lastChunkTime = Date.now();
        playPCM16(event.data);
      }
    };

    ws.onclose = function() {
      connected = false;
      statusDot.classList.remove('connected');
      statusText.textContent = 'Disconnected. Reconnecting in 5s...';
      statusText.classList.remove('active');
      meterBar.style.width = '0%';
      dbValue.textContent = '-∞ dB';
      setTimeout(connect, RECONNECT_DELAY);
    };

    ws.onerror = function() {
      ws.close();
    };
  }

  // Keepalive ping
  setInterval(function() {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send('ping');
    }
  }, 30000);

  // Poll server status
  async function pollStatus() {
    try {
      const resp = await fetch('/api/status');
      const data = await resp.json();
      glassesCount.textContent = data.glasses_connected;
    } catch(e) {}
  }
  setInterval(pollStatus, 2000);

  // Update display
  setInterval(function() {
    chunkCountEl.textContent = chunkCount > 999 ? (chunkCount/1000).toFixed(1) + 'k' : chunkCount;

    if (connected) {
      const elapsed = Math.floor((Date.now() - startTime) / 1000);
      if (elapsed < 60) uptimeVal.textContent = elapsed + 's';
      else if (elapsed < 3600) uptimeVal.textContent = Math.floor(elapsed/60) + 'm';
      else uptimeVal.textContent = Math.floor(elapsed/3600) + 'h';
    }

    if (lastChunkTime > 0 && connected) {
      const lat = Date.now() - lastChunkTime;
      latencyVal.textContent = lat < 1000 ? lat + 'ms' : '—';
    }

    // Decay meter if no recent audio
    if (Date.now() - lastChunkTime > 200) {
      const cur = parseFloat(meterBar.style.width) || 0;
      if (cur > 0) {
        meterBar.style.width = Math.max(0, cur - 3) + '%';
      }
    }
  }, 100);

  // Volume
  volumeSlider.addEventListener('input', function() {
    const v = this.value / 100;
    volumeVal.textContent = this.value + '%';
    if (gainNode) gainNode.gain.value = v;
  });

  // Click anywhere to init audio context (autoplay policy)
  document.addEventListener('click', initAudio, { once: true });

  connect();
})();
</script>
</body>
</html>"""


@app.get("/")
async def index():
    return HTMLResponse(VIEWER_HTML)


@app.get("/api/status")
async def status():
    return {
        "glasses_connected": len(glasses_connections),
        "viewers_connected": len(viewer_connections),
        "audio_chunks": audio_stats["chunks_received"],
        "last_audio": audio_stats["last_chunk_time"],
    }


@app.websocket("/ws/glasses")
async def glasses_ws(ws: WebSocket):
    await ws.accept()
    glass_id = f"glass_{int(time.time()*1000) % 100000}"
    glasses_connections[glass_id] = ws
    audio_stats["connected_glasses"] = len(glasses_connections)
    print(f"[+] Glasses connected: {glass_id} (total: {len(glasses_connections)})")

    # Notify viewers of glasses count change
    await _broadcast_status()

    try:
        while True:
            data = await ws.receive_bytes()
            audio_stats["chunks_received"] += 1
            audio_stats["last_chunk_time"] = time.time()

            # Relay raw PCM16 to all viewers
            dead: list[WebSocket] = []
            for viewer in viewer_connections:
                try:
                    await viewer.send_bytes(data)
                except Exception:
                    dead.append(viewer)
            for d in dead:
                viewer_connections.discard(d)

    except WebSocketDisconnect:
        pass
    except Exception as e:
        print(f"[!] Glasses error: {e}")
    finally:
        glasses_connections.pop(glass_id, None)
        audio_stats["connected_glasses"] = len(glasses_connections)
        print(f"[-] Glasses disconnected: {glass_id} (remaining: {len(glasses_connections)})")


@app.websocket("/ws/viewer")
async def viewer_ws(ws: WebSocket):
    await ws.accept()
    viewer_connections.add(ws)
    print(f"[+] Viewer connected (total: {len(viewer_connections)})")
    try:
        while True:
            await ws.receive_text()  # keepalive pings
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        viewer_connections.discard(ws)
        print(f"[-] Viewer disconnected (remaining: {len(viewer_connections)})")


async def _broadcast_status():
    """Push status update to all viewers as JSON text frame."""
    msg = json.dumps({
        "type": "status",
        "glasses_connected": len(glasses_connections),
    })
    dead: list[WebSocket] = []
    for viewer in viewer_connections:
        try:
            await viewer.send_text(msg)
        except Exception:
            dead.append(viewer)
    for d in dead:
        viewer_connections.discard(d)


if __name__ == "__main__":
    print("=" * 50)
    print("  MeshTalk Monitor Server")
    print("  http://0.0.0.0:8435")
    print("=" * 50)
    uvicorn.run(app, host="0.0.0.0", port=8435)
