#!/usr/bin/env python3
# portal-matrix-plan.py - Turn the dimension configs into a portal test room:
# one bay per mod-managed portal, its frame geometry, and what each assertion
# should expect.
#
# Context: the pure half of e2e-portal-matrix.sh. No Minecraft, no docker, no
# RCON - configs in, a JSON plan out, so the arithmetic that decides where a
# frame stands and whether a proximity twin can link is testable on its own
# (portal-matrix-selftest.sh). Template-only; nothing ships this.
#
# Usage:
#   portal-matrix-plan.py [options] > plan.json
#
#   --dimensions DIR        rendered dimension configs (default: the consumer's
#                           data/config/custom-dimensions/dimensions, else the
#                           platform's config/custom-dimensions/dimensions)
#   --compare-dimensions D  second config dir to diff against; drift is reported
#                           (default: the platform's, when --dimensions is not it)
#   --settings FILE         custom-dimensions/settings.json (for the namespace)
#   --origin-x N            west edge of the first bay (default -6800)
#   --plane-z N             the frame plane, and the room's north end (default -6000)
#   --floor-y N             the room floor's top surface (default 40)
#   --horizontal MODE       forced | all | none (default forced) - which
#                           dimensions get a Y-axis bay
#   --igniter-expectation M auto | untouched | damaged | consumed (default auto)
#   --only SLUG[,SLUG]      restrict to these dimensions
#   --limit N               first N bays after filtering
#   --from SLUG             drop bays before this one (resume)
#   --border N              the overworld player border (default 8192)
#
# Gotchas: a dimension left out is a `skipped` record with a reason, never a
# silent drop - the plan carries its own denominator. `orientation:
# "horizontal"` means the frame must lie in the Y plane (PortalDefinition
# .allowsAxis), so those dimensions refuse a VERTICAL frame, not a horizontal
# one. An igniter this file has never heard of is a hard error rather than an
# assumed-safe default.

import argparse
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
TEMPLATE_DIR = os.path.dirname(os.path.dirname(HERE))
PLATFORM_DIMS = os.path.join(TEMPLATE_DIR, "config", "custom-dimensions", "dimensions")
CONSUMER_DIR = os.environ.get("CONSUMER_DIR", os.path.expanduser("~/Projects/elfydd"))
CONSUMER_DIMS = os.path.join(
    CONSUMER_DIR, "data", "config", "custom-dimensions", "dimensions")
CONSUMER_SETTINGS = os.path.join(
    CONSUMER_DIR, "data", "config", "custom-dimensions", "settings.json")

# Durability, not stack size. An igniter absent from this table stops the plan:
# assuming "not damageable" would quietly assert the wrong igniter contract.
IGNITER_DAMAGEABLE = {
    "minecraft:flint_and_steel": True,
    "minecraft:diamond_sword": True,
    "minecraft:crossbow": True,
    "minecraft:amethyst_shard": False,
    "minecraft:ender_eye": False,
    "minecraft:gold_ingot": False,
    "minecraft:netherite_scrap": False,
    "minecraft:netherite_ingot": False,
    "minecraft:diamond": False,
    "minecraft:nautilus_shell": False,
    "minecraft:torch": False,
    "minecraft:water_bucket": False,
    "minecraft:blaze_rod": False,
    "minecraft:cherry_sapling": False,
    "minecraft:spruce_sapling": False,
    "minecraft:pink_petals": False,
    "regions_unexplored:lavender_wisteria_sapling": False,
}

# PortalDefinition.AuraSettings.getRadius / getBudget defaults and clamps.
AURA_RADIUS_DEFAULT = 8
AURA_RADIUS_MIN = 1
AURA_RADIUS_MAX = 32

# ServerWorldMixin's arrival reuse box, mirrored by ArrivalResolver.
REUSE_RADIUS_H = 5
REUSE_RADIUS_V = 16

BAY_GAP = 4          # untouched stone columns between two bays' shells
LANE_BLOCKS = 16     # walkable approach south of the frame plane
ALCOVE_BLOCKS = 6    # air north of the frame plane, so a projection has depth
HEADROOM = 3         # air rows above the top ring, the last one glowstone


