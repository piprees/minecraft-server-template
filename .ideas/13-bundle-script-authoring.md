# Skill brief: `bundle-script-authoring`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

There are 77 shell scripts and 20 Python scripts in `scripts/`, and they obey a house style that contradicts an agent's defaults in four specific ways:

- **macOS bash 3.2.** No `declare -A`, no `${var,,}`, no `|&`, no `mapfile`. Every one of those is a reflex.
- **No `grep -P`.** BSD grep has no PCRE. `AGENTS.md:200` says this "has caused multiple CI and runtime failures" — and the seed-rolling scripts hit it again after it was documented.
- **The header comment is the authoritative reference** and must be kept current when behaviour changes. Agents write code and leave the header stale.
- **Three script categories with different destinies** (bundle / image / template). Putting a script in the wrong one, or forgetting the manifest, means it silently never ships.

The manifest and scaffold-sync traps are the expensive ones. A new bundle script not added to the `MANIFEST` array in `build-stack-bundle.sh` is never shipped to consumers. A new consumer scaffold file not added to the `update)` sync list in `examples/consumer/dev` never reaches existing consumers. Both fail silently and both are caught only partially by lint.

## Scope

**In:** writing or modifying any script in this repo. Categories and where each lands, the portability rules, the conventions (headers, idempotency, `--non-interactive`, backups, `set -euo pipefail`), `lib.sh`'s API, the two shipping traps, and the quality gates.

**Out:** what a specific script *does* → the relevant operational skill. Python-only concerns beyond `py_compile` are out of scope unless the repo grows a Python style guide.

## Source material

| File | What to mine |
| --- | --- |
| `AGENTS.md § Conventions → Scripting` (line 200) | The full rule set: shebang, `set -euo pipefail`, bash 3.2, no `grep -P`, idempotent, `backup()`, `--non-interactive`, headers authoritative |
| `AGENTS.md § Conventions → .env writing` (line 202) | `set_env_var`/`env_quote`; never a raw `KEY=$value` line |
| `AGENTS.md § Script map` (lines 182-196) | The three categories; **the bundle manifest trap**; **the consumer scaffold sync trap** |
| `AGENTS.md § Platform-specific traps` | `od -An -td8`, `grep -P`, `mise exec` |
| `AGENTS.md § Safety rules 10` | No unbounded wait loops; what is allowed (single `sleep N`) vs forbidden |
| `AGENTS.md § Conventions → Versions` | Never trust training data for versions; the `--limit 1` trap; GitHub-tag ≠ Docker Hub |
| `README.md § Scripts` (three tables) | The full inventory by category — the skill should point here rather than duplicate |
| `scripts/lib.sh` (header + the exported functions) | `PROJECT_DIR` precedence, colours, `log/warn/die`, `load_env`, `sed_i`, `sha256`, `backup`, `rcon`, `get_player_count`, `detect_provider`, `require_provider_cli` |
| `scripts/build-stack-bundle.sh` — the `MANIFEST` array | What ships |
| `examples/consumer/dev` — the `update)` case | The scaffold sync list; the write-to-temp+`mv` self-update trick and why `cp` corrupts a running script |
| `scripts/test-scripts.sh` (header) | `--quick` (shellcheck `--severity=warning`, `py_compile`, compose config) vs the full Ubuntu-container run |
| `.github/workflows/lint.yml` (222 lines) | Six jobs: commit messages, ShellCheck, YAML lint, compose config, **bundle manifest**, Python deps, CI guidance |
| `.shellcheckrc`, `.yamllint.yml`, `.editorconfig`, `lefthook.yml` | The configured rules and the pre-commit hook |
| `CONTRIBUTING.md § Quality gates, § Style guide` | The commit conventions and British-English rule |
| Any script header, e.g. `scripts/doctor.sh` or `scripts/sync-mods.sh` | **The house header format** — purpose, context, usage, gotchas. Use one verbatim as the template |

## Required structure

```
bundle-script-authoring/
├── SKILL.md
└── references/
    ├── portability.md   # bash 3.2 substitutions, BSD vs GNU tool differences, tested alternatives
    └── lib-api.md       # every function lib.sh exports, signature, when to use it instead of rolling your own
```

