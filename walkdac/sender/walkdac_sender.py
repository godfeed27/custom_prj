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
    """Keeps the merged now-playing state from `media-control stream` and runs transport commands."""

    KEYS = ("bundleIdentifier", "playing", "title", "artist", "album", "duration", "elapsedTime",
            "playbackRate", "artworkMimeType")

    def __init__(self, enabled: bool, on_change: Callable[[dict, bool], None]):
        self.binary = shutil.which("media-control") if enabled else None
        self.on_change = on_change
        self.state: dict = {}
        self.artwork_b64: Optional[str] = None
        self.artwork_mime: Optional[str] = None
        self.received_at_ns = 0
        self.lock = threading.Lock()
        self._proc: Optional[subprocess.Popen] = None
        self._stop = threading.Event()
        self.available = self.binary is not None

    def start(self) -> None:
        if not self.binary:
            log("media-control not found: now playing and transport control are off (brew install media-control)")
            return
        threading.Thread(target=self._stream_loop, name="media-control", daemon=True).start()

    def _stream_loop(self) -> None:
        while not self._stop.is_set():
            try:
                self._proc = subprocess.Popen([self.binary, "stream"], stdout=subprocess.PIPE,
                                              stderr=subprocess.DEVNULL, text=True, bufsize=1)
            except OSError as e:
                log("media-control stream failed: %s" % e)
                time.sleep(5)
                continue
            assert self._proc.stdout is not None
            for line in self._proc.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except ValueError:
                    continue
                self._apply(msg)
            self._proc.wait()
            if not self._stop.is_set():
                log("media-control stream exited (code %s); restarting in 3 s" % self._proc.returncode)
                time.sleep(3)

    def _apply(self, msg: dict) -> None:
        if msg.get("type") != "data":
            return
        payload = msg.get("payload")
        diff = bool(msg.get("diff", False))
        if payload is None:
            with self.lock:
                self.state = {}
                self.artwork_b64 = None
                self.artwork_mime = None
                self.received_at_ns = now_ns()
            self.on_change({}, True)
            return
        track_changed = False
        artwork_changed = False
        with self.lock:
            if not diff:
                old_track = (self.state.get("bundleIdentifier"), self.state.get("title"), self.state.get("artist"), self.state.get("album"))
                self.state = {k: payload.get(k) for k in self.KEYS if payload.get(k) is not None}
                if "artworkData" in payload:
                    self.artwork_b64 = payload.get("artworkData")
                    self.artwork_mime = payload.get("artworkMimeType")
                    artwork_changed = True
                new_track = (self.state.get("bundleIdentifier"), self.state.get("title"), self.state.get("artist"), self.state.get("album"))
                track_changed = old_track != new_track
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
            self.received_at_ns = now_ns()
            snapshot = self.message(include_artwork=artwork_changed)
        self.on_change(snapshot, track_changed)

    def message(self, include_artwork: bool) -> dict:
        """Build a now_playing message. Caller must hold self.lock or accept a racy snapshot."""
        s = self.state
        msg = {
            "t": "now_playing",
            "app": s.get("bundleIdentifier"),
            "playing": bool(s.get("playing", False)),
            "title": s.get("title"),
            "artist": s.get("artist"),
            "album": s.get("album"),
            "duration_us": int(float(s.get("duration", 0)) * 1e6) if s.get("duration") is not None else None,
            "elapsed_us": int(float(s.get("elapsedTime", 0)) * 1e6) if s.get("elapsedTime") is not None else None,
            "rate": float(s.get("playbackRate", 1.0 if s.get("playing") else 0.0)),
            "at_mac_ns": self.received_at_ns,
        }
        if include_artwork and self.artwork_b64:
            msg["artwork_b64"] = self.artwork_b64
            msg["artwork_mime"] = self.artwork_mime or "image/jpeg"
        return msg

    def snapshot(self) -> dict:
        with self.lock:
            return self.message(include_artwork=True)

    def command(self, op: str, pos_us: Optional[int] = None) -> Tuple[bool, str]:
        if not self.binary:
            return False, "media-control not installed"
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

