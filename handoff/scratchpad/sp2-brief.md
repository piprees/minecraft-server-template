SP2 — The set-piece structure type (worktree; the server lane only when the lead gives it)

Read and treat as binding: handoff/scratchpad/common-rules.md; docs/design/rebuild-26/places/set-pieces.md (all of it: "In the definition", "Seats", "Elements", "Composition", "Finding places", § SP2 and "Deep-time links"); docs/design/rebuild-26/lore/placement.md (§ Fitting the setting, § Age, § Gaps: G3 and G5); decisions RB-39 to RB-45 in docs/design/rebuild-26/decisions.md; mods/AGENTS.md (mixin conventions, verification loop). Package name sp2: log /tmp/claude-0/sp2/progress.log, one line per step (`HH:MM — what`), with done.txt beside it.

## Scope
1. SP2 exactly as set-pieces.md § SP2 states it, including every acceptance item:
   - register `customdimensions:set_piece` and `customdimensions:set_piece_part`;
   - `setPieces` in the schema and the converter, with the compiler writing the structures and group sets;
   - the seats `ground`, `floor` and `pinned`;
   - the elements `template` and `scatter`, `chest` and `spawner`;
   - the `size` check;
   - set pieces in `places.named`.
2. **G3, site filters, for set pieces and for every placed structure** (forced starts, wants, groups; ours and the mods'):
   - slope;
   - minimum and maximum surface height, and relative height (summit, spur, valley floor);
   - distance to water, shore or river;
   - distance to a cliff face;
   - shelter;
   - biome.
   They extend `StructureSites` (its chooser already tests up to 20,000 ring chunks and the biome at the surface for above-ground starts; see StructureRuntime.startBiomeAt and T53). Schema fields go under `structures` per structure id and on a set piece. This answers places README question 4 (keeps on peaks prefer height).
3. **G5, age:** one `age` value (`recent`, `old`, `ancient`, `deep`) on a set piece or element. It sets integrity, burial, weathering swaps (stone to mossy or cracked, copper oxidation stage, planks dropped, wool removed), cobwebs and vines, per the table in placement.md § Age. A dimension default as well. The archaeology share waits for SP4, so leave it out; banding by distance waits for SP3.
Out of scope: G1/G2/G4 (stories, satellites, anchor separation), shapes (SP3), assemblies (SP4).

## Facts you can rely on (verified by the lead)
- Build line, run in mods/custom-dimensions-26:
  `JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=$JAVA_HOME/bin:$PATH ./gradlew --max-workers=1 --offline build`.
  It is green on the branch head: 1,567 tests, 0 failures.
- Quick gate, from the repo root:
  `PATH=/tmp/claude-0/pybin:/root/.local/bin:/tmp/claude-0/sc/bin:$PATH ./scripts/test-scripts.sh --quick`.
- Self-test: `./gradlew --offline :server:runServer -PselfTestOnly=<section>`. For a script that boots, reads results and stops, see the pattern in handoff/evidence/k13/confirm-lane.sh. The pack's structure mods are at /tmp/claude-0/pack-structure-mods (`-PstructureMods=`).
- The places README's questions 1 and 2 (a vanilla client joins with the new types registered; `StructureTemplateManager.getOrCreate` inside a Fantasy level during generation) are yours to answer with your self-test boot and the smoke-test-26 join gate.
- Clean room RB-13: never open Terralith, Incendium, Nullscape, Structory, Tectonic, Regions Unexplored, Nature's Spirit, WilderWild or Stardust Labs files. Vanilla templates are used by id, never copied.
- No client mod required: every block, item and entity placed is vanilla.

## Rules
- One server or one Gradle build at a time, and a boot or build only after messaging the lead and being told the lane is yours. The lane is shared with other agents.
- Before every boot: 5 GB of memory and 3 GB of disk free. Kill processes by pid, never with `pkill -f`.
- Commits: explicit paths, with `[skip ci]`, Co-Authored-By and Claude-Session trailers as common-rules.md gives them. Never stage HANDOFF.md. Don't push.
- Trap drafts go in your report, numbered from T310; the lead numbers them finally.
- No code names a dimension.
- Budget: about 200 tool steps.

## Report
- per acceptance item: pass, with its evidence (test name or self-test line), or not done and why;
- the answers to the README questions 1 and 2;
- the schema fields added;
- commits;
- trap drafts.
