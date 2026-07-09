# Per-portal cooldown configuration

Make the teleportation cooldown configurable per portal link instead of hardcoded.

## Motivation

The current 40-tick (2 second) cooldown is hardcoded in ServerWorldMixin and EntityTickPortalMixin. Hub portals in compact dimensions should be near-instant, while exploration portals to full-size worlds might want a longer, more dramatic delay.

## Behaviour

- Add a `cooldown` field to `PortalDefinition` (default 40 ticks / 2 seconds)
- Expose in the `/portal link` command as an optional parameter
- Apply per-portal when teleporting in ServerWorldMixin (`player.resetPortalCooldown(cooldown)`)
- Persist in `multiverse_config.json` alongside existing portal fields
- Range: 0 (instant) to 200 (10 seconds)
