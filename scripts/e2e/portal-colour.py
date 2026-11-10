#!/usr/bin/env python3
# portal-colour.py - How much of a screenshot's centre is a vanilla portal.
#
# Purpose: the screenshot half of e2e-vanilla-portal.sh. A client without the
#          companion is sent a Nether portal block for an upright opening and
#          an End portal block for a flat one; this measures the share of the
#          centre crop each would paint: `nether` counts violet pixels (blue
#          well above red and both well above green, so magenta concrete is
#          not one), `end` counts near-black ones. `hue:RRGGBB` counts the
#          pixels a server-pack pane tinted that colour paints: within
#          HUE_TOLERANCE degrees of its hue, saturated and lit.
# Context: template-only. Needs Pillow. No Minecraft, no server.
# Usage:   portal-colour.py <png> nether|end|hue:RRGGBB [--crop 0.4]   -> one JSON line
#          portal-colour.py --self-test                     -> synthetic images, exit 0 on pass
# Gotchas: the thresholds are a colour rule, not a calibration: the share is
#          reported so a run can be judged against its own screenshot, and the
#          caller states the floor it asserts.

import colorsys
import json
import sys

from PIL import Image


def violet(r, g, b):
    return b >= 80 and b > 1.3 * g and r > 1.2 * g and b > 1.25 * r


def dark(r, g, b):
    return 0.2126 * r + 0.7152 * g + 0.0722 * b < 40


RULES = {"nether": violet, "end": dark}
HUE_TOLERANCE = 15


def tinted(target):
    th, ts, _ = colorsys.rgb_to_hsv(*(c / 255 for c in target))
    if ts < 0.3:
        raise ValueError("hue:%02X%02X%02X has no hue to match" % target)

    def rule(r, g, b):
        h, sat, val = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        apart = abs(h - th) * 360
        return min(apart, 360 - apart) <= HUE_TOLERANCE and sat >= 0.3 and val >= 0.25
    return rule


def rule_for(mode):
    if mode in RULES:
        return RULES[mode]
    if mode.startswith("hue:") and len(mode) == 10:
        v = int(mode[4:], 16)
        return tinted(((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF))
    raise ValueError("unknown mode " + mode)


def share(image, mode, crop=0.4):
    rule = rule_for(mode)
    rgb = image.convert("RGB")
    w, h = rgb.size
    cw, ch = max(1, int(w * crop)), max(1, int(h * crop))
    x0, y0 = (w - cw) // 2, (h - ch) // 2
    box = rgb.crop((x0, y0, x0 + cw, y0 + ch))
    raw = box.tobytes()
    pixels = [tuple(raw[i:i + 3]) for i in range(0, len(raw), 3)]
    hits = sum(1 for p in pixels if rule(*p))
    return {"mode": mode, "crop": crop, "size": [w, h], "pixels": len(pixels), "matching": hits,
            "share": round(hits / len(pixels), 4)}


def self_test():
    failures = 0

    def check(name, ok, saw):
        nonlocal failures
        print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  saw: %s" % saw))
        failures += 0 if ok else 1

    sky = Image.new("RGB", (200, 100), (120, 167, 255))
    portal = sky.copy()
    portal.paste((90, 20, 170), (70, 30, 130, 70))
    floor = Image.new("RGB", (200, 100), (94, 168, 24))
    end = floor.copy()
    end.paste((10, 12, 18), (60, 25, 140, 75))
    check("sky alone holds no violet", share(sky, "nether")["share"] == 0, share(sky, "nether"))
    check("a violet pane in the centre is counted", share(portal, "nether")["share"] > 0.5, share(portal, "nether"))
    check("a lime floor is not dark", share(floor, "end")["share"] == 0, share(floor, "end"))
    check("an End portal's black is counted", share(end, "end")["share"] > 0.9, share(end, "end"))
    check("the magenta concrete frame is not read as the portal",
          share(Image.new("RGB", (50, 50), (169, 48, 159)), "nether")["share"] == 0, None)
    blue = sky.copy()
    blue.paste((40, 130, 160), (70, 30, 130, 70))
    check("a light-blue pane is counted against its own hue", share(blue, "hue:3AB3DA")["share"] > 0.5,
          share(blue, "hue:3AB3DA"))
    check("the sky is not light blue enough to count", share(sky, "hue:3AB3DA")["share"] == 0, share(sky, "hue:3AB3DA"))
    check("the vanilla violet is not a light-blue pane", share(portal, "hue:3AB3DA")["share"] == 0, None)
    yellow = Image.new("RGB", (50, 50), (200, 170, 50))
    check("a yellow pane is counted", share(yellow, "hue:FED83D")["share"] == 1, share(yellow, "hue:FED83D"))
    check("red concrete is not a yellow pane", share(Image.new("RGB", (50, 50), (142, 33, 33)), "hue:FED83D")["share"] == 0,
          None)
    try:
        rule_for("hue:9D9D97")
        check("a grey target is refused", False, "accepted")
    except ValueError:
        check("a grey target is refused", True, None)
    return 1 if failures else 0


def main(argv):
    if argv[1:] == ["--self-test"]:
        return self_test()
    if len(argv) < 3:
        print("usage: portal-colour.py <png> nether|end|hue:RRGGBB [--crop F]", file=sys.stderr)
        return 2
    try:
        rule_for(argv[2])
    except ValueError as e:
        print(str(e), file=sys.stderr)
        return 2
    crop = float(argv[argv.index("--crop") + 1]) if "--crop" in argv else 0.4
    print(json.dumps(share(Image.open(argv[1]), argv[2], crop)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
