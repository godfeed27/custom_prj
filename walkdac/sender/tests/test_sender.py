import json
import os
import socket
import struct
import sys
import threading
import time
import unittest

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import walkdac_sender as ws  # noqa: E402


def decode_s24(payload: bytes) -> np.ndarray:
    u8 = np.frombuffer(payload, dtype=np.uint8).reshape(-1, 3)
    i32 = (u8[:, 0].astype(np.uint32) | (u8[:, 1].astype(np.uint32) << 8) | (u8[:, 2].astype(np.uint32) << 16)).astype(np.int32)
    i32 = np.where(i32 & 0x800000, i32 - (1 << 24), i32)
    return i32.astype(np.float32) / 8388607.0


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return buf


def read_frame(sock: socket.socket):
    hdr = ws.unpack_header(recv_exact(sock, ws.HEADER_SIZE))
    payload = recv_exact(sock, hdr["payload_len"])
    return hdr, payload


class ProtocolTests(unittest.TestCase):
    def test_header_roundtrip(self):
        payload = b"\x01\x02\x03" * 4
        frame = ws.pack_frame(123456, 987654321987, 96000, 2, ws.CODEC_S24, payload)
        self.assertEqual(len(frame), ws.HEADER_SIZE + len(payload))
        self.assertEqual(ws.HEADER_SIZE, 28)
        h = ws.unpack_header(frame)
        self.assertEqual(h["version"], 1)
        self.assertEqual(h["codec"], ws.CODEC_S24)
        self.assertEqual(h["seq"], 123456)
        self.assertEqual(h["capture_ns"], 987654321987)
        self.assertEqual(h["rate"], 96000)
        self.assertEqual(h["frames"], 2)
        self.assertEqual(h["bits"], 24)
        self.assertEqual(h["channels"], 2)
        self.assertEqual(h["payload_len"], 12)
        self.assertEqual(frame[:2], b"WD")
        self.assertEqual(struct.unpack("<I", frame[4:8])[0], 123456)

    def test_encode_s16_s24_f32(self):
        block = np.array([[0.0, 0.5], [-0.5, 1.0], [-1.0, 0.25]], dtype=np.float32)
        s16 = np.frombuffer(ws.encode_block(block, ws.CODEC_S16), dtype="<i2")
        self.assertEqual(list(s16), [0, 16384, -16384, 32767, -32767, 8192])
        s24 = decode_s24(ws.encode_block(block, ws.CODEC_S24))
        np.testing.assert_allclose(s24, block.reshape(-1), atol=2e-7)
        f32 = np.frombuffer(ws.encode_block(block, ws.CODEC_F32), dtype="<f4")
        np.testing.assert_array_equal(f32, block.reshape(-1))
        clipped = ws.encode_block(np.array([[2.0, -2.0]], dtype=np.float32), ws.CODEC_S24)
        self.assertEqual(list(decode_s24(clipped).round(6)), [1.0, -1.0])


class LoopbackTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        args = ws.build_parser().parse_args(["--source", "sine", "--rate", "48000", "--format", "s24", "--block-ms", "10",
                                             "--port", "0", "--control-port", "0", "--no-beacon", "--no-media-control",
                                             "--name", "TestMac"])
        cls.sender = ws.Sender(args)
        cls.sender.start()

    @classmethod
    def tearDownClass(cls):
        cls.sender.stop()

    def test_audio_stream_is_continuous_sine(self):
        s = socket.create_connection(("127.0.0.1", self.sender.audio_port), timeout=5)
        frames = [read_frame(s) for _ in range(60)]
        s.close()
        seqs = [h["seq"] for h, _ in frames]
        self.assertEqual(seqs, list(range(seqs[0], seqs[0] + 60)), "seq must be contiguous")
        for h, payload in frames:
            self.assertEqual(h["rate"], 48000)
            self.assertEqual(h["frames"], 480)
            self.assertEqual(h["codec"], ws.CODEC_S24)
            self.assertEqual(h["payload_len"], 480 * 2 * 3)
            self.assertEqual(len(payload), h["payload_len"])
        caps = [h["capture_ns"] for h, _ in frames]
        deltas = np.diff(caps) / 1e6
        self.assertTrue(np.all(deltas > 0), "capture timestamps must increase")
        self.assertLess(abs(np.mean(deltas) - 10.0), 1.0, "blocks should be paced at ~10 ms, got %.2f" % np.mean(deltas))
        pcm = np.concatenate([decode_s24(p).reshape(-1, 2) for _, p in frames])
        left = pcm[:, 0]
        np.testing.assert_allclose(pcm[:, 0], pcm[:, 1])
        rms = float(np.sqrt(np.mean(left ** 2)))
        self.assertAlmostEqual(rms, 0.5 / np.sqrt(2), delta=0.01)
        step = float(np.max(np.abs(np.diff(left))))
        self.assertLess(step, 2 * np.pi * 1000 / 48000 * 0.5 * 1.1, "no discontinuity across frame boundaries")

    def test_control_channel(self):
        s = socket.create_connection(("127.0.0.1", self.sender.control_port), timeout=5)
        f = s.makefile("rw", encoding="utf-8", newline="\n")

        def send(msg):
            f.write(json.dumps(msg) + "\n")
            f.flush()

        def recv():
            line = f.readline()
            self.assertTrue(line, "control connection closed")
            return json.loads(line)

        send({"t": "hello", "name": "unit-test", "ver": 1})
        src = recv()
        self.assertEqual(src["t"], "source")
        self.assertEqual(src["rate"], 48000)
        self.assertEqual(src["codec"], "pcm_s24")
        self.assertEqual(src["bits"], 24)
        self.assertEqual(src["name"], "TestMac")
        self.assertFalse(src["media_control"])
        npm = recv()
        self.assertEqual(npm["t"], "now_playing")
        self.assertFalse(npm["playing"])
        t0 = time.monotonic_ns()
        send({"t": "time", "id": 7, "a105_ns": t0})
        ack = recv()
        self.assertEqual(ack["t"], "time_ack")
        self.assertEqual(ack["id"], 7)
        self.assertEqual(ack["a105_ns"], t0)
        self.assertGreater(ack["mac_ns"], 0)
        send({"t": "cmd", "op": "toggle"})
        cack = recv()
        self.assertEqual(cack["t"], "cmd_ack")
        self.assertFalse(cack["ok"])
        self.assertIn("media-control", cack["error"])
        send({"t": "cmd", "op": "volume", "value": 0.5})
        vack = recv()
        self.assertFalse(vack["ok"])
        send({"t": "stats", "buffer_ms": 480, "underruns": 0, "lost": 0, "drift_ppm": 12, "rssi": -50})
        # flush broadcast reaches control clients
        self.sender.schedule_flush(0.0)
        fl = recv()
        self.assertEqual(fl["t"], "flush")
        self.assertGreater(fl["from_seq"], 0)
        s.close()

    def test_slow_client_drops_oldest_but_keeps_stream_alive(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 65536)  # small receive window, like a congested Wi-Fi link
        s.settimeout(5)
        s.connect(("127.0.0.1", self.sender.audio_port))
        time.sleep(4.0)  # stall: kernel buffers (~0.5 s) + our queue (2 s) must overflow and drop the oldest blocks
        h1, _ = read_frame(s)
        deadline = time.time() + 8
        gap_seen = False
        prev = h1["seq"]
        while time.time() < deadline:
            h, _ = read_frame(s)
            if h["seq"] != prev + 1:
                gap_seen = True
                break
            prev = h["seq"]
        s.close()
        self.assertTrue(gap_seen, "after a stall the receiver should see a seq gap (dropped blocks), not an unbounded backlog")


class BeaconTests(unittest.TestCase):
    def test_beacon_broadcast(self):
        rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rx.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        rx.bind(("", 0))
        port = rx.getsockname()[1]
        rx.settimeout(4)
        args = ws.build_parser().parse_args(["--source", "sine", "--port", "0", "--control-port", "0", "--no-media-control",
                                             "--beacon-port", str(port), "--name", "BeaconMac", "--unpaced"])
        sender = ws.Sender(args)
        sender.beacon.interval_s = 0.2
        sender.start()
        try:
            try:
                data, _ = rx.recvfrom(2048)
            except socket.timeout:
                self.skipTest("UDP broadcast not deliverable in this sandbox")
            msg = json.loads(data.decode())
            self.assertEqual(msg["t"], "beacon")
            self.assertEqual(msg["name"], "BeaconMac")
            self.assertEqual(msg["audio_port"], sender.audio_port)
            self.assertEqual(msg["control_port"], sender.control_port)
        finally:
            sender.stop()
            rx.close()


if __name__ == "__main__":
    unittest.main()
