# Agent Skills — proposal index

**What this folder is.** One document per proposed Agent Skill (`SKILL.md`). Each file is a *brief for a sub-agent that will build the skill* — it says what the skill must cover, where the source material lives, which traps are load-bearing, and how to know the result is good. **These files are not the skills themselves.** A sub-agent handed one of these should end up writing a `SKILL.md` (plus `references/*.md`), not copying the brief.

## Why these exist

The repo already documents almost everything, but the documentation is organised for *humans reading a repo*, not for *an agent handed a task*. Concretely:

- `AGENTS.md` (364 lines) mixes fixed decisions, 15 architecture traps, macOS traps, dimension traps, known issues, conventions and a task table. An agent asked to "add a mod" must read all of it to find the four paragraphs that apply, and will still miss `docker/defaults-seed/Dockerfile` (documented in `README.md § Config sync`, not in the mods section).
- The authoritative reference for most operations is the **header comment of a shell script** (`AGENTS.md:200`: "keep headers current when changing behaviour; they're the authoritative reference"). Those headers are excellent and completely undiscoverable — nothing tells an agent that `scripts/doctor.sh`'s header is the triage checklist.
- Several procedures exist only as scar tissue in trap lists: the level.dat scrub, the c2me re-patch before every restart, the "never `./dev up` to test a local mod build" rule. Each is written as a warning attached to an incident, not as a procedure attached to a task.
- Some `docs/` files are thin pointers (`docs/deployment.md` 16 lines, `docs/security.md` 12, `docs/troubleshooting.md` 42) while the real depth sits in `AGENTS.md` or a script header.

A skill fixes the *routing* problem: description-triggered, task-shaped, self-contained, with the traps inline at the step where they bite.

## The existing exemplar

`config/custom-dimensions/SKILL.md` (`custom-dimension-authoring`, 347 lines) is the model. What it gets right and every new skill should copy:

- **Frontmatter description names the artefact and the trigger** ("Use whenever asked to create a new dimension… Always consult this before hand-writing…").
- **A MANDATORY-read block** listing reference files with a "why you need it" column, *plus* an instruction to read 2–3 real shipped files as anchors.
- **Traps numbered and stated as symptom → cause**, at the end, after the happy path.
- **Silent-failure inventory** — it explicitly separates loud failures from silent ones. This is the single highest-value section for an agent.
- **A validation section with runnable commands** that is marked "do not skip".

Its one deviation from the Agent Skills standard: it keeps a worked JSON example inline rather than in `references/`. That is fine and worth repeating — a single canonical example in SKILL.md beats a reference hop.

## Where new skills should live

**Decision needed from Pip.** Three viable homes, and they are not exclusive:

| Location | Discovered by | Reaches consumers? | Notes |
| --- | --- | --- | --- |
| `.claude/skills/<name>/SKILL.md` | Claude Code, automatically, in this repo | No | The project `.claude/` is currently empty. Default recommendation for platform-facing skills. |
| Colocated (e.g. `config/custom-dimensions/SKILL.md`) | Nothing automatically — it is read when an agent is pointed at it | **Yes, by accident** — `docker/defaults-seed/Dockerfile:24` COPYs the whole `config/custom-dimensions/` dir into the seed image, so it lands in every consumer's `data/config/`. | How the exemplar reaches consumers today. Undocumented side effect. |
| `examples/consumer/.claude/skills/` | Claude Code in a consumer repo | Only if added to the sync list | Requires editing the `update)` case in `examples/consumer/dev` — see the **consumer scaffold sync trap** (`AGENTS.md:196`). Forgetting this means existing consumers never receive it. |

**Recommendation:** build all skills at `.claude/skills/<name>/SKILL.md` first. Then pick the 4 that consumers genuinely need (`consumer-repo-operations`, `server-mod-management`, `production-triage`, `backup-and-recovery`), copy them into `examples/consumer/.claude/skills/`, and add that path to the `dev update` sync list in the same commit. Do not ship the platform-only ones — a consumer has no `config/`, no `docker/`, no release workflow.

## The proposed skills

Ordered by value ÷ effort. Tier 1 pays for itself immediately; Tier 3 is worth doing but nothing breaks without it.

### Tier 1 — build first

