#!/usr/bin/env python3
# server-pack.py - Stage the portal tint resource pack for pack-web and print the server's pack properties.
#
# Purpose: the 26.x custom-dimensions jar carries the server resource pack that
#   tints portals for unmodded clients (customdimensions/server-pack.zip). This
#   copies it out of the jar in data/mods into <dist>/server-pack/portals.zip,
#   where pack-web serves it, and prints the four values itzg writes into
#   server.properties, one "NAME<TAB>value" line each:
#     RESOURCE_PACK         <base-url>/server-pack/portals.zip?sha1=<sha1>
#     RESOURCE_PACK_SHA1    the zip's SHA-1
#     RESOURCE_PACK_ID      a fixed UUID, so a client replaces its cached copy
#     RESOURCE_PACK_PROMPT  config/messages.json's server_pack.prompt, as a JSON text component
#   With no jar holding a pack (the 1.21.1 stack) every value is empty, which
#   itzg writes as a cleared property, and any staged zip is removed.
#
# Context: bundle script. deploy.sh and dev-up.sh export the values before mc
#   starts; docker-compose.yml passes them to itzg. The mod arms the tints only
#   when the offered pack's SHA-1 is its own pack's, so a stale value shows
#   vanilla portals, never a wrong model.
#
# Usage:
#   server-pack.py <project-dir> <base-url> [--dist DIR] [--messages FILE]
#     project-dir  the consumer/server root (holds data/mods and overlay/)
#     base-url     where pack-web is reached, e.g. https://pack.example.com
#     --dist       pack-web's document root (default <project-dir>/modpack-dist)
#     --messages   messages file (default overlay/config/messages.json, else
#                  the stack's config/messages.json)
#   server-pack.py --self-test
#
# Gotchas:
#   - The query string busts Cloudflare's cache when the pack changes; the path
#     stays stable. nginx ignores it.
#   - The prompt must be a JSON text component (26.3 parses it strictly); a
#     plain string is quoted here.
#   - Never write RESOURCE_PACK_ENFORCE here: compose fixes it at FALSE, because
#     Geyser answers a required pack as loaded without loading it.

import hashlib
import json
import os
import shutil
import sys
import tempfile
import uuid
import zipfile

ENTRY = "customdimensions/server-pack.zip"
PACK_ID = str(uuid.uuid3(uuid.NAMESPACE_URL, "customdimensions:portal-tints"))
PROMPT_KEY = "server_pack.prompt"
NAMES = ("RESOURCE_PACK", "RESOURCE_PACK_SHA1", "RESOURCE_PACK_ID", "RESOURCE_PACK_PROMPT")
HERE = os.path.dirname(os.path.abspath(__file__))


def find_pack(mods_dir):
    """The pack bytes from the first jar (by name) in mods_dir that holds one, else None."""
    if not os.path.isdir(mods_dir):
        return None
    for name in sorted(os.listdir(mods_dir)):
        if not name.endswith(".jar"):
            continue
        try:
            with zipfile.ZipFile(os.path.join(mods_dir, name)) as jar:
                if ENTRY in jar.namelist():
                    return jar.read(ENTRY)
        except (zipfile.BadZipFile, OSError):
            continue
    return None


def prompt(messages_file):
    try:
        with open(messages_file, encoding="utf-8") as f:
            text = json.load(f).get(PROMPT_KEY)
    except (OSError, ValueError):
        text = None
    return json.dumps(text, ensure_ascii=False) if text else ""


def messages_path(project_dir):
    overlay = os.path.join(project_dir, "overlay", "config", "messages.json")
    if os.path.isfile(overlay):
        return overlay
    return os.path.join(HERE, "..", "config", "messages.json")


