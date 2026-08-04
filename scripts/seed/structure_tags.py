"""structure_tags.py -- exact structure tag resolution from extracted jar data.

Resolves #ns:tag references to their exact member lists by reading the
merged tag JSON files extracted from mod/server jars during warmup.
Multiple datapacks contributing to the same tag are merged (Minecraft's
default behaviour when replace=false). Nested #tag references are
expanded recursively with cycle detection.

The extraction writes files to <seedtest>/.structure_tags/<ns>/<tagname>.json,
mirroring the .structure_sets/ extraction layout.

Usage:
    from structure_tags import resolve_tag
    members = resolve_tag(seedtest_path, "#minecraft:village")
    # -> sorted list of exact structure ids, or None if tag data unavailable
"""

import json
from pathlib import Path


def _load_all_tags(tags_dir):
    """Load every extracted tag file into {ns:tagname: [raw values entries]}.

    Multiple datapacks may contribute to the same ns:tagname. The extraction
    writes each contributor as a separate file; this function merges them
    following Minecraft's semantics: replace=true discards prior entries,
    replace=false (the default) appends. Files for the same tag are read in
    sorted order for determinism.
    """
    tags_dir = Path(tags_dir)
    if not tags_dir.is_dir():
        return {}

    # Collect all files per tag id, sorted for deterministic merge order.
    files_by_tag = {}
    for json_path in sorted(tags_dir.rglob("*.json")):
        # Path: <tags_dir>/<ns>/<tagname>.json (possibly nested subdirs
        # for tags like explorer_maps/bandit_towers).
        rel = json_path.relative_to(tags_dir)
        parts = rel.parts
        if len(parts) < 2:
            continue
        ns = parts[0]
        tag_name = "/".join(parts[1:])
        if tag_name.endswith(".json"):
            tag_name = tag_name[:-5]
        tag_id = "{}:{}".format(ns, tag_name)
        files_by_tag.setdefault(tag_id, []).append(json_path)

    # Group files by their canonical tag id: the extraction appends __<jar>
    # suffixes when multiple jars contribute to the same tag, so
    # "village.json" and "village__modded.json" both belong to tag
    # "ns:village". Strip the double-underscore suffix to recover the
    # canonical id.
    canonical = {}
    for tag_id, paths in files_by_tag.items():
        # "ns:village__modded" -> "ns:village"
        ns_sep = tag_id.find(":")
        if ns_sep >= 0:
            ns = tag_id[:ns_sep]
            name_part = tag_id[ns_sep + 1:]
        else:
            ns, name_part = "", tag_id
        base_name = name_part.split("__")[0]
        canon_id = "{}:{}".format(ns, base_name) if ns else base_name
        canonical.setdefault(canon_id, []).extend(paths)

    merged = {}
    for tag_id, paths in canonical.items():
        values = []
        for path in sorted(paths):
            try:
                data = json.loads(path.read_text())
            except (json.JSONDecodeError, OSError):
                continue
            if data.get("replace", False):
                values = []
            values.extend(data.get("values") or [])
        merged[tag_id] = values
    return merged


def _expand_tag(tag_id, all_tags, visited):
    """Recursively expand one tag to its member structure ids.

    Handles nested #tag references with cycle detection. Entries with
    {"id": ..., "required": false} are included as members (required=false
    means the entry is optional for loading, not that it is excluded from
    the tag's membership).

    Returns a set of structure ids, or None if the tag does not exist.
    """
    if tag_id in visited:
        return set()  # cycle detected -- terminate
    if tag_id not in all_tags:
        return None

    visited.add(tag_id)
    members = set()
    for entry in all_tags[tag_id]:
        if isinstance(entry, str):
            if entry.startswith("#"):
                nested_id = entry[1:]
                nested = _expand_tag(nested_id, all_tags, visited)
                if nested is not None:
                    members.update(nested)
            else:
                members.add(entry)
        elif isinstance(entry, dict):
            eid = entry.get("id", "")
            if eid.startswith("#"):
                nested_id = eid[1:]
                nested = _expand_tag(nested_id, all_tags, visited)
                if nested is not None:
                    members.update(nested)
            elif eid:
                members.add(eid)
    return members


# Module-level cache: (tags_dir_path -> merged tag dict).
_TAG_CACHE = {}


def _tags_for(seedtest_path):
    """Cached load of all tag data for a seedtest directory."""
    tags_dir = str(Path(seedtest_path) / ".structure_tags")
    if tags_dir not in _TAG_CACHE:
        _TAG_CACHE[tags_dir] = _load_all_tags(tags_dir)
    return _TAG_CACHE[tags_dir], tags_dir


def resolve_tag(seedtest_path, tag_ref):
    """Resolve a #ns:tag reference to its exact sorted member list.

    Returns a sorted list of structure ids, or None when the tag data
    is unavailable (the caller records "not exactly measurable", never
    falls back to substring). An empty list [] is distinct from None:
    it means the tag exists but has no members.

    tag_ref must start with '#', e.g. "#minecraft:village".
    """
    if not tag_ref or not tag_ref.startswith("#"):
        return None  # not a tag reference
    if seedtest_path is None:
        return None  # no seedtest path available

    tag_id = tag_ref[1:]
    all_tags, tags_dir = _tags_for(seedtest_path)
    if not all_tags:
        return None  # no tag data extracted

    members = _expand_tag(tag_id, all_tags, set())
    if members is None:
        return None
    return sorted(members)


def clear_cache():
    """Clear the module-level tag cache (for testing)."""
    _TAG_CACHE.clear()
