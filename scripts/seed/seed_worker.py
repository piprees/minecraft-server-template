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
  structure_<name>_dist  locate structure (-1 = not found)
  biome_<id>_dist        locate biome     (-1 = not found)
  height_rNcM            column surface height at the 3x3 grid point
  water_rNcM             fluid at the reference level (water y62 overworld,
                         lava y32 nether)
  errors                 new filtered ERROR log lines during this candidate

Render mode (--mode render) creates each candidate, forceloads a spawn
square, saves, writes a BlueMap map config, waits for tiles, and copies one
to .seedtest/renders/<dim>/<seed>.png. --mode measure+render does both.

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
from dimension_profiles import build_profile, load_difficulty  # noqa: E402

BOOT_TIMEOUT = int(os.environ.get("RCON_TIMEOUT", "300"))
IMAGE = os.environ.get("ROLL_IMAGE", "itzg/minecraft-server:2026.7.0-java21")

# Column-height search ranges per family (nether capped under the roof).
HEIGHT_RANGE = {"overworld": (-60, 318), "nether": (0, 118), "end": (0, 250)}
FLUID_CHECK = {"overworld": (62, "minecraft:water"), "nether": (32, "minecraft:lava"),
               "end": (62, "minecraft:water")}

ERROR_FILTERS = ("No data fixer registered", "Error loading class",
                 "Block-attached entity at invalid position", "template pool reference")


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
class Rcon:
    # Command responses can take a long time when N servers world-create
    # simultaneously on a contended host — 15s killed a whole 8-worker fleet.
    CMD_TIMEOUT = float(os.environ.get("ROLL_RCON_CMD_TIMEOUT", "120"))

    def __init__(self, host, port, password, timeout=None):
        timeout = timeout if timeout is not None else self.CMD_TIMEOUT
        self.addr = (host, port)
        self.password = password
        self.timeout = timeout
        self.sock = None
        self._id = 0

    def connect(self):
        self.close()
        self.sock = socket.create_connection(self.addr, timeout=self.timeout)
        self._send(3, self.password)
        rid, _typ, _body = self._recv()
        if rid == -1:
            raise ConnectionError("RCON auth failed")

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
        """Run a command, reconnecting once on a broken socket."""
        for attempt in (0, 1):
            try:
                if self.sock is None:
                    self.connect()
                self._send(2, command)
                _rid, _typ, body = self._recv()
                return body
            except (OSError, ConnectionError):
                self.close()
                if attempt:
                    raise
        return ""


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
    mem_gb = int(memory[:-1]) if memory.endswith("G") else 0
    java_mem = f"{mem_gb - 1}G" if mem_gb > 2 else memory
    docker("run", "-d", "--name", name,
           *fabric_pin_env(workdir),
           "--memory", memory,
           "--log-opt", "max-size=5m", "--log-opt", "max-file=1",
           "-p", "127.0.0.1:0:25575",
           "-e", "EULA=TRUE", "-e", "TYPE=FABRIC", "-e", "VERSION=1.21.1",
           "-e", f"SEED={seed}", "-e", f"MEMORY={java_mem}",
           "-e", "ENABLE_RCON=TRUE", "-e", "RCON_PASSWORD=seedroll",
           "-e", "ONLINE_MODE=FALSE", "-e", "ENABLE_AUTOPAUSE=FALSE",
           "-e", "OVERRIDE_SERVER_PROPERTIES=true",
           # Synchronous customdim create + locate can exceed the 60s watchdog
           # tick limit — the watchdog killed a manual test server. This is a
           # throwaway measurement server; disable it.
           "-e", "MAX_TICK_TIME=-1",
           "-e", "SEED_ROLL_MODE=true",
           "-e", "VIEW_DISTANCE=6", "-e", "SIMULATION_DISTANCE=4",
           "-v", f"{workdir}:/data", IMAGE)
    port = docker("port", name, "25575").stdout.strip().rsplit(":", 1)[-1]
    return int(port)


def wait_for_rcon(worker_id, name, port):
    rcon = Rcon("127.0.0.1", port, "seedroll", timeout=10.0)
    start = time.time()
    last = ""
    while time.time() - start < BOOT_TIMEOUT:
        if not container_running(name):
            log(worker_id, "container died during boot")
            return None
        try:
            rcon.connect()
            rcon.cmd("list")
            # Boot probing used a short timeout; commands get the long one.
            rcon.timeout = Rcon.CMD_TIMEOUT
            rcon.sock.settimeout(rcon.timeout)
            log(worker_id, f"server ready ({int(time.time() - start)}s)")
            return rcon
        except (OSError, ConnectionError):
            pass
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


