/* exactfacts.js — the structures panel, and the map selection it drives.
 *
 * Fetches GET /census/<dim>/<seed> and renders the sidebar's structures
 * section: every group the scorer uses, with its count and the distance to
 * its nearest site, and every structure under it with the same two numbers.
 *
 * The sidebar is the control and the map follows it. Nothing is pinned until
 * something is chosen, because a dimension can carry thousands of sites and
 * drawing them all at once is an opaque blob that hides the map. Choosing a
 * group draws that group; choosing a structure draws that structure.
 *
 * Every number here is exact. Positions and their assigned structure ids come
 * from the same noise placement and weighted pick the live world generates
 * from, and counts come from the banked measurement.
 */
;(function () {
  if (location.protocol === 'file:') return
  var NS = 'http://www.w3.org/2000/svg'
  var lb = document.getElementById('lightbox')
  if (!lb) return
  var info = lb.querySelector('.lb-info')
  var imageBox = lb.querySelector('.lb-image')
  if (!info || !imageBox) return

  // Report order. deco is last and never opens expanded: 2075 of one
  // dimension's 3007 sites were deco, and it is scenery.
  var GROUP_ORDER = ['landmarks', 'settlements', 'dungeons', 'endgame',
    'maritime', 'loot', 'deco']
  var NEVER_SUGGEST = 'deco'
  var MARKER = 2.0

  var cache = {}
  var pending = {}
  var selection = null       // {kind:'group'|'structure', key:string}
  var lastKey = null

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
        cache[key] = { ok: false, error: 'the viewer server is not answering' }
        then(cache[key])
      })
  }

  var layer = document.createElementNS(NS, 'svg')
  layer.setAttribute('id', 'lb-exact-markers')
  layer.setAttribute('class', 'lb-layer')
  layer.setAttribute('viewBox', '0 0 100 100')
  layer.setAttribute('preserveAspectRatio', 'none')
  var overlay = document.getElementById('lb-overlay')
  imageBox.insertBefore(layer, overlay || imageBox.firstChild)

  var familyFor = window.structIconFamily || function () {
    return { fill: 'rgb(148,163,184)', d: 'M0 -8.5 L6.5 0 L0 8.5 L-6.5 0 Z' }
  }

  /**
   * The world column the render is centred on. The thumbnail is centred on
   * spawn and the detail view on the origin, so a marker drawn against the
   * wrong one is offset by the whole spawn column — plausible, and wrong.
   * Told apart by the same `_hires` test lbMapCoverage uses.
   */
  function centre(data) {
    var img = imageBox.querySelector('img')
    var src = ((img && img.getAttribute('src')) || '').split('?')[0]
    if (/_hires\.png$/.test(src)) return { x: 0, z: 0 }
    return { x: data.spawnX || 0, z: data.spawnZ || 0 }
  }

  function distance(data, x, z) {
    var dx = x - (data.spawnX || 0)
    var dz = z - (data.spawnZ || 0)
    return Math.sqrt(dx * dx + dz * dz)
  }

  /** The sites the current selection asks for, as {x, z, sid, group}. */
  function selectedSites(data) {
    if (!selection || !data || !data.groups) return []
    var out = []
    Object.keys(data.groups).forEach(function (group) {
      var entry = data.groups[group]
      if (!entry || !entry.positions) return
      if (selection.kind === 'group' && group !== selection.key) return
      entry.positions.forEach(function (p) {
        if (selection.kind === 'structure' && p[2] !== selection.key) return
        out.push({ x: p[0], z: p[1], sid: p[2], group: group })
      })
    })
    return out
  }

  function clearLayer() { while (layer.firstChild) layer.removeChild(layer.firstChild) }

  function drawMarkers(data) {
    clearLayer()
    if (!data || !data.ok || !selection) return
    if (window.alignLbOverlay) window.alignLbOverlay()
    var coverage = window.lbMapCoverage ? window.lbMapCoverage() : 0
    if (!coverage) return
    var img = imageBox.querySelector('img')
    if (!img || !img.getBoundingClientRect().width) return

    var mid = centre(data)
    var sites = selectedSites(data)
    var frag = document.createDocumentFragment()
    var drawn = 0
    sites.forEach(function (s) {
      var cx = window.lbProject(s.x - mid.x, coverage)
      var cy = window.lbProject(s.z - mid.z, coverage)
      if (!window.lbOnRender(cx) || !window.lbOnRender(cy)) return
      var fam = familyFor(s.sid || s.group)
      var g = document.createElementNS(NS, 'g')
      g.setAttribute('class', 'ef-marker')
      g.setAttribute('data-sid', s.sid || '')
      g.setAttribute('data-group', s.group)
      g.setAttribute('transform', 'translate(' + cx.toFixed(3) + ' ' + cy.toFixed(3) +
        ') scale(' + (MARKER / 25).toFixed(5) + ')')
      var plate = document.createElementNS(NS, 'circle')
      plate.setAttribute('r', '12.5')
      plate.setAttribute('class', 'ef-plate')
      g.appendChild(plate)
      var glyph = document.createElementNS(NS, 'path')
      glyph.setAttribute('d', fam.d)
      glyph.setAttribute('fill', fam.fill)
      glyph.setAttribute('fill-rule', 'evenodd')
      g.appendChild(glyph)
      var title = document.createElementNS(NS, 'title')
      title.textContent = shortName(s.sid || s.group) + ' — ' +
        Math.round(distance(data, s.x, s.z)) + ' blocks from spawn'
      g.appendChild(title)
      frag.appendChild(g)
      drawn++
    })
    layer.appendChild(frag)
    note(drawn, sites.length)
  }

  function note(drawn, total) {
    var el = imageBox.querySelector('.ef-marker-note')
    if (!el) {
      el = document.createElement('p')
      el.className = 'ef-marker-note'
      imageBox.appendChild(el)
    }
    // Fewer drawn than selected is normal — a site outside the rendered area
    // is dropped rather than clamped to the edge, which would put it
    // somewhere it is not. But NONE drawn while sites exist reads as a broken
    // selection, so that case says where they went and how to see them.
    el.classList.toggle('ef-note-loud', drawn === 0 && total > 0)
    if (drawn === 0 && total > 0) {
      el.textContent = 'all ' + total + ' sites are outside this view — switch to the '
        + 'whole-world map to see them'
    } else {
      el.textContent = drawn === total
        ? drawn + ' sites'
        : drawn + ' of ' + total + ' sites on this view'
    }
  }

  // -------------------------------------------------------------- the panel

  function shortName(id) {
    return String(id || '').split(':').pop().replace(/_/g, ' ')
  }

  function dist(v) {
    return (v === undefined || v === null || v < 0) ? '—' : Math.round(v) + 'b'
  }

  /** Structures present in a group, with their exact count and nearest. */
  function structuresIn(data, group) {
    var entry = (data.groups || {})[group]
    if (!entry || !entry.positions) return []
    var counts = {}
    var nearest = {}
    entry.positions.forEach(function (p) {
      var sid = p[2]
      if (!sid) return
      counts[sid] = (counts[sid] || 0) + 1
      var d = distance(data, p[0], p[1])
      if (nearest[sid] === undefined || d < nearest[sid]) nearest[sid] = d
    })
    return Object.keys(counts).map(function (sid) {
      return { sid: sid, count: counts[sid], nearest: nearest[sid] }
    }).sort(function (a, b) { return a.nearest - b.nearest })
  }

  function buildPanel(data) {
    if (!data) return ''
    if (!data.ok) {
      return '<div class="ef-panel"><h4 class="ef-heading">Structures</h4>' +
        '<p class="ef-unavailable">' + escHtml(data.error || 'unavailable') + '</p></div>'
    }
    var groups = data.groups || {}
    var present = GROUP_ORDER.filter(function (g) { return groups[g] })
      .concat(Object.keys(groups).filter(function (g) {
        return GROUP_ORDER.indexOf(g) < 0
      }))

    var parts = ['<div class="ef-panel">']
    parts.push('<h4 class="ef-heading">Structures</h4>')
    parts.push('<p class="ef-provenance">' + (data.totalPositions || 0) +
      ' sites across ' + present.length + ' groups · choose one to draw it</p>')

    present.forEach(function (group) {
      var entry = groups[group]
      var count = (data.byGroup || {})[group]
      if (count === undefined) count = (entry.positions || []).length
      var muted = group === NEVER_SUGGEST ? ' ef-muted' : ''
      var hostile = entry.hostile ? ' ef-hostile' : ''
      parts.push('<div class="ef-group' + muted + hostile + '" data-group="' + escAttr(group) + '">')
      parts.push('<button type="button" class="ef-group-row" data-group="' + escAttr(group) + '">' +
        '<span class="ef-gname">' + escHtml(group) + '</span>' +
        '<span class="ef-gcount">' + count + '</span>' +
        '<span class="ef-gdist">' + dist(entry.nearestBlocks) + '</span></button>')
      var rows = structuresIn(data, group)
      if (rows.length) {
        parts.push('<ul class="ef-structures">')
        rows.forEach(function (r) {
          parts.push('<li><button type="button" class="ef-struct-row" data-sid="' +
            escAttr(r.sid) + '" data-group="' + escAttr(group) + '" title="' + escAttr(r.sid) + '">' +
            '<span class="ef-sname">' + escHtml(shortName(r.sid)) + '</span>' +
            '<span class="ef-scount">' + r.count + '</span>' +
            '<span class="ef-sdist">' + dist(r.nearest) + '</span></button></li>')
        })
        parts.push('</ul>')
      }
      parts.push('</div>')
    })
    parts.push('</div>')
    return parts.join('')
  }

  function escHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  }
  function escAttr(s) { return escHtml(s).replace(/"/g, '&quot;') }

  // ---------------------------------------------------------- selection

  function applySelection(panel, data) {
    panel.querySelectorAll('.ef-selected').forEach(function (el) {
      el.classList.remove('ef-selected')
    })
    if (selection) {
      var sel = selection.kind === 'group'
        ? '.ef-group-row[data-group="' + CSS.escape(selection.key) + '"]'
        : '.ef-struct-row[data-sid="' + CSS.escape(selection.key) + '"]'
      panel.querySelectorAll(sel).forEach(function (el) {
        el.classList.add('ef-selected')
      })
    }
    drawMarkers(data)
  }

  function wire(panel, data) {
    panel.addEventListener('click', function (ev) {
      var g = ev.target.closest('.ef-group-row')
      var s = ev.target.closest('.ef-struct-row')
      var next = null
      if (s) next = { kind: 'structure', key: s.dataset.sid }
      else if (g) next = { kind: 'group', key: g.dataset.group }
      else return
      // Choosing the same row again unpins it, so the map can be cleared
      // without hunting for an "off" control.
      if (selection && selection.kind === next.kind && selection.key === next.key) {
        selection = null
      } else {
        selection = next
      }
      applySelection(panel, data)
    })

    // Row to marker.
    panel.addEventListener('mouseover', function (ev) {
      var s = ev.target.closest('.ef-struct-row')
      if (!s) return
      layer.querySelectorAll('.ef-marker').forEach(function (m) {
        m.classList.toggle('ef-hot', m.dataset.sid === s.dataset.sid)
      })
    })
    panel.addEventListener('mouseout', function () {
      layer.querySelectorAll('.ef-hot').forEach(function (m) {
        m.classList.remove('ef-hot')
      })
    })

    // Marker to row.
    layer.addEventListener('mouseover', function (ev) {
      var m = ev.target.closest('.ef-marker')
      if (!m) return
      panel.querySelectorAll('.ef-struct-row').forEach(function (row) {
        row.classList.toggle('ef-hot', row.dataset.sid === m.dataset.sid)
      })
    })
    layer.addEventListener('mouseout', function () {
      panel.querySelectorAll('.ef-hot').forEach(function (row) {
        row.classList.remove('ef-hot')
      })
    })
  }

  // ------------------------------------------------------------- lifecycle

  function refresh() {
    var cand = window.lbCandidate ? window.lbCandidate() : null
    if (!cand) {
      lastKey = null
      selection = null
      clearLayer()
      var gone = info.querySelector('.ef-panel')
      if (gone) gone.remove()
      return
    }
    // Inserting the panel mutates the node this file observes; without the
    // guard the observer re-runs its own insertion forever.
    var key = cacheKey(cand)
    if (key === lastKey && info.querySelector('.ef-panel')) return
    lastKey = key
    selection = null
    clearLayer()
    var existing = info.querySelector('.ef-panel')
    if (existing) existing.remove()

    fetchFacts(cand, function (data) {
      if (window.lbCandidate) {
        var current = window.lbCandidate()
        if (!current || current.dim !== cand.dim || current.seed !== cand.seed) return
      }
      var old = info.querySelector('.ef-panel')
      if (old) old.remove()
      var html = buildPanel(data)
      if (!html) return
      info.insertAdjacentHTML('beforeend', html)
      var panel = info.querySelector('.ef-panel')
      if (panel) wire(panel, data)
    })
  }

  function redraw() {
    var cand = window.lbCandidate ? window.lbCandidate() : null
    if (cand && cache[cacheKey(cand)]) drawMarkers(cache[cacheKey(cand)])
  }

  new MutationObserver(refresh).observe(info, { childList: true })
  // A route-opened lightbox populates .lb-info during app.js's own evaluation,
  // before this observer exists.
  refresh()
  window.addEventListener('resize', redraw)
  imageBox.addEventListener('load', redraw, true)
})()
