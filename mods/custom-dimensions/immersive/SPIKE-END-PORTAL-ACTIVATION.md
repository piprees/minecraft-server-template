# Spike — End Portal Activation Model

> **Date:** 2026-07-26 | **Status:** findings complete, design decision needed

## Question

How should the custom-dimensions mod support End-style "fill" portals alongside the existing "ignite" model? (Phase 7b in PHASE-7-PORTAL-IDENTITY.md)

## Key findings

### 1. Current ignition model is click-driven, no per-position state

`PortalIgnitionMixin.tryIgnite` hooks `ItemStack.useOnBlock` (HEAD, cancellable). On a recognised frame+item match it flood-fills via `IgnitionScan.discover`, builds a `PortalZone`, and calls `registerAndFinish`. The entire portal appears atomically — there is no "partially built" state.

`PortalZone` (PortalHelper.java:1818–1836) stores `interior`, `definition`, `axis`, `singleUseTicksLeft`, and aura fields. **No per-frame-position progress counter exists anywhere in the codebase.**

### 2. Per-tick validation already scans frame block states

`ServerWorldMixin.onTick` calls `isZoneValid` every tick, which scans every neighbour of the interior against `FrameMatcher`. `ExitPortalManager.tick` does a similar periodic scan (every 100 ticks). Both are binary intact/broken checks — never progress counters.

### 3. No vanilla End portal frame code exists in the mod

Zero hits for `EndPortalFrameBlock`, `end_portal_frame`, or `Properties.EYE`. The string `ender_eye` appears only as an `igniterItem` config value in comments. The mod has never interacted with vanilla's fill mechanic.

### 4. No existing hook fits the fill model

- `PortalIgnitionMixin` claims and cancels on any frame+item match at HEAD — a fill model can't share this path since it needs to allow the eye placement and only fire the portal on completion.
- No `UseBlockCallback` registration exists.
- `UseItemCallback.EVENT` is used for ender-pearl exits, unrelated.

## What needs building

### Schema addition

```json
"portal": {
  "activation": "fill",
  "frameBlock": "minecraft:end_portal_frame",
  "fillItem": "minecraft:ender_eye",
  "fillCount": 12,
  "shape": "end_exit"
}
```

- `activation`: `"ignite"` (default, today's behaviour) or `"fill"`.
- `fillItem`: the item that fills frame positions (replaces `igniterItem` for fill-mode portals).
- `fillCount`: how many positions must be filled before the portal forms. Derives from shape geometry — could be computed rather than configured.
- Partial progress persists via vanilla's block state (`EYE` property on `end_portal_frame`), so no custom persistence needed if the frame block is `end_portal_frame`. For custom frame blocks, the mod would need its own per-position tracking.

### New hook

A mixin on `EndPortalFrameBlock.onUse` (or a broader `Block.onUse` with frame-block filtering) that:
1. After an eye is placed, scans the frame for completeness using the portal's shape geometry.
2. On completion, calls the existing `registerAndFinish` path.
3. Does NOT cancel the vanilla eye-placement interaction — it must let the block state update happen first.

Alternatively: a block-state change listener (`ServerBlockEvents` or a mixin on `World.setBlockState`) that fires when any block in a registered frame shape transitions to "filled". This is cleaner but higher traffic.

### Changes required

| File | Change | Risk |
| --- | --- | --- |
| `DimensionConfig.java` (Portal class) | Add `activation`, `fillItem`, `fillCount` fields + parsing in `toPortalDefinition()` | Low — follows the `singleUse` pattern exactly |
| `PortalDefinition.java` | New fields | Low |
| New: `EndPortalFillMixin.java` | Mixin on `EndPortalFrameBlock` or `Block.onUse` | Medium — must not break vanilla End portal, must coexist with `PortalIgnitionMixin` |
| `PortalIgnitionMixin.java` | Skip fill-mode portals (check activation type before claiming the click) | Low |
| `PortalHelper.java` | Completion-check helper for fill mode, called from the new mixin | Low |
| Tests | New `PortalFillActivationTest` | — |

### Estimated scope

~200–400 lines of new code. 2–3 days including testing. The riskiest part is the mixin coexistence: `PortalIgnitionMixin` currently cancels at HEAD on any frame+item match, so it must learn to check activation mode and pass through for fill-mode portals.

## Decision needed

1. **Vanilla-only or generic?** If fill mode only needs to support `end_portal_frame` (which has a built-in `EYE` block state), the implementation is simpler — piggyback on vanilla's state. A generic fill model for arbitrary frame blocks needs custom per-position persistence, which is significantly more work.

2. **Computed or configured fill count?** The shape's frame positions are known at parse time. Computing the count from geometry eliminates a config field and prevents misconfiguration, but means the config can't require fewer than all positions (e.g., a "partial fill" variant).

**Recommendation:** vanilla-only for v1 (End portal frames with `Properties.EYE`), computed fill count from shape geometry. Extend to generic fills only if a concrete use case appears.
