# The 26.3 switch: checklist

The switch is the last commits on PR #29; merging the PR is the switch, `v6.0.0` ships it. `cutover/` (tracked) holds `apply.sh`, which does every item of `docs/design/rebuild-26/cutover-removals.md` and the code ports (`not-automated.md` § The ports), and deletes `cutover/` as its last step; what it leaves is in `not-automated.md` § Judgement calls. A running server's own steps are `migration-notes.md`.

## Before

1. **The ports are current:** `cutover/apply.sh --dry-run` prints no `!` line. A `!` means a repo file changed since its port was written: carry the change into the stored replacement, re-record it with `cutover/mkshas.sh <switched clone> <path>`, and re-run the rehearsal.
2. **CI green on the branch head:** `gh run list --branch claude/minecraft-dependency-automation-n1w79q --limit 5 --json name,conclusion`; `Mod build (26.3)` and `Smoke Test (26.3)` both `success` (dispatch the smoke test if the last run predates the head: `gh workflow run smoke-test-26.yml --ref claude/minecraft-dependency-automation-n1w79q`).
3. **Self-test green:** in `mods/custom-dimensions-26/`, `./gradlew :server:runServer -PselfTest=/tmp/st.txt` with `level-seed=20261022`: `grep -c '^FAIL' /tmp/st.txt` is 0.
4. **Rehearse on a copy:** `git clone -q . /tmp/cutover-rehearsal && /tmp/cutover-rehearsal/cutover/apply.sh`, then in the copy `./scripts/test-scripts.sh --quick` and `python3 -m pytest -q scripts/tests mods/custom-dimensions-client-26/scripts` (needs pytest), and `./gradlew build` in `mods/custom-dimensions-26` and in `mods/custom-dimensions-client-26`.
5. **Rehearse the stack on a linked local consumer** (`~/Projects/elfydd`; ask first, it is shared): `./dev link` it to the rehearsal copy, `./dev refresh-config` (T78: `data/config/` holds the 1.21.1 definitions), `./dev reset-world`, `./dev up`; then `./ops rcon "customdim list"`, `./ops rcon "customdim audit"`, a vanilla 26.3 client join, a Bedrock join, and one portal crossing into The Aviary. Measure P8's per-1,000-chunk figures here.
6. **Rehearse deploy.sh**, which no 26.3 test runs: a full `./dev`-driven deploy of the rehearsal bundle to a throwaway droplet, or `scripts/deploy.sh` against the local consumer, read end to end.
7. **Dry run on the branch:** `cutover/apply.sh --dry-run > /tmp/cutover-dry.txt`; no line starts `  !`.

## The script

```bash
git status --short                 # empty
cutover/apply.sh                   # prints every add, change, delete and move; exits 1 on drift; deletes cutover/ last
git add -A -- .minecraft-version docker-compose.yml AGENTS.md README.md DEPENDENCIES.md \
  .claude .github config cutover docker docs examples mods modpack scripts   # explicit paths, never a bare -A
git commit -m "feat!: switch the platform to Minecraft 26.3 [v6]"
```

A line marked `!` means a file changed since its replacement in `cutover/files/` was written: diff it against the replacement, carry the change over, then `--accept-drift`.

## After

1. `./scripts/test-scripts.sh --quick` green.
2. `python3 -m pytest -q scripts/tests` green.
3. `cd mods/custom-dimensions-26 && ./gradlew build` green (1117+ tests) and the self-test green.
4. Push; `Smoke Test` (the boot, Bedrock ping, vanilla join and admin parity) and `Mod build` green on the PR.
5. The e2e rig: `mods/custom-dimensions-client-26/scripts/rig.sh` variants a and b against the switched tree, and `scripts/e2e/vanilla-join.sh` once by hand (`scripts/e2e/build-the-rig.md` first).
6. Retire and reword the TROUBLESHOOTING.md entries in `troubleshooting-retire.md`, and add the retired ids to AGENTS.md's list.

## Owner only

1. **Merge PR #29.**
2. **Release:** `gh workflow run release.yml -f version=v6.0.0`. The breaking-change note: "New world: Minecraft 26.3, our own worldgen in every dimension, no client mod needed to join. Bedrock players join through Geyser and need the Bedrock port (19132) open over UDP and TCP. The 1.21.1 world is archived, not carried over." Then `git fetch origin '+refs/tags/v6:refs/tags/v6'`.
3. **Production, in order:**
   1. P9 archive (`p9-archive.md`): restic snapshot, last map render, world download.
   2. Firewall for Bedrock: `sudo ufw allow 19132/tcp comment "Geyser Bedrock signaling"`, `sudo ufw allow 19132/udp comment "Geyser Bedrock"`; back up `/etc/ufw/before.rules` and `before6.rules` (`.bak.TIMESTAMP`), copy harden.sh's `mc-bedrock` and `mc-bedrock6` lines into their `RATE-LIMIT` blocks, `sudo ufw reload`. On Hetzner: `hcloud firewall add-rule <SERVER_NAME>-fw --direction in --protocol udp --port 19132 --source-ips 0.0.0.0/0 --source-ips ::/0 --description "Bedrock (Geyser)"` and the same with `--protocol tcp`.
   3. Floodgate key: `base64 < key.pem | tr -d '\n' | gh secret set FLOODGATE_KEY_B64 --env production` in the consumer repo.
   4. Stale flat Serilum configs: Collective moves any `data/config/<modid>.json5` over `config/serilum/<modid>.json` at boot, so the 1.21.1 copies would win once. Back up, then remove `data/config/{collective,doubledoors,firespreadtweaks,healingcampfire,nametagtweaks,nutritiousmilk,youritemsaresafe}` and `data/config/{collective,starterkit}.json5` on the server (production `data/`: confirm first).
   5. Consumer: `.stack-version` to `v6`, push; the full deploy runs (`gh run list --workflow deploy.yml --commit <sha> --json databaseId,status`).
   6. New world: `./ops reset-seed <seed>` (backs up, wipes `data/world`, re-runs post-boot setup). Then `./ops doctor`, `./ops rcon "customdim list"`.
   7. P8 pre-generation (`p8-pregen.md`).
4. **Launch evening** (work-plan.md § Launch): a vanilla client, the pack and a Bedrock phone each join and cross into three dimensions including The Aviary; five players hold 19+ TPS.
