#!/usr/bin/env python3
"""bedrock-ping.py - Ask a Bedrock server (Geyser) for its MOTD over RakNet.

Context: template-only; smoke-test-26.yml runs it after the 26.3 boot gate, and
it works against any host. It sends one RakNet unconnected ping (0x01) and
reads the unconnected pong (0x1c), whose payload is the server's advertisement:
"MCPE;<motd>;<protocol>;<version>;<players>;<max>;<guid>;<sub-motd>;<mode>;...".
A pong means Geyser is listening and translating for this server; it touches
nothing on the Java side, so it does not wake an autopaused server.

Usage:
  scripts/e2e/bedrock-ping.py [host] [port]       # default 127.0.0.1 19132
  scripts/e2e/bedrock-ping.py HOST PORT --expect "CI smoke test"
  options: --tries N (default 5), --timeout S per try (default 2)

Exit 0 with the advertisement printed; 1 when nothing answers or --expect is
not in the MOTD.

Gotchas:
  - UDP has no connection: "no answer" covers a closed port, a firewall drop,
    a container that does not publish the port, and Geyser not started yet.
    Retry with --tries before reading it as any one of them.
  - Geyser answers pings once its listener binds, which is after the Java
    server reports Done; run this after the boot gate, not alongside it.
"""
import argparse
import os
import socket
import struct
import sys
import time

MAGIC = bytes.fromhex("00ffff00fefefefefdfdfdfd12345678")
UNCONNECTED_PING = 0x01
UNCONNECTED_PONG = 0x1C


def ping_packet(client_guid: int) -> bytes:
    millis = int(time.monotonic() * 1000) & 0x7FFFFFFFFFFFFFFF
    return struct.pack(">Bq", UNCONNECTED_PING, millis) + MAGIC + struct.pack(">q", client_guid)


def parse_pong(data: bytes) -> str:
    """The advertisement string of an unconnected pong; ValueError otherwise."""
    if len(data) < 35 or data[0] != UNCONNECTED_PONG:
        raise ValueError(f"not an unconnected pong: {data[:1].hex() or 'empty'}")
    if data[17:33] != MAGIC:
        raise ValueError("pong without the offline-message magic")
    (length,) = struct.unpack(">H", data[33:35])
    body = data[35 : 35 + length]
    if len(body) != length:
        raise ValueError(f"truncated advertisement: {len(body)} of {length} bytes")
    return body.decode("utf-8", errors="replace")


def ping(host: str, port: int, tries: int, timeout: float) -> str | None:
    guid = struct.unpack(">q", os.urandom(8))[0]
    family = socket.getaddrinfo(host, port, type=socket.SOCK_DGRAM)[0]
    with socket.socket(family[0], socket.SOCK_DGRAM) as sock:
        sock.settimeout(timeout)
        for _ in range(tries):
            sock.sendto(ping_packet(guid), family[4])
            try:
                data, _ = sock.recvfrom(4096)
            except socket.timeout:
                continue
            return parse_pong(data)
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("host", nargs="?", default="127.0.0.1")
    parser.add_argument("port", nargs="?", type=int, default=19132)
    parser.add_argument("--expect", help="text the MOTD must contain")
    parser.add_argument("--tries", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=2.0)
    args = parser.parse_args()

    try:
        advert = ping(args.host, args.port, args.tries, args.timeout)
    except (OSError, ValueError) as error:
        print(f"FAIL: {args.host}:{args.port}/udp: {error}", file=sys.stderr)
        return 1
    if advert is None:
        print(f"FAIL: no pong from {args.host}:{args.port}/udp after {args.tries} tries", file=sys.stderr)
        return 1

    fields = advert.split(";")
    names = ("edition", "motd", "protocol", "version", "players", "max_players", "server_guid", "sub_motd", "game_mode")
    print(f"pong from {args.host}:{args.port}/udp")
    for name, value in zip(names, fields):
        print(f"  {name}: {value}")
    if args.expect is not None and (len(fields) < 2 or args.expect not in fields[1]):
        print(f"FAIL: MOTD does not contain {args.expect!r}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
