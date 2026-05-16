"""TCP video stream server - sends H.264 encoded video to connected Android client."""

import socket
import threading
import time
import collections
from config import VIDEO_PORT, HOST


class StreamServer:
    """TCP server that streams H.264 data to a single connected client.
    Uses a send queue to decouple the encoder from network I/O.
    Also reads upstream touch events from the client."""

    def __init__(self, host=HOST, port=VIDEO_PORT, on_touch_event=None):
        self.host = host
        self.port = port
        self.on_touch_event = on_touch_event
        self.server_socket = None
        self.client_socket = None
        self.running = False
        self._accept_thread = None
        self._send_thread = None
        self._read_thread = None
        self._lock = threading.Lock()
        self._bytes_sent = 0
        self._start_time = 0
        self._header_cache = bytearray()
        self._parse_buf = bytearray()
        # Send queue: bounded deque drops oldest when full
        self._send_queue = collections.deque(maxlen=300)  # ~10 seconds at 30fps
        self._send_event = threading.Event()
        self._chunks_queued = 0
        self._chunks_dropped = 0

    @property
    def bytes_sent(self):
        return self._bytes_sent

    @property
    def has_client(self):
        return self.client_socket is not None

    def start(self):
        self.running = True
        self._bytes_sent = 0
        self._start_time = time.time()

        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.settimeout(1.0)
        self.server_socket.bind((self.host, self.port))
        self.server_socket.listen(1)

        self._accept_thread = threading.Thread(target=self._accept_loop, daemon=True)
        self._accept_thread.start()
        self._send_thread = threading.Thread(target=self._send_loop, daemon=True)
        self._send_thread.start()
        self._read_thread = threading.Thread(target=self._read_loop, daemon=True)
        self._read_thread.start()
        print(f"Video stream server listening on {self.host}:{self.port}")

    def _accept_loop(self):
        while self.running:
            try:
                client, addr = self.server_socket.accept()
                print(f"Video client connected from {addr}")
                with self._lock:
                    if self.client_socket:
                        try:
                            self.client_socket.close()
                        except:
                            pass
                    self.client_socket = client
                    self.client_socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    self.client_socket.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 524288)
                    self.client_socket.settimeout(2.0)  # 2s send timeout to prevent blocking
                    # Clear old queue data and send cached headers
                    self._send_queue.clear()
                    if self._header_cache:
                        self._send_queue.append(bytes(self._header_cache))
                        print(f"  Queued {len(self._header_cache)} bytes of cached SPS/PPS/IDR")
                    self._send_event.set()
            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    print(f"Accept error: {e}")

    def _send_loop(self):
        """Dedicated sender thread — sends queued data to client."""
        while self.running:
            self._send_event.wait(timeout=0.1)
            while self._send_queue:
                data = self._send_queue.popleft()
                with self._lock:
                    if self.client_socket is None:
                        self._send_queue.clear()
                        break
                    try:
                        self.client_socket.sendall(data)
                        self._bytes_sent += len(data)
                    except socket.timeout:
                        # Send timed out — skip this chunk rather than blocking
                        self._chunks_dropped += 1
                    except (BrokenPipeError, ConnectionResetError, OSError) as e:
                        print(f"Client disconnected: {e}")
                        try:
                            self.client_socket.close()
                        except:
                            pass
                        self.client_socket = None
                        self._send_queue.clear()
                        break
            self._send_event.clear()

    def _read_loop(self):
        """Read upstream data (touch events) from the connected client."""
        import json
        import select
        buffer = ""
        while self.running:
            with self._lock:
                sock = self.client_socket
            if sock is None:
                time.sleep(0.1)
                continue
            try:
                # Use select to check if data is available (non-blocking check)
                ready, _, _ = select.select([sock], [], [], 0.1)
                if not ready:
                    continue
                data = sock.recv(4096)
                if not data:
                    time.sleep(0.1)
                    continue
                buffer += data.decode("utf-8", errors="replace")
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    line = line.strip()
                    if line and self.on_touch_event:
                        try:
                            event = json.loads(line)
                            self.on_touch_event(event)
                        except json.JSONDecodeError:
                            pass
            except Exception:
                buffer = ""
                time.sleep(0.1)

    def _cache_headers(self, data):
        """Parse H.264 data to cache SPS+PPS+IDR for new client connections."""
        self._parse_buf.extend(data)
        buf = self._parse_buf

        positions = []
        i = 0
        while i < len(buf) - 3:
            if buf[i] == 0 and buf[i+1] == 0:
                if buf[i+2] == 1:
                    positions.append(i)
                    i += 3
                elif i < len(buf) - 4 and buf[i+2] == 0 and buf[i+3] == 1:
                    positions.append(i)
                    i += 4
                else:
                    i += 1
            else:
                i += 1

        if len(positions) < 2:
            return

        sps_data = None
        pps_data = None
        idr_data = None

        for j in range(len(positions) - 1):
            nal = buf[positions[j]:positions[j+1]]
            if len(nal) < 5:
                continue
            if nal[2] == 1:
                nal_type = nal[3] & 0x1F
            elif nal[3] == 1:
                nal_type = nal[4] & 0x1F
            else:
                continue

            if nal_type == 7:
                sps_data = bytes(nal)
            elif nal_type == 8:
                pps_data = bytes(nal)
            elif nal_type == 5:
                idr_data = bytes(nal)
                if sps_data and pps_data:
                    self._header_cache = bytearray(sps_data + pps_data + idr_data)
                    break

        self._parse_buf = bytearray(buf[positions[-1]:])

    def send_data(self, data):
        """Queue encoded H.264 data for sending to the connected client."""
        if not self._header_cache:
            self._cache_headers(data)

        # Track drops: if queue is full, the oldest item will be silently dropped
        was_full = len(self._send_queue) >= self._send_queue.maxlen
        self._send_queue.append(data)
        self._chunks_queued += 1
        if was_full:
            self._chunks_dropped += 1
        self._send_event.set()
        return self.client_socket is not None

    def stop(self):
        self.running = False
        self._send_event.set()
        with self._lock:
            if self.client_socket:
                try:
                    self.client_socket.close()
                except:
                    pass
                self.client_socket = None
        if self.server_socket:
            try:
                self.server_socket.close()
            except:
                pass
        if self._accept_thread:
            self._accept_thread.join(timeout=2)
        if self._send_thread:
            self._send_thread.join(timeout=2)
        elapsed = time.time() - self._start_time
        mb_sent = self._bytes_sent / (1024 * 1024)
        print(f"Stream server stopped. Sent {mb_sent:.1f} MB in {elapsed:.1f}s")