# ---------------------------------------------------------------------------
# Geometry: what a frame of each shape looks like
# ---------------------------------------------------------------------------

# Interior width x height for a vertical frame (X in-plane, Y up), or width x
# depth for a horizontal one (X by Z). `None` means the shape is unbuildable on
# that axis.
VERTICAL_INTERIOR = {
    "standard": (2, 3),
    "doorway": (2, 3),
    "door": (1, 2),
    "end_gateway": (1, 1),
    "end_exit": None,      # PortalShape.matches forces axis Y
}
HORIZONTAL_INTERIOR = {
    "standard": (3, 3),
    "end_exit": (3, 3),
    "end_gateway": (1, 1),
    "doorway": None,       # isDoorway refuses axis Y
    "door": None,          # isDoor refuses axis Y
}


def implied_orientation(shape):
    """PortalShape.impliedOrientation - the shape's own default."""
    if shape in ("door", "doorway"):
        return "vertical"
    if shape == "end_exit":
        return "horizontal"
    return None


def effective_orientation(portal):
    """PortalDefinition.getOrientation - the explicit field always wins."""
    explicit = portal.get("orientation")
    if isinstance(explicit, str) and explicit.strip():
        return explicit.strip()
    implied = implied_orientation(normalise_shape(portal.get("shape")))
    return implied if implied else "any"


def normalise_shape(shape):
    """PortalShape.normalise - null/blank collapse to "standard"."""
    if isinstance(shape, dict):
        return "pattern"
    if not isinstance(shape, str) or not shape.strip():
        return "standard"
    return shape.strip()


def allows_axis(orientation, axis):
    """PortalDefinition.allowsAxis, verbatim. Unknown values behave as any."""
    if orientation == "vertical":
        return axis != "Y"
    if orientation == "horizontal":
        return axis == "Y"
    if orientation == "vertical_x":
        return axis == "X"
    if orientation == "vertical_z":
        return axis == "Z"
    return True


def frame_place_block(portal):
    """The one concrete id a frame can be BUILT from, or None with a reason.

    Accepting is not placing: `frameBlock` may be a tag, a list or a colour
    group, and only `framePlaceBlock` (or a plain id somewhere in it) can be
    handed to setblock.
    """
    explicit = portal.get("framePlaceBlock")
    if isinstance(explicit, str) and explicit.strip():
        return explicit.strip(), None
    raw = portal.get("frameBlock")
    if isinstance(raw, str) and raw.strip():
        if raw.startswith("#"):
            return None, "frameBlock is the tag %s and no framePlaceBlock is set" % raw
        return raw.strip(), None
    if isinstance(raw, list):
        for entry in raw:
            if isinstance(entry, str) and entry.strip() and not entry.startswith("#"):
                return entry.strip(), None
        return None, "frameBlock is a list with no plain id in it: %s" % json.dumps(raw)
    if isinstance(raw, dict) and isinstance(raw.get("colorGroup"), str):
        return "minecraft:%s_wool" % raw["colorGroup"].strip(), None
    return None, "no frameBlock this plan can place: %s" % json.dumps(raw)


def frame_accepts(portal):
    """Every form this frame accepts, from both places they can be written.

    A dimension may list its accept forms in `frameAccepts`, or make
    `frameBlock` itself a list. Reported, never asserted on - the harness
    builds with one concrete block and the mod decides what it accepts.
    """
    forms = []
    for key in ("frameBlock", "frameAccepts"):
        value = portal.get(key)
        if isinstance(value, list):
            for entry in value:
                if isinstance(entry, str) and entry not in forms:
                    forms.append(entry)
    return forms


def aura_radius(portal):
    aura = portal.get("aura")
    if not isinstance(aura, dict):
        return 0
    raw = aura.get("radius")
    if not isinstance(raw, int):
        return AURA_RADIUS_DEFAULT
    return max(AURA_RADIUS_MIN, min(AURA_RADIUS_MAX, raw))


def aura_is_hazardous(portal):
    """Does this aura place lava or set fires? Those bays cook a standing player."""
    aura = portal.get("aura")
    if not isinstance(aura, dict):
        return False
    fluids = aura.get("fluids")
    if isinstance(fluids, list) and fluids:
        return True
    fire = aura.get("fireChance")
    return isinstance(fire, (int, float)) and fire > 0


