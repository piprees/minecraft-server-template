/* dartboard.js — every structure criterion drawn on the map at once.
 *
 * The lightbox already draws ONE row's wanted band when you hover it. That is
 * useful and also the problem: a dimension carries ~27 criteria, so answering
 * "is this layout right" means hovering 27 rows in turn and remembering what
 * each one looked like. The data for all of them is already in the DOM —
 * every .mrow[data-band] carries its band in blocks, its severity class, and
 * its placements where those are real — so all of it can simply be drawn.
 *
 * Reading it: the map is the world, spawn is the centre, and distance from
 * the centre is distance from spawn. Each criterion is an arc at its own
 * radius, coloured by whether it passed. Green arcs sitting where the map
 * shows interesting terrain is a good seed; a red ring tight around spawn is
 * a structure the dimension wanted kept away and did not get.
 *
 * Deliberately opt-in. When it is on it replaces the hover overlay rather
 * than fighting it, and the map stays legible underneath because the arcs
 * are strokes, not fills.
 *
 * Coordinate note: the SVG viewBox is 0-100 across the render's full
 * coverage, which is why a distance d in blocks lands at d/coverage*100 from
 * a centre of 50. Same maths the hover overlay uses — if one changes, both do.
 */
;(function () {
  if (location.protocol === 'file:') return
  var NS = 'http://www.w3.org/2000/svg'

  var lb = document.getElementById('lightbox')
  if (!lb) return
  var svg = document.getElementById('lb-overlay')
  var info = lb.querySelector('.lb-info')
  var imageBox = lb.querySelector('.lb-image')
  if (!svg || !info || !imageBox) return

  var on = false
  var btn = document.createElement('button')
  btn.type = 'button'
  btn.className = 'lb-dart-toggle'
  btn.setAttribute('aria-pressed', 'false')
  btn.title = 'Draw every structure criterion on the map'
  btn.textContent = 'All criteria'
  imageBox.appendChild(btn)

  function clear() {
    while (svg.firstChild) svg.removeChild(svg.firstChild)
  }

  function sevOf(row) {
    if (row.classList.contains('sev2')) return 'bad'
    if (row.classList.contains('sev1')) return 'warn'
    return 'good'
  }

  function draw() {
    clear()
    if (!on) return
    // One aligner, owned by the lightbox in app.js — this SVG has two
    // consumers and must not carry two copies of the geometry.
    if (window.alignLbOverlay) window.alignLbOverlay()
    // The render on screen decides the scale: the lightbox shows the low-res
    // one until the hi-res probe lands, and they cover different areas.
    var coverage = window.lbMapCoverage ? window.lbMapCoverage() : 0
    if (!coverage) return

    var rows = info.querySelectorAll('.mrow[data-band]')
    if (!rows.length) return

    // Distance grid first, underneath everything, so an arc's radius can be
    // read as a number rather than just compared with its neighbours.
    var quarter = coverage / 2 / 4
    for (var g = 1; g <= 4; g++) {
      var gr = ((quarter * g) / coverage) * 100
      var gc = document.createElementNS(NS, 'circle')
      gc.setAttribute('cx', 50)
      gc.setAttribute('cy', 50)
      gc.setAttribute('r', gr)
      gc.setAttribute('class', 'db-grid')
      gc.setAttribute('vector-effect', 'non-scaling-stroke')
      svg.appendChild(gc)
      var t = document.createElementNS(NS, 'text')
      t.setAttribute('x', 50)
      t.setAttribute('y', 50 - gr - 0.6)
      t.setAttribute('class', 'db-gridlabel')
      t.textContent = Math.round(quarter * g) + 'b'
      svg.appendChild(t)
    }

    Array.prototype.forEach.call(rows, function (row) {
      var band = (row.dataset.band || '').split(',').map(Number)
      if (band.length !== 2 || !isFinite(band[1])) return
      var sev = sevOf(row)
      var shun = row.classList.contains('shun')
      var r0 = (band[0] / coverage) * 100
      var r1 = (band[1] / coverage) * 100

      // A shun is an exclusion: the band is the area that should be EMPTY, so
      // it reads as a filled keep-out zone rather than a target ring.
      if (shun) {
        var disc = document.createElementNS(NS, 'circle')
        disc.setAttribute('cx', 50)
        disc.setAttribute('cy', 50)
        disc.setAttribute('r', r1)
        disc.setAttribute('class', 'db-shun db-' + sev)
        svg.appendChild(disc)
      } else {
        ;[r0, r1].forEach(function (r, i) {
          if (r <= 0) return
          var c = document.createElementNS(NS, 'circle')
          c.setAttribute('cx', 50)
          c.setAttribute('cy', 50)
          c.setAttribute('r', r)
          c.setAttribute('class', 'db-band db-' + sev)
          c.setAttribute('vector-effect', 'non-scaling-stroke')
          if (i === 0) c.setAttribute('stroke-dasharray', '1.5 1.5')
          svg.appendChild(c)
        })
      }

      // Real placements, where the set has them. Noise-placed groups have no
      // grid position to plot, which is why this is conditional and not a bug.
      var pos = row.dataset.pos
      if (pos) {
        pos.split(';').forEach(function (pair) {
          var xz = pair.split(',').map(Number)
          if (xz.length !== 2) return
          var p = document.createElementNS(NS, 'circle')
          p.setAttribute('cx', 50 + (xz[0] / coverage) * 100)
          p.setAttribute('cy', 50 + (xz[1] / coverage) * 100)
          p.setAttribute('r', 0.6)
          p.setAttribute('class', 'db-pt db-' + sev)
          svg.appendChild(p)
        })
      }
    })

    var spawn = document.createElementNS(NS, 'circle')
    spawn.setAttribute('cx', 50)
    spawn.setAttribute('cy', 50)
    spawn.setAttribute('r', 0.8)
    spawn.setAttribute('class', 'db-spawn')
    svg.appendChild(spawn)
  }

  btn.addEventListener('click', function () {
    on = !on
    btn.setAttribute('aria-pressed', String(on))
    btn.classList.toggle('on', on)
    // The hover overlay and this share one <svg>; whichever is active owns it.
    info.classList.toggle('dart-on', on)
    draw()
  })

  // Re-draw when the lightbox swaps candidate (arrow keys, clicking another
  // tile) — the panel content is replaced wholesale, so nothing else would.
  new MutationObserver(function () {
    if (on) draw()
  }).observe(info, { childList: true })

  window.addEventListener('resize', function () {
    if (on) draw()
  })
  // The hi-res swap replaces the src after load, which changes the laid-out
  // box; without this the rings stay aligned to the low-res geometry.
  imageBox.addEventListener('load', function () { if (on) draw() }, true)

  document.addEventListener('keydown', function (e) {
    if (!lb.classList.contains('open')) return
    if (e.key === 'd' || e.key === 'D') btn.click()
  })
})()
