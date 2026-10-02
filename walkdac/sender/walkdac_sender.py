#!/usr/bin/env python3
"""WalkDAC sender: stream the Mac's system audio to a NW-A105 over Wi-Fi.

Audio path on the Mac:  apps -> BlackHole 2ch (system output) -> this script -> TCP -> A105.
Control path:           A105 -> TCP JSON lines -> media-control (play/pause/next/prev/seek)
Now playing:            media-control stream -> JSON lines -> A105 (title, artist, artwork)
Discovery:              UDP broadcast beacon every 2 s on port 7702

Run:  python3 walkdac_sender.py --device "BlackHole 2ch"
Test: python3 walkdac_sender.py --source sine
"""
from __future__ import annotations

import argparse
import json
import math
import os
import queue
import re
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
from typing import Callable, Dict, List, Optional, Tuple

import numpy as np

PROTOCOL_VERSION = 1
MAGIC = b"WD"
CODEC_S16, CODEC_S24, CODEC_F32 = 0, 1, 2
CODEC_NAMES = {CODEC_S16: "pcm_s16", CODEC_S24: "pcm_s24", CODEC_F32: "pcm_f32"}
CODEC_BITS = {CODEC_S16: 16, CODEC_S24: 24, CODEC_F32: 32}
CODEC_BYTES = {CODEC_S16: 2, CODEC_S24: 3, CODEC_F32: 4}
# magic(2) version(1) codec(1) seq(4) capture_ns(8) rate(4) frames(2) bits(1) channels(1) payload_len(4) = 28 bytes
HEADER = struct.Struct("<2sBBIQIHBBI")
HEADER_SIZE = HEADER.size
CHANNELS = 2

DEFAULT_AUDIO_PORT = 7700
DEFAULT_CONTROL_PORT = 7701
DEFAULT_BEACON_PORT = 7702

MEDIA_CONTROL_CMDS = {
    "play": ["play"],
    "pause": ["pause"],
    "toggle": ["toggle-play-pause"],
    "next": ["next-track"],
    "prev": ["previous-track"],
}


def now_ns() -> int:
    """Sender clock used for capture timestamps and time sync (monotonic)."""
    return time.monotonic_ns()


def log(msg: str) -> None:
    sys.stderr.write(time.strftime("%H:%M:%S ") + msg + "\n")
    sys.stderr.flush()


# ----------------------------------------------------------------------------- encoding

def encode_block(block: np.ndarray, codec: int) -> bytes:
    """block: float32 array (frames, channels) in [-1, 1]. Returns packed little-endian PCM."""
    x = np.clip(block, -1.0, 1.0)
    if codec == CODEC_F32:
        return x.astype("<f4").tobytes()
    if codec == CODEC_S16:
        return np.round(x * 32767.0).astype("<i2").tobytes()
    if codec == CODEC_S24:
        i32 = np.round(x * 8388607.0).astype("<i4")
        return i32.view(np.uint8).reshape(-1, 4)[:, :3].tobytes()
    raise ValueError("unknown codec %r" % codec)


def pack_frame(seq: int, capture_ns: int, rate: int, frames: int, codec: int, payload: bytes) -> bytes:
    return HEADER.pack(MAGIC, PROTOCOL_VERSION, codec, seq & 0xFFFFFFFF, capture_ns, rate, frames,
                       CODEC_BITS[codec], CHANNELS, len(payload)) + payload


def unpack_header(buf: bytes) -> dict:
    magic, version, codec, seq, capture_ns, rate, frames, bits, channels, payload_len = HEADER.unpack(buf[:HEADER_SIZE])
    if magic != MAGIC:
        raise ValueError("bad magic %r" % magic)
    return dict(version=version, codec=codec, seq=seq, capture_ns=capture_ns, rate=rate, frames=frames,
                bits=bits, channels=channels, payload_len=payload_len)


# ----------------------------------------------------------------------------- sources

