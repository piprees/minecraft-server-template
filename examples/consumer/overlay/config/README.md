# Config overlay

Files placed here override the platform defaults seeded by the `defaults-seed`
image. The tree shape mirrors the template's `config/` directory.

For example, to override the Uptime Kuma monitor config:

```
overlay/config/uptime-kuma/kuma-config.json
```

Or to add custom datapacks:

```
overlay/config/datapacks/my-datapack/
```

The seed container copies platform defaults first, then copies your overlay
on top — your files win. Only place files you actually want to change;
everything else inherits from the platform.
