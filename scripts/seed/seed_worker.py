#!/usr/bin/env python3
"""seed_worker.py — one seed-roll worker: container lifecycle + measurement.

Launched N times in parallel by roll-all.sh. Each worker owns one Docker
container (itzg/minecraft-server, SEED_ROLL_MODE=true so no dimensions are
created at boot) and works through its manifest sequentially:

    customdim create <cand> ... -> measure -> [render] -> customdim destroy

Talks RCON directly over a published localhost port (native Source-RCON
client) — docker exec rcon-cli costs 150-300ms per call and a candidate
needs ~150 calls; the socket path is ~5-10ms.

Measurement per candidate (metrics land in .seedtest/worker-<id>.csv,
long format target,seed,metric,value — target is the REAL dimension name):
  spawn_biome            first matching probe (y 64, then y -32 for caves)
  spawn_filter_dist      nearest spawn-filter biome (locate; accepted rows)
  structure_<name>_dist  locate structure (-1 = not found)
  biome_<id>_dist        locate biome     (-1 = not found)
  height_rNcM            column surface height at the 3x3 grid point
  water_rNcM             fluid at the reference level (water y62 overworld,
                         lava y32 nether)
  errors                 new filtered ERROR log lines during this candidate

Render mode (--mode render) creates each candidate, forceloads a spawn
square, saves, then unmined-cli renders a flat top-down map image to
.seedtest/renders/<dim>/<seed>.png. --mode measure+render does both.

Gotchas honoured (AGENTS.md): c2me DFC forced off via config; world dirs
of destroyed candidates are deleted from disk (destroy keeps files);
finite waits everywhere — no unbounded loops.
"""
import argparse
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from dimension_profiles import build_profile, load_config, load_difficulty  # noqa: E402

BOOT_TIMEOUT = int(os.environ.get("RCON_TIMEOUT", "120"))
_BASE_IMAGE = "itzg/minecraft-server:2026.7.0-java21"
_WARM_IMAGE = "seedroll:warm"


def _resolve_image():
    """Use the warm image if it exists locally; fall back to base."""
    explicit = os.environ.get("ROLL_IMAGE")
    if explicit:
        return explicit
    try:
        r = subprocess.run(["docker", "image", "inspect", _WARM_IMAGE],
                           capture_output=True, check=False)
        if r.returncode == 0:
            return _WARM_IMAGE
    except OSError:
        pass
    return _BASE_IMAGE


IMAGE = _resolve_image()

# A candidate only needs a namesake biome within a short expedition from
# spawn. The score already gives true spawns full credit and nearby biomes
# proportionally less, so escalating gates merely starved the shortlist.
SPAWN_GATE_RADIUS = int(os.environ.get("ROLL_SPAWN_GATE_RADIUS", "768"))
RCON_CLOSE_RECREATE_AFTER = int(os.environ.get("ROLL_RCON_CLOSE_RECREATE_AFTER", "2"))
RCON_BACKOFF_BASE = float(os.environ.get("ROLL_RCON_BACKOFF_BASE", "5"))
RCON_BACKOFF_MAX = float(os.environ.get("ROLL_RCON_BACKOFF_MAX", "60"))
SKIP_AFTER_MISSES = int(os.environ.get("ROLL_SKIP_AFTER_MISSES", "15"))


def spawn_gate_for(_misses):
    """Fixed acceptance radius; proximity remains a ranking signal."""
    return SPAWN_GATE_RADIUS, False

# Column-height search ranges per family (nether capped under the roof).
HEIGHT_RANGE = {"overworld": (-60, 318), "nether": (0, 118), "end": (0, 250)}
FLUID_CHECK = {"overworld": (62, "minecraft:water"), "nether": (32, "minecraft:lava"),
               "end": (62, "minecraft:water")}

ERROR_FILTERS = ("No data fixer registered", "Error loading class",
                 "Block-attached entity at invalid position", "template pool reference")
CREATE_SUCCESS_RESPONSES = ("Queued dimension", "Created dimension")


# ---------------------------------------------------------------------------
# Minimal PNG crop (BlueMap lowres tiles are 8-bit RGB/RGBA). sips crops
# CENTRED and ignores --cropOffset on some macOS builds — it silently
# produced blank thumbnails from the empty middle of the tile.
# ---------------------------------------------------------------------------
def _png_rows(data):
    """-> (rows, width, height, ctype, bpp) for an 8-bit RGB/RGBA PNG."""
    import zlib
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos, width, height, ctype, idat = 8, 0, 0, 0, b""
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if tag == b"IHDR":
            width, height, depth, ctype = struct.unpack(">IIBB", body[:10])
            if depth != 8 or ctype not in (2, 6):
                raise ValueError(f"unsupported PNG (depth {depth}, type {ctype})")
        elif tag == b"IDAT":
            idat += body
        elif tag == b"IEND":
            break
        pos += 12 + length
    bpp = 4 if ctype == 6 else 3
    raw = zlib.decompress(idat)
    stride = width * bpp
    rows, prev = [], bytearray(stride)
    for r in range(height):
        off = r * (stride + 1)
        f = raw[off]
        line = bytearray(raw[off + 1:off + 1 + stride])
        if f == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        rows.append(bytes(line))
        prev = line
    return rows, width, height, ctype, bpp


def crop_png(src, dst, x0, y0, w, h):
    import zlib
    rows, _width, height, ctype, bpp = _png_rows(Path(src).read_bytes())
    out = bytearray()
    for r in range(y0, min(y0 + h, height)):
        out += b"\x00" + rows[r][x0 * bpp:(x0 + w) * bpp]

    def chunk(tag, body):
        return (struct.pack(">I", len(body)) + tag + body
                + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, ctype, 0, 0, 0)
    Path(dst).write_bytes(
        b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(out), 6)) + chunk(b"IEND", b""))