def java_int_div(total, count):
    """Java's `/` on ints: truncate TOWARDS ZERO.

    Python's `//` floors, so the two disagree on every negative average - and
    this room stands at negative coordinates. A centre column one block out
    picks a different arrival, so symmetric breaking and the proximity twin
    would both be measured against the wrong place.
    """
    quotient = abs(total) // count
    return -quotient if total < 0 else quotient


def centre_column(cells):
    """PortalBreakLink.centreColumn - integer-averaged, truncating."""
    if not cells:
        return None
    return [java_int_div(sum(c[0] for c in cells), len(cells)),
            java_int_div(sum(c[2] for c in cells), len(cells))]


def dest_column(centre, scale):
    """ServerWorldMixin's entry mapping: DIVIDE by scale, then round."""
    return [int(round(centre[0] / scale)), int(round(centre[1] / scale))]


def vertical_frame(x0, y0, z, width, height):
    """Interior and ring for a frame in the X/Y plane (mod axis "X")."""
    interior = [[x, y, z]
                for x in range(x0, x0 + width)
                for y in range(y0, y0 + height)]
    ring = [[x, y, z]
            for x in range(x0 - 1, x0 + width + 1)
            for y in range(y0 - 1, y0 + height + 1)
            if not (x0 <= x < x0 + width and y0 <= y < y0 + height)]
    return interior, ring


def horizontal_frame(x0, y, z0, width, depth):
    """Interior and ring for a frame lying in the X/Z plane (mod axis "Y")."""
    interior = [[x, y, z]
                for x in range(x0, x0 + width)
                for z in range(z0, z0 + depth)]
    ring = [[x, y, z]
            for x in range(x0 - 1, x0 + width + 1)
            for z in range(z0 - 1, z0 + depth + 1)
            if not (x0 <= x < x0 + width and z0 <= z < z0 + depth)]
    return interior, ring


# ---------------------------------------------------------------------------
# The igniter contract - the ONE place the expectation is decided
# ---------------------------------------------------------------------------

def resolve_igniter(item, mode, consumes_igniter=False):
    """What the igniter should look like after a successful ignition.

    `auto` mirrors `IgniterSpend.of(damageable, creative, consumesIgniter)`,
    including its precedence: a dimension that asks for the item gets the item,
    whether or not it could have taken damage instead. The other three modes
    pin every dimension to one behaviour, which is how a build that spends
    every igniter is asserted.

    Returns (expectation, predicate, verdict, assertable, reason). `verdict` is
    "present" or "absent" - what `execute if items` should answer.
    """
    if item not in IGNITER_DAMAGEABLE:
        raise KeyError(item)
    damageable = IGNITER_DAMAGEABLE[item]
    expectation = mode
    if mode == "auto":
        if consumes_igniter:
            expectation = "consumed"
        else:
            expectation = "damaged" if damageable else "untouched"
    if expectation == "consumed":
        return expectation, item, "absent", True, None
    if expectation == "damaged":
        if not damageable:
            return (expectation, None, None, False,
                    "%s has no durability, so it cannot take damage" % item)
        return expectation, "%s[minecraft:damage=1]" % item, "present", True, None
    if expectation == "untouched":
        if damageable:
            return expectation, "%s[minecraft:damage=0]" % item, "present", True, None
        return expectation, item, "present", True, None
    raise ValueError("unknown igniter expectation: %s" % mode)


# ---------------------------------------------------------------------------
# Reading the configs
# ---------------------------------------------------------------------------

# `<slug>_thumb.json` is a seed-viewer sidecar, not a dimension. There is one
# per dimension, they carry no portal, and counting them halves the meaning of
# every count this plan prints.
SIDECAR_SUFFIX = "_thumb.json"


def load_dimensions(directory):
    """Every real dimension config in a directory, sidecars left out.

    Returns (dimensions, sidecars-ignored) so the caller can print a
    denominator that means what it says.
    """
    out = {}
    sidecars = 0
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".json"):
            continue
        if name.endswith(SIDECAR_SUFFIX):
            sidecars += 1
            continue
        with open(os.path.join(directory, name)) as handle:
            out[name[:-5]] = json.load(handle)
    return out, sidecars


