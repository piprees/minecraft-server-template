/* route.js — the viewer's URL, in one place, so it can be tested.
 *
 * Two pieces of view state live in the PATH rather than the query string,
 * because they are what the page is currently about:
 *
 *     /the-nether              the_nether's card is expanded
 *     /the-nether/-4831234567  ...and that candidate's lightbox is open
 *
 * Dimension names are snake_case (`^[a-z][a-z0-9_]*$`, enforced by the
 * create/fork form and by the mod), so `_` <-> `-` is a bijection and the
 * slug round-trips exactly. Everything else — filters, sort, search — stays
 * in the query string, where order and absence are cheap.
 *
 * A path is parsed strictly: anything that is not a slug, or a slug plus a
 * signed integer seed, is NOT a route. That is what keeps `/assets/app.js`,
 * `/renders/d1/11.png` and a `file://` open of `.seedtest/index.html` from
 * being read as a dimension. viewer-server.ViewerHandler._is_page_route
 * mirrors the same shape server-side, so the set of paths that serve the
 * page and the set the page can restore from are the same set.
 *
 * Loaded before app.js by viewer_template.html; also require()-able from
 * node so test_web_routing.py can pin it without a browser.
 */
;(function (root) {
  //: A dimension slug: the snake_case name with underscores swapped out.
  var DIM_RE = /^[a-z][a-z0-9-]*$/
  //: Seeds are signed 64-bit integers and stay STRINGS end to end — parsing
  //: one to a Number silently rounds anything past 2^53.
  var SEED_RE = /^-?[0-9]+$/

  function slug(name) {
    return String(name == null ? '' : name).replace(/_/g, '-')
  }

  function unslug(s) {
    return String(s == null ? '' : s).replace(/-/g, '_')
  }

  /** location.pathname -> {dim, seed}, both '' when the path is not a route. */
  function parsePath(pathname) {
    var none = { dim: '', seed: '' }
    var parts = String(pathname == null ? '' : pathname).split('/')
      .filter(function (p) { return p !== '' })
    if (!parts.length || parts.length > 2) return none
    if (!DIM_RE.test(parts[0])) return none
    if (parts.length === 1) return { dim: unslug(parts[0]), seed: '' }
    if (!SEED_RE.test(parts[1])) return none
    return { dim: unslug(parts[0]), seed: parts[1] }
  }

  /** {dim, seed} -> path. No dim means the root, and a seed without a
   *  dimension is not addressable — a candidate only exists inside one. */
  function buildPath(dim, seed) {
    if (!dim) return '/'
    return '/' + slug(dim) + (seed ? '/' + String(seed) : '')
  }

  /** The full URL to hand history.pushState: path plus the filter query. */
  function buildUrl(dim, seed, query) {
    var q = String(query == null ? '' : query)
    if (q.charAt(0) === '?') q = q.slice(1)
    return buildPath(dim, seed) + (q ? '?' + q : '')
  }

  root.lbRouteSlug = slug
  root.lbRouteUnslug = unslug
  root.lbRouteParse = parsePath
  root.lbRoutePath = buildPath
  root.lbRouteUrl = buildUrl
})(typeof window !== 'undefined' ? window : globalThis)

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    slug: globalThis.lbRouteSlug,
    unslug: globalThis.lbRouteUnslug,
    parsePath: globalThis.lbRouteParse,
    buildPath: globalThis.lbRoutePath,
    buildUrl: globalThis.lbRouteUrl,
  }
}
