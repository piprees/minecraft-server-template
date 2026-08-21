#!/usr/bin/env python3
"""Parse the JSON Minecraft actually accepts, not the JSON the spec defines.

Purpose:  Mods ship worldgen JSON through GSON in lenient mode, so `//` and
          `/* */` comments and trailing commas are all legal to the game.
          Python's json rejects them, and an extractor that catches
          JSONDecodeError and moves on drops that content silently — measured:
          YUNG's Cave Biomes ships both its biomes with a `// RAW_GENERATION`
          comment, and every catalogue in this repo was missing them.

Usage:    import mcjson
          data = mcjson.loads(raw_bytes_or_str)      # raises on real syntax errors

Gotchas:  - Comment stripping is string-aware; a `//` inside a JSON string is
            left alone, which a naive regex would destroy.
          - It raises for genuinely malformed input. A caller that swallows
            that is back to the silent-skip bug this module exists to fix —
            count and report instead.
"""

import json

__all__ = ["loads", "strip_lenient"]


def strip_lenient(text):
    """The text with GSON-legal comments and trailing commas removed."""
    out = []
    i = 0
    n = len(text)
    in_string = False
    while i < n:
        ch = text[i]
        if in_string:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if ch == '"':
                in_string = False
            i += 1
            continue
        if ch == '"':
            in_string = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < n:
            nxt = text[i + 1]
            if nxt == "/":
                i = text.find("\n", i)
                if i == -1:
                    break
                continue
            if nxt == "*":
                end = text.find("*/", i + 2)
                i = n if end == -1 else end + 2
                continue
        out.append(ch)
        i += 1

    # Trailing commas, once the comments that could hide them are gone.
    stripped = "".join(out)
    result = []
    i = 0
    n = len(stripped)
    in_string = False
    while i < n:
        ch = stripped[i]
        if in_string:
            result.append(ch)
            if ch == "\\" and i + 1 < n:
                result.append(stripped[i + 1])
                i += 2
                continue
            if ch == '"':
                in_string = False
            i += 1
            continue
        if ch == '"':
            in_string = True
        elif ch == ",":
            j = i + 1
            while j < n and stripped[j] in " \t\r\n":
                j += 1
            if j < n and stripped[j] in "}]":
                i += 1
                continue
        result.append(ch)
        i += 1
    return "".join(result)


def loads(raw):
    """Parse bytes or str, tolerating what the game tolerates."""
    if isinstance(raw, (bytes, bytearray)):
        raw = raw.decode("utf-8-sig", "replace")
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return json.loads(strip_lenient(raw))