def portal_drift(primary, secondary):
    """Slugs whose portal block differs between two config directories.

    data/config/ is seeded skip-if-exists, so a committed portal change can sit
    on disk while the server runs the old one (TROUBLESHOOTING.md#t78). The
    plan measures the config the SERVER has and says loudly where it is stale.
    """
    drift = []
    for slug, config in sorted(primary.items()):
        other = secondary.get(slug)
        if other is None:
            drift.append(slug)
        elif config.get("portal") != other.get("portal"):
            drift.append(slug)
    return drift


# ---------------------------------------------------------------------------
# Bay construction
# ---------------------------------------------------------------------------

def bay_candidates(dimensions, namespace, horizontal_mode):
    """Every (slug, axis) pair worth building, and every reason one was left out."""
    wanted = []
    skipped = []
    for slug, config in sorted(dimensions.items()):
        portal = config.get("portal")
        if not isinstance(portal, dict):
            skipped.append({"slug": slug, "reason": "no portal block in the config"})
            continue
        if portal.get("vanillaManaged"):
            skipped.append({"slug": slug,
                            "reason": "vanillaManaged: vanilla owns its ignition"})
            continue
        shape = normalise_shape(portal.get("shape"))
        if shape == "pattern":
            skipped.append({"slug": slug,
                            "reason": "shape is an explicit pattern template; "
                                      "this plan builds preset shapes only"})
            continue
        if shape not in VERTICAL_INTERIOR:
            skipped.append({"slug": slug, "reason": "unknown shape %r" % shape})
            continue
        scale = portal.get("scale")
        if not isinstance(scale, (int, float)) or scale <= 0:
            skipped.append({"slug": slug, "reason": "no usable portal scale: %r" % scale})
            continue
        if slug == "overworld" and float(scale) == 1.0:
            skipped.append({"slug": slug,
                            "reason": "an overworld portal at scale 1 arrives in the "
                                      "same column of the same world: no crossing to "
                                      "measure"})
            continue

        orientation = effective_orientation(portal)
        axes = []
        if allows_axis(orientation, "X") and VERTICAL_INTERIOR[shape]:
            axes.append("X")
        if allows_axis(orientation, "Y") and HORIZONTAL_INTERIOR.get(shape):
            axes.append("Y")
        if not axes:
            skipped.append({"slug": slug,
                            "reason": "orientation %r and shape %r leave no axis this "
                                      "plan can build" % (orientation, shape)})
            continue
        if horizontal_mode == "none":
            axes = [a for a in axes if a != "Y"]
            if not axes:
                skipped.append({"slug": slug,
                                "reason": "--horizontal none, and orientation %r allows "
                                          "only the Y axis" % orientation})
                continue
        elif horizontal_mode == "forced":
            axes = ["Y"] if axes == ["Y"] else [a for a in axes if a != "Y"]
        for axis in axes:
            wanted.append((slug, config, portal, shape, orientation, axis))
    return wanted, skipped


