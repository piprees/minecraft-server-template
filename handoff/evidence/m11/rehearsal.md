# Rehearsal of migrate-to-26.sh on a local 1.21.1 consumer

One command per phase, against a linked local stack (`--local`): the same code paths as production except ssh (commands run in the consumer directory), ufw (`host` is skipped) and push/gh (none). Server lane only: it starts the game server. Log each phase's exit code and the lines named under "Pass".

## 0. Set-up (once)

```bash
P=/tmp/claude-0/<package>
B=claude/minecraft-dependency-automation-n1w79q
df -h /                                                   # needs ~3 GB: two shallow clones, a world, its tar and copy

# The 1.21.1 tree (the branch before the switch) and the switched 26.3 tree.
git clone -q --depth 1 -b $B /home/user/minecraft-server-template $P/v5tree
git clone -q --depth 1 -b $B /home/user/minecraft-server-template $P/switched
$P/switched/cutover/apply.sh --no-network
(cd $P/switched && python3 scripts/gen-resolve-cache.py)
# Each tree's in-house mod jar, so `./dev link` serves it:
#   $P/v5tree/mods/custom-dimensions:      mise exec -- ./gradlew build      (Java 21)
#   $P/switched/mods/custom-dimensions-26: ./gradlew --max-workers=3 build   (Java 25, common-rules.md)

# The consumer: a copy of Elfydd without its .env (./dev up writes one from .env.example).
mkdir -p $P/elfydd && cp -a /home/user/elfydd/. $P/elfydd/ && rm -f $P/elfydd/.env && rm -rf $P/elfydd/.stack
cd $P/elfydd
./dev link $P/v5tree
./dev up                                                  # 1.21.1 boots and generates a world
./ops rcon "list"                                         # answers: the stack is up
ls data/world/level.dat data/config/portal_links.json     # both present before the migration

M=$P/switched/scripts/migrate-to-26.sh
X="--local $P/elfydd --target v6.0.0"
bash $M plan $X                                           # Pass: A1 … E16 listed, then the clean list
```

## 1. preflight

```bash
bash $M preflight $X                                      # dry run first
bash $M preflight $X --apply
```

Pass: exit 0; `ok    local .stack/current is dev`, `ok    server bundle dev`, `note  --local: ./ops doctor is a production check; skipped`, `ok    nobody online` (or the autopause note), `ok    disk: …`; `.migrate-26.state` has `done_preflight`; `git -C $P/elfydd check-ignore .migrate-26.state` prints it.

## 2. pin

```bash
bash $M pin $X --apply
```

Pass: exit 0; `note  --local: the stack is linked (dev); there is no release to pin`; then `Next, an owner step: release` with the A3 text.

## 3. archive

```bash
bash $M archive $X --apply                                # Pass: exit 3, "Owner step due: release"
bash $M archive $X --apply --owner-done release           # B4: triggers mc-backup-local
```

Pass: `RUN   trigger a backup (mc-backup-local restart)` then `ok    restic snapshot <id> (<n> region files)` with n > 0. The sidecar waits about 2 minutes (`INITIAL_DELAY`), so exit 3 with `no 'snapshot ... saved'` is a pass too: re-run the same command; it must say `a backup was triggered at …` and NOT trigger again (`docker ps` shows one restart of mc-backup-local). B5: `note  no unmined-render container` on a local stack, or, if it exists, exit 3 after the render restart and a third run that asks for `map`. B6: type `stop-mc`; `ok    mc stopped`, `ok    backups/world-1.21.1-<date>.tar.gz sha256 <sum>`, the `cp` line and `expected <sum>`. Then `docker ps -a --filter name=mc --format '{{.Names}} {{.Status}}'` shows mc Exited.

A third run changes nothing: `ok    world tar … (recorded, matches)`.

## 4. host

```bash
bash $M host $X --apply
```

Pass: exit 0; `note  --local: no ufw and no cloud firewall on a local stack; host skipped`.

## 5. clean (the refusals first, then the deletion)

```bash
mkdir -p $P/offhost && cp backups/world-1.21.1-*.tar.gz $P/offhost/ && sha256sum $P/offhost/world-1.21.1-*.tar.gz
S=<that sum>
bash $M clean $X --apply --yes-clean                       # Pass: exit 1, "--offhost-sha <sum> is required"
bash $M clean $X --apply --yes-clean --offhost-sha 0000    # Pass: exit 1, "is not the world tar's"
docker start mc && bash $M clean $X --apply --yes-clean --offhost-sha $S; docker stop mc
                                                           # Pass: exit 1, "mc is running"; data/world still there
bash $M clean $X --offhost-sha $S                          # dry run: the "present" list, "DRY   would, after confirm clean: rm -rf the N paths above"
bash $M clean $X --apply --offhost-sha $S                  # type "clean"
```

Pass: exit 0; `ok    removed`; `ls data/world data/config/portal_links.json data/config/collective.json5` all fail; `ls data/mods data/whitelist.json data/ops.json backups/` all succeed; the tidy note lists `data/config/custom-dimensions/` and the cut mods' configs, still present. Then `Next, an owner step: secrets`.

## 6. consumer (the switch on the local stack)

```bash
bash $M consumer $X --apply --owner-done secrets --link $P/switched     # type "consumer"
```

Pass: exit 0; `.stack-version` is `v6.0.0`; `git -C $P/elfydd log -1 --stat` is `feat!: move to the v6 stack (Minecraft 26.3)` with the 36 seed-viewer deletions and `.stack-version`; `readlink .stack/current` is `dev` and `cat .stack/current/stack/.minecraft-version` is `26.3`; `./dev refresh-config` and `./dev up` ran and mc starts on an empty world (`docker logs mc --tail 50` snapshot).

## 7. verify (after mc reports healthy; re-run on exit 3)

```bash
bash $M verify $X --apply
```

Pass: exit 0; `ok    server bundle dev`; `ok    82 dimensions: 4 base, 78 lazy, …`; the audit lines; the ERROR count. Record for the report, they are the notes' open facts:

- step 12: `ok    the world seed <n> is the overworld definition's`, or `MISMATCH` with the `./ops reset-seed <seed>` remedy (exit 1; applying the remedy is the lead's call, not the rehearsal's);
- step 13: `ok    open fact answered: the lazy level … follows the overworld`, or `FINDING  the lazy level … has spawn_phantoms …`.

## 8. first-backup

```bash
bash $M first-backup $X --apply                           # re-run on exit 3 (the sidecar's delay)
bash $M status $X --apply --owner-done launch
bash $M status $X
```

Pass: `ok    restic snapshot <id>` (a different id from step 3's), then status shows every phase and owner step `done`.

## Clean-up

```bash
cd $P/elfydd && ./dev down; cd / && rm -rf $P/v5tree $P/offhost      # keep $P/elfydd and $P/switched only if the lead asks
```
