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


class ViewerHandler(SimpleHTTPRequestHandler):
    # Set by main()
    seedtest = ""
    config_path = ""
    finalise_args: list = []
    winner_overlay = ""

    def log_message(self, format, *args):  # noqa: A002 — quiet server
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
        out_path = out_dir / f"{seed}.hires.png"

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
            # Overlay structure markers on the hi-res render
            try:
                from biome_renderer import overlay_structures
                overlay_structures(str(out_path), seed, dim, self.config_path, 1024, 16)
            except Exception:
                pass
            rel = f"renders/{dim}/{seed}.hires.png"
            self._respond_json({"ok": True, "path": rel})
        else:
            self._respond_json({"ok": False,
                                "error": (r.stderr or r.stdout or "render failed")[:200]})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True)
    ap.add_argument("--seedtest", required=True)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--write-config", action="store_true")
    ap.add_argument("--winner-overlay",
                    help="consumer mode passthrough to score-dimensions finalise")
    args = ap.parse_args()

    finalise_args = ["--config", args.config, "--seedtest", args.seedtest, "--viewer"]
    if args.write_config:
        finalise_args.append("--write-config")
    if args.winner_overlay:
        finalise_args += ["--winner-overlay", args.winner_overlay]

    handler = partial(ViewerHandler, directory=args.seedtest)
    ViewerHandler.seedtest = args.seedtest
    ViewerHandler.config_path = args.config
    ViewerHandler.finalise_args = finalise_args
    ViewerHandler.winner_overlay = args.winner_overlay or ""
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler)
    print(f"viewer server: http://127.0.0.1:{args.port}/viewer.html", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
