/* project.js — the map projection, in one place, so it can be tested.
 *
 * Every overlay over the lightbox map (the hover rings in app.js, the
 * dartboard's bands, the structure markers) turns a distance in BLOCKS from
 * spawn into a position inside an SVG whose viewBox is `0 0 100 100`
 * stretched across the whole render. There is exactly one correct sum for
 * that, it was written out by hand in four places, and a marker drawn with
 * the wrong one lands somewhere plausible rather than somewhere obviously
 * broken — which is the failure mode you do not catch by looking.
 *
 * Spawn is the centre of the render, so a block offset d sits at
 *
 *     50 + (d / coverage) * 100
 *
 * where `coverage` is how many blocks the image ACTUALLY on screen spans
 * edge to edge (window.lbMapCoverage() — the low-res render until the
 * hi-res probe lands, never the data-coverage attribute on its own).
 *
 * Because .lb-layer is laid exactly over the <img> by window.alignLbOverlay
 * and the SVG uses preserveAspectRatio="none", a viewBox unit is 1% of the
 * image's laid-out width. So the same sum in screen pixels is
 *
 *     imgRect.left + (0.5 + d / coverage) * imgRect.width
 *
 * which is what the browser check measures. `screenX` below is that
 * identity, spelled out once, so a test can assert the two agree instead of
 * trusting that two hand-written copies still match.
 *
 * Loaded before app.js by viewer_template.html; also require()-able from
 * node so test_web_projection.py can pin the arithmetic without a browser.
 */
;(function (root) {
  /** Block offset from spawn -> viewBox coordinate (0-100 across the render). */
  function project(blockOffset, coverage) {
    if (!coverage) return NaN
    return 50 + (blockOffset / coverage) * 100
  }

  /** A distance in blocks -> a viewBox RADIUS (no centre offset).
   *
   * The other half of the same sum, and the half that is easy to get wrong by
   * reaching for project() instead: a ring at 4000 blocks has radius
   * 4000/coverage*100, not 50 + that.
   */
  function radius(blocks, coverage) {
    if (!coverage) return NaN
    return (blocks / coverage) * 100
  }

  /** The same point in screen pixels, given the <img>'s laid-out rect.
   *  `rect` needs only `left`/`top` and `width`/`height`. */
  function screenX(blockOffset, coverage, rect) {
    return rect.left + (project(blockOffset, coverage) / 100) * rect.width
  }

  function screenY(blockOffset, coverage, rect) {
    return rect.top + (project(blockOffset, coverage) / 100) * rect.height
  }

  /** Is a projected viewBox coordinate on the render at all?
   *
   * A pocket dimension's fixed placement, and any census position in a
   * dimension whose border exceeds the rendered area, sits outside the
   * image. Clamping such a marker to the edge would claim it is somewhere it
   * is not, so callers drop it instead. One block of slack either side
   * keeps a marker exactly on the border from flickering out.
   */
  function onRender(v) {
    return v >= -1 && v <= 101
  }

  root.lbProject = project
  root.lbProjectRadius = radius
  root.lbProjectScreenX = screenX
  root.lbProjectScreenY = screenY
  root.lbOnRender = onRender
})(typeof window !== 'undefined' ? window : globalThis)

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    project: globalThis.lbProject,
    radius: globalThis.lbProjectRadius,
    screenX: globalThis.lbProjectScreenX,
    screenY: globalThis.lbProjectScreenY,
    onRender: globalThis.lbOnRender,
  }
}
