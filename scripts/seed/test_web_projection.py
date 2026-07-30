#!/usr/bin/env python3
"""Pins the map projection in scripts/seed/web/project.js.

Why a test at all: every overlay on the lightbox map converts a distance in
blocks from spawn into a coordinate inside an SVG whose viewBox is 0-100
across the whole render, and a marker drawn with the wrong constant lands
somewhere PLAUSIBLE rather than somewhere obviously broken. That is not a
failure you catch by looking at a screenshot, which is precisely why the sum
was written out by hand in four places and why it now lives in one.

Why node: it is the same file the browser loads, evaluated by the same kind
of engine, so this cannot pass while the shipped asset disagrees. Skipped
rather than failed when node is absent — the roller itself needs no Node.

The identity being pinned is the one the browser check measures:

    marker screen x == imgRect.left + (0.5 + blockX / coverage) * imgRect.width

so a browser measurement disagreeing with this test means the DOM plumbing
(window.alignLbOverlay, window.lbMapCoverage) is wrong, not the arithmetic.
"""
import json
import shutil
import subprocess
import unittest
from pathlib import Path

PROJECT_JS = Path(__file__).resolve().parent / "web" / "project.js"

# (blockOffset, coverage) -> viewBox coordinate. Worked by hand, not by
# running the code: a test that recomputes the implementation proves nothing.
#
# The coverages are real ones from score-dimensions._map_coverages: a scale-1
# dimension renders 8192 blocks low-res and 32768 hi-res, and a scale-8 pocket
# dimension 1024 and 4096. BOTH of a dimension's two numbers appear on screen
# for the same candidate — the low-res render shows immediately and the hi-res
# one replaces it if a probe finds it — which is the entire reason
# lbMapCoverage exists rather than a single per-dimension constant.
CASES = [
    (0, 32768, 50.0),               # spawn is the centre of the render
    (16384, 32768, 100.0),          # half the coverage out is the right edge
    (-16384, 32768, 0.0),           # ...and the left
    (8192, 32768, 75.0),
    (-8192, 32768, 25.0),
    (4096, 8192, 100.0),            # low-res render of the same dimension
    (1600, 8192, 69.53125),
    (16, 32768, 50.048828125),      # one chunk: the census's quantisation
    (2048, 4096, 100.0),            # a scale-8 pocket dimension, hi-res
    (-256, 1024, 25.0),             # ...and low-res
]


def run_node(script):
    return subprocess.run(["node", "-e", script], capture_output=True,
                          text=True, timeout=30)


class ProjectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("node"):
            raise unittest.SkipTest("node not installed")
        cls.require = "const P = require({});".format(json.dumps(str(PROJECT_JS)))

    def _eval(self, expr):
        r = run_node(self.require + "process.stdout.write(JSON.stringify(" + expr + "))")
        self.assertEqual(r.returncode, 0, r.stderr)
        return json.loads(r.stdout)

    def test_project_matches_hand_worked_values(self):
        got = self._eval("[{}]".format(",".join(
            "P.project({}, {})".format(d, c) for d, c, _ in CASES)))
        for (d, c, want), have in zip(CASES, got):
            self.assertAlmostEqual(
                have, want, places=9,
                msg="project({}, {}) — a block offset d must land at "
                    "50 + d/coverage*100".format(d, c))

    def test_radius_is_the_projection_without_the_centre_offset(self):
        """The other half of the same sum, and the easy one to get wrong.

        A ring at 4000 blocks has radius 4000/coverage*100. Reaching for
        project() there puts every ring 50 viewBox units too wide, which on a
        band starting at 0 draws a circle round the whole render — the exact
        shape 53 of the overworld winner's 77 rows were already drawing for
        an unrelated reason.
        """
        for d, c, want in CASES:
            have = self._eval("P.radius({}, {})".format(d, c))
            self.assertAlmostEqual(have, want - 50.0, places=9)

    def test_screen_pixels_agree_with_the_browser_identity(self):
        """imgRect.left + (0.5 + blockX/coverage) * imgRect.width.

        This is what `agent-browser eval` measures against a real marker's
        bounding box, so the two must be the same expression. A 660px image
        at left 137 is a real measured lightbox geometry.
        """
        rect = {"left": 137.0, "top": 41.0, "width": 660.0, "height": 660.0}
        for d, c, _want in CASES:
            have = self._eval("P.screenX({}, {}, {})".format(d, c, json.dumps(rect)))
            expected = rect["left"] + (0.5 + d / c) * rect["width"]
            self.assertAlmostEqual(have, expected, places=6)
            have_y = self._eval("P.screenY({}, {}, {})".format(d, c, json.dumps(rect)))
            self.assertAlmostEqual(
                have_y, rect["top"] + (0.5 + d / c) * rect["height"], places=6)

    def test_zero_coverage_is_not_silently_the_centre(self):
        """A missing coverage must poison the sum, not project everything to 50.

        lbMapCoverage returns 0 when the panel carries no data-coverage — for
        instance before the detail markup lands. Returning 50 there would pile
        every marker in the dead centre of the map, which reads as a real
        (and very wrong) answer instead of as no answer.
        """
        self.assertIsNone(self._eval("Number.isFinite(P.project(100, 0)) ? 1 : null"))
        self.assertIsNone(self._eval("Number.isFinite(P.radius(100, 0)) ? 1 : null"))

    def test_on_render_rejects_points_outside_the_image(self):
        """A pocket dimension's border can exceed the rendered area."""
        self.assertEqual(
            self._eval("[P.onRender(50), P.onRender(0), P.onRender(100), "
                       "P.onRender(-1), P.onRender(101), "
                       "P.onRender(-1.5), P.onRender(140)]"),
            [True, True, True, True, True, False, False])


class ShippedTogetherTests(unittest.TestCase):
    """project.js must actually reach the page, in the right order.

    It is a new web asset, and there are three separate lists it has to be in
    or it silently does nothing: WEB_ASSETS (copied beside index.html), the
    template's script tags (loaded at all), and the bundle MANIFEST (shipped
    to consumers). Missing the third is invisible in local testing and breaks
    every consumer viewer, which is the trap AGENTS.md documents.
    """

    def test_in_web_assets(self):
        import importlib.util
        path = Path(__file__).with_name("score-dimensions.py")
        spec = importlib.util.spec_from_file_location("score_dimensions", path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        names = [src for src, _served in mod.WEB_ASSETS]
        self.assertIn("project.js", names)
        # Before app.js: app.js's hover overlay calls window.lbProjectRadius.
        self.assertLess(names.index("project.js"), names.index("app.js"))

    def test_in_template_before_app_js(self):
        html = (Path(__file__).with_name("viewer_template.html")).read_text()
        self.assertIn('src="assets/project.js"', html)
        self.assertLess(html.index('assets/project.js'), html.index('assets/app.js'))

    def test_in_bundle_manifest(self):
        manifest = (Path(__file__).resolve().parents[1]
                    / "build-stack-bundle.sh").read_text()
        self.assertIn("scripts/seed/web/project.js", manifest)


if __name__ == "__main__":
    unittest.main()
