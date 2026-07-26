# Prompt for the next session

Copy everything below the line into a fresh session in `~/Projects/minecraft-server-template`.

---

Work through `.ideas/OPEN-TASKS.md` end to end. It has 18 open items across four sections (3.4 is already closed — leave it).

**Do it in three phases, in this order. Do not start phase 3 before phase 2 is written to disk.**

## Phase 1 — understand before you ask

Read `.ideas/OPEN-TASKS.md`, `.ideas/FINDINGS.md`, `AGENTS.md` and `TROUBLESHOOTING.md` in full. For each open item, verify the claim against the actual code before forming a view — several entries cite a file and line, and the repo has moved since they were written. If an item is already fixed, say so rather than asking me about it.

Skills exist for most of this and are worth loading before you touch the relevant area: `server-mod-management` (1.1, 3.7), `discord-integration-ops` (1.2, 2.1), `deploy-pipeline-operations` (1.3), `consumer-repo-operations` (2.2, 2.3, 2.4), `server-provisioning` (2.5), `bundle-script-authoring` (3.5, 3.6, 3.7a), `fabric-mod-development` (4.1, 4.2).

## Phase 2 — get a decision on every item, then record them

Ask me for a decision on each open item. **Batch the questions** — use `AskUserQuestion` with up to 4 items per call, grouped by section, rather than 18 round-trips. For each item give me:

- a one-line statement of what's actually true (post-verification, not a quote from the doc)
- **your recommendation**, so I can usually just confirm
- the consequence of doing nothing

Offer these outcomes per item: **do now** / **do later** (stays in the file, deferred) / **won't do** (with reason recorded) / **needs a spike** (can't be decided without investigation).

Batch everything, including the items with wider blast radius — just state the consequence in the item's line so I'm deciding with it in view rather than after:

- **3.1** rewrites shared git history (force-push).
- **1.1** changes `pin-mod-versions.sh`, which ships in the stack bundle, so it needs a release to reach consumers.
- **3.2** and **3.7** touch platform `config/`. Note that platform config does *not* match `FULL_PATTERNS` — that's consumer paths only. Platform config reaches consumers via a release, and the resolved-tag comparison is what forces their full deploy, on their next push after the release. A full deploy is fine and currently cheap: the resolve cache is warm and baked into `defaults-seed`, so zero Modrinth API calls and only changed jars hit the CDN.

When every item has a decision, **write them into `.ideas/OPEN-TASKS.md` as a decision record before doing any of the work**: a dated table of item → decision → rationale, with the deferred and won't-do items keeping their detail so the reasoning survives. Commit that on its own. If the session dies mid-implementation, the decisions must not die with it.

## Phase 3 — action the "do now" items

Work them in the order the file lists them (consequence-first: §1 correctness before §2 docs before §3 hygiene). After each item:

- run `./scripts/test-scripts.sh --quick`
- update the item in `OPEN-TASKS.md` — struck through, with what actually changed, the way 3.4 is written
- commit that item on its own, conventional-commit style

Then push once at the end, not per commit.

## Rules that apply throughout

**Verify, don't transcribe.** These items were written by an agent auditing the repo; treat every claim as a lead, not a fact. Check the file. If a claim is wrong, correct the entry and tell me — that's a finding, not a detour.

**`~/Projects/elfydd` is read-only.** Production is currently stopped and a push there restarts it. Read it if it helps you verify something; never write, never push.

**Releases:** `gh workflow run release.yml -f version=vX.Y.Z`, never `gh release create`. Published tags burn permanently — a failure means cutting the next patch, not retrying. Latest is `v3.10.3`. Ask me before cutting one.

**Pushing:** check `gh run list --limit 3` first; if a run is in flight, wait. Every push to `main` triggers image builds (no path filter), and dispatching a release while those run cancels them. Resolve runs by commit sha, never `--limit 1`. Check status once, hand me the URL, don't poll.

**Never stream logs or use unbounded wait loops.** `TROUBLESHOOTING.md` has the full forbidden list at the top.

**Cite problems by id** (`TROUBLESHOOTING.md#t14`) rather than restating them. If you find a new one, add it there with the next free id and link it — don't describe it in place.

Ask me if an item turns out to be bigger than its one-line description suggests. I'd rather re-scope than have you guess.
