# Per-version config overlay

`config/versions/<version>/<path>` replaces or adds `config/<path>` when `.minecraft-version` (or `MC_VERSION_OVERRIDE`) names that version: `version_config_files` in `scripts/lib.sh` lists the files, and deploy.sh, dev-up.sh, `scripts/smoke.sh` and the defaults-seed image apply them after the base config. A version's own mod configs that differ from the base, or configs of mods only its list carries, go here while another version is the default.
