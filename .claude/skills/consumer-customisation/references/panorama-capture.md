---
title: Panorama Capture
description: Step-by-step guide to capturing cubemap faces for the title screen panorama resource pack
tags: [panorama, cubemap, title-screen, resource-pack, screenshots]
---

# Capturing cubemap faces for the title screen panorama

Cubemap faces must be **square** screenshots taken at **exactly 90° FOV** with no dynamic FOV effects. Mismatched FOV or non-square images cause visible seams at cube edges.

## 1. Set options.txt before launching

Close Minecraft, then edit `options.txt` in your Prism/MultiMC instance:

```
fov:0.5
fovEffectScale:0.0
fullscreen:false
overrideWidth:1024
overrideHeight:1024
```

- `fov:0.5` = exactly 90° (the slider is non-linear: `0.0` = 70°, `0.5` = 90°, `1.0` = 110°)
- `fovEffectScale:0.0` = disables dynamic FOV from sprinting/potions
- `overrideWidth`/`overrideHeight` = forces a square window (critical — widescreen screenshots won't tile correctly)

## 2. Launch and freeze the world

```
/gamerule doDaylightCycle false
/gamerule doWeatherCycle false
/time set 6000
/weather clear
/gamemode spectator
```

Press **F1** to hide HUD. Enable shaders if desired.

## 3. Capture the 6 faces

Replace `<x> <y> <z>` with your coordinates from `.env` (`SPAWN_X`, `SPAWN_Y`, `SPAWN_Z`). Run each command, then take a screenshot (**F2**):

```
/tp @s <x> <y> <z> 0 0        → panorama_0.png (South)
/tp @s <x> <y> <z> 90 0       → panorama_1.png (West)
/tp @s <x> <y> <z> 180 0      → panorama_2.png (North)
/tp @s <x> <y> <z> -90 0      → panorama_3.png (East)
/tp @s <x> <y> <z> 0 -90      → panorama_4.png (Up)
/tp @s <x> <y> <z> 0 90       → panorama_5.png (Down)
```

## 4. Reset

```
/gamemode survival
/gamerule doDaylightCycle true
/gamerule doWeatherCycle true
```

Press **F1** to show HUD. Restore your preferred settings in `options.txt`:

```
fov:0.5
fovEffectScale:0.5000000000000001
fullscreen:true
overrideWidth:0
overrideHeight:0
```

## 5. Process and place

Rename the 6 screenshots to `panorama_0.png` through `panorama_5.png` and place them in the overlay:

```
overlay/modpack/overrides/configureddefaults/resourcepacks/server-panorama/
└── assets/minecraft/textures/gui/title/background/
    ├── panorama_0.png through panorama_5.png
```

Optionally crush them with `pngquant` to reduce pack size:

```bash
for f in panorama_*.png; do pngquant --quality=80-100 --speed 1 --force --output "$f" "$f"; done
```

Your images replace the template defaults. No `pack.mcmeta` or `options.txt` changes needed.
