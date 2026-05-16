"""Touch input receiver - receives touch events from Android and injects Windows mouse events."""

import json
import socket
import threading
import ctypes
from ctypes import wintypes
from config import CONTROL_PORT, HOST, CAPTURE_WIDTH, CAPTURE_HEIGHT, MONITOR_INDEX

# Windows API constants for mouse input injection
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_ABSOLUTE = 0x8000
MOUSEEVENTF_VIRTUALDESK = 0x4000

# Get virtual screen metrics for multi-monitor support
user32 = ctypes.windll.user32
SM_XVIRTUALSCREEN = 76
SM_YVIRTUALSCREEN = 77
SM_CXVIRTUALSCREEN = 78
SM_CYVIRTUALSCREEN = 79


class TouchReceiver:
    """Processes touch events and injects Windows mouse events.
    Can run as a standalone TCP server OR receive events from StreamServer."""

    def __init__(self, host=HOST, port=CONTROL_PORT,
                 display_width=CAPTURE_WIDTH, display_height=CAPTURE_HEIGHT,
                 monitor_index=MONITOR_INDEX,
                 monitor_offset_x=0, monitor_offset_y=0):
        self.host = host
        self.port = port
        self.display_width = display_width
        self.display_height = display_height
        self.monitor_index = monitor_index
        self.monitor_offset_x = monitor_offset_x
        self.monitor_offset_y = monitor_offset_y
        self.server_socket = None
        self.running = False
        self._thread = None
        self._client = None

        # Get virtual screen bounds for coordinate mapping
        self.virtual_left = user32.GetSystemMetrics(SM_XVIRTUALSCREEN)
        self.virtual_top = user32.GetSystemMetrics(SM_YVIRTUALSCREEN)
        self.virtual_width = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN)
        self.virtual_height = user32.GetSystemMetrics(SM_CYVIRTUALSCREEN)

    def handle_touch_event(self, event):
        """Process a touch event dict (called from StreamServer's read loop)."""
        try:
            action = event.get("action", "")
            x = float(event.get("x", 0))
            y = float(event.get("y", 0))

            # Convert normalized coordinates (0-1) to screen pixels on the target monitor
            screen_x = int(x * self.display_width)
            screen_y = int(y * self.display_height)

            # Add the actual monitor offset to get virtual desktop coordinates
            # Then convert to absolute 0-65535 range for mouse_event
            virt_x = screen_x + self.monitor_offset_x - self.virtual_left
            virt_y = screen_y + self.monitor_offset_y - self.virtual_top
            abs_x = int(virt_x * 65535 / self.virtual_width)
            abs_y = int(virt_y * 65535 / self.virtual_height)

            # Clamp values
            abs_x = max(0, min(65535, abs_x))
            abs_y = max(0, min(65535, abs_y))

            if action == "down":
                self._move_mouse(abs_x, abs_y)
                self._mouse_event(MOUSEEVENTF_LEFTDOWN)
            elif action == "move":
                self._move_mouse(abs_x, abs_y)
            elif action == "up":
                self._move_mouse(abs_x, abs_y)
                self._mouse_event(MOUSEEVENTF_LEFTUP)

        except (ValueError, TypeError) as e:
            pass  # Silently ignore malformed events

    def start(self):
        """Start the touch receiver server."""
        self.running = True
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.settimeout(1.0)
        self.server_socket.bind((self.host, self.port))
        self.server_socket.listen(5)

        self._thread = threading.Thread(target=self._accept_loop, daemon=True)
        self._thread.start()
        print(f"Touch receiver listening on {self.host}:{self.port}")

    def _accept_loop(self):
        """Accept client connections."""
        while self.running:
            try:
                client, addr = self.server_socket.accept()
                print(f"Touch client connected from {addr}")
                self._client = client
                self._handle_client(client)
            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    print(f"Touch accept error: {e}")

    def _handle_client(self, client):
        """Handle incoming touch data from a client."""
        buffer = ""
        try:
            while self.running:
                data = client.recv(4096)
                if not data:
                    break
                buffer += data.decode("utf-8", errors="replace")

                # Process complete JSON messages (newline-delimited)
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    line = line.strip()
                    if line:
                        self._process_touch_event(line)
        except Exception as e:
            if self.running:
                print(f"Touch client error: {e}")
        finally:
            client.close()
            self._client = None
            print("Touch client disconnected")

    def _process_touch_event(self, json_str):
        """Process a single touch event JSON message."""
        try:
            event = json.loads(json_str)
            action = event.get("action", "")
            x = float(event.get("x", 0))
            y = float(event.get("y", 0))

            # Convert normalized coordinates (0-1) to screen pixels
            # Map to the virtual display area
            screen_x = int(x * self.display_width)
            screen_y = int(y * self.display_height)

            # Convert to absolute coordinates for the virtual desktop (0-65535 range)
            abs_x = int((screen_x + self._get_monitor_offset_x()) * 65535 / self.virtual_width)
            abs_y = int((screen_y + self._get_monitor_offset_y()) * 65535 / self.virtual_height)

            # Clamp values
            abs_x = max(0, min(65535, abs_x))
            abs_y = max(0, min(65535, abs_y))

            if action == "down":
                self._move_mouse(abs_x, abs_y)
                self._mouse_event(MOUSEEVENTF_LEFTDOWN)
            elif action == "move":
                self._move_mouse(abs_x, abs_y)
            elif action == "up":
                self._move_mouse(abs_x, abs_y)
                self._mouse_event(MOUSEEVENTF_LEFTUP)

        except (json.JSONDecodeError, ValueError) as e:
            pass  # Silently ignore malformed events

    def _get_monitor_offset_x(self):
        """Get the X offset of the target monitor in virtual screen space."""
        return self.monitor_offset_x

    def _get_monitor_offset_y(self):
        """Get the Y offset of the target monitor."""
        return self.monitor_offset_y

    def _move_mouse(self, abs_x, abs_y):
        """Move mouse to absolute position."""
        flags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK
        user32.mouse_event(flags, abs_x, abs_y, 0, 0)

    def _mouse_event(self, flags):
        """Send a mouse button event."""
        user32.mouse_event(flags, 0, 0, 0, 0)

    def stop(self):
        """Stop the touch receiver."""
        self.running = False
        if self._client:
            try:
                self._client.close()
            except:
                pass
        if self.server_socket:
            try:
                self.server_socket.close()
            except:
                pass
        if self._thread:
            self._thread.join(timeout=2)
        print("Touch receiver stopped")
