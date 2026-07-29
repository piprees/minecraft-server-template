#!/usr/bin/env python3
"""viewer-server.py — serve .seedtest/index.html with winner picking.

A deliberately rudimentary localhost server: GET serves the seedtest
directory (index.html, renders/), POST /pick records a human winner
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
standalone after a roll too (./dev seed-roll leaves the data behind).
"""
import argparse
import errno
import json
import subprocess
import sys
import threading
from urllib.parse import parse_qs, urlparse
import time
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
_jobs = {}
_jobs_lock = threading.Lock()


# How many candidates each pass covers. Enrichment is cheap arithmetic and
# rendering is minutes of CPU, so they are DELIBERATELY not the same number:
# enrich wide, re-rank, then render only the survivors. Rendering first would
# spend the expensive pass on a ranking the cheap pass is about to change.
ENRICH_TOP = 25
RENDER_TOP = 10


def _top_seeds(store, limit):
    """The store's best `limit` seeds by current-config score, best first."""
    current_hash = store.get("configHash", "")
    scored = []
    for seed_str, cand in store["candidates"].items():
        scores = cand.get("scores", {})
        if current_hash and current_hash in scores:
            best = scores[current_hash].get("total", 0)
        else:
            best = max((s.get("total", 0) for s in scores.values()), default=0)
        if best > 0:
            scored.append((best, seed_str))
    scored.sort(reverse=True)
    return [s for _, s in scored[:limit]]


def _target_names(config_path):
    """Every rollable dimension plus the base worlds, as the roller sees them."""
    from dimension_profiles import load_config, rollable
    config = load_config(config_path)
    return ({w["name"] for w in config.get("worlds", [])}
            | {d["name"] for d in config["dimensions"] if rollable(d)})


def _enrich_dim(name, dim, config, difficulty, cdir, all_sets, top_n):
    """Structure enumeration for one dimension. Returns how many were added."""
    from dimension_profiles import build_profile
    from structure_placement import find_all_in_radius
    import candidates as cmod

    profile = build_profile(dim, config, difficulty)
    battery = profile.get("battery", [])
    store_path = cdir / f"{name}.json"
    if not battery or not store_path.exists():
        return 0
    store = cmod.load_store(store_path)
    radius = int(profile["radius"])
    added = 0
    for seed_str in _top_seeds(store, top_n):
        cand = store["candidates"][seed_str]
        if "structure_all" in cand:
            continue
        sa = {}
        for sname, sid, _spec, _kind in battery:
            cfg = all_sets.get(sid.lstrip("#"))
            if not cfg:
                continue
            hits = find_all_in_radius(
                int(seed_str), cfg["spacing"], cfg["separation"], cfg["salt"],
                radius, spread_type=cfg.get("spread_type", "linear"),
                frequency=cfg.get("frequency", 1.0))
            sa[sname] = [(d, x, z) for d, x, z in hits]
        cand["structure_all"] = sa
        added += 1
    if added:
        cmod.save_store(store_path, store)
    return added


