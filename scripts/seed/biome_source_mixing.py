"""biome_source_mixing.py — Port of the Java mod's biome-list mixing.

Pure-Python reimplementation of DimensionManager.buildMixedSource from the
custom-dimensions mod.  Given the full biome parameter table and a dimension's
biome list, produces the filtered/remapped entry list that BiomeSampler
should use for nearest-neighbour lookup.

Algorithm (mirrors the Java exactly):
  1. Parse biome_list into an ordered set of allowed IDs.
  2. Walk every entry in the base table (optionally pre-filtered by family):
     - entry biome IN the allowed set  -> "native" (kept with original params)
     - entry biome NOT in allowed set  -> its climate region goes into a "pool"
  3. Identify "foreign" biomes: allowed IDs with zero native entries.
  4. If foreign biomes exist: each pool region is assigned to
     foreign[i % len(foreign)] round-robin — foreign biomes claim real territory.
  5. If NO foreign biomes: pool regions are dropped entirely.  Sampling resolves
     to the nearest kept (native) entry.
  6. Return the resulting entry list.

Known limitation: TerraBlender post-layers add extra regions at runtime
that we deliberately don't model.
"""


def _override_entry(biome_id, params):
    """One explicit entry from a raw "parameters" object (Tier 3) —
    mirrors DimensionManager.hypercubeFrom: absent axis -> full [-2, 2]
    span, number -> point, [min, max] -> interval; offset 0..1 (default 0).
    Returns None on any invalid axis (the biome falls back to plain-listed
    behaviour, matching the Java warn-and-ignore)."""
    entry = {"biome": biome_id}
    for axis in ("temperature", "humidity", "continentalness",
                 "erosion", "depth", "weirdness"):
        v = params.get(axis)
        if v is None:
            entry[axis] = [-2.0, 2.0]
        elif isinstance(v, (int, float)) and not isinstance(v, bool):
            if not -2.0 <= v <= 2.0:
                return None
            entry[axis] = [float(v), float(v)]
        elif (isinstance(v, list) and len(v) == 2
              and all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in v)
              and -2.0 <= v[0] <= v[1] <= 2.0):
            entry[axis] = [float(v[0]), float(v[1])]
        else:
            return None
    off = params.get("offset", 0.0)
    entry["offset"] = float(off) if isinstance(off, (int, float)) \
        and not isinstance(off, bool) and 0.0 <= off <= 1.0 else 0.0
    return entry


def build_mixed_entries(biome_table, biome_list, family_filter=None,
                        param_overrides=None):
    """Port of DimensionManager.buildMixedSource — filter and remap biome
    parameter entries to match the mod's exact mixing semantics.

    Args:
        biome_table: full biome parameter table (list of dicts from
                     biome_params.json).  Each dict has keys: biome,
                     temperature, humidity, continentalness, erosion, depth,
                     weirdness, offset, family.
        biome_list:  list of biome ID strings (the dimension's biome list).
        family_filter: optional family string for pre-filtering the base
                       source (e.g. "overworld").  Entries with a non-matching
                       family are excluded from BOTH native and pool, matching
                       the mod's behaviour of starting from a family-specific
                       source.
        param_overrides: optional {biome_id: raw "parameters" dict} from
                       object-form biomes entries (Tier 3).  Overridden
                       biomes get ONE explicit entry with those intervals
                       and are withdrawn from native/foreign entirely.

    Returns:
        list of biome parameter entry dicts (same format as biome_table)
        with biome IDs remapped per the mixing algorithm.

    Known limitation: TerraBlender post-layers add extra regions at runtime
    that we deliberately don't model.
    """
    # 1. Ordered set of allowed biome IDs (preserves insertion order for
    #    deterministic round-robin assignment of foreign biomes — matches
    #    the Java LinkedHashSet).
    allowed = dict.fromkeys(biome_list)  # ordered, unique

    # 0. Explicit per-biome parameters (Tier 3): a listed biome with a valid
    #    parameters object gets ONE explicit entry and is withdrawn from the
    #    native/round-robin machinery entirely — its natural regions join the
    #    pool. Mirrors DimensionManager.buildMixedSource; keep in sync.
    explicit = {}
    for biome_id, params in (param_overrides or {}).items():
        if biome_id not in allowed or not isinstance(params, dict):
            continue
        entry = _override_entry(biome_id, params)
        if entry is not None:
            explicit[biome_id] = entry

    # 2. Walk the base table and partition into native entries + pool regions.
    #    When family_filter is set, only entries matching that family form the
    #    base source (both native and pool).  When None, all entries qualify.
    native_entries = []
    pool = []          # climate parameter dicts (everything except "biome")
    native_ids = set()

    for entry in biome_table:
        if family_filter is not None:
            entry_family = entry.get("family")
            if entry_family and entry_family != family_filter:
                continue

        biome_id = entry["biome"]
        if biome_id in allowed and biome_id not in explicit:
            native_entries.append(entry)
            native_ids.add(biome_id)
        else:
            pool.append(entry)

    # 3. Foreign biomes: allowed IDs that have no native entries.
    #    Preserve the original list order (round-robin assignment depends on it).
    #    Explicit-parameter biomes are never foreign — they already have a home.
    foreign = [bid for bid in allowed if bid not in native_ids and bid not in explicit]

    # 4/5. Build the result list: explicit entries first (matches the Java
    #      result order), then native, then pool-assigned foreign.
    result = list(explicit.values()) + list(native_entries)

    if foreign:
        # Each pool region is assigned to foreign[i % len(foreign)].
        n_foreign = len(foreign)
        for i, pool_entry in enumerate(pool):
            remapped = dict(pool_entry)
            remapped["biome"] = foreign[i % n_foreign]
            result.append(remapped)

    # When there are no foreign biomes, pool regions are simply dropped —
    # the result contains only native entries.

    return result