# ---------------------------------------------------------------------------
# Measurement primitives
# ---------------------------------------------------------------------------
def parse_distance(output):
    """'... (123 blocks away)' -> 123, else -1."""
    if not output or "could not" in output.lower():
        return -1
    marker = " blocks away"
    idx = output.find(marker)
    if idx < 0:
        return -1
    start = output.rfind("(", 0, idx)
    try:
        return int(output[start + 1:idx])
    except (ValueError, IndexError):
        return -1


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
def create_candidate(rcon, worker_id, ns, cand, profile, seed):
    ca = profile["create_args"]
    cmd = (f"customdim create {cand} {ca['type']} {seed} "
           f"{ca['noiseSettings'] or '-'} {ca['structureDensity'] or '-'} {ca['biome'] or '-'}")
    out = rcon.cmd(cmd)
    if "Created dimension" not in out:
        log(worker_id, f"  create failed for {cand}: {out[:160]}")
        return False
    # Prove the world answers before measuring.
    for _ in range(12):
        if "Seed" in rcon.cmd(f"execute in {ns}:{cand} run seed"):
            return True
        time.sleep(2)
    log(worker_id, f"  {cand} never became queryable")
    return False


def destroy_candidate(rcon, workdir, ns, cand):
    rcon.cmd(f"customdim destroy {cand}")
    # destroy unloads the world but leaves files — reclaim the disk.
    shutil.rmtree(Path(workdir) / "world" / "dimensions" / ns / cand, ignore_errors=True)


def measure_candidate(rcon, worker_id, container, dim, profile, err_before,
                      force_accept=False):
    """Measure one candidate world (dim = full dimension id). Applies the
    spawn filter FIRST: a spawn that misses it returns immediately with a
    rejection row — the caller re-rolls a fresh seed. Namesakes represent
    SPAWN; the filter is the contract. force_accept measures anyway (the
    last attempt is always kept so narrow filters still bank candidates —
    the namesake component simply scores low)."""
    rows = []
    fam = profile["family"] or "overworld"
    lo, hi = HEIGHT_RANGE[fam]

    # Spawn filter via locate biome — noise-sampled, needs NO chunks, ~1s
    # per probe. Chunk generation per rejection was the fleet's pace killer
    # (~60-90s each under contention); rejections are now nearly free.
    spawn = "unknown"
    if profile["namesake"]:
        best_b, best_d = None, None
        for b in profile["namesake"]:
            d = parse_distance(rcon.cmd(f"execute in {dim} run locate biome {b}"))
            if d >= 0 and (best_d is None or d < best_d):
                best_b, best_d = b, d
            if d == 0:
                break
        if best_d is not None and best_d <= 48:
            spawn = best_b
        elif not force_accept:
            miss = f"{best_b}@{best_d}" if best_b else "unknown"
            rows.append(("spawn_biome", miss))
            rows.append(("rejected", 1))
            return rows, miss, False

    # Accepted (or keeper): generate the spawn chunk and probe properly —
    # keepers need their real spawn identified for partial namesake credit.
    rcon.cmd(f"execute in {dim} run forceload add 0 0")
    if not wait_loaded(rcon, dim, 0, 0):
        rcon.cmd(f"execute in {dim} run forceload remove 0 0")
        rows.append(("spawn_biome", spawn))
        rows.append(("rejected", 2))  # probe timeout, not a filter verdict
        return rows, f"{spawn}(timeout)", False
    surface = column_height(rcon, dim, 0, 0, lo, hi)
    if spawn == "unknown":
        spawn = detect_spawn_biome(rcon, dim, profile["spawn_probes"], surface)
    rows.append(("spawn_biome", spawn))
    rcon.cmd(f"execute in {dim} run forceload remove 0 0")

    for sname, sid, _band, _kind in profile["battery"]:
        d = parse_distance(rcon.cmd(f"execute in {dim} run locate structure {sid}"))
        rows.append((f"structure_{sname}_dist", d))

    for biome in profile["variety_biomes"]:
        d = parse_distance(rcon.cmd(f"execute in {dim} run locate biome {biome}"))
        rows.append((f"biome_{biome}_dist", d))

    fy, fluid = FLUID_CHECK[fam]
    pitch = profile["grid_pitch"]
    for r in range(3):
        for c in range(3):
            x, z = (c - 1) * pitch, (r - 1) * pitch
            rcon.cmd(f"execute in {dim} run forceload add {x} {z}")
            if not wait_loaded(rcon, dim, x, z, timeout=60):
                rcon.cmd(f"execute in {dim} run forceload remove {x} {z}")
                continue  # missing point degrades land_fraction, not heights
            h = column_height(rcon, dim, x, z, lo, hi)
            if h is not None:
                rows.append((f"height_r{r}c{c}", h))
            wet = test_ok(rcon.cmd(f"execute in {dim} if block {x} {fy} {z} {fluid}"))
            rows.append((f"water_r{r}c{c}", 1 if wet else 0))
            rcon.cmd(f"execute in {dim} run forceload remove {x} {z}")

    rows.append(("errors", max(0, error_count(container) - err_before)))
    return rows, spawn, True


