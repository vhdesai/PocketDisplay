# Architecture

## System Overview

PocketDisplay requires a **virtual display driver** installed on Windows so that the OS creates a dedicated monitor for the phone. The server captures this virtual monitor with FFmpeg and streams it to the phone over USB. Without a virtual display driver, the server can only mirror an existing physical monitor.

```text
+---------------------------- Windows PC -----------------------------+
|                                                                     |
|  Python Server                                                      |
|  +------------------+    +----------------------+                   |
|  | FFmpeg gdigrab   | -> | libx264 Annex B out  |                   |
|  +------------------+    +----------+-----------+                   |
|                                      |                               |
|                             +--------v---------+                     |
|                             | StreamServer     |==== TCP :5000 ======+==== USB / ADB reverse ====+
|                             | downstream video |<=== touch JSON =====+                           |
|                             +--------+---------+                                                   |
|                                      |                                                             |
|                             +--------v---------+                                                   |
|                             | TouchReceiver    |                                                   |
|                             | Windows mouse    |                                                   |
|                             +------------------+                                                   |
+-----------------------------------------------------------------------------------------------+    |
                                                                                                    |
+-------------------------------- Android Phone --------------------------------------------------+ |
|                                                                                                  | |
|  +------------------+     +----------------------+     +----------------------+                  | |
|  | StreamClient     | --> | H264Decoder          | --> | SurfaceView          |                  | |
|  | TCP socket       |     | MediaCodec hardware  |     | on-screen rendering  |                  | |
|  +---------+--------+     +----------------------+     +----------------------+                  | |
|            |                                                                                     | |
|            +--------------------------> ControlClient -------------------------------------------+ |
|                                      newline-delimited JSON touch events                           |
+----------------------------------------------------------------------------------------------------+
```

## Server Components

### FFmpeg pipeline
`server/server.py` launches FFmpeg with `gdigrab` to capture the selected Windows monitor, then encodes the output as raw Annex B H.264 using `libx264` with low-latency settings.

### StreamServer
`server/stream_server.py` hosts the TCP connection on the video port. It:
- accepts a single Android client
- queues encoded H.264 data for asynchronous sending
- caches SPS/PPS/IDR headers for late join/reconnect cases
- reads upstream touch JSON from the same socket

### TouchReceiver
`server/touch_receiver.py` converts normalized phone coordinates into Windows virtual desktop coordinates and injects mouse move/down/up events with Win32 `mouse_event`.

## Android Components

### StreamClient
`StreamClient.kt` connects to the server on the configured host and port, reads video bytes on `Dispatchers.IO`, and reconnects automatically after disconnects.

### H264Decoder
`H264Decoder.kt` parses Annex B NAL units, detects SPS/PPS, configures `MediaCodec`, and renders decoded frames into the `SurfaceView` surface.

### ControlClient
`ControlClient.kt` writes newline-delimited JSON touch events upstream. In normal USB mode it attaches to the same TCP socket used for video, avoiding the need for a second ADB reverse channel.

### SurfaceView
`MainActivity.kt` owns the `SurfaceView`, starts/stops the pipeline with the activity lifecycle, forwards touch events, and displays the optional debug HUD.

## Data Flow

### Video downstream
1. FFmpeg captures the selected Windows monitor.
2. FFmpeg encodes H.264 Annex B byte stream.
3. `StreamServer` sends encoded chunks over TCP port `5000`.
4. `StreamClient` receives bytes.
5. `H264Decoder` decodes and renders to `SurfaceView`.

### Touch upstream
1. User touches the Android display.
2. `MainActivity` normalizes coordinates to `0.0..1.0`.
3. `ControlClient` sends newline-delimited JSON upstream on the same socket.
4. `StreamServer` reads the JSON lines.
5. `TouchReceiver` injects Windows mouse events.

## Protocol Details

### Video protocol
- Direction: server -> Android
- Transport: TCP
- Payload: raw Annex B H.264 byte stream
- Framing: none beyond H.264 start codes (`00 00 01` / `00 00 00 01`)

### Touch protocol
- Direction: Android -> server
- Transport: same TCP socket as video in USB mode
- Format: newline-delimited JSON
- Example:

```json
{"action":"move","x":0.42,"y":0.77}
```

Supported actions are `down`, `move`, and `up`.

## Threading Model

### Server
- main thread: startup, shutdown, FFmpeg process management
- FFmpeg reader thread: reads encoded stdout and pushes to `StreamServer`
- FFmpeg stderr thread: monitors encoder warnings/errors
- `StreamServer` accept thread: accepts client connections
- `StreamServer` send thread: flushes queued H.264 chunks
- `StreamServer` read thread: processes upstream touch JSON

### Android
- UI thread: activity, `SurfaceView`, settings, HUD
- coroutines on `Dispatchers.IO`: socket connect/read/write work
- `MediaCodec` callback/decoder interaction: frame decode and output to the surface