def png_hole_fraction(path, w, h):
    """Fraction of unrendered pixels (transparent, or pure black in RGB) in
    the top-left w x h of a tile — the no-holes-no-black-squares check."""
    try:
        rows, _width, height, ctype, bpp = _png_rows(Path(path).read_bytes())
    except (ValueError, OSError):
        return 1.0
    holes = total = 0
    for r in range(min(h, height)):
        row = rows[r]
        for c in range(min(w, len(row) // bpp)):
            total += 1
            px = row[c * bpp:(c + 1) * bpp]
            if ctype == 6 and px[3] == 0:
                holes += 1
            elif px[0] == 0 and px[1] == 0 and px[2] == 0:
                holes += 1
    return holes / total if total else 1.0


def log(worker_id, msg):
    print(f"[W{worker_id}] {msg}", flush=True)


# ---------------------------------------------------------------------------
# Minimal Source-RCON client
# ---------------------------------------------------------------------------
class RconTimeout(TimeoutError):
    """The server did not answer one command before its hard deadline."""


class RconClosed(ConnectionError):
    """The server closed the RCON socket before replying."""


class Rcon:
    # `locate` is synchronous on the server thread. Closing the socket does
    # not cancel it, so time out at 60s and let the worker recreate the
    # throwaway container rather than queue more work behind a stalled locate.
    CMD_TIMEOUT = float(os.environ.get("ROLL_RCON_CMD_TIMEOUT", "120"))

    def __init__(self, host, port, password, timeout=None):
        timeout = timeout if timeout is not None else self.CMD_TIMEOUT
        self.addr = (host, port)
        self.password = password
        self.timeout = timeout
        self.sock = None
        self._id = 0

    def connect(self):
        try:
            self.close()
            self.sock = socket.create_connection(self.addr, timeout=self.timeout)
            self._send(3, self.password)
            rid, _typ, _body = self._recv()
            if rid == -1:
                raise ConnectionError("RCON auth failed")
        except RconTimeout:
            raise
        except RconClosed:
            raise
        except socket.timeout as exc:
            self.close()
            raise RconTimeout(
                f"RCON connection timed out after {int(self.timeout)}s") from exc
        except (OSError, ConnectionError) as exc:
            self.close()
            raise RconClosed(str(exc)) from exc

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None

    def _send(self, typ, body):
        self._id += 1
        payload = struct.pack("<ii", self._id, typ) + body.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)
        return self._id

    def _recv(self):
        raw = b""
        while len(raw) < 4:
            chunk = self.sock.recv(4 - len(raw))
            if not chunk:
                raise ConnectionError("RCON closed")
            raw += chunk
        (length,) = struct.unpack("<i", raw)
        data = b""
        while len(data) < length:
            chunk = self.sock.recv(length - len(data))
            if not chunk:
                raise ConnectionError("RCON closed")
            data += chunk
        rid, typ = struct.unpack("<ii", data[:8])
        return rid, typ, data[8:-2].decode("utf-8", "replace")

    def cmd(self, command):
        """Run one command without retrying an indeterminate side effect."""
        try:
            if self.sock is None:
                self.connect()
            self._send(2, command)
            _rid, _typ, body = self._recv()
            return body
        except (RconTimeout, RconClosed):
            raise
        except socket.timeout as exc:
            self.close()
            raise RconTimeout(
                f"RCON command timed out after {int(self.timeout)}s") from exc
        except (OSError, ConnectionError) as exc:
            self.close()
            raise RconClosed(str(exc)) from exc


# ---------------------------------------------------------------------------
# Container lifecycle
# ---------------------------------------------------------------------------
def docker(*args, check=True, capture=True):
    return subprocess.run(["docker", *args], check=check,
                          capture_output=capture, text=True)


def container_running(name):
    r = docker("inspect", "-f", "{{.State.Running}}", name, check=False)
    return r.returncode == 0 and r.stdout.strip() == "true"


def fabric_pin_env(workdir):
    """Pin the Fabric loader/launcher to the cached install so itzg's
    install step never queries meta.fabricmc.net (a hung resolve once ate a
    whole 300s boot window). Falls back to unpinned when no cache exists."""
    env_file = Path(workdir) / ".install-fabric.env"
    if not env_file.exists():
        return []
    import re
    m = re.search(r"loader\.([0-9.]+)-launcher\.([0-9.]+)\.jar", env_file.read_text())
    if not m:
        return []
    return ["-e", f"FABRIC_LOADER_VERSION={m.group(1)}",
            "-e", f"FABRIC_LAUNCHER_VERSION={m.group(2)}"]


def start_container(name, workdir, memory, seed="1"):
    docker("rm", "-f", name, check=False)
    for _ in range(10):
        r = docker("inspect", name, check=False, capture=True)
        if r.returncode != 0:
            break
        time.sleep(0.5)
    mem_gb = int(memory[:-1]) if memory.endswith("G") else 0
    java_mem = f"{mem_gb - 3}G" if mem_gb > 4 else memory
    # When a warm image exists, mount only per-seed state (world/) and the
    # session-constant roll config. Everything else (mods, libraries, Fabric,
    # defaultconfigs) is baked into the image and stays cached.
    image = IMAGE
    if image == _WARM_IMAGE:
        mounts = ["-v", f"{workdir}/world:/data/world",
                  "-v", f"{workdir}/config/custom-dimensions:/data/config/custom-dimensions",
                  "-v", f"{workdir}/config/c2me.toml:/data/config/c2me.toml",
                  "-v", f"{workdir}/config/bluemap:/data/config/bluemap"]
    else:
        mounts = ["-v", f"{workdir}:/data"]
    docker("run", "-d", "--name", name,
           *fabric_pin_env(workdir),
           "--memory", memory,
           "--log-opt", "max-size=5m", "--log-opt", "max-file=1",
           "-p", "127.0.0.1:0:25575",
           "-e", "EULA=TRUE", "-e", "TYPE=FABRIC", "-e", "VERSION=1.21.1",
           "-e", f"SEED={seed}", "-e", f"MEMORY={java_mem}",
           "-e", "USE_AIKAR_FLAGS=false",
           "-e", "JVM_XX_OPTS=-XX:+UseZGC -XX:+ZGenerational",
           "-e", "ENABLE_RCON=TRUE", "-e", "RCON_PASSWORD=seedroll",
           "-e", "ONLINE_MODE=FALSE", "-e", "ENABLE_AUTOPAUSE=FALSE",
           "-e", "OVERRIDE_SERVER_PROPERTIES=true",
           "-e", "MAX_TICK_TIME=-1",
           "-e", "SEED_ROLL_MODE=true",
           "-e", "VIEW_DISTANCE=6", "-e", "SIMULATION_DISTANCE=4",
           "-e", "GENERATE_STRUCTURES=false",
           "-e", "SPAWN_CHUNK_RADIUS=0",
           *mounts, image)
    port = docker("port", name, "25575").stdout.strip().rsplit(":", 1)[-1]
    return int(port)


def wait_for_rcon(worker_id, name, port):
    """Wait for server readiness using rcon-cli (itzg built-in), then open
    the persistent TCP socket for bulk measurement commands."""
    start = time.time()
    last = ""
    while time.time() - start < BOOT_TIMEOUT:
        if should_stop():
            log(worker_id, "stop requested during boot — abandoning")
            return None
        if not container_running(name):
            log(worker_id, "container died during boot")
            return None
        r = docker("exec", name, "rcon-cli", "list", check=False)
        if r.returncode == 0 and "players" in (r.stdout or "").lower():
            log(worker_id, f"server ready ({int(time.time() - start)}s)")
            rcon = Rcon("127.0.0.1", port, "seedroll")
            rcon.connect()
            return rcon
        line = docker("logs", "--tail", "1", name, check=False).stdout.strip()[:110]
        if line and line != last:
            last = line
            log(worker_id, f"boot [{int(time.time() - start)}s] {line}")
        time.sleep(5)
    log(worker_id, f"RCON not ready after {BOOT_TIMEOUT}s")
    return None


def error_count(name):
    r = docker("logs", name, check=False)
    n = 0
    for line in (r.stdout or "").splitlines() + (r.stderr or "").splitlines():
        if "ERROR" in line and not any(f in line for f in ERROR_FILTERS):
            n += 1
    return n


def worker_backoff(failures):
    """Bounded exponential backoff for a worker whose RCON became unhealthy."""
    return min(RCON_BACKOFF_BASE * (2 ** max(0, failures - 1)), RCON_BACKOFF_MAX)


def file_tail(path, lines=120):
    try:
        return "\n".join(Path(path).read_text(errors="replace").splitlines()[-lines:]) + "\n"
    except OSError:
        return ""


def capture_rcon_diagnostic(args, worker_id, container, dim_name, seed, failure):
    """Preserve post-mortem evidence before a timed-out worker is recreated."""
    stamp = time.strftime("%Y%m%d-%H%M%S")
    diag_dir = Path(args.seedtest) / "diagnostics" / f"worker-{worker_id}"
    diag_dir.mkdir(parents=True, exist_ok=True)
    stem = diag_dir / f"{stamp}-{dim_name}-{seed}"
    state = docker("inspect", "-f", "{{json .State}}", container,
                   check=False).stdout.strip()
    container_log = docker("logs", "--tail", "120", container,
                           check=False).stdout
    (stem.with_suffix(".json")).write_text(json.dumps({
        "timestamp": stamp,
        "worker": worker_id,
        "container": container,
        "dimension": dim_name,
        "seed": seed,
        "failure": type(failure).__name__,
        "message": str(failure),
        "container_state": state,
    }, indent=2) + "\n")
    (stem.with_suffix(".container.log")).write_text(container_log)
    (stem.with_suffix(".game.log")).write_text(
        file_tail(Path(args.workdir) / "logs" / "latest.log"))
    return stem


def record_abandoned_seed(args, worker_id, dim_name, seed, reason):
    """Keep an unhealthy-worker seed out of later sessions without scoring it."""
    path = Path(args.seedtest) / f"abandoned-worker-{worker_id}.csv"
    new_file = not path.exists()
    with path.open("a") as fh:
        if new_file:
            fh.write("target,seed,reason\n")
        fh.write(f"{dim_name},{seed},{reason}\n")


def rcon_failure_reason(reason):
    return reason in ("rcon-timeout", "rcon-closed")


def recover_rcon(args, worker_id, container, rcon, failures, reason):
    """Reconnect one closed socket; recreate immediately on timeout or repeat."""
    if should_stop():
        return None, failures
    delay = worker_backoff(failures)
    if reason == "rcon-closed" and failures < RCON_CLOSE_RECREATE_AFTER:
        log(worker_id, f"  RCON closed once — checking health after {delay:.0f}s")
        time.sleep(delay)
        if should_stop():
            return None, failures
        try:
            rcon.connect()
            rcon.cmd("list")
            log(worker_id, "  RCON health check recovered; continuing")
            return rcon, 0
        except (RconTimeout, RconClosed):
            failures += 1
            delay = worker_backoff(failures)

    if should_stop():
        return None, failures
    log(worker_id, f"  recreating unhealthy container after {delay:.0f}s backoff")
    docker("rm", "-f", container, check=False)
    time.sleep(delay)
    return boot(worker_id, container, args.workdir, args.memory), failures


# ---------------------------------------------------------------------------
# Measurement primitives
# ---------------------------------------------------------------------------
def parse_distance(output, cap=None):
    """'... (123 blocks away)' -> 123, else -1. Distances beyond cap are -1."""
    if not output or "could not" in output.lower():
        return -1
    marker = " blocks away"
    idx = output.find(marker)
    if idx < 0:
        return -1
    start = output.rfind("(", 0, idx)
    try:
        d = int(output[start + 1:idx])
        if cap is not None and d > cap:
            return -1
        return d
    except (ValueError, IndexError):
        return -1


def parse_locate_coords(output):
    """Extract [x, y, z] from 'is at [x y z]' locate response, or None."""
    if not output or "could not" in output.lower():
        return None
    import re
    m = re.search(r'\[(-?\d+)[, ]+(-?\d+)[, ]+(-?\d+)\]', output)
    if m:
        return [int(m.group(1)), int(m.group(2)), int(m.group(3))]
    return None


def test_ok(output):
    return bool(output) and "passed" in output.lower()


def column_height(rcon, dim, x, z, lo, hi):
    """Topmost solid Y, tolerant of void-bottomed columns (end/sky islands):
    step down from the top to find any solid, then binary-refine the surface
    boundary. Returns None when the whole column is replaceable (true void)."""
    step = 24
    y = hi
    solid_y = None
    while y >= lo:
        out = rcon.cmd(f"execute in {dim} if block {x} {y} {z} #minecraft:replaceable")
        if not test_ok(out):
            solid_y = y
            break
        y -= step
    if solid_y is None:
        return None
    lo_b, hi_b = solid_y, min(solid_y + step, hi + 1)
    while hi_b - lo_b > 1:
        mid = (lo_b + hi_b) // 2
        out = rcon.cmd(f"execute in {dim} if block {x} {mid} {z} #minecraft:replaceable")
        if test_ok(out):
            hi_b = mid
        else:
            lo_b = mid
    return lo_b


def wait_loaded(rcon, dim, x=0, z=0, timeout=90):
    """forceload add returns before the chunk GENERATES — under fleet load
    that gap poisoned every probe (biome unknown, heights = build limit).
    Poll `execute if loaded` until the chunk is real."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if test_ok(rcon.cmd(f"execute in {dim} if loaded {x} 64 {z}")):
            return True
        time.sleep(2)
    return False


# Identification fallback so rejections name their REAL spawn biome —
# needed to audit filter behaviour and to give near-miss candidates their
# partial namesake credit. Ordered common-first; ~10ms per probe.
COMMON_BIOMES = [
    "minecraft:plains", "minecraft:forest", "minecraft:birch_forest",
    "minecraft:taiga", "minecraft:snowy_taiga", "minecraft:savanna",
    "minecraft:desert", "minecraft:swamp", "minecraft:jungle",
    "minecraft:dark_forest", "minecraft:meadow", "minecraft:river",
    "minecraft:beach", "minecraft:ocean", "minecraft:deep_ocean",
    "minecraft:cold_ocean", "minecraft:lukewarm_ocean", "minecraft:warm_ocean",
    "minecraft:frozen_ocean", "minecraft:snowy_plains", "minecraft:windswept_hills",
    "minecraft:windswept_forest", "minecraft:stony_shore", "minecraft:grove",
    "minecraft:snowy_slopes", "minecraft:jagged_peaks", "minecraft:stony_peaks",
    "minecraft:badlands", "minecraft:cherry_grove", "minecraft:mangrove_swamp",
    "minecraft:old_growth_spruce_taiga", "minecraft:old_growth_pine_taiga",
    "minecraft:sunflower_plains", "minecraft:flower_forest",
    "minecraft:nether_wastes", "minecraft:crimson_forest", "minecraft:warped_forest",
    "minecraft:soul_sand_valley", "minecraft:basalt_deltas",
    "minecraft:the_end", "minecraft:end_highlands", "minecraft:end_midlands",
    "minecraft:end_barrens", "minecraft:small_end_islands",
]


def detect_spawn_biome(rcon, dim, probes, surface_y=None):
    """Probe the biome a player would actually see at spawn: at the surface
    when there is one (biomes are 3D — y=64 under a mountain can be a cave
    biome), with sensible fallbacks for voids and open caves. Filter probes
    first; if none hit, identify against the common list so the row still
    says what the spawn actually was."""
    ys = []
    if surface_y is not None:
        ys.append(surface_y + 1)
    ys += [64, -32]
    for y in ys:
        for biome in probes:
            if test_ok(rcon.cmd(f"execute in {dim} if biome 0 {y} 0 {biome}")):
                return biome
    y = ys[0]
    for biome in COMMON_BIOMES:
        if biome in probes:
            continue
        if test_ok(rcon.cmd(f"execute in {dim} if biome 0 {y} 0 {biome}")):
            return biome
    return "unknown"


# ---------------------------------------------------------------------------
# Candidate passes
# ---------------------------------------------------------------------------
def spawn_filter_rejection(nearest_biome, nearest_distance, targets, radius):
    """Explain why a candidate missed its configured spawn gate."""
    required = ", ".join(targets)
    if nearest_biome is None:
        return ("spawn filter: no configured biome found; requires one of "
                f"[{required}] within {radius} blocks")
    return (f"spawn filter: nearest configured biome {nearest_biome} at "
            f"{nearest_distance} blocks; requires one of [{required}] within "
            f"{radius} blocks")


def create_candidate(rcon, worker_id, ns, cand, profile, seed):
    ca = profile["create_args"]
    ctype = ca["type"]
    if ":" in ctype:
        ctype = f'"{ctype}"'  # clone types (ns:path) need brigadier quoting
    cmd = (f"customdim create {cand} {ctype} {seed} "
           f"{ca['noiseSettings'] or '-'} {ca['structureDensity'] or '-'} {ca['biome'] or '-'}")
    out = rcon.cmd(cmd)
    # custom-dimensions has used both messages across released builds. The
    # follow-up seed query below remains the authoritative readiness check.
    if not any(message in out for message in CREATE_SUCCESS_RESPONSES):
        log(worker_id, f"  create failed for {cand}: {out[:160]}")
        return False, f"create command rejected: {out[:160]}"
    # Prove the world answers before measuring.
    for _ in range(12):
        if "Seed" in rcon.cmd(f"execute in {ns}:{cand} run seed"):
            return True, None
        time.sleep(2)
    log(worker_id, f"  {cand} never became queryable")
    return False, "created dimension never became queryable after 24s"


def destroy_candidate(rcon, workdir, ns, cand, keep_files=False):
    rcon.cmd(f"customdim destroy {cand}")
    for _ in range(12):
        response = rcon.cmd(f"execute in {ns}:{cand} run seed")
        if "Unknown" in response or "not found" in response.lower():
            break
        time.sleep(2)
    else:
        log("worker", f"  {cand} remained queryable after queued destroy; preserving files")
        return
    if not keep_files:
        shutil.rmtree(Path(workdir) / "world" / "dimensions" / ns / cand, ignore_errors=True)


ASYNC_LOCATE_POLL_INTERVAL = 3  # seconds between locate-result polls
ASYNC_LOCATE_TIMEOUT = 130  # max wait for all pending locates (> mod-side 120s)


def _parse_async_result(output):
    """Parse a locate-result response. Returns (status, distance, x, y, z).
    status is one of: 'pending', 'done', 'not_found', 'timed_out', 'error', 'unknown'."""
    if not output or "locate:" not in output:
        return ("error", -1, 0, 0, 0)
    parts = output.split()
    # format: "locate:<uuid> <status> [distance x y z]"
    if len(parts) < 2:
        return ("error", -1, 0, 0, 0)
    status = parts[1]
    if status == "done" and len(parts) >= 6:
        try:
            return ("done", int(parts[2]), int(parts[3]), int(parts[4]), int(parts[5]))
        except (ValueError, IndexError):
            return ("done", -1, 0, 0, 0)
    return (status, -1, 0, 0, 0)


def _poll_async_locates(rcon, worker_id, pending, cap=None):
    """Poll all pending async locates until done or timed out.
    pending: {uuid_str: short_name}. Returns [(short_name, distance), ...]."""
    results = []
    remaining = dict(pending)
    deadline = time.time() + ASYNC_LOCATE_TIMEOUT
    while remaining and time.time() < deadline:
        if should_stop():
            break
        done_this_round = []
        for uuid_str, sname in remaining.items():
            try:
                out = rcon.cmd(f"customdim locate-result {uuid_str}")
            except (RconTimeout, RconClosed):
                # RCON failure — record -1 for all remaining and bail
                for sn in remaining.values():
                    results.append((sn, -1))
                return results
            status, dist, _x, _y, _z = _parse_async_result(out)
            if status == "pending":
                continue
            if status == "done":
                if cap is not None and dist > cap:
                    dist = -1
                results.append((sname, dist))
            elif status == "not_found":
                results.append((sname, -1))
            else:
                results.append((sname, -1))
            done_this_round.append(uuid_str)
        for u in done_this_round:
            del remaining[u]
        if remaining:
            time.sleep(ASYNC_LOCATE_POLL_INTERVAL)
    # Anything still pending after deadline gets -1
    for sname in remaining.values():
        log(worker_id, f"  async locate timed out for {sname}")
        results.append((sname, -1))
    return results


MAX_CONSECUTIVE_TIMEOUTS = 3

# ---------------------------------------------------------------------------
# Server-side biome grid: one RCON call samples BiomeSource.getBiome() at a
# grid of points (no chunk generation, uses the full noise router including
# mod transforms). Replaces ALL individual biome locate calls.
# ---------------------------------------------------------------------------
def _sample_biome_grid(rcon, dim, workdir, radius=768, step=64):
    """Call sample-biome-grid on the server; read the CSV result.
    Returns {(x, z): biome_id} or None on failure."""
    try:
        out = rcon.cmd(f"customdim sample-biome-grid {dim} {radius} {step}")
        if not out or "grid" not in out:
            return None
    except (RconTimeout, RconClosed):
        return None
    grid_path = Path(workdir) / "config" / "custom-dimensions" / "biome_grid.csv"
    if not grid_path.exists():
        return None
    grid = {}
    for line in grid_path.read_text().splitlines():
        parts = line.split(",", 2)
        if len(parts) == 3:
            try:
                grid[(int(parts[0]), int(parts[1]))] = parts[2]
            except ValueError:
                continue
    return grid if grid else None


def _grid_spawn_filter(grid, namesake_biomes, radius):
    """Check if any namesake biome exists in the grid within radius.
    Returns (biome_id, distance, x, z) or (None, None, None, None)."""
    import math
    best = None
    best_dist_sq = float('inf')
    for (x, z), biome in grid.items():
        if biome in namesake_biomes:
            dist_sq = x * x + z * z
            if dist_sq <= radius * radius and dist_sq < best_dist_sq:
                best_dist_sq = dist_sq
                best = (biome, int(math.sqrt(dist_sq)), x, z)
    return best if best else (None, None, None, None)


def _grid_locate_biome(grid, biome_id):
    """Find nearest instance of biome_id in the grid from origin.
    Returns distance or -1."""
    import math
    best = -1
    for (x, z), b in grid.items():
        if b == biome_id:
            dist = int(math.sqrt(x * x + z * z))
            if best < 0 or dist < best:
                best = dist
    return best


# ---------------------------------------------------------------------------
# Pure-Python structure placement (no server needed)
# ---------------------------------------------------------------------------
_STRUCTURE_SETS = None
_STRUCT_TO_SETS = None


def _load_structure_sets_once(seedtest_path=None):
    """Load structure set configs from mod JARs + vanilla server jar (once)."""
    global _STRUCTURE_SETS, _STRUCT_TO_SETS
    if _STRUCTURE_SETS is not None:
        return
    from structure_placement import load_structure_sets
    seedtest = Path(seedtest_path) if seedtest_path else Path(".seedtest")
    extract_dir = seedtest / ".structure_sets"
    if not extract_dir.exists():
        import zipfile
        extract_dir.mkdir(parents=True, exist_ok=True)
        base = seedtest / "base"
        for jar_path in list((base / "mods").glob("*.jar")) + list((base / "versions").rglob("*.jar")):
            try:
                with zipfile.ZipFile(jar_path) as zf:
                    for name in zf.namelist():
                        if "worldgen/structure_set" in name and name.endswith(".json"):
                            zf.extract(name, extract_dir)
            except (zipfile.BadZipFile, OSError):
                pass
        # Also from datapacks
        dp = base / "world-datapacks-template"
        if dp.is_dir():
            for f in dp.rglob("*/worldgen/structure_set/*.json"):
                rel = f.relative_to(dp)
                dest = extract_dir / "data" / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(f, dest)
    _STRUCTURE_SETS = load_structure_sets(str(extract_dir))
    _STRUCT_TO_SETS = {}
    for set_id, cfg in _STRUCTURE_SETS.items():
        for s in cfg["structures"]:
            _STRUCT_TO_SETS.setdefault(s["id"], []).append(set_id)


def _find_structure_set(structure_id):
    """Find the structure set config for a given structure locate ID.
    Handles tags (#minecraft:village -> find any set with a village_* structure)
    and direct IDs."""
    _load_structure_sets_once()
    clean_id = structure_id.lstrip("#")
    # Direct match: structure ID in a set
    if clean_id in _STRUCT_TO_SETS:
        set_id = _STRUCT_TO_SETS[clean_id][0]
        return _STRUCTURE_SETS[set_id]
    # Tag match: #minecraft:village -> look for sets containing village_*
    if structure_id.startswith("#"):
        tag_path = clean_id.split(":")[-1] if ":" in clean_id else clean_id
        for sid, cfg in _STRUCTURE_SETS.items():
            for s in cfg["structures"]:
                if tag_path in s["id"]:
                    return cfg
    # Set ID match: the battery might already use set IDs
    if clean_id in _STRUCTURE_SETS:
        return _STRUCTURE_SETS[clean_id]
    return None


def _resolve_candidate_seed(rcon, dim):
    """Get the seed of the candidate dimension via RCON."""
    import re
    try:
        out = rcon.cmd(f"execute in {dim} run seed")
        m = re.search(r'\[(-?\d+)\]', out)
        return int(m.group(1)) if m else None
    except (RconTimeout, RconClosed):
        return None


def measure_candidate(rcon, worker_id, container, dim, profile, err_before,
                      force_accept=False, spawn_radius=48, args_workdir=None):
    """Measure one candidate world (dim = full dimension id). The spawn
    filter rejects ONLY when no namesake biome exists at all (locate returns
    not-found for every configured biome). Seeds with a far biome are
    accepted and fully measured — spawn proximity is a SCORING signal, not a
    gate. Individual RCON timeouts record -1 and continue; only consecutive
    failures (container genuinely sick) abort the seed."""
    rows = []
    fam = profile["family"] or "overworld"
    lo, hi = profile.get("height_range") or HEIGHT_RANGE[fam]
    consecutive_timeouts = 0

    def safe_cmd(cmd):
        """Run an RCON command, reconnecting on timeout. Returns the response
        or None if the call timed out (caller decides what -1/skip means)."""
        nonlocal rcon, consecutive_timeouts
        try:
            result = rcon.cmd(cmd)
            consecutive_timeouts = 0
            return result
        except (RconTimeout, RconClosed) as exc:
            consecutive_timeouts += 1
            log(worker_id, f"  RCON {type(exc).__name__} on: {cmd[:80]}... "
                           f"({consecutive_timeouts}/{MAX_CONSECUTIVE_TIMEOUTS})")
            if consecutive_timeouts >= MAX_CONSECUTIVE_TIMEOUTS:
                raise
            try:
                rcon.close()
                rcon.connect()
            except (RconTimeout, RconClosed):
                raise exc
            return None

    def safe_locate(cmd, cap=None):
        out = safe_cmd(cmd)
        return parse_distance(out, cap=cap) if out is not None else -1

    spawn = "unknown"
    spawn_coords = None
    cap = profile.get("locate_cap")

    # Server-side biome grid: one RCON call samples BiomeSource.getBiome()
    # at a grid of points. Uses the server's full noise router (including
    # mod transforms like Terralith splines) — accurate for ALL dimension
    # families. No chunk generation needed, ~1s server-side.
    biome_grid = _sample_biome_grid(rcon, dim, args_workdir,
                                    radius=spawn_radius, step=64)

    if profile["namesake"]:
        best_b, best_d, best_x, best_z = None, None, None, None
        if biome_grid:
            namesake_set = set(profile["namesake"])
            best_b, best_d, best_x, best_z = _grid_spawn_filter(
                biome_grid, namesake_set, spawn_radius)
        else:
            # Fallback: RCON async locate (original path)
            import re as _re
            pending_biomes = {}
            for b in profile["namesake"]:
                out = safe_cmd(f"customdim locate biome {dim} {b} 60")
                if out and "locate:" in out and "pending" in out:
                    m = _re.search(r'locate:([0-9a-f-]{36})\s+pending', out)
                    if m:
                        pending_biomes[m.group(1)] = b
            if pending_biomes:
                remaining = dict(pending_biomes)
                deadline = time.time() + 70
                while remaining and time.time() < deadline:
                    if should_stop():
                        break
                    done_this_round = []
                    for uuid_str, biome_id in remaining.items():
                        try:
                            out = rcon.cmd(f"customdim locate-result {uuid_str}")
                        except (RconTimeout, RconClosed):
                            remaining.clear()
                            break
                        status, dist, x, _y, z = _parse_async_result(out)
                        if status == "pending":
                            continue
                        done_this_round.append(uuid_str)
                        if status == "done":
                            if cap is not None and dist > cap:
                                dist = -1
                            if dist >= 0 and (best_d is None or dist < best_d):
                                best_b, best_d = biome_id, dist
                                best_x, best_z = x, z
                    for u in done_this_round:
                        del remaining[u]
                    if remaining:
                        time.sleep(ASYNC_LOCATE_POLL_INTERVAL)
        if best_d is not None and best_d <= 48:
            spawn = best_b
            spawn_coords = [best_x, 0, best_z]
        elif not force_accept and best_d is None:
            reason = spawn_filter_rejection(
                None, None, profile["namesake"], spawn_radius)
            rows.append(("spawn_biome", "unknown"))
            rows.append(("rejected", 1))
            return rows, reason, False
        else:
            if best_x is not None:
                spawn_coords = [best_x, 0, best_z]
        if best_d is not None:
            rows.append(("spawn_filter_dist", best_d))
    if spawn_coords:
        rows.append(("spawn_x", spawn_coords[0]))
        rows.append(("spawn_z", spawn_coords[2]))

    # Spawn biome from grid (no chunk gen needed)
    if spawn == "unknown" and biome_grid and (0, 0) in biome_grid:
        spawn = biome_grid[(0, 0)]

    safe_cmd(f"execute in {dim} run forceload add 0 0")
    if not wait_loaded(rcon, dim, 0, 0):
        safe_cmd(f"execute in {dim} run forceload remove 0 0")
        rows.append(("spawn_biome", spawn))
        rows.append(("rejected", 2))
        return rows, "spawn probe timed out after 90s", False
    surface = column_height(rcon, dim, 0, 0, lo, hi)
    if spawn == "unknown":
        spawn = detect_spawn_biome(rcon, dim, profile["spawn_probes"], surface)
    rows.append(("spawn_biome", spawn))
    safe_cmd(f"execute in {dim} run forceload remove 0 0")

    # Structure placement via pure Python — no server thread, no RCON.
    from structure_placement import nearest_structure
    dim_seed = _resolve_candidate_seed(rcon, dim)
    for sname, sid, _band, _kind in profile["battery"]:
        set_cfg = _find_structure_set(sid)
        if set_cfg and dim_seed is not None:
            result = nearest_structure(
                dim_seed, set_cfg["spacing"], set_cfg["separation"],
                set_cfg["salt"], spread_type=set_cfg.get("spread_type", "linear"),
                frequency=set_cfg.get("frequency", 1.0))
            dist = result[0] if result else -1
            if cap is not None and dist > cap:
                dist = -1
        else:
            dist = -1
        rows.append((f"structure_{sname}_dist", dist))

    # Variety biome distances from the grid (already sampled, no extra RCON).
    # For biomes beyond the grid radius, a wider grid would be needed —
    # record -1 (not found within grid) rather than falling back to slow
    # RCON locate which was the original bottleneck.
    for biome in profile["variety_biomes"]:
        if should_stop():
            return rows, spawn, False
        if biome_grid:
            dist = _grid_locate_biome(biome_grid, biome)
        else:
            dist = -1
        rows.append((f"biome_{biome}_dist", dist))

    fy, fluid = FLUID_CHECK[fam]
    pitch = profile["grid_pitch"]
    for r in range(3):
        for c in range(3):
            if should_stop():
                return rows, spawn, False
            x, z = (c - 1) * pitch, (r - 1) * pitch
            safe_cmd(f"execute in {dim} run forceload add {x} {z}")
            if not wait_loaded(rcon, dim, x, z, timeout=60):
                safe_cmd(f"execute in {dim} run forceload remove {x} {z}")
                continue
            h = column_height(rcon, dim, x, z, lo, hi)
            if h is not None:
                rows.append((f"height_r{r}c{c}", h))
            out = safe_cmd(f"execute in {dim} if block {x} {fy} {z} {fluid}")
            wet = test_ok(out) if out else False
            rows.append((f"water_r{r}c{c}", 1 if wet else 0))
            safe_cmd(f"execute in {dim} run forceload remove {x} {z}")

    rows.append(("errors", max(0, error_count(container) - err_before)))
    return rows, spawn, True



# ---------------------------------------------------------------------------
# Render via unmined-cli — flat top-down map, runs natively on macOS (~1s).
# Replaces the broken BlueMap Docker approach that produced unusable tiles.
# ---------------------------------------------------------------------------
_UNMINED_CLI = None


def _find_unmined_cli():
    """Auto-detect the unmined-cli binary. Checks UNMINED_CLI env, then the
    standard install location under ~/.unmined/."""
    global _UNMINED_CLI
    if _UNMINED_CLI is not None:
        return _UNMINED_CLI if _UNMINED_CLI != "" else None
    env = os.environ.get("UNMINED_CLI")
    if env and Path(env).is_file():
        _UNMINED_CLI = env
        return _UNMINED_CLI
    home = Path.home()
    for candidate in sorted(home.glob(".unmined/unmined-cli_*/unmined-cli"), reverse=True):
        if candidate.is_file():
            _UNMINED_CLI = str(candidate)
            return _UNMINED_CLI
    _UNMINED_CLI = ""
    return None


NETHER_FAMILIES = {"nether", "nether_islands"}


def render_candidate(rcon, worker_id, workdir, seedtest, container, ns, cand, dim_name, seed,
                     family="overworld", render_size=512):
    """Render a candidate via unmined-cli (flat top-down map).
    MC server forceloads + saves chunks around spawn, then unmined-cli
    renders the region files directly on the host — no Docker needed,
    ~1s per render. Works for all 74+ dimension families."""
    dim = f"{ns}:{cand}"
    out_png = Path(seedtest) / "renders" / dim_name / f"{seed}.png"
    out_png.parent.mkdir(parents=True, exist_ok=True)

    unmined = _find_unmined_cli()
    if not unmined:
        log(worker_id, "  unmined-cli not found — skipping render "
            "(install to ~/.unmined/ or set UNMINED_CLI)")
        return False

    render_size = int(os.environ.get("ROLL_RENDER_SIZE", str(render_size)))
    half = render_size // 2

    # tick freeze blocks chunk generation. Sprint clears the accumulated
    # tick debt, unfreeze enables normal ticking for chunk generation.
    rcon.cmd("tick sprint 200")
    time.sleep(2)
    rcon.cmd("tick unfreeze")
    # Prime with center chunk, then forceload the full area.
    rcon.cmd(f"execute in {dim} run forceload add 0 0")
    if not wait_loaded(rcon, dim, 0, 0, timeout=45):
        log(worker_id, f"  center chunk never loaded for {cand}")
        rcon.cmd(f"execute in {dim} run forceload remove all")
        return False
    rcon.cmd(f"execute in {dim} run forceload add {-half} {-half} {half - 1} {half - 1}")
    time.sleep(20)
    rcon.cmd("save-all flush")
    time.sleep(4)

    world_path = Path(workdir) / "world"
    region_dir = world_path / "dimensions" / ns / cand / "region"
    # macOS Docker VirtioFS can delay bind-mount sync — pull from container.
    if not (region_dir.is_dir() and any(region_dir.glob("*.mca"))):
        r = docker("exec", container, "ls",
                   f"/data/world/dimensions/{ns}/{cand}/region/", check=False)
        if r.returncode == 0 and ".mca" in (r.stdout or ""):
            region_dir.mkdir(parents=True, exist_ok=True)
            docker("cp", f"{container}:/data/world/dimensions/{ns}/{cand}/region/.",
                   str(region_dir), check=False)
            log(worker_id, f"  region files pulled from container (VirtioFS lag)")
    if not (region_dir.is_dir() and any(region_dir.glob("*.mca"))):
        log(worker_id, f"  no region files for {cand} — skipping render")
        rcon.cmd(f"execute in {dim} run forceload remove all")
        return False

    zoom = int(os.environ.get("ROLL_RENDER_ZOOM", "0"))
    world_path = str(world_path)

    cmd = [unmined, "image", "render",
           f"--world={world_path}",
           f"--output={out_png}",
           f"--dimension={dim}",
           f"--area=b({-half},{-half},{render_size},{render_size})",
           f"--zoom={zoom}",
           "--trim",
           "--shadows=true",
           "--textures=true",
           "-c",
           "--log-level=warning"]

    if family in NETHER_FAMILIES:
        cmd.append("--topY=127")

    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        if r.returncode == 0 and out_png.exists() and out_png.stat().st_size > 500:
            kb = out_png.stat().st_size // 1024
            log(worker_id, f"  render saved: {out_png.name} ({kb}KB)")
            rcon.cmd(f"execute in {dim} run forceload remove all")
            return True
        stderr = (r.stderr or r.stdout or "")[:200]
        log(worker_id, f"  unmined-cli exit {r.returncode}: {stderr}")
    except subprocess.TimeoutExpired:
        log(worker_id, "  unmined-cli timed out (60s)")
    except OSError as e:
        log(worker_id, f"  unmined-cli error: {e}")

    rcon.cmd(f"execute in {dim} run forceload remove all")
    return False


# ---------------------------------------------------------------------------
# Worker main loop
# ---------------------------------------------------------------------------
def prepare_boot_dir(workdir, mvconfig, seedtest):
    workdir = Path(workdir)
    shutil.rmtree(workdir / "world", ignore_errors=True)
    (workdir / "world").mkdir(parents=True, exist_ok=True)
    (workdir / "server.properties").unlink(missing_ok=True)

    cfg = workdir / "config"
    cfg.mkdir(exist_ok=True)
    # v4 config: minimal settings.json + empty dimensions dir. The mod
    # reads config/custom-dimensions/ at boot; SEED_ROLL_MODE skips
    # dimension creation, so only the namespace matters.
    cd = cfg / "custom-dimensions"
    shutil.rmtree(cd, ignore_errors=True)
    (cd / "dimensions").mkdir(parents=True)
    settings = json.loads(Path(mvconfig).read_text()) if Path(mvconfig).exists() else {}
    (cd / "settings.json").write_text(json.dumps(
        {"namespace": settings.get("namespace", "adventure")}, indent=2) + "\n")
    # Legacy monolith must not exist — the mod would warn.
    (cfg / "multiverse_config.json").unlink(missing_ok=True)

    c2me = cfg / "c2me.toml"
    if c2me.is_dir():
        shutil.rmtree(c2me)
    c2me.write_text(
        "[vanillaWorldGenOptimizations]\n\tuseDensityFunctionCompiler = false\n")

    bm = cfg / "bluemap"
    shutil.rmtree(bm, ignore_errors=True)
    (bm / "maps").mkdir(parents=True)
    (bm / "core.conf").write_text('accept-download: true\ndata: "bluemap"\nmetrics: false\n')
    (bm / "webserver.conf").write_text("enabled: false\n")
    shutil.rmtree(cfg / "DistantHorizons", ignore_errors=True)

    dp_template = workdir / "world-datapacks-template"
    if dp_template.is_dir():
        dst = workdir / "world" / "datapacks"
        dst.mkdir(parents=True)
        shutil.copytree(dp_template, dst, dirs_exist_ok=True)


def boot(wid, container, workdir, memory, seed="1"):
    """Boot with retries; returns a live Rcon (frozen ticks) or None."""
    rcon = None
    for attempt in range(1, 4):
        try:
            port = start_container(container, workdir, memory, seed=seed)
            rcon = wait_for_rcon(wid, container, port)
        except Exception as e:  # noqa: BLE001 — docker races must not kill the worker
            log(wid, f"boot attempt {attempt} error: {type(e).__name__}: {e}")
            rcon = None
            time.sleep(5)
        if rcon is not None:
            break
        log(wid, f"boot attempt {attempt} failed{' — retrying' if attempt < 3 else ''}")
    if rcon is not None:
        rcon.cmd("tick freeze")
        rcon.cmd("gamerule doMobSpawning false")
        rcon.cmd("gamerule doDaylightCycle false")
        rcon.cmd("gamerule spawnChunkRadius 0")
        # Prime worldgen caches. First customdim create per type lazily
        # initializes noise routers + structure registries. Failures are
        # non-fatal — per-call resilience in measure_candidate handles
        # slow locates if the warmup doesn't complete.
        saved_timeout = rcon.timeout
        rcon.timeout = 45
        if rcon.sock:
            rcon.sock.settimeout(45)
        warmed = 0
        for wtype in ("overworld", "nether", "end", '"paradise_lost:paradise_lost"'):
            try:
                rcon.cmd(f"customdim create _warmup {wtype} 1 - - -")
                time.sleep(1)
                rcon.cmd("customdim destroy _warmup")
                time.sleep(1)
                warmed += 1
            except (RconTimeout, RconClosed):
                log(wid, f"  warmup {wtype} timed out — skipping")
                try:
                    rcon.close()
                    rcon = Rcon("127.0.0.1", port, "seedroll")
                    rcon.timeout = 180
                    rcon.connect()
                    if rcon.sock:
                        rcon.sock.settimeout(180)
                except (RconTimeout, RconClosed, OSError):
                    log(wid, f"  reconnect failed after {wtype} warmup — continuing with {warmed} types")
                    break
        log(wid, f"  worldgen caches primed ({warmed}/4 types)")
        rcon.timeout = saved_timeout
        if rcon.sock:
            rcon.sock.settimeout(saved_timeout)
    return rcon


STOP_FILE = None  # set in main from --seedtest


def should_stop():
    return STOP_FILE is not None and STOP_FILE.exists()


def load_seen_seeds(seedtest, base_config_path=None):
    """Seeds already banked (any target) — never re-roll them. Sources:
    worker CSV spools + legacy measurements.csv, and (v4 directory mode)
    the candidate store's measured/rejected/abandoned seeds."""
    seen = set()
    for f in Path(seedtest).glob("*.csv"):
        try:
            for line in f.read_text().splitlines():
                parts = line.split(",", 2)
                if len(parts) >= 2:
                    seen.add(parts[1])
        except OSError:
            pass
    if base_config_path and Path(base_config_path).is_dir():
        import candidates
        seen |= candidates.seen_seeds(base_config_path)
    return seen


def fresh_seed(seen):
    import struct as _s
    while True:
        s = _s.unpack("<q", os.urandom(8))[0]
        if str(s) not in seen:
            seen.add(str(s))
            return s


def roll_one(args, wid, container, rcon, ns, dim_name, profile, seed, cand,
             csv_fh, gate=True, spawn_radius=48):
    """create -> gate -> measure(+render) -> destroy for one candidate.
    Returns (accepted, outcome). Infrastructure failures are not seed misses."""
    try:
        err_before = error_count(container)
        created, reason = create_candidate(rcon, wid, ns, cand, profile, seed)
        if not created:
            return False, reason
        rows, spawn, accepted = measure_candidate(
            rcon, wid, container, f"{ns}:{cand}", profile, err_before,
            force_accept=not gate, spawn_radius=spawn_radius,
            args_workdir=args.workdir)
        for metric, value in rows:
            csv_fh.write(f"{dim_name},{seed},{metric},{value}\n")
        csv_fh.flush()
        if accepted and args.mode in ("render", "measure+render"):
            render_candidate(rcon, wid, args.workdir, args.seedtest,
                             container, ns, cand, dim_name, seed,
                             family=profile["family"] or "overworld")
        destroy_candidate(rcon, args.workdir, ns, cand)
        return accepted, spawn
    except RconTimeout as exc:
        diagnostic = capture_rcon_diagnostic(
            args, wid, container, dim_name, seed, exc)
        log(wid, f"  RCON timeout for {dim_name} seed {seed}; diagnostic: {diagnostic.name}")
        rcon.close()
        return False, "rcon-timeout"
    except RconClosed as exc:
        diagnostic = capture_rcon_diagnostic(
            args, wid, container, dim_name, seed, exc)
        log(wid, f"  RCON closed for {dim_name} seed {seed}; diagnostic: {diagnostic.name}")
        rcon.close()
        return False, "rcon-closed"
    except Exception as e:  # noqa: BLE001 — a candidate must never kill the worker
        log(wid, f"  ERROR {dim_name} seed {seed}: {type(e).__name__}: {e} — recovering")
        if rcon is not None:
            rcon.close()
            try:
                destroy_candidate(rcon, args.workdir, ns, cand)
            except Exception:  # noqa: BLE001
                pass
        return False, "error"


def run_dimension_jobs(args, wid, container, base_config, csv_fh):
    """INDEFINITE roll: cycle this worker's dimensions forever, one accepted
    candidate per dimension per cycle, unbounded attempts, every event
    reported live. Ctrl+C (via the orchestrator's stop file / SIGTERM)
    finalises with whatever exists. '@worlds' in the rotation rolls a
    coupled world-seed slot (overworld gate -> nether + end clones)."""
    ns = base_config.get("namespace", "adventure")
    dims_by_name = {d["name"]: d for d in base_config["dimensions"]}
    worlds = {w["name"]: w for w in base_config.get("worlds", [])}
    difficulty = load_difficulty(args.base_config)

    rotation = [ln.strip() for ln in Path(args.manifest).read_text().splitlines() if ln.strip()]
    if not rotation:
        log(wid, "nothing to do")
        return 0

    seen = load_seen_seeds(args.seedtest, args.base_config)
    prepare_boot_dir(args.workdir, args.mvconfig, args.seedtest)
    log(wid, f"rolling {', '.join(rotation)} — indefinitely (Ctrl+C to finish)")
    rcon = boot(wid, container, args.workdir, args.memory)
    if rcon is None:
        return 1

    accepted_total = 0
    cycle = 0
    counter = 0
    rcon_failures = 0
    while not should_stop():
        cycle += 1
        for item in rotation:
            if should_stop():
                break
            if rcon is None or not container_running(container):
                log(wid, "container died — rebooting (unlimited retries)")
                rcon = boot(wid, container, args.workdir, args.memory)
                if rcon is None:
                    time.sleep(20)
                    continue

            if item == "@worlds":
                accepted, rcon = roll_world_slot(
                    args, wid, container, rcon, ns, worlds, difficulty,
                    base_config, seen, csv_fh, cycle)
                accepted_total += accepted
                continue

            if item.startswith("@world:"):
                # One REAL world (overworld/nether/end/paradise_lost) rolled
                # independently as a runtime clone — a fake_<world> candidate
                # generates identically to that world booted on the seed.
                wname = item.split(":", 1)[1]
                world = worlds.get(wname)
                if world is None:
                    log(wid, f"  unknown world '{wname}' — skipping")
                    continue
                profile = build_profile(world, base_config, difficulty)
                profile["create_args"] = {"type": world_clone_type(world), "noiseSettings": None,
                                          "structureDensity": None, "biome": None}
                target, cand_base = wname, f"fake_{wname}"
            else:
                profile = build_profile(dims_by_name[item], base_config, difficulty)
                target, cand_base = item, item
            t0 = time.time()
            misses = 0
            while not should_stop():
                counter += 1
                seed = fresh_seed(seen)
                cand = f"{cand_base}__r{counter:05d}"
                radius, _force = spawn_gate_for(misses)
                ok, spawn = roll_one(args, wid, container, rcon, ns, target,
                                     profile, seed, cand, csv_fh,
                                     spawn_radius=radius)
                if ok:
                    rcon_failures = 0
                    accepted_total += 1
                    log(wid, f"ACCEPTED #{accepted_total} {target} seed {seed} "
                             f"spawn={spawn} (+{misses} rejected, {int(time.time() - t0)}s)")
                    break
                if rcon_failure_reason(spawn):
                    rcon_failures += 1
                    record_abandoned_seed(args, wid, target, seed, spawn)
                    log(wid, f"  abandoned {target} seed {seed} ({spawn}; not scored)")
                    rcon, rcon_failures = recover_rcon(
                        args, wid, container, rcon, rcon_failures, spawn)
                    if rcon is None:
                        log(wid, "worker recovery failed — retrying after cooldown")
                        time.sleep(worker_backoff(max(1, rcon_failures)))
                        break
                    continue
                misses += 1
                log(wid, f"  rejected {target} seed {seed} ({spawn})")
                if misses >= SKIP_AFTER_MISSES:
                    log(wid, f"  skipping {target} after {misses} consecutive "
                             f"rejections — will retry next cycle")
                    break
                # A dead container mid-slot must reboot HERE — the rotation-
                # level check only runs between dimensions.
                if not container_running(container):
                    log(wid, "container died mid-slot — rebooting")
                    rcon = boot(wid, container, args.workdir, args.memory)
                    if rcon is None:
                        time.sleep(20)
                        break
    log(wid, f"stopping: {accepted_total} accepted candidates this session")
    return 0


def run_shortlist_jobs(args, wid, container, base_config):
    """Render finite, already-scored jobs from `target|seed` manifest rows."""
    ns = base_config.get("namespace", "adventure")
    dims = {d["name"]: d for d in base_config["dimensions"]}
    worlds = {w["name"]: w for w in base_config.get("worlds", [])}
    difficulty = load_difficulty(args.base_config)
    jobs = []
    for line in Path(args.manifest).read_text().splitlines():
        if not line.strip():
            continue
        try:
            target, seed = line.split("|", 1)
            jobs.append((target, int(seed)))
        except ValueError:
            log(wid, f"  invalid shortlist job: {line!r}")
    if not jobs:
        return 0

    prepare_boot_dir(args.workdir, args.mvconfig, args.seedtest)
    rcon = boot(wid, container, args.workdir, args.memory)
    if rcon is None:
        return 1
    rcon_failures = 0
    for index, (target, seed) in enumerate(jobs):
        if should_stop():
            break
        if target in dims:
            profile = build_profile(dims[target], base_config, difficulty)
        elif target in worlds:
            world = worlds[target]
            profile = build_profile(world, base_config, difficulty)
            profile["create_args"] = {"type": world_clone_type(world), "noiseSettings": None,
                                      "structureDensity": None, "biome": None}
        else:
            log(wid, f"  unknown shortlist target: {target}")
            continue
        cand = f"shortlist_{target}__r{index:05d}"
        try:
            created, reason = create_candidate(rcon, wid, ns, cand, profile, seed)
            if not created:
                log(wid, f"  shortlist render skipped {target} seed {seed} ({reason})")
                continue
            render_candidate(rcon, wid, args.workdir, args.seedtest, container,
                             ns, cand, target, seed, family=profile["family"] or "overworld")
            destroy_candidate(rcon, args.workdir, ns, cand, keep_files=True)
            rcon_failures = 0
        except (RconTimeout, RconClosed) as exc:
            reason = "rcon-timeout" if isinstance(exc, RconTimeout) else "rcon-closed"
            diagnostic = capture_rcon_diagnostic(
                args, wid, container, target, seed, exc)
            log(wid, f"  shortlist RCON failure for {target} seed {seed}; "
                     f"diagnostic: {diagnostic.name}")
            rcon_failures += 1
            rcon, rcon_failures = recover_rcon(
                args, wid, container, rcon, rcon_failures, reason)
            if rcon is None:
                return 1
        except Exception as exc:  # noqa: BLE001 — one render must not abort the batch
            log(wid, f"  shortlist render error {target} seed {seed}: "
                     f"{type(exc).__name__}: {exc}")
    return 0


# Clone type per real world: native types for the vanilla three, generic
# dimension-id clone for static mod dimensions (paradise_lost etc.).
WORLD_CLONE_TYPES = {"minecraft:overworld": "overworld",
                     "minecraft:the_nether": "nether",
                     "minecraft:the_end": "end"}


def world_clone_type(world):
    return WORLD_CLONE_TYPES.get(world.get("dimensionId"), world.get("dimensionId"))


def roll_world_slot(args, wid, container, rcon, ns, worlds, difficulty,
                    base_config, seen, csv_fh, cycle):
    """One coupled world-seed candidate INSIDE the long-lived container:
    the shared world seed is rolled as runtime clones (an overworld-type
    dimension with seed S generates identically to a world booted with
    SEED=S), so world attempts parallelise with everything else. The
    overworld clone's spawn filter gates the seed; the nether, end and
    paradise_lost clones (generic ns:path clone type — Paradise Lost is a
    plain datapack noise dimension) are measured on the same seed."""
    order = [("overworld", "overworld", True), ("the_nether", "nether", False),
             ("the_end", "end", False),
             ("paradise_lost", "paradise_lost:paradise_lost", False)]
    present = [(n, t, g) for n, t, g in order if n in worlds]
    if not present:
        return 0
    t0 = time.time()
    misses = 0
    rcon_failures = 0
    while not should_stop():
        seed = fresh_seed(seen)
        accepted = False
        radius, _force = spawn_gate_for(misses)
        for name, ctype, gate in present:
            entry = dict(worlds[name])
            entry["type"] = ctype  # clone type for customdim
            profile = build_profile(worlds[name], base_config, difficulty)
            profile["create_args"] = {"type": ctype, "noiseSettings": None,
                                      "structureDensity": None, "biome": None}
            cand = f"world_{name}__c{cycle:04d}"
            ok, spawn = roll_one(args, wid, container, rcon, ns, name,
                                 profile, seed, cand, csv_fh,
                                 gate=gate, spawn_radius=radius)
            if ok:
                rcon_failures = 0
                accepted = True
                continue
            if rcon_failure_reason(spawn):
                rcon_failures += 1
                record_abandoned_seed(args, wid, name, seed, spawn)
                log(wid, f"  abandoned world seed {seed} ({spawn}; not scored)")
                rcon, rcon_failures = recover_rcon(
                    args, wid, container, rcon, rcon_failures, spawn)
                if rcon is None:
                    return 0, rcon
                break
            if gate:
                misses += 1
                log(wid, f"  rejected world seed {seed} (overworld {spawn})")
            else:
                log(wid, f"  incomplete world seed {seed} ({name} {spawn})")
            accepted = False
            break
        if accepted:
            log(wid, f"ACCEPTED world seed {seed} "
                     f"(+{misses} rejected, {int(time.time() - t0)}s)")
            return 1, rcon
    return 0, rcon


def run_world_jobs(args, wid, container, base_config, csv_fh):
    """The boot stream: INDEFINITELY roll world seeds with a fresh BOOT per
    candidate (SEED=<s>) measuring ALL configured worlds — this is the only
    way paradise_lost (a static mod dimension) gets per-seed data. Prefers
    seeds the clone stream already accepted for the overworld but that lack
    paradise rows, so the combined pick converges on fully-measured seeds."""
    difficulty = load_difficulty(args.base_config)
    worlds = base_config.get("worlds", [])
    if not worlds:
        log(wid, "no worlds configured")
        return 0
    profiles = [(w, build_profile(w, base_config, difficulty)) for w in worlds]
    seen = load_seen_seeds(args.seedtest, args.base_config)

    def preferred_seeds():
        """Overworld-accepted seeds without paradise_lost rows."""
        import csv as _csv
        ow, para = set(), set()
        for f in Path(args.seedtest).glob("*.csv"):
            try:
                with open(f, newline="") as fh:
                    for row in _csv.reader(fh):
                        if len(row) != 4:
                            continue
                        if row[0] == "overworld" and row[2] == "errors":
                            ow.add(row[1])
                        elif row[0] == "paradise_lost":
                            para.add(row[1])
            except OSError:
                pass
        return [int(s) for s in ow - para]

    boots = 0
    accepted = 0
    while not should_stop():
        queue = preferred_seeds()
        seed = queue[0] if queue else fresh_seed(seen)
        gated = not queue  # preferred seeds already passed the overworld gate
        t0 = time.time()
        boots += 1
        prepare_boot_dir(args.workdir, args.mvconfig, args.seedtest)
        rcon = boot(wid, container, args.workdir, args.memory, seed=str(seed))
        if rcon is None:
            log(wid, "world boot failed — retrying with a fresh seed")
            time.sleep(10)
            continue
        try:
            for world, profile in profiles:
                dim = world["dimensionId"]
                err_before = error_count(container)
                rows, spawn, ok = measure_candidate(
                    rcon, wid, container, dim, profile, err_before,
                    force_accept=(world["name"] != "overworld" or not gated),
                    args_workdir=args.workdir)
                for metric, value in rows:
                    csv_fh.write(f"{world['name']},{seed},{metric},{value}\n")
                csv_fh.flush()
                if not ok and world["name"] == "overworld":
                    log(wid, f"  rejected world seed {seed} (overworld {spawn}, "
                             f"{int(time.time() - t0)}s)")
                    break
            else:
                accepted += 1
                log(wid, f"ACCEPTED world seed {seed} incl. paradise_lost "
                         f"({int(time.time() - t0)}s, boot #{boots})")
        except Exception as e:  # noqa: BLE001
            log(wid, f"  ERROR world seed {seed}: {type(e).__name__}: {e} — recovering")
        finally:
            docker("rm", "-f", container, check=False)
    log(wid, f"stopping: {accepted} world seeds this session")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--worker-id", required=True)
    ap.add_argument("--workdir", required=True)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--mvconfig", required=True)
    ap.add_argument("--base-config", required=True)
    ap.add_argument("--seedtest", required=True)
    ap.add_argument("--mode", choices=["measure", "render", "measure+render", "shortlist", "world"],
                    default="measure")
    ap.add_argument("--memory", default=os.environ.get("ROLL_MEMORY", "6G"))
    args = ap.parse_args()

    wid = args.worker_id
    suffix = {"render": "r", "world": "v"}.get(args.mode, "")
    container = f"seedrollall-{wid}{suffix}"
    # --base-config: the v4 config directory or the legacy monolithic file.
    base_config = load_config(args.base_config)

    global STOP_FILE
    STOP_FILE = Path(args.seedtest) / ".stop"
    _load_structure_sets_once(args.seedtest)
    log(wid, "biome grid: server-side sampling via sample-biome-grid")
    import signal
    signal.signal(signal.SIGTERM, lambda *_: STOP_FILE.touch())
    signal.signal(signal.SIGINT, lambda *_: STOP_FILE.touch())

    csv_path = Path(args.seedtest) / f"worker-{wid}.csv"
    csv_new = not csv_path.exists()
    try:
        with open(csv_path, "a") as csv_fh:
            if csv_new:
                csv_fh.write("target,seed,metric,value\n")
            if args.mode == "world":
                return run_world_jobs(args, wid, container, base_config, csv_fh)
            if args.mode == "shortlist":
                return run_shortlist_jobs(args, wid, container, base_config)
            return run_dimension_jobs(args, wid, container, base_config, csv_fh)
    finally:
        docker("rm", "-f", container, check=False)


if __name__ == "__main__":
    sys.exit(main())
