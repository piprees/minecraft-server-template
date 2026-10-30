#!/usr/bin/env python3
"""Tests for scripts/e2e/bedrock-ping.py's RakNet packets, against a local UDP pong."""
import importlib.util
import socket
import struct
import threading
import http.server
import json
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "bedrock_ping", Path(__file__).resolve().parent.parent / "e2e" / "bedrock-ping.py"
)
bp = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bp)

ADVERT = "MCPE;CI smoke test 26.3;975;26.3;0;20;123;Geyser;Survival;1;19132;19133;"


def pong(advert=ADVERT, magic=bp.MAGIC):
    body = advert.encode()
    return struct.pack(">Bqq", 0x1C, 1, 2) + magic + struct.pack(">H", len(body)) + body


class Packets(unittest.TestCase):
    def test_the_ping_is_id_time_magic_guid(self):
        packet = bp.ping_packet(42)
        self.assertEqual(len(packet), 33)
        self.assertEqual(packet[0], 0x01)
        self.assertEqual(packet[9:25], bytes.fromhex("00ffff00fefefefefdfdfdfd12345678"))
        self.assertEqual(struct.unpack(">q", packet[25:])[0], 42)

    def test_a_pong_yields_its_advertisement(self):
        self.assertEqual(bp.parse_pong(pong()), ADVERT)

    def test_malformed_pongs_are_refused(self):
        for data in (b"", b"\x1c", pong(magic=b"\0" * 16), pong()[:-3], b"\x1d" + pong()[1:]):
            with self.assertRaises(ValueError):
                bp.parse_pong(data)


class RoundTrip(unittest.TestCase):
    def test_a_listening_server_answers(self):
        server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        server.bind(("127.0.0.1", 0))
        self.addCleanup(server.close)
        seen = []

        def answer():
            data, addr = server.recvfrom(4096)
            seen.append(data)
            server.sendto(pong(), addr)

        thread = threading.Thread(target=answer, daemon=True)
        thread.start()
        advert = bp.ping("127.0.0.1", server.getsockname()[1], tries=2, timeout=2)
        thread.join(5)
        self.assertEqual(advert, ADVERT)
        self.assertEqual(seen[0][0], 0x01)

    def test_silence_is_none(self):
        quiet = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        quiet.bind(("127.0.0.1", 0))
        self.addCleanup(quiet.close)
        self.assertIsNone(bp.ping("127.0.0.1", quiet.getsockname()[1], tries=1, timeout=0.2))


class Signaling(unittest.TestCase):
    def serve(self, body):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                code = 200 if self.path == "/v1/join" else 404
                self.send_response(code)
                self.end_headers()
                if code == 200:
                    self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        return server.server_address[1]

    def test_the_join_status_is_read(self):
        status = {"name": "CI smoke test 26.3", "version": "26.52", "protocol": 2193}
        port = self.serve(json.dumps(status).encode())
        self.assertEqual(bp.signaling_status("127.0.0.1", port, tries=1, timeout=2), status)

    def test_a_non_status_answer_is_refused(self):
        port = self.serve(b'["not", "a", "status"]')
        with self.assertRaises(ValueError):
            bp.signaling_status("127.0.0.1", port, tries=1, timeout=2)

    def test_a_closed_port_is_none(self):
        closed = socket.socket()
        closed.bind(("127.0.0.1", 0))
        port = closed.getsockname()[1]
        closed.close()
        self.assertIsNone(bp.signaling_status("127.0.0.1", port, tries=1, timeout=0.2))


if __name__ == "__main__":
    unittest.main()
