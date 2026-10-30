#!/usr/bin/env python3
"""bedrock-ping.py - Ask a Bedrock server (Geyser) for its MOTD.

Context: template-only; smoke-test-26.yml runs it after the 26.3 boot gate, and
it works against any host. Two transports, matching Geyser's bedrock.transport:
  nethernet (default)  GET http://<host>:<port>/v1/join, the status Geyser's
                       built-in signaling serves over TCP, as JSON with the MOTD
                       in "name". Bedrock 26.60 and later join only this way.
  --raknet             one RakNet unconnected ping (0x01, offline-message magic
                       00ffff00fefefefefdfdfdfd12345678) over UDP and its pong
                       (0x1c): "MCPE;<motd>;<protocol>;<version>;<players>;...".
An answer means Geyser is listening and translating for this server; it touches
nothing on the Java side, so it does not wake an autopaused server.

Usage:
  scripts/e2e/bedrock-ping.py [host] [port]       # default 127.0.0.1 19132
  scripts/e2e/bedrock-ping.py HOST PORT --expect "CI smoke test"
  scripts/e2e/bedrock-ping.py HOST PORT --raknet
  options: --tries N (default 5), --timeout S per try (default 2)

Exit 0 with the status printed; 1 when nothing answers or --expect is not in
the MOTD.

Gotchas:
  - Each transport answers only when Geyser runs it: transport nethernet
    leaves the RakNet ping unanswered, and raknet serves no /v1/join.
  - UDP has no connection: a RakNet "no answer" covers a closed port, a
    firewall drop, a container that does not publish the port, and Geyser not
    started yet. Retry with --tries before reading it as any one of them.
  - Geyser starts after the Java server reports Done; run this after the boot
    gate, not alongside it.
  - The signaling probe bypasses any HTTP proxy in the environment.
"""
import argparse
import json
import os
import socket
import struct
import sys
import time
import urllib.error
import urllib.request

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


def signaling_status(host: str, port: int, tries: int, timeout: float) -> dict | None:
    """The JSON status Geyser's built-in NetherNet signaling serves at /v1/join."""
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    url = f"http://{host}:{port}/v1/join"
    for attempt in range(tries):
        try:
            with opener.open(url, timeout=timeout) as response:
                status = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, OSError):
            if attempt + 1 < tries:
                time.sleep(timeout)
            continue
        if not isinstance(status, dict) or "name" not in status:
            raise ValueError(f"{url} answered with something other than a NetherNet status")
        return status
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("host", nargs="?", default="127.0.0.1")
    parser.add_argument("port", nargs="?", type=int, default=19132)
    parser.add_argument("--expect", help="text the MOTD must contain")
    parser.add_argument("--tries", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=2.0)
    parser.add_argument("--raknet", action="store_true", help="RakNet ping over UDP instead of the signaling status")
    args = parser.parse_args()

    if not args.raknet:
        try:
            status = signaling_status(args.host, args.port, args.tries, args.timeout)
        except ValueError as error:
            print(f"FAIL: {error}", file=sys.stderr)
            return 1
        if status is None:
            print(f"FAIL: no signaling status from {args.host}:{args.port}/tcp after {args.tries} tries", file=sys.stderr)
            return 1
        print(f"signaling status from {args.host}:{args.port}/tcp (NetherNet)")
        for key in ("name", "level", "version", "protocol", "players", "maxPlayers", "onlineAuth"):
            print(f"  {key}: {status.get(key)}")
        if args.expect is not None and args.expect not in str(status.get("name")):
            print(f"FAIL: MOTD does not contain {args.expect!r}", file=sys.stderr)
            return 1
        return 0

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
