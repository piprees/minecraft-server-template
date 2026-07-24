#!/usr/bin/env python3
"""viewer-server.py — serve .seedtest/viewer.html with winner picking.

A deliberately rudimentary localhost server: GET serves the seedtest
directory (viewer.html, renders/), POST /pick records a human winner
choice and immediately re-finalises so the pick lands in the config
(and the viewer regenerates with the pin).

    ./viewer-server.py --config <multiverse_config.json> --seedtest <dir>
                       [--port 8765] [--write-config]

POST /pick body: {"dim": "<name>", "seed": "<seed>"} — seed null/empty
clears the override (back to the score ranking). Picks persist in
<seedtest>/winner-overrides.json and are honoured by every later
finalise, including roll-all's end-of-run one.

Additional endpoints (require the live server):
  POST /reroll     — re-roll a dimension's candidates in background
  GET  /job/<id>   — poll a background job's status
  POST /edit-config — open a dimension's config in VS Code
  POST /preview    — (stub) detailed candidate preview

Gotchas: binds 127.0.0.1 only — this is a local tool, not a web app.
Started/stopped by roll-all.sh alongside the live reporter; safe to run
standalone after a roll too (./dev seed-report leaves the data behind).
"""
import argparse
import json
import subprocess
import sys
import threading
import time
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
_jobs = {}
_jobs_lock = threading.Lock()


def _batch_render(config, seedtest, biome_params, size, scale, sample_res,
                   label, suffix=""):
    """Run biome_renderer.py batch with output to terminal."""
    cmd = [sys.executable, str(SCRIPT_DIR / "biome_renderer.py"),
           "batch", "--config", config, "--seedtest", seedtest,
           "--biome-params", biome_params,
           "--top", "10", "--size", str(size), "--scale", str(scale),
           "--sample-res", str(sample_res)]
    if suffix:
        cmd += ["--suffix", suffix]
    print(f"\n=== {label} ===", flush=True)
    subprocess.run(cmd)


# (size, base blocks-per-pixel, filename suffix) of the two batch render
# passes — structure overlays must be written with the same geometry as
# the base render they stack over.
BATCH_GEOMETRIES = ((1024, 8, ""), (2048, 16, "_hires"))


def _load_structure_all(config_path, dim, seed):
    """Read a candidate's banked structure_all from the candidate store.
    Returns None when absent — callers skip overlay generation entirely
    (no fallback recomputation; a later finalise fills the gap)."""
    try:
        sys.path.insert(0, str(SCRIPT_DIR))
        import candidates as cmod
        cdir = cmod.candidates_dir(Path(config_path))
        store = cmod.load_store(cdir / f"{dim}.json")
        return store["candidates"].get(str(seed), {}).get("structure_all")
    except Exception:
        return None


def _write_structure_overlays(seedtest, dim, seed_str, structure_all, dim_scale,
                              geometries=BATCH_GEOMETRIES, skip_existing=True):
    """Write transparent {seed}{suffix}_structures.png overlays next to the
    renders. Each geometry MUST match the base render it stacks over;
    dim_scale shrinks coverage exactly like batch_render's effective_scale."""
    from biome_renderer import render_structure_overlay
    out_dir = Path(seedtest) / "renders" / dim
    out_dir.mkdir(parents=True, exist_ok=True)
    written = 0
    for size, base_scale, suffix in geometries:
        out = out_dir / f"{seed_str}{suffix}_structures.png"
        if skip_existing and out.exists():
            continue
        eff_scale = max(1, int(base_scale / dim_scale))
        render_structure_overlay(structure_all, str(out), size, size * eff_scale)
        written += 1
    return written


def _find_dim_config(config_path, dim):
    """Find the JSON file for a dimension in the config directory or
    monolith config. Returns (path, is_overlay) or (None, False)."""
    cfg = Path(config_path)
    if cfg.is_dir():
        f = cfg / "dimensions" / f"{dim}.json"
        if f.exists():
            return f, False
    else:
        return cfg, False
    return None, False


def _run_reroll(job_id, dim, config, seedtest, finalise_args, pool, count):
    """Background worker: fast-roll, render, re-finalise."""
    try:
        # Fast roll.
        r = subprocess.run(
            [sys.executable, str(SCRIPT_DIR / "fast_roller.py"),
             "--config", config, "--seedtest", seedtest,
             "--dims", dim, "--count", str(count),
             "--tier1-pool", str(pool)],
            capture_output=True, text=True, timeout=300)
        if r.returncode != 0:
            with _jobs_lock:
                _jobs[job_id]["status"] = "failed"
                _jobs[job_id]["error"] = r.stderr[:500]
            return

        # Render top candidates.
        subprocess.run(
            [sys.executable, str(SCRIPT_DIR / "biome_renderer.py"),
             "batch", "--config", config, "--seedtest", seedtest,
             "--dims", dim, "--top", "10"],
            capture_output=True, text=True, timeout=300)

        # Re-finalise.
        subprocess.run(
            [sys.executable, str(SCRIPT_DIR / "score-dimensions.py"),
             "finalise", *finalise_args],
            capture_output=True, text=True, timeout=120)

        with _jobs_lock:
            j = _jobs[job_id]
            j["status"] = "done"
            j["elapsed"] = int(time.monotonic() - j["started_mono"])
    except Exception as exc:
        with _jobs_lock:
            _jobs[job_id]["status"] = "failed"
            _jobs[job_id]["error"] = str(exc)[:500]