# Per-family BlueMap map settings, mirroring BlueMap's stock dimension
# configs: the nether renders pitch black without a roof cut (max-y) and
# ambient light; the end needs ambient light too.
MAP_LIGHTING = {
    "overworld": 'sky-light: 1\nambient-light: 0.1\n',
    "nether": ('sky-light: 0\nambient-light: 0.6\nmax-y: 100\n'
               'remove-caves: true\ncave-detection-uses-block-light: true\n'
               'sky-color: "#290000"\nvoid-color: "#150000"\n'),
    "end": ('sky-light: 0\nambient-light: 0.6\n'
            'sky-color: "#080010"\nvoid-color: "#080010"\n'),
}


def render_candidate(rcon, worker_id, workdir, seedtest, container, ns, cand, dim_name, seed,
                     family="overworld"):
    dim = f"{ns}:{cand}"
    out_png = Path(seedtest) / "renders" / dim_name / f"{seed}.png"
    out_png.parent.mkdir(parents=True, exist_ok=True)

    # Blocks 0..144 sit entirely inside lowres tile 1/x0/z0 (500x500 px,
    # 1px/block) — hires tiles are .prbm meshes, so the PNG thumbnail has
    # to come from a lowres tile, and staying inside ONE tile avoids
    # compositing. 10x10 chunks to generate.
    rcon.cmd(f"execute in {dim} run forceload add 0 0 143 143")
    # Chunk generation runs off-thread; give it a bounded window then flush
    # regions to disk (BlueMap reads region files, not live chunks).
    time.sleep(10)
    rcon.cmd("save-all flush")
    time.sleep(2)

    conf = Path(workdir) / "config" / "bluemap" / "maps" / f"{cand}.conf"
    conf.parent.mkdir(parents=True, exist_ok=True)
    conf.write_text(
        f'world: "world"\ndimension: "{dim}"\nname: "{cand}"\nsorting: 100\n'
        'render-mask: [ { min-x: 0, max-x: 144, min-z: 0, max-z: 144 } ]\n'
        'min-inhabited-time: 0\nrender-edges: true\nenable-hires: false\n'
        'enable-perspective-view: false\nenable-free-flight-view: false\n'
        'storage: "file"\nignore-missing-light-data: true\nmarker-sets: {}\n'
        + MAP_LIGHTING.get(family, MAP_LIGHTING["overworld"]))
    rcon.cmd("bluemap reload")
    time.sleep(3)

    # Renders must be COMPLETE — no holes, no unrendered squares. Chunk gen
    # and the BlueMap update are both async, so re-flush + re-update until
    # the cropped thumbnail passes the hole check (bounded attempts).
    tiles = Path(workdir) / "bluemap" / "web" / "maps" / cand / "tiles"
    origin_tile = tiles / "1" / "x0" / "z0.png"
    picked = False
    hole_ok = 0.02 if family == "overworld" else 0.10  # nether roof-cut edges
    for attempt in range(5):
        rcon.cmd("save-all flush")
        time.sleep(2)
        upd = rcon.cmd(f"bluemap force-update {cand}")
        if "not" in upd.lower() and "found" in upd.lower():
            rcon.cmd(f"bluemap update {cand}")
        deadline = time.time() + 60
        while time.time() < deadline and not origin_tile.exists():
            time.sleep(4)
        if not origin_tile.exists():
            continue
        time.sleep(4)  # let the write finish
        try:
            crop_png(origin_tile, out_png, 0, 0, 145, 145)
        except (ValueError, OSError):
            continue
        holes = png_hole_fraction(out_png, 145, 145)
        if holes <= hole_ok:
            picked = True
            log(worker_id, f"  render saved: {out_png.name} "
                           f"(attempt {attempt + 1}, holes {holes:.1%})")
            break
        log(worker_id, f"  render attempt {attempt + 1}: {holes:.0%} holes — regenerating")
        # Un-rendered chunks are usually still generating; nudge and retry.
        rcon.cmd(f"execute in {dim} run forceload add 0 0 143 143")
        time.sleep(10)
    if not picked:
        log(worker_id, f"  render INCOMPLETE for {cand} after 5 attempts (kept best effort)")

    if os.environ.get("ROLL_RENDER_DEBUG"):
        dbg = out_png.parent / f"{seed}-web-debug"
        shutil.rmtree(dbg, ignore_errors=True)
        web_map = Path(workdir) / "bluemap" / "web" / "maps" / cand
        if web_map.exists():
            shutil.copytree(web_map, dbg)
        log(worker_id, f"  debug web copy: {dbg}")
    conf.unlink(missing_ok=True)
    shutil.rmtree(Path(workdir) / "bluemap" / "web" / "maps" / cand, ignore_errors=True)
    rcon.cmd("forceload remove all")
    rcon.cmd("bluemap reload")
    return picked