def _survey_dim(name, dim, config, difficulty, cdir, biome_params, top_n):
    """Biome survey for one dimension. Returns how many were added."""
    from dimension_profiles import build_profile
    from biome_sampler import BiomeSampler, load_noise_configs
    import candidates as cmod

    FAMILY_NOISE = {"overworld": "overworld", "nether": "nether",
                    "end": "end", "paradise_lost": "paradise_lost"}
    TYPE_OVERRIDE = {"paradise_lost:paradise_lost": "paradise_lost"}

    store_path = cdir / f"{name}.json"
    if not store_path.exists():
        return 0
    profile = build_profile(dim, config, difficulty)
    store = cmod.load_store(store_path)
    radius = int(profile["radius"])
    noise_configs = load_noise_configs()
    noise_fam = TYPE_OVERRIDE.get(dim.get("type", ""),
                                  FAMILY_NOISE.get(profile.get("family", "overworld"),
                                                   "overworld"))
    nc = noise_configs.get(noise_fam, noise_configs.get("overworld"))
    config_biomes = list(dim.get("biomes") or []) \
        or [b.strip() for b in (dim.get("biome") or "").split(",") if b.strip()]

    added = 0
    step = max(64, radius // 32)
    for seed_str in _top_seeds(store, top_n):
        cand = store["candidates"][seed_str]
        if "biome_survey" in cand:
            continue
        sampler = BiomeSampler(int(seed_str), biome_params, noise_config=nc,
                               family=noise_fam,
                               biome_filter=config_biomes or None)
        biome_map = {}
        for bx in range(-radius, radius + 1, step):
            for bz in range(-radius, radius + 1, step):
                biome = sampler.biome_at(bx, bz)
                if biome not in biome_map:
                    biome_map[biome] = [int((bx * bx + bz * bz) ** 0.5), bx, bz]
        cand["biome_survey"] = biome_map
        added += 1
    if added:
        cmod.save_store(store_path, store)
    return added


def _render_args_for(config_path, dim):
    """Every CLI arg a one-off HI-RES render of `dim` needs.

    _handle_preview and _handle_shortlist each used to resolve this
    themselves, from profile["family"] alone. That draws every custom
    paradise_lost:paradise_lost dimension as an overworld world — the TYPE
    decides the noise family, not the family (see
    biome_renderer.resolve_noise_family). They also dropped the biome
    filter and the noise preset, so the hi-res render was a different
    world from the batch thumbnail sitting next to it.

    Geometry is part of "the same world" and lives here for the same
    reason. Both callers used to hard-code `--size 1024 --scale 16`, which
    covers 16384 blocks whatever the dimension's portal scale is, while
    batch_render's hi-res pass covers 2048 * max(1, 16 // dim_scale). For a
    16x pocket dimension that is 16384 against 2048 — an eightfold
    disagreement between two files both called {seed}_hires.png, and every
    overlay drawn over one of them reads its coverage from the other.
    Half the pixel budget at twice the blocks per pixel lands on exactly
    the batch coverage, so one file name now means one geometry.
    """
    family, dim_type, biomes, noise_settings = "overworld", "", [], ""
    dim_scale = 1.0
    try:
        from dimension_profiles import load_config, load_difficulty, build_profile
        config = load_config(config_path)
        difficulty = load_difficulty(config_path)
        all_dims = {d["name"]: d for d in config.get("dimensions", [])}
        all_dims.update({w["name"]: w for w in config.get("worlds", [])})
        entry = all_dims.get(dim)
        if entry:
            profile = build_profile(entry, config, difficulty)
            family = profile.get("family") or "overworld"
            dim_scale = float(profile.get("scale", 1.0) or 1.0) or 1.0
            dim_type = entry.get("type", "") or ""
            noise_settings = entry.get("noiseSettings", "") or ""
            biomes = list(entry.get("biomes") or []) or [
                b.strip() for b in (entry.get("biome") or "").split(",") if b.strip()]
    except Exception:
        pass
    blocks_per_pixel = 2 * max(1, int(16 / dim_scale))
    return ["--family", family, "--dim-type", dim_type,
            "--biome-filter", ",".join(biomes),
            "--noise-settings", noise_settings,
            "--size", "1024", "--scale", str(blocks_per_pixel)]


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


class RenderWorker:
    """Renders the current top-RENDER_TOP of every target. Forever.

    Rendering used to be step 4 of the roll cycle, which had two
    consequences nobody wanted:

      - Nothing rendered unless a roll was running. Open the viewer on a
        bank that already has thousands of candidates and press nothing,
        and no image is ever produced — the grid stays "render queued" for
        as long as you leave it alone.
      - The queue was chosen per dimension, mid-cycle, and a re-rank a
        moment later could demote a candidate that was still about to be
        rendered. Rendering is minutes of CPU; spending it on a seed that
        has already dropped out of the top ten is the expensive mistake.

    So it is its own thread with its own lifecycle. Play/pause owns rolling
    and scoring; this owns rendering, and the only thing the two share is
    `bump()` — "the ranking moved, re-plan".

    Re-planning is the whole design. The plan is never a stored queue; it
    is recomputed from the candidate stores every pass, so the newest top
    ten is always what gets rendered and a demotion simply stops being in
    the answer. `biome_renderer batch` re-reads the store itself and skips
    files that already exist, so a pass costs nothing where there is
    nothing to do.
    """

    #: The two batch passes, low first so a thumbnail appears while the
    #: expensive one is still running. MIRRORS biome_renderer.batch_render's
    #: geometry — and score-dimensions._map_coverages, which tells the
    #: overlays how many blocks each of these covers.
    PASSES = (("rendering_low", 1024, 8, 256, ""),
              ("rendering_high", 2048, 16, 512, "_hires"))

    #: How long to sleep when everything is rendered. Short enough that a
    #: roll finishing elsewhere is picked up promptly even if bump() is
    #: missed, long enough to cost nothing.
    IDLE_SECONDS = 30

    def __init__(self, config, seedtest, workers=0):
        self.config = config
        self.seedtest = seedtest
        # A cold bank is 81 targets x up to 20 missing images, which is hours
        # of CPU — and unlike the old arrangement it now starts the moment the
        # viewer opens. batch_render defaults to every core, so leave two for
        # the machine you are still using. Same lesson as --census-workers.
        import multiprocessing
        self.workers = workers or max(1, multiprocessing.cpu_count() - 2)
        self.lock = threading.Lock()
        self.thread = None
        self.stop_flag = threading.Event()
        self.wake = threading.Event()
        self.state = {"rendering_low": [], "rendering_high": [],
                      "render_pending": 0, "render_done": 0,
                      "render_stage": "idle"}

    def snapshot(self):
        with self.lock:
            return dict(self.state)

    def _set(self, **kw):
        with self.lock:
            self.state.update(kw)

    def bump(self):
        """The ranking moved — drop the current plan and recompute."""
        self.wake.set()

    def start(self):
        if self.thread and self.thread.is_alive():
            return
        self.stop_flag.clear()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_flag.set()
        self.wake.set()

    def _plan(self):
        """[(name, missing_count)] for targets whose top-N lacks images.

        Read fresh every pass from the candidate stores, so it is always the
        CURRENT top ten. Most-missing first: a dimension with no renders at
        all is more useful to work on than one that is nine-tenths done.
        """
        from dimension_profiles import load_config, rollable
        import candidates as cmod
        config = load_config(self.config)
        targets = {**{w["name"]: w for w in config.get("worlds", [])},
                   **{d["name"]: d for d in config["dimensions"] if rollable(d)}}
        cdir = cmod.candidates_dir(Path(self.config))
        renders = Path(self.seedtest) / "renders"
        plan = []
        for name in targets:
            store_path = cdir / f"{name}.json"
            if not store_path.exists():
                continue
            missing = 0
            for seed in _top_seeds(cmod.load_store(store_path), RENDER_TOP):
                for _label, _size, _scale, _res, suffix in self.PASSES:
                    if not (renders / name / f"{seed}{suffix}.png").exists():
                        missing += 1
            if missing:
                plan.append((name, missing))
        plan.sort(key=lambda p: -p[1])
        return plan

    def _run(self):
        while not self.stop_flag.is_set():
            self.wake.clear()
            try:
                plan = self._plan()
            except Exception as exc:
                self._set(render_stage=f"plan failed: {str(exc)[:120]}")
                self.wake.wait(self.IDLE_SECONDS)
                continue
            if not plan:
                self._set(render_stage="idle", render_pending=0,
                          rendering_low=[], rendering_high=[])
                self.wake.wait(self.IDLE_SECONDS)
                continue
            self._set(render_pending=sum(n for _n, n in plan))
            for name, _missing in plan:
                if self.stop_flag.is_set():
                    break
                # A bump means the ranking moved under us. Abandon the rest
                # of the plan and rebuild it rather than finishing a list
                # that may no longer describe the top ten.
                if self.wake.is_set():
                    break
                self._render_target(name)
            self._set(rendering_low=[], rendering_high=[])

    def _render_target(self, name):
        for label, size, scale, res, suffix in self.PASSES:
            if self.stop_flag.is_set() or self.wake.is_set():
                return
            self._set(render_stage=label, **{label: [name]})
            argv = [sys.executable, str(SCRIPT_DIR / "biome_renderer.py"), "batch",
                    "--config", self.config, "--seedtest", self.seedtest,
                    "--dims", name, "--top", str(RENDER_TOP), "--size", str(size),
                    "--scale", str(scale), "--sample-res", str(res),
                    "--workers", str(self.workers)]
            if suffix:
                argv += ["--suffix", suffix]
            try:
                subprocess.run(argv, capture_output=True, text=True, timeout=3600)
            except Exception as exc:
                self._set(render_stage=f"{name}: render failed — {str(exc)[:120]}")
            self._set(**{label: []})
            with self.lock:
                self.state["render_done"] += 1


class Pipeline:
    """Roll -> enrich -> re-rank, driven from the page's play/pause.

    One controller thread per viewer, cycling dimensions round-robin. This
    is arithmetic only: generate seeds, enrich what survives, re-rank.

    It does NOT render. Rendering is minutes of CPU per candidate and has
    nothing to do with whether a roll is in progress, so it lives in
    RenderWorker on its own thread and runs continuously. The one coupling
    is `self.renderer.bump()` after a re-rank: the ranking moved, so the
    render plan should be rebuilt from the new top ten.

    Re-ranking is GLOBAL, not per-candidate — clutter is scored against the
    median of a dimension's own candidates, so an arrival can demote a seed
    that has already been rendered. That is exactly why the render plan is
    recomputed rather than queued.
    """

    def __init__(self, config, seedtest, finalise_args, biome_params,
                 renderer=None):
        self.config = config
        self.seedtest = seedtest
        self.finalise_args = finalise_args
        self.biome_params = biome_params
        self.renderer = renderer
        self.lock = threading.Lock()
        self.thread = None
        self._backfill = None
        self.stop_flag = threading.Event()
        self.state = {
            "running": False, "target": 0, "cycles": 0, "rolled": 0,
            "enriched": 0, "surveyed": 0, "rendered": 0,
            "stage": "idle", "dim": None, "only_dim": None,
            "enriching": [], "rendering_low": [], "rendering_high": [],
            "generation": 0, "log": [], "error": None, "backfill": None,
        }

    def snapshot(self):
        with self.lock:
            return dict(self.state, log=list(self.state["log"])[-40:])

    def _set(self, **kw):
        with self.lock:
            self.state.update(kw)

    def _log(self, msg):
        with self.lock:
            self.state["log"].append(msg)
            del self.state["log"][:-200]

    def _bump(self):
        """Signal the page that the ranking changed."""
        with self.lock:
            self.state["generation"] += 1

    def start(self, count, pool, only_dim=None):
        with self.lock:
            if self.state["running"]:
                return False
            self.stop_flag.clear()
            self.state.update(running=True, target=int(count), stage="starting",
                              error=None, cycles=0, only_dim=only_dim)
        self.thread = threading.Thread(target=self._run,
                                       args=(int(count), int(pool), only_dim),
                                       daemon=True)
        self.thread.start()
        return True

    def stop(self):
        self.stop_flag.set()
        self._set(stage="stopping")

    def _sh(self, argv, timeout):
        return subprocess.run(argv, capture_output=True, text=True, timeout=timeout)

    def _run(self, count, pool, only_dim=None):
        try:
            self._ensure_warmup()
            from dimension_profiles import load_config, load_difficulty, rollable
            from structure_placement import load_structure_sets
            import candidates as cmod

            config = load_config(self.config)
            difficulty = load_difficulty(self.config)
            targets = {**{w["name"]: w for w in config.get("worlds", [])},
                       **{d["name"]: d for d in config["dimensions"] if rollable(d)}}
            cdir = cmod.candidates_dir(Path(self.config))
            ssd = Path(self.seedtest) / ".structure_sets"
            all_sets = load_structure_sets(str(ssd)) if ssd.exists() else {}

            names = list(targets)
            if only_dim:
                if only_dim not in targets:
                    raise RuntimeError(f"unknown dimension: {only_dim}")
                names = [only_dim]
            while not self.stop_flag.is_set() and self.state["rolled"] < count:
                for name in names:
                    if self.stop_flag.is_set() or self.state["rolled"] >= count:
                        break
                    dim = targets[name]
                    batch = max(1, min(BATCH_SEEDS, count - self.state["rolled"]))
                    self._cycle(name, dim, config, difficulty, cdir, all_sets, batch, pool)
                with self.lock:
                    self.state["cycles"] += 1
            self._set(stage="stopped" if self.stop_flag.is_set() else "complete",
                      dim=None, running=False)
        except Exception as exc:
            self._log(f"pipeline failed: {exc}")
            self._set(stage="failed", error=str(exc)[:400], running=False)

    def _cycle(self, name, dim, config, difficulty, cdir, all_sets, batch, pool):
        # 1. Generate + disqualify (fast_roller banks rejects itself).
        self._set(stage="rolling", dim=name)
        r = self._sh([sys.executable, str(SCRIPT_DIR / "fast_roller.py"),
                      "--config", self.config, "--seedtest", self.seedtest,
                      "--dims", name, "--count", str(batch),
                      "--tier1-pool", str(pool)], 1800)
        if r.returncode != 0:
            self._log(f"{name}: roll failed — {(r.stderr or '').strip()[-200:]}")
            return
        with self.lock:
            self.state["rolled"] += batch
        self._log(f"{name}: rolled {batch}")

        # 2. Enrich what survived.
        self._set(stage="enriching", enriching=[name])
        n = _enrich_dim(name, dim, config, difficulty, cdir, all_sets, ENRICH_TOP)
        s = _survey_dim(name, dim, config, difficulty, cdir, self.biome_params, ENRICH_TOP)
        with self.lock:
            self.state["enriched"] += n
            self.state["surveyed"] += s
            self.state["enriching"] = []
        if n or s:
            self._log(f"{name}: enriched {n}, surveyed {s}")

        # 3. Re-rank on the enriched data, then republish the page data.
        self._set(stage="ranking")
        self._sh([sys.executable, str(SCRIPT_DIR / "score-dimensions.py"), "rescore",
                  "--config", self.config, "--seedtest", self.seedtest], 600)
        self._sh([sys.executable, str(SCRIPT_DIR / "score-dimensions.py"),
                  "finalise", *self.finalise_args], 600)
        self._bump()

        # Rendering is NOT step 4 any more. It belongs to RenderWorker, which
        # runs whether or not anything is rolling — see its docstring. All the
        # roll loop does is tell it the ranking moved.
        if self.renderer is not None:
            self.renderer.bump()

    def enrich_all_async(self):
        """Backfill structures and biomes for every banked dimension.

        Enrichment used to happen only as part of rolling a dimension, so the
        detail panel's structure and biome lists were empty for anything the
        current session had not rolled — 79 of 81 dimensions on a real bank.
        Clicking almost any candidate showed the thin fallback, which reads as
        the lists having been lost rather than never populated.

        It is pure arithmetic over banked seeds, so it runs at startup in the
        background and needs neither Docker nor a roll.
        """
        if self._backfill and self._backfill.is_alive():
            return
        self._backfill = threading.Thread(target=self._enrich_all, daemon=True)
        self._backfill.start()

    def _enrich_all(self):
        try:
            from dimension_profiles import load_config, load_difficulty, rollable
            from structure_placement import load_structure_sets
            import candidates as cmod

            if not Path(self.biome_params).exists():
                return
            config = load_config(self.config)
            difficulty = load_difficulty(self.config)
            targets = {**{w["name"]: w for w in config.get("worlds", [])},
                       **{d["name"]: d for d in config["dimensions"] if rollable(d)}}
            cdir = cmod.candidates_dir(Path(self.config))
            ssd = Path(self.seedtest) / ".structure_sets"
            all_sets = load_structure_sets(str(ssd)) if ssd.exists() else {}

            total = len(targets)
            done = added = 0
            for name, dim in targets.items():
                if self.stop_flag.is_set():
                    break
                try:
                    added += _enrich_dim(name, dim, config, difficulty, cdir,
                                         all_sets, ENRICH_TOP)
                    added += _survey_dim(name, dim, config, difficulty, cdir,
                                         self.biome_params, ENRICH_TOP)
                except Exception as exc:
                    self._log(f"{name}: backfill failed — {exc}")
                done += 1
                with self.lock:
                    self.state["backfill"] = f"{done}/{total}"
            with self.lock:
                self.state["backfill"] = None
            if added:
                self._log(f"backfill: enriched {added} candidate record(s)")
                # Enrichment feeds the clutter score, so the ranking can move.
                self._sh([sys.executable, str(SCRIPT_DIR / "score-dimensions.py"),
                          "rescore", "--config", self.config,
                          "--seedtest", self.seedtest], 1800)
                self._sh([sys.executable, str(SCRIPT_DIR / "score-dimensions.py"),
                          "finalise", *self.finalise_args], 900)
                self._bump()
                if self.renderer is not None:
                    self.renderer.bump()
        except Exception as exc:
            self._log(f"backfill failed: {exc}")
            with self.lock:
                self.state["backfill"] = None

    def _ensure_warmup(self):
        """Warmup is a prerequisite, not a separate command the user runs.

        roll-all.sh owns the Docker-backed extraction; calling it with a zero
        pool performs warmup and rolls nothing, so the viewer works from a
        clean checkout with no prior seed-roll.
        """
        have_sets = (Path(self.seedtest) / ".structure_sets").exists()
        have_params = Path(self.biome_params).exists()
        if have_sets and have_params:
            return
        self._set(stage="warmup")
        self._log("warmup: extracting structure sets and biome params (needs Docker)")
        r = self._sh(["bash", str(SCRIPT_DIR / "roll-all.sh"),
                      "--warmup-only"], 3600)
        if r.returncode != 0:
            raise RuntimeError(f"warmup failed: {(r.stderr or '').strip()[-300:]}")
        self._log("warmup: done")


BATCH_SEEDS = 25
_pipeline = None
_renderer = None


_fork_schema_cache = None


def _build_fork_schema(config_path, seedtest=None):
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
    from seed_paths import biome_params_path
    biomes = {}
    bp_path = Path(biome_params_path(seedtest))
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
def _validate_fork_config(raw, config_path, seedtest=None):
    schema = _build_fork_schema(config_path, seedtest)
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
                self._respond_json(_build_fork_schema(self.config_path,
                                                      self.seedtest))
            except Exception as exc:
                self._respond_json({"error": str(exc)[:300]}, 500)
            return
        if self.path.startswith("/dim-config"):
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
        if self.path == "/pipeline-status":
            if not _pipeline:
                self._respond_json({"running": False, "stage": "unavailable"})
                return
            # Rolling state and rendering state are two different lifecycles
            # now; the page shows one status line, so they merge here. The
            # renderer's keys win — it is the one doing the rendering.
            snap = _pipeline.snapshot()
            if _renderer:
                snap.update(_renderer.snapshot())
            self._respond_json(snap)
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
        elif self.path == "/pipeline/start":
            self._handle_pipeline_start()
        elif self.path == "/pipeline/stop":
            if _pipeline:
                _pipeline.stop()
            self._respond_json({"ok": True})
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

    def _handle_pipeline_start(self):
        if not _pipeline:
            self._respond_json({"error": "pipeline unavailable"}, 500)
            return
        try:
            body = self._read_json()
        except Exception:
            body = {}
        # `or` would swallow a literal 0 and silently substitute the default,
        # starting a full run for a request that asked for none.
        raw_count = body.get("count")
        raw_pool = body.get("pool")
        try:
            count = 100 if raw_count in (None, "") else int(raw_count)
            pool = 5000 if raw_pool in (None, "") else int(raw_pool)
        except (TypeError, ValueError):
            self._respond_json({"error": "count and pool must be integers"}, 400)
            return
        if count < 1:
            self._respond_json({"error": "count must be >= 1"}, 400)
            return
        if pool < 1:
            self._respond_json({"error": "pool must be >= 1"}, 400)
            return
        # Validate the dimension HERE, not in the worker: an unknown name
        # would otherwise return ok, flip the button to "running", and only
        # surface as a failed stage seconds later.
        only = (body.get("dim") or "").strip() or None
        if only:
            try:
                known = _target_names(self.config_path)
            except Exception as exc:
                self._respond_json({"error": f"could not read config: {exc}"}, 500)
                return
            if only not in known:
                self._respond_json({"error": f"unknown dimension: {only}"}, 400)
                return
        started = _pipeline.start(count, pool, only)
        self._respond_json({"ok": started,
                            "error": None if started else "already running"})

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

        from seed_paths import biome_params_path
        biome_params = str(biome_params_path(self.seedtest))
        if not Path(biome_params).exists():
            self._respond_json({"ok": False, "error": "biome_params.json not found"})
            return

        r = subprocess.run(
            [sys.executable, str(SCRIPT_DIR / "biome_renderer.py"),
             "render", "--seed", seed, "--output", str(out_path),
             "--biome-params", biome_params,
             *_render_args_for(self.config_path, dim)],
            capture_output=True, text=True, timeout=120)
        if r.returncode == 0 and out_path.exists():
            self._respond_json({"ok": True,
                                "path": f"renders/{dim}/{seed}_hires.png"})
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
            from seed_paths import biome_params_path
            biome_params = str(biome_params_path(self.seedtest))
            hires_path.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(
                [sys.executable, str(SCRIPT_DIR / "biome_renderer.py"),
                 "render", "--seed", seed, "--output", str(hires_path),
                 "--biome-params", biome_params,
                 *_render_args_for(self.config_path, dim)],
                capture_output=True, text=True, timeout=120)

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
            clean_config, field_errors = _validate_fork_config(
                form_config, self.config_path, self.seedtest)
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


def _port_answers(port):
    """Is the thing holding this port one of our own viewers?

    /pipeline-status is served by nothing else, so a 200 with the expected
    shape identifies a sibling viewer rather than some unrelated dev server
    that happened to grab the port.
    """
    try:
        import urllib.request
        with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/pipeline-status", timeout=1.5) as r:
            return "stage" in json.loads(r.read().decode())
    except Exception:
        return False


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
    ap.add_argument("--render-workers", type=int, default=0,
                    help="processes for the background render worker "
                         "(default: CPU count minus 2). It runs continuously "
                         "from viewer start, not only while rolling")
    ap.add_argument("--enrich-top", type=int, default=ENRICH_TOP,
                    help=f"candidates per dim to enrich before re-ranking "
                         f"(default {ENRICH_TOP}; renders cover the top {RENDER_TOP})")
    args = ap.parse_args()

    sys.path.insert(0, str(SCRIPT_DIR))
    import candidates as _cmod
    _cmod.set_bank_root(args.seedtest)

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

    # Re-finalise to regenerate index.html with current scores
    subprocess.run([sys.executable, str(SCRIPT_DIR / "score-dimensions.py"),
                   "finalise", *finalise_args],
                  capture_output=True, text=True)

    handler = partial(ViewerHandler, directory=args.seedtest)
    ViewerHandler.seedtest = args.seedtest
    ViewerHandler.config_path = args.config
    ViewerHandler.finalise_args = finalise_args
    ViewerHandler.winner_overlay = args.winner_overlay or ""

    from seed_paths import biome_params_path
    global _pipeline, _renderer
    _renderer = RenderWorker(args.config, args.seedtest,
                             workers=args.render_workers)
    _pipeline = Pipeline(args.config, args.seedtest, finalise_args,
                         str(biome_params_path(args.seedtest)),
                         renderer=_renderer)

    try:
        server = ThreadingHTTPServer(("127.0.0.1", args.port), handler)
    except OSError as exc:
        if exc.errno not in (errno.EADDRINUSE, errno.EACCES):
            raise
        # Almost always a viewer you already have open, not a problem to
        # debug — and a socketserver traceback says none of that. It also
        # buries the useful fact, which is that the thing you wanted is
        # already running at the address you were about to be given.
        url = f"http://127.0.0.1:{args.port}/"
        if _port_answers(args.port):
            print(f"A seed viewer is already running at {url}", file=sys.stderr)
            print("Open it, or stop it first:  pkill -f viewer-server.py",
                  file=sys.stderr)
        else:
            print(f"Port {args.port} is taken by something that is not a "
                  f"seed viewer.", file=sys.stderr)
            print(f"  Find it:  lsof -nP -iTCP:{args.port} -sTCP:LISTEN",
                  file=sys.stderr)
            print(f"  Or pick another port:  ./dev seed-viewer --port "
                  f"{args.port + 1}", file=sys.stderr)
        return 2
    print(f"viewer server: http://127.0.0.1:{args.port}/", flush=True)

    # Serve in a background thread so batch renders can run with the server up
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    # Two independent background lifecycles, neither blocking the server:
    #
    #   RenderWorker  runs from now until the process exits, filling in the
    #                 current top-10 renders of every target. It is NOT gated
    #                 on play/pause — a bank that already has candidates gets
    #                 its images whether or not you ever press Start.
    #   Pipeline      rolls and scores, and only when the page says to.
    #
    # Plus a one-off backfill of structures/biomes for everything already
    # banked, so the detail panel's lists are populated for dimensions this
    # session never rolled.
    _renderer.start()
    _pipeline.enrich_all_async()
    print("Rendering the current top 10s in the background. "
          "Press Start in the viewer to roll more seeds.", flush=True)

    try:
        server_thread.join()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    # main() returns a code on the paths that refuse to start; without this
    # `./dev seed-viewer` would exit 0 after printing why it did nothing.
    sys.exit(main() or 0)