_fork_schema_cache = None


def _build_fork_schema(config_path):
    """One JSON blob of every option list the fork/create/edit form needs.
    Built lazily on first request, cached for the server's lifetime — it
    IS the documentation of valid moods/bands/structures/biomes, always
    in sync with dimension_profiles."""
    global _fork_schema_cache
    if _fork_schema_cache is not None:
        return _fork_schema_cache
    sys.path.insert(0, str(SCRIPT_DIR))
    from dimension_profiles import (BANDS, HOSTILE_STRUCTURES, MOOD_BLURBS,
                                    STRUCTS)
    biomes = {}
    bp_path = SCRIPT_DIR / "biome_params.json"
    if bp_path.exists():
        try:
            # A flat list of {biome, ..., family} rows (~1800), from the
            # mod's /customdim dump-biome-params.
            for row in json.loads(bp_path.read_text()):
                biome_id = row.get("biome") if isinstance(row, dict) else None
                if not biome_id:
                    continue
                ns = biome_id.split(":")[0]
                biomes.setdefault(ns, [])
                if biome_id not in biomes[ns]:
                    biomes[ns].append(biome_id)
            for ns in biomes:
                biomes[ns].sort()
        except (OSError, json.JSONDecodeError, AttributeError, TypeError):
            biomes = {}
    _fork_schema_cache = {
        "version": 1,
        # Mirrors the mod's DimensionManager type switch.
        "types": ["overworld", "multi_biome", "single_biome", "nether", "end",
                  "void", "superflat", "cave", "checkerboard", "sky_islands",
                  "nether_islands", "amplified", "large_biomes"],
        "noise_settings": ["", "adventure:wide", "adventure:compressed",
                           "minecraft:amplified", "minecraft:large_biomes"],
        "structure_density": ["", "sparse", "normal", "dense"],
        "moods": {k: MOOD_BLURBS.get(k, "") for k in sorted(MOOD_BLURBS)},
        "bands": sorted(BANDS),
        "band_ranges": {k: list(v) for k, v in BANDS.items()},
        "structures": sorted(STRUCTS),
        "hostile_structures": sorted(HOSTILE_STRUCTURES),
        "waters": ["", "default", "sea", "high", "none"],
        "biomes": biomes,
    }
    return _fork_schema_cache


