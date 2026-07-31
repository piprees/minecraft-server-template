#!/usr/bin/env python3
"""Pins the viewer's URL shape in scripts/seed/web/route.js.

The viewer addresses two pieces of view state in the PATH — the expanded
dimension and the open candidate — and three separate things have to agree
on what that path looks like:

  - route.js, which writes it into the address bar and reads it back on load
  - viewer-server.ViewerHandler._is_page_route, which decides whether a GET
    serves index.html or a file
  - the <base href="/"> in viewer_template.html, without which every
    relative asset href 404s at the two-segment route

A disagreement is not a visible bug at the point it is introduced: the page
still renders, and only a refresh on a deep link shows it. So the shape is
pinned here, on both sides, from hand-worked cases rather than by
re-running the implementation.

Why node: it is the same file the browser loads. Skipped rather than failed
when node is absent — the roller itself needs no Node.
"""
import importlib.util
import json
import shutil
import subprocess
import unittest
from pathlib import Path

ROUTE_JS = Path(__file__).resolve().parent / "web" / "route.js"

# pathname -> (dim, seed). Everything that is not a slug, or a slug plus a
# signed integer, is not a route at all: '' means "the page decides nothing
# from the URL", which is what keeps assets, renders and a file:// open from
# being read as a dimension.
PARSE_CASES = [
    ("/", ("", "")),
    ("", ("", "")),
    ("/overworld", ("overworld", "")),
    ("/the-nether", ("the_nether", "")),
    ("/the-nether/", ("the_nether", "")),
    ("/paradise-lost", ("paradise_lost", "")),
    ("/the-gauntlet/12345", ("the_gauntlet", "12345")),
    # Seeds are signed 64-bit and stay strings — this one is past 2^53, so a
    # parse to Number would round it and pick the wrong candidate.
    ("/overworld/-4831234567890123456", ("overworld", "-4831234567890123456")),
    # Not routes.
    ("/index.html", ("", "")),
    ("/assets/app.js", ("", "")),
    ("/renders/the_nether/123.png", ("", "")),
    ("/the-nether/not-a-seed", ("", "")),
    ("/the-nether/123/extra", ("", "")),
    ("/The-Nether", ("", "")),
    ("/9lives", ("", "")),
    ("/the_nether", ("", "")),  # underscores are the NAME, hyphens the slug
]

# (dim, seed, query) -> url
BUILD_CASES = [
    ("", "", "", "/"),
    ("", "", "family=nether", "/?family=nether"),
    ("the_nether", "", "", "/the-nether"),
    ("the_nether", "12345", "", "/the-nether/12345"),
    ("the_nether", "-12345", "sort=score-desc", "/the-nether/-12345?sort=score-desc"),
    # A leading '?' from location.search must not double up.
    ("overworld", "", "?q=deep", "/overworld?q=deep"),
    # A seed with no dimension is not addressable — a candidate only exists
    # inside one.
    ("", "12345", "", "/"),
]


def run_node(script):
    return subprocess.run(["node", "-e", script], capture_output=True,
                          text=True, timeout=30)


class RouteJsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("node"):
            raise unittest.SkipTest("node not installed")
        cls.require = "const R = require({});".format(json.dumps(str(ROUTE_JS)))

    def _eval(self, expr):
        r = run_node(self.require + "process.stdout.write(JSON.stringify("
                     + expr + "))")
        self.assertEqual(r.returncode, 0, r.stderr)
        return json.loads(r.stdout)

    def test_parse_matches_hand_worked_cases(self):
        got = self._eval("[{}]".format(",".join(
            "R.parsePath({})".format(json.dumps(p)) for p, _ in PARSE_CASES)))
        for (path, (dim, seed)), have in zip(PARSE_CASES, got):
            self.assertEqual(have, {"dim": dim, "seed": seed},
                             "parsePath({!r})".format(path))

    def test_build_matches_hand_worked_cases(self):
        got = self._eval("[{}]".format(",".join(
            "R.buildUrl({}, {}, {})".format(json.dumps(d), json.dumps(s),
                                            json.dumps(q))
            for d, s, q, _ in BUILD_CASES)))
        for (d, s, q, want), have in zip(BUILD_CASES, got):
            self.assertEqual(have, want,
                             "buildUrl({!r}, {!r}, {!r})".format(d, s, q))

    def test_slug_round_trips_every_dimension_name(self):
        """Names are `^[a-z][a-z0-9_]*$`, so `_` <-> `-` is a bijection.

        If a name could contain a hyphen the round trip would be lossy and
        two dimensions could share a URL; the create/fork form and the mod
        both reject that, and this asserts the roller's own shipped names
        agree.
        """
        dims = (Path(__file__).resolve().parents[2]
                / "config" / "custom-dimensions" / "dimensions")
        names = sorted(p.stem for p in dims.glob("*.json")) if dims.is_dir() else []
        names += ["overworld", "the_nether", "the_end", "paradise_lost"]
        self.assertTrue(names, "no dimension names to check")
        got = self._eval("[{}]".format(",".join(
            "R.parsePath('/' + R.slug({})).dim".format(json.dumps(n))
            for n in names)))
        self.assertEqual(got, names)

    def test_a_file_url_path_is_never_a_route(self):
        """roll-all.sh prints .seedtest/index.html to open directly."""
        self.assertEqual(
            self._eval("R.parsePath('/Users/pip/Projects/elfydd/.seedtest/index.html')"),
            {"dim": "", "seed": ""})


