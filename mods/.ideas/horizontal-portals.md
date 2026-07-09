# Horizontal portals

Support Y-axis portal planes (floor/ceiling portals) in addition to the existing X-axis and Z-axis vertical portals.

## Motivation

Vertical-only portals feel artificial for some dimension types — a trapdoor-style floor portal into a void dimension or an upward portal to a sky dimension would be far more immersive.

## Implementation notes

- `PortalHelper.planeDirections()` needs a Y-axis case returning N/S/E/W directions
- `PortalHelper.floodFill()` must accept `Direction.Axis.Y`
- `PortalIgnitionMixin` needs to detect horizontal frames — standing on a frame block and clicking down/up, or clicking a block adjacent to a horizontal opening
- `PortalHelper.createTargetPortal()` must place horizontal portal blocks with the correct axis property and build the frame around them horizontally
- Gravity: players falling through a floor portal need correct velocity handling on the other side — teleport should preserve or zero out vertical momentum, not leave them plummeting
- Portal block rendering: vanilla nether_portal blocks support X and Z axes but not Y. May need a custom block or creative use of the existing axis property
- Frame detection: a horizontal frame is bounded in X and Z (not X/Y or Z/Y) — the flood-fill plane is different from vertical portals