class SineSource:
    """Test source: 1 kHz sine, paced to real time (or unpaced with paced=False)."""

    def __init__(self, rate: int, block_frames: int, freq: float = 1000.0, paced: bool = True):
        self.rate = rate
        self.block_frames = block_frames
        self.freq = freq
        self.paced = paced
        self._phase = 0
        self._stop = threading.Event()

    def start(self, on_block: Callable[[np.ndarray, int], None]) -> None:
        def run():
            t0 = now_ns()
            n = 0
            while not self._stop.is_set():
                idx = np.arange(self._phase, self._phase + self.block_frames)
                mono = 0.5 * np.sin(2 * np.pi * self.freq * idx / self.rate)
                self._phase += self.block_frames
                block = np.stack([mono, mono], axis=1).astype(np.float32)
                n += 1
                target = t0 + int(n * self.block_frames * 1e9 / self.rate)
                if self.paced:
                    delay = (target - now_ns()) / 1e9
                    if delay > 0:
                        time.sleep(delay)
                on_block(block, now_ns())
        threading.Thread(target=run, name="sine", daemon=True).start()

    def stop(self) -> None:
        self._stop.set()


class SoundDeviceSource:
    """Captures from a CoreAudio input device (BlackHole) with the sounddevice package."""

    def __init__(self, device: str, rate: int, block_frames: int):
        import sounddevice as sd  # imported lazily so tests run without PortAudio
        self.sd = sd
        self.device = device
        self.rate = rate
        self.block_frames = block_frames
        self._stream = None

    @staticmethod
    def device_default_rate(device: str) -> int:
        import sounddevice as sd
        info = sd.query_devices(device)
        return int(round(info["default_samplerate"]))

    def start(self, on_block: Callable[[np.ndarray, int], None]) -> None:
        def callback(indata, frames, time_info, status):
            if status:
                log("capture status: %s" % status)
            on_block(np.array(indata, dtype=np.float32, copy=True), now_ns())

        self._stream = self.sd.InputStream(device=self.device, samplerate=self.rate, channels=CHANNELS,
                                           dtype="float32", blocksize=self.block_frames, callback=callback)
        self._stream.start()

    def stop(self) -> None:
        if self._stream is not None:
            self._stream.stop()
            self._stream.close()
            self._stream = None


# ----------------------------------------------------------------------------- audio server

class AudioClient:
    def __init__(self, sock: socket.socket, addr, max_queue: int):
        self.sock = sock
        self.addr = addr
        self.queue: "queue.Queue[Optional[bytes]]" = queue.Queue(maxsize=max_queue)
        self.dropped = 0
        self.alive = True

    def offer(self, frame: bytes) -> None:
        try:
            self.queue.put_nowait(frame)
        except queue.Full:
            # Drop the oldest frame so the receiver sees a seq gap (and we never buffer unbounded).
            try:
                self.queue.get_nowait()
            except queue.Empty:
                pass
            self.dropped += 1
            try:
                self.queue.put_nowait(frame)
            except queue.Full:
                pass


class AudioServer:
    def __init__(self, port: int, max_queue_blocks: int, sndbuf_bytes: int = 262144):
        self.port = port
        self.max_queue_blocks = max_queue_blocks
        self.sndbuf_bytes = sndbuf_bytes
        self.clients: List[AudioClient] = []
        self.lock = threading.Lock()
        self._stop = threading.Event()
        self.seq = 0
        self.frames_sent = 0

    def start(self) -> None:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("", self.port))
        self.port = srv.getsockname()[1]
        srv.listen(4)
        srv.settimeout(0.5)
        self._srv = srv
        threading.Thread(target=self._accept_loop, name="audio-accept", daemon=True).start()

    def _accept_loop(self) -> None:
        while not self._stop.is_set():
            try:
                sock, addr = self._srv.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            # Bound the kernel send buffer to ~500 ms of audio so a Wi-Fi stall cannot pile up
            # seconds of stale audio in the socket; our own queue then drops the oldest blocks.
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, max(65536, self.sndbuf_bytes))
            except OSError:
                pass
            client = AudioClient(sock, addr, self.max_queue_blocks)
            with self.lock:
                self.clients.append(client)
            log("audio client connected: %s:%d" % addr)
            threading.Thread(target=self._client_loop, args=(client,), name="audio-%s" % addr[0], daemon=True).start()

    def _client_loop(self, client: AudioClient) -> None:
        try:
            while client.alive and not self._stop.is_set():
                try:
                    frame = client.queue.get(timeout=0.5)
                except queue.Empty:
                    continue
                if frame is None:
                    break
                client.sock.sendall(frame)
        except OSError as e:
            log("audio client %s:%d gone: %s" % (client.addr[0], client.addr[1], e))
        finally:
            client.alive = False
            with self.lock:
                if client in self.clients:
                    self.clients.remove(client)
            try:
                client.sock.close()
            except OSError:
                pass
            log("audio client disconnected: %s:%d (dropped %d blocks)" % (client.addr[0], client.addr[1], client.dropped))

    def broadcast(self, capture_ns: int, rate: int, frames: int, codec: int, payload: bytes) -> int:
        with self.lock:
            clients = list(self.clients)
        seq = self.seq
        self.seq = (self.seq + 1) & 0xFFFFFFFF
        if not clients:
            return seq
        frame = pack_frame(seq, capture_ns, rate, frames, codec, payload)
        for c in clients:
            c.offer(frame)
        self.frames_sent += 1
        return seq

    def client_count(self) -> int:
        with self.lock:
            return len(self.clients)

    def stop(self) -> None:
        self._stop.set()
        try:
            self._srv.close()
        except OSError:
            pass