# ---------------------------------------------------------------------------
# Worker main loop
# ---------------------------------------------------------------------------
def prepare_boot_dir(workdir, mvconfig, seedtest):
    workdir = Path(workdir)
    shutil.rmtree(workdir / "world", ignore_errors=True)
    (workdir / "server.properties").unlink(missing_ok=True)

    cfg = workdir / "config"
    cfg.mkdir(exist_ok=True)
    shutil.copy2(mvconfig, cfg / "multiverse_config.json")
    # c2me's density-function compiler ignores per-dimension seeds — every
    # candidate would silently clone the main world (mods/AGENTS.md).
    (cfg / "c2me.toml").write_text(
        "[vanillaWorldGenOptimizations]\n\tuseDensityFunctionCompiler = false\n")

    # Fresh BlueMap config: accept-download + no default maps (candidate map
    # configs are written per render). Stale per-dimension state from the live
    # server must not ride along (AGENTS.md trap).
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
        port = start_container(container, workdir, memory, seed=seed)
        rcon = wait_for_rcon(wid, container, port)
        if rcon is not None:
            break
        log(wid, f"boot attempt {attempt} failed{' — retrying' if attempt < 3 else ''}")
    if rcon is not None:
        rcon.cmd("tick freeze")
        rcon.cmd("gamerule doMobSpawning false")
        rcon.cmd("gamerule doDaylightCycle false")
    return rcon


def run_dimension_jobs(args, wid, container, base_config, csv_fh):
    """Dimension candidates: one long-lived container, customdim per attempt.
    Each manifest slot carries spare seeds; spawn-filter rejections re-roll
    to the next seed (rejections are banked so those seeds never repeat)."""
    ns = base_config.get("namespace", "adventure")
    dims_by_name = {d["name"]: d for d in base_config["dimensions"]}
    difficulty = load_difficulty(args.base_config)

    jobs = []
    for line in Path(args.manifest).read_text().splitlines():
        if line.strip():
            dim_name, base, seeds = line.split("|")
            jobs.append((dim_name, base, [int(s) for s in seeds.split(",")]))
    if not jobs:
        log(wid, "nothing to do")
        return 0

    prepare_boot_dir(args.workdir, args.mvconfig, args.seedtest)
    log(wid, f"booting container ({len(jobs)} candidate slots, mode {args.mode})")
    rcon = boot(wid, container, args.workdir, args.memory)
    if rcon is None:
        return 1

    done = 0
    for dim_name, base, seeds in jobs:
        if not container_running(container):
            log(wid, "container died — rebooting")
            rcon = boot(wid, container, args.workdir, args.memory)
            if rcon is None:
                return 1
        profile = build_profile(dims_by_name[dim_name], base_config, difficulty)
        t0 = time.time()
        accepted = False
        rejections = 0
        for k, seed in enumerate(seeds):
            cand = f"{base}a{k}"
            # One slow/broken candidate must never kill the worker.
            try:
                err_before = error_count(container)
                if not create_candidate(rcon, wid, ns, cand, profile, seed):
                    continue
                if args.mode in ("measure", "measure+render"):
                    rows, spawn, accepted = measure_candidate(
                        rcon, wid, container, f"{ns}:{cand}", profile, err_before,
                        force_accept=(k == len(seeds) - 1))
                    for metric, value in rows:
                        csv_fh.write(f"{dim_name},{seed},{metric},{value}\n")
                    csv_fh.flush()
                    if not accepted:
                        rejections += 1
                        destroy_candidate(rcon, args.workdir, ns, cand)
                        continue
                else:
                    accepted, spawn = True, ""
                if args.mode in ("render", "measure+render"):
                    render_candidate(rcon, wid, args.workdir, args.seedtest,
                                     container, ns, cand, dim_name, seed,
                                     family=profile["family"] or "overworld")
                destroy_candidate(rcon, args.workdir, ns, cand)
            except (OSError, ConnectionError) as e:
                log(wid, f"  SKIP {dim_name} seed {seed}: {type(e).__name__}: {e}")
                rcon.close()
                try:
                    destroy_candidate(rcon, args.workdir, ns, cand)
                except (OSError, ConnectionError):
                    rcon.close()
                continue
            if accepted:
                done += 1
                rej = f" +{rejections} spawn-rejected" if rejections else ""
                log(wid, f"[{done}/{len(jobs)}] {dim_name} seed {seed} "
                         f"({int(time.time() - t0)}s spawn={spawn}{rej})")
                break
        if not accepted:
            log(wid, f"[{done}/{len(jobs)}] {dim_name}: no spawn-filter hit in "
                     f"{len(seeds)} attempts ({int(time.time() - t0)}s) — banked as rejections")
    log(wid, f"done: {done}/{len(jobs)} candidate slots")
    return 0