class ServerRouteTests(unittest.TestCase):
    """viewer-server must serve the page for exactly the paths route.js writes.

    Serving too little means a refresh on a deep link 404s. Serving too much
    means a real file — the stylesheet, a render — is shadowed by the page,
    which breaks everything at once rather than one link.
    """

    @classmethod
    def setUpClass(cls):
        path = Path(__file__).with_name("viewer-server.py")
        spec = importlib.util.spec_from_file_location("viewer_server", path)
        cls.mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.mod)

    def setUp(self):
        import shutil as _shutil
        import tempfile
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(_shutil.rmtree, self.tmp, ignore_errors=True)
        (self.tmp / "index.html").write_text("<html></html>")
        (self.tmp / "assets").mkdir()
        (self.tmp / "assets" / "app.js").write_text("")
        (self.tmp / "renders" / "the_nether").mkdir(parents=True)

        handler = self.mod.ViewerHandler
        self.is_route = lambda p: handler._is_page_route(
            type("H", (), {"seedtest": str(self.tmp)})(), p)

    def test_deep_links_serve_the_page(self):
        for path in ("/overworld", "/the-nether", "/the-nether/",
                     "/the-gauntlet/12345", "/overworld/-4831234567890123456"):
            self.assertTrue(self.is_route(path), path)

    def test_real_files_and_endpoints_are_never_shadowed(self):
        for path in ("/", "/index.html", "/assets", "/assets/app.js",
                     "/renders", "/renders/the_nether/123.png",
                     "/fork-schema", "/pipeline-status", "/job/reroll-x-1",
                     "/the-nether/not-a-seed"):
            self.assertFalse(self.is_route(path), path)

    def test_it_agrees_with_route_js(self):
        """Both sides, same list, so neither can drift alone.

        The one deliberate asymmetry is _API_ROOTS: `/fork-schema` is shaped
        exactly like a dimension slug and route.js would happily parse it,
        but the server owes that path a JSON endpoint.
        """
        if not shutil.which("node"):
            self.skipTest("node not installed")
        require = "const R = require({});".format(json.dumps(str(ROUTE_JS)))
        paths = [p for p, _ in PARSE_CASES if p]
        r = run_node(require + "process.stdout.write(JSON.stringify([{}]))".format(
            ",".join("R.parsePath({}).dim !== ''".format(json.dumps(p))
                     for p in paths)))
        self.assertEqual(r.returncode, 0, r.stderr)
        for path, js_routes in zip(paths, json.loads(r.stdout)):
            # '/' is the page for both, and neither treats it as a dimension.
            self.assertEqual(self.is_route(path), js_routes, path)


class ShippedTogetherTests(unittest.TestCase):
    """route.js has to be in three lists or it silently does nothing.

    Same trap project.js documents: WEB_ASSETS (copied beside index.html),
    the template's script tags (loaded at all), and the bundle MANIFEST
    (shipped to consumers). Missing the third is invisible locally and
    breaks every consumer viewer.
    """

    def test_in_web_assets_before_app_js(self):
        path = Path(__file__).with_name("score-dimensions.py")
        spec = importlib.util.spec_from_file_location("score_dimensions", path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        names = [src for src, _served in mod.WEB_ASSETS]
        self.assertIn("route.js", names)
        self.assertLess(names.index("route.js"), names.index("app.js"))

    def test_in_template_before_app_js(self):
        html = (Path(__file__).with_name("viewer_template.html")).read_text()
        self.assertIn('src="assets/route.js"', html)
        self.assertLess(html.index("assets/route.js"), html.index("assets/app.js"))

    def test_in_bundle_manifest(self):
        manifest = (Path(__file__).resolve().parents[1]
                    / "build-stack-bundle.sh").read_text()
        self.assertIn("scripts/seed/web/route.js", manifest)

    def test_template_carries_the_base_element_for_deep_routes(self):
        """Without it, /the-nether/<seed> resolves assets/app.css against
        /the-nether/ and the page loads unstyled with no renders."""
        html = (Path(__file__).with_name("viewer_template.html")).read_text()
        self.assertIn("createElement('base')", html)
        self.assertIn("location.protocol !== 'file:'", html)
        # Before the stylesheet, or it has nothing left to fix.
        self.assertLess(html.index("createElement('base')"),
                        html.index('href="assets/app.css"'))


if __name__ == "__main__":
    unittest.main()
