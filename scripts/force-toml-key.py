#!/usr/bin/env python3
"""force-toml-key.py - Force one key in a TOML file to a value, creating the
file and its section if absent.

Context: mods rewrite their own config on every boot, so a value that must
hold has to be re-applied rather than set once. c2me strips
useDensityFunctionCompiler when it rewrites c2me.toml, and that key
generating every custom dimension as a clone of the main world is D6.
Called by deploy.sh (step 8c), dev-up.sh (up and refresh-config).

Usage:
  force-toml-key.py <file> <section> <key> <value>

  file      the TOML file; created with the section if it does not exist
  section   the section header, in brackets, e.g. "[vanillaWorldGenOptimizations]"
  key       the bare key name
  value     written verbatim — quote it yourself if the key wants a string

Exits 0 whether or not anything changed, and prints only when it did.

Gotchas: deliberately not a TOML parser. The files are written by mods in
whatever style they like and a round-trip through a parser would reformat
the whole file and lose their comments. Matching is textual, so a key name
that appears inside a comment or a string is matched too.
"""
import os
import re
import sys


def force(path, section, key, value):
    """Returns a message if the file changed, else None."""
    if os.path.exists(path):
        with open(path) as handle:
            before = handle.read()
        if re.search(r"^\s*%s\s*=" % re.escape(key), before, re.M):
            after = re.sub(
                r"^(\s*)%s\s*=\s*\S+" % re.escape(key),
                r"\g<1>%s = %s" % (key, value),
                before,
                flags=re.M,
            )
        elif section in before:
            after = before.replace(section, "%s\n\t%s = %s" % (section, key, value), 1)
        else:
            after = before + "\n%s\n\t%s = %s\n" % (section, key, value)
        if after == before:
            return None
        with open(path, "w") as handle:
            handle.write(after)
        return "  %s: %s = %s enforced" % (os.path.basename(path), key, value)

    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w") as handle:
        handle.write("%s\n\t%s = %s\n" % (section, key, value))
    return "  %s: created with %s = %s" % (os.path.basename(path), key, value)


def main(argv):
    if len(argv) != 5:
        print("usage: force-toml-key.py <file> <section> <key> <value>", file=sys.stderr)
        return 2
    message = force(argv[1], argv[2], argv[3], argv[4])
    if message:
        print(message)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
