# Phase 6 — Aura Policy: What May an Aura Eat?

> **Depends on:** nothing in the immersive stack — portal auras shipped in
> v3.7.0 and are independent of Phases 0–5. This is a behavioural change to an
> already-live system.
> **Status:** Not started — specification only.
> **Priority:** Higher than Phase 5. Phase 5 is polish; this one touches
> player property.

## The question

Raised by the project owner 2026-07-25, and it is the right question:

> *"Let's say someone builds a house with a portal, do we want it to subsume
> their basement? Maybe we DO for some super hard dims (that's a hell of a
> statement) but for other dims we wouldn't want it to (a peaceful pocket dim
> should be unassuming and easy to hide), while if it was a portal out on the
> beach it would make sense to slowly subsume the sand around it."*

That is three different correct answers for three different dimensions, which
means it is a **policy**, not a fix.

## Current behaviour (this is live, today)

`PortalAuraManager` converts blocks in an annulus around every linked portal
pair. Its only exclusion is **the portal's interior and frame ring** — plus,
as of 2026-07-25, the portal's own building materials are excluded from the
sampled *palette* (so a plank-framed portal no longer seeds planks), but that
does not stop it converting planks it finds.

So today, with defaults (`radius` 8, `budget` 300 lifetime conversions per
side):

- A house built around a portal **will** have up to 300 of its blocks
  converted, within 8 blocks of the portal.
- **Claims are not consulted.** `open-parties-and-claims` is installed
  (`config/openpartiesandclaims/`), and the aura ignores it entirely.
- Conversions are one-way. There is no undo, and the original block is not
  recorded anywhere by the mod.

Nobody has complained yet because the shipped dimensions' portals mostly sit
in wilderness. The moment players build around portals — which is the whole
point of a hub dimension — this becomes a grief report that is technically
working as designed.

**This is the argument for doing Phase 6 before Phase 5.** A cosmetic
limitation annoys; eating someone's basement loses trust.

## Design

### `portal.aura.subsume` — a per-dimension policy

```jsonc
"portal": {
  "aura": {
    "subsume": "natural"   // "none" | "natural" (default) | "everything"
  }
}
```

| Value | Meaning | Fits |
|---|---|---|
| `none` | Convert nothing. The aura still spawns particles/flora on natural ground it placed itself, but never replaces an existing block. | Peaceful pocket dimensions — "unassuming and easy to hide" |
| `natural` | Convert only blocks that were not placed by a player. **The default.** | A portal on a beach slowly taking the sand — the intuitive behaviour |
| `everything` | Convert whatever it reaches, player builds included. | Deliberately hostile dimensions, where the encroachment IS the statement |

`everything` must be opt-in per dimension and should be called out in that
dimension's description, because it is a promise to the player that their
build is at risk. It is a legitimate design choice — a dimension whose
influence overpowers even a player's walls says something no amount of red
text can — but it must never be the default and never a surprise.

### How to tell "player-placed" from "natural"

Three candidate mechanisms, best first:

1. **Claims (`open-parties-and-claims`).** Do not convert inside a claimed
   chunk, regardless of `subsume` — **including under `everything`** (decided;
   see below). This is the
   cheapest, most socially-correct signal available: the player has already
   said "this is mine". It should apply as a hard gate ABOVE the policy, not
   as one of its cases. Check whether the mod exposes a server-side API for a
   position query; if it does not, treat this as unavailable rather than
   reaching into its internals.
2. **Ledger (`ledger:t1AtqfxZ`, installed).** Logs block placements to
   `ledger.sqlite`. Authoritative for "did a player put this here", but it is a
   **SQL query per candidate block**, and the aura runs from the world tick.
   Do not query it synchronously from the tick — that is the same class of
   mistake as sync-loading a chunk. If used at all, it must be an async
   pre-pass that populates a bounded cache.
3. **Self-tracking.** Record positions the aura itself converted (it already
   persists `budgetSpent`), and additionally snapshot the natural block at
   link time within the aura radius. Anything that differs from the snapshot
   was changed by somebody — player or another mod. Self-contained, no
   dependency, but it costs persistent storage proportional to aura radius and
   only works for portals created after the feature ships.

**Recommendation:** implement (1) as a hard gate and (3) as the `natural`
discriminator, and treat (2) as a documented non-goal unless a cheap async
path appears. (1) alone probably solves 90% of real cases, because the people
who care about their builds are the people who claim them.

### Reversibility — decided against

An earlier draft proposed persisting original block states plus a
`customdim aura revert` command. **The owner dropped it**: with claims as the
protection mechanism the exposure is small and self-inflicted, and rebuilding
is quick. See the decision section below. Do not reintroduce it without
revisiting that reasoning — the persistence only ever existed to serve the
command.

## Verification

- Build a structure of a distinctive block around a portal, let the aura run
  with each `subsume` value, assert conversions by `execute if block`.
- Claimed-chunk test: claim the area, confirm zero conversions inside it.
- `everything`: confirm it does convert, and that the dimension config
  documents it.
- Regression: a wilderness portal with no players nearby behaves exactly as
  it does today.

## Decided (owner, 2026-07-25)

**`natural` is the default, including for dimensions that already exist.**
Today's behaviour is the surprising one; changing it under live worlds is the
correct direction, not a compatibility break to be avoided.

**The three values are lore, not just safety settings** — which is why all
three earn their place rather than `none`/`everything` being escape hatches:

- **`everything` is a narrative device.** The owner's framing: the sculk and
  void dimensions imply *something is eating or rotting the world* — that is
  the in-fiction explanation for their sky islands. A portal to one of those
  overpowering a player's walls is the dimension telling the truth about
  itself. Use it where the encroachment IS the story, and say so in the
  dimension's `description`.
- **`none` is infrastructure.** Exit portals and other guaranteed-way-home
  fixtures should not slowly convert their own surroundings — the point of
  them is to be a stable, recognisable landmark. `none` is also the right
  answer for any dimension meant to be discreet ("a peaceful pocket dim
  should be unassuming and easy to hide").
- **`natural` is the world breathing.** A portal on a beach taking the sand
  around it, without touching what anyone built.

When implementing, audit the shipped dimension set against this: the sculk/
void family are `everything` candidates, `exitPortal` fixtures want `none`,
everything else takes the default.

**Claims are an absolute veto, including under `everything`.** Confirmed by
the owner. The reasoning is worth keeping, because it changes what the feature
is for:

> *"We don't want people to really be able to have an ultra-giga-hard dim in
> their basement anyway, but if they built a portal like that without a
> protection mechanism like a claim then, well, that's karma and should make
> people think twice."*

So the claim is not merely a safety net bolted onto a destructive feature — it
is the **interaction**. Claiming land is how a player says "I am prepared to
host this thing"; not claiming it is a decision with consequences. That also
neatly stops the degenerate case of an `everything` dimension being parked
inside somebody's base with no cost.

Implementation consequence: the claim check is a hard gate evaluated BEFORE
the `subsume` policy, and `everything` does not bypass it. One rule, no
exceptions — an exception would make the guarantee unexplainable to players.

**No revert command.** Explicitly dropped by the owner: rebuilding is quick,
and with claims as the protection mechanism the exposure is small and
self-inflicted. Do NOT persist original block states for this purpose — that
was only ever justified by the revert command, and without it the storage is
dead weight. (Recorded so a future agent does not "helpfully" add it back.)

## Still open

Nothing. Both original questions are decided above; the remaining work is
implementation.
