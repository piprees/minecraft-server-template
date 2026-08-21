/* structicons.js — real structure markers on the lightbox map.
 *
 * What this replaces: a pre-rendered transparent PNG of grey boxes
 * (biome_renderer.render_structure_overlay), one per candidate per render
 * geometry, stacked over the map as a second <img>. It cost a full RGBA
 * raster per candidate, it could not be styled or interrogated, and it drew
 * every placement in `structure_all`.
 *
 * NB the old claim here — that noise-placed positions were "fiction" — was
 * true of the Python, which reproduced a vanilla grid it did not own. In the
 * mod, NoiseFieldIndex IS the placement: those positions are the real sites,
 * and the noise-managed groups are most of what a dimension places. The sets
 * to treat carefully are the pass-throughs on their own custom placement
 * types, where we genuinely do not own the answer.
 * `GET /census/<dim>/<seed>` serves the real positions, keyed by group.
 *
 * TWO SOURCES OF POSITIONS, and they are different kinds of fact:
 *
 *   1. `data-pos` on a row — grid-placed sets and fixed placements. Exact
 *      block coordinates, emitted by score-dimensions.py, and true per
 *      STRUCTURE: a marker means "this structure is here".
 *
 *   2. `data-group` on a row plus GET /noise-census — noise-placed sets.
 *      Each position carries a resolved structure id (the pick algorithm
 *      assigns exactly one structure per site, mirrored in both Java and
 *      Python), so a marker is drawn with the STRUCTURE's own glyph via
 *      familyFor(id). Positions without an id fall back to the group glyph.
 *
 * The second source is why this file exists in its current form. On a modern
 * overworld, noise owns every set: the winner's panel carried zero `data-pos`
 * rows, so this layer drew zero markers, while 77 rows offered nothing but
 * `data-band` — and 53 of those bands start at 0, so hovering almost any row
 * painted the same disc from spawn to the border. A map that answers every
 * question identically is not answering any of them.
 *
 * The positions were always computable (noise_placement.noise_census); they
 * are just too big to bank. census_summary deliberately keeps a count and a
 * 10-bin histogram instead, because the largest shipped dimension is 62k
 * positions and the bank is thousands of candidates deep. The viewer looks at
 * ONE candidate, so it can afford to ask for them — see the endpoint's own
 * notes in viewer-server.py, including why the sample is strided rather than
 * nearest-first.
 *
 * Coordinate model, unchanged and not reinvented: the viewBox is 0-100 across
 * the render's full coverage, so a block offset d lands at 50 + d/coverage*100.
 * That sum now lives in ONE place, assets/project.js (window.lbProject), and
 * this file must not grow its own copy. window.lbMapCoverage() reports the
 * coverage of the image ACTUALLY on screen (the low-res render until the
 * hi-res probe lands — never data-coverage on its own), and
 * window.alignLbOverlay() lays every `.lb-layer` exactly on the <img>. Both
 * live in app.js and both must be called before anything is drawn.
 *
 * Icons are inline path data — no font, no sprite, no request of any kind.
 * They map to the ~30 keyword families in biome_renderer.STRUCT_MAT plus a
 * fallback, NOT to the 177 names in dimension_profiles.STRUCTS.
 */
