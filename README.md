# PocketDisplay

PocketDisplay turns an Android phone such as a Samsung Galaxy S10+ into a USB secondary display for Windows. The Windows server captures a selected monitor with FFmpeg `gdigrab`, encodes low-latency H.264, streams it over TCP through ADB reverse USB forwarding, and the Android app decodes it with `MediaCodec` into a `SurfaceView`. Touch input from the phone is sent back to Windows and injected as mouse events.

## Features

- 1920x1080 streaming at 30 FPS
- USB transport through ADB reverse forwarding
- H.264 baseline profile for broad decoder compatibility
- Touch input forwarding from phone to Windows
- Debug HUD with in-app toggle
- Auto-reconnect after disconnects
- Dynamic resolution detection from H.264 SPS data on Android
- One-click launcher via `start_usb.bat`
- YAML-based server configuration via `configuration.yaml`

## Requirements

- Windows 10 or Windows 11
- Python 3.10+
- FFmpeg available on `PATH`
- ADB available on `PATH`
- Android 8.0+ device
- USB debugging enabled on the phone
- Android Studio or Gradle to build the APK
- A virtual/secondary display available in Windows

## Quick Start

1. Install server dependencies:
   ```powershell
   py -3 -m pip install -r server\requirements.txt
   ```
2. Build the Android app:
   ```powershell
   cd android
   .\gradlew.bat assembleDebug
   ```
   Or open `android\` in Android Studio and build/install from there.
3. Connect the phone over USB and accept the ADB authorization prompt.
4. Run the launcher:
   ```powershell
   .\start_usb.bat
   ```
5. Open the app on the phone. Keep the server host as `127.0.0.1` for USB mode.

For an automated setup, run `install.ps1`.

## Configuration

Runtime settings live in `configuration.yaml` at the repository root.

### `display`
- `monitor_index`: target display to capture
- `capture_width` / `capture_height`: fallback capture size
- `capture_fps`: target frame rate

### `encoding`
- `preset`: x264 preset
- `tune`: x264 tune value
- `bitrate`: target and max bitrate
- `keyframe_interval`: GOP size
- `profile`: H.264 profile
- `level`: H.264 level
- `slices`: number of slices per frame
- `threads`: encoder threads

### `network`
- `video_port`: primary multiplexed video/touch port
- `control_port`: fallback control port
- `host`: bind address

### `android`
- `default_server`: default phone-side server address

`server\config.py` loads the YAML file and falls back to built-in defaults if the file is missing.

## Project Structure

```text
C:\temp\android_camera
├── android\                               # Android receiver app
│   └── app\src\main\java\com\seconddisplay\receiver\
│       ├── MainActivity.kt
│       ├── StreamClient.kt
│       ├── ControlClient.kt
│       └── H264Decoder.kt
├── server\                                # Windows streaming server
│   ├── server.py
│   ├── stream_server.py
│   ├── touch_receiver.py
│   ├── adb_setup.py
│   ├── config.py
│   └── requirements.txt
├── configuration.yaml
├── install.ps1
├── start_usb.bat
└── README.md
```

## Troubleshooting

- **ADB device not detected**: verify USB debugging, cable quality, and device authorization.
- **FFmpeg not found**: install FFmpeg and add it to `PATH`.
- **Black or frozen video**: confirm the selected `monitor_index` and that the target display is active.
- **Green/pink corruption**: keep baseline profile, single-slice output, and current Android decoder logic.
- **No touch input**: make sure the phone is connected through the active video socket and the server is running as the logged-in desktop user.
- **Connection drops**: keep the screen unlocked during testing and re-run `start_usb.bat` to refresh ADB reverse rules.

## Additional Docs

- `INSTALL.md` - detailed installation guide
- `ARCHITECTURE.md` - component and protocol details
- `CONTRIBUTING.md` - contributor workflow
- `SECURITY.md` - security considerations
- `CHANGELOG.md` - release history

## License

MIT. See `LICENSE.md`.
