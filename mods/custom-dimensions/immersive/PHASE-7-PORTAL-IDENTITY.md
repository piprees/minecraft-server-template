# Phase 7 — Portal Identity: consistent doors, both ways

> **Status:** presentation SHIPPED and tested (v3.9.1). The End's activation model and the sound half of the rule remain.
>
> The colour/particle half was implemented long before it worked. It fell back to the DESTINATION's own portal whenever `getPortalFor(sourceWorld)` returned null — which is every arrival from the overworld, i.e. almost all of them — so the way home out of an ember dimension glowed ember. It had **zero tests** (audit row 9), which is why it survived. Fixed by treating a null source presentation as a BASE world and using `PortalHelper.NEUTRAL_PORTAL_COLOR`; 6 tests in `PortalPresentationTest`, and verified in game (a fresh claymarsh arrival now registers colour `0x8844ff`, not the claymarsh's own). **Depends on:** nothing. Independent of the immersive stack.

## The rule

> **Material describes where a portal IS. Presentation describes where it GOES.**

A portal's frame is built from the dimension it stands in, so it is recognisable on arrival and can be built from what is locally available. Its colour, particles and sounds belong to the world on the other side, because its job is to take you there.

Raised by the project owner 2026-07-25:

> _"Dimensions should always have this kind of consistency. If I go to the nether it's always a nether portal and it always makes the same sounds and looks the same. If I come back, now that's an oversight on the original game because they didn't need or have 'overworld' portals, but we do, so ours should have a consistent theme too."_

Vanilla never needed the second half: the nether portal you arrive through is the same block you go home through, so it cannot disagree with itself. Ours are built per dimension, and without the rule the way home inherits the presentation of the place you are trying to leave — a portal out of an ember dimension glowing ember, reading as another door deeper in rather than the exit.

## Shipped

- `MultiverseConfig.getPortalFor(targetWorld)` — the definition describing travel INTO a world. A dimension's `portal` block has always meant "the portal that leads to this dimension", which is what makes it meaningful in the return direction.
- `createTargetPortal` resolves colour and particle type through it, falling back to the local definition when the destination configures no portal.
- `overworld.json` has a `portal` block of its own — frame `minecraft:mossy_stone_bricks` (matching the existing `settings.json` `frames.overworld`), igniter `minecraft:ender_eye`, a cool blue `7FC8F8` and cherry-leaf particles. Every arrival portal in every custom dimension now reads as an overworld portal.

## Not started

### 7a. Sounds follow the same rule

`enterSound`/`exitSound` still come from the local definition. The traversal sounds should follow presentation, not material — going home should sound like the overworld's portal wherever you leave from.

Ambiguity to settle first: the exit sound plays at the ARRIVAL. Travelling overworld → ember, you emerge from the ember-side portal, which is an _overworld_ portal by the rule above. Whose sound is that? Two defensible answers; pick one and write it down rather than leaving it to whichever definition happens to be in scope.

### 7b. The End's activation model

End portals cannot use the igniter model, and forcing them into it is why the End is currently the odd one out. Vanilla's End portal is **filled**, not lit: a frame of `end_portal_frame` blocks, each of which must be given an eye of ender, and the portal forms when the twelfth goes in. There is no click-to- ignite moment and no igniter item in the config's sense.

This needs a genuine schema addition, not a reskin:

- An `activation` block on `portal`: `"ignite"` (today's behaviour, the default) or `"fill"`.
- For `"fill"`: the frame block that accepts the filling (`end_portal_frame`), the item that fills it (`ender_eye`), the count required, and whether partial progress persists (it must — vanilla's does, in the block state).
- Detection is a block-state watch rather than a use-item hook, so it wants its own path; `PortalIgnitionMixin` is the wrong place.
- The existing `end_exit` shape preset and `centreBlock` pedestal already describe the geometry. Only activation is missing.

Worth doing properly: it is the one dimension family whose portal every player already knows the rules for, so getting it almost-right will read as broken in a way an invented portal never would.

### 7c. Login into a runtime dimension

Deferred by the owner ("likely just a problem with the game not wanting to spawn me in an impossible place on login"), recorded so it is not lost.

Symptom: logging out inside a runtime dimension and back in puts the player in the overworld, sometimes at an unrelated position, sometimes in the sky. The suspected cause is ordering — runtime worlds are created lazily on first player entry, so at login the world a player is stored in does not exist yet and vanilla falls back to the overworld spawn. If that is right, the fix is to force-create a dimension when a player whose stored world key names it connects, before the spawn logic runs.

Confirm the cause before building anything: check whether the player's stored dimension is one of ours and whether the world exists at `ServerPlayConnectionEvents.JOIN` time.
