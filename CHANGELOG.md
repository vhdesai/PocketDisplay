# Changelog

## [1.0.0] - 2026-05-16

### Added
- Full 1920x1080 @ 30fps H.264 streaming over USB
- Touch input forwarding (phone touch → Windows mouse events)
- Multiplexed touch/video over single TCP connection (port 5000)
- Debug HUD with toggle in settings
- Auto-reconnection on disconnect
- Dynamic resolution detection from H.264 SPS headers
- ADB reverse port forwarding setup (automated)
- One-click launcher (start_usb.bat)
- Configuration via YAML file

### Fixed
- Green/pink screen corruption (baseline profile + single slice + skip repeated SPS/PPS)
- Samsung ADB single-port limitation (multiplexed control over video socket)
- WiFi throughput issues (switched to USB-only)
- NAL parser O(n²) performance bug
- Decoder blocking causing TCP backpressure