def stage(project_dir, base_url, dist_dir, messages_file):
    """Writes or removes the staged zip and returns the four values in NAMES order."""
    target_dir = os.path.join(dist_dir, "server-pack")
    target = os.path.join(target_dir, "portals.zip")
    pack = find_pack(os.path.join(project_dir, "data", "mods"))
    if pack is None:
        if os.path.exists(target):
            os.remove(target)
        return ["", "", "", ""]
    os.makedirs(target_dir, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=target_dir, prefix=".portals-")
    with os.fdopen(fd, "wb") as f:
        f.write(pack)
    os.chmod(tmp, 0o644)
    os.replace(tmp, target)
    sha1 = hashlib.sha1(pack).hexdigest()
    url = base_url.rstrip("/") + "/server-pack/portals.zip?sha1=" + sha1
    return [url, sha1, PACK_ID, prompt(messages_file)]


def self_test():
    failures = 0

    def check(name, ok, saw=None):
        nonlocal failures
        print(("PASS " if ok else "FAIL ") + name + ("" if ok else "  saw: %r" % (saw,)))
        failures += 0 if ok else 1

    root = tempfile.mkdtemp()
    try:
        mods = os.path.join(root, "data", "mods")
        os.makedirs(mods)
        messages = os.path.join(root, "messages.json")
        with open(messages, "w", encoding="utf-8") as f:
            json.dump({PROMPT_KEY: "Colour the portals? \"Yes\" or no"}, f)
        dist = os.path.join(root, "dist")
        out = stage(root, "https://pack.example.com/", dist, messages)
        check("no jar: every value empty", out == ["", "", "", ""], out)
        with zipfile.ZipFile(os.path.join(mods, "a-other.jar"), "w") as z:
            z.writestr("fabric.mod.json", "{}")
        with open(os.path.join(mods, "broken.jar"), "wb") as f:
            f.write(b"not a zip")
        with zipfile.ZipFile(os.path.join(mods, "customdimensions-0.1.0.jar"), "w") as z:
            z.writestr(ENTRY, b"PACKBYTES")
        out = stage(root, "https://pack.example.com/", dist, messages)
        sha1 = hashlib.sha1(b"PACKBYTES").hexdigest()
        check("the url is stable with the hash as a query",
              out[0] == "https://pack.example.com/server-pack/portals.zip?sha1=" + sha1, out[0])
        check("the sha1 is the zip's", out[1] == sha1, out[1])
        check("the id is fixed", out[2] == PACK_ID and uuid.UUID(out[2]).version == 3, out[2])
        check("the prompt is a JSON string component",
              json.loads(out[3]) == "Colour the portals? \"Yes\" or no", out[3])
        with open(os.path.join(dist, "server-pack", "portals.zip"), "rb") as f:
            check("the zip is staged byte for byte", f.read() == b"PACKBYTES")
        os.remove(os.path.join(mods, "customdimensions-0.1.0.jar"))
        out = stage(root, "https://pack.example.com", dist, messages)
        check("a jar gone takes the staged zip with it",
              out == ["", "", "", ""] and not os.path.exists(os.path.join(dist, "server-pack", "portals.zip")), out)
        check("a missing messages file gives no prompt", prompt(os.path.join(root, "none.json")) == "")
        check("the shipped messages carry the prompt",
              prompt(os.path.join(HERE, "..", "config", "messages.json")) != "")
    finally:
        shutil.rmtree(root)
    return 1 if failures else 0


def main(argv):
    if argv[1:] == ["--self-test"]:
        return self_test()
    args = argv[1:]
    dist = messages = None
    if "--dist" in args:
        i = args.index("--dist")
        dist = args[i + 1]
        del args[i:i + 2]
    if "--messages" in args:
        i = args.index("--messages")
        messages = args[i + 1]
        del args[i:i + 2]
    if len(args) != 2:
        print("usage: server-pack.py <project-dir> <base-url> [--dist DIR] [--messages FILE]", file=sys.stderr)
        return 2
    project_dir, base_url = args
    values = stage(project_dir, base_url, dist or os.path.join(project_dir, "modpack-dist"),
                   messages or messages_path(project_dir))
    for name, value in zip(NAMES, values):
        print(name + "\t" + value)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