| # | Skill | Fixes |
| --- | --- | --- |
| 01 | [`server-mod-management`](01-server-mod-management.md) | The most common change and the most likely to break things. Deps, pins, holds, the two-place config rule, the offline boot model. |
| 02 | [`deploy-pipeline-operations`](02-deploy-pipeline-operations.md) | Tier detection, push discipline, run-id-by-sha, the `.deployed` lie, never-`gh run watch`. |
| 03 | [`production-triage`](03-production-triage.md) | "Something's wrong." Autopause vs outage, snapshot-not-stream, crash-loop triage, the wedge, the Kuma stop-rule. |
| 04 | [`consumer-repo-operations`](04-consumer-repo-operations.md) | Working in `~/Projects/elfydd`-shaped repos: overlay contract, `dev` vs `ops`, what you may not change here. |
| 05 | [`fabric-mod-development`](05-fabric-mod-development.md) | The verification loop. Remapped-jar checks, the Carpet bot harness, tick-loop threading rules. |

### Tier 2

| # | Skill | Fixes |
| --- | --- | --- |
| 06 | [`platform-release-management`](06-platform-release-management.md) | Burnt tags, immutable releases, the publish.yml cancellation collision, bundle manifest. |
| 07 | [`env-and-secrets`](07-env-and-secrets.md) | The four-places rule, CI regeneration, `env_quote`, 1Password, the `KUMA_API_KEY` wipe. |
| 08 | [`backup-and-recovery`](08-backup-and-recovery.md) | Restore procedure, restic hostname retention trap, `reset-seed`, `wipe-chunk`. |
| 09 | [`seed-rolling`](09-seed-rolling.md) | The roller, moods, generation fingerprints, zero-candidate triage. |
| 10 | [`discord-integration-ops`](10-discord-integration-ops.md) | Two clients one token, registry ground truth, role sync, `messages.json`. |
| 11 | [`local-stack-testing`](11-local-stack-testing.md) | Testing unreleased platform content without a false pass. Volume/seed reversion, c2me re-patch. |

### Tier 3

| # | Skill | Fixes |
| --- | --- | --- |
| 12 | [`server-provisioning`](12-server-provisioning.md) | Zero to running server: the wizard chain, provider routing, Cloudflare's three credentials. |
| 13 | [`bundle-script-authoring`](13-bundle-script-authoring.md) | bash 3.2, no `grep -P`, script categories, manifest + scaffold sync traps, lint gates. |
| 14 | [`web-surface-branding`](14-web-surface-branding.md) | Four surfaces, no shared stylesheet, five nav copies, footer version string. |
| 15 | [`dimension-lifecycle-operations`](15-dimension-lifecycle-operations.md) | The *operational* half of dimensions: creation-time vs boot-re-read, level.dat scrub, BlueMap discovery. Complements the existing authoring skill. |

## House rules for every skill built from these briefs

Non-negotiable, derived from `~/.claude/skills/skill-management` and from what makes the exemplar work:

1. **Frontmatter**: `name` (matches directory, lowercase-hyphen, 4–64 chars) and `description` (250–1024 chars, third-person active voice, names the concrete files/commands, includes 3–5 "Use when" triggers and 2–3 verbatim error strings an agent would grep for).
2. **SKILL.md under 500 lines**, target 150–300. Everything longer goes to `references/<topic>.md` (max 500 lines each, self-contained, kebab-case).
3. **Every path, command, filename and env var must be verified against the repo at build time.** Do not transcribe from these briefs — the briefs are a map, the repo is the territory. An orphan reference breaks trust in the whole skill.
4. **Traps last, numbered, symptom → cause → fix.** Copy the `AGENTS.md` house style. Preserve the incident dates (`2026-07-13`) — they are load-bearing evidence that the trap is real.
5. **Separate loud failures from silent ones** in a validation section, with runnable commands.
6. **Do not restate what the model already knows.** No "what is Docker Compose". Only what this repo does differently and where the general knowledge is actively wrong.
7. **British English.** Present tense for descriptions, second-person imperative for instructions, blunt warnings unsoftened (`CONTRIBUTING.md:67-68`).
8. **Never invent.** If the brief asks for something the repo does not support, say so in the skill's PR description rather than writing plausible fiction.
