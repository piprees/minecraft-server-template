#!/usr/bin/env python3
# entity-pixels.py - How much of a portal opening an entity covers, from two screenshots.
#
# Purpose: the screenshot half of e2e-companion-portal.sh's entity check. The
#          same camera is shot twice through the opening: with a mob standing on
#          the far side in front of a white wall, and with the mob moved out of
#          view. A pixel of the centre crop is the mob's when, without it, the
#          pixel is the wall (bright and grey) and, with it, the pixel is not
#          (darker by more than LUMA_DROP, or coloured): the husk is tan and
#          shaded, the wall white concrete.
# Context: template-only. Needs Pillow. No Minecraft, no server.
# Usage:   entity-pixels.py <with.png> <without.png> [--crop 0.4]  -> one JSON line
#          entity-pixels.py --self-test                            -> synthetic images, exit 0 on pass
# Gotchas: the two shots must share a camera; anything else that moved between
#          them counts too, which is why the changed pixels are also reported.
#          The rule is a colour rule, not a calibration: the caller states the
#          floor it asserts.

import json
import sys

from PIL import Image

LUMA_DROP = 40
CHANGED = 48


def luma(r, g, b):
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def wall(r, g, b):
    return luma(r, g, b) >= 150 and max(r, g, b) - min(r, g, b) <= 40


def crop_pixels(image, crop):
    rgb = image.convert("RGB")
    w, h = rgb.size
    cw, ch = max(1, int(w * crop)), max(1, int(h * crop))
    x0, y0 = (w - cw) // 2, (h - ch) // 2
    raw = rgb.crop((x0, y0, x0 + cw, y0 + ch)).tobytes()
    return (w, h), [tuple(raw[i:i + 3]) for i in range(0, len(raw), 3)]


def measure(with_image, without_image, crop=0.4):
    size, a = crop_pixels(with_image, crop)
    other, b = crop_pixels(without_image, crop)
    if size != other:
        raise ValueError("the two shots differ in size: %s and %s" % (size, other))
    changed = 0
    entity = 0
    walls = 0
    for p, q in zip(a, b):
        distance = sum((x - y) ** 2 for x, y in zip(p, q)) ** 0.5
        if distance > CHANGED:
            changed += 1
        if wall(*q):
            walls += 1
            if not wall(*p) and (luma(*q) - luma(*p) > LUMA_DROP or distance > CHANGED):
                entity += 1
    n = len(a)
    return {"crop": crop, "size": list(size), "pixels": n, "wallPixels": walls, "changed": changed,
            "entityPixels": entity, "share": round(entity / n, 4), "changedShare": round(changed / n, 4)}


def self_test():
    failures = 0

    def check(name, ok, saw):
        nonlocal failures
        print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  saw: %s" % saw))
        failures += 0 if ok else 1

    scene = Image.new("RGB", (200, 100), (120, 167, 255))
    scene.paste((207, 213, 214), (60, 20, 140, 80))
    husk = scene.copy()
    husk.paste((150, 128, 88), (90, 30, 110, 75))
    same = measure(scene, scene)
    check("one shot against itself has no entity", same["entityPixels"] == 0 and same["changed"] == 0, same)
    seen = measure(husk, scene)
    check("a tan figure on the wall is counted", seen["entityPixels"] == 20 * 40 and seen["share"] > 0.2, seen)
    fogged = Image.new("RGB", (200, 100), (120, 167, 255))
    fogged.paste((190, 196, 200), (60, 20, 140, 80))
    check("a slightly fogged wall is not an entity", measure(fogged, scene)["entityPixels"] == 0, measure(fogged, scene))
    sky = Image.new("RGB", (200, 100), (120, 167, 255))
    sky_change = sky.copy()
    sky_change.paste((20, 20, 20), (90, 30, 110, 75))
    off = measure(sky_change, sky)
    check("a change over the sky is changed, not the entity", off["entityPixels"] == 0 and off["changed"] > 0, off)
    try:
        measure(husk, Image.new("RGB", (10, 10)))
        check("shots of different sizes are refused", False, "no error")
    except ValueError:
        check("shots of different sizes are refused", True, None)
    return 1 if failures else 0


def main(argv):
    if argv[1:] == ["--self-test"]:
        return self_test()
    if len(argv) < 3:
        print("usage: entity-pixels.py <with.png> <without.png> [--crop F]", file=sys.stderr)
        return 2
    crop = float(argv[argv.index("--crop") + 1]) if "--crop" in argv else 0.4
    print(json.dumps(measure(Image.open(argv[1]), Image.open(argv[2]), crop)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
