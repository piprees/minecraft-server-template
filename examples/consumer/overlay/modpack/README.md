# Modpack overlay

`manifest.json` is a JSON patch applied on top of the platform's default
client modpack manifest (`adventure.mrpack.json`). Use it to add or remove
client mods for your instance.

## Patch schema

The patch object is merged shallowly into the base manifest. To add client
mods, provide the relevant keys:

```json
{
  "_clientMods": {
    "required": [
      { "slug": "my-extra-mod", "versionId": "abc123" }
    ]
  }
}
```

To override metadata (name, description, etc.):

```json
{
  "name": "My Server Pack",
  "summary": "The official modpack for My Server"
}
```

An empty `{}` (the default) changes nothing — you get the platform defaults.

## Overrides

Place client-side config files in `overlay/modpack/overrides/`. These are
merged into the built `.mrpack` and applied to the player's instance.
The same rules as the template apply: use `configureddefaults/` for
merge-safe defaults, never raw `overrides/` for user-tunable files.
