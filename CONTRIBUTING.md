# Contributing

Thanks for contributing to PocketDisplay.

## Development Setup

1. Install Python 3.10+, FFmpeg, ADB, and Android Studio.
2. Install Python dependencies:
   ```powershell
   py -3 -m pip install -r server\requirements.txt
   ```
3. Open `android\` in Android Studio or use `android\gradlew.bat` from the command line.
4. Connect a test Android device with USB debugging enabled.
5. Keep a secondary or virtual display available on the Windows machine.

See `INSTALL.md` for full setup details.

## Coding Style

### Python
- Follow PEP 8.
- Keep modules focused and avoid unnecessary dependencies.
- Prefer clear logging around connection, capture, and shutdown flows.

### Kotlin
- Follow Kotlin style conventions used by Android Studio.
- Keep UI code in `MainActivity` and transport/decoder logic in dedicated classes.
- Prefer lifecycle-safe cleanup for sockets, coroutines, and decoder resources.

## Pull Request Process

1. Create a topic branch from the latest main branch.
2. Make focused changes with clear commit messages.
3. Update docs when behavior, setup, or configuration changes.
4. Verify the Python server still starts and the Android app still builds.
5. Submit a pull request describing the problem, approach, and validation steps.

## Testing

Before opening a PR, run what applies:

```powershell
python -m compileall server
cd android
.\gradlew.bat assembleDebug
```

Manual validation is also important:
- confirm video appears on the phone
- confirm touch input reaches Windows
- confirm reconnect works after unplug/replug
- confirm the configured monitor is the one being captured

## Reporting Issues

When filing bugs, include:
- Windows version
- Android device model and Android version
- FFmpeg version
- ADB version
- relevant server logs or logcat snippets
- reproduction steps