# ----------------------------------------------------------------------------- media-control bridge

class NowPlaying:
    """Keeps the merged now-playing state from `media-control stream --micros` and runs transport commands."""

    KEYS = ("bundleIdentifier", "playing", "title", "artist", "album", "durationMicros", "elapsedTimeMicros",
            "timestampEpochMicros", "playbackRate", "artworkMimeType")
    # Keys whose presence means the timeline anchor (elapsed time + the instant it was valid) moved.
    POS_KEYS = ("elapsedTimeMicros", "timestampEpochMicros", "playing", "playbackRate")

    def __init__(self, enabled: bool, on_change: Callable[[dict, bool], None]):
        self.binary = shutil.which("media-control") if enabled else None
        self.on_change = on_change
        self.state: dict = {}
        self.artwork_b64: Optional[str] = None
        self.artwork_mime: Optional[str] = None
        self.anchor_ns = 0  # sender monotonic ns at which state["elapsedTimeMicros"] was valid
        self.lock = threading.Lock()
        self._proc: Optional[subprocess.Popen] = None
        self._stop = threading.Event()
        self.available = self.binary is not None

    def start(self) -> None:
        if not self.binary:
            log("media-control not found: now playing and transport control are off (brew install media-control)")
            return
        try:
            r = subprocess.run([self.binary, "test"], capture_output=True, text=True, timeout=20)
            ok = r.returncode == 0
            err = (r.stderr or r.stdout or "").strip()[:300] or "exit %d" % r.returncode
        except (OSError, subprocess.TimeoutExpired) as e:
            ok, err = False, str(e)
        if not ok:
            log("media-control self-test failed (%s): now playing + transport control are OFF. "
                "Try: brew upgrade media-control; media-control get" % err)
            self.available = False
            return
        threading.Thread(target=self._stream_loop, name="media-control", daemon=True).start()

    def _stream_loop(self) -> None:
        backoff = 3.0
        while not self._stop.is_set():
            started = time.monotonic()
            try:
                # stderr is inherited so the adapter's own error text reaches the terminal
                self._proc = subprocess.Popen([self.binary, "stream", "--micros"], stdout=subprocess.PIPE,
                                              stderr=None, text=True, bufsize=1)
            except OSError as e:
                log("media-control stream failed: %s" % e)
                self._stop.wait(5)
                continue
            assert self._proc.stdout is not None
            for line in self._proc.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                    if isinstance(msg, dict):
                        self._apply(msg)
                except Exception as e:  # noqa: BLE001 - one bad line must not kill the thread
                    log("media-control: ignored line (%s)" % e)
            self._proc.wait()
            if self._stop.is_set():
                break
            backoff = 3.0 if time.monotonic() - started > 30 else min(backoff * 2, 60.0)
            log("media-control stream exited (code %s); restarting in %.0f s" % (self._proc.returncode, backoff))
            self._stop.wait(backoff)

    def _anchor_from(self, payload: dict) -> int:
        """Sender monotonic ns at which the payload's elapsed time was valid."""
        ts_us = payload.get("timestampEpochMicros", self.state.get("timestampEpochMicros"))
        now_mono = now_ns()
        if isinstance(ts_us, (int, float)) and ts_us > 0:
            age_ns = time.time_ns() - int(ts_us) * 1000
            if -5_000_000_000 < age_ns < 3_600_000_000_000:
                return now_mono - age_ns
        return now_mono

    def _apply(self, msg: dict) -> None:
        if msg.get("type") != "data":
            return
        payload = msg.get("payload")
        diff = bool(msg.get("diff", False))
        if payload is None or (not diff and not payload):
            # The adapter sends diff=false with {} when no player reports media.
            with self.lock:
                self.state = {}
                self.artwork_b64 = None
                self.artwork_mime = None
                self.anchor_ns = now_ns()
                snapshot = self.message(include_artwork=False)
            self.on_change(snapshot, True)
            return
        track_changed = False
        artwork_changed = False
        with self.lock:
            if not diff:
                old_track = self._track_key()
                self.state = {k: payload.get(k) for k in self.KEYS if payload.get(k) is not None}
                # A full payload is the complete state: no artwork key means no artwork (yet).
                self.artwork_b64 = payload.get("artworkData")
                self.artwork_mime = payload.get("artworkMimeType")
                artwork_changed = True
                track_changed = old_track != self._track_key()
                self.anchor_ns = self._anchor_from(payload)
            else:
                for k, v in payload.items():
                    if k == "artworkData":
                        self.artwork_b64 = v
                        artwork_changed = True
                        continue
                    if k not in self.KEYS:
                        continue
                    if v is None:
                        self.state.pop(k, None)
                    else:
                        self.state[k] = v
                    if k in ("title", "artist", "album", "bundleIdentifier"):
                        track_changed = True
                if "artworkMimeType" in payload:
                    self.artwork_mime = payload.get("artworkMimeType")
                if any(k in payload for k in self.POS_KEYS):
                    self.anchor_ns = self._anchor_from(payload)
            snapshot = self.message(include_artwork=artwork_changed)
        self.on_change(snapshot, track_changed)

    def _track_key(self):
        s = self.state
        return (s.get("bundleIdentifier"), s.get("title"), s.get("artist"), s.get("album"))

    def message(self, include_artwork: bool) -> dict:
        """Build a now_playing message. Caller must hold self.lock."""
        s = self.state
        playing = bool(s.get("playing", False))
        msg = {
            "t": "now_playing",
            "app": s.get("bundleIdentifier"),
            "playing": playing,
            "title": s.get("title"),
            "artist": s.get("artist"),
            "album": s.get("album"),
            "duration_us": int(s["durationMicros"]) if s.get("durationMicros") is not None else None,
            "elapsed_us": int(s["elapsedTimeMicros"]) if s.get("elapsedTimeMicros") is not None else None,
            "rate": float(s.get("playbackRate", 1.0 if playing else 0.0)) if playing else 0.0,
            "at_mac_ns": self.anchor_ns,
        }
        if include_artwork:
            msg["artwork_b64"] = self.artwork_b64
            msg["artwork_mime"] = (self.artwork_mime or "image/jpeg") if self.artwork_b64 else None
        return msg

    def snapshot(self) -> dict:
        with self.lock:
            return self.message(include_artwork=True)

    def command(self, op: str, pos_us: Optional[int] = None) -> Tuple[bool, str]:
        if not self.available:
            return False, "media-control not installed or not working on this macOS"
        if op == "seek":
            if pos_us is None:
                return False, "seek needs pos_us"
            args = [self.binary, "seek", "%.3f" % (pos_us / 1e6)]
        elif op in MEDIA_CONTROL_CMDS:
            args = [self.binary] + MEDIA_CONTROL_CMDS[op]
        else:
            return False, "unknown op %r" % op
        try:
            r = subprocess.run(args, capture_output=True, text=True, timeout=5)
        except (OSError, subprocess.TimeoutExpired) as e:
            return False, str(e)
        if r.returncode != 0:
            return False, (r.stderr or r.stdout or "exit %d" % r.returncode).strip()[:200]
        return True, ""

    def stop(self) -> None:
        self._stop.set()
        if self._proc and self._proc.poll() is None:
            self._proc.terminate()