# Field-by-field validation of the optional fork-form config. Returns
# (clean_config, errors) — errors is {field: message} for inline display;
# clean_config contains ONLY the validated fields (deep-merged over the
# parent clone by the caller). Shuns must be the MAP form: the mod's Gson
# crashes on list-form structures.shuns.
def _validate_fork_config(raw, config_path):
    schema = _build_fork_schema(config_path)
    sys.path.insert(0, str(SCRIPT_DIR))
    from dimension_profiles import resolve_struct
    clean, errors = {}, {}
    if not isinstance(raw, dict):
        return {}, {"config": "config must be an object"}

    def s(key):
        v = raw.get(key)
        return v.strip() if isinstance(v, str) else None

    if s("type"):
        if s("type") in schema["types"]:
            clean["type"] = s("type")
        else:
            errors["type"] = f"unknown type '{s('type')}'"
    if s("noiseSettings") is not None:
        if s("noiseSettings") in schema["noise_settings"]:
            if s("noiseSettings"):
                clean["noiseSettings"] = s("noiseSettings")
        else:
            errors["noiseSettings"] = f"unknown preset '{s('noiseSettings')}'"
    if s("structureDensity") is not None and s("structureDensity"):
        if s("structureDensity") in schema["structure_density"]:
            clean["structureDensity"] = s("structureDensity")
        else:
            errors["structureDensity"] = "must be sparse/normal/dense"

    border = raw.get("borderRadius")
    if border is not None:
        try:
            border = int(border)
            if not 64 <= border <= 100000:
                raise ValueError
            clean["borders"] = {"player": border, "generation": border}
        except (TypeError, ValueError):
            errors["borderRadius"] = "must be an integer 64..100000"

    biomes = raw.get("biomes")
    if biomes is not None:
        known = {b for ids in schema["biomes"].values() for b in ids}
        if not isinstance(biomes, list) or not all(isinstance(b, str) for b in biomes):
            errors["biomes"] = "must be a list of biome ids"
        else:
            bad = [b for b in biomes if b not in known]
            if bad:
                errors["biomes"] = f"unknown biome(s): {', '.join(bad[:5])}"
            elif biomes:
                clean["biomes"] = biomes

    seed_roll = {}
    if s("mood") is not None and s("mood"):
        if s("mood") in schema["moods"]:
            seed_roll["mood"] = s("mood")
        else:
            errors["mood"] = f"unknown mood '{s('mood')}'"
    if s("water") is not None and s("water"):
        if s("water") in schema["waters"]:
            seed_roll["water"] = s("water")
        else:
            errors["water"] = "must be default/sea/high/none"
    spawn_filter = raw.get("spawnFilter")
    if spawn_filter:
        if not isinstance(spawn_filter, list):
            errors["spawnFilter"] = "must be a list of biome ids"
        else:
            chosen = set(clean.get("biomes") or [])
            bad = [b for b in spawn_filter if chosen and b not in chosen]
            if bad:
                errors["spawnFilter"] = "spawnFilter must be a subset of the chosen biomes"
            else:
                seed_roll["spawnFilter"] = spawn_filter
    if seed_roll:
        clean["seedRoll"] = seed_roll

    structures = {}
    wants = raw.get("wants")
    if wants is not None:
        if not isinstance(wants, dict):
            errors["wants"] = "must be a map of structure -> band or {min,max}"
        else:
            # Band-name wants live in seedRoll.wants (free-form, roller
            # scoring); {min,max} ranges live in structures.wants — the
            # mod's Gson maps that to StructureWant objects and CRASHES on
            # band strings there (caught live by the boot gate 2026-07-24).
            band_wants, range_wants = {}, {}
            for sname, spec in wants.items():
                if resolve_struct(sname) is None:
                    errors["wants"] = f"unknown structure '{sname}'"
                    break
                if isinstance(spec, str) and spec in schema["bands"]:
                    band_wants[sname] = spec
                elif isinstance(spec, dict):
                    try:
                        lo, hi = int(spec["min"]), int(spec["max"])
                        if not 0 <= lo < hi:
                            raise ValueError
                        range_wants[sname] = {"min": lo, "max": hi}
                    except (KeyError, TypeError, ValueError):
                        errors["wants"] = f"'{sname}' range needs 0 <= min < max"
                        break
                else:
                    errors["wants"] = f"'{sname}' must be a band name or {{min,max}}"
                    break
            else:
                if range_wants:
                    structures["wants"] = range_wants
                if band_wants:
                    clean.setdefault("seedRoll", {})["wants"] = band_wants
    shuns = raw.get("shuns")
    if shuns is not None:
        if not isinstance(shuns, dict):
            errors["shuns"] = "must be a MAP of structure -> {minDistance} (the mod crashes on lists)"
        else:
            out = {}
            for sname, spec in shuns.items():
                if resolve_struct(sname) is None:
                    errors["shuns"] = f"unknown structure '{sname}'"
                    break
                md = spec.get("minDistance") if isinstance(spec, dict) else None
                try:
                    out[sname] = {"minDistance": max(0, int(md))} if md is not None else {}
                except (TypeError, ValueError):
                    errors["shuns"] = f"'{sname}' minDistance must be an integer"
                    break
            else:
                if out:
                    structures["shuns"] = out
    if structures:
        clean["structures"] = structures

    difficulty = {}
    mm = raw.get("mobMultiplier")
    if mm is not None:
        try:
            mm = float(mm)
            if not 0.0 <= mm <= 10.0:
                raise ValueError
            difficulty["mobMultiplier"] = mm
        except (TypeError, ValueError):
            errors["mobMultiplier"] = "must be a number 0..10"
    hs = raw.get("hostileSpawning")
    if hs is not None:
        difficulty["hostileSpawning"] = bool(hs)
    pl = raw.get("playerLuck")
    if pl is not None:
        try:
            difficulty["playerLuck"] = max(0.0, min(10.0, float(pl)))
        except (TypeError, ValueError):
            errors["playerLuck"] = "must be a number"
    if difficulty:
        clean["difficulty"] = difficulty

    portal = {}
    for key in ("frameBlock", "igniterItem", "particleType"):
        v = s(key)
        if v:
            if ":" not in v:
                errors[key] = "must be a namespaced id (e.g. minecraft:obsidian)"
            else:
                portal[key] = v
    colour = s("color")
    if colour:
        import re as _re
        if _re.match(r"^#?[0-9a-fA-F]{6}$", colour):
            portal["color"] = colour.lstrip("#").upper()
        else:
            errors["color"] = "must be a 6-digit hex colour"
    if portal:
        clean["portal"] = portal

    scale = raw.get("scale")
    if scale is not None:
        try:
            scale = float(scale)
            if scale not in (1.0, 4.0, 8.0, 12.0, 16.0):
                raise ValueError
            clean.setdefault("portal", {})["scale"] = scale
        except (TypeError, ValueError):
            errors["scale"] = "must be one of 1, 4, 8, 12, 16"

    return clean, errors


def _deep_merge(base, over):
    out = dict(base)
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _deep_merge(out[k], v)
        else:
            out[k] = v
    return out


