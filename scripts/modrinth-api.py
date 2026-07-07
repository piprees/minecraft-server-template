#!/usr/bin/env python3
"""Modrinth API helper - bulk mod resolution with connection reuse.

Uses a single HTTPS connection to api.modrinth.com, respects rate limit
headers, and retries on 429. Called by check-updates.sh and pin-mod-versions.sh.

Modes:
  pin   <mc_version> <fallback_versions>   Read slugs from stdin, resolve each
                                           to its latest Fabric version ID.
  check <mc_version> [--fast]              Read "slug\\tpinned_id" from stdin,
                                           return update status for each mod.
                                           --fast skips the "newest version"
                                           lookup (halves API calls).

Output: one JSON object per line (order matches input).
"""

import http.client
import json
import os
import ssl
import sys
import time
import urllib.parse


class ModrinthAPI:
    def __init__(self):
        ctx = ssl.create_default_context()
        self.conn = http.client.HTTPSConnection(
            "api.modrinth.com", timeout=15, context=ctx
        )
        self.headers = {
            "User-Agent": f"{os.environ.get('BRAND_SLUG', 'adventure')}/modrinth-api",
            "Accept": "application/json",
        }
        self.remaining = 300
        self.retries = 0

    def get(self, path):
        if self.remaining <= 5:
            time.sleep(2)

        try:
            self.conn.request("GET", path, headers=self.headers)
            resp = self.conn.getresponse()
            body = resp.read().decode()

            self.remaining = int(resp.getheader("X-Ratelimit-Remaining", "300"))

            if resp.status == 200:
                self.retries = 0
                return json.loads(body)

            if resp.status == 429:
                reset = int(resp.getheader("X-Ratelimit-Reset", "5"))
                self.retries += 1
                if self.retries > 3:
                    print(f"  [api] Giving up after 3 rate-limit retries", file=sys.stderr)
                    return None
                print(f"  [api] Rate limited, waiting {reset + 1}s...", file=sys.stderr)
                time.sleep(reset + 1)
                return self.get(path)

            return None

        except (http.client.HTTPException, ConnectionError, OSError):
            self._reconnect()
            return None

    def _reconnect(self):
        try:
            self.conn.close()
        except Exception:
            pass
        ctx = ssl.create_default_context()
        self.conn = http.client.HTTPSConnection(
            "api.modrinth.com", timeout=15, context=ctx
        )

    def close(self):
        try:
            self.conn.close()
        except Exception:
            pass

    def get_versions(self, slug, game_version, loader="fabric"):
        encoded_gv = urllib.parse.quote(f'["{game_version}"]')
        encoded_loader = urllib.parse.quote(f'["{loader}"]')
        return self.get(
            f"/v2/project/{slug}/version"
            f"?game_versions={encoded_gv}&loaders={encoded_loader}"
        )

    def get_newest(self, slug, loader="fabric"):
        encoded_loader = urllib.parse.quote(f'["{loader}"]')
        return self.get(
            f"/v2/project/{slug}/version?loaders={encoded_loader}&limit=1"
        )


def cmd_pin(api, fallback_versions):
    for line in sys.stdin:
        slug = line.strip()
        if not slug:
            continue

        found = False
        for try_ver in fallback_versions:
            data = api.get_versions(slug, try_ver)
            if data and isinstance(data, list) and len(data) > 0:
                ver = data[0]
                deps = [
                    d["project_id"]
                    for d in ver.get("dependencies", [])
                    if d.get("dependency_type") == "required" and d.get("project_id")
                ]
                print(
                    json.dumps(
                        {
                            "slug": slug,
                            "status": "found",
                            "id": ver["id"],
                            "version": ver["version_number"],
                            "matched": try_ver,
                            "deps": deps,
                        }
                    ),
                    flush=True,
                )
                found = True
                break

        if not found:
            print(json.dumps({"slug": slug, "status": "none"}), flush=True)


def cmd_check(api, mc_version, skip_newest=False):
    mc_major = mc_version.rsplit(".", 1)[0] if "." in mc_version else mc_version

    for line in sys.stdin:
        parts = line.strip().split("\t")
        if not parts or not parts[0]:
            continue
        slug = parts[0]
        pinned_id = parts[1] if len(parts) > 1 else ""

        # Compatible versions for our MC version
        compat = api.get_versions(slug, mc_version)
        if not compat or not isinstance(compat, list):
            compat = []

        # Fallback to major version (e.g. 1.21 if 1.21.1 returned nothing)
        if not compat and mc_major != mc_version:
            compat = api.get_versions(slug, mc_major)
            if not compat or not isinstance(compat, list):
                compat = []

        # Newest Fabric build for any MC version (skip in fast mode)
        newest_data = []
        if not skip_newest:
            newest_data = api.get_newest(slug)
            if not newest_data or not isinstance(newest_data, list):
                newest_data = []

        # Determine pinned version info
        pinned_ver = ""
        if pinned_id and compat:
            for v in compat:
                if v.get("id") == pinned_id:
                    pinned_ver = v.get("version_number", "?")
                    break
            if not pinned_ver:
                pinned_ver = f"(pinned ID not in {mc_version} builds)"

        # Latest compatible
        latest_compat_ver = ""
        latest_compat_id = ""
        if compat:
            latest_compat_ver = compat[0].get("version_number", "?")
            latest_compat_id = compat[0].get("id", "")

        # Newest overall
        newest_ver = ""
        newest_mc = []
        newest_id = ""
        if newest_data:
            newest_ver = newest_data[0].get("version_number", "?")
            newest_mc = newest_data[0].get("game_versions", [])
            newest_id = newest_data[0].get("id", "")

        # Status (when skip_newest, treat missing compat as not-found)
        if not compat and not newest_data and not skip_newest:
            status = "not-found"
        elif not compat and skip_newest:
            status = "not-found"
        elif not compat:
            status = "no-compat-build"
        elif not pinned_id:
            status = "up-to-date"
        elif pinned_id == latest_compat_id:
            status = "up-to-date"
        else:
            status = "update-available"

        newest_mc_str = ", ".join(newest_mc[-3:]) if newest_mc else "?"

        print(
            json.dumps(
                {
                    "slug": slug,
                    "pinned_ver": pinned_ver or latest_compat_ver,
                    "pinned_id": pinned_id,
                    "latest_compat_ver": latest_compat_ver,
                    "latest_compat_id": latest_compat_id,
                    "newest_ver": newest_ver,
                    "newest_id": newest_id,
                    "newest_mc": newest_mc_str,
                    "status": status,
                    "url": f"https://modrinth.com/mod/{slug}",
                }
            ),
            flush=True,
        )


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} pin|check <mc_version> [fallback_versions]", file=sys.stderr)
        sys.exit(1)

    mode = sys.argv[1]
    mc_version = sys.argv[2]

    api = ModrinthAPI()

    # Preflight check
    test = api.get_versions("fabric-api", mc_version)
    if test is None:
        print("ERROR: Modrinth API is unreachable", file=sys.stderr)
        sys.exit(1)
    print(f"  Modrinth API connected ({api.remaining} requests remaining)", file=sys.stderr)

    try:
        if mode == "pin":
            fallback_str = sys.argv[3] if len(sys.argv) > 3 else mc_version
            fallback_versions = fallback_str.split(",")
            cmd_pin(api, fallback_versions)
        elif mode == "check":
            skip_newest = "--fast" in sys.argv
            cmd_check(api, mc_version, skip_newest=skip_newest)
        else:
            print(f"Unknown mode: {mode}", file=sys.stderr)
            sys.exit(1)
    finally:
        api.close()


if __name__ == "__main__":
    main()