# ----------------------------------------------------------------------------- control server

class ControlConn:
    """One control client. Other threads only enqueue; a dedicated writer thread owns sendall(),
    so a big artwork line can never be interleaved with a time_ack, and a dead peer cannot block
    the media-control or capture threads."""

    def __init__(self, sock: socket.socket, addr, on_dead: Callable[["ControlConn"], None]):
        self.sock = sock
        self.addr = addr
        self.key = "%s:%d" % addr
        self.on_dead = on_dead
        self.q: "queue.Queue[Optional[bytes]]" = queue.Queue(maxsize=64)
        self.closed = False
        threading.Thread(target=self._writer, name="control-tx-%s" % addr[0], daemon=True).start()

    def send(self, msg: dict) -> bool:
        if self.closed:
            return False
        data = (json.dumps(msg, separators=(",", ":")) + "\n").encode("utf-8")
        try:
            self.q.put_nowait(data)
            return True
        except queue.Full:
            log("control client %s is not reading (64 messages queued): dropping it" % self.key)
            self.close()
            return False

    def _writer(self) -> None:
        try:
            while True:
                data = self.q.get()
                if data is None:
                    return
                self.sock.sendall(data)
        except OSError:
            pass
        finally:
            self.close()

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        try:
            self.q.put_nowait(None)
        except queue.Full:
            pass
        try:
            self.sock.shutdown(socket.SHUT_RDWR)  # also wakes the reader's readline()
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass
        self.on_dead(self)


