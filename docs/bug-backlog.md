# Bug backlog

Open bugs found while building the dependency automation, kept here until each
is fixed. Fixing one means deleting its row. Every entry was checked against
the code at the location given.

| Id | Bug | Where | Impact | Fix |
| --- | --- | --- | --- | --- |
| B4 | The full-mode summary of `test-scripts.sh` hard-codes "Python syntax: PASS" and "Compose validation: PASS" (static failures do exit 1 earlier, at line 283) | `scripts/test-scripts.sh:406-407` | Misleading summary only | Print the real counters, as the shell line already does |
| B9 | `config/modrinth-mods.pinned.txt` is tracked in git and 51 entry lines behind `config/modrinth-mods.txt` | `config/modrinth-mods.pinned.txt` | A stale second mod list anyone can mistake for the real one | Delete it and git-ignore it (it's `pin-mod-versions.sh` scratch output) |
| B14 | elfydd's `README.md` says `STACK_VERSION` in `.env` drives deploys; deploys read `.stack-version`. `README.md` is consumer-owned, so `./dev update` never refreshes it (the scaffold copy and the synced `AGENTS.md` are correct) | elfydd `README.md:23` | Operators change the wrong setting | Rewrite the paragraph from the scaffold's `README.md` § Update the platform when elfydd migrates to `.stack-version` |
| B15 | `iris.properties` names the shader and resource packs by filename with no drift check against the pins | `iris.properties:9` | A pack bump silently breaks the shader setting for new clients | Rewrite or check it alongside `options.txt`, which already fails the build on drift |
| B16 | `uptime-kuma-api==1.2.1` is pinned against Kuma 2.4.0; compatibility is unverified | `docker/kuma-init/requirements.txt` | Kuma provisioning may break (unverified) | Test `kuma-init` against Kuma 2.x |
| B17 | `test_an_unwritable_lock_file_warns_rather_than_failing_silently` fails when the suite runs as root, because root can write anywhere | `scripts/tests/test_deploy_activation.py` | False failure in root containers (not on GitHub runners) | Skip the test when `os.geteuid() == 0` |
| B18 | consumer-customisation's removal steps point consumers at `_clientMods.required` (the template's manifest) instead of the overlay manifest; consumer-repo-operations trap 7 narrates ("now lives") | `.claude/skills/consumer-customisation/SKILL.md` (removal steps), `.claude/skills/consumer-repo-operations/SKILL.md` (trap 7) | Wrong file for consumers; narration breaks the house rule | Point at `overlay/modpack/manifest.json`; restate trap 7 in the present tense |
| B19 | Two `DEPENDENCIES.md` claims are unverified: that `config/modrinth-resolve-cache.json` keeps a deleted project's pin resolving while the CDN serves the file, and that a consumer's client-mod change rebuilds the `.mrpack` on push | `DEPENDENCIES.md` § Situation playbooks, § For consumers | Possibly wrong guidance | Verify against `scripts/resolve-mods.py` and the consumer deploy path; correct or delete |
| B20 | `server-power.yml` downloads hcloud from `releases/latest/download/…` with `curl -sL`: unpinned, and an error page is piped into `tar` when the asset is missing (the fault B3 was for doctl) | `examples/consumer/.github/workflows/server-power.yml:46` | Hetzner power toggle breaks whenever the release asset naming changes | Pin `HCLOUD_VERSION` with `curl -fsSL`, and add it to `scripts/platform_updates.py`'s `PINS` like doctl |
