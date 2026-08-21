# Common tasks

Task → file → command lookup. Constraints and traps live in [`AGENTS.md`](../AGENTS.md); scripts are catalogued in [README § Scripts](../README.md#scripts).

| Task | Edit | Run |
| --- | --- | --- |
| Add a server mod (consumer) | `overlay/mods-extra.txt` (+ deps, pinned) | `./dev up` or push to `main` |
| Add a default server mod (platform) | `config/modrinth-mods.txt` (+ deps, pinned) | Push, cut release |
| Build an in-house mod | `mods/<name>/` (Fabric project) | `cd mods/<name> && mise exec -- ./gradlew build` → `./dev up` in a linked consumer ([local-stack-testing](../.claude/skills/local-stack-testing/SKILL.md)) → cut a release to ship |
| Link a consumer to this checkout | - | `cd ~/Projects/elfydd && ./dev link` (once; `./dev unlink` to restore a release) |
| Cut a platform release | - | `gh workflow run release.yml -f version=vX.Y.Z` (**never** `gh release create`) |
| Add a client mod | `modpack/adventure.mrpack.json` | Push (CI rebuilds `.mrpack`) |
| Change a game rule | `config/boring_default_game_rules/config.json` + `scripts/deploy.sh` | Push (full deploy) |
| Change claim settings | `config/openpartiesandclaims/openpartiesandclaims-server.toml` | Push (full deploy) |
| Change a player/Discord message | `config/messages.json` | Push |
| Change a web page's look | [`docs/web-surfaces.md`](web-surfaces.md); tokens in [`DESIGN.md`](../DESIGN.md) | Push (tier varies by file) |
| Add/remove a player | - | Discord `/register` + role, or `docker exec -i mc rcon-cli "whitelist add NAME"` |
| Grant extra claims | - | `docker exec -i mc rcon-cli "lp user NAME permission set xaero.pac_max_claims N"` |
| Trigger a backup | - | `./ops backup` |
| Restore from backup | - | [README → Backups](../README.md#backups) |
| Restart a sidecar | - | `./ops restart <name>` (force-recreates; `mc` is prohibited) |
| Check mod updates | - | `./scripts/check-updates.sh` (weekly PR: `gh workflow run mod-updates.yml`) |
| Update MC version | `.env` + re-pin | Big job — [README → Update Minecraft version](../README.md#update-minecraft-version) |
| Manual deploy | - | `ssh -i ~/.ssh/mc_deploy_key deploy@$DROPLET_HOST 'cd ~/server && .stack/current/stack/scripts/deploy.sh --non-interactive'` (deploy.sh ships in the bundle — there is no `~/server/scripts/`) |
| Validate scripts | - | `./scripts/test-scripts.sh --quick` |
