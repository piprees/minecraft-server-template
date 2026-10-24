# The pack

Checked against Modrinth on 27 September 2026. Every project's decision, reason and replacement is in [mod-register.csv](mod-register.csv).

## Inclusion rule

A mod is in when:

1. its author ships a 26.3 build;
2. it runs as released — no loader overrides and no trials;
3. it leaves worldgen to us — structure mods join through a bridge ([worldgen.md](worldgen.md#firewall-and-bridges));
4. an unmodded 26.3 client can still join with it installed (P3's join gate), unless it is an optional client mod that is purely effects, shaders or UI.

dep-review adds a waiting mod when its 26.3 build appears and runs the join gate on every update.

## Counts

| Group | Kept | Waiting | Replaced | Rebuilt by us | Cut | Total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| admin | 9 | 2 | 0 | 0 | 0 | 11 |
| client | 35 | 17 | 1 | 0 | 2 | 55 |
| content | 1 | 1 | 13 | 9 | 10 | 34 |
| gameplay | 26 | 7 | 3 | 0 | 3 | 39 |
| library | 13 | 2 | 0 | 0 | 14 | 29 |
| perf | 8 | 2 | 0 | 0 | 0 | 10 |
| structures | 10 | 24 | 0 | 0 | 2 | 36 |
| worldgen | 0 | 0 | 1 | 19 | 1 | 21 |
| **Total** | **102** | **55** | **18** | **28** | **32** | **235** |

22 of the 55 waiting projects have a 26.2 build, so most should arrive before launch.

## By group

- **client** — Shaders, Distant Horizons, Sodium, Iris, maps, animations, human villagers, sounds, cosmetics, voice chat. All optional and presentation only ([RB-25](decisions.md)). EMI goes for polydex, which shows recipes to every client; two add-ons go with the mods they served.
- **admin and perf** — Unchanged in role. dcintegration (the chat bridge), Krypton, ModernFix and No Chat Reports wait for 26.3 builds; dcintegration has a 26.2 build, so it is the one to watch. discord-sync is ours and unaffected.
- **gameplay** — Elytra take-off, falling trees, healing campfires, sit, starter kit, voice chat, claims. Longer days and per-dimension borders are vanilla 26.x data; game rules are enforced by `deploy.sh`. Fabric Seasons is dead, and Smarter Farmers needs a library that turns vanilla clients away.
- **structures** — Moog's, Towns and Towers, Dungeons and Taverns, Explorify, YUNG's, When Dungeons Arise. Vanilla blocks only, placed by our structure control through bridges. Structory and Structory Towers are cut: the Stardust Labs licence bars using their work with AI systems, and this project is built with agents.
- **worldgen** — Terralith, Tectonic, Incendium, Nullscape, WWOO, Geophilic, Regions Unexplored, Nature's Spirit, Wilder Wild, BetterEnd, BetterNether, Galosphere, YUNG's caves, Paradise Lost. We own worldgen; each register row names the part of our kit or biome set that takes its place. Pale Garden Backport is replaced by vanilla.
- **content** — Furniture, doors, backpacks, armour, compasses, creatures. Farmer's Delight stays through its Polymer patch; Friends & Foes follows when it and its patch reach 26.3. Server-side replacements: elytra-armor, inventory-totem, bundles, slashloot, polydecorations, gm4-double-doors, clucking-ducks, abridged.
- **library** — They follow the mods that need them.

## Additions

Not in the register's counts; each is pinned to its 26.3 build in P2, or waits.

- Polymer, with the Farmer's Delight and Friends & Foes patches
- polydecorations, polydex, elytra-armor, inventory-totem, slashloot, sswaystones, clucking-ducks, gm4-double-doors, abridged
- Geyser and Floodgate
- Vanilla-block structure packs, each through a bridge: Repurposed Structures, Trek, Hopo's Better Mineshafts, Decubed Dungeons, the two Dungeons and Taverns overhauls
- bibliophilia, for the pack

## The optional client pack

The `.mrpack` through Prism carries the kept client mods, Distant Horizons, shaders and the companion (P4). Nothing in it is needed to join.
