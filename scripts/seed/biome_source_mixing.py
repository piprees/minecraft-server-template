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


def build_mixed_entries(biome_table, biome_list, family_filter=None):
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
        if biome_id in allowed:
            native_entries.append(entry)
            native_ids.add(biome_id)
        else:
            pool.append(entry)

    # 3. Foreign biomes: allowed IDs that have no native entries.
    #    Preserve the original list order (round-robin assignment depends on it).
    foreign = [bid for bid in allowed if bid not in native_ids]

    # 4/5. Build the result list.
    result = list(native_entries)

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
