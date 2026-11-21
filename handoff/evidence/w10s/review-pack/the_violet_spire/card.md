# the_violet_spire — design card
Source: 1.21.1 paradise_lost type; biomes amethyst_canyon, windswept_spires, crystal_peaks (patch -240,200), haze_mountain, skylands_spring, highlands, highlands_thicket, wisteria_woods, lavender (spawnFilter lavender_fields, amethyst_canyon), sparse_jungle patch (-420,-230); sky #6B4B9B fog #3A2352 light 0.25; force aurel_tower (60,60), mystical_archway (-300,180); wants aurel_tower near spawn, birdcage, wizard_tower, shrine_tower, mystical_archway spread, para_palace and relic_temple near border; shuns village, monument. Owner: night-only; crystals that show; growth on some spire tops.

| Phrase | A player must see |
| --- | --- |
| "Violet spires in the sky" | Sky: fixed night under the violet sky and fog. Terrain: sky islands carrying tall spires, amethyst faces. |
| "where endermen architecture meets the natural skylands" | Two worlds: an End-ward side (purpur, chorus, crystal) and skyland meadows. Structures: End-made towers (mes enderwatch_tower, mystical_archway) beside skyland towers (mss large_tower as the aurel tower, small_tower). Mobs: endermen at night are natural; no extra. |
| "Wisteria islands" | wisteria_grove the largest place. |
| "amethyst canyons" | crystal_hollows (the library's amethyst canyon: amethyst, budding amethyst, clusters) on island tops; amethyst cliffs. |
| "aurel towers and birdcages" | mss:large_tower forced at (60,60); birdcage has no 26.3 stand-in: waiting on the places kit. |
| "Arcane but not hostile" | Lit, calm; mobMultiplier 1. No dungeons, no keeps. |
| "two worlds overlapped and neither broke" | Bands of places meeting, both whole. |
| Owner: night-only, crystals, growth on spire tops | atmosphere.fixedTime 18000; crystal_hollows; spires with flatter tops that carry grass and trees. |

Must NOT appear: cherry_grove (nothing asks for it; it came from skylands_spring by conversion) — removed; mineshafts, villages, monuments, overworld dungeons; snow peaks; daylight.
Remove: cherry_grove from the biome list and the skylands place (sky_highlands keeps the skylands).

Danger and reward (RB-39): calm: "arcane but not hostile"; danger low, reward middling-high from the towers and temples (playerLuck lifts loot). 1.21.1 difficulty block: {"mobMultiplier": 1.0, "playerLuck": 1.6}.

## Structures (places plan, docs/design/rebuild-26/places/dimensions.md)
Follows the places plan: aurel tower mss:large_tower forced (60,60); mes:mystical_archway forced (-300,180) and spread; mes:enderwatch_tower; ddd:wizard_tower; nova shrine_tower; the relic temple and palace ruin near the border as ati fortified_temple and mss castle_ruin (wants 450-650). Crowned spires and birdcages: set pieces, waiting on the places kit (the crowned spires are approached in terrain: crystal_hollows tops and flatter spire tops). This replaces the card's earlier drop of the temples.
