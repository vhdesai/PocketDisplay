# Installation Guide

## Prerequisites

Install the following first:

- Python 3.10 or newer: https://www.python.org/downloads/windows/
- FFmpeg: https://ffmpeg.org/download.html
- Android SDK Platform-Tools (ADB): https://developer.android.com/tools/releases/platform-tools
- Android Studio: https://developer.android.com/studio
- **Virtual display driver** (required): creates a dedicated Windows monitor for the phone to display. See [docs/virtual-display-setup.md](docs/virtual-display-setup.md) for step-by-step installation.

Make sure `python`/`py`, `ffmpeg`, and `adb` are available on `PATH`.

## Virtual Display Setup

PocketDisplay captures the output of a Windows monitor and streams it to your phone. For the phone to act as a **secondary display** (not just a mirror of your main screen), Windows must have a virtual monitor that PocketDisplay can capture.

1. Install the recommended virtual display driver — see [docs/virtual-display-setup.md](docs/virtual-display-setup.md).
2. After installation, open **Settings → System → Display** and arrange the virtual monitor next to your physical monitors.
3. Note the monitor's position number — this is the `monitor_index` value in `configuration.yaml`.
4. Set the virtual display resolution to match your phone's screen (e.g., 1920×1080).

> **Without a virtual display driver**, PocketDisplay will mirror an existing physical monitor instead of creating an independent second screen.

## Server Setup

1. Open PowerShell in `C:\temp\android_camera`.
2. Install Python packages:
   ```powershell
   py -3 -m pip install -r server\requirements.txt
   ```
3. Review `configuration.yaml` and adjust monitor, resolution, bitrate, or ports if needed.
4. Confirm FFmpeg works:
   ```powershell
   ffmpeg -version
   ```
5. Confirm ADB works:
   ```powershell
   adb version
   ```

## Android App Build

### Android Studio
1. Open the `android\` folder in Android Studio.
2. Let Gradle sync.
3. Select a connected device.
4. Build and run the app.

### Command Line
1. Open PowerShell.
2. Build the debug APK:
   ```powershell
   cd android
   .\gradlew.bat assembleDebug
   ```
3. The APK will usually be generated at:
   `android\app\build\outputs\apk\debug\app-debug.apk`

## Phone Setup

1. Enable Developer Options on the phone.
2. Enable **USB debugging**.
3. Connect the phone with a data-capable USB cable.
4. Accept the PC authorization prompt on the device.
5. Install any required OEM USB drivers on Windows if the device is not detected.

To verify the connection:

```powershell
adb devices
```

Your device should appear with the `device` state.

## Running the Project

### Recommended
Run the installer once:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\install.ps1
```

Then start the server with:

```powershell
.\start_usb.bat
```

### Manual Startup
1. Set up ADB reverse:
   ```powershell
   adb reverse tcp:5000 tcp:5000
   adb reverse tcp:5002 tcp:5002
   ```
2. Start the server:
   ```powershell
   cd server
   py -3 server.py
   ```
3. Launch the Android app.
4. Leave the server address as `127.0.0.1` for USB mode.

## Verifying the Connection

- The server should report that FFmpeg started and that the stream server is listening.
- The Android app should connect and display video on the `SurfaceView`.
- Touching the phone screen should move/click the Windows cursor on the captured display.
- If enabled, the debug HUD should show connection and decoder status.

## Troubleshooting

- **No virtual display**: you must install a virtual display driver for PocketDisplay to work as a secondary screen. Without it, the server will only mirror an existing physical monitor. See [docs/virtual-display-setup.md](docs/virtual-display-setup.md).
- **`adb devices` shows nothing**: replug the phone, unlock it, and re-accept the RSA prompt.
- **`ffmpeg` not recognized**: add FFmpeg `bin` to `PATH` and restart the terminal.
- **Gradle build fails**: install a supported JDK and ensure `JAVA_HOME` is configured.
- **Phone connects but no image**: verify the virtual display exists and matches `monitor_index` in `configuration.yaml`.
- **Touch works poorly**: check Windows display layout and monitor placement — the virtual monitor must be correctly arranged in Display Settings.