class ControlServer:
    def __init__(self, port: int, sender: "Sender"):
        self.port = port
        self.sender = sender
        self.clients: List[socket.socket] = []
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
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            with self.lock:
                self.clients.append(sock)
            log("control client connected: %s:%d" % addr)
            threading.Thread(target=self._client_loop, args=(sock, addr), name="control-%s" % addr[0], daemon=True).start()

    def _send(self, sock: socket.socket, msg: dict) -> bool:
        data = (json.dumps(msg, separators=(",", ":")) + "\n").encode("utf-8")
        try:
            sock.sendall(data)
            return True
        except OSError:
            return False

    def broadcast(self, msg: dict) -> None:
        with self.lock:
            clients = list(self.clients)
        for c in clients:
            if not self._send(c, msg):
                self._drop(c)

    def _drop(self, sock: socket.socket) -> None:
        with self.lock:
            if sock in self.clients:
                self.clients.remove(sock)
        try:
            sock.close()
        except OSError:
            pass

    def _client_loop(self, sock: socket.socket, addr) -> None:
        f = sock.makefile("r", encoding="utf-8", errors="replace")
        try:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except ValueError:
                    continue
                self._handle(sock, addr, msg)
        except OSError:
            pass
        finally:
            self._drop(sock)
            self.last_stats.pop("%s:%d" % addr, None)
            log("control client disconnected: %s:%d" % addr)

    def _handle(self, sock: socket.socket, addr, msg: dict) -> None:
        t = msg.get("t")
        if t == "time":
            self._send(sock, {"t": "time_ack", "id": msg.get("id"), "a105_ns": msg.get("a105_ns"), "mac_ns": now_ns()})
        elif t == "hello":
            log("receiver hello from %s:%d: %s" % (addr[0], addr[1], msg.get("name", "?")))
            self._send(sock, self.sender.source_message())
            self._send(sock, self.sender.now_playing.snapshot())
        elif t == "cmd":
            op = msg.get("op")
            pos_us = msg.get("pos_us")
            if op == "volume":
                self._send(sock, {"t": "cmd_ack", "op": op, "ok": False, "error": "volume is handled on the A105 in this version"})
                return

            def run():
                ok, err = self.sender.now_playing.command(op, pos_us)
                log("cmd %s from %s: %s%s" % (op, addr[0], "ok" if ok else "failed", "" if ok else " (" + err + ")"))
                self._send(sock, {"t": "cmd_ack", "op": op, "ok": ok, "error": err})
                if ok and op in ("pause", "next", "prev", "seek"):
                    # The receiver already flushed locally; make sure audio captured before the
                    # app reacted does not get replayed on the A105.
                    self.sender.schedule_flush(delay_s=0.15)
            threading.Thread(target=run, daemon=True).start()
        elif t == "stats":
            self.last_stats["%s:%d" % addr] = msg
            if self.sender.verbose:
                log("stats %s: buffer %sms underruns %s lost %s drift %sppm rssi %s" % (
                    addr[0], msg.get("buffer_ms"), msg.get("underruns"), msg.get("lost"), msg.get("drift_ppm"), msg.get("rssi")))

    def stop(self) -> None:
        self._stop.set()
        try:
            self._srv.close()
        except OSError:
            pass
        with self.lock:
            clients = list(self.clients)
        for c in clients:
            self._drop(c)


# ----------------------------------------------------------------------------- beacon

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
        while not self._stop.is_set():
            msg = {"t": "beacon", "name": self.sender.name, "audio_port": self.sender.audio_port,
                   "control_port": self.sender.control_port, "ver": PROTOCOL_VERSION,
                   "rate": self.sender.rate, "codec": CODEC_NAMES[self.sender.codec]}
            data = json.dumps(msg, separators=(",", ":")).encode("utf-8")
            try:
                sock.sendto(data, ("255.255.255.255", self.port))
            except OSError as e:
                log("beacon send failed: %s" % e)
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

    # --- capture callback (audio thread) ---
    def _on_block(self, block: np.ndarray, capture_ns: int) -> None:
        self.blocks_captured += 1
        if block.ndim == 1:
            block = np.stack([block, block], axis=1)
        elif block.shape[1] == 1:
            block = np.repeat(block, 2, axis=1)
        elif block.shape[1] > 2:
            block = block[:, :2]
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
        if track_changed and self.flush_on_track_change:
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
    sender = Sender(args)
    try:
        sender.start()
    except Exception as e:  # noqa: BLE001
        log("failed to start: %s" % e)
        if args.source == "device":
            log("hint: python3 walkdac_sender.py --list-devices   (is BlackHole installed and named 'BlackHole 2ch'?)")
        return 1
    try:
        last = 0
        while True:
            time.sleep(5)
            sent = sender.audio.frames_sent
            if sender.verbose or sender.audio.client_count() == 0:
                log("clients: %d, blocks captured: %d, sent: %d (+%d)" % (sender.audio.client_count(), sender.blocks_captured, sent, sent - last))
            last = sent
    except KeyboardInterrupt:
        log("stopping")
    finally:
        sender.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