class ViewerHandler(SimpleHTTPRequestHandler):
    # Set by main()
    seedtest = ""
    config_path = ""
    finalise_args: list = []
    winner_overlay = ""

    def log_message(self, format, *args):  # noqa: A002 — quiet server
        pass

    def handle_one_request(self):
        try:
            super().handle_one_request()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _read_json(self):
        length = int(self.headers.get("Content-Length", 0))
        return json.loads(self.rfile.read(length) or b"{}")

    def _respond_json(self, payload, status=200):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/fork-schema":
            try:
                self._respond_json(_build_fork_schema(self.config_path))
            except Exception as exc:
                self._respond_json({"error": str(exc)[:300]}, 500)
            return
        if self.path.startswith("/dim-config"):
            from urllib.parse import parse_qs, urlparse
            dim = (parse_qs(urlparse(self.path).query).get("dim") or [""])[0]
            path = self._resolve_dim_config(dim) if dim else None
            if not path:
                self._respond_json({"error": f"config not found for '{dim}'"}, 404)
                return
            try:
                self._respond_json({"ok": True, "dim": dim,
                                    "config": json.loads(path.read_text())})
            except (OSError, json.JSONDecodeError) as exc:
                self._respond_json({"error": str(exc)[:300]}, 500)
            return
        if self.path.startswith("/job/"):
            job_id = self.path[5:]
            with _jobs_lock:
                job = _jobs.get(job_id)
            if not job:
                self._respond_json({"error": "unknown job"}, 404)
                return
            out = {k: v for k, v in job.items() if k != "started_mono"}
            if job["status"] == "running":
                out["elapsed"] = int(time.monotonic() - job["started_mono"])
            self._respond_json(out)
            return
        super().do_GET()

    def do_POST(self):
        if self.path == "/pick":
            self._handle_pick()
        elif self.path == "/reroll":
            self._handle_reroll()
        elif self.path == "/edit-config":
            self._handle_edit_config()
        elif self.path == "/preview":
            self._handle_preview()
        elif self.path == "/create-dimension":
            self._handle_create_dimension()
        elif self.path == "/shortlist":
            self._handle_shortlist()
        elif self.path == "/hide-dimension":
            self._handle_hide_dimension()
        elif self.path == "/remove-dimension":
            self._handle_remove_dimension()
        else:
            self.send_error(404)

    def _handle_pick(self):
        try:
            body = self._read_json()
            dim = str(body["dim"])
            seed = body.get("seed")
        except (ValueError, KeyError, json.JSONDecodeError):
            self.send_error(400, "expected JSON {dim, seed}")
            return

        overrides_path = Path(self.seedtest) / "winner-overrides.json"
        try:
            overrides = json.loads(overrides_path.read_text())
        except (OSError, json.JSONDecodeError):
            overrides = {}
        if seed:
            overrides[dim] = str(seed)
        else:
            overrides.pop(dim, None)
        tmp = overrides_path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(overrides, indent=2) + "\n")
        tmp.replace(overrides_path)

        r = subprocess.run([sys.executable, str(SCRIPT_DIR / "score-dimensions.py"),
                           "finalise", *self.finalise_args],
                          capture_output=True, text=True)
        self._respond_json({"ok": r.returncode == 0, "dim": dim,
                            "seed": seed, "overrides": overrides},
                           200 if r.returncode == 0 else 500)

    def _handle_reroll(self):
        try:
            body = self._read_json()
            dim = str(body["dim"])
            pool = int(body.get("pool", 5000))
            count = int(body.get("count", 100))
        except (ValueError, KeyError, json.JSONDecodeError):
            self.send_error(400, "expected JSON {dim}")
            return

        job_id = f"reroll-{dim}-{int(time.time())}"
        with _jobs_lock:
            _jobs[job_id] = {"status": "running", "dim": dim,
                             "started": time.strftime("%H:%M:%S"),
                             "started_mono": time.monotonic()}

        t = threading.Thread(
            target=_run_reroll, daemon=True,
            args=(job_id, dim, self.config_path, self.seedtest,
                  self.finalise_args, pool, count))
        t.start()
        self._respond_json({"ok": True, "job_id": job_id})

    def _handle_edit_config(self):
        try:
            body = self._read_json()
            dim = str(body["dim"])
        except (ValueError, KeyError, json.JSONDecodeError):
            self.send_error(400, "expected JSON {dim}")
            return

        # Try overlay first, then platform config.
        target = None
        if self.winner_overlay:
            overlay_path = Path(self.winner_overlay) / "dimensions" / f"{dim}.json"
            if overlay_path.exists():
                target = overlay_path
            else:
                src, _ = _find_dim_config(self.config_path, dim)
                if src and src.is_file():
                    overlay_path.parent.mkdir(parents=True, exist_ok=True)
                    if Path(self.config_path).is_dir():
                        import shutil
                        shutil.copy2(src, overlay_path)
                    target = overlay_path
        if not target:
            target, _ = _find_dim_config(self.config_path, dim)

        if not target or not target.exists():
            self._respond_json({"ok": False, "error": f"config not found for {dim}"}, 404)
            return

        subprocess.Popen(["code", str(target)],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self._respond_json({"ok": True, "path": str(target)})

    def _handle_preview(self):
        """Hi-res 16km biome render (1024×1024). ~10-30s depending on family."""
        try:
            body = self._read_json()
            dim = str(body["dim"])
            seed = str(body["seed"])
        except (ValueError, KeyError, json.JSONDecodeError):
            self.send_error(400, "expected JSON {dim, seed}")
            return

        out_dir = Path(self.seedtest) / "renders" / dim
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / f"{seed}_hires.png"

        biome_params = str(SCRIPT_DIR / "biome_params.json")
        if not Path(biome_params).exists():
            self._respond_json({"ok": False, "error": "biome_params.json not found"})
            return

        # Resolve the dimension's family from config
        family = "overworld"
        try:
            from dimension_profiles import load_config, load_difficulty, build_profile
            config = load_config(self.config_path)
            difficulty = load_difficulty(self.config_path)
            all_dims = {d["name"]: d for d in config.get("dimensions", [])}
            all_dims.update({w["name"]: w for w in config.get("worlds", [])})
            if dim in all_dims:
                profile = build_profile(all_dims[dim], config, difficulty)
                family = profile.get("family") or "overworld"
        except Exception:
            pass

        # Map family to noise family (same as biome_renderer + fast_roller)
        noise_family = {"paradise_lost": "paradise_lost"}.get(family, family)

        r = subprocess.run(
            [sys.executable, str(SCRIPT_DIR / "biome_renderer.py"),
             "render", "--seed", seed, "--output", str(out_path),
             "--biome-params", biome_params,
             "--family", noise_family,
             "--size", "1024", "--scale", "16"],
            capture_output=True, text=True, timeout=120)
        if r.returncode == 0 and out_path.exists():
            # Structures overlay matching THIS render's geometry (1024px,
            # 16 b/px — narrower than the 2048px batch hires, so overwrite).
            sa = _load_structure_all(self.config_path, dim, seed)
            if sa:
                try:
                    _write_structure_overlays(
                        self.seedtest, dim, seed, sa, 1.0,
                        geometries=((1024, 16, "_hires"),), skip_existing=False)
                except Exception:
                    pass
            rel = f"renders/{dim}/{seed}_hires.png"
            self._respond_json({"ok": True, "path": rel})
        else:
            self._respond_json({"ok": False,
                                "error": (r.stderr or r.stdout or "render failed")[:200]})

    def _handle_shortlist(self):
        """Toggle a candidate on/off the shortlist. Also renders hi-res if adding."""
        try:
            body = self._read_json()
            dim = str(body["dim"])
            seed = str(body["seed"])
            action = body.get("action", "toggle")
        except (ValueError, KeyError, json.JSONDecodeError):
            self.send_error(400, "expected JSON {dim, seed}")
            return

        sl_path = Path(self.seedtest) / "shortlist.json"
        try:
            shortlist = json.loads(sl_path.read_text())
        except (OSError, json.JSONDecodeError):
            shortlist = {}

        key = f"{dim}/{seed}"
        if action == "remove" or (action == "toggle" and key in shortlist):
            shortlist.pop(key, None)
            sl_path.write_text(json.dumps(shortlist, indent=2) + "\n")
            subprocess.run([sys.executable, str(SCRIPT_DIR / "score-dimensions.py"),
                           "finalise", *self.finalise_args],
                          capture_output=True, text=True)
            self._respond_json({"ok": True, "shortlisted": False})
            return

        shortlist[key] = {"dim": dim, "seed": seed, "added": time.strftime("%Y-%m-%dT%H:%M:%S")}
        sl_path.write_text(json.dumps(shortlist, indent=2) + "\n")

        # Re-finalise to regenerate index.html with updated shortlist state
        subprocess.run([sys.executable, str(SCRIPT_DIR / "score-dimensions.py"),
                       "finalise", *self.finalise_args],
                      capture_output=True, text=True)

        # Render hi-res if not already done
        hires_path = Path(self.seedtest) / "renders" / dim / f"{seed}_hires.png"
        if not hires_path.exists():
            biome_params = str(SCRIPT_DIR / "biome_params.json")
            family = "overworld"
            try:
                from dimension_profiles import load_config, load_difficulty, build_profile
                config = load_config(self.config_path)
                difficulty = load_difficulty(self.config_path)
                all_dims = {d["name"]: d for d in config.get("dimensions", [])}
                all_dims.update({w["name"]: w for w in config.get("worlds", [])})
                if dim in all_dims:
                    profile = build_profile(all_dims[dim], config, difficulty)
                    family = profile.get("family") or "overworld"
            except Exception:
                pass
            noise_family = {"paradise_lost": "paradise_lost"}.get(family, family)
            hires_path.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(
                [sys.executable, str(SCRIPT_DIR / "biome_renderer.py"),
                 "render", "--seed", seed, "--output", str(hires_path),
                 "--biome-params", biome_params,
                 "--family", noise_family,
                 "--size", "1024", "--scale", "16"],
                capture_output=True, text=True, timeout=120)
            sa = _load_structure_all(self.config_path, dim, seed)
            if sa and hires_path.exists():
                try:
                    _write_structure_overlays(
                        self.seedtest, dim, seed, sa, 1.0,
                        geometries=((1024, 16, "_hires"),), skip_existing=False)
                except Exception:
                    pass

        self._respond_json({"ok": True, "shortlisted": True,
                            "hires": f"renders/{dim}/{seed}_hires.png"})

    def _handle_create_dimension(self):
        """Fork (default), create-from-scratch, or edit-in-place — one
        endpoint, three modes, identical validation. The optional `config`
        object carries the form's divergences; validation errors come back
        per-field for inline display."""
        try:
            body = self._read_json()
            mode = str(body.get("mode", "fork"))
            name = str(body["name"])
            description = str(body.get("description", ""))[:300]
            parent_dim = str(body.get("parent_dim", ""))
            seed = str(body.get("seed", "") or "")
            form_config = body.get("config")
        except (ValueError, KeyError, json.JSONDecodeError):
            self.send_error(400, "expected JSON {name, ...}")
            return

        import re
        if not re.match(r'^[a-z][a-z0-9_]*$', name):
            self._respond_json({"ok": False, "error": "Name must be snake_case",
                                "errors": {"name": "Name must be snake_case"}}, 400)
            return
        if mode not in ("fork", "create", "edit"):
            self._respond_json({"ok": False, "error": f"unknown mode '{mode}'"}, 400)
            return
        if mode == "fork" and not parent_dim:
            self._respond_json({"ok": False, "error": "fork needs parent_dim"}, 400)
            return

        clean_config, field_errors = ({}, {})
        if form_config is not None:
            clean_config, field_errors = _validate_fork_config(form_config, self.config_path)
            if field_errors:
                self._respond_json({"ok": False, "error": "validation failed",
                                    "errors": field_errors}, 422)
                return

        cfg = Path(self.config_path)
        target_path = None
        if mode == "fork":
            parent_path, _ = _find_dim_config(self.config_path, parent_dim)
            if not parent_path or not parent_path.exists():
                self._respond_json({"ok": False, "error": f"Parent config not found: {parent_dim}"}, 404)
                return
            if cfg.is_dir():
                parent_data = json.loads(parent_path.read_text())
            else:
                full = json.loads(parent_path.read_text())
                parent_data = next((d for d in full.get("dimensions", [])
                                    if d["name"] == parent_dim), None)
                if not parent_data:
                    self._respond_json({"ok": False, "error": "Dimension not found in config"}, 404)
                    return
        elif mode == "edit":
            target_path = self._resolve_dim_config(name)
            if not target_path:
                self._respond_json({"ok": False, "error": f"Config not found: {name}"}, 404)
                return
            parent_data = json.loads(target_path.read_text())
        else:  # create: a minimal sane skeleton
            parent_data = {"type": "overworld",
                           "borders": {"player": 2048, "generation": 2048}}

        new_data = _deep_merge(dict(parent_data), clean_config)
        new_data["name"] = name
        if seed:
            new_data["seed"] = int(seed)
        if description:
            new_data["description"] = description
        if mode == "fork":
            new_data["parentDimension"] = parent_dim
        ns = str(new_data.get("dimensionId", "")).split(":")[0] or "adventure"
        new_data["dimensionId"] = f"{ns}:{name}"

        if mode == "edit":
            out_path = target_path
        else:
            if self.winner_overlay:
                out_dir = Path(self.winner_overlay) / "dimensions"
            elif cfg.is_dir():
                out_dir = cfg / "dimensions"
            else:
                self._respond_json({"ok": False, "error": "Cannot create in monolith mode"}, 400)
                return
            out_dir.mkdir(parents=True, exist_ok=True)
            out_path = out_dir / f"{name}.json"
            if out_path.exists():
                self._respond_json({"ok": False, "error": f"'{name}' already exists"}, 409)
                return

        out_path.write_text(json.dumps(new_data, indent=2, ensure_ascii=False) + "\n")

        # Overlay-written dims are invisible to fast_roller/finalise until
        # dev-up re-stages the consumer overlay into the config dir — mirror
        # the file into the staged overlay now so the auto-reroll (and the
        # viewer) see the new dim immediately. Same content, same contract:
        # the next dev-up re-stages it identically.
        if self.winner_overlay and cfg.is_dir():
            staged = cfg / "overlay" / "dimensions"
            if staged.parent.is_dir():
                staged.mkdir(parents=True, exist_ok=True)
                (staged / f"{name}.json").write_text(
                    json.dumps(new_data, indent=2, ensure_ascii=False) + "\n")

        if mode == "fork" and seed:
            try:
                sys.path.insert(0, str(SCRIPT_DIR))
                import candidates as cand_mod
                src_cdir = cand_mod.candidates_dir(cfg if cfg.is_dir() else cfg.parent)
                src_store_path = src_cdir / f"{parent_dim}.json"
                if src_store_path.exists():
                    src_store = cand_mod.load_store(src_store_path)
                    seed_str = str(seed)
                    if seed_str in src_store["candidates"]:
                        dst_base = Path(self.winner_overlay) if self.winner_overlay else cfg
                        dst_cdir = cand_mod.candidates_dir(dst_base)
                        dst_cdir.mkdir(parents=True, exist_ok=True)
                        dst_store = cand_mod.load_store(dst_cdir / f"{name}.json")
                        cand_mod.merge_rows(dst_store, seed_str,
                                            src_store["candidates"][seed_str].get("measurements", {}))
                        cand_mod.save_store(dst_cdir / f"{name}.json", dst_store)
            except Exception:
                pass

        # The form's whole point is diverging, so the parent's candidates
        # rarely apply — auto-roll fresh candidates for the new/edited dim
        # when the form changed anything generation-relevant.
        job_id = None
        if clean_config:
            job_id = f"reroll-{name}-{int(time.time())}"
            with _jobs_lock:
                _jobs[job_id] = {"status": "running", "dim": name,
                                 "started": time.strftime("%H:%M:%S"),
                                 "started_mono": time.monotonic()}
            threading.Thread(
                target=_run_reroll, daemon=True,
                args=(job_id, name, self.config_path, self.seedtest,
                      self.finalise_args, 5000, 100)).start()

        self._respond_json({"ok": True, "path": str(out_path), "job_id": job_id})

    def _handle_hide_dimension(self):
        try:
            body = self._read_json()
            dim = str(body["dim"])
        except (ValueError, KeyError, json.JSONDecodeError):
            self.send_error(400, "expected JSON {dim}")
            return

        path = self._resolve_dim_config(dim)
        if not path:
            self._respond_json({"ok": False, "error": f"Config not found: {dim}"}, 404)
            return

        data = json.loads(path.read_text())
        data["hidden"] = True
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")

        subprocess.run([sys.executable, str(SCRIPT_DIR / "score-dimensions.py"),
                       "finalise", *self.finalise_args],
                      capture_output=True, text=True)
        self._respond_json({"ok": True})

    def _handle_remove_dimension(self):
        try:
            body = self._read_json()
            dim = str(body["dim"])
        except (ValueError, KeyError, json.JSONDecodeError):
            self.send_error(400, "expected JSON {dim}")
            return

        path = self._resolve_dim_config(dim)
        if not path:
            self._respond_json({"ok": False, "error": f"Config not found: {dim}"}, 404)
            return

        data = json.loads(path.read_text())
        data["hidden"] = True
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")

        removed_path = path.with_suffix(".json.removed")
        path.rename(removed_path)

        subprocess.run([sys.executable, str(SCRIPT_DIR / "score-dimensions.py"),
                       "finalise", *self.finalise_args],
                      capture_output=True, text=True)
        self._respond_json({"ok": True, "path": str(removed_path)})

    def _resolve_dim_config(self, dim):
        """Find the best config file for a dimension (overlay takes priority)."""
        if self.winner_overlay:
            p = Path(self.winner_overlay) / "dimensions" / f"{dim}.json"
            if p.exists():
                return p
        path, _ = _find_dim_config(self.config_path, dim)
        return path if path and path.exists() else None


def main():
    import shutil

    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    ap.add_argument("--seedtest", required=True)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--write-config", action="store_true")
    ap.add_argument("--winner-overlay",
                    help="consumer mode passthrough to score-dimensions finalise")
    ap.add_argument("--refresh", action="store_true",
                    help="wipe existing renders and regenerate all in background")
    args = ap.parse_args()

    finalise_args = ["--config", args.config, "--seedtest", args.seedtest, "--viewer"]
    if args.write_config:
        finalise_args.append("--write-config")
    if args.winner_overlay:
        finalise_args += ["--winner-overlay", args.winner_overlay]

    # --refresh: wipe all renders so they regenerate
    renders_dir = Path(args.seedtest) / "renders"
    if args.refresh and renders_dir.exists():
        shutil.rmtree(renders_dir)
        print("renders wiped (--refresh)", flush=True)

    # Re-finalise to regenerate viewer.html with current scores
    subprocess.run([sys.executable, str(SCRIPT_DIR / "score-dimensions.py"),
                   "finalise", *finalise_args],
                  capture_output=True, text=True)

    handler = partial(ViewerHandler, directory=args.seedtest)
    ViewerHandler.seedtest = args.seedtest
    ViewerHandler.config_path = args.config
    ViewerHandler.finalise_args = finalise_args
    ViewerHandler.winner_overlay = args.winner_overlay or ""

    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler)
    print(f"viewer server: http://127.0.0.1:{args.port}/", flush=True)

    # Serve in a background thread so batch renders can run with the server up
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    # Render top candidates via multiprocessing batch (fast, output to CLI)
    biome_params = str(SCRIPT_DIR / "biome_params.json")
    if Path(biome_params).exists():
        _batch_render(args.config, args.seedtest, biome_params,
                      1024, 8, 256, "Rendering normal-res (1024px)")
        _batch_render(args.config, args.seedtest, biome_params,
                      2048, 16, 512, "Rendering hires (2048px)",
                      suffix="_hires")

    # Enumerate all structure placements within border for top 10 candidates
    print("\n=== Enumerating structures ===", flush=True)
    try:
        from dimension_profiles import load_config, load_difficulty, build_profile, rollable
        from structure_placement import load_structure_sets, find_all_in_radius
        import candidates as cmod

        config = load_config(args.config)
        difficulty = load_difficulty(args.config)
        dims = {d["name"]: d for d in config["dimensions"] if rollable(d)}
        worlds = {w["name"]: w for w in config.get("worlds", [])}
        all_targets = {**worlds, **dims}
        cdir = cmod.candidates_dir(Path(args.config))

        struct_sets_dir = Path(args.seedtest) / ".structure_sets"
        all_sets = load_structure_sets(str(struct_sets_dir)) if struct_sets_dir.exists() else {}

        enriched = 0
        overlays = 0
        for name, dim in all_targets.items():
            profile = build_profile(dim, config, difficulty)
            store = cmod.load_store(cdir / f"{name}.json")
            battery = profile.get("battery", [])
            if not battery:
                continue
            radius = int(profile["radius"])

            scored = []
            for seed_str, cand in store["candidates"].items():
                best = max((s.get("total", 0) for s in cand.get("scores", {}).values()), default=0)
                if best > 0:
                    scored.append((best, seed_str))
            scored.sort(reverse=True)

            dim_scale = float(profile.get("scale", 1.0) or 1.0)
            changed = False
            for _, seed_str in scored[:10]:
                cand = store["candidates"][seed_str]
                if "structure_all" not in cand:
                    sa = {}
                    for sname, sid, spec, kind in battery:
                        clean = sid.lstrip("#")
                        cfg = all_sets.get(clean)
                        if not cfg:
                            continue
                        hits = find_all_in_radius(
                            int(seed_str), cfg["spacing"], cfg["separation"], cfg["salt"],
                            radius, spread_type=cfg.get("spread_type", "linear"),
                            frequency=cfg.get("frequency", 1.0))
                        sa[sname] = [(d, x, z) for d, x, z in hits]
                    cand["structure_all"] = sa
                    changed = True
                    enriched += 1
                # Transparent marker overlays stacked over the batch renders
                # by the viewer. Same top-10 as the renders; nothing else
                # gets one (missing seeds fill in on the next finalise).
                overlays += _write_structure_overlays(
                    args.seedtest, name, seed_str, cand["structure_all"], dim_scale)

            if changed:
                cmod.save_store(cdir / f"{name}.json", store)

        print(f"Enriched {enriched} candidates with full structure data "
              f"({overlays} overlay images written)", flush=True)

        # Biome survey: sample a grid within the border, record all unique biomes + positions
        print("=== Surveying biomes ===", flush=True)
        from biome_sampler import BiomeSampler, load_noise_configs
        noise_configs = load_noise_configs()
        biome_params = str(SCRIPT_DIR / "biome_params.json")
        if Path(biome_params).exists():
            FAMILY_NOISE = {"overworld": "overworld", "nether": "nether",
                            "end": "end", "paradise_lost": "paradise_lost"}
            TYPE_OVERRIDE = {"paradise_lost:paradise_lost": "paradise_lost"}
            bio_enriched = 0
            for name, dim in all_targets.items():
                profile = build_profile(dim, config, difficulty)
                store = cmod.load_store(cdir / f"{name}.json")
                radius = int(profile["radius"])
                dim_type = dim.get("type", "")
                fam = profile.get("family", "overworld")
                noise_fam = TYPE_OVERRIDE.get(dim_type, FAMILY_NOISE.get(fam, "overworld"))
                nc = noise_configs.get(noise_fam, noise_configs.get("overworld"))

                config_biomes = list(dim.get("biomes") or []) \
                    or [b.strip() for b in (dim.get("biome") or "").split(",") if b.strip()]
                biome_filter = config_biomes if config_biomes else None

                scored = []
                for seed_str, cand in store["candidates"].items():
                    best = max((s.get("total", 0) for s in cand.get("scores", {}).values()), default=0)
                    if best > 0:
                        scored.append((best, seed_str))
                scored.sort(reverse=True)

                changed = False
                step = max(64, radius // 32)
                for _, seed_str in scored[:10]:
                    cand = store["candidates"][seed_str]
                    if "biome_survey" in cand:
                        continue
                    sampler = BiomeSampler(int(seed_str), biome_params,
                                          noise_config=nc, family=noise_fam,
                                          biome_filter=biome_filter)
                    biome_map = {}
                    for bx in range(-radius, radius + 1, step):
                        for bz in range(-radius, radius + 1, step):
                            biome = sampler.biome_at(bx, bz)
                            if biome not in biome_map:
                                dist = int((bx * bx + bz * bz) ** 0.5)
                                biome_map[biome] = [dist, bx, bz]
                    cand["biome_survey"] = biome_map
                    changed = True
                    bio_enriched += 1

                if changed:
                    cmod.save_store(cdir / f"{name}.json", store)

            print(f"Surveyed biomes for {bio_enriched} candidates", flush=True)
    except Exception as e:
        print(f"Structure enumeration failed: {e}", flush=True)

    print("\nRenders complete. Server running — Ctrl+C to stop.", flush=True)
    try:
        server_thread.join()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