def build_bay(slug, config, portal, shape, orientation, axis, cursor, opts, namespace):
    """One bay: its room box, its two frames, and what each step should expect."""
    horizontal = axis == "Y"
    dims = HORIZONTAL_INTERIOR[shape] if horizontal else VERTICAL_INTERIOR[shape]
    width, second = dims
    radius = aura_radius(portal)
    pad = radius + 1
    # Two clear columns between the rings, so IgnitionScan's 7x7x7 sweep around
    # one frame's ignition block cannot reach the other frame's opening. The
    # twin still has to land inside findRegisteredPortalNear's radius once
    # divided by scale, which `twin.linkable` is the check for.
    twin_offset = width + 4

    place_block, place_problem = frame_place_block(portal)
    if place_block is None:
        return None, {"slug": slug, "axis": axis, "reason": place_problem}
    igniter = portal.get("igniterItem")
    if not isinstance(igniter, str) or not igniter.strip():
        return None, {"slug": slug, "axis": axis,
                      "reason": "no igniterItem: nothing can light this frame"}
    igniter = igniter.strip()
    # One guard over the durability table, and every read of it inside: a
    # second, unguarded lookup elsewhere turns an unknown igniter into a crash
    # instead of a skip with a reason.
    consumes_igniter = bool(portal.get("consumesIgniter"))
    try:
        damageable = IGNITER_DAMAGEABLE[igniter]
        expectation, predicate, verdict, assertable, why = resolve_igniter(
            igniter, opts.igniter_expectation, consumes_igniter)
    except KeyError:
        return None, {"slug": slug, "axis": axis,
                      "reason": "igniter %s is not in this plan's durability table - "
                                "add it rather than assuming it does not wear" % igniter}

    floor_y = opts.floor_y
    plane_z = opts.plane_z
    box_x1 = cursor
    x0 = box_x1 + pad + 1
    twin_x0 = x0 + twin_offset
    box_x2 = twin_x0 + width + pad

    # The click never happens from inside the opening. Standing in it lights
    # the portal under the player's own feet and the ignition crossing fires
    # before anything has been measured; standing ON the ring puts the player
    # inside a frame block. Both frames are used from the lane, in reach.
    if horizontal:
        depth = second
        interior, ring = horizontal_frame(x0, floor_y, plane_z, width, depth)
        twin_interior, twin_ring = horizontal_frame(
            twin_x0, floor_y, plane_z, width, depth)
        top_y = floor_y
        south_ring_z = plane_z + depth
        use_primary = [x0, floor_y, south_ring_z, "up"]
        use_twin = [twin_x0, floor_y, south_ring_z, "up"]
        stand_primary = [x0 + 0.5, floor_y, south_ring_z + 1.5]
        stand_twin = [twin_x0 + 0.5, floor_y, south_ring_z + 1.5]
        enter_primary = [x0 + 0.5, floor_y, plane_z + 0.5]
        enter_twin = [twin_x0 + 0.5, floor_y, plane_z + 0.5]
        # East ring, clear of the twin's own ring and of the ignition block.
        break_cell = [x0 + width, floor_y, plane_z]
        look = [180.0, 60.0]
        view = [x0 + 0.5, floor_y, south_ring_z + 5.5]
        box_z1 = plane_z - 1 - ALCOVE_BLOCKS
        box_z2 = south_ring_z + LANE_BLOCKS
    else:
        height = second
        interior, ring = vertical_frame(x0, floor_y, plane_z, width, height)
        twin_interior, twin_ring = vertical_frame(
            twin_x0, floor_y, plane_z, width, height)
        top_y = floor_y + height - 1
        use_primary = [x0, floor_y - 1, plane_z, "up"]
        use_twin = [twin_x0, floor_y - 1, plane_z, "up"]
        stand_primary = [x0 + 0.5, floor_y, plane_z + 2.5]
        stand_twin = [twin_x0 + 0.5, floor_y, plane_z + 2.5]
        enter_primary = [x0 + 0.5, floor_y, plane_z + 0.5]
        enter_twin = [twin_x0 + 0.5, floor_y, plane_z + 0.5]
        # The west middle ring cell: away from the corners, away from the block
        # the ignition click lands on, and shared with no other frame.
        break_cell = [x0 - 1, floor_y, plane_z]
        look = [180.0, 0.0]
        view = [x0 + 0.5, floor_y, plane_z + 6.5]
        box_z1 = plane_z - ALCOVE_BLOCKS
        box_z2 = plane_z + LANE_BLOCKS

    box_y1 = floor_y - 1
    box_y2 = top_y + 1 + HEADROOM

    scale = float(portal.get("scale"))
    centre = centre_column(interior)
    twin_centre = centre_column(twin_interior)
    dest = dest_column(centre, scale)
    twin_dest = dest_column(twin_centre, scale)
    dest_delta = max(abs(twin_dest[0] - dest[0]), abs(twin_dest[1] - dest[1]))

    anchor = portal.get("anchor") is not None
    borders = config.get("borders") or {}
    dest_border = borders.get("player")
    border_ok = True
    border_note = None
    # An anchor dimension resolves its arrival at the fixed anchor column and
    # never divides by scale, so a source outside border*scale is fine there
    # and only there.
    border_applies = not anchor and isinstance(dest_border, (int, float))
    if border_applies:
        reach = max(abs(dest[0]), abs(dest[1]))
        border_ok = reach <= dest_border
        if not border_ok:
            border_note = ("the arrival column %s is outside %s's player border %s"
                           % (dest, slug, dest_border))
    elif anchor:
        border_note = "anchor arrival: the scaled column is never used"
    source_reach = max(abs(centre[0]), abs(centre[1]),
                       abs(twin_centre[0]), abs(twin_centre[1]))
    if source_reach > opts.border:
        border_ok = False
        border_note = ("the bay sits at %s, outside the overworld border %s"
                       % (source_reach, opts.border))

    single_use = portal.get("singleUse") is not None

    bay = {
        "slug": slug,
        "id": "%s_%s" % (slug, "y" if horizontal else "x"),
        "dimension": "%s:%s" % (namespace, slug),
        "axis": axis,
        "horizontal": horizontal,
        "shape": shape,
        "orientation": orientation,
        "scale": scale,
        "frameBlock": place_block,
        "frameAccepts": frame_accepts(portal),
        "igniter": igniter,
        "igniterDamageable": damageable,
        "consumesIgniter": consumes_igniter,
        "igniterExpectation": expectation,
        "igniterPredicate": predicate,
        "igniterVerdict": verdict,
        "igniterAssertable": assertable,
        "igniterNote": why,
        "auraRadius": radius,
        "auraSubsume": ((portal.get("aura") or {}).get("subsume", "natural")
                        if isinstance(portal.get("aura"), dict) else None),
        "hazardousAura": aura_is_hazardous(portal),
        "anchor": anchor,
        "singleUse": single_use,
        # PortalHelper.breakLinkedArrival returns 0 for an anchor definition:
        # one arrival is shared by every source into that dimension.
        "breaksSymmetrically": not anchor,
        # PORTAL_TARGETS entries are removed on a symmetric break, so
        # findRegisteredPortalNear has nothing to reuse afterwards.
        "relightExpectation": "linked-to-old" if anchor else "new-arrival",
        "destBorder": dest_border,
        "borderApplies": border_applies,
        "borderOk": border_ok,
        "borderNote": border_note,
        "room": {
            "box": [box_x1, box_y1, box_z1, box_x2, box_y2, box_z2],
            "floorY": floor_y,
            "ceilingY": box_y2 - 1,
            "planeZ": plane_z,
            "laneZ": box_z2 - 1,
            "chunkFrom": [box_x1, box_z1],
            "chunkTo": [box_x2, box_z2],
            "look": look,
            "view": view,
        },
        "primary": {
            "interior": interior,
            "ring": ring,
            "centreColumn": centre,
            "destColumn": dest,
            "use": use_primary,
            "stand": stand_primary,
            "enter": enter_primary,
            "breakCell": break_cell,
        },
        "twin": {
            "interior": twin_interior,
            "ring": twin_ring,
            "centreColumn": twin_centre,
            "destColumn": twin_dest,
            "use": use_twin,
            "stand": stand_twin,
            "enter": enter_twin,
            "offset": twin_offset,
            "destDelta": dest_delta,
            # findRegisteredPortalNear's horizontal radius, in DESTINATION
            # blocks. Beyond it the twin builds its own arrival and the
            # proximity assertion has nothing to prove.
            "linkable": dest_delta <= REUSE_RADIUS_H,
            "linkNote": (None if dest_delta <= REUSE_RADIUS_H else
                         "the twin's arrival column is %s blocks from the primary's, "
                         "past findRegisteredPortalNear's radius of %s"
                         % (dest_delta, REUSE_RADIUS_H)),
        },
    }
    return bay, None