### SKILL.md must contain

1. **The category decision, first.** Three destinies, and the question that picks one:
   | Category | Lands in | Registered where | Example |
   | --- | --- | --- | --- |
   | Bundle | the stack tarball, run by consumers via `./ops` | `MANIFEST` in `build-stack-bundle.sh` | `deploy.sh`, `doctor.sh`, `lib.sh` |
   | Image | baked into a GHCR image, never run directly | a `COPY` in the image's Dockerfile | `discord-sync.py`, `idle-tasks.sh` |
   | Template | stays in this repo for platform work | nowhere — it just lives here | `test-scripts.sh`, `pin-mod-versions.sh` |
2. **The header template**, reproduced from a real script, with the four required parts: what it does, context (where it runs, who calls it), usage with every flag, and gotchas. Plus the rule: **update the header in the same commit as the behaviour**.
3. **A portability cheat-sheet table** — the bash 3.2 substitutions written out, because "avoid `declare -A`" is useless without the replacement:
   | Don't | Do |
   | --- | --- |
   | `declare -A map` | parallel arrays, or a `case`, or a `printf`+`grep` lookup |
   | `${var,,}` | `tr '[:upper:]' '[:lower:]'` |
   | `mapfile -t arr < f` | `while IFS= read -r line; do arr+=("$line"); done < f` |
   | `grep -P` | `grep -oE`, or `sed` |
   | `od -An -td8` | `od -An -tu8`, or the `python3` struct one-liner |
   | `cmd |& other` | `cmd 2>&1 | other` |
4. **`lib.sh` as the first stop**, with the sourcing idiom verbatim and the instruction to check it before writing a helper. Call out `backup()` (`file.bak.TIMESTAMP`), `set_env_var`/`env_quote`, and `rcon`.
5. **The two shipping traps as a pre-commit checklist:**
   - [ ] New bundle script → added to `MANIFEST` in `build-stack-bundle.sh`?
   - [ ] New `examples/consumer/` file → added to the `update)` sync list in `examples/consumer/dev`?
   Note that `lint.yml`'s manifest job only catches scripts referenced by `ops` or imported by another bundle script — a standalone one slips through.
6. **The safety rule on loops**, verbatim in spirit: a single `sleep N` outside a loop is allowed; any loop that exits on a condition you cannot guarantee will occur is forbidden. A crashing container never becomes healthy.
7. **The quality gate**, one command: `./scripts/test-scripts.sh --quick`. State that CI runs the same plus yamllint and blocks on failure, and that `lefthook install` wires it to pre-commit.

### Traps to capture

1. **`grep -P` on macOS.** Documented, then hit again in seed-rolling scripts. Repeat it.
2. **A bundle script missing from `MANIFEST` never ships** — the consumer's `./ops <thing>` fails with a missing file and the cause is a build-time omission.
3. **A consumer scaffold file missing from the sync list never reaches existing consumers.** `README.md` and `overlay/` are excluded deliberately.
4. **`cp` over a running script corrupts it.** Bash reads scripts incrementally; `cp` rewrites the inode in place and the interpreter hits shifted bytes with a bogus `unexpected EOF`. Use write-to-temp + `mv` (see the `dev` `update)` case).
5. **Never write a raw `KEY=$value` into `.env`.** An unquoted MOTD once executed itself on production.
6. **Never trust training data for a version number.** Look it up live; `gh release list --limit 1` returns most-recently-published, not highest; a GitHub tag may not exist on Docker Hub.
7. **Idempotent means safe to run twice**, and back up before overwriting. Both are load-bearing because these scripts are re-run constantly by wizards and CI.
8. **User-facing strings live in `config/messages.json`**, never hard-coded.

### Validation section

```bash
./scripts/test-scripts.sh --quick    # shellcheck + py_compile + compose config
shellcheck --severity=warning scripts/<new>.sh
bash -n scripts/<new>.sh             # syntax only
./scripts/test-scripts.sh            # full Ubuntu-container run
```

## Done when

- An agent writing a new script picks the right category, writes the house header, avoids all six bash-3.2 traps, and adds the manifest entry unprompted.
- The portability reference is complete enough that an agent never needs to discover a BSD/GNU difference the hard way.
