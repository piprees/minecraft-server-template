# Next steps — the working queue

Agreed with Pip 2026-07-23. Work top to bottom; each item links to the doc that specifies it. Update this file as items complete (mark done, move lessons into AGENTS/READMEs, delete the item's idea doc once its content is captured — the pattern used for the portal-concepts docs).

## 1. Custom world settings, Tiers 2–3 — DONE 2026-07-23

Spec: `vanilla-custom-world-settings.md` (Tiers 1–3 shipped; only the precision-placement section — item 2 below — remains from that doc).

- **Tier 2**: `checkerboard` (+`checkerboardScale`, `CheckerboardBiomeSampler` parity, live-verified probe-for-probe), `superflat` custom `layers` + `flatBiome`, `seedRoll: {skip: true}` in mod schema + `rollable()`.
- **Tier 3**: `settingsOverrides` whitelist, per-biome `parameters` (object-form biomes entries), per-set `structures.spacing` — each landed with its roller counterpart (`build_mixed_entries` param_overrides, seed_worker fluid check, tier-1 spacing maths) and live-verified.

## 2. biomePatches — DONE 2026-07-23

Spec: `vanilla-custom-world-settings.md` § Precision placement. Shipped beyond the original sketch after design discussion with Pip: three modes (stamp / clipped swap via `replace` / global swap via `scope: "global"` with explicit target or area-selector), `shape: circle|square`, `blend` edge jitter (bit-mirrored value noise in `PatchedBiomeSampler`). Codec-registered `customdimensions:patched`; level.dat round-trip and a codec evolution verified live; all modes oracle-verified. Guaranteed spawn biome at (0,0) now deletes the spawn-filter lottery. Still gated behind this section: **fixed structure placements** (same spec section — next candidate alongside item 3).

## 3. Exit shrines + dimension links & exit conditions — DONE 2026-07-23

Spec: `exit-shrine-structure.md` (both parts shipped; doc carries the verified-status summaries).

- **Part 2**: ExitTarget descriptors on every exit surface, `exits` trigger block (void/death/death:cause/death:mob/enderPearl/fallFrom), validator rules, bot-verified live (respawnAt awaits first real-player death — mixin applies clean).
- **Part 1**: `adventure:exit_shrine` jigsaw (jar datapack; `scripts/gen-exit-shrine.py` generates the NBT), beacon-marked frames self-register on chunk load (rotation-aware), frequency-gated set (0.001 shipped, x1000 for opted-in dims — never leaks into base worlds), `exit_shrine` STRUCTS entry + tier-1 frequency parity. Bot-verified: generation at spacing, detection under jigsaw rotation, traversal home.

## 4. Seed group rolling — DONE 2026-07-23

Spec: `seed-group-rolling.md` (deleted — content lives in `scripts/seed/README.md` § Seed-Group Rolling, `docs/customisation.md` § Worldgen: seed rolling, and mods/AGENTS §Seed rolling pipeline). Shipped: `generation_fingerprint()`/`generation_payload()` in dimension_profiles (validated against the spec's 8-group/31-dim table — exact match), fast_roller groups by fingerprint with a shared tier-1 pool + union survivors + `MemoSampler` (per-member rows proven bit-identical to solo runs), candidates stamped with their measurement fingerprint, injective winner assignment within groups at finalise (pins claim first), and DRIFTED warnings in status/finalise. **Design deviation from the spec**: the bank stays per-dim (every member banks every group seed's rows) rather than re-keying to a fingerprint-keyed store — same saving, zero schema migration, all existing tooling untouched. Corollary rule now in mods/AGENTS: any new generation-affecting config field MUST be added to `generation_payload()`.

## 5. Further portal customisations — TIER 1 DONE 2026-07-23; Tiers 2–3 remain

Spec: `further-portal-customisations.md`. Tier 1 shipped and bot-verified: `FrameMatcher` (plain/`#tag`/list/`colorGroup` accept forms), 16 jar-datapack colour tags, `framePlaceBlock` (accepting ≠ placing), `orientation` gating (default "any" = today's behaviour — the spec's "default vertical" lost to its own back-compat principle), matcher-aware igniter ordering and zone validation. Two incidents en route, both fixed + documented in mods/AGENTS: (1) persisted `frameBlock` must stay a plain parseable id or older jars crash-loop on downgrade; (2) `NetherPortalProtectionMixin` — netherportalspread's corruption spread was popping ALL custom-framed arrival portals (production-affecting, pre-existing; root-caused via a temporary portal-pop stack-trace mixin). Remaining tiers: door/doorway/end_exit shapes + per-part materials (medium), end_gateway + pattern templates (deep), exit-shrine residuals (frame substitution, per-border spacing) — each shape needs its own bot recipe. NEW (Pip, 2026-07-24): **portal auras** — per-dimension environmental spread around portals (corruption/fire for hard dims, trees/moss for forest dims); spec in `portal-auras.md`, slots after Tier 2; carries a pack-curation decision on removing netherportalspread once it lands.

## 6. Fork-config GUI

Spec: `fork-dimension-config-gui.md`. Pure Python/HTML in `scripts/seed/`. NOTE: the old reference to `fork-gui-implementation-prompt.md` is STALE — that file was deleted in the `0dc30dc` ideas tidy-up; its content was folded into `fork-dimension-config-gui.md` (verify coverage against `git show 0dc30dc` before starting, the worked example for absorbed docs).

## 7. Optional-mods hardening (last)

Spec: `optional-mods-hardening.md`. The open boot-breaker: removing Tectonic/Terralith breaks boots because the `adventure:wide`/ `compressed` presets reference their registries. Round 1 = self-contained noise presets; round 2 = removal-matrix smoke coverage.

## Running-start notes for the next agent — session 2026-07-23/24 additions

- **Persisted-state downgrade rule**: anything serialised into `portal_links.json` (or any state file) must stay parseable by every jar that might read it back — deploys roll back. A `#tag` in a persisted `frameBlock` crash-looped v3.6.0 (`Identifier.of` in an uncaught world-tick path). mods/AGENTS carries the full rule.
- **netherportalspread eats custom-framed portals** — root-caused and fixed (`NetherPortalProtectionMixin`); if a portal block ever vanishes mysteriously again, the diagnostic that worked was a temporary `World.setBlockState` HEAD mixin logging a stack trace on portal→air. Four plausible theories (leaf decay, stale zones, mod tracking, site curse) were all wrong; the trace was right in one cycle. Instrument early, don't armchair.
- **Source portal interiors contain NO portal blocks** — zones are invisible; only arrival portals carry real NETHER_PORTAL blocks. Assert source-side success by bot traversal, never interior probes.
- **`execute in <dim> run tp Bot ...` teleports ACROSS dimensions** — it's the standard way to move the bot between worlds, and the standard way to accidentally do so.
- **PLAYER_IN_ZONE is edge-triggered and can go stale**: a bot returned into a zone it never "exited" (flag-wise) won't re-teleport — step out and back in (`tp` away, sleep 2, `tp` back).
- **`/setblock` and `/fill` fire NO neighbour updates** (flag 2) — never use them to test update-driven behaviour; use falling blocks or player actions (bot `attack once`).
- **The existing-portal reuse branch does not log** — only fresh creations print "Created portal"; a traversal with no log line means `findExistingPortal` matched. Stacked test frames climb the heightmap (`findSurfaceY`), so repeated traversals at one site create portals at ever-higher Y — clean sites between runs.
- **Empty RCON responses under load are failures-to-recheck**, and `execute at <entity> if block` in particular returns empty unreliably — prefer `execute in <dim> if block <abs coords>`.
- **elfydd's `.stack/current` now points at the released bundle (v3.6.0)**, NOT the repo — seed-script edits are no longer live on elfydd; re-point to `v3-dev` (symlink to the repo) if roller work needs live iteration.
- **Release pending Pip's word**: `fc27767` (seed-group rolling) + `67e93dc` (map fixes) + `f22423a` (portals Tier 1 + NetherPortalProtectionMixin). The protection fix is player-facing on production (return portals silently dying) — lean quick. elfydd's local `data/mods/customdimensions.jar` runs the unreleased Tier 1 build.
- **Still awaiting a real player on production**: the `respawnAt` death redirect and the first organic exit-shrine encounter (carpet bots can't respawn).

## Running-start notes for the next agent (accrued 2026-07-23)

Verification-loop traps hit THIS session (beyond what AGENTS documents):

- **c2me re-patch before EVERY `docker stop/start`** — now in mods/AGENTS.md §2. Three consecutive cycles ran unpatched here before it was caught. The idempotent snippet lives in `dev-up.sh` (search `useDensityFunctionCompiler`).
- **Chunk generation from RCON can wedge the main thread permanently** (Epic Dungeons `epic:chests/DungeonZombie` invalid loot id + c2me — AGENTS known-issues). Run any forceload/if-block over ungenerated chunks as a BACKGROUND command with a timeout; recovery is `docker stop -t 90 mc && docker start mc`.
- **`docker exec -i` eats your loop's stdin** — pipe-driven RCON loops silently stop after one iteration; append `</dev/null` to every docker exec inside a `while read` loop.
- **zsh does not word-split unquoted vars** — `set -- $probe` patterns from bash break; use `read -r a b c <<< "$str"`.
- **`unzip -l` with a glob matching two files** treats the second as a member filter and lists nothing — always name the jar exactly.
- **`locate biome` samples on a 32-block horizontal grid** — distances quantise; classify inside/outside rather than expecting exact 0s near edges.
- **Python sampler parity is REGION-level** — large regions and half-plane parameter tests match probe-for-probe; sliver biomes (sparse_jungle etc.) land within an approximation envelope. Server measurement stays ground truth; don't chase point mismatches.
- **Fixture-dim lifecycle takes TWO restarts to fully clear** — level.dat re-creates deleted fixture dims for one boot (orphan reconciliation unloads them; the registry entry clears the restart after). Fixture cleanup = delete config + world dir + fingerprints entry, then expect one boot of orphan-unload chatter.
- **elfydd's stack `current` → `v3-dev` symlinks straight to this repo** — seed-script changes are live locally without any sync step.
- **Bot recipes**: `gamemode survival Bot` before `/damage` (spawns creative-ish/invulnerable); carpet fake players never respawn, so the `respawnAt` exit action is still awaiting its first real-player death as live confirmation; `touch /data/.skip-pause` before bot sessions and delete it in cleanup.
- **Structure-set extraction cache**: `.seedtest/.structure_sets` won't contain `adventure:exit_shrines` until the next extraction run — roller work touching shrine placement should re-extract first.