def lay_out(wanted, opts, namespace):
    bays = []
    skipped = []
    cursor = opts.origin_x
    for slug, config, portal, shape, orientation, axis in wanted:
        bay, problem = build_bay(slug, config, portal, shape, orientation, axis,
                                 cursor, opts, namespace)
        if bay is None:
            skipped.append(problem)
            continue
        bays.append(bay)
        cursor = bay["room"]["box"][3] + BAY_GAP + 1
    return bays, skipped


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def parse_args(argv):
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--dimensions")
    parser.add_argument("--compare-dimensions")
    parser.add_argument("--settings")
    parser.add_argument("--origin-x", type=int, default=-6800)
    parser.add_argument("--plane-z", type=int, default=-6000)
    parser.add_argument("--floor-y", type=int, default=40)
    parser.add_argument("--horizontal", default="forced",
                        choices=["forced", "all", "none"])
    parser.add_argument("--igniter-expectation", default="auto",
                        choices=["auto", "untouched", "damaged", "consumed"])
    parser.add_argument("--only", default="")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--from", dest="from_slug", default="")
    parser.add_argument("--border", type=int, default=8192)
    return parser.parse_args(argv)


def namespace_of(settings_path):
    try:
        with open(settings_path) as handle:
            return json.load(handle).get("namespace", "adventure")
    except (OSError, ValueError):
        return "adventure"


