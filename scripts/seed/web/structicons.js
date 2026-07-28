/* structicons.js — real structure markers on the lightbox map.
 *
 * What this replaces: a pre-rendered transparent PNG of grey boxes
 * (biome_renderer.render_structure_overlay), one per candidate per render
 * geometry, stacked over the map as a second <img>. It cost a full RGBA
 * raster per candidate, it could not be styled or interrogated, and it drew
 * every placement in `structure_all` — INCLUDING the noise-placed sets,
 * whose grid position is fiction. The detail panel already refuses to show
 * those numbers for exactly that reason; the overlay was drawing them as
 * squares on the map anyway.
 *
 * Where the data comes from: the panel itself. score-dimensions.py emits
 * `data-pos` on precisely the rows whose positions are real (grid-placed
 * sets and fixed placements) and `data-struct` on every structure row. So
 * the marker layer needs no new plumbing and can never disagree with the
 * numbers next to it — it is the same rows, drawn spatially.
 *
 * Coordinate model, unchanged and not reinvented: the viewBox is 0-100
 * across the render's full coverage, so a block offset d lands at
 * d/coverage*100 from a centre of 50. window.lbMapCoverage() reports the
 * coverage of the image ACTUALLY on screen (the low-res render until the
 * hi-res probe lands), and window.alignLbOverlay() lays every `.lb-layer`
 * exactly on the <img>. Both live in app.js and both must be called before
 * anything is drawn.
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

  // A village at vanilla spacing inside an 8192 border is ~700 placements,
  // and a dense dimension carries a couple of dozen criteria. Drawing all of
  // it is a grey haze, not a map. Nearest-first per row (data-pos is emitted
  // in distance order), and the button says what was left out rather than
  // pretending the map is complete.
  var PER_ROW = 60
  var TOTAL = 320
  var MARKER = 3.0 // diameter in viewBox units, i.e. % of the map's width
  var PLATE = 12.5 // plate radius; glyphs are drawn in a -10..10 box

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

  function marker(fam, cx, cy, rowIdx) {
    var g = document.createElementNS(NS, 'g')
    g.setAttribute('class', 'sm')
    g.setAttribute('data-row', String(rowIdx))
    g.setAttribute('transform', 'translate(' + cx.toFixed(3) + ' ' + cy.toFixed(3) +
      ') scale(' + (MARKER / (PLATE * 2)).toFixed(5) + ')')
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

  function draw() {
    clear()
    layer.classList.toggle('off', !on)
    btn.setAttribute('aria-pressed', String(on))
    var rows = info.querySelectorAll('.mrow[data-pos]')
    if (!rows.length) {
      // Every structure here is noise-placed, so there is no single
      // position to mark — a whole group shares one field. The control
      // stays put and says so: a missing button reads as a broken
      // feature, and this is a fact about the dimension, not a fault.
      btn.disabled = true
      count.textContent = '0'
      btn.title = 'Nothing to mark: every structure in this dimension is ' +
        'noise-placed, and a noise group has no single position — see the ' +
        'radial histograms in the panel'
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

    var drawn = 0, dropped = 0
    var frag = document.createDocumentFragment()
    Array.prototype.forEach.call(rows, function (row, rowIdx) {
      var fam = familyFor(row.dataset.struct || (row.querySelector('.mname') || {}).textContent)
      var pairs = (row.dataset.pos || '').split(';')
      for (var i = 0; i < pairs.length; i++) {
        var xz = pairs[i].split(',')
        var x = parseFloat(xz[0]), z = parseFloat(xz[1])
        if (!isFinite(x) || !isFinite(z)) continue
        var cx = 50 + (x / coverage) * 100
        var cy = 50 + (z / coverage) * 100
        // Off the render entirely: a pocket dimension's fixed placement can
        // sit outside the area the render covers, and a marker clamped to
        // the edge would claim it is somewhere it is not.
        if (cx < -1 || cx > 101 || cy < -1 || cy > 101) continue
        if (i >= PER_ROW || drawn >= TOTAL) { dropped++; continue }
        frag.appendChild(marker(fam, cx, cy, rowIdx))
        drawn++
      }
    })
    layer.appendChild(frag)
    count.textContent = dropped ? drawn + '+' : String(drawn)
    btn.title = dropped
      ? drawn + ' markers shown, ' + dropped + ' further placements not drawn ' +
        '(nearest ' + PER_ROW + ' per criterion, ' + TOTAL + ' in total)'
      : drawn + ' structure placements on the map'
  }

  // The row is the legend: a marker's icon appears inline in the row that
  // produced it, so no separate legend block competes for panel space. Rows
  // whose positions are not real (noise-placed groups) still get the chip —
  // it says WHICH structure the row is about, which is the other half of the
  // legend's job.
  function decorateRows() {
    Array.prototype.forEach.call(info.querySelectorAll('.mrow[data-struct]'), function (row) {
      var name = row.querySelector('.mname')
      if (!name || name.querySelector('.sm-chip')) return
      var fam = familyFor(row.dataset.struct)
      name.insertAdjacentHTML('afterbegin',
        '<svg class="sm-chip" viewBox="-13.5 -13.5 27 27" aria-hidden="true">' +
        '<circle r="' + PLATE + '" class="sm-plate"></circle>' +
        '<path class="sm-glyph" fill-rule="evenodd" fill="' + fam.fill +
        '" d="' + fam.d + '"></path></svg>')
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
    var row = e.target.closest('.mrow[data-pos]')
    if (!row) return
    var rows = Array.prototype.slice.call(info.querySelectorAll('.mrow[data-pos]'))
    var idx = rows.indexOf(row)
    layer.classList.add('focus')
    Array.prototype.forEach.call(layer.querySelectorAll('.sm'), function (g) {
      g.classList.toggle('on', g.dataset.row === String(idx))
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
