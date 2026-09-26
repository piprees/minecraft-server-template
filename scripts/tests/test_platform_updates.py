#!/usr/bin/env python3
"""Tests for platform_updates.py: tag-shape rules, stable-only choice, and coupled rewrites.

Load-bearing: a wrong pick here opens a PR that moves the server to a
pre-release, a different Java line or a different Minecraft version, and a
rewrite that misses a duplicate splits the loader between server and client.
No network: resolvers get a fake Net.
"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import platform_updates as pu  # noqa: E402

REPO = Path(__file__).resolve().parents[2]

COMPOSE = """services:
  mc:
    image: ${MIRROR_REGISTRY:-ghcr.io/piprees/mirrors}/itzg/minecraft-server:2026.7.0-java21
    environment:
      TYPE: FABRIC
      FABRIC_LOADER_VERSION: ${FABRIC_LOADER_VERSION:-0.19.3}
  backup:
    image: ${MIRROR_REGISTRY:-ghcr.io/piprees/mirrors}/itzg/mc-backup:2026.7.0
  backup2:
    image: ${MIRROR_REGISTRY:-ghcr.io/piprees/mirrors}/itzg/mc-backup:2026.7.0
  minio:
    image: ${MIRROR_REGISTRY:-ghcr.io/piprees/mirrors}/minio/minio:RELEASE.2025-09-07T16-13-09Z
  seed:
    image: ${IMAGE_REGISTRY:-ghcr.io/x}/defaults-seed:${IMAGE_TAG:-latest}
