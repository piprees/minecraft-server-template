# V2 results — linked 26.3 consumer, both portal kinds, the login fix, Elfydd

Consumer: /tmp/claude-0/v2/consumer (scaffold, `./dev link` to the checkout, `MC_VERSION_OVERRIDE='26.3'`, `JVM_DD_OPTS='customdimensions.e2e=true'`, rig-26 x4). Evidence: /tmp/claude-0/v2/evidence/, run dirs /tmp/claude-0/v2/runs/.

| Check | Result | Evidence |
| --- | --- | --- |
| Merge P11 (squash) | pass: dc41f10 | git |
| Merge M8b (its two commits, squashed) | pass: 01c1e46 (README row conflict resolved) | git |
| Mod build --rerun-tasks | pass: 1245 tests, 0 fail (1252+ after V2's tests) | build-mod.txt |
| Companion build --rerun-tasks | pass: 65 tests | build-client.txt |
| Quick gate | pass | gate1.txt |
| `cutover/apply.sh --dry-run`, clean clone | pass: 0 `!` lines | apply-dry.txt |
| Served jar = checkout build | pass (sha256 checked after every `./dev up`) | progress.log |
| itzg `RESOURCE_PACK*` in server.properties | pass: url, sha1 2f9e6755…, id 64475e80-…, prompt, require=false | evidence/server-properties-pack.txt |
| mc log tints | pass: 48 of 48 tripwire states granted; tints on for pack 64475e80 | dev-up-1.log, mc log |
| pack-web serves the zip | pass: 200, `Cache-Control: no-cache`, body sha1 = printed sha1 | evidence/pack-web-head.txt |
| Bedrock ping 19132 | pass: answers, protocol 2193, maxPlayers 20 | evidence/bedrock-ping.txt |
| Bedrock login through Geyser | not run: no Bedrock client on this machine | — |
| Instrument: `customdim e2e-state` | pass after two fixes (`.seed-rolling` root-owned; message keys without defaults) | evidence/e2e-state-1.json, e2e-state-nobody.json |
| Instrument: bridge `/health`, `/state`, `/portals`, `/portal-census` | pass: `customdimensionsclient 0.1.0`, mc 26.3, worldLoaded, fps 23; portals and census JSON | evidence/bridge-*.json, portals-sky-lit.json, census-sky-lit.json |
| Vanilla portal, pack declined | pass 55/55 (after the Z-title and flat-frame aim fixes); X .44 Z .39 Y .21 vanilla colour share | evidence/vanilla-pass1-results.md, vanilla1b-sheet.png (looked at) |
| Vanilla portal, pack accepted | pass 65/65 (after the PolymerTexturedBlock fix and the End-rule fix); tint share .23/.21/.17 | evidence/vanilla-pass2-results.md, vanilla2c-sheet.png (looked at: light-blue panes, yellow flat pane) |
| Companion portal, all rows incl. drawn | pass 50/50 (after the wall-brightness fix); husk pixel share 0.040; arrow drawn moving 22.2 -> 23.2 | evidence/companion-results.md, companion1-sheet.png (looked at) |
| Frame rate, software GL | min 23 fps over 5 s (floor 10) | companion-results.md row 27 |
| Saved in a lazy level, server restart | pass 4/4: back in e2e_two at 6.5,-59,9.5; `login: opening e2e_two` | evidence/saved-level/restart-back.log |
| Saved in a lazy level, idle unload, no restart | pass: unload -> lazy; rejoin 4/4 | evidence/saved-level/unload.log, unload-back.log |
| Saved in an unavailable level -> world spawn | unit tests + self-test line (PrepareSpawnTaskMixin); no e2e (needs a deleted definition) | SavedLevelTest, selftest-final.txt |
| Playerdata workaround | removed: TintGate rejoined in e2e_one after a restart with its data intact | vanilla2d during.log |
| Portal matrix, dry run | pass: 83 bays (82 v2 + 4 rig − 3 skipped) | matrix-dry.log |
| Portal matrix, full, with renders | not run (budget); command: README § On 26.x | — |
| e2e self-tests (matrix, lib, portal-colour) | pass | — |
| Items 2, 3, 5, portal-aperture | not run (budget) | — |
| Idle unload | pass (saved-level unload phase; the_ashgrove idle unload under Chunky) | chunky-long.txt |
| Iris / Distant Horizons rig variants | not run: budget spent on the login fix and the CI hang | — |
| K17 Floodgate fwhitelist on repeat parity | does not reproduce: 49/49, 49/49, 48/48 with VanillaGate online, 49/49 | parity-1..4.txt |
| Starter kit bundle line | measured: `/starterkit set` writes P7's line with keys sorted | not-automated.md |
| Game rules on lazy levels | measured: one server-wide store | migration-notes.md step 13 |
| Overworld seed | `rcon seed` 20261022 = `.env` SEED = level-seed; `customdim info overworld` Seed 20261022; the overworld definition's seed 4705305050088502262 is not used | progress.log |
| Chunky keeps a lazy level open | measured: unload waits on Chunky's tickets, fails after 2400 ticks, Chunky completes, level closes after | p8-pregen.md |
| Pre-generation, 16-chunk radius x2 | overworld 15 s, +17.6 MiB; the_greenreach 17 s, +17.5 MiB; mspt P99 1.5 / 58 | chunky2-*.txt |
| Structure census, the_red_monument / the_end_citadel | not run (budget) | — |
| Full self-test, CI's server.properties | see selftest-final.txt | — |

## V2b (continuation): the matrix, Elfydd, end state

Measured on this build. Run folders: /tmp/claude-0/v2/runs/, client logs /tmp/claude-0/v2/elf-mx*/.

| Check | Result | Evidence |
| --- | --- | --- |
| Matrix mx9 (79 bays, V2's run) | invalid after bay 1: Rig landed one above the flat the_abyssal_shrine arrival (-425 91 -375, carved over water); no unprompted return (26.3 `Entity.setAsInsidePortal` tops up the cooldown while the player stands in a portal — javap); the fallback `tp $AX.5` put it at block -426, the ring's corner column, and it drowned (01:16:15) | elf-mx9/during.log, mc log |
| Matrix mx10 (2 bays after the return fix) | return pass on both (stepped out and back in); the re-light rows failed on the measurement (new arrival on the same cells) — the server logged `Created portal` both times; fixed to read that line | elf-mx10/during.log, progress.log 01:35 |
| Matrix mx11 | invalid: Rig suffocated in a wall 9 s after rejoining inside a bay shell during the build (01:33:35); the_glacial_drift ice ring melted under the bays' glowstone | elf-mx11/during.log |
| Matrix mx12 | 10 of 79 bays, then the player dead: 7 bays 21/21; the_bloodroot_wastes nylium ring 1/28 wrong then `OPENING_TOO_LARGE`; the_blossom_gardens return landed 16,80 (anchor exit `bed` → respawn point, as defined; the row expected the source frame); the_boneyard: the overworld source zone broke at 02:19:12 by the mod's one-second frame check with no player in the overworld (the failing cell is not logged), so the arrival was closed before the step-in and the return could not fire; Rig `was blown up by Ghast` in the_boneyard at 02:19:26 | runs/portal-matrix-20261111-015806 (341 pass, 11 fail) |
| Matrix, one valid complete run | not yet: the hardened script (protected player, random_tick_speed 0 and immediate_respawn for the run, invalid bays rebuilt and retried, `--resume`) is committed and self-tested (60/60); the run waits for the server lane | portal-matrix-selftest.sh |
| the_boneyard source break | open: reproduce with `--only the_boneyard`; read the frame's ring and opening cells in the second after `portal lit` and at the break | mc log 02:19:02–02:19:12 |
| Owner's-eye: unlit nylium frame | an upright crimson- or warped-nylium frame decays on random ticks before it is lit, and ignition is strict, so it refuses (`OPENING_TOO_LARGE` in the_bloodroot_wastes); T124 covers only a LIT frame. Affects the_bloodroot_wastes and the_twisted_groves | assertions.tsv rows 15, 309 |
| discord-sync restart loop | expected on a copy without Discord secrets, not a 26.3 defect: `DISCORD_GUILD_ID` empty → `int('')` ValueError at import (scripts/discord-sync.py:66); a task to make it idle is queued | `docker logs discord-sync` |
| mod-checker restart loop | expected here: `Modrinth API is unreachable` from containers on this machine | `docker logs mod-checker` |
| Structure census, the_red_monument / the_end_citadel | not run yet (server lane) | — |
| Items 2, 3, 5, portal-aperture, item 4 on the consumer | not run yet (server lane) | — |
| Iris / Distant Horizons rig variants | not yet run here; packs built: variants/companion-iris.mrpack (Sodium 0.9.3-beta.4, Iris 1.11.7, MakeUp-UltraFast 9.6 enabled), variants/companion-dh.mrpack (DH 3.3.4-26.3); sha1s match Modrinth | variants/ |
| Quick gate | pass, rc=0 (pytest and yamllint in /tmp/claude-0/v2/pylib) | gate2.txt |
| `cutover/apply.sh --dry-run`, clean clone at cadf6602 | pass: 0 `!` lines (add 7, change 91, delete 1456, move 193, already done 2) | apply-dry2.txt |

### Elfydd against the scaffold, and what the 26.3 boot kept

Real repo /home/user/elfydd at 0c3bea3; copy /tmp/claude-0/v2/elfydd after the patch; file lists elfydd-files.txt, scaffold-files.txt.

| Path | Real Elfydd | On the booted copy |
| --- | --- | --- |
| `dev`, `AGENTS.md`, `.env.example`, `.gitignore`, `.github/workflows/update.yml`, `server-power.yml` | the v5 scaffold | the v6 scaffold's |
| `.github/workflows/deploy.yml` | `deploy-reusable.yml@v5` | the scaffold's with `@v6` (the patch's step 4) |
| `ops`, `commands.json` | byte for byte the scaffold's | unchanged |
| `.github/dependabot.yml` | absent | the scaffold's |
| `.stack-version` | absent | `v6.0.0` |
| `README.md`, `overlay/assets/README.md`, `overlay/modpack/README.md` | Elfydd's own | kept byte for byte |
| `overlay/assets/{cover,favicon,icon,logo}.svg` | branding | kept byte for byte |
| `overlay/config/README.md`, `deploy-banner.txt` | prose, banner | kept; reach the stack config byte for byte |
| `overlay/modpack/manifest.json` | name Elfydd, summary | kept |
| `overlay/mods-extra.txt`, `mods-remove.txt` | comments only | kept |
| `overlay/config/custom-dimensions/dimensions/` | 36 seed-viewer files | deleted (step 5); nothing asks for them |
| `elfydd.code-workspace`, `reports/` (3 notes) | not scaffold files | kept |
| `.env` | not in the repo | written for the rehearsal: `BRAND_NAME='Elfydd'`, `BRAND_SLUG='elfydd'`, `STACK_VERSION='v6.0.0'`, `MC_VERSION_OVERRIDE='26.3'`, `IMAGE_TAG='5.27.1'` |

### After the lane came back (head bb1e0d03 + this package's commits)

| Check | Result | Evidence |
| --- | --- | --- |
| Current jar served on the Elfydd copy | pass: `./dev up` reinstalled it; served sha256 57737c5c… = the build, later fe6fe051… after the frame-check log change | progress.log 04:52, 05:14 |
| Seed guard on a pre-existing world (T245) | pass, measured on this build: two `seeds: … keeps generating from 20261022` WARN lines, for `minecraft:overworld` (definition 4705305050088502262) and `adventure:the_aviary` (7826077224469180302), whose region folders hold chunks; `the_nether` (no region files) generates from -6876984017876508741 and `the_end` from -1727347169663540530 | evidence/elfydd/seeds-boot.txt |
| the_boneyard source closed with nobody in the overworld (mx12, 02:19:12) | did not reproduce: `--only the_boneyard` 21/21, valid; the probe read every ring and opening cell each ~3 s from lighting to the matrix's own break step and found none wrong while the player was in the_boneyard. Code: the once-a-second check skips a zone whose chunks are not all resident, and `getBlockState` on the server thread loads a chunk rather than reading air, so an unloaded cell cannot read as a broken frame. Bone block has no random tick. mx12's own mc log went with the container's recreation, so its failing cell is unknown; a closing check now logs every ring (or opening) cell as `x,y,z=block` (commit 6c31a3c2) so the next one names itself | boneyard-probe.txt, runs/portal-matrix-20261111-045333 |

## V2c: the matrix closed, census, items, game rules, end state

Jar d0a143fc (commit a357c801) served on the Elfydd copy after `./dev up` (sha256 checked). The machine rebooted at 06:38; the client logs of mx9–mx12 (elf-mx9…12.log, elf-mx12/) and the uncited run folders were deleted for disk, so the mx9–mx12 rows above rest on progress.log.

### Portal matrix, 79 bays (mx13, runs/portal-matrix-20261111-051437; the four re-run in mx14, runs/portal-matrix-20261111-065331)

| Row | mx13 ok | mx13 fail | After the fix |
| --- | --- | --- | --- |
| ignite | 79 | 0 | — |
| travel | 79 | 0 | — |
| return | 79 | 0 | — |
| break | 79 | 0 | — |
| relight | 79 | 0 | — |
| proximity | 75 | 4 (assertions 368, 660, 1372, 1643) | 4 of 4 ok, 84/84 assertions, all valid |
| assertions | 1649 pass | 4 fail | — |

| Bay | Failure | Cause | Verdict |
| --- | --- | --- | --- |
| the_ember_fields_x | neighbour lit 05:47:48, closed 05:47:49: `ring … -6274,41,-6000=minecraft:copper_ore` | the lit primary's default aura (e2e-state `aura.spent` 4 → 10 between 05:47:43 and 05:47:51) converted a magma block of the neighbour's ring to far-side terrain | mod defect, fixed |
| the_twisted_groves_x | `OPENING_NOT_ENCLOSED … ran into minecraft:dirt at -5237, 41, -6000` | the primary's aura (spent 0 → 3 by 06:29:17) turned a warped-nylium ring cell of the unlit neighbour into dirt | mod defect, fixed |
| the_shallows_y | `OPENING_NOT_ENCLOSED axis=X cells=8 … glowstone at -5543, 43, -5997` | the primary's aura (spent 9–10 by 06:19:23) had eaten the flat neighbour's prismarine ring, so no flat opening closed and the fill climbed to the bay's ceiling | mod defect, fixed |
| the_boneyard_x | no second arrival: wanted 1, got 0 | the PRIMARY closed at 05:33:12, one second after the neighbour linked: `ring … -6626,40,-6000=minecraft:air`, which closed the shared arrival. The neighbour's aura had made its first pass; no aura path writes air, so the cell's exact change is not explained | closed by the same fix (did not recur in mx14) |

Build-time checks for all four bays passed (`28 of 28 cells` / `32 of 32`): the rig built every frame whole. Fix: `Aura.pass` takes a keep set (the source's own frame materials) and `PortalAuras.run` excludes every zone's opening and face neighbours in the level, not only its own (`Aura.java`, `PortalAuras.java`; test `AuraTest.aSecondFrameOfThePortalsOwnMaterialIsNeverEaten`, red without the keep set); 1302 tests, 0 fail.

| Check | Result | Evidence |
| --- | --- | --- |
| Structure census, the_red_monument | pass: lists every forced structure — `mes:monolith` 1, `mes:manuscript_shrine` 2, `mes:mythic_garden` 1, `mes:mystical_archway` 1 (5 forced, 4 ids) | evidence/census/the_red_monument.json `structures.census` |
| Structure census, the_end_citadel | pass: lists every want — `minecraft:end_city` 2 (nearest 988), `mes:phantom_citadel` 89, `mes:enderwatch_tower` 59 (nearest 895), `mes:ender_spire` 41, `mes:monolith` 65; excluded `mes:ruined_pillar` absent; 28 structure ids in all (terrain checks not applied, per the census note) | evidence/census/the_end_citadel.json |
| Item 2 (stronghold ritual) | pass 29/29 | runs/item2-*, items1/during.log |
| Item 3 (End exit) | fail 2/5: the exit rig stood and the player stood in its end_portal at 0 55 0 (starfield in 01-after-contact.png, looked at) but read `minecraft:the_end` 5 s and 15 s later; the mc log has no line for it. Cause not isolated (candidates: a credits screen the client never closed, or the portal cooldown from item 2's crossing) | runs/item3-20261111-070458 |
| Item 5 (crucible) | fail 15/33, rig: with Rig on the frame column and the chunk ready, `build-test-frame.sh`'s setblocks answered `That position is not loaded` (z 297–337); nothing after the build could run. Not isolated | items1/during.log |
| portal-aperture | not applicable on 26.x: its gates read the 1.21.1 `PortalAperture` class and the `aperture: emitted` heartbeat (README row says so) | items1/during.log |
| Item 4 (idle unload) | pass 4/4: open while occupied 330 s, `unloading e2e_two: no players for 5 min`, lazy again | evidence/item4/ |
| Game rules over RCON | pass: e2e_two `keep_inventory` true while the overworld answers the store's false; `immediate_respawn`, `reduced_debug_info` true in e2e_two; `customdim info e2e_two`: `Game rules of its own: immediate_respawn true, keep_inventory true, reduced_debug_info true` | evidence/rules-rcon.txt |
| Game rules on the client: immediate_respawn | observed: store set false, e2e_two true. Death in e2e_two → no death screen, `LevelLoadingScreen` 1 s later and the player in the overworld (03-e2e_two-died-1s.png "Loading terrain…"); death in the overworld → `DeathScreen` (04-overworld-died.png "You Died!"), both from the bridge's `currentScreen`, screenshots looked at | evidence/rules-client/ |
| Game rules on the client: keep_inventory | not proven: Rig was killed by a wither skeleton at the overworld respawn point 3 s after the e2e_two respawn, and a grave mod ("Your items are safe at your death location") takes the drop, so the diamond count cannot tell the rules apart | evidence/rules-client/, mc log 07:20:49–07:20:58 |
| Game rules on the client: reduced_debug_info | not observed: the bridge has no F3 input and does not report the flag; RCON only | — |
| Iris / Distant Horizons rig variants | not run here: budget (packs built, see V2b) | — |
| Seeds on a fresh consumer world | not run here: budget. What proves it: boot /tmp/claude-0/v2/consumer with no world data (one stack at a time) and read `execute in <dim> run seed` for the overworld, the_nether, the_end and the_aviary against their definitions, with no `seeds:` WARN | — |