def main(argv):
    opts = parse_args(argv)
    dims_dir = opts.dimensions or (
        CONSUMER_DIMS if os.path.isdir(CONSUMER_DIMS) else PLATFORM_DIMS)
    if not os.path.isdir(dims_dir):
        sys.stderr.write("no dimension configs at %s\n" % dims_dir)
        return 2
    settings = opts.settings or (
        CONSUMER_SETTINGS if os.path.isfile(CONSUMER_SETTINGS) else
        os.path.join(TEMPLATE_DIR, "config", "custom-dimensions", "settings.json"))
    namespace = namespace_of(settings)

    dimensions, sidecars = load_dimensions(dims_dir)
    compare_dir = opts.compare_dimensions
    if compare_dir is None and os.path.abspath(dims_dir) != os.path.abspath(PLATFORM_DIMS):
        compare_dir = PLATFORM_DIMS
    drift = []
    if compare_dir and os.path.isdir(compare_dir):
        drift = portal_drift(dimensions, load_dimensions(compare_dir)[0])

    wanted, skipped = bay_candidates(dimensions, namespace, opts.horizontal)

    only = [s for s in opts.only.split(",") if s]
    if only:
        kept = []
        for entry in wanted:
            if entry[0] in only:
                kept.append(entry)
            else:
                skipped.append({"slug": entry[0], "axis": entry[5],
                                "reason": "not named by --only"})
        wanted = kept
    if opts.from_slug:
        slugs = [entry[0] for entry in wanted]
        if opts.from_slug in slugs:
            start = slugs.index(opts.from_slug)
            for entry in wanted[:start]:
                skipped.append({"slug": entry[0], "axis": entry[5],
                                "reason": "before --from %s" % opts.from_slug})
            wanted = wanted[start:]

    bays, layout_skipped = lay_out(wanted, opts, namespace)
    skipped.extend(layout_skipped)

    if opts.limit > 0 and len(bays) > opts.limit:
        for bay in bays[opts.limit:]:
            skipped.append({"slug": bay["slug"], "axis": bay["axis"],
                            "reason": "past --limit %d" % opts.limit})
        bays = bays[:opts.limit]

    plan = {
        "kind": "portal-matrix-plan",
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source": {
            "dimensionsDir": dims_dir,
            "compareDir": compare_dir,
            "settings": settings,
            "namespace": namespace,
            "dimensionsRead": len(dimensions),
            "sidecarsIgnored": sidecars,
            "portalDrift": drift,
        },
        "options": {
            "originX": opts.origin_x,
            "planeZ": opts.plane_z,
            "floorY": opts.floor_y,
            "horizontal": opts.horizontal,
            "igniterExpectation": opts.igniter_expectation,
            "overworldBorder": opts.border,
            "bayGap": BAY_GAP,
            "laneBlocks": LANE_BLOCKS,
            "reuseRadiusH": REUSE_RADIUS_H,
            "reuseRadiusV": REUSE_RADIUS_V,
        },
        "counts": {
            "bays": len(bays),
            "skipped": len(skipped),
            "horizontalBays": len([b for b in bays if b["horizontal"]]),
            "anchorBays": len([b for b in bays if b["anchor"]]),
            "unlinkableTwins": len([b for b in bays if not b["twin"]["linkable"]]),
            "borderProblems": len([b for b in bays if not b["borderOk"]]),
        },
        "bays": bays,
        "skipped": skipped,
    }
    json.dump(plan, sys.stdout, indent=1, sort_keys=False)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
