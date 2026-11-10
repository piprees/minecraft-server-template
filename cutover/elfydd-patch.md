# Elfydd for 26.3: the proposed patch

What the Elfydd consumer repo (`piprees/elfydd`, read at commit `0c3bea3`, "take the v5.27.1 scaffold") changes to run the `v6.0.0` stack. A proposal for the owner: nothing here is applied. The server-side steps (archive, firewall, world reset) are in `migration-notes.md`; this file is the repository.

## What Elfydd carries today

| Path | Content | Under 26.3 |
| --- | --- | --- |
| `.github/workflows/deploy.yml` | `deploy-reusable.yml@v5`, `stack_version: ${{ vars.STACK_VERSION \|\| 'latest' }}`; no `STACK_READ_TOKEN`, no `FLOODGATE_KEY_B64` | An older scaffold: the template's is pinned by a committed `.stack-version` and passes both secrets |
| `.stack-version` | absent | The pin a deploy reads first; without it the deprecated `STACK_VERSION` repository variable, then `latest` |
| `.github/workflows/update.yml`, `server-power.yml`, `dev`, `ops`, `AGENTS.md`, `commands.json` | the v5 scaffold | `./dev update` from the v6 bundle replaces them |
| `overlay/config/custom-dimensions/dimensions/` | 12 `*_thumb.json` and 24 `*_low.png`/`*_high.png`: the 1.21.1 seed viewer's thumbnails | Nothing reads them: `stage-dimensions.py` ignores them with one line. No definition override is there, so nothing needs converting |
| `overlay/config/README.md`, `overlay/config/deploy-banner.txt` | prose and the deploy banner | Unchanged |
| `overlay/mods-extra.txt`, `overlay/mods-remove.txt` | comments only | Unchanged; a slug added later must have a 26.3 build |
| `overlay/modpack/manifest.json` | `name`, `summary` | Unchanged |
| `overlay/assets/` | branding | Unchanged |
| `.env.example` | `SPAWN_X/Y/Z` empty | Unchanged: the overworld definition's `spawn` sets the world spawn |

## The route a v2 override takes

For the record, since Elfydd has none yet: `overlay/config/custom-dimensions/dimensions/<slug>.json` is staged by `scripts/stage-dimensions.py` on every deploy and `./dev up`. `{"overrides": {"seed": 123}}` merges over the platform definition (objects key by key, `null` removes a key); a file with `"schemaVersion": 2` replaces it or adds a dimension; `{}` removes a lazy dimension; `overlay/config/custom-dimensions/bridges/<name>.json` adds a structure bridge for a worldgen mod in `mods-extra.txt`. A 1.21.1 file is not staged and the deploy log names it. A seed picked on the server with `/customdim pick` lives in `data/config` until the next full deploy; keep it as an `overrides` file here.

## The patch

Before `v6.0.0` is published, so no deploy can take it early:

1. Pin the current line. `deploy.yml` passes `vars.STACK_VERSION || 'latest'`, and `deploy-reusable.yml@v5` refuses a bundle from another major, so with the variable unset the first push after the release fails rather than migrating; pin it anyway so the failure never happens: commit `.stack-version` containing the running release (`readlink ~/server/.stack/current` on the server, e.g. `v5.27.1`). A committed `.stack-version` wins over the variable; delete the `STACK_VERSION` repository variable once it is in.

At the migration (`migration-notes.md` step 6), one commit:

2. `.stack-version`: `v6.0.0`. First: the v6 `dev` that step 3 re-runs itself as reads the file before an exported `STACK_VERSION`, so step 1's pin left in place pulls v5 back.
3. `./dev update --scaffold-only` with `STACK_VERSION=v6.0.0` exported (Elfydd's v5 `dev` resolves its pin from `.env` and the variable only), which pulls the v6 bundle and replaces the scaffold files its `update)` case names: `dev`, `ops`, `.env.example`, `.gitignore`, `AGENTS.md`, `commands.json`, `.github/workflows/{deploy,update,server-power}.yml` and `.github/dependabot.yml`. It leaves `.stack-version`, `README.md` and `overlay/` alone.
4. `.github/workflows/deploy.yml`: step 3 brings the scaffold's, which already carries the `stack_version` input, `stack_version_override` and both secrets below; the one change is the reusable workflow's ref, as the v6 scaffold has it: `uses: piprees/minecraft-server-template/.github/workflows/deploy-reusable.yml@v6`, `with: stack_version_override: ${{ inputs.stack_version || '' }}` and the `workflow_dispatch` `stack_version` input, and two more secrets:

   ```yaml
         STACK_READ_TOKEN: ${{ secrets.STACK_READ_TOKEN }}
         FLOODGATE_KEY_B64: ${{ secrets.FLOODGATE_KEY_B64 }}
   ```

5. Delete the 36 seed-viewer files (`*_thumb.json`, `*_low.png`, `*_high.png` in `overlay/config/custom-dimensions/dimensions/`), which are all `overlay/config/custom-dimensions/` holds.
6. Commit: `feat!: move to the v6 stack (Minecraft 26.3)`.

Secrets, in the Elfydd repository's `production` environment, before step 6 is pushed:

7. `FLOODGATE_KEY_B64`: `base64 < key.pem | tr -d '\n' | gh secret set FLOODGATE_KEY_B64 --env production --repo piprees/elfydd` with `key.pem` the one Floodgate writes to `data/config/floodgate/key.pem` at its first boot (the rehearsal's, or production's after the first 26.3 boot, copied off the server). Without it Floodgate keeps the key it generated, which a rebuilt server loses.
8. `STACK_READ_TOKEN`, only while the template repository is private: a fine-grained token with read access to `piprees/minecraft-server-template` contents and releases.

Nothing else in Elfydd names 1.21.1: no overlay config of a cut mod (`betterdays/`, `lootr/`, `tectonic.json`, the flat Serilum files) is present.