def _configure_control_socket(sock: socket.socket) -> None:
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    if sys.platform == "darwin":
        # Drop a peer that stops ACKing for 10 s (A105 left Wi-Fi without FIN) instead of XNU's minutes-long backoff.
        TCP_RXT_CONNDROPTIME = 0x80
        TCP_KEEPALIVE = 0x10
        for opt, val in ((TCP_RXT_CONNDROPTIME, 10), (TCP_KEEPALIVE, 10)):
            try:
                sock.setsockopt(socket.IPPROTO_TCP, opt, val)
            except OSError:
                pass
    else:
        for name, val in (("TCP_KEEPIDLE", 10), ("TCP_KEEPINTVL", 5), ("TCP_KEEPCNT", 3)):
            if hasattr(socket, name):
                try:
                    sock.setsockopt(socket.IPPROTO_TCP, getattr(socket, name), val)
                except OSError:
                    pass


class ControlServer:
    def __init__(self, port: int, sender: "Sender"):
        self.port = port
        self.sender = sender
        self.clients: List[ControlConn] = []
        self.lock = threading.Lock()
        self._stop = threading.Event()
        self.last_stats: Dict[str, dict] = {}

    def start(self) -> None:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("", self.port))
        self.port = srv.getsockname()[1]
        srv.listen(4)
        srv.settimeout(0.5)
        self._srv = srv
        threading.Thread(target=self._accept_loop, name="control-accept", daemon=True).start()

    def _accept_loop(self) -> None:
        while not self._stop.is_set():
            try:
                sock, addr = self._srv.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            _configure_control_socket(sock)
            conn = ControlConn(sock, addr, self._forget)
            with self.lock:
                self.clients.append(conn)
            log("control client connected: %s" % conn.key)
            threading.Thread(target=self._client_loop, args=(conn,), name="control-%s" % addr[0], daemon=True).start()

    def _forget(self, conn: ControlConn) -> None:
        with self.lock:
            if conn in self.clients:
                self.clients.remove(conn)
        self.last_stats.pop(conn.key, None)

    def broadcast(self, msg: dict) -> None:
        with self.lock:
            clients = list(self.clients)
        for c in clients:
            c.send(msg)

    def _client_loop(self, conn: ControlConn) -> None:
        f = conn.sock.makefile("r", encoding="utf-8", errors="replace")
        try:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except ValueError:
                    continue
                if isinstance(msg, dict):
                    self._handle(conn, msg)
        except (OSError, ValueError):
            pass
        finally:
            conn.close()
            log("control client disconnected: %s" % conn.key)

    def _handle(self, conn: ControlConn, msg: dict) -> None:
        t = msg.get("t")
        if t == "time":
            conn.send({"t": "time_ack", "id": msg.get("id"), "a105_ns": msg.get("a105_ns"), "mac_ns": now_ns()})
        elif t == "hello":
            log("receiver hello from %s: %s" % (conn.key, msg.get("name", "?")))
            conn.send(self.sender.source_message())
            conn.send(self.sender.now_playing.snapshot())
        elif t == "cmd":
            op = msg.get("op")
            pos_us = msg.get("pos_us")
            if op == "volume":
                conn.send({"t": "cmd_ack", "op": op, "ok": False, "error": "volume is handled on the A105 in this version"})
                return
            self.sender.last_cmd_ns = now_ns()

            def run():
                ok, err = self.sender.now_playing.command(op, pos_us)
                log("cmd %s from %s: %s%s" % (op, conn.key, "ok" if ok else "failed", "" if ok else " (" + err + ")"))
                conn.send({"t": "cmd_ack", "op": op, "ok": ok, "error": err})
                if ok and op in ("pause", "next", "prev", "seek"):
                    # The receiver already flushed locally; make sure audio captured before the
                    # app reacted does not get replayed on the A105.
                    self.sender.schedule_flush(delay_s=0.15)
            threading.Thread(target=run, daemon=True).start()
        elif t == "stats":
            self.last_stats[conn.key] = msg
            if self.sender.verbose:
                log("stats %s: buffer %sms underruns %s lost %s drift %sppm rssi %s" % (
                    conn.key, msg.get("buffer_ms"), msg.get("underruns"), msg.get("lost"), msg.get("drift_ppm"), msg.get("rssi")))

    def stop(self) -> None:
        self._stop.set()
        try:
            self._srv.close()
        except OSError:
            pass
        with self.lock:
            clients = list(self.clients)
        for c in clients:
            c.close()


