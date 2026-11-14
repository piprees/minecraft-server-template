# cutover/ — the Minecraft 26.3 switch kit

Template-only. Everything that turns this branch from the 1.21.1 platform into the 26.3 one in a single reviewed commit, and the notes for the steps the commit cannot take. `apply.sh` deletes this folder as its last step, so the switched tree carries no kit; `git show <commit>:cutover/<file>` reads it afterwards.

| File | What it is |
| --- | --- |
| `apply.sh` | The switch: every mechanical item of [`docs/design/rebuild-26/cutover-removals.md`](../docs/design/rebuild-26/cutover-removals.md). `--dry-run` lists it; a real run refuses a dirty tree |
| `edits.py` | The exact-text edits `apply.sh` runs, by step; an edit whose old text is missing is reported as drift |
| `files/` | Whole-file replacements and additions, stored under their repo path with the names below |
| `files.sha256`, `mkshas.sh` | The repo file each replacement was written against; `mkshas.sh <repo> <path>...` re-records an entry once its changes are carried over |
| `checklist.md` | Before, the script, after, owner-only |
| `not-automated.md` | What `apply.sh` leaves, each with its reason |
| `migration-notes.md` | What a running 1.21.1 server and its consumer repo do to reach 26.3, in order |
| `elfydd-patch.md` | The proposed patch for the Elfydd consumer repo |
| `troubleshooting-retire.md`, `docs-verification.md` | TROUBLESHOOTING.md entries the switch retires or rewords; how the replacement docs were checked |
| `p7-proposal.md`, `p8-pregen.md`, `p9-archive.md` | Gameplay settings, pre-generation, and the 1.21.1 world's archive |

## Stored names

Nothing under `files/` may be taken for a live file: a stored workflow must not run, a stored skill must not load, a stored agent contract must not bind, and no linter may lint a stored script as shipped. So `files/` holds each replacement under its repo path with two changes, applied by `stored()` in `apply.sh` and reversed by `mkshas.sh`:

- a leading `.` of any path component becomes `dot-` (`files/dot-github/workflows/smoke-test.yml`, `files/dot-claude/skills/…`);
- `AGENTS.md` becomes `AGENTS.next.md`.

Base shas of the ports (the files changed after the edits, below the second comment in `files.sha256`) are the file as `apply.sh`'s edits leave it, so re-record those against a switched clone.

The quick gate's linters read `scripts/`, `docker/`, `examples/consumer/` and `.github/workflows/` only, so `cutover/files/scripts/` is not linted as a shipped script; `tests/run.sh` lints it on the rehearsal clone, where it is.

## Rehearsal

```bash
git clone -q . /tmp/cutover-rehearsal
/tmp/cutover-rehearsal/cutover/apply.sh --no-network   # the clone's own kit, so it removes itself
cd /tmp/cutover-rehearsal
./scripts/test-scripts.sh --quick
python3 -m pytest -q scripts/tests mods/custom-dimensions-client-26/scripts
```

Run the clone's own `cutover/apply.sh`: `--repo` with another checkout's kit applies the switch but leaves that kit in place.
