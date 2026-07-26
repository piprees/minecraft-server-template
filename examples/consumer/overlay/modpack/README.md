# Modpack overlay

`manifest.json` is a JSON patch applied on top of the platform's default
client modpack manifest (`adventure.mrpack.json`). Use it to add or remove
client mods for your instance.

## Patch schema

The patch is deep-merged onto the base manifest (see
`docker/modpack-builder/merge-manifest.py` in the template repo). To add or
remove client mods, use `add`/`remove` with `slug:versionId` entries — the
same format as the base manifest's own `_clientMods` lists:

```json
{
  "add": {
    "required": ["my-extra-mod:abc123"]
  },
  "remove": ["some-default-mod"]
}
```

`add.required`/`add.optional` append slugs (duplicates are skipped);
`remove` drops slugs from both `required` and `optional`. Only slugs
already in the platform's mod catalogue can be added this way — a mod
that's genuinely new needs a PR to the template repo first.

To override top-level metadata:

```json
{
  "name": "My Server Pack"
}
```

An empty `{}` (the default) changes nothing — you get the platform defaults.

## Overrides

Place client-side config files in `overlay/modpack/overrides/`. These are
merged into the built `.mrpack` and applied to the player's instance.
The same rules as the template apply: use `configureddefaults/` for
merge-safe defaults, never raw `overrides/` for user-tunable files.
