# Structure placement — design principles

**Stated by the maintainer, 2026-08-29. These are the specification. Anything in
the code, the docs or the skills that contradicts them is a defect, not a
design.**

## 1. What a want is

> A want means "admit this structure where the noise puts it", not "use this
> wherever nothing else fits".

**Wants are a SEED-ROLLING feature.** Want data enters structure generation for
one reason only: so the wanted structure is **mixed into the noise properly**.
That is not the same as emphasising it.

- Correct mechanism: **re-roll the noise without the wanted structures present
  where we want them.**
- A want is NOT a forced placement. `structures.force` is the mechanism for "put
  this exact structure at this exact coordinate".
- A want must never become a fallback, a filler, or a universal candidate.

Measured consequence of getting this wrong: allowing a bypass-flagged candidate
to be reached by re-draw turned one `seedRoll.wants` entry into **340
`minecraft:monument` sites** in a 512-chunk radius. One want, 340 ocean
monuments.

## 2. Shuns are deliberately asymmetric with wants

Shuns SHOULD artificially reduce or entirely remove a structure, depending on
the settings chosen. This asymmetry is intended. Do not "fix" it for symmetry
with wants.

## 3. Absence is a valid, expected outcome

In the original design of the noise generators **the lack of a structure is
completely valid, and the vast majority of noise values were expected to be
null / false / empty.**

So a high empty-site count is not automatically a defect and must never be
"fixed" by filling. 1725 empty sites of 4586 may be correct, or may still be too
few.

**Do not add fallbacks.** A site whose assigned structure refuses the position
stays empty. Falling through to another group's pool is the "use this wherever
nothing else fits" anti-pattern and is forbidden.

## 4. Avoid the central bound of the noise distribution

Noise values cluster around the centre of their range. Placing structures
wherever the field crosses a threshold therefore over-places at the popular
"shade range" of the map.

**Structures must not be placed artificially often merely because they landed on
a common noise value** — especially larger structures. Exclude the central bound
of the distribution, with the width of that exclusion derived from the
spacing/density being targeted.

## 5. The product goal these serve

Worlds that feel believable, not a smattering of random or patterned structures.
Specifically avoid:

- a world stuffed full of assorted gibberish structures
- millions of the same structure
- over-emphasised wants

> "Imagine you're a DM trying to tell a story: 'and then... the party arrived at
> another church, much like the last...'"

A world with hundreds of vibrant villages and the same abandoned church repeated
throughout is the failure mode. Repetition stands out to a player trying to have
an adventure.

## What these principles already invalidate

- **The A4 proposal "let a refused site fall through to another group's pool"
  is WRONG and is retracted.** It is precisely principle 1's anti-pattern and
  would stuff the world.
- Raising `StructurePick.MAX_CANDIDATES` to reduce empty sites is suspect for
  the same reason. More re-draws means more "something rather than nothing".
- The 1725 empty sites are not, by themselves, a problem to solve.
- The real maritime defect stands: **466 of 666 maritime sites are not in ocean.**
  The fix is to stop placing the site there, never to fill it with something else.
