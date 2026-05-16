"""ADB reverse port forwarding setup."""

import subprocess
import sys
from config import ADB_PATH, VIDEO_PORT, CONTROL_PORT


def check_device():
    """Check if an ADB device is connected."""
    try:
        result = subprocess.run(
            [ADB_PATH, "devices"],
            capture_output=True,
            text=True,
            check=False,
        )
    except FileNotFoundError:
        print(f"ERROR: ADB not found at '{ADB_PATH}'")
        print("Install Android SDK Platform-Tools and ensure adb is accessible.")
        print("Download: https://developer.android.com/tools/releases/platform-tools")
        return False

    lines = result.stdout.strip().splitlines()
    devices = [line for line in lines[1:] if line.rstrip().endswith("\tdevice")]
    if not devices:
        print("ERROR: No ADB device connected.")
        print("Make sure:")
        print("  1. USB debugging is enabled on your phone")
        print("  2. Phone is connected via USB")
        print("  3. You've authorized the computer on the phone")
        return False

    print(f"Found {len(devices)} device(s):")
    for device in devices:
        print(f"  {device}")
    return True


def _reverse_port(port, required=True):
    """Set up ADB reverse for one port."""
    cmd = [ADB_PATH, "reverse", f"tcp:{port}", f"tcp:{port}"]
    result = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        level = "ERROR" if required else "WARNING"
        print(f"{level}: Failed to set up reverse for port {port}")
        if result.stderr.strip():
            print(f"  {result.stderr.strip()}")
        return not required

    print(f"  Reverse port forwarding: phone:{port} -> PC:{port}")
    return True


def setup_reverse_ports():
    """Set up required and optional ADB reverse port forwarding."""
    if not _reverse_port(VIDEO_PORT, required=True):
        return False

    if CONTROL_PORT != VIDEO_PORT:
        _reverse_port(CONTROL_PORT, required=False)

    return True


def main():
    print("Setting up ADB reverse port forwarding...")
    print()
    if not check_device():
        sys.exit(1)
    print()
    if not setup_reverse_ports():
        sys.exit(1)
    print()
    print("ADB setup complete! Phone can now connect to PC via localhost.")


if __name__ == "__main__":
    main()