"""
GRADLE = "minecraft_version=1.21.1\nyarn_mappings=1.21.1+build.2\nloader_version=0.16.14\nfabric_version=0.115.0+1.21.1\n"
TAILWIND = '''TAILWIND_VERSION="4.3.3"
asset_sha256() {
  case "$1" in
    tailwindcss-linux-x64) echo "''' + "a" * 64 + '''" ;;
    tailwindcss-macos-arm64) echo "''' + "b" * 64 + '''" ;;
    *) die "no pinned checksum for $1" ;;
  esac
}
'''
MODPACK_SH = ('FABRIC_LOADER_VERSION=$(python3 -c "import json; print(json.load(open(\'$MANIFEST\'))'
              '[\'dependencies\'][\'fabric-loader\'])" 2> /dev/null || echo "0.19.3")\n'
              'PACKWIZ_BOOTSTRAP_VERSION="0.0.3"\n')


def texts():
    return {
        "docker-compose.yml": COMPOSE,
        "modpack/adventure.mrpack.json": '{\n  "dependencies": {\n    "minecraft": "1.21.1",\n    "fabric-loader": "0.19.3"\n  }\n}\n',
        "scripts/build-modpack.sh": MODPACK_SH,
        "mods/a/gradle.properties": GRADLE,
        "mods/b/gradle.properties": GRADLE,
        pu.TAILWIND_FILE: TAILWIND,
        ".github/workflows/release.yml": "      - uses: orhun/git-cliff-action@v4\n        with:\n          version: v2.14.2\n",
        ".github/workflows/release-train.yml": "env:\n  GIT_CLIFF_VERSION: '2.14.2'\n",
        "examples/consumer/.github/workflows/server-power.yml": "        env:\n          DOCTL_VERSION: '1.175.0'\n",
    }


class FakeNet:
    def __init__(self, hub=None, tags=None, docs=None):
        self.hub, self.tags, self.docs = hub or {}, tags or {}, docs or {}
        self.calls = []

    def hub_tags(self, image, name_filter=""):
        self.calls.append(("hub", image, name_filter))
        return self.hub.get(image, [])

    def git_tags(self, repo):
        return self.tags.get(repo, [])

    def json(self, url):
        return self.docs[url]

    def get(self, url):
        return self.docs[url]

    def exists(self, url):
        return url in self.docs


LOADER_URL = "https://meta.fabricmc.net/v2/versions/loader/1.21.1"
YARN_URL = "https://meta.fabricmc.net/v2/versions/yarn/1.21.1"


def full_net(**overrides):
    docs = {
        LOADER_URL: [{"loader": {"version": "0.19.5", "stable": True}}, {"loader": {"version": "0.19.4", "stable": False}}],
        YARN_URL: [{"gameVersion": "1.21.1", "build": 3, "version": "1.21.1+build.3", "stable": False},
                   {"gameVersion": "1.21.1", "build": 2, "version": "1.21.1+build.2", "stable": False}],
        "https://api.modrinth.com/v2/version/Nlt8gI9z": {"version_number": "0.116.15+1.21.1", "game_versions": ["1.21.1"]},
        "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.116.15+1.21.1/fabric-api-0.116.15+1.21.1.pom": "",
        "https://pypi.org/pypi/git-cliff/json": {"releases": {"2.14.2": [], "2.15.0": []}},
        "https://github.com/tailwindlabs/tailwindcss/releases/download/v4.4.0/sha256sums.txt":
            f"{'c' * 64}  ./tailwindcss-linux-x64\n{'d' * 64}  ./tailwindcss-macos-arm64\n{'e' * 64}  ./tailwindcss-windows-x64.exe\n",
    }
    docs.update(overrides.pop("docs", {}))
    return FakeNet(
        hub=overrides.pop("hub", {"itzg/minecraft-server": ["2026.9.2-java21", "2026.9.2-java25", "2026.9.2-java21-jdk",
                                                           "2026.10.0-java21-rc1", "stable-java21"],
                                  "itzg/mc-backup": ["2026.9.2", "latest", "2026.9.1"]}),
        tags=overrides.pop("tags", {"tailwindlabs/tailwindcss": ["v4.3.3", "v4.4.0", "v5.0.0-beta.1"],
                                    "packwiz/packwiz-installer-bootstrap": ["v0.0.3"],
                                    "orhun/git-cliff": ["v2.14.2", "v2.15.0", "v2.16.0"],
                                    "digitalocean/doctl": ["v1.175.0", "v1.176.0"]}),
        docs=docs)


class Root:
    """A temporary checkout holding config/modrinth-mods.txt for the fabric-api resolver."""

    def __enter__(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        (root / "config").mkdir()
        (root / "config/modrinth-mods.txt").write_text("# libs\nfabric-api:Nlt8gI9z\nsodium:AAAAAAAA?\n")
        return root

    def __exit__(self, *exc):
        self.tmp.cleanup()


class TagShapeTest(unittest.TestCase):
    def test_keeps_java21_suffix_and_skips_prereleases(self):
        rule = pu.IMAGE_RULES["itzg/minecraft-server"]
        tags = ["2026.9.2-java25", "2026.9.2-java21-jdk", "2026.10.0-java21-rc1", "2026.9.2-java21", "stable-java21"]
        self.assertEqual(pu.pick_image_tag("2026.7.0-java21", tags, rule), "2026.9.2-java21")

    def test_java21_is_required(self):
        with self.assertRaises(pu.UpdateError):
            pu.pick_image_tag("2026.7.0-java25", ["2026.9.2-java25"], pu.IMAGE_RULES["itzg/minecraft-server"])

    def test_nginx_stays_on_the_stable_branch_and_alpine(self):
        tags = ["1.31.6-alpine", "1.30.5-alpine", "1.30.5-alpine-slim", "1.30.5-alpine3.24", "1.30.5", "1.30-alpine"]
        self.assertEqual(pu.pick_image_tag("1.30.3-alpine", tags, pu.IMAGE_RULES["nginx"]), "1.30.5-alpine")

    def test_calver_and_component_count(self):
        tags = ["2026.9.3", "2026.9.3-arm64", "1961-96d39ad", "latest", "2026"]
        self.assertEqual(pu.pick_image_tag("2026.6.1", tags, {}), "2026.9.3")

    def test_minio_timestamp_pattern(self):
        rule = pu.IMAGE_RULES["minio/minio"]
        tags = ["RELEASE.2025-10-15T17-29-55Z", "RELEASE.2025-10-15T17-29-55Z-cpuv1", "latest"]
        self.assertEqual(pu.pick_image_tag("RELEASE.2025-09-07T16-13-09Z", tags, rule), "RELEASE.2025-10-15T17-29-55Z")

    def test_never_downgrades(self):
        self.assertIsNone(pu.pick_image_tag("2.5.5", ["2.4.0", "2.5.5"], {}))

    def test_unrecognised_tag_fails(self):
        with self.assertRaises(pu.UpdateError):
            pu.pick_image_tag("latest", ["1.0.0"], {})


class StablePickTest(unittest.TestCase):
    def test_pick_stable(self):
        self.assertEqual(pu.pick_stable("4.3.3", ["v4.3.3", "v4.4.0", "v5.0.0-beta.1", "v4.10.0-rc.1"]), "4.4.0")
        self.assertIsNone(pu.pick_stable("0.0.3", ["v0.0.1", "v0.0.3"]))
        self.assertEqual(pu.pick_stable("1.175.0", ["v1.9.0", "v1.176.0", "v1.200.0-dev"]), "1.176.0")

    def test_loader_takes_the_stable_marked_build(self):
        entries = [{"loader": {"version": "0.19.6", "stable": False}}, {"loader": {"version": "0.19.5", "stable": True}}]
        self.assertEqual(pu.pick_fabric_loader(entries, "0.16.14"), "0.19.5")
        self.assertIsNone(pu.pick_fabric_loader(entries, "0.19.5"))
        self.assertIsNone(pu.pick_fabric_loader([{"loader": {"version": "0.20.0", "stable": False}}], "0.19.5"))

    def test_yarn_newest_build_for_exactly_1_21_1(self):
        entries = [{"gameVersion": "1.21.2", "build": 9, "version": "1.21.2+build.9", "stable": True},
                   {"gameVersion": "1.21.1", "build": 3, "version": "1.21.1+build.3", "stable": False},
                   {"gameVersion": "1.21.1", "build": 1, "version": "1.21.1+build.1", "stable": False}]
        self.assertEqual(pu.pick_yarn(entries, "1.21.1+build.2"), "1.21.1+build.3")
        self.assertIsNone(pu.pick_yarn(entries, "1.21.1+build.3"))
        self.assertIsNone(pu.pick_yarn(entries[:1], "1.21.1+build.3"))

    def test_modrinth_pin_and_sums(self):
        self.assertEqual(pu.modrinth_pin("fabric-api:Nlt8gI9z  # lib\nfoo:AAAAAAAA?\n", "fabric-api"), "Nlt8gI9z")
        self.assertEqual(pu.modrinth_pin("foo:AAAAAAAA?\n", "foo"), "AAAAAAAA")
        self.assertIsNone(pu.modrinth_pin("fabric-api-extra:AAAAAAAA\n", "fabric-api"))
        self.assertEqual(pu.parse_sha256sums(f"{'a' * 64}  ./x\n{'b' * 64} *y\njunk\n"), {"x": "a" * 64, "y": "b" * 64})


class ScanTest(unittest.TestCase):
    def test_scan_finds_every_pin(self):
        found = {(o.ecosystem, o.name) for o in pu.scan(texts())}
        self.assertEqual(found, {("image", "itzg/minecraft-server"), ("image", "itzg/mc-backup"),
                                 ("image", "minio/minio"), ("loader", "fabric-loader"), ("gradle", "yarn"),
                                 ("gradle", "fabric-api"), ("tool", "tailwindcss"),
                                 ("tool", "packwiz-installer-bootstrap"), ("tool", "git-cliff"), ("tool", "doctl")})

    def test_missing_pin_line_fails(self):
        t = texts()
        t["mods/b/gradle.properties"] = GRADLE.replace("yarn_mappings=", "yarn=")
        with self.assertRaisesRegex(pu.UpdateError, "yarn"):
            pu.scan(t)

    def test_classify_line(self):
        self.assertEqual(pu.classify_line("docker-compose.yml", COMPOSE.splitlines()[2]), ("image", "itzg/minecraft-server"))
        self.assertEqual(pu.classify_line("mods/x/gradle.properties", "loader_version=0.19.5"), ("loader", "fabric-loader"))
        self.assertIsNone(pu.classify_line("mods/x/y/gradle.properties", "loader_version=0.19.5"))
        self.assertIsNone(pu.classify_line("docker-compose.yml", "    image: evil/image:1.0"))
        self.assertIsNone(pu.classify_line("docker-compose.yml", "      FABRIC_LOADER_VERSION: 0.19.5"))
        self.assertIsNone(pu.classify_line("mods/x/gradle.properties", "minecraft_version=1.21.4"))
        self.assertEqual(pu.classify_line(pu.TAILWIND_FILE, '    tailwindcss-linux-x64) echo "' + "f" * 64 + '" ;;'),
                         ("sha256", "tailwindcss-linux-x64"))

    def test_allowed_path(self):
        for path in ("docker-compose.yml", "mods/custom-dimensions/gradle.properties", pu.TAILWIND_FILE,
                     ".github/workflows/release.yml", "examples/consumer/.github/workflows/server-power.yml"):
            self.assertTrue(pu.allowed_path(path), path)
        for path in ("mods/x/build.gradle", ".github/workflows/deploy.yml", "mods/a/b/gradle.properties"):
            self.assertFalse(pu.allowed_path(path), path)

    def test_repo_pins_are_found_and_aligned(self):
        rel = pu.pin_files(REPO)
        occ = pu.scan({p: (REPO / p).read_text() for p in rel})
        by_dep = {}
        for o in occ:
            by_dep.setdefault((o.ecosystem, o.name), set()).add(o.version)
        self.assertEqual(len(by_dep[("loader", "fabric-loader")]), 1, by_dep[("loader", "fabric-loader")])
        self.assertEqual(len(by_dep[("tool", "git-cliff")]), 1)
        self.assertEqual(sum(1 for o in occ if o.name == "fabric-loader"), 3 + len(list(REPO.glob("mods/*/gradle.properties"))))


class PlanApplyTest(unittest.TestCase):
    def test_plan_aligns_duplicates_and_skips_frozen(self):
        with Root() as root:
            updates, errors = pu.plan(texts(), full_net(), root)
        self.assertEqual(errors, [])
        got = {(u["ecosystem"], u["name"]): (u["old"], u["new"]) for u in updates}
        self.assertEqual(got[("loader", "fabric-loader")], (["0.16.14", "0.19.3"], "0.19.5"))
        self.assertEqual(got[("image", "itzg/minecraft-server")], (["2026.7.0-java21"], "2026.9.2-java21"))
        self.assertEqual(got[("image", "itzg/mc-backup")], (["2026.7.0"], "2026.9.2"))
        self.assertEqual(got[("gradle", "fabric-api")], (["0.115.0+1.21.1"], "0.116.15+1.21.1"))
        self.assertEqual(got[("gradle", "yarn")], (["1.21.1+build.2"], "1.21.1+build.3"))
        self.assertEqual(got[("tool", "tailwindcss")], (["4.3.3"], "4.4.0"))
        self.assertEqual(got[("tool", "git-cliff")], (["2.14.2"], "2.15.0"))  # 2.16.0 is not on PyPI
        self.assertEqual(got[("tool", "doctl")], (["1.175.0"], "1.176.0"))
        self.assertNotIn(("image", "minio/minio"), got)
        self.assertNotIn(("tool", "packwiz-installer-bootstrap"), got)
        loader = next(u for u in updates if u["name"] == "fabric-loader")
        self.assertEqual(loader["notes"], "https://github.com/FabricMC/fabric-loader/releases/tag/0.19.5")
        self.assertEqual(len(loader["files"]), 5)

    def test_apply_rewrites_every_duplicate_and_the_checksums(self):
        with Root() as root:
            updates, _ = pu.plan(texts(), full_net(), root)
        new = pu.apply_plan(texts(), updates)
        self.assertIn("itzg/minecraft-server:2026.9.2-java21\n", new["docker-compose.yml"])
        self.assertEqual(new["docker-compose.yml"].count("itzg/mc-backup:2026.9.2\n"), 2)
        self.assertIn("${FABRIC_LOADER_VERSION:-0.19.5}", new["docker-compose.yml"])
        self.assertIn("minio/minio:RELEASE.2025-09-07T16-13-09Z", new["docker-compose.yml"])
        self.assertIn("defaults-seed:${IMAGE_TAG:-latest}", new["docker-compose.yml"])
        self.assertIn('"fabric-loader": "0.19.5"', new["modpack/adventure.mrpack.json"])
        self.assertIn('|| echo "0.19.5")', new["scripts/build-modpack.sh"])
        for g in ("mods/a/gradle.properties", "mods/b/gradle.properties"):
            self.assertEqual(new[g], "minecraft_version=1.21.1\nyarn_mappings=1.21.1+build.3\n"
                                     "loader_version=0.19.5\nfabric_version=0.116.15+1.21.1\n")
        self.assertEqual(pu.tailwind_sums(new[pu.TAILWIND_FILE]),
                         {"tailwindcss-linux-x64": "c" * 64, "tailwindcss-macos-arm64": "d" * 64})
        self.assertIn('TAILWIND_VERSION="4.4.0"', new[pu.TAILWIND_FILE])
        self.assertIn("version: v2.15.0", new[".github/workflows/release.yml"])
        self.assertIn("GIT_CLIFF_VERSION: '2.15.0'", new[".github/workflows/release-train.yml"])
        self.assertEqual(pu.scan(pu.apply_plan(new, [])), pu.scan(new))

    def test_missing_published_checksum_fails(self):
        net = full_net(docs={"https://github.com/tailwindlabs/tailwindcss/releases/download/v4.4.0/sha256sums.txt":
                             f"{'c' * 64}  ./tailwindcss-linux-x64\n"})
        with Root() as root:
            updates, _ = pu.plan(texts(), net, root)
        with self.assertRaisesRegex(pu.UpdateError, "macos-arm64"):
            pu.apply_plan(texts(), updates)

    def test_resolver_failure_is_an_error_not_a_skip(self):
        net = full_net(docs={"https://api.modrinth.com/v2/version/Nlt8gI9z":
                             {"version_number": "0.116.15+1.21.4", "game_versions": ["1.21.4"]}})
        with Root() as root:
            _, errors = pu.plan(texts(), net, root)
        self.assertTrue(any("fabric-api" in e and "1.21.1" in e for e in errors), errors)

    def test_fabric_api_missing_from_maven_fails(self):
        net = full_net()
        del net.docs["https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.116.15+1.21.1/fabric-api-0.116.15+1.21.1.pom"]
        with Root() as root:
            _, errors = pu.plan(texts(), net, root)
        self.assertTrue(any("maven" in e for e in errors), errors)

    def test_frozen_image_with_diverging_pins_fails(self):
        t = texts()
        t["docker-compose.yml"] += "  minio2:\n    image: ${MIRROR_REGISTRY:-ghcr.io/piprees/mirrors}/minio/minio:RELEASE.2025-01-01T00-00-00Z\n"
        with Root() as root:
            _, errors = pu.plan(t, full_net(), root)
        self.assertTrue(any("minio/minio" in e and "disagree" in e for e in errors), errors)

    def test_hub_is_queried_with_the_tag_suffix(self):
        net = full_net()
        with Root() as root:
            pu.plan(texts(), net, root)
        self.assertIn(("hub", "itzg/minecraft-server", "-java21"), net.calls)
        self.assertNotIn("minio/minio", [c[1] for c in net.calls])

    def test_markdown_links(self):
        body = pu.markdown([{"ecosystem": "image", "name": "nginx", "old": ["1.30.3-alpine"], "new": "1.30.5-alpine",
                             "files": ["docker-compose.yml"], "notes": pu.notes_url("image", "nginx", "1.30.5-alpine")}])
        self.assertIn("[1.30.5-alpine](https://nginx.org/en/CHANGES-1.30)", body)
        self.assertIn("mirrored to GHCR", body)
        self.assertEqual(pu.notes_url("image", "itzg/minecraft-server", "2026.9.2-java21"),
                         "https://github.com/itzg/docker-minecraft-server/releases/tag/2026.9.2")


if __name__ == "__main__":
    unittest.main()
