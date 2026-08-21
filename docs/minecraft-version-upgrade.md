# Update the Minecraft version

**A big job:** all ~150 server mods and ~110 client mods must support the target version before anything moves. Expect the mod lists, not the server, to be the work.

1. Back up: `./ops backup`
2. Check compatibility: `./scripts/check-modrinth-compat.sh --version <target>`
3. Update `MC_VERSION` in `.env`, then re-pin: `./scripts/pin-mod-versions.sh --version <target> --apply`
4. Test locally: `./dev up` — watch for mod load errors
5. Deploy: push to `main`, then force a map re-render: `./ops map render`

Terralith, Incendium, and Nullscape generate custom terrain, so a version change can leave visible chunk borders where old and new chunks meet. Test on a copy of the world first.

Any mod that can't move blocks the upgrade — resolve it before starting, either by dropping the mod or by finding a maintained fork. Dependency resolution, version holds, and the flag reference for both scripts are in the [`server-mod-management`](../.claude/skills/server-mod-management/SKILL.md) skill.
