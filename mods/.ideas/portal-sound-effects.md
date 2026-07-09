# Portal sound effects

Play configurable sounds on portal ignition and teleportation.

## Motivation

Portal creation and teleportation are currently silent. Sound feedback makes the experience feel complete — a whoosh on entry, a chime on arrival, a crackle when a new portal ignites.

## Behaviour

- Add optional `igniteSound`, `enterSound`, and `exitSound` fields to `PortalDefinition`
- Default to vanilla sounds: `block.portal.trigger` for ignition, `block.portal.travel` for enter/exit
- Accept any Minecraft sound ID (e.g., `entity.enderman.teleport`, `block.amethyst_block.chime`)
- Play at the portal position with configurable volume and pitch
- Expose in `/portal link` as optional parameters