# ----------------------------------------------------------------------------- beacon

def broadcast_targets() -> List[str]:
    """Directed broadcast of every up IPv4 interface (from ifconfig) plus the limited broadcast.
    255.255.255.255 alone only leaves through the default-route interface (wrong NIC with a VPN,
    Internet Sharing or Ethernet+Wi-Fi), and some Wi-Fi firmware drops it."""
    targets = {"255.255.255.255"}
    try:
        out = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=2).stdout
        targets.update(re.findall(r"\binet \d+\.\d+\.\d+\.\d+ netmask \S+ broadcast (\d+\.\d+\.\d+\.\d+)", out))
        # Linux net-tools format: "inet 192.168.1.5  netmask 255.255.255.0  broadcast 192.168.1.255"
        targets.update(re.findall(r"\binet \d+\.\d+\.\d+\.\d+\s+netmask \S+\s+broadcast (\d+\.\d+\.\d+\.\d+)", out))
    except (OSError, subprocess.TimeoutExpired):
        pass
    return sorted(targets)


class Beacon:
    def __init__(self, sender: "Sender", port: int, interval_s: float = 2.0):
        self.sender = sender
        self.port = port
        self.interval_s = interval_s
        self._stop = threading.Event()

    def start(self) -> None:
        threading.Thread(target=self._loop, name="beacon", daemon=True).start()

    def _loop(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        targets: List[str] = []
        refreshed = -1e9
        while not self._stop.is_set():
            if time.monotonic() - refreshed > 10:  # interfaces change (VPN up/down, hotspot)
                targets, refreshed = broadcast_targets(), time.monotonic()
            msg = {"t": "beacon", "name": self.sender.name, "audio_port": self.sender.audio_port,
                   "control_port": self.sender.control_port, "ver": PROTOCOL_VERSION,
                   "rate": self.sender.rate, "codec": CODEC_NAMES[self.sender.codec]}
            data = json.dumps(msg, separators=(",", ":")).encode("utf-8")
            for t in targets:
                try:
                    sock.sendto(data, (t, self.port))
                except OSError as e:
                    if self.sender.verbose:
                        log("beacon to %s failed: %s" % (t, e))
            self._stop.wait(self.interval_s)
        sock.close()

    def stop(self) -> None:
        self._stop.set()


# ----------------------------------------------------------------------------- sender

class Sender:
    def __init__(self, args):
        self.args = args
        self.name = args.name or socket.gethostname().split(".")[0]
        self.codec = {"s16": CODEC_S16, "s24": CODEC_S24, "f32": CODEC_F32}[args.format]
        self.audio_port = args.port
        self.control_port = args.control_port
        self.verbose = args.verbose
        self.block_ms = args.block_ms
        self.flush_on_track_change = not args.no_flush_on_change
        if args.source == "sine":
            self.rate = args.rate or 48000
            self.device_name = "sine 1 kHz (test)"
        else:
            self.rate = args.rate or SoundDeviceSource.device_default_rate(args.device)
            self.device_name = args.device
        self.block_frames = max(1, int(round(self.rate * self.block_ms / 1000.0)))
        if self.block_frames > 65535:
            raise ValueError("--block-ms %d at %d Hz = %d frames; the header stores frames as u16, use --block-ms <= %d"
                             % (self.block_ms, self.rate, self.block_frames, 65535 * 1000 // self.rate))
        max_queue_blocks = max(10, int(args.max_queue_ms / self.block_ms))
        bytes_per_sec = self.rate * CHANNELS * CODEC_BYTES[self.codec]
        self.audio = AudioServer(self.audio_port, max_queue_blocks, sndbuf_bytes=bytes_per_sec // 2)
        self.control = ControlServer(self.control_port, self)
        self.now_playing = NowPlaying(not args.no_media_control, self._on_now_playing)
        self.beacon = None if args.no_beacon else Beacon(self, args.beacon_port)
        self.source = None
        self._flush_timer: Optional[threading.Timer] = None
        self._flush_lock = threading.Lock()
        self.last_seq = 0
        self.blocks_captured = 0
        self.silent_blocks = 0
        self.last_cmd_ns = 0

    # --- capture callback (audio thread) ---
    def _on_block(self, block: np.ndarray, capture_ns: int) -> None:
        self.blocks_captured += 1
        if block.ndim == 1:
            block = np.stack([block, block], axis=1)
        elif block.shape[1] == 1:
            block = np.repeat(block, 2, axis=1)
        elif block.shape[1] > 2:
            block = block[:, :2]
        if not block.any():
            self.silent_blocks += 1
        payload = encode_block(block, self.codec)
        self.last_seq = self.audio.broadcast(capture_ns, self.rate, block.shape[0], self.codec, payload)

    def source_message(self) -> dict:
        return {"t": "source", "name": self.name, "device": self.device_name, "capture": self.args.source,
                "rate": self.rate, "codec": CODEC_NAMES[self.codec], "bits": CODEC_BITS[self.codec],
                "channels": CHANNELS, "block_ms": self.block_ms, "ver": PROTOCOL_VERSION,
                "media_control": self.now_playing.available}

    def _on_now_playing(self, msg: dict, track_changed: bool) -> None:
        if msg:
            self.control.broadcast(msg)
            if self.verbose:
                log("now playing: %s - %s (%s)" % (msg.get("artist"), msg.get("title"), "playing" if msg.get("playing") else "paused"))
        # Only cut the A105's buffer when the change was most likely caused by a button press there.
        # A natural track transition on the Mac just plays out with the normal stream latency.
        recent_cmd = now_ns() - self.last_cmd_ns < 3_000_000_000
        if track_changed and self.flush_on_track_change and recent_cmd:
            self.schedule_flush(delay_s=0.0)

    def schedule_flush(self, delay_s: float) -> None:
        """Tell receivers to drop audio captured before now (+ delay), e.g. after a track change."""
        def fire():
            self.control.broadcast({"t": "flush", "from_seq": self.last_seq + 1})
        with self._flush_lock:
            if self._flush_timer is not None:
                self._flush_timer.cancel()
            if delay_s <= 0:
                fire()
                self._flush_timer = None
            else:
                self._flush_timer = threading.Timer(delay_s, fire)
                self._flush_timer.daemon = True
                self._flush_timer.start()

    def start(self) -> None:
        self.audio.start()
        self.control.start()
        self.audio_port = self.audio.port
        self.control_port = self.control.port
        self.now_playing.start()
        if self.beacon:
            self.beacon.start()
        if self.args.source == "sine":
            self.source = SineSource(self.rate, self.block_frames, paced=not self.args.unpaced)
        else:
            self.source = SoundDeviceSource(self.args.device, self.rate, self.block_frames)
        self.source.start(self._on_block)
        log("WalkDAC sender '%s': %s @ %d Hz, %s, %d ms blocks (%d frames), audio tcp/%d control tcp/%d%s" % (
            self.name, self.device_name, self.rate, CODEC_NAMES[self.codec], self.block_ms, self.block_frames,
            self.audio_port, self.control_port, "" if self.beacon else ", beacon off"))

    def stop(self) -> None:
        if self.source:
            self.source.stop()
        if self.beacon:
            self.beacon.stop()
        self.now_playing.stop()
        self.control.stop()
        self.audio.stop()


def list_devices() -> None:
    import sounddevice as sd
    print(sd.query_devices())


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="WalkDAC sender (Mac -> Wi-Fi -> NW-A105)")
    p.add_argument("--device", default="BlackHole 2ch", help="CoreAudio input device to capture (default: BlackHole 2ch)")
    p.add_argument("--source", choices=["device", "sine"], default="device", help="'sine' = 1 kHz test tone instead of a device")
    p.add_argument("--rate", type=int, default=0, help="sample rate; default = the device's current nominal rate")
    p.add_argument("--format", choices=["s16", "s24", "f32"], default="s24", help="PCM format on the wire (default s24)")
    p.add_argument("--block-ms", type=int, default=10, help="block length in ms (default 10)")
    p.add_argument("--port", type=int, default=DEFAULT_AUDIO_PORT)
    p.add_argument("--control-port", type=int, default=DEFAULT_CONTROL_PORT)
    p.add_argument("--beacon-port", type=int, default=DEFAULT_BEACON_PORT)
    p.add_argument("--name", default="", help="name shown on the A105 (default: hostname)")
    p.add_argument("--max-queue-ms", type=int, default=2000, help="per-client send queue before dropping (default 2000)")
    p.add_argument("--no-media-control", action="store_true", help="do not use media-control for now playing / commands")
    p.add_argument("--no-flush-on-change", action="store_true", help="do not tell the A105 to flush when the track changes")
    p.add_argument("--no-beacon", action="store_true", help="do not broadcast the discovery beacon")
    p.add_argument("--unpaced", action="store_true", help="(sine only) generate as fast as possible")
    p.add_argument("--list-devices", action="store_true")
    p.add_argument("-v", "--verbose", action="store_true")
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    if args.list_devices:
        list_devices()
        return 0
    try:
        sender = Sender(args)
        sender.start()
    except Exception as e:  # noqa: BLE001
        log("failed to start: %s" % e)
        if isinstance(e, ImportError) or "PortAudio" in str(e):
            log("hint: pip install -r requirements.txt (in the Python/venv you run this with)")
        elif args.source == "device":
            log("hint: python3 walkdac_sender.py --list-devices   (is BlackHole installed and named 'BlackHole 2ch'?)")
        return 1
    try:
        last = last_cap = last_silent = 0
        while True:
            time.sleep(5)
            cap, silent, sent = sender.blocks_captured, sender.silent_blocks, sender.audio.frames_sent
            stream = getattr(sender.source, "_stream", None)
            active = None if stream is None else stream.active
            if cap == last_cap or active is False:
                log("WARNING: no audio blocks from %s for 5 s (stream active=%s): capture stalled. "
                    "Restart the sender (rate changed in Audio MIDI Setup? coreaudiod restarted?)" % (sender.device_name, active))
            elif args.source == "device" and cap > last_cap and silent - last_silent == cap - last_cap and sender.audio.client_count() > 0:
                log("WARNING: 5 s of digital silence. Is System Settings > Sound > Output = %s, is something playing, "
                    "and does Terminal have Microphone permission?" % sender.device_name)
            if sender.verbose or sender.audio.client_count() == 0:
                with sender.audio.lock:
                    dropped = sum(c.dropped for c in sender.audio.clients)
                log("clients: %d, blocks captured: %d, sent: %d (+%d), dropped for slow clients: %d" % (
                    sender.audio.client_count(), cap, sent, sent - last, dropped))
            last, last_cap, last_silent = sent, cap, silent
    except KeyboardInterrupt:
        log("stopping")
    finally:
        sender.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
