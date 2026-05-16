"""Main entry point for the PocketDisplay server."""

import signal
import subprocess
import sys
import threading
import time
from config import (
    MONITOR_INDEX, VIDEO_PORT, CONTROL_PORT, HOST,
    CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS,
    FFMPEG_PATH, H264_PRESET, H264_TUNE, H264_BITRATE,
    KEYFRAME_INTERVAL, H264_PROFILE, H264_LEVEL,
    H264_SLICES, H264_THREADS
)
from stream_server import StreamServer
from touch_receiver import TouchReceiver
from adb_setup import check_device, setup_reverse_ports


def _print_local_ips():
    """Print the local IP addresses of this machine."""
    import socket as _sock
    try:
        hostname = _sock.gethostname()
        addrs = _sock.getaddrinfo(hostname, None, _sock.AF_INET)
        ips = sorted(set(addr[4][0] for addr in addrs if not addr[4][0].startswith("127.")))
        if not ips:
            s = _sock.socket(_sock.AF_INET, _sock.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ips = [s.getsockname()[0]]
            s.close()
        for ip in ips:
            print(f"    >>> {ip} <<<")
    except Exception:
        print("    (Could not determine local IP — check ipconfig)")


def _detect_resolution():
    """Detect resolution of the target monitor using mss."""
    try:
        import mss
        with mss.MSS() as sct:
            monitors = sct.monitors
            mss_idx = MONITOR_INDEX + 1
            if mss_idx < len(monitors):
                m = monitors[mss_idx]
            elif len(monitors) > 1:
                m = monitors[1]
            else:
                return CAPTURE_WIDTH, CAPTURE_HEIGHT
            return m['width'], m['height']
    except Exception:
        return CAPTURE_WIDTH, CAPTURE_HEIGHT


def _get_monitor_offset():
    """Get the top-left offset of the target monitor for gdigrab."""
    try:
        import mss
        with mss.MSS() as sct:
            monitors = sct.monitors
            mss_idx = MONITOR_INDEX + 1
            if mss_idx < len(monitors):
                m = monitors[mss_idx]
                return m['left'], m['top']
    except Exception:
        pass
    return 0, 0


def main():
    width, height = _detect_resolution()

    print("=" * 60)
    print("  PocketDisplay Server")
    print("=" * 60)
    print(f"  Monitor Index: {MONITOR_INDEX}")
    print(f"  Resolution: {width}x{height} @ {CAPTURE_FPS}fps")
    print(f"  Video Port: {VIDEO_PORT}")
    print(f"  Control Port: {CONTROL_PORT}")
    print("=" * 60)
    print()

    stream_server = None
    touch_receiver = None
    ffmpeg_proc = None
    shutdown_event = threading.Event()
    frame_count = [0]
    start_time = [time.time()]

    def signal_handler(sig, frame):
        print("\nShutting down...")
        shutdown_event.set()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    try:
        print("[1/4] Setting up ADB reverse port forwarding...")
        adb_ok = False
        if check_device():
            if setup_reverse_ports():
                adb_ok = True
            else:
                print("WARNING: ADB port forwarding failed.")

        if not adb_ok:
            print()
            print("  ADB not available — using WiFi mode instead.")
            print("  On your phone app, enter this PC's IP address:")
            _print_local_ips()
            print()
            print("  The server will listen on all interfaces (0.0.0.0).")
            print("  Make sure your phone is on the same WiFi network.")
        print()

        print("[2/4] Starting video stream server with touch input...")
        offset_x, offset_y = _get_monitor_offset()
        touch_receiver = TouchReceiver(host=HOST, port=CONTROL_PORT,
                                       display_width=width, display_height=height,
                                       monitor_index=MONITOR_INDEX,
                                       monitor_offset_x=offset_x,
                                       monitor_offset_y=offset_y)
        stream_server = StreamServer(host=HOST, port=VIDEO_PORT,
                                     on_touch_event=touch_receiver.handle_touch_event)
        stream_server.start()
        print()

        print("[3/4] Touch input processing ready (via video connection)")
        print()

        print("[4/4] Starting FFmpeg capture + encode pipeline...")
        offset_x, offset_y = _get_monitor_offset()
        print(f"  Capture offset: ({offset_x}, {offset_y})")
        print(f"  Resolution: {width}x{height}")
        print(f"  Using FFmpeg at: {FFMPEG_PATH}")

        encode_w, encode_h = width, height
        print(f"  Encode resolution: {encode_w}x{encode_h}")
        print(f"  H.264 profile: {H264_PROFILE} level {H264_LEVEL}")

        cmd = [
            FFMPEG_PATH,
            "-f", "gdigrab",
            "-framerate", str(CAPTURE_FPS),
            "-offset_x", str(offset_x),
            "-offset_y", str(offset_y),
            "-video_size", f"{width}x{height}",
            "-i", "desktop",
            "-c:v", "libx264",
            "-profile:v", H264_PROFILE,
            "-level", H264_LEVEL,
            "-preset", H264_PRESET,
            "-tune", H264_TUNE,
            "-b:v", H264_BITRATE,
            "-maxrate", H264_BITRATE,
            "-bufsize", H264_BITRATE,
            "-g", str(KEYFRAME_INTERVAL),
            "-keyint_min", str(KEYFRAME_INTERVAL),
            "-threads", str(H264_THREADS),
            "-x264-params", f"repeat-headers=1:slices={H264_SLICES}:threads={H264_THREADS}",
            "-pix_fmt", "yuv420p",
            "-f", "h264",
            "-an",
            "-flush_packets", "1",
            "pipe:1"
        ]
        print(f"  Command: {' '.join(cmd[:6])}...")

        ffmpeg_proc = subprocess.Popen(
            cmd,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0
        )

        # Thread to read encoded H.264 output and send to clients
        def read_output():
            try:
                while not shutdown_event.is_set():
                    data = ffmpeg_proc.stdout.read(65536)
                    if not data:
                        break
                    frame_count[0] += 1
                    stream_server.send_data(data)
            except Exception as e:
                if not shutdown_event.is_set():
                    print(f"Output read error: {e}")

        # Thread to read FFmpeg stderr
        def read_stderr():
            try:
                for line in ffmpeg_proc.stderr:
                    line = line.decode("utf-8", errors="replace").strip()
                    if line and not shutdown_event.is_set():
                        if any(k in line.lower() for k in ["error", "warning", "fatal"]):
                            print(f"FFmpeg: {line}")
            except:
                pass

        output_thread = threading.Thread(target=read_output, daemon=True)
        output_thread.start()
        stderr_thread = threading.Thread(target=read_stderr, daemon=True)
        stderr_thread.start()

        start_time[0] = time.time()
        print()
        print("=" * 60)
        print("  Server running! Press Ctrl+C to stop.")
        print("=" * 60)
        print()

        try:
            while not shutdown_event.is_set():
                shutdown_event.wait(timeout=5.0)
                if not shutdown_event.is_set():
                    elapsed = time.time() - start_time[0]
                    fps = frame_count[0] / elapsed if elapsed > 0 else 0
                    chunks = frame_count[0]
                    sent_mb = stream_server.bytes_sent / (1024 * 1024)
                    client = "Yes" if stream_server.has_client else "No"
                    print(
                        f"  [Status] Chunks: {chunks} | "
                        f"Sent: {sent_mb:.1f}MB | "
                        f"Drops: {stream_server._chunks_dropped} | "
                        f"Q: {len(stream_server._send_queue)} | Client: {client}"
                    )
        except KeyboardInterrupt:
            shutdown_event.set()
    finally:
        print("\nStopping components...")
        if ffmpeg_proc:
            try:
                ffmpeg_proc.kill()
                ffmpeg_proc.wait(timeout=3)
            except:
                pass
            print("FFmpeg stopped.")
        if stream_server:
            stream_server.stop()
        print("\nServer stopped. Goodbye!")


if __name__ == "__main__":
    main()
