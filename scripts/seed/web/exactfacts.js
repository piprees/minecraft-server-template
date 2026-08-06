/* exactfacts.js — exact structure facts from the census sidecar.
 *
 * Fetches GET /census/<dim>/<seed> and renders:
 *   1. Exact structure markers at true block positions on the map (reusing
 *      the same coordinate transform as structicons.js / dartboard.js).
 *   2. A facts panel listing every structure with its exact count and
 *      nearest distance from the banked spawn, plus provenance labels.
 *
 * The census sidecar carries every position the noise placement assigned,
 * with a resolved structure id per site. Unlike /noise-census (which
 * computes positions on demand and samples them for the map), this endpoint
 * serves the pre-computed sidecar written during scoring — the same data
 * the scorer used, so the facts are authoritative.
 *
 * Provenance labels: "census sidecar (exact)" for facts derived from the
 * sidecar, "banked measurement" for spawn/biome data from the candidate
 * store, "not exactly measurable" when no sidecar exists.
 */
;(function () {
  if (location.protocol === 'file:') return
  var NS = 'http://www.w3.org/2000/svg'
  var lb = document.getElementById('lightbox')
  if (!lb) return
  var info = lb.querySelector('.lb-info')
  var imageBox = lb.querySelector('.lb-image')
  if (!info || !imageBox) return

  var cache = {}
  var pending = {}

  function cacheKey(c) { return c.dim + '/' + c.seed }

  function fetchFacts(c, then) {
    var key = cacheKey(c)
    if (cache[key]) { then(cache[key]); return }
    if (pending[key]) return
    pending[key] = true
    var slug = c.dim.replace(/_/g, '-')
    fetch('/census/' + encodeURIComponent(slug) + '/' + encodeURIComponent(c.seed))
      .then(function (r) { return r.json() })
      .then(function (d) {
        delete pending[key]
        cache[key] = d && d.ok ? d : { ok: false, error: (d && d.error) || 'unavailable' }
        then(cache[key])
      })
      .catch(function () {
        delete pending[key]
        cache[key] = { ok: false, error: 'exact positions require the viewer server (./dev seed-viewer)' }
        then(cache[key])
      })
  }

  // The marker layer sits alongside #lb-markers from structicons.js.
  var layer = document.createElementNS(NS, 'svg')
  layer.setAttribute('id', 'lb-exact-markers')
  layer.setAttribute('class', 'lb-layer')
  layer.setAttribute('viewBox', '0 0 100 100')
  layer.setAttribute('preserveAspectRatio', 'none')
  layer.setAttribute('aria-hidden', 'true')
  var overlay = document.getElementById('lb-overlay')
  imageBox.insertBefore(layer, overlay || imageBox.firstChild)

  var MARKER = 2.0
  var DISPLAY_CAP = 5000
  var familyFor = window.structIconFamily || function () {
    return { fill: 'rgb(128,128,128)', d: 'M0 -8.5 L6.5 0 L0 8.5 L-6.5 0 Z M-2 -2 h4 v4 h-4 Z' }
  }

  function clearLayer() { while (layer.firstChild) layer.removeChild(layer.firstChild) }

  function markerNote() {
    return imageBox.parentNode.querySelector('.ef-marker-note')
  }

  function drawMarkers(data) {
    clearLayer()
    if (!data || !data.ok || !data.groups) {
      var stale_note = markerNote()
      if (stale_note) stale_note.textContent = ''
      return
    }
    if (window.alignLbOverlay) window.alignLbOverlay()
    var coverage = window.lbMapCoverage ? window.lbMapCoverage() : 0
    if (!coverage) return
    var mapImg = imageBox.querySelector('img')
    if (!mapImg || !mapImg.getBoundingClientRect().width) return

    var frag = document.createDocumentFragment()
    var drawn = 0
    var capped = false
    var total = data.totalPositions || 0

    Object.keys(data.groups).forEach(function (group) {
      var entry = data.groups[group]
      if (!entry || !entry.positions) return
      var positions = entry.positions
      if (positions.length > DISPLAY_CAP) {
        capped = true
        var step = positions.length / DISPLAY_CAP
        var sampled = []
        for (var i = 0; i < DISPLAY_CAP; i++) {
          sampled.push(positions[Math.round(i * step)])
        }
        positions = sampled
      }
      positions.forEach(function (pos) {
        var bx = pos[0], bz = pos[1], sid = pos[2]
        var cx = window.lbProject(bx, coverage)
        var cy = window.lbProject(bz, coverage)
        if (!window.lbOnRender(cx) || !window.lbOnRender(cy)) return
        var fam = sid ? familyFor(sid) : familyFor(group)
        var g = document.createElementNS(NS, 'g')
        g.setAttribute('class', 'ef-marker')
        g.setAttribute('data-group', group)
        g.setAttribute('transform',
          'translate(' + cx.toFixed(3) + ' ' + cy.toFixed(3) + ') scale(' +
          (MARKER / 25).toFixed(5) + ')')
        var plate = document.createElementNS(NS, 'circle')
        plate.setAttribute('r', '12.5')
        plate.setAttribute('class', 'ef-plate')
        g.appendChild(plate)
        var glyph = document.createElementNS(NS, 'path')
        glyph.setAttribute('d', fam.d)
        glyph.setAttribute('fill', fam.fill)
        glyph.setAttribute('fill-rule', 'evenodd')
        glyph.setAttribute('class', 'ef-glyph')
        g.appendChild(glyph)
        frag.appendChild(g)
        drawn++
      })
    })
    layer.appendChild(frag)
    // The marker layer is a display, never a fact: state what it shows.
    // drawn < total is normal (off-map positions are not drawn; huge groups
    // are display-capped) — the counts in the table always come from the
    // full server-side set.
    var note = markerNote() ||
      (function () {
        var p = document.createElement('p')
        p.className = 'ef-marker-note ef-provenance'
        imageBox.parentNode.appendChild(p)
        return p
      })()
    note.textContent = drawn + ' of ' + total + ' positions drawn' +
      (capped ? ' (display-capped; counts use the full set)' : '')
    return { drawn: drawn, total: total, capped: capped }
  }

  function buildFactsPanel(data) {
    if (!data) return ''
    if (!data.ok) {
      return '<div class="ef-panel"><p class="ef-unavailable">' +
        escHtml(data.error || 'Exact positions unavailable') + '</p></div>'
    }
    var parts = []
    parts.push('<div class="ef-panel">')
    parts.push('<h4 class="ef-heading">Exact structure facts')
    if (data.stale) {
      parts.push(' <span class="ef-stale" title="The census sidecar was computed against a different dimension config — rescore to refresh">stale</span>')
    }
    parts.push('</h4>')
    parts.push('<p class="ef-provenance">census sidecar (exact) · ' +
      (data.totalPositions || 0) + ' positions across ' +
      Object.keys(data.groups || {}).length + ' groups</p>')

    // Per-structure table sorted by nearest distance.
    var bs = data.byStructure || {}
    var rows = Object.keys(bs).map(function (sid) {
      return { sid: sid, count: bs[sid].count, nearest: bs[sid].nearestBlocks }
    })
    rows.sort(function (a, b) {
      if (a.nearest < 0 && b.nearest >= 0) return 1
      if (b.nearest < 0 && a.nearest >= 0) return -1
      return a.nearest - b.nearest
    })

    if (rows.length) {
      parts.push('<div class="ef-filter">')
      parts.push('<input type="text" class="ef-search" placeholder="Filter structures…" aria-label="Filter structures">')
      parts.push('</div>')
      parts.push('<table class="ef-table"><thead><tr><th>Structure</th><th>Count</th><th>Nearest</th></tr></thead><tbody>')
      rows.forEach(function (r) {
        var shortName = r.sid.split(':').pop().replace(/_/g, ' ')
        var distStr = r.nearest >= 0 ? Math.round(r.nearest) + 'b' : '—'
        parts.push('<tr data-sid="' + escAttr(r.sid) + '">' +
          '<td class="ef-name" title="' + escAttr(r.sid) + '">' + escHtml(shortName) + '</td>' +
          '<td class="ef-count">' + r.count + '</td>' +
          '<td class="ef-dist">' + distStr + '</td></tr>')
      })
      parts.push('</tbody></table>')
    }

    // Spawn info from banked measurements.
    if (data.spawnX !== undefined) {
      parts.push('<p class="ef-spawn ef-provenance">spawn: ' +
        data.spawnX + ', ' + data.spawnZ +
        ' <span class="ef-prov-label">banked measurement</span></p>')
    }

    parts.push('</div>')
    return parts.join('')
  }

  function escHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  }
  function escAttr(s) {
    return escHtml(s).replace(/"/g, '&quot;')
  }

  function attachFilter(panel) {
    var input = panel.querySelector('.ef-search')
    var rows = panel.querySelectorAll('.ef-table tbody tr')
    if (!input || !rows.length) return
    input.addEventListener('input', function () {
      var q = input.value.toLowerCase()
      rows.forEach(function (tr) {
        var sid = (tr.dataset.sid || '').toLowerCase()
        var name = (tr.querySelector('.ef-name') || {}).textContent || ''
        tr.style.display = (!q || sid.indexOf(q) >= 0 || name.toLowerCase().indexOf(q) >= 0) ? '' : 'none'
      })
    })
  }

  var lastKey = null

  function refresh() {
    var cand = window.lbCandidate ? window.lbCandidate() : null
    if (!cand) {
      lastKey = null
      drawMarkers(null)
      var gone = info.querySelector('.ef-panel')
      if (gone) gone.remove()
      return
    }
    // Inserting the panel mutates the node this file observes; without
    // this guard the observer re-runs its own insertion forever. Same
    // candidate + panel still present = our own mutation, nothing to do.
    var key = cacheKey(cand)
    if (key === lastKey && info.querySelector('.ef-panel')) return
    lastKey = key
    drawMarkers(null)
    var existing = info.querySelector('.ef-panel')
    if (existing) existing.remove()

    fetchFacts(cand, function (data) {
      if (window.lbCandidate) {
        var current = window.lbCandidate()
        if (!current || current.dim !== cand.dim || current.seed !== cand.seed) return
      }
      drawMarkers(data)
      var old = info.querySelector('.ef-panel')
      if (old) old.remove()
      var html = buildFactsPanel(data)
      if (html) {
        info.insertAdjacentHTML('beforeend', html)
        var panel = info.querySelector('.ef-panel')
        if (panel) attachFilter(panel)
      }
    })
  }

  new MutationObserver(refresh).observe(info, { childList: true })
  window.addEventListener('resize', function () {
    var cand = window.lbCandidate ? window.lbCandidate() : null
    drawMarkers(cand ? cache[cacheKey(cand)] : null)
  })
  imageBox.addEventListener('load', function () {
    var cand = window.lbCandidate ? window.lbCandidate() : null
    if (cand && cache[cacheKey(cand)]) drawMarkers(cache[cacheKey(cand)])
  }, true)
})()