;(function () {
  var NS = 'http://www.w3.org/2000/svg'
  var lb = document.getElementById('lightbox')
  if (!lb) return
  var info = lb.querySelector('.lb-info')
  var imageBox = lb.querySelector('.lb-image')
  var btn = lb.querySelector('.lb-structs-toggle')
  if (!info || !imageBox || !btn) return

  // Glyphs shared by more than one family. A second-tier family often means
  // the same silhouette in a different material — a mega ship is a ship, an
  // illager fort is a fort — so the shape is named once and the colour does
  // the distinguishing.
  var G_SHIP = 'M-9 2 L9 2 L5.5 8 L-5.5 8 Z M-0.8 2 L-0.8 -9 L0.8 -9 L0.8 2 Z'
    + ' M1.6 -8 L8 -4.5 L1.6 -1 Z'
  var G_HOUSE = 'M-9 -0.5 L0 -8.5 L9 -0.5 L9 9 L-9 9 Z M-2 9 L-2 2 L2 2 L2 9 Z'
  var G_RAILS = 'M-8.5 -7 h2.5 v14 h-2.5 Z M6 -7 h2.5 v14 h-2.5 Z'
    + ' M-8.5 -3.5 h17 v2.6 h-17 Z M-8.5 1.5 h17 v2.6 h-17 Z'
  var G_COLUMN = 'M-4 -9 h8 v2.6 h-8 Z M-2.8 -6.4 h5.6 v12.8 h-5.6 Z'
    + ' M-5 6.4 h10 v2.6 h-10 Z'
  var G_GRAVE = 'M-6 9 L-6 -2.5 A6 6 0 0 1 6 -2.5 L6 9 Z'
    + ' M-3.4 -1.6 h6.8 v1.9 h-6.8 Z M-3.4 1.9 h6.8 v1.9 h-6.8 Z'
  var G_ARENA = 'M0 -7.5 A10 7.5 0 1 0 0.01 -7.5 Z M0 -3.4 A5.4 4 0 1 1 -0.01 -3.4 Z'
  var G_CHALICE = 'M-6.5 -8 L6.5 -8 L4.2 -1.5 L-4.2 -1.5 Z M-1.3 -1.5 h2.6 v6 h-2.6 Z'
    + ' M-6 4.5 h12 v3.5 h-12 Z'
  var G_MUSHROOM = 'M-9.5 -0.5 A9.5 8 0 0 1 9.5 -0.5 Z M-2.4 -0.5 h4.8 v9.5 h-4.8 Z'

  // Keyword families, in MATCH ORDER — the first whose keyword appears in the
  // structure name wins. Order is explicit and specific-first on purpose:
  // STRUCT_MAT is a dict matched by substring, so `jungle_temple` hit plain
  // `temple` and never got its own colour. Colours are STRUCT_MAT's material
  // triples verbatim (see lift() for why they are not used raw).
  //
  // Glyphs are drawn in a -10..10 box, fill-rule evenodd, so a second
  // subpath inside the first reads as a hole (a doorway, a window, a slot).
  var FAMILIES = [
    { k: 'ancient_city', c: [20, 20, 30],
      d: 'M-5 9 L-5 -5 L0 -9 L5 -5 L5 9 Z M-2 -3 L2 -3 L2 3 L-2 3 Z' },
    { k: 'desert_pyramid', c: [210, 195, 145],
      d: 'M0 -9 L8.5 6.5 L-8.5 6.5 Z M-10 6.5 L10 6.5 L10 9 L-10 9 Z' },
    { k: 'jungle_temple', c: [80, 90, 50],
      d: 'M-9 9 L-9 5 L-6 5 L-6 1 L-3 1 L-3 -3 L3 -3 L3 1 L6 1 L6 5 L9 5 L9 9 Z'
       + ' M-2 9 L-2 3 L2 3 L2 9 Z' },
    { k: 'ruined_portal', c: [40, 10, 40],
      d: 'M-7.5 9 L-7.5 -2 A7.5 7.5 0 0 1 7.5 -2 L7.5 9 L3.6 9 L3.6 -2'
       + ' A3.9 3.9 0 0 0 -3.6 -2 L-3.6 9 Z' },
    { k: 'trail_ruins', c: [140, 100, 60],
      d: 'M-9 3 h5 v5 h-5 Z M-2 1 h5.5 v7 h-5.5 Z M5.5 4 h3.5 v4 h-3.5 Z'
       + ' M-7 -4 h4.5 v3.5 h-4.5 Z M1 -6.5 h4 v3.5 h-4 Z' },
    { k: 'ocean_ruin', c: [70, 100, 80],
      d: 'M-8 9 L-8 -6 L-3.2 -6 L-3.2 9 Z M1.5 9 L1.5 -1 L6.3 -1 L6.3 9 Z'
       + ' M-9 -8.4 L3 -8.4 L3 -6 L-9 -6 Z' },
    { k: 'end_city', c: [200, 160, 200],
      d: 'M-3 9 L-3 -3 L-6.5 -3 L-6.5 -5.5 L-2 -9 L2 -9 L6.5 -5.5 L6.5 -3 L3 -3 L3 9 Z' },
    { k: 'mineshaft', c: [100, 72, 36],
      d: 'M-8.5 -7 h2.5 v14 h-2.5 Z M6 -7 h2.5 v14 h-2.5 Z'
       + ' M-8.5 -3.5 h17 v2.6 h-17 Z M-8.5 1.5 h17 v2.6 h-17 Z' },
    { k: 'shipwreck', c: [90, 60, 30],
      d: 'M-9 2 L9 2 L5.5 8 L-5.5 8 Z M-0.8 2 L-0.8 -9 L0.8 -9 L0.8 2 Z'
       + ' M1.6 -8 L8 -4.5 L1.6 -1 Z' },
    { k: 'stronghold', c: [80, 80, 80],
      d: 'M-8.5 0 A8.5 8.5 0 1 0 8.5 0 A8.5 8.5 0 1 0 -8.5 0 Z'
       + ' M-4.2 0 A4.2 4.2 0 1 1 4.2 0 A4.2 4.2 0 1 1 -4.2 0 Z' },
    { k: 'monument', c: [70, 130, 130],
      d: 'M0 -9 L9 0 L0 9 L-9 0 Z M0 -4 L4 0 L0 4 L-4 0 Z' },
    { k: 'mansion', c: [90, 60, 30],
      d: 'M-10 9 L-10 -5 L10 -5 L10 9 Z M-10 -5 L0 -9.5 L10 -5 Z'
       + ' M-6.5 -1.5 h3 v3 h-3 Z M-1.5 -1.5 h3 v3 h-3 Z M3.5 -1.5 h3 v3 h-3 Z'
       + ' M-1.8 9 L-1.8 3.5 L1.8 3.5 L1.8 9 Z' },
    { k: 'fortress', c: [55, 10, 10],
      d: 'M-10 9 L-10 -3 L-7.2 -3 L-7.2 -6.5 L-4.4 -6.5 L-4.4 -3 L-1.4 -3'
       + ' L-1.4 -6.5 L1.4 -6.5 L1.4 -3 L4.4 -3 L4.4 -6.5 L7.2 -6.5 L7.2 -3'
       + ' L10 -3 L10 9 Z M-2 9 L-2 2 A2 2 0 0 1 2 2 L2 9 Z' },
    { k: 'bastion', c: [30, 30, 30],
      d: 'M-9 -6.5 L9 -6.5 L9 1.5 L5.5 1.5 L5.5 9 L-5.5 9 L-5.5 1.5 L-9 1.5 Z' },
    { k: 'pillager', c: [60, 60, 60],
      d: 'M-4.2 9 L-4.2 -9 L-2.6 -9 L-2.6 9 Z'
       + ' M-2.6 -8 L6.5 -8 L4.4 -4.5 L6.5 -1 L-2.6 -1 Z' },
    { k: 'outpost', c: [100, 72, 36],
      d: 'M-4.5 9 L-3 -1.5 L3 -1.5 L4.5 9 Z'
       + ' M-6.5 -1.5 L6.5 -1.5 L6.5 -5 L4 -5 L4 -3.2 L1.3 -3.2 L1.3 -5'
       + ' L-1.3 -5 L-1.3 -3.2 L-4 -3.2 L-4 -5 L-6.5 -5 Z' },
    { k: 'village', c: [104, 104, 104],
      d: 'M-9 -0.5 L0 -8.5 L9 -0.5 L9 9 L-9 9 Z M-2 9 L-2 2 L2 2 L2 9 Z' },
    { k: 'treasure', c: [180, 170, 140],
      d: 'M-9 -3 L-9 8.5 L9 8.5 L9 -3 Z M-9 -3 L-7 -7.5 L7 -7.5 L9 -3 Z'
       + ' M-1.5 -0.5 h3 v4 h-3 Z' },
    { k: 'sanctum', c: [100, 60, 60],
      d: 'M-6.5 -8 L6.5 -8 L4.2 -1.5 L-4.2 -1.5 Z M-1.3 -1.5 h2.6 v6 h-2.6 Z'
       + ' M-6 4.5 h12 v3.5 h-12 Z' },
    { k: 'citadel', c: [80, 60, 40],
      d: 'M-10 9 L-10 -7.5 L-5.5 -7.5 L-5.5 -4 L-4 -4 L-4 -1 L4 -1 L4 -4'
       + ' L5.5 -4 L5.5 -7.5 L10 -7.5 L10 9 Z M-1.8 9 L-1.8 3 L1.8 3 L1.8 9 Z' },
    { k: 'dungeon', c: [80, 80, 80],
      d: 'M-8 9 L-8 -6 L8 -6 L8 9 Z M-4.6 -3 h1.8 v9 h-1.8 Z'
       + ' M-0.9 -3 h1.8 v9 h-1.8 Z M2.8 -3 h1.8 v9 h-1.8 Z' },
    { k: 'temple', c: [180, 170, 140],
      d: 'M-9.5 9 L-9.5 5 L-6.5 5 L-6.5 1 L-3.5 1 L-3.5 -3 L3.5 -3 L3.5 1'
       + ' L6.5 1 L6.5 5 L9.5 5 L9.5 9 Z' },
    { k: 'shrine', c: [130, 110, 80],
      d: 'M-9.5 -7.5 h19 v2.4 h-19 Z M-8 -4 h16 v2.2 h-16 Z'
       + ' M-5.2 -4 h2.6 v13 h-2.6 Z M2.6 -4 h2.6 v13 h-2.6 Z' },
    { k: 'tower', c: [104, 104, 104],
      d: 'M-5.4 9 L-5.4 -4.5 L-7 -4.5 L-7 -8.6 L-4.2 -8.6 L-4.2 -6.4 L-1.4 -6.4'
       + ' L-1.4 -8.6 L1.4 -8.6 L1.4 -6.4 L4.2 -6.4 L4.2 -8.6 L7 -8.6'
       + ' L7 -4.5 L5.4 -4.5 L5.4 9 Z M-1.4 -2 h2.8 v4.4 h-2.8 Z' },
    { k: 'witch', c: [60, 80, 30],
      d: 'M0 -9.5 L5.5 3 L-5.5 3 Z M-8.5 3 L8.5 3 L8.5 6.5 L-8.5 6.5 Z' },
    { k: 'igloo', c: [220, 220, 220],
      d: 'M-9 7.5 A9 9 0 0 1 9 7.5 Z M-3 7.5 L-3 2.5 A3 3 0 0 1 3 2.5 L3 7.5 Z' },
    { k: 'camp', c: [100, 72, 36],
      d: 'M0 -8.5 L8.5 8.5 L-8.5 8.5 Z M0 -1.5 L3.2 8.5 L-3.2 8.5 Z' },
    { k: 'keep', c: [50, 50, 50],
      d: 'M-7 9 L-7 -5.5 L-8.2 -5.5 L-8.2 -8.5 L-5.4 -8.5 L-5.4 -6.8 L-2.6 -6.8'
       + ' L-2.6 -8.5 L2.6 -8.5 L2.6 -6.8 L5.4 -6.8 L5.4 -8.5 L8.2 -8.5'
       + ' L8.2 -5.5 L7 -5.5 L7 9 Z M-1.8 9 L-1.8 2.5 L1.8 2.5 L1.8 9 Z' },
    { k: 'vault', c: [60, 50, 50],
      d: 'M0 -9 L8.5 -6 L8.5 1 C8.5 6.5 4.5 8.8 0 9.5 C-4.5 8.8 -8.5 6.5 -8.5 1'
       + ' L-8.5 -6 Z M-2.2 -3.4 h4.4 v7 h-4.4 Z' },
    { k: 'ruin', c: [90, 90, 80],
      d: 'M-9.5 9 L-9.5 -1 L-4 -1 L-4 -7 L1 -7 L1 1.5 L5 1.5 L5 -3'
       + ' L9.5 -3 L9.5 9 Z' },

    // --- second tier -------------------------------------------------
    // Appended, never interleaved: every keyword above still wins, so no
    // structure that had an icon loses or changes it. These cover the
    // largest clusters of the 77 short names that were falling through to
    // the generic diamond — the Dungeons Arise flagships, the crypt and
    // ship families, and the nether/end furniture.
    { k: 'lighthouse', c: [200, 200, 190],   // before `house`
      d: 'M-3 9 L-2 -3 L2 -3 L3 9 Z M-4.5 -3 h9 v-2.2 h-9 Z'
       + ' M-2.6 -5.2 L-2.6 -8.6 L2.6 -8.6 L2.6 -5.2 Z'
       + ' M-9 -8 L-3.4 -6.9 L-3.4 -4.9 L-9 -3.8 Z'
       + ' M9 -8 L3.4 -6.9 L3.4 -4.9 L9 -3.8 Z' },
    { k: 'crypt', c: [70, 70, 78], d: G_GRAVE },
    { k: 'tomb', c: [120, 105, 85], d: G_GRAVE },
    { k: 'graveyard', c: [95, 95, 88], d: G_GRAVE },
    { k: 'mausoleum', c: [150, 148, 140], d: G_GRAVE },
    { k: 'coliseum', c: [190, 178, 150], d: G_ARENA },
    { k: 'arena', c: [190, 178, 150], d: G_ARENA },
    { k: 'palace', c: [190, 150, 60],
      d: 'M-10 9 L-10 -2 A10 10 0 0 1 10 -2 L10 9 Z M-1.9 9 L-1.9 1.5'
       + ' A1.9 1.9 0 0 1 1.9 1.5 L1.9 9 Z M-0.8 -12 h1.6 v3.4 h-1.6 Z' },
    { k: 'fort', c: [70, 66, 62],        // before `illager`: a fort first
      d: 'M-9.5 9 L-9.5 -5 L-7 -5 L-7 -7.6 L-4.5 -7.6 L-4.5 -5 L-2.4 -5'
       + ' L-2.4 -7.6 L2.4 -7.6 L2.4 -5 L4.5 -5 L4.5 -7.6 L7 -7.6 L7 -5'
       + ' L9.5 -5 L9.5 9 Z M-2 9 L-2 2 L2 2 L2 9 Z' },
    { k: 'illager', c: [55, 58, 52],
      d: 'M-4.2 9 L-4.2 -9 L-2.6 -9 L-2.6 9 Z'
       + ' M-2.6 -8 L6.5 -8 L4.4 -4.5 L6.5 -1 L-2.6 -1 Z' },
    { k: 'forge', c: [150, 60, 30],
      d: 'M-9 1.5 L-4.5 -2 L4.5 -2 L9 1.5 L9 4 L-9 4 Z M-9 6 h18 v3 h-18 Z'
       + ' M-1.4 -2 L-1.4 -9 L1.4 -9 L1.4 -2 Z' },
    { k: 'foundry', c: [150, 60, 30],
      d: 'M-9 1.5 L-4.5 -2 L4.5 -2 L9 1.5 L9 4 L-9 4 Z M-9 6 h18 v3 h-18 Z'
       + ' M-1.4 -2 L-1.4 -9 L1.4 -9 L1.4 -2 Z' },
    { k: 'mine', c: [110, 82, 46], d: G_RAILS },   // mines, mining_complex
    { k: 'pillar', c: [160, 158, 150], d: G_COLUMN },
    { k: 'spire', c: [150, 130, 190],
      d: 'M0 -9.5 L3.4 -1 L2 -1 L4.6 9 L-4.6 9 L-2 -1 L-3.4 -1 Z' },
    { k: 'monolith', c: [45, 45, 58],
      d: 'M-3.6 9 L-3.6 -8 L3.6 -9.5 L3.6 9 Z' },
    { k: 'ship', c: [80, 62, 44], d: G_SHIP },     // after `shipwreck`
    { k: 'house', c: [120, 96, 62], d: G_HOUSE },
    { k: 'hut', c: [96, 78, 50], d: G_HOUSE },
    { k: 'farm', c: [140, 160, 70], d: G_HOUSE },
    { k: 'settlement', c: [104, 104, 104], d: G_HOUSE },
    { k: 'altar', c: [130, 70, 90], d: G_CHALICE },
    { k: 'chapel', c: [150, 140, 120], d: G_CHALICE },
    { k: 'castle', c: [90, 84, 78],
      d: 'M-10 9 L-10 -4 L-10 -8 L-6 -8 L-6 -4 L-2 -4 L-2 -8 L2 -8 L2 -4'
       + ' L6 -4 L6 -8 L10 -8 L10 9 Z M-2 9 L-2 2 A2 2 0 0 1 2 2 L2 9 Z' },
    { k: 'skull', c: [220, 214, 200],
      d: 'M0 -9 A8.5 8 0 0 1 8 3 L5.5 9 L-5.5 9 L-8 3 A8.5 8 0 0 1 0 -9 Z'
       + ' M-4.4 -2.2 A2.4 2.4 0 1 1 -4.39 -2.2 Z'
       + ' M4.4 -2.2 A2.4 2.4 0 1 1 4.41 -2.2 Z' },
    { k: 'bridge', c: [110, 60, 60],
      d: 'M-10 -2 h20 v2.6 h-20 Z M-9 0.6 h2.6 v8.4 h-2.6 Z'
       + ' M6.4 0.6 h2.6 v8.4 h-2.6 Z M-1.3 0.6 h2.6 v8.4 h-2.6 Z' },
    { k: 'well', c: [130, 125, 115],
      d: 'M-6.5 1 h13 v8 h-13 Z M-8 -1.6 h16 v2.6 h-16 Z'
       + ' M-5.5 -8.6 L0 -5.4 L5.5 -8.6 L5.5 -6 L0 -2.8 L-5.5 -6 Z' },
    { k: 'post', c: [120, 96, 62],       // guide_post_warm / _cold
      d: 'M-1.2 9 L-1.2 -9 L1.2 -9 L1.2 9 Z M1.2 -7.4 h7.4 v3.4 h-7.4 Z'
       + ' M-8.6 -2.6 h7.4 v3.4 h-7.4 Z' },
    { k: 'garden', c: [110, 175, 80],
      d: 'M-1.2 9 h2.4 v-11 h-2.4 Z'
       + ' M-1.6 -1 A7.5 7.5 0 0 1 -9 -8.4 A7.5 7.5 0 0 1 -1.6 -1 Z'
       + ' M1.6 -3.5 A7.5 7.5 0 0 0 9 -10.9 A7.5 7.5 0 0 0 1.6 -3.5 Z' },
    { k: 'nest', c: [120, 100, 70],
      d: 'M-9.5 0 A9.5 6.5 0 0 0 9.5 0 Z M-9.5 0 h19 v-2 h-19 Z'
       + ' M-4 -5.5 A2.6 2.6 0 1 1 -3.99 -5.5 Z M3.4 -4.6 A2.6 2.6 0 1 1 3.41 -4.6 Z' },
    { k: 'crashed', c: [80, 62, 44], d: G_SHIP },
    { k: 'remains', c: [140, 136, 125], d: G_COLUMN },
    { k: 'hall', c: [100, 70, 45],
      d: 'M-10 9 L-10 -3 L0 -9 L10 -3 L10 9 Z M-6 -0.5 h3 v3 h-3 Z'
       + ' M-1.5 -0.5 h3 v3 h-3 Z M3 -0.5 h3 v3 h-3 Z M-1.8 9 L-1.8 4 L1.8 4 L1.8 9 Z' },
    { k: 'trial', c: [90, 120, 130],      // trial_chambers, trident_trial
      d: 'M-9 9 L-9 -6 L9 -6 L9 9 Z M-5 -2.6 h3.2 v3.2 h-3.2 Z'
       + ' M1.8 -2.6 h3.2 v3.2 h-3.2 Z M-2 9 L-2 3 L2 3 L2 9 Z' },
    { k: 'mushroom', c: [180, 100, 180], d: G_MUSHROOM },
    { k: 'fungus', c: [160, 40, 60], d: G_MUSHROOM },
    { k: 'mining', c: [110, 82, 46], d: G_RAILS },
    { k: 'tavern', c: [140, 100, 55], d: G_HOUSE },
    { k: 'manor', c: [90, 60, 30],
      d: 'M-10 9 L-10 -5 L10 -5 L10 9 Z M-10 -5 L0 -9.5 L10 -5 Z'
       + ' M-6.5 -1.5 h3 v3 h-3 Z M-1.5 -1.5 h3 v3 h-3 Z M3.5 -1.5 h3 v3 h-3 Z'
       + ' M-1.8 9 L-1.8 3.5 L1.8 3.5 L1.8 9 Z' },
    { k: 'pyramid', c: [200, 180, 120],
      d: 'M0 -9 L8.5 6.5 L-8.5 6.5 Z M-10 6.5 L10 6.5 L10 9 L-10 9 Z' },
    { k: 'archway', c: [150, 140, 190],
      d: 'M-8 9 L-8 -2 A8 8 0 0 1 8 -2 L8 9 L4 9 L4 -2 A4 4 0 0 0 -4 -2 L-4 9 Z' },
    { k: 'cache', c: [180, 170, 140],
      d: 'M-9 -3 L-9 8.5 L9 8.5 L9 -3 Z M-9 -3 L-7 -7.5 L7 -7.5 L9 -3 Z'
       + ' M-1.5 -0.5 h3 v4 h-3 Z' },
    { k: 'outcast', c: [110, 96, 70], d: G_HOUSE },
    { k: 'volcano', c: [170, 60, 30],
      d: 'M-10 9 L-3.4 -6 L3.4 -6 L10 9 Z M-3 -6 L-1.4 -9.5 L1.4 -9.5 L3 -6 Z' },
    { k: 'heavenly', c: [200, 195, 225], d: G_SHIP },
    { k: 'skeleton', c: [225, 222, 210],
      d: 'M-1.3 -9 h2.6 v18 h-2.6 Z M-8 -5.5 h16 v2.2 h-16 Z'
       + ' M-6.5 -0.5 h13 v2.2 h-13 Z M-5 4.5 h10 v2.2 h-10 Z' },
  ]
  // Anything the 30 families do not name. A marker still has to appear —
  // "there is a structure here and I have no icon for it" is information;
  // silently dropping it is not.
  var FALLBACK = { k: '', c: [128, 128, 128],
    d: 'M0 -8.5 L6.5 0 L0 8.5 L-6.5 0 Z M-2 -2 h4 v4 h-4 Z' }

  // STRUCT_MAT is a MATERIAL palette, chosen for a filled box on a bright
  // biome render. A third of it (blackstone 30,30,30 · nether brick 55,10,10
  // · deepslate 20,20,30) is invisible on a nether or end map, which is
  // exactly where those structures live. Scale each triple up until it
  // clears a readable luminance — multiplying preserves the hue, so the
  // material is still recognisable — and only mix toward white for the ones
  // multiplication cannot rescue. Colours that are already bright (snow,
  // purpur, sandstone) come through untouched.
  function lift(rgb) {
    var r = rgb[0], g = rgb[1], b = rgb[2]
    var TARGET = 150
    function luma() { return 0.2126 * r + 0.7152 * g + 0.0722 * b }
    var l = luma()
    if (l > 0 && l < TARGET) {
      var k = Math.min(255 / Math.max(r, g, b, 1), TARGET / l)
      if (k > 1) { r *= k; g *= k; b *= k }
    }
    for (var i = 0; i < 20 && luma() < TARGET; i++) {
      r += (255 - r) * 0.1; g += (255 - g) * 0.1; b += (255 - b) * 0.1
    }
    return 'rgb(' + Math.round(r) + ',' + Math.round(g) + ',' + Math.round(b) + ')'
  }
  FAMILIES.concat([FALLBACK]).forEach(function (f) { f.fill = lift(f.c) })

  function familyFor(name) {
    var n = (name || '').toLowerCase()
    for (var i = 0; i < FAMILIES.length; i++) {
      if (n.indexOf(FAMILIES[i].k) >= 0) return FAMILIES[i]
    }
    return FALLBACK
  }
  window.structIconFamily = familyFor

  // The seven noise GROUPS (structure-type-defaults.json groupDefaults) get
  // their own glyphs, borrowed from the family list above so nothing new has
  // to be drawn. These are the FALLBACK for positions that carry no resolved
  // structure id. When the /noise-census response includes a structure id per
  // position (schemaVersion 2), each marker uses the structure's own glyph
  // via familyFor(id) instead.
  var GROUP_ICON = {
    deco: 'well',
    dungeons: 'dungeon',
    endgame: 'vault',
    landmarks: 'monument',
    loot: 'treasure',
    maritime: 'shipwreck',
    settlements: 'village',
  }
  function familyForGroup(group) {
    var key = GROUP_ICON[(group || '').toLowerCase()]
    return key ? familyFor(key) : familyFor(group)
  }

  // A village at vanilla spacing inside an 8192 border is ~700 placements,
  // and a dense dimension carries a couple of dozen criteria. Drawing all of
  // it is a grey haze, not a map. Nearest-first per row (data-pos is emitted
  // in distance order), and the button says what was left out rather than
  // pretending the map is complete.
  var PER_ROW = 60
  var TOTAL = 320
  var MARKER = 3.0 // diameter in viewBox units, i.e. % of the map's width
  var PLATE = 12.5 // plate radius; glyphs are drawn in a -10..10 box

  // Noise sites are drawn smaller than grid placements, deliberately. There
  // are up to seven groups of them against a handful of grid rows, and the
  // marker says less: "a site from this group", not "this structure". The
  // size difference is the legend for that distinction.
  var GROUP_MARKER = 2.2

  // --- /noise-census ---------------------------------------------------
  //
  // Fetched per (dim, seed), kept for the session. The endpoint caches too,
  // so an arrow-key walk back and forth costs one request per candidate at
  // most; this second cache just stops a resize or a hi-res swap re-asking.
  var censusCache = {}
  var censusPending = {}

  function censusKey(c) { return c.dim + ' ' + c.seed }

  function fetchCensus(c, then) {
    var key = censusKey(c)
    if (censusCache[key]) { then(censusCache[key]); return }
    if (censusPending[key]) return
    censusPending[key] = true
    fetch('/noise-census?dim=' + encodeURIComponent(c.dim) +
          '&seed=' + encodeURIComponent(c.seed))
      .then(function (r) { return r.json() })
      .then(function (d) {
        delete censusPending[key]
        // An error response is cached as well as a good one: retrying a
        // config the server cannot resolve on every resize would hammer it
        // for the whole time the lightbox stays open.
        censusCache[key] = d && d.ok ? d : { ok: false, groups: {},
                                            error: (d && d.error) || 'unavailable' }
        then(censusCache[key])
      })
      .catch(function () {
        delete censusPending[key]
        censusCache[key] = { ok: false, groups: {}, error: 'request failed' }
        then(censusCache[key])
      })
  }

  var layer = document.createElementNS(NS, 'svg')
  layer.setAttribute('id', 'lb-markers')
  layer.setAttribute('class', 'lb-layer')
  layer.setAttribute('viewBox', '0 0 100 100')
  layer.setAttribute('preserveAspectRatio', 'none')
  layer.setAttribute('aria-hidden', 'true')
  // Before #lb-overlay so the hover rings and the dartboard's arcs — which
  // are annotations ON the markers — stay legible over them.
  var overlay = document.getElementById('lb-overlay')
  imageBox.insertBefore(layer, overlay || imageBox.firstChild)

  var on = true
  var count = document.createElement('span')
  count.className = 'sm-count'
  btn.appendChild(count)

  function marker(fam, cx, cy, sel, size, kind) {
    var g = document.createElementNS(NS, 'g')
    g.setAttribute('class', 'sm sm-' + kind)
    // `data-sel` rather than a row index: a noise group is named by several
    // rows at once (every want and shun the field owns, plus its census
    // row), and all of them mean the same sites. Hovering any of them must
    // light up the same markers.
    g.setAttribute('data-sel', sel)
    g.setAttribute('transform', 'translate(' + cx.toFixed(3) + ' ' + cy.toFixed(3) +
      ') scale(' + (size / (PLATE * 2)).toFixed(5) + ')')
    var plate = document.createElementNS(NS, 'circle')
    plate.setAttribute('r', String(PLATE))
    plate.setAttribute('class', 'sm-plate')
    g.appendChild(plate)
    var glyph = document.createElementNS(NS, 'path')
    glyph.setAttribute('d', fam.d)
    glyph.setAttribute('fill', fam.fill)
    glyph.setAttribute('fill-rule', 'evenodd')
    glyph.setAttribute('class', 'sm-glyph')
    g.appendChild(glyph)
    return g
  }

  function clear() { while (layer.firstChild) layer.removeChild(layer.firstChild) }

  /** Distinct noise groups the panel names, in the order they first appear. */
  function panelGroups() {
    var seen = {}, out = []
    Array.prototype.forEach.call(info.querySelectorAll('.mrow[data-group]'),
      function (row) {
        var g = row.dataset.group
        if (g && !seen[g]) { seen[g] = 1; out.push(g) }
      })
    return out
  }

  /** Which markers a row is about: its group, or its own index among the
   *  positional rows. Mirrored by the hover handler. */
  function selectorFor(row, gridRows) {
    if (row.dataset.group) return 'g:' + row.dataset.group
    var idx = Array.prototype.indexOf.call(gridRows, row)
    return idx < 0 ? '' : 'r:' + idx
  }

  function draw() {
    clear()
    layer.classList.toggle('off', !on)
    btn.setAttribute('aria-pressed', String(on))
    var gridRows = info.querySelectorAll('.mrow[data-pos]')
    var groups = panelGroups()
    var cand = window.lbCandidate ? window.lbCandidate() : null
    // Noise positions come from the server, so a file:// open of
    // .seedtest/index.html can only ever draw the positional rows. Say which
    // case it is rather than reporting a smaller number without explanation.
    var canFetch = location.protocol !== 'file:' && !!cand
    if (!gridRows.length && !(groups.length && canFetch)) {
      // Genuinely nothing to mark: no positional rows, and either no noise
      // groups or no way to ask for their sites. The control stays put and
      // says so — a missing button reads as a broken feature.
      btn.disabled = true
      count.textContent = '0'
      btn.title = groups.length
        ? 'Structure sites for the ' + groups.length + ' noise group(s) here ' +
          'need the viewer server — this page was opened as a file, so only ' +
          'grid-placed sets could be marked, and there are none'
        : 'Nothing to mark: this dimension places no structures'
      return
    }
    btn.disabled = false
    if (!on) { count.textContent = '0'; return }
    // Same two calls every consumer of this map makes, in the same order.
    if (window.alignLbOverlay) window.alignLbOverlay()
    var coverage = window.lbMapCoverage ? window.lbMapCoverage() : 0
    // A candidate with no render has no map to mark. alignLbOverlay hides
    // the layer in that case; saying "18 markers" over the placeholder
    // would claim something is on screen that is not.
    var mapImg = imageBox.querySelector('img')
    if (!coverage || !mapImg || !mapImg.getBoundingClientRect().width) {
      count.textContent = '0'
      btn.title = 'No render for this candidate yet, so there is no map to mark'
      return
    }

    var frag = document.createDocumentFragment()
    var drawn = 0, dropped = 0, offmap = 0

    function plot(fam, x, z, sel, size, kind) {
      var cx = window.lbProject(x, coverage)
      var cy = window.lbProject(z, coverage)
      // Off the render entirely: a pocket dimension's fixed placement, and
      // any site in a dimension whose border exceeds the rendered area, sits
      // outside the image. A marker clamped to the edge would claim it is
      // somewhere it is not.
      if (!window.lbOnRender(cx) || !window.lbOnRender(cy)) { offmap++; return }
      frag.appendChild(marker(fam, cx, cy, sel, size, kind))
      drawn++
    }

    // 1. Grid-placed and forced sets — exact, per structure, already in the DOM.
    Array.prototype.forEach.call(gridRows, function (row, rowIdx) {
      var fam = familyFor(row.dataset.struct || (row.querySelector('.mname') || {}).textContent)
      var pairs = (row.dataset.pos || '').split(';')
      for (var i = 0; i < pairs.length; i++) {
        var xz = pairs[i].split(',')
        var x = parseFloat(xz[0]), z = parseFloat(xz[1])
        if (!isFinite(x) || !isFinite(z)) continue
        if (i >= PER_ROW || drawn >= TOTAL) { dropped++; continue }
        plot(fam, x, z, 'r:' + rowIdx, MARKER, 'grid')
      }
    })

    // 2. Noise sites — one strided sample per group, from the server.
    var census = cand ? censusCache[censusKey(cand)] : null
    var sites = 0, siteTotal = 0, censusNote = ''
    if (groups.length && canFetch && !census) {
      censusNote = 'loading structure sites…'
      fetchCensus(cand, function () { draw() })
    } else if (census && census.ok) {
      groups.forEach(function (group) {
        var entry = census.groups[group]
        if (!entry || !entry.pos) return
        var groupFam = familyForGroup(group)
        siteTotal += entry.count
        entry.pos.forEach(function (p) {
          sites++
          // schemaVersion 2: [blockX, blockZ, "ns:id"] — use the structure's
          // own glyph. Legacy [blockX, blockZ] falls back to the group glyph.
          var fam = (p.length >= 3 && p[2]) ? familyFor(p[2]) : groupFam
          plot(fam, p[0], p[1], 'g:' + group, GROUP_MARKER, 'noise')
        })
      })
      if (census.suppressed) censusNote = 'noise placement is off for this dimension'
    } else if (census && !census.ok) {
      censusNote = 'structure sites unavailable: ' + census.error
    }

    layer.appendChild(frag)
    count.textContent = dropped || (siteTotal > sites) ? drawn + '+' : String(drawn)
    var bits = []
    if (drawn - sites > 0) bits.push((drawn - sites) + ' exact placement(s)')
    if (sites) {
      bits.push(sites + ' of ' + siteTotal + ' noise site(s), sampled evenly ' +
        'across the radius so the shape is honest — each site\'s assigned ' +
        'structure is resolved exactly and drawn with its own glyph')
    }
    if (dropped) bits.push(dropped + ' further exact placement(s) not drawn ' +
      '(nearest ' + PER_ROW + ' per criterion, ' + TOTAL + ' in total)')
    if (offmap) bits.push(offmap + ' outside the rendered area')
    if (censusNote) bits.push(censusNote)
    btn.title = bits.length ? bits.join(' · ') : 'Nothing to mark on this render'
  }

  // The row is the legend: a marker's icon appears inline in the row that
  // produced it, so no separate legend block competes for panel space. Rows
  // whose positions are not real (noise-placed groups) still get the chip —
  // it says WHICH structure the row is about, which is the other half of the
  // legend's job.
  function chip(fam, title) {
    return '<svg class="sm-chip" viewBox="-13.5 -13.5 27 27" aria-hidden="true"' +
      (title ? ' title="' + title + '"' : '') + '>' +
      '<circle r="' + PLATE + '" class="sm-plate"></circle>' +
      '<path class="sm-glyph" fill-rule="evenodd" fill="' + fam.fill +
      '" d="' + fam.d + '"></path></svg>'
  }

  function decorateRows() {
    Array.prototype.forEach.call(info.querySelectorAll('.mrow[data-struct]'), function (row) {
      var name = row.querySelector('.mname')
      if (!name || name.querySelector('.sm-chip')) return
      name.insertAdjacentHTML('afterbegin', chip(familyFor(row.dataset.struct)))
    })
    // The full-census rows name a GROUP and no structure. Each site's
    // assigned structure is drawn with its own glyph on the map; the group
    // chip is a fallback label for positions without a resolved id.
    Array.prototype.forEach.call(
      info.querySelectorAll('.mrow[data-group]:not([data-struct])'), function (row) {
        var name = row.querySelector('.mname')
        if (!name || name.querySelector('.sm-chip')) return
        name.insertAdjacentHTML('afterbegin',
          chip(familyForGroup(row.dataset.group),
               row.dataset.group + ' group — sites drawn with their assigned structure'))
      })
  }

  btn.addEventListener('click', function () {
    on = !on
    btn.classList.toggle('on', on)
    draw()
  })

  // Hovering a row picks its own markers out of the crowd. The ring the
  // hover handler draws answers "what radius"; this answers "which ones".
  info.addEventListener('mouseover', function (e) {
    var row = e.target.closest('.mrow[data-pos], .mrow[data-group]')
    if (!row) return
    var sel = selectorFor(row, info.querySelectorAll('.mrow[data-pos]'))
    if (!sel) return
    layer.classList.add('focus')
    Array.prototype.forEach.call(layer.querySelectorAll('.sm'), function (g) {
      g.classList.toggle('on', g.dataset.sel === sel)
    })
  })
  info.addEventListener('mouseleave', function () {
    layer.classList.remove('focus')
  })

  function refresh() { decorateRows(); draw() }

  // The panel is replaced wholesale when the lightbox swaps candidate
  // (arrow keys, clicking another tile), so nothing else would redraw.
  new MutationObserver(refresh).observe(info, { childList: true })
  window.addEventListener('resize', draw)
  // The hi-res swap changes both the laid-out box AND the coverage.
  imageBox.addEventListener('load', draw, true)
  btn.classList.toggle('on', on)
  refresh()
})()