def run_world_jobs(args, wid, container, base_config, csv_fh):
    """World-seed candidates: every seed is a fresh BOOT (SEED=<s>); all
    configured worlds are measured per boot. The overworld's spawn filter
    early-rejects a seed before the expensive full battery."""
    difficulty = load_difficulty(args.base_config)
    worlds = base_config.get("worlds", [])
    if not worlds:
        log(wid, "no worlds configured")
        return 0
    profiles = [(w, build_profile(w, base_config, difficulty)) for w in worlds]

    lines = [ln for ln in Path(args.manifest).read_text().splitlines() if ln.strip()]
    quota = int(lines[0].split("|")[1])
    seeds = [int(s) for s in lines[1:]]
    if not quota or not seeds:
        log(wid, "nothing to do")
        return 0

    accepted = 0
    for seed in seeds:
        if accepted >= quota:
            break
        t0 = time.time()
        prepare_boot_dir(args.workdir, args.mvconfig, args.seedtest)
        rcon = boot(wid, container, args.workdir, args.memory, seed=str(seed))
        if rcon is None:
            return 1
        try:
            # Overworld first — its spawn filter gates the whole seed; the
            # other worlds are always measured in full (their namesake
            # component just scores what it finds).
            for world, profile in profiles:
                dim = world["dimensionId"]
                err_before = error_count(container)
                rows, spawn, ok = measure_candidate(
                    rcon, wid, container, dim, profile, err_before,
                    force_accept=(world["name"] != "overworld"))
                for metric, value in rows:
                    csv_fh.write(f"{world['name']},{seed},{metric},{value}\n")
                csv_fh.flush()
                if not ok and world["name"] == "overworld":
                    log(wid, f"world seed {seed}: overworld spawn={spawn} — rejected "
                             f"({int(time.time() - t0)}s)")
                    break
            else:
                accepted += 1
                log(wid, f"[{accepted}/{quota}] world seed {seed} measured "
                         f"({int(time.time() - t0)}s)")
        except (OSError, ConnectionError) as e:
            log(wid, f"  SKIP world seed {seed}: {type(e).__name__}: {e}")
        finally:
            docker("rm", "-f", container, check=False)
    log(wid, f"done: {accepted}/{quota} world seeds")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--worker-id", required=True)
    ap.add_argument("--workdir", required=True)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--mvconfig", required=True)
    ap.add_argument("--base-config", required=True)
    ap.add_argument("--seedtest", required=True)
    ap.add_argument("--mode", choices=["measure", "render", "measure+render", "world"],
                    default="measure")
    ap.add_argument("--memory", default=os.environ.get("ROLL_MEMORY", "6G"))
    args = ap.parse_args()

    wid = args.worker_id
    suffix = {"render": "r", "world": "v"}.get(args.mode, "")
    container = f"seedrollall-{wid}{suffix}"
    base_config = json.loads(Path(args.base_config).read_text())

    csv_path = Path(args.seedtest) / f"worker-{wid}.csv"
    csv_new = not csv_path.exists()
    try:
        with open(csv_path, "a") as csv_fh:
            if csv_new:
                csv_fh.write("target,seed,metric,value\n")
            if args.mode == "world":
                return run_world_jobs(args, wid, container, base_config, csv_fh)
            return run_dimension_jobs(args, wid, container, base_config, csv_fh)
    finally:
        docker("rm", "-f", container, check=False)


if __name__ == "__main__":
    sys.exit(main())
