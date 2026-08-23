/* app.js — the seed viewer's client.
 *
 * Moved verbatim out of viewer_template.html's three inline <script>
 * blocks, in their original order. Loaded with `defer` from
 * assets/app.js, so it now runs after parsing rather than mid-document;
 * every block was already an IIFE reading elements declared above it, so
 * that is strictly safer than it was.
 *
 * `openConfigDialog` opens the fork/configure dialog; only this file
 * calls it, via the create-dim/configure action buttons below.
 */

;(function () {
  var live = location.protocol !== 'file:'

  // --- Candidate detail lightbox ---
  var lb = document.getElementById('lightbox')
  var lbImg = lb.querySelector('.lb-image img')
  var lbInfo = lb.querySelector('.lb-info')
  var lbReturnFocus = null
  function lbShow() {
    if (!lb.classList.contains('open')) {
      lbReturnFocus = document.activeElement
      lb.classList.add('open')
      // Without this the page keeps scrolling under the open panel, and on a
      // phone a drag that starts over the map moves the grid behind it.
      document.body.classList.add('lb-open')
    }
    lb.querySelector('.lb-close').focus()
  }
  function lbHide() {
    lb.classList.remove('open')
    document.body.classList.remove('lb-open')
    if (lbReturnFocus && document.contains(lbReturnFocus)) lbReturnFocus.focus()
    lbReturnFocus = null
    lbCurrentCand = null
    // Closing drops back to the spawn view. Reopening starts there again
    // unless the whole-world map exists by then, which the probe decides.
    lbShowingHires = false
    lbHiresReady = false
    lbRenderAsked = ''
    lbRenderWatching = ''
    lbNote('')
    var box = lb.querySelector('.lb-image')
    if (box) box.classList.remove('awaiting-hires')
    // The candidate leaves the URL; the dimension behind it stays, because
    // closing the lightbox reveals the expanded card rather than the root.
    setRoute(expandedDimName(), '')
  }
  document.addEventListener('click', function (e) {
    var img = e.target.closest('.cand img, img.winner-img')
    if (img && img.closest('.compact')) img = null
    // Anywhere on a candidate card counts: with borders on the render
    // shrinks inside its box, and clicking the obvious target (the card)
    // used to do nothing.
    if (!img) {
      var card = e.target.closest('.cand')
      if (card && !e.target.closest('button, .lb-actions, a')) {
        e.stopPropagation()
        openCandInLightbox(card)
      }
      return
    }
    e.stopPropagation()
    var cand = img.closest('.cand')
    if (cand) {
      openCandInLightbox(cand)
    } else {
      lbImg.src = img.src
      lbInfo.innerHTML = ''
      lbShow()
    }
  })
  lb.querySelector('.lb-close').addEventListener('click', function (e) {
    e.stopPropagation()
    lbHide()
  })
  lb.addEventListener('click', function (e) {
    if (e.target === lb) lbHide()
  })
  // Track current candidate for arrow nav
  var lbCurrentCand = null
  // Two renders per candidate, covering different areas (see lbMapCoverage
  // below): lbLowSrc arrives immediately and covers less ground; lbHiresSrc
  // is wider, and only confirmed to exist once its probe's onload fires. The
  // toggle lets the user pick either by hand rather than only ever seeing
  // whichever one a probe happened to land on.
  var lbLowSrc = null
  var lbHiresSrc = null
  var lbHiresReady = false
  var lbShowingHires = false
  // The candidate this modal has asked the server to draw, as
  // "<dim>/<seed>" — the same spelling /pipeline-status reports it under.
  // Kept so a second press does not post a second request, and so the modal
  // can tell that the render it was watching has finished.
  var lbRenderAsked = ''
  var lbRenderWatching = ''
  var lbRenderNoteEl = lb.querySelector('.lb-render-note')
  function lbNote(text) {
    if (!lbRenderNoteEl) return
    lbRenderNoteEl.textContent = text || ''
    lbRenderNoteEl.hidden = !text
  }
  // How much world each render covers, in blocks, from the attributes the
  // page already carries. Guarded end to end: a missing panel, a stale
  // index.html or an unparseable attribute answers 0, and the labels fall
  // back to the wording they had rather than taking the whole IIFE with them.
  function lbCoverageBlocks() {
    var host = lbInfo && lbInfo.querySelector('[data-coverage]')
    if (!host) return { low: 0, high: 0 }
    return {
      low: parseFloat(host.dataset.coverageLow || '') || 0,
      high: parseFloat(host.dataset.coverage || '') || 0,
    }
  }
  function updateResToggle() {
    var btn = lb.querySelector('.lb-res-toggle')
    if (!btn) return
    if (!lbHiresSrc) {
      btn.hidden = true
      return
    }
    // Usable before the whole-world render exists. Switching sets the view
    // the overlays project against, which is what the criteria and structure
    // markers need; the picture catches up when the render lands.
    // SAY WHAT THE PICTURE COVERS. The two renders are different areas, not
    // two qualities of the same one: the zoomed-in view is a fixed 512-block
    // window centred on the dimension's declared SPAWN, which the roller
    // picks to be habitable — so on a mostly-drowned world it is the island
    // you land on and nothing else. Read as the world, it produced a
    // reported "the map disagrees with the facts" that measurement did not
    // support: 81.4% blue in the thumbnail against 96.6% in the high-def
    // render of the same seed, the facts at 98.0% and the live world at
    // 97.3% (TROUBLESHOOTING.md#t32). "Wider area" was true and far too
    // quiet; the numbers are right here on the page.
    var cov = lbCoverageBlocks()
    var lowLabel = cov.low ? 'Around spawn · ' + cov.low + ' blocks' : 'Zoomed in'
    var highLabel = cov.high ? 'Whole world · ' + cov.high + ' blocks' : 'High-def'
    btn.hidden = false
    btn.disabled = false
    btn.setAttribute('aria-pressed', lbShowingHires ? 'true' : 'false')
    var pending = lbShowingHires && !lbHiresReady
    if (!lbHiresReady && !lbShowingHires) {
      // No whole-world map on disk for this candidate. The button is an
      // ASK, not a toggle: pressing it is the only thing on the page that
      // starts a detail render, and it costs minutes of CPU — so say that
      // rather than leaving it looking like a free view change.
      btn.textContent = 'High-def'
      btn.title = 'Draw the whole-world map for this seed — ' + highLabel.toLowerCase()
        + '. It is minutes of CPU, and progress is reported here on the card.'
      var askBox = lb.querySelector('.lb-image')
      if (askBox) askBox.classList.remove('awaiting-hires')
      return
    }
    btn.textContent = (lbShowingHires ? highLabel : lowLabel) + (pending ? ' · rendering' : '')
    btn.title = pending
      ? 'The whole-world render is not drawn yet — the spawn view is shown at its'
        + ' true scale inside the frame until it arrives'
      : lbShowingHires
        ? 'Showing the whole world — click for the ' + lowLabel.toLowerCase() + ' view'
        : 'Showing ' + lowLabel.toLowerCase()
          + ', NOT the whole world — click for the ' + highLabel.toLowerCase()
    var box = lb.querySelector('.lb-image')
    if (box) box.classList.toggle('awaiting-hires', pending)
  }
  function lbSetRes(showHires) {
    if (showHires === lbShowingHires) return
    lbShowingHires = showHires
    // Without the whole-world render, the spawn view stands in for it — drawn
    // at the fraction of the frame it actually covers rather than stretched
    // across it, so a marker projected against the wider area still lands
    // where it belongs.
    lbImg.src = showHires && !lbHiresReady ? lbLowSrc
      : showHires ? lbHiresSrc : lbLowSrc
    lbScaleInset()
    updateResToggle()
  }

  /**
   * Sizes the stand-in. The spawn view covers `low` blocks of a frame that
   * now stands for `high`, so it occupies low/high of the frame, offset by
   * where spawn sits in it. Cleared whenever the real render is showing.
   */
  function lbScaleInset() {
    if (!lbImg) return
    var cov = lbCoverageBlocks()
    var standIn = lbShowingHires && !lbHiresReady && cov.low && cov.high
    if (!standIn || cov.low >= cov.high) {
      lbImg.style.width = ''
      lbImg.style.height = ''
      lbImg.style.left = ''
      lbImg.style.top = ''
      lbImg.style.position = ''
      return
    }
    var pct = (cov.low / cov.high) * 100
    var host = lbInfo && lbInfo.querySelector('[data-coverage]')
    var sx = parseFloat((host && host.dataset.spawnX) || '0') || 0
    var sz = parseFloat((host && host.dataset.spawnZ) || '0') || 0
    lbImg.style.position = 'absolute'
    lbImg.style.width = pct + '%'
    lbImg.style.height = pct + '%'
    lbImg.style.left = (50 + (sx / cov.high) * 100 - pct / 2) + '%'
    lbImg.style.top = (50 + (sz / cov.high) * 100 - pct / 2) + '%'
  }
  // A stale index.html (finalised before this button existed) has no
  // .lb-res-toggle; an unguarded dereference here killed the whole IIFE
  // and every click handler with it. The button is hidden by default, so
  // its absence is tolerable — the rest of the viewer must keep working.
  var lbResToggle = lb.querySelector('.lb-res-toggle')
  if (lbResToggle) lbResToggle.addEventListener('click', function (e) {
    e.stopPropagation()
    var want = !lbShowingHires
    lbSetRes(want)
    // The ONE place a detail render is started. Not on open, not on arrowing
    // between candidates or dimensions: a whole-world map samples the world
    // edge to edge and runs the generator at every sample, so nothing may
    // ask for one on the user's behalf.
    if (want && !lbHiresReady) askForDetailRender()
  })

  /**
   * Asks the server to draw this candidate's whole-world map.
   *
   * <p>One render runs at a time server-side, and a refusal is reported here
   * rather than swallowed — a button that posts, is turned down and says
   * nothing is indistinguishable from a broken one.
   */
  function askForDetailRender() {
    if (!live) return
    var c = window.lbCandidate()
    if (!c) return
    var key = c.dim + '/' + c.seed
    if (lbRenderAsked === key) return
    lbRenderAsked = key
    lbNote('Asking for the whole-world map…')
    fetch('/render', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // The seed goes as a string: a 64-bit seed does not survive a JSON
      // number in this browser, and the server parses either form.
      body: JSON.stringify({ dim: c.dim, seed: String(c.seed), resolution: 'highres' }),
    })
      .then(function (r) { return r.json() })
      .then(function (d) {
        if (lbRenderAsked !== key) return
        if (d && d.error) {
          lbRenderAsked = ''
          lbNote(d.error === 'a render is already running'
            ? 'Another map is being drawn — press High-def again once it finishes.'
            : d.error)
          return
        }
        lbNote('Drawing the whole world — minutes of CPU. This card updates when it lands.')
      })
      .catch(function () {
        if (lbRenderAsked !== key) return
        lbRenderAsked = ''
        lbNote('Could not reach the server.')
      })
  }

  /**
   * Looks for this candidate's whole-world render and shows it if it is
   * there. A plain GET of an image, never a request to draw one.
   */
  function probeHires(cand) {
    if (!lbHiresSrc) return
    var p = new Image()
    p.onload = function () {
      // Arrow-key nav can move on before an earlier candidate's probe
      // lands — a stale probe must not overwrite what is on screen now.
      if (lbCurrentCand !== cand) return
      lbHiresReady = true
      lbShowingHires = true
      lbImg.src = lbHiresSrc
      lbScaleInset()
      updateResToggle()
      if (window.alignLbOverlay) window.alignLbOverlay()
    }
    p.src = lbHiresSrc
  }

  /**
   * What the roller's status poll hands the modal each time it lands.
   *
   * <p>Progress for a detail render belongs on the card that asked for it,
   * not on the roller's line under the nav — that line is about the bank as
   * a whole, and a page that looks frozen while one modal works is exactly
   * what it produced. Matched on {@code <dim>/<seed>} so a render for some
   * other candidate says nothing here.
   */
  window.lbRenderStatus = function (st) {
    if (!lb.classList.contains('open')) {
      lbRenderWatching = ''
      return
    }
    var open = window.lbCandidate()
    if (!open) return
    var mine = open.dim + '/' + open.seed
    var busy = (st.rendering_high || [])[0] || ''
    if (busy === mine) {
      lbRenderWatching = mine
      var secs = st.rendering_high_seconds || 0
      lbNote('Drawing the whole world — ' + secs + 's so far. It runs to minutes.')
      return
    }
    if (lbRenderWatching === mine) {
      // The render this card was watching has finished; its file is on disk.
      lbRenderWatching = ''
      lbRenderAsked = ''
      lbNote('')
      probeHires(lbCurrentCand)
    }
  }
  function getVisibleCands() {
    var src
    var expanded = document.querySelector('.dim-card.expanded')
    if (document.body.classList.contains('ungrouped')) {
      src = ugGrid.querySelectorAll('.cand')
    } else if (expanded) {
      src = expanded.querySelectorAll('.cand[data-idx]')
    } else {
      return []
    }
    return Array.from(src).filter(function (c) {
      if (c.style.display === 'none') return false
      if (c.offsetParent === null) return false
      return true
    })
  }
  // A flat-view tile is a detail-stripped clone; (dim, seed) identifies the
  // original, which still carries it.
  // A src-less <img> renders as broken-image chrome, and alt text does not
  // reliably paint in its place. Use a real element that says what is true,
  // and reuse the candidate's own relief chip — the terrain shape IS known
  // even when the render is not.
  function setNoRender(on, cand) {
    var box = lb.querySelector('.lb-image')
    var old = box.querySelector('.lb-noimg')
    if (old) old.remove()
    lbImg.style.display = on ? 'none' : ''
    if (!on) return
    var el = document.createElement('div')
    el.className = 'lb-noimg'
    var chip = cand && cand.querySelector('.relief-chip')
    el.innerHTML =
      (chip ? chip.outerHTML : '') +
      '<p>Render not produced yet</p>' +
      '<p class="lb-noimg-sub">The measurements below are complete — a render ' +
      'is minutes of CPU and arrives on its own.</p>'
    box.appendChild(el)
  }

  // The overlay's position was pure CSS (top:50% + translateY(-50%) inside
  // .lb-image with aspect-ratio:1). That centres against the CONTAINER, and
  // .lb-image is not the image's box — .lb-inner clips at max-height:90vh, so
  // the SVG ended up exactly half its own height above the map: measured
  // -330px against a 660px image. Every ring the hover drew, and every band
  // the dartboard drew, was offset by that.
  //
  // Measure the <img> and lay every overlay layer on it. Exported because
  // dartboard.js and structicons.js draw into their own layers over the same
  // image and must not carry copies of this.
  function alignOverlay() {
    var box = lb.querySelector('.lb-image')
    var img = box && box.querySelector('img')
    if (!img) return
    var b = box.getBoundingClientRect()
    var r = img.getBoundingClientRect()
    var layers = box.querySelectorAll('.lb-layer')
    // No render for this candidate: the <img> is hidden behind the "render
    // not produced yet" placeholder and has no box, so there is nothing for
    // a ring or a marker to be measured against. Leaving the layers where
    // the PREVIOUS candidate put them draws that candidate's map over this
    // one's placeholder, which is worse than drawing nothing.
    if (!r.width || !r.height) {
      layers.forEach(function (svg) { svg.style.visibility = 'hidden' })
      return
    }
    layers.forEach(function (svg) {
      svg.style.visibility = ''
      svg.style.position = 'absolute'
      svg.style.left = r.left - b.left + 'px'
      svg.style.top = r.top - b.top + 'px'
      svg.style.width = r.width + 'px'
      svg.style.height = r.height + 'px'
      svg.style.aspectRatio = 'auto'
      svg.style.transform = 'none'
      svg.style.inset = 'auto'
    })
  }
  window.alignLbOverlay = alignOverlay
  window.addEventListener('resize', alignOverlay)
  // The hi-res swap replaces the src and relays the box out from under it.
  lb.addEventListener('load', alignOverlay, true)

  // How many blocks the image CURRENTLY on screen covers, edge to edge.
  //
  // Every overlay divides a block distance by this to get a viewBox
  // fraction, and the lightbox shows two different renders of the same
  // candidate: the 1024px low-res one immediately, then the 2048px hi-res
  // one if a probe finds it. They cover different areas, so a single
  // number is right for at most one of them — and since most candidates
  // never get a hi-res render, the single number was wrong most of the
  // time, drawing every ring at a quarter of its true radius.
  //
  // Both values are emitted by score-dimensions._coverage_attrs, computed
  // the same way biome_renderer.batch_render picks its geometry.
  function lbMapCoverage() {
    var host = lbInfo.querySelector('[data-coverage]')
    if (!host) return 0
    var src = (lbImg.getAttribute('src') || '').split('?')[0]
    var low = parseFloat(host.dataset.coverageLow || '')
    if (low && !/_hires\.png$/.test(src)) return low
    return parseFloat(host.dataset.coverage) || 0
  }
  window.lbMapCoverage = lbMapCoverage

  // WHICH candidate the panel is currently showing. The panel itself is a
  // copy of .cand-detail's innerHTML, and (dim, seed) live on the .cand
  // wrapper that was left behind — so anything needing to ask the server
  // about the open candidate (structicons.js and /noise-census) has no way
  // to identify it from the lightbox DOM alone.
  window.lbCandidate = function () {
    if (!lbCurrentCand) return null
    var d = lbCurrentCand.dataset.dim
    var s = lbCurrentCand.dataset.seed
    return d && s ? { dim: d, seed: s } : null
  }

  function candDetailFor(cand) {
    var own = cand.querySelector('.cand-detail')
    if (own) return own
    var d = cand.dataset.dim,
      s = cand.dataset.seed
    if (!d || !s) return null
    var orig = document.querySelector(
      '#grid .cand[data-dim="' + CSS.escape(d) + '"][data-seed="' + CSS.escape(s) + '"]'
    )
    return orig ? orig.querySelector('.cand-detail') : null
  }

  function openCandInLightbox(cand) {
    if (!cand) return
    lbCurrentCand = cand
    // Both facts, in one write: in flat view no card is expanded, so the
    // candidate's own dimension is the only thing that identifies it.
    setRoute(cand.dataset.dim || expandedDimName(), cand.dataset.seed)
    // No <img> means the render has not been produced yet — the onerror
    // handler REPLACES the element with the "render queued" placeholder. This
    // used to `return`, so clicking any un-rendered candidate silently did
    // nothing... which is most candidates most of the time, since a render is
    // minutes of CPU. The measurements are the point of this panel; the map
    // is context. Open it either way.
    // The frame behind the map is this dimension's own sky, so a render that
    // does not fill it (a stand-in, or a world smaller than its border) sits
    // on the colour that world actually is.
    var skyCard = cand.closest('.dim-card')
    var skyBox = lb.querySelector('.lb-image')
    if (skyBox) skyBox.style.background = (skyCard && skyCard.dataset.sky) || ''
    var img = cand.querySelector('img')
    setNoRender(!img, cand)
    lbHiresSrc = (img && img.dataset.hires) || null
    // NEVER trust the tile's current src for the zoomed-in URL: autoRefresh
    // upgrades tiles to hi-res in place. Prefer the stashed original, then
    // the name derived from the hi-res one, and only then the live src.
    lbLowSrc = img
      ? (img.dataset.low
         || (lbHiresSrc ? lbHiresSrc.replace('_hires', '') : null)
         || img.src)
      : null
    lbHiresReady = false
    lbShowingHires = false
    // A render this modal asked for belongs to the card that asked. Moving
    // to another candidate stops reporting it here; the render itself is
    // never cancelled, and its own card picks the picture up on reopen.
    lbRenderAsked = ''
    lbRenderWatching = ''
    lbNote('')
    updateResToggle()
    if (!img) {
      lbImg.removeAttribute('src')
      var d0 = candDetailFor(cand)
      lbInfo.innerHTML = d0 ? d0.innerHTML : ''
      lbShow()
      alignOverlay()
      return
    }
    lbImg.src = lbLowSrc
    // A LOOK, not an ask. A candidate whose whole-world map already exists
    // opens on it; one whose does not opens on the spawn view and stays
    // there until somebody presses High-def.
    probeHires(cand)
    var detail = candDetailFor(cand)
    lbInfo.innerHTML = detail ? detail.innerHTML : ''
    // Again, now the panel exists: the toggle's labels read the coverage
    // attributes out of it, and the call above ran before it was written.
    updateResToggle()
    // Lightbox border ring: hires renders (32K) exceed most borders.
    // Show border ring at diameter/hirescoverage proportion.
    var lbImageDiv = lb.querySelector('.lb-image')
    var existingRing = lbImageDiv.querySelector('.border-ring')
    if (existingRing) existingRing.remove()
    var existingLbl = lbImageDiv.querySelector('.border-label')
    if (existingLbl) existingLbl.remove()
    var dimCard = cand.closest('.dim-card')
    if (dimCard && dimCard.dataset.borderDiameter &&
        document.body.classList.contains('show-borders')) {
      var bDiam = parseFloat(dimCard.dataset.borderDiameter)
      var dScale = parseFloat(dimCard.dataset.dimScale || 1)
      var hiresCoverage = (2048 * 16) / dScale
      var lbl = document.createElement('div')
      lbl.className = 'border-label'
      lbl.textContent = hiresCoverage + 'b render · ' + bDiam + 'b border'
      lbImageDiv.style.position = 'relative'
      lbImageDiv.appendChild(lbl)
      if (hiresCoverage > bDiam) {
        var pct = (bDiam / hiresCoverage) * 100
        var off = (100 - pct) / 2
        var ring = document.createElement('div')
        ring.className = 'border-ring'
        ring.style.cssText =
          'display:block;left:' +
          off.toFixed(1) +
          '%;top:' +
          off.toFixed(1) +
          '%;width:' +
          pct.toFixed(1) +
          '%;height:' +
          pct.toFixed(1) +
          '%'
        lbImageDiv.appendChild(ring)
      }
    }
    lbShow()
  }
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') {
      if (lb.classList.contains('open')) {
        lbHide()
        return
      }
      var expanded = document.querySelector('.dim-card.expanded')
      if (expanded) {
        setExpanded(expanded, false)
        setRoute('', '')
      }
      return
    }
    if (!lb.classList.contains('open')) return
    // Focus trap: keep Tab inside the dialog while open
    if (e.key === 'Tab') {
      var focusables = lb.querySelectorAll('button, [href], input, select, textarea')
      if (!focusables.length) return
      var first = focusables[0],
        last = focusables[focusables.length - 1]
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault()
        first.focus()
      }
      return
    }
    // Arrow keys: navigate between candidates
    if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
      e.preventDefault()
      var cands = getVisibleCands()
      if (!cands.length || !lbCurrentCand) return
      var idx = cands.indexOf(lbCurrentCand)
      if (idx < 0) return
      var next = e.key === 'ArrowRight' ? cands[idx + 1] : cands[idx - 1]
      if (next) openCandInLightbox(next)
      return
    }
    // Keyboard shortcuts for actions
    if (e.key === 'u' || e.key === 'U') {
      var btn = lbInfo.querySelector('.pick')
      if (btn) btn.click()
      return
    }
    if (e.key === 'x' || e.key === 'X') {
      var btn = lbInfo.querySelector('.action-btn.shortlist')
      if (btn) btn.click()
      return
    }
    if (e.key === 'f' || e.key === 'F') {
      var btn = lbInfo.querySelector('.action-btn.create-dim')
      if (btn) btn.click()
      return
    }
  })
  if (live) document.body.classList.add('live')

  // The reserved dimensions, in the order they are worth seeing: the one
  // everybody starts in, then the two that gate progression, then the skylands.
  var RESERVED_NAMES = ['overworld', 'the_nether', 'the_end', 'paradise_lost']

  function reservedRank(name) {
    var i = RESERVED_NAMES.indexOf(name)
    return i === -1 ? RESERVED_NAMES.length : i
  }

  // --- Filter + sort state from the query string, view state from the path ---
  //
  // Filters, sort and search stay in the query string. The two facts that say
  // what the page is currently ABOUT live in the path instead:
  //
  //     /the-nether              the_nether's card is expanded
  //     /the-nether/-4831234567  ...and that candidate's lightbox is open
  //
  // so a refresh, the reload after "Use this seed", and a link pasted to
  // someone else all land back on the same view. The sums live in route.js;
  // viewer-server serves index.html for both shapes.
  //
  // Not on file://, where the path is a filesystem path and there is no
  // server to route it.
  var routed = live && !!window.lbRouteParse
  function readRoute() {
    return routed ? window.lbRouteParse(location.pathname) : { dim: '', seed: '' }
  }
  function readHash() {
    var p = new URLSearchParams(location.search.slice(1))
    var r = readRoute()
    return {
      family: p.get('family') || 'All',
      type: p.get('type') || '',
      mood: p.get('mood') || '',
      search: p.get('q') || '',
      flagged: p.get('flagged') === '1',
      maxScore: p.get('max-score') || '',
      shortlisted: p.get('shortlisted') === '1',
      ungrouped: p.get('ungrouped') === '1',
      showHidden: p.get('hidden') === '1',
      sort: p.get('sort') || 'name',
      dim: r.dim,
      seed: r.seed,
    }
  }
  function writeHash(s, push) {
    var p = new URLSearchParams()
    if (s.family !== 'All') p.set('family', s.family)
    if (s.type) p.set('type', s.type)
    if (s.mood) p.set('mood', s.mood)
    if (s.search) p.set('q', s.search)
    if (s.flagged) p.set('flagged', '1')
    if (s.maxScore) p.set('max-score', s.maxScore)
    if (s.shortlisted) p.set('shortlisted', '1')
    if (s.ungrouped) p.set('ungrouped', '1')
    if (s.showHidden) p.set('hidden', '1')
    if (s.sort !== 'name') p.set('sort', s.sort)
    var qs = p.toString()
    if (!routed) {
      history.replaceState(null, '', qs ? '?' + qs : location.pathname)
      return
    }
    var url = window.lbRouteUrl(s.dim, s.seed, qs)
    // An unchanged URL must not become a history entry, or Back walks
    // through a dozen identical states before it leaves the page.
    if (url === location.pathname + location.search) return
    if (push) history.pushState(null, '', url)
    else history.replaceState(null, '', url)
  }

  // True while applyRoute is reproducing a URL in the DOM. Expanding a card
  // and opening its lightbox are two route writes, and neither is a
  // navigation the user made — they must not push history entries of their
  // own on top of the one being restored.
  var routeApplying = false
  function setRoute(dim, seed) {
    dim = dim || ''
    seed = seed || ''
    if (state.dim === dim && state.seed === seed) return
    state.dim = dim
    state.seed = seed
    writeHash(state, !routeApplying)
  }
  function expandedDimName() {
    var c = document.querySelector('.dim-card.expanded')
    return c ? c.dataset.name || '' : ''
  }

  var state = readHash()
  var typeEl = document.getElementById('f-type')
  var moodEl = document.getElementById('f-mood')
  var sortEl = document.getElementById('f-sort')
  var searchEl = document.getElementById('f-search')
  var flaggedEl = document.getElementById('f-flagged')
  var maxScoreEl = document.getElementById('f-maxscore')
  var shortlistedEl = document.getElementById('f-shortlisted')
  var ungroupedEl = document.getElementById('f-ungrouped')
  var hiddenEl = document.getElementById('f-hidden')
  var grid = document.getElementById('grid')
  var ugGrid = document.getElementById('ungrouped-grid')

  // ViewerPage stamps RollPipeline.SCORE_THRESHOLD here so the flag means the
  // same number on the card, in the label and in the flat view.
  var flagThreshold = parseFloat(flaggedEl.dataset.threshold || 80)
  // Unscored reads 0.0 from ViewerPage, on cards and candidates alike, so a
  // dimension with nothing banked sits below every bar — which is the set this
  // filter exists to find.
  function below(el, limit) {
    return parseFloat(el.dataset.score || 0) < limit
  }

  // A named seed (current, starting, best, shortlisted) keeps the place the
  // server gave it, ahead of everything ranked. Sorting by score alone put the
  // configured world — which often has no score at all — last, which is the
  // one card the page exists to show first.
  function namedRank(el) {
    var role = el.dataset.role
    return role && role !== 'other' ? parseInt(el.dataset.idx || '0', 10) : Infinity
  }
  function candSort(a, b) {
    var na = namedRank(a)
    var nb = namedRank(b)
    // Both unnamed leaves this false (Infinity !== Infinity is false), so the
    // ranked cards fall through to the chosen sort.
    if (na !== nb) return na - nb
    switch (state.sort) {
      case 'score-desc':
        return parseFloat(b.dataset.score || 0) - parseFloat(a.dataset.score || 0)
      case 'score-asc':
        return parseFloat(a.dataset.score || 0) - parseFloat(b.dataset.score || 0)
      case 'family':
        return (a.dataset.dim || '').localeCompare(b.dataset.dim || '')
      default:
        return (
          (a.dataset.dim || '').localeCompare(b.dataset.dim || '') ||
          parseFloat(b.dataset.score || 0) - parseFloat(a.dataset.score || 0)
        )
    }
  }

  function buildUngrouped() {
    ugGrid.innerHTML = ''
    var q = state.search.toLowerCase()
    var clones = []
    document.querySelectorAll('.dim-card').forEach(function (card) {
      var groups = (card.dataset.family || '').split(' ')
      var fam = state.family === 'All' || groups.indexOf(state.family) >= 0
      var typ = !state.type || card.dataset.type === state.type
      var moo = !state.mood || card.dataset.mood === state.mood
      var txt = !q || card.dataset.name.toLowerCase().indexOf(q) >= 0
      var hid = state.showHidden || card.dataset.hidden !== '1'
      if (!(fam && typ && moo && txt && hid)) return
      card.querySelectorAll('.cand').forEach(function (c) {
        if (state.shortlisted && !c.dataset.shortlisted) return
        if (state.flagged && !below(c, flagThreshold)) return
        if (state.maxScore && !below(c, parseFloat(state.maxScore))) return
        var clone = c.cloneNode(true)
        clone.style.display = ''
        // Drop the detail panel from the clone. It is the whole lightbox
        // payload — terrain, structures, biomes, ~2700px of markup — and
        // duplicating it per candidate took the DOM to 1618 .cand nodes at
        // only 809 candidates. Sorting then rebuilt all of them, which is the
        // cliff behind the hang seen while hi-res probes were in flight.
        // candDetailFor() resolves it from the original instead.
        var det = clone.querySelector('.cand-detail')
        if (det) det.remove()
        var lbl = clone.querySelector('.cand-dim-label')
        if (lbl) lbl.style.display = 'block'
        clones.push(clone)
      })
    })
    clones.sort(candSort)
    var frag = document.createDocumentFragment()
    clones.forEach(function (el) {
      frag.appendChild(el)
    })
    ugGrid.appendChild(frag)
  }

  function sortCandsInGroups() {
    document.querySelectorAll('.all-cands').forEach(function (container) {
      var items = Array.from(container.querySelectorAll('.cand'))
      items.sort(candSort)
      items.forEach(function (el) {
        container.appendChild(el)
      })
    })
  }

  // The filtered set going empty used to render as blank space under a stats
  // line that still claimed "81 dimensions", so a typo'd search was
  // indistinguishable from a broken page.
  function updateEmptyState(visibleCount) {
    var el = document.getElementById('empty-state')
    if (!el) return
    el.hidden = visibleCount > 0
  }

  // Recomputed off visibleDimensions() every filter change narrows it, so it
  // agrees with grouped AND ungrouped view alike — ungrouped mode never sets
  // .dim-card.hidden, only its own clones in #ungrouped-grid, so that class
  // alone cannot answer "what is shown" in both modes. Reads data-cands and
  // data-screened (SeedBank totals) off each dimension's card rather than
  // roster-limited counts; "shortlisted" counts .cand[data-shortlisted]
  // directly, since every shortlisted seed is always drawn as its own card.
  var summaryStatsEl = document.getElementById('summary-stats')
  function updateSummaryStats() {
    if (!summaryStatsEl) return
    var names = visibleDimensions()
    var rolled = 0, seeds = 0, candidates = 0, shortlisted = 0, flagged = 0
    names.forEach(function (name) {
      var card = document.querySelector('.dim-card[data-name="' + CSS.escape(name) + '"]')
      if (!card) return
      var cands = parseInt(card.dataset.cands || '0', 10)
      if (cands > 0) rolled++
      candidates += cands
      seeds += parseInt(card.dataset.screened || '0', 10)
      shortlisted += card.querySelectorAll('.cand[data-shortlisted]').length
      if (card.dataset.flagged === '1') flagged++
    })
    summaryStatsEl.innerHTML = '<b>' + rolled + '/' + names.length + '</b> dimensions rolled &middot; <b>'
      + seeds + '</b> seeds checked &middot; <b>' + candidates + '</b> candidates &middot; <b>'
      + shortlisted + '</b> shortlisted &middot; <b>' + flagged + '</b> flagged'
  }

  // Each nav menu's summary carries its own current selection. Collapsed, a
  // menu that cannot say what it is filtering by hides the filter itself.
  function setMenuLabel(id, text) {
    var el = document.getElementById(id)
    if (el) el.textContent = text || ''
  }
  function updateMenuLabels() {
    // Type carries the family buttons and the declared-type select; Filters
    // carries everything that removes cards; View everything that redraws
    // them. Each label counts only what its own menu owns.
    var type = []
    if (state.family && state.family !== 'All') type.push(state.family)
    if (state.type) type.push(state.type)
    setMenuLabel('type-label', type.join(' · '))
    var filters = ['mood', 'flagged', 'maxScore', 'shortlisted']
      .filter(function (k) { return state[k] }).length
    setMenuLabel('filters-label', filters ? String(filters) : '')
    var views = ['ungrouped', 'scatter', 'hidden', 'borders']
      .filter(function (k) { return state[k] }).length
    setMenuLabel('view-label', views ? String(views) : '')
  }

  function applyState() {
    document.querySelectorAll('.family-btn').forEach(function (b) {
      var active = b.dataset.family === state.family
      b.classList.toggle('active', active)
      b.setAttribute('aria-pressed', active ? 'true' : 'false')
    })
    updateMenuLabels()
    if (typeEl) typeEl.value = state.type
    moodEl.value = state.mood
    sortEl.value = state.sort
    searchEl.value = state.search
    flaggedEl.checked = state.flagged
    maxScoreEl.value = state.maxScore
    shortlistedEl.checked = state.shortlisted
    ungroupedEl.checked = state.ungrouped
    hiddenEl.checked = state.showHidden
    document.body.classList.toggle('shortlist-filter', state.shortlisted)
    document.body.classList.toggle('ungrouped', state.ungrouped)
    var q = state.search.toLowerCase()
    if (state.ungrouped) {
      buildUngrouped()
    } else {
      var cards = Array.from(document.querySelectorAll('.dim-card'))
      cards.forEach(function (c) {
        var groups = (c.dataset.family || '').split(' ')
        var fam = state.family === 'All' || groups.indexOf(state.family) >= 0
        var typ = !state.type || c.dataset.type === state.type
        var moo = !state.mood || c.dataset.mood === state.mood
        var txt = !q || c.dataset.name.toLowerCase().indexOf(q) >= 0
        var flg = !state.flagged || c.dataset.flagged === '1'
        // The card's score is the dimension's BEST, so this asks "nothing here
        // reaches the bar" — the same question the flag asks at a fixed 80.
        var scr = !state.maxScore || below(c, parseFloat(state.maxScore))
        var shl = !state.shortlisted || c.querySelector('.cand[data-shortlisted]') !== null
        var hid = state.showHidden || c.dataset.hidden !== '1'
        c.classList.toggle('hidden', !(fam && typ && moo && txt && flg && scr && shl && hid))
      })
      var visible = cards.filter(function (c) {
        return !c.classList.contains('hidden')
      })
      visible.sort(function (a, b) {
        switch (state.sort) {
          case 'score-desc':
            return parseFloat(b.dataset.score) - parseFloat(a.dataset.score)
          case 'score-asc':
            return parseFloat(a.dataset.score) - parseFloat(b.dataset.score)
          case 'family':
            return (
              (a.dataset.family || '').localeCompare(b.dataset.family || '') ||
              a.dataset.name.localeCompare(b.dataset.name)
            )
          case 'candidates':
            return parseInt(b.dataset.cands) - parseInt(a.dataset.cands)
          default:
            // The four reserved dimensions lead the default sort. Not strictly by
            // name, deliberately: they are the worlds every session starts
            // from, and hunting for "overworld" under O every time is the
            // sort serving the alphabet rather than the person reading it.
            // Only this default does it — an explicitly chosen sort stays
            // strictly on its own key.
            return (
              reservedRank(a.dataset.name) - reservedRank(b.dataset.name) ||
              a.dataset.name.localeCompare(b.dataset.name)
            )
        }
      })
      visible.forEach(function (c) {
        grid.appendChild(c)
      })
      sortCandsInGroups()
      updateEmptyState(visible.length)
    }
    if (state.ungrouped) updateEmptyState(ugGrid.children.length)
    updateSummaryStats()
    // The scatter honours the same filters; a scatter showing everything
    // while the grid shows one family answers a different question.
    if (window.refreshScatter) window.refreshScatter()
    // The roller's Filtered option rolls whatever is left after this, so its
    // count has to move with the filters rather than with the page load.
    if (window.rollFilteredCount) window.rollFilteredCount()
    writeHash(state)
  }

  /**
   * The dimensions the grid is SHOWING, in the order it is showing them.
   *
   * <p>Read back out of the DOM applyState has just arranged, not recomputed:
   * the family buttons, the type and mood filters, the search, the sort and
   * the flat-view rebuild all end by writing their result there, so the DOM
   * is the one place the display order actually exists. A second
   * implementation would be a second answer.
   *
   * <p>Grouped view orders whole cards. Flat view orders candidates across
   * dimensions, so a dimension takes the place of its first visible one.
   */
  function visibleDimensions() {
    var nodes = document.body.classList.contains('ungrouped')
      ? ugGrid.querySelectorAll('.cand[data-dim]')
      : grid.querySelectorAll('.dim-card:not(.hidden)')
    var out = []
    Array.prototype.forEach.call(nodes, function (el) {
      // A card carries both spellings and a candidate only data-dim; both
      // are the slug the server matches on.
      var name = el.dataset.dim || el.dataset.name
      if (name && out.indexOf(name) < 0) out.push(name)
    })
    return out
  }
  window.visibleDimensions = visibleDimensions

  document.querySelectorAll('.family-btn').forEach(function (b) {
    b.addEventListener('click', function () {
      state.family = b.dataset.family
      var menu = b.closest('.nav-menu')
      if (menu) menu.open = false
      applyState()
    })
  })

  // Three menus in one row: opening one closes the others, and a click
  // anywhere else closes all of them. Without this they stack open over the
  // grid and each has to be dismissed by hand.
  document.addEventListener('click', function (e) {
    var inside = e.target.closest ? e.target.closest('.nav-menu') : null
    document.querySelectorAll('.nav-menu[open]').forEach(function (m) {
      if (m !== inside) m.open = false
    })
  })
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape') return
    document.querySelectorAll('.nav-menu[open]').forEach(function (m) {
      m.open = false
    })
  })

  var clearBtn = document.getElementById('clear-filters')
  if (clearBtn) {
    clearBtn.addEventListener('click', function () {
      state.family = 'All'
      state.type = ''
      state.mood = ''
      state.search = ''
      state.flagged = false
      state.maxScore = ''
      state.shortlisted = false
      applyState()
      searchEl.focus()
    })
  }
  if (typeEl) typeEl.addEventListener('change', function () {
    state.type = typeEl.value
    applyState()
  })
  moodEl.addEventListener('change', function () {
    state.mood = moodEl.value
    applyState()
  })
  sortEl.addEventListener('change', function () {
    state.sort = sortEl.value
    applyState()
  })
  // applyState re-filters every card in the grid, which is thousands of
  // nodes here — running it per keystroke made typing lag. Wait for the
  // typing to stop, and ignore one- and two-character terms that would
  // match nearly everything anyway.
  var searchTimer = null
  searchEl.addEventListener('input', function () {
    clearTimeout(searchTimer)
    searchTimer = setTimeout(function () {
      var v = searchEl.value.trim()
      var next = v.length >= 3 ? searchEl.value : ''
      if (next === state.search) return
      state.search = next
      applyState()
    }, 200)
  })
  flaggedEl.addEventListener('change', function () {
    state.flagged = flaggedEl.checked
    applyState()
  })
  maxScoreEl.addEventListener('change', function () {
    state.maxScore = maxScoreEl.value
    applyState()
  })
  shortlistedEl.addEventListener('change', function () {
    state.shortlisted = shortlistedEl.checked
    applyState()
  })
  ungroupedEl.addEventListener('change', function () {
    state.ungrouped = ungroupedEl.checked
    // Flat view exists to rank candidates ACROSS dimensions. Under the default
    // name sort it only stripped the card chrome and left every dimension's
    // candidates clustered in the same order — the mode silently did nothing.
    if (state.ungrouped && state.sort === 'name') state.sort = 'score-desc'
    applyState()
  })
  hiddenEl.addEventListener('change', function () {
    state.showHidden = hiddenEl.checked
    applyState()
  })
  document.getElementById('f-borders').addEventListener('change', function (e) {
    var on = e.target.checked
    document.body.classList.toggle('show-borders', on)
    if (lbCurrentCand && lb.classList.contains('open')) {
      openCandInLightbox(lbCurrentCand)
    }
    document.querySelectorAll('.img-wrap').forEach(function (wrap) {
      var img = wrap.querySelector('img')
      if (img) img.style.transform = on ? 'scale(' + wrap.dataset.borderScale + ')' : ''
    })
  })

  // --- World border overlays ---
  // A thumbnail's scale reflects how much of its world the picture ON
  // SCREEN actually shows: a low-res render is a fixed 512-block window
  // around spawn, a high-res one covers the whole border. The candidate
  // showing the largest fraction fills the card; everything else scales
  // down from that, so a tightly-cropped overworld thumbnail no longer
  // outsizes a small world whose 512-block window already shows it whole.
  var THUMBNAIL_BLOCKS = 512
  function imageIsHires(img) {
    return /_hires\.png(\?|$)/.test((img.getAttribute('src') || '').split('?')[0])
  }
  function imageCoverage(img, diameter) {
    return imageIsHires(img) ? Math.max(THUMBNAIL_BLOCKS, diameter) : THUMBNAIL_BLOCKS
  }
  function wrapImages () {
    var entries = []
    document.querySelectorAll('.dim-card').forEach(function (card) {
      var radius = parseFloat(card.dataset.radius || 0)
      if (!radius) return
      var diameter = radius * 2
      card.dataset.borderDiameter = diameter
      var spawnX = parseFloat(card.dataset.spawnX || 0) || 0
      var spawnZ = parseFloat(card.dataset.spawnZ || 0) || 0
      card.querySelectorAll('img').forEach(function (img) {
        var hires = imageIsHires(img)
        entries.push({
          img: img, diameter: diameter, hires: hires,
          coverage: imageCoverage(img, diameter),
          // The thumbnail is spawn-centred; the whole-world render is
          // centred on the border itself (CandidateRender.draw).
          cx: hires ? 0 : spawnX, cz: hires ? 0 : spawnZ,
        })
      })
    })
    var maxRatio = 0
    entries.forEach(function (e) {
      e.ratio = e.coverage / e.diameter
      if (e.ratio > maxRatio) maxRatio = e.ratio
    })
    if (!maxRatio) return
    var bordersOn = document.body.classList.contains('show-borders')
    entries.forEach(function (e) {
      var img = e.img
      var wrap = img.parentElement
      if (!wrap.classList.contains('img-wrap')) {
        var div = document.createElement('div')
        div.className = 'img-wrap'
        img.parentNode.insertBefore(div, img)
        div.appendChild(img)
        wrap = div
      }
      var scale = Math.max(0.05, e.ratio / maxRatio)
      wrap.dataset.borderScale = scale.toFixed(4)
      if (bordersOn) img.style.transform = 'scale(' + wrap.dataset.borderScale + ')'
      var lbl = wrap.querySelector('.border-label')
      if (!lbl) {
        lbl = document.createElement('div')
        lbl.className = 'border-label'
        wrap.appendChild(lbl)
      }
      lbl.textContent = e.coverage + 'b'
      var ring = wrap.querySelector('.border-ring')
      // A ring only means something when the border sits INSIDE the
      // frame — when the render covers less than the border, the edge is
      // off-screen and there is nothing here to outline.
      if (e.coverage > e.diameter) {
        if (!ring) {
          ring = document.createElement('div')
          ring.className = 'border-ring'
          wrap.appendChild(ring)
        }
        var pct = window.lbProjectRadius(e.diameter, e.coverage)
        ring.style.left = window.lbProject(-e.diameter / 2 - e.cx, e.coverage) + '%'
        ring.style.top = window.lbProject(-e.diameter / 2 - e.cz, e.coverage) + '%'
        ring.style.width = pct + '%'
        ring.style.height = pct + '%'
      } else if (ring) {
        ring.remove()
      }
      // The HD badge is server-rendered for every card with a whole-world
      // render, but only visible once its image is actually the one shown.
      var badge = wrap.parentElement && wrap.parentElement.querySelector('.hires-badge')
      if (badge) badge.classList.toggle('visible', e.hires)
    })
  }
  // The roller replaces #grid on every bank change, and the fresh markup has
  // no wrappers — without re-running this the border overlay stops working
  // for the whole of a run.
  window.wrapCardImages = wrapImages
  wrapImages()

  applyState()

  // --- Restore the view the URL describes ---
  //
  // Runs on load, on Back/Forward, and after the roller swaps #grid on a
  // re-rank (which throws away the expanded card with the old markup). The
  // seed is looked up in the ORIGINAL grid tile even in flat view, because
  // that is the one carrying .cand-detail — the flat clones are stripped of
  // it, and candDetailFor resolves back to the original anyway.
  function candTile(dim, seed) {
    if (!dim || !seed) return null
    var sel =
      '.cand[data-dim="' + CSS.escape(dim) + '"][data-seed="' + CSS.escape(seed) + '"]'
    return (
      (state.ungrouped && ugGrid.querySelector(sel)) || document.querySelector('#grid ' + sel)
    )
  }

  function applyRoute() {
    var r = readRoute()
    routeApplying = true
    try {
      var card = r.dim
        ? document.querySelector('.dim-card[data-name="' + CSS.escape(r.dim) + '"]')
        : null
      if (card) {
        expandCard(card)
      } else {
        document.querySelectorAll('.dim-card.expanded').forEach(function (c) {
          setExpanded(c, false)
        })
      }
      var cand = card ? candTile(r.dim, r.seed) : null
      if (cand) {
        openCandInLightbox(cand)
      } else {
        if (lb.classList.contains('open')) lbHide()
        // A seed that no longer has a tile — demoted out of the top ten by a
        // re-rank, or a hand-typed URL — must not stay in the address bar
        // claiming a candidate is open.
        setRoute(card ? r.dim : '', '')
      }
    } finally {
      routeApplying = false
    }
  }
  window.applyViewerRoute = applyRoute
  // The roller swaps #grid wholesale each time the bank changes, and the
  // fresh markup arrives unfiltered. Without this the search and family
  // filters silently reset every couple of seconds for the whole of a run —
  // exactly when narrowing the view matters most.
  window.applyFilters = applyState
  if (routed) {
    applyRoute()
    window.addEventListener('popstate', function () {
      state = readHash()
      applyState()
      applyRoute()
    })
  }

  // --- Image auto-refresh: retry missing images + upgrade to hires ---
  ;(function autoRefreshImages() {
    var pending = new Set()
    document.querySelectorAll('img[data-hires]').forEach(function (img) {
      // Already showing the whole-world render chosen server-side —
      // nothing here for this loop to upgrade.
      if (!imageIsHires(img)) pending.add(img)
    })
    function tick() {
      if (!pending.size) return
      pending.forEach(function (img) {
        if (!img.naturalWidth || !img.src) {
          var normalSrc = img.src || img.dataset.hires.replace('_hires', '')
          var probe = new Image()
          probe.onload = function () {
            img.src = normalSrc.split('?')[0] + '?t=' + Date.now()
            tryHires(img)
          }
          probe.src = normalSrc.split('?')[0] + '?t=' + Date.now()
        } else {
          tryHires(img)
        }
      })
    }
    function tryHires(img) {
      var hires = img.dataset.hires
      if (!hires) {
        pending.delete(img)
        return
      }
      var hi = new Image()
      hi.onload = function () {
        // Remember the zoomed-in URL before overwriting it. The lightbox
        // reads its low-res source off this element, so an in-place upgrade
        // with nothing kept leaves the two resolutions pointing at the same
        // file: the toggle then swaps hi-res for hi-res and appears dead,
        // and lbMapCoverage keeps scaling the overlay for the wide render.
        if (!img.dataset.low) img.dataset.low = img.src
        img.src = hi.src
        // The border scale, ring, label and HD badge are all keyed off
        // which render is on screen — an upgraded thumbnail needs them
        // redrawn, not just relabelled.
        if (window.wrapCardImages) window.wrapCardImages()
        pending.delete(img)
      }
      hi.src = hires.split('?')[0] + '?t=' + Date.now()
    }
    tick()
    var iv = setInterval(function () {
      tick()
      if (!pending.size) clearInterval(iv)
    }, 10000)
  })()

  // --- Card expand/collapse ---
  // The dimension you have open is the one worth spending seeds and cores on,
  // so opening one tells the server to roll and render it first. Closing
  // clears the hint; it never cancels anything already started.
  var focusSent = null
  function sendFocus(slug) {
    if (!live || slug === focusSent) return
    focusSent = slug
    fetch('/focus', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dim: slug || '' }),
    }).catch(function () {
      // A hint that did not arrive is not worth a message: the queue just
      // keeps its ordinary order.
      focusSent = null
    })
  }
  function setExpanded(card, on) {
    card.classList.toggle('expanded', on)
    // aria-expanded belongs on the control, not on the container it reveals.
    var trigger = card.querySelector('.compact-trigger')
    if (trigger) trigger.setAttribute('aria-expanded', on ? 'true' : 'false')
    if (on) {
      sendFocus(card.dataset.name)
    } else if (focusSent === card.dataset.name) {
      sendFocus('')
    }
  }
  function expandCard(card) {
    if (card.classList.contains('expanded')) return
    document.querySelectorAll('.dim-card.expanded').forEach(function (c) {
      setExpanded(c, false)
    })
    setExpanded(card, true)
    sortCandsInGroups()
    setRoute(card.dataset.name, '')
    card.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }
  grid.addEventListener('click', function (e) {
    if (e.target.closest('.action-btn, .pick, .close-btn, .cand')) return
    var card = e.target.closest('.dim-card')
    if (!card) return
    expandCard(card)
  })
  // No keydown shim: .compact-trigger is a real <button>, so Enter and Space
  // are native. The old shim existed because the card was a div with
  // role=button; keeping it would double-fire.
  grid.addEventListener('click', function (e) {
    var close = e.target.closest('.close-btn')
    if (close) {
      var card = close.closest('.dim-card')
      setExpanded(card, false)
      setRoute('', '')
      // The close button is inside the panel that just disappeared, so focus
      // would land on <body>. Hand it back to the control that opened it.
      var trigger = card.querySelector('.compact-trigger')
      if (trigger) trigger.focus()
    }
  })

  // --- Action buttons (live server only) ---
  if (!live) return

  function postJSON(url, data) {
    return fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    }).catch(function () {
      return null
    })
  }

  function handleAction(e) {
    var pick = e.target.closest('.pick')
    if (pick) {
      pick.disabled = true
      pick.textContent = 'saving…'
      postJSON('/pick', { dim: pick.dataset.dim, seed: pick.dataset.seed }).then(function (res) {
        if (res && res.ok) location.reload()
        else {
          pick.disabled = false
          pick.textContent = 'Could not save'
        }
      })
      return
    }
    var reroll = e.target.closest('.action-btn.reroll')
    if (reroll) {
      reroll.disabled = true
      reroll.textContent = 'Rolling…'
      postJSON('/reroll', { dim: reroll.dataset.dim, pool: 5000, count: 100 }).then(function (res) {
        if (res && res.ok)
          res.json().then(function (d) {
            pollJob(d.job_id, reroll)
          })
        else {
          reroll.disabled = false
          reroll.textContent = 'Roll failed — check terminal'
        }
      })
      return
    }
    var edit = e.target.closest('.action-btn.edit')
    if (edit) {
      edit.disabled = true
      edit.textContent = 'Opening…'
      postJSON('/edit-config', { dim: edit.dataset.dim }).then(function (res) {
        edit.textContent = res && res.ok ? 'Opened' : 'Could not open'
        setTimeout(function () {
          edit.disabled = false
          edit.textContent = 'Edit'
        }, 2500)
      })
      return
    }
    var createDim = e.target.closest('.action-btn.create-dim')
    if (createDim) {
      openConfigDialog('fork', createDim.dataset.dim, createDim.dataset.seed)
      return
    }
    var configureBtn = e.target.closest('.action-btn.configure')
    if (configureBtn) {
      openConfigDialog('edit', configureBtn.dataset.dim, '')
      return
    }
    var hideBtn = e.target.closest('.action-btn.hide')
    if (hideBtn) {
      hideBtn.disabled = true
      hideBtn.textContent = 'Hiding…'
      postJSON('/hide-dimension', { dim: hideBtn.dataset.dim }).then(function (res) {
        if (res && res.ok) location.reload()
        else {
          hideBtn.disabled = false
          hideBtn.textContent = 'Could not hide'
        }
      })
      return
    }
    var removeBtn = e.target.closest('.action-btn.remove')
    if (removeBtn) {
      if (!confirm('Remove "' + removeBtn.dataset.dim + '" and all its candidate data? This cannot be undone.'))
        return
      removeBtn.disabled = true
      removeBtn.textContent = 'Removing…'
      postJSON('/remove-dimension', { dim: removeBtn.dataset.dim }).then(function (res) {
        if (res && res.ok) location.reload()
        else {
          removeBtn.disabled = false
          removeBtn.textContent = 'Could not remove'
        }
      })
      return
    }
    var slBtn = e.target.closest('.action-btn.shortlist')
    if (slBtn) {
      // In the lightbox the button lives in copied markup — fall back to the
      // original candidate element so its shortlist state stays in sync.
      var cand = slBtn.closest('.cand') || lbCurrentCand
      var isShortlisted = cand ? cand.dataset.shortlisted === '1' : slBtn.textContent.indexOf('Remove') >= 0
      slBtn.disabled = true
      slBtn.textContent = isShortlisted ? 'Removing…' : 'Shortlisting…'
      postJSON('/shortlist', {
        dim: slBtn.dataset.dim,
        seed: slBtn.dataset.seed,
        action: isShortlisted ? 'remove' : 'add',
      })
        .then(function (res) {
          if (!res || !res.ok) {
            slBtn.disabled = false
            slBtn.textContent = 'Could not update'
            return
          }
          return res.json()
        })
        .then(function (data) {
          if (!data) return
          if (data.shortlisted) {
            if (cand) cand.dataset.shortlisted = '1'
            slBtn.textContent = 'Remove from shortlist'
          } else {
            if (cand) delete cand.dataset.shortlisted
            slBtn.textContent = 'Shortlist'
          }
          slBtn.disabled = false
          var cardEl = cand && cand.closest('.dim-card')
          if (cardEl) {
            cardEl.dataset.shortlisted = cardEl.querySelector('.cand[data-shortlisted]') ? '1' : '0'
          }
              })
      return
    }
  }
  grid.addEventListener('click', handleAction)
  ugGrid.addEventListener('click', handleAction)
  lb.addEventListener('click', handleAction)

  function pollJob(id, btn) {
    var iv = setInterval(function () {
      fetch('/job/' + id)
        .then(function (r) {
          return r.json()
        })
        .then(function (d) {
          if (d.status === 'done') {
            clearInterval(iv)
            btn.textContent = 'Done'
            setTimeout(function () {
              location.reload()
            }, 800)
          } else if (d.status === 'failed') {
            clearInterval(iv)
            btn.disabled = false
            btn.textContent = 'Roll failed — check terminal'
          } else {
            btn.textContent = 'Rolling… ' + (d.elapsed || '') + 's'
          }
        })
        .catch(function () {})
    }, 2000)
  }

  // --- Fork/create/edit config form ---
  var cfSchema = null
  var cfParent = null // parent (or existing) config for pre-population
  function $id(id) {
    return document.getElementById(id)
  }
  function fetchSchema() {
    if (cfSchema) return Promise.resolve(cfSchema)
    return fetch('/fork-schema')
      .then(function (r) {
        return r.json()
      })
      .then(function (s) {
        cfSchema = s
        return s
      })
  }
  function fillSelect(el, options, labels, selected) {
    el.innerHTML = ''
    options.forEach(function (opt, i) {
      var o = document.createElement('option')
      o.value = opt
      o.textContent = (labels && labels[i]) || opt || '(inherit)'
      if (opt === selected) o.selected = true
      el.appendChild(o)
    })
  }
  // Searchable checklist: filter box over checkboxes, capped render so
  // 1800 biomes stay snappy (matches render lazily on filter input).
  function renderChecklist(container, ids, checked, filter, cap) {
    container.innerHTML = ''
    var q = (filter || '').toLowerCase()
    var shown = 0
    var hidden = 0
    ids.forEach(function (id) {
      var isChecked = checked.indexOf(id) >= 0
      // checked entries always render so selections stay visible
      if (!isChecked && q && id.indexOf(q) < 0) return
      if (!isChecked && shown >= (cap || 250)) {
        hidden++
        return
      }
      var label = document.createElement('label')
      var cb = document.createElement('input')
      cb.type = 'checkbox'
      cb.value = id
      cb.checked = isChecked
      label.appendChild(cb)
      label.appendChild(document.createTextNode(' ' + id))
      container.appendChild(label)
      shown++
    })
    if (hidden > 0) {
      var more = document.createElement('div')
      more.className = 'cf-more'
      more.textContent = hidden + ' more — type to filter'
      container.appendChild(more)
    }
  }
  function checkedValues(container) {
    return Array.prototype.map.call(
      container.querySelectorAll('input:checked'),
      function (cb) {
        return cb.value
      }
    )
  }
  function addStructRow(container, kind, name, spec) {
    var row = document.createElement('div')
    row.className = 'cf-row'
    var input = document.createElement('input')
    input.type = 'text'
    input.placeholder = 'structure…'
    input.setAttribute('list', 'cf-structs-list')
    input.value = name || ''
    row.appendChild(input)
    if (kind === 'want') {
      var band = document.createElement('select')
      var bands = (cfSchema && cfSchema.bands) || []
      fillSelect(band, bands.concat(['custom']), null, typeof spec === 'string' ? spec : 'custom')
      if (typeof spec !== 'string' && !spec) band.value = bands[0] || 'spread'
      row.appendChild(band)
      var lo = document.createElement('input')
      lo.type = 'number'
      lo.placeholder = 'min'
      var hi = document.createElement('input')
      hi.type = 'number'
      hi.placeholder = 'max'
      if (spec && typeof spec === 'object') {
        lo.value = spec.min
        hi.value = spec.max
      }
      function syncCustom() {
        var isCustom = band.value === 'custom'
        lo.hidden = hi.hidden = !isCustom
      }
      band.addEventListener('change', syncCustom)
      row.appendChild(lo)
      row.appendChild(hi)
      syncCustom()
    } else {
      var md = document.createElement('input')
      md.type = 'number'
      md.placeholder = 'min distance'
      if (spec && typeof spec === 'object' && spec.minDistance != null) md.value = spec.minDistance
      row.appendChild(md)
    }
    var del = document.createElement('button')
    del.type = 'button'
    del.className = 'cf-del'
    del.textContent = '×'
    del.setAttribute('aria-label', 'Remove row')
    del.addEventListener('click', function () {
      row.remove()
      updateHostileWarn()
    })
    row.appendChild(del)
    input.addEventListener('change', updateHostileWarn)
    container.appendChild(row)
  }
  function collectStructRows(container, kind) {
    var out = {}
    container.querySelectorAll('.cf-row').forEach(function (row) {
      var name = row.querySelector('input[list]').value.trim()
      if (!name) return
      if (kind === 'want') {
        var band = row.querySelector('select').value
        if (band === 'custom') {
          var nums = row.querySelectorAll('input[type=number]')
          out[name] = { min: parseInt(nums[0].value || '0', 10), max: parseInt(nums[1].value || '0', 10) }
        } else {
          out[name] = band
        }
      } else {
        var md = row.querySelector('input[type=number]').value
        out[name] = md ? { minDistance: parseInt(md, 10) } : {}
      }
    })
    return out
  }
  function currentBorder() {
    var sel = $id('cf-border').value
    if (sel === 'custom') return parseInt($id('cf-border-custom').value || '0', 10) || null
    return sel ? parseInt(sel, 10) : null
  }
  function updateBandHint() {
    if (!cfSchema) return
    var r =
      currentBorder() ||
      (cfParent && cfParent.borders && cfParent.borders.player) ||
      8192
    var parts = []
    Object.keys(cfSchema.band_ranges || {}).forEach(function (b) {
      var f = cfSchema.band_ranges[b]
      parts.push(b + ' = ' + Math.round(f[0] * r) + '–' + Math.round(f[1] * r) + ' blocks')
    })
    $id('cf-band-hint').textContent = 'At radius ' + r + ': ' + parts.join(' · ')
    $id('cf-border-custom-wrap').hidden = $id('cf-border').value !== 'custom'
  }
  function updateHostileWarn() {
    if (!cfSchema) return
    var warn = $id('cf-hostile-warn')
    if ($id('cf-hostile').checked) {
      warn.hidden = true
      return
    }
    var hostile = cfSchema.hostile_structures || []
    var stripped = Object.keys(collectStructRows($id('cf-wants'), 'want')).filter(function (n) {
      return hostile.indexOf(n) >= 0
    })
    warn.hidden = stripped.length === 0
    warn.textContent = stripped.length
      ? 'hostileSpawning off strips hostile wants: ' + stripped.join(', ')
      : ''
  }
  function updateMoodBlurb() {
    if (!cfSchema) return
    $id('cf-mood-blurb').textContent = (cfSchema.moods || {})[$id('cf-mood').value] || ''
  }
  function refreshSpawnFilter() {
    var chosen = checkedValues($id('cf-biomes'))
    var current = ((cfParent && cfParent.seedRoll) || {}).spawnFilter || []
    renderChecklist($id('cf-spawnfilter'), chosen, current, '', 500)
  }
  function populateForm(mode, parentCfg) {
    cfParent = parentCfg || {}
    var s = cfSchema
    fillSelect($id('cf-type'), s.types, null, cfParent.type || 'overworld')
    fillSelect($id('cf-noise'), s.noise_settings, null, cfParent.noiseSettings || '')
    fillSelect($id('cf-density'), s.structure_density, null, cfParent.structureDensity || '')
    var moods = [''].concat(Object.keys(s.moods))
    fillSelect($id('cf-mood'), moods, null, (cfParent.seedRoll || {}).mood || '')
    fillSelect($id('cf-water'), s.waters, null, (cfParent.seedRoll || {}).water || '')
    var mults = ['', '0', '0.5', '1', '1.5', '2', '2.5', '3']
    var dif = cfParent.difficulty || {}
    fillSelect($id('cf-mobmult'), mults, null, dif.mobMultiplier != null ? String(dif.mobMultiplier) : '')
    fillSelect($id('cf-luck'), mults, null, dif.playerLuck != null ? String(dif.playerLuck) : '')
    $id('cf-hostile').checked = dif.hostileSpawning !== false
    var portal = cfParent.portal || {}
    $id('cf-frame').value = typeof portal.frameBlock === 'string' ? portal.frameBlock : ''
    $id('cf-igniter').value = portal.igniterItem || ''
    $id('cf-color').value = portal.color || ''
    $id('cf-particle').value = portal.particleType || ''
    $id('cf-scale').value = portal.scale != null ? String(portal.scale) : ''
    var border = (cfParent.borders || {}).player
    var std = ['256', '512', '1024', '2048', '4096', '8192']
    $id('cf-border').value = border == null ? '' : std.indexOf(String(border)) >= 0 ? String(border) : 'custom'
    $id('cf-border-custom').value = border || ''
    var allBiomes = []
    Object.keys(s.biomes).forEach(function (ns) {
      allBiomes = allBiomes.concat(s.biomes[ns])
    })
    var chosenBiomes = cfParent.biomes || []
    // object-form biome entries carry {id: ...}
    chosenBiomes = chosenBiomes.map(function (b) {
      return typeof b === 'string' ? b : b.id
    })
    $id('cf-biome-filter').value = ''
    renderChecklist($id('cf-biomes'), allBiomes, chosenBiomes, '')
    $id('cf-biome-count').textContent = '(' + chosenBiomes.length + ' selected)'
    var dl = $id('cf-structs-list')
    dl.innerHTML = ''
    s.structures.forEach(function (n) {
      var o = document.createElement('option')
      o.value = n
      dl.appendChild(o)
    })
    $id('cf-wants').innerHTML = ''
    var wants = (cfParent.structures || {}).wants || (cfParent.seedRoll || {}).wants || {}
    Object.keys(wants).forEach(function (n) {
      addStructRow($id('cf-wants'), 'want', n, wants[n])
    })
    $id('cf-shuns').innerHTML = ''
    var shuns = (cfParent.structures || {}).shuns || {}
    if (Object.prototype.toString.call(shuns) === '[object Object]') {
      Object.keys(shuns).forEach(function (n) {
        addStructRow($id('cf-shuns'), 'shun', n, shuns[n])
      })
    }
    refreshSpawnFilter()
    updateBandHint()
    updateMoodBlurb()
    updateHostileWarn()
    Array.prototype.forEach.call(document.querySelectorAll('#create-dim-dialog .cd-error'), function (el) {
      el.textContent = ''
    })
  }
  function openConfigDialog(mode, dim, seed) {
    var dlg = $id('create-dim-dialog')
    $id('cd-mode').value = mode
    $id('cd-parent-dim').value = mode === 'fork' ? dim : ''
    $id('cd-seed').value = seed || ''
    $id('cd-title').textContent =
      mode === 'fork' ? 'Fork "' + dim + '" as new dimension' : 'Configure "' + dim + '"'
    $id('cd-name').value = mode === 'fork' ? dim + '_fork' : dim
    $id('cd-name').readOnly = mode === 'edit'
    $id('cd-create').disabled = false
    $id('cd-create').textContent = mode === 'edit' ? 'Save' : 'Create'
    var configPromise = fetch('/dim-config?dim=' + encodeURIComponent(dim)).then(function (r) {
      return r.json()
    })
    Promise.all([fetchSchema(), configPromise]).then(function (results) {
      var cfg = (results[1] && results[1].config) || {}
      $id('cd-desc').value = mode === 'edit' ? cfg.description || '' : ''
      populateForm(mode, cfg)
      dlg.showModal()
    })
  }
  window.openConfigDialog = openConfigDialog
  $id('cf-biome-filter').addEventListener('input', function () {
    var s = cfSchema
    if (!s) return
    var allBiomes = []
    Object.keys(s.biomes).forEach(function (ns) {
      allBiomes = allBiomes.concat(s.biomes[ns])
    })
    renderChecklist($id('cf-biomes'), allBiomes, checkedValues($id('cf-biomes')), this.value)
  })
  $id('cf-biomes').addEventListener('change', function () {
    $id('cf-biome-count').textContent = '(' + checkedValues($id('cf-biomes')).length + ' selected)'
    refreshSpawnFilter()
  })
  $id('cf-border').addEventListener('change', updateBandHint)
  $id('cf-border-custom').addEventListener('input', updateBandHint)
  $id('cf-mood').addEventListener('change', updateMoodBlurb)
  $id('cf-hostile').addEventListener('change', updateHostileWarn)
  $id('cf-add-want').addEventListener('click', function () {
    addStructRow($id('cf-wants'), 'want', '', null)
  })
  $id('cf-add-shun').addEventListener('click', function () {
    addStructRow($id('cf-shuns'), 'shun', '', null)
  })
  $id('cd-cancel').addEventListener('click', function () {
    $id('create-dim-dialog').close()
  })
  $id('cd-create').addEventListener('click', function () {
    var name = $id('cd-name').value.trim()
    var errEl = $id('cd-name-error')
    if (!/^[a-z][a-z0-9_]*$/.test(name)) {
      errEl.textContent =
        'Name must start with a letter and contain only lowercase letters, numbers, and underscores.'
      // The dialog is ~880px of accordion and Create sits at the bottom, so an
      // error painted at the top is invisible from where the click happened.
      errEl.scrollIntoView({ block: 'center', behavior: 'smooth' })
      $id('cd-name').focus()
      return
    }
    Array.prototype.forEach.call(document.querySelectorAll('#create-dim-dialog .cd-error'), function (el) {
      el.textContent = ''
    })
    var config = {
      type: $id('cf-type').value,
      noiseSettings: $id('cf-noise').value,
      structureDensity: $id('cf-density').value,
      mood: $id('cf-mood').value,
      water: $id('cf-water').value,
      biomes: checkedValues($id('cf-biomes')),
      spawnFilter: checkedValues($id('cf-spawnfilter')),
      wants: collectStructRows($id('cf-wants'), 'want'),
      shuns: collectStructRows($id('cf-shuns'), 'shun'),
      hostileSpawning: $id('cf-hostile').checked,
      frameBlock: $id('cf-frame').value,
      igniterItem: $id('cf-igniter').value,
      color: $id('cf-color').value,
      particleType: $id('cf-particle').value,
    }
    var border = currentBorder()
    if (border) config.borderRadius = border
    if ($id('cf-scale').value) config.scale = parseFloat($id('cf-scale').value)
    if ($id('cf-mobmult').value) config.mobMultiplier = parseFloat($id('cf-mobmult').value)
    if ($id('cf-luck').value) config.playerLuck = parseFloat($id('cf-luck').value)
    var btn = $id('cd-create')
    btn.disabled = true
    btn.textContent = 'Saving…'
    postJSON('/create-dimension', {
      mode: $id('cd-mode').value,
      parent_dim: $id('cd-parent-dim').value,
      seed: $id('cd-seed').value,
      name: name,
      description: $id('cd-desc').value.trim(),
      config: config,
    })
      .then(function (res) {
        return res && res.json ? res.json() : null
      })
      .then(function (data) {
        if (data && data.ok) {
          if (data.job_id) {
            btn.textContent = 'Rolling…'
            pollJob(data.job_id, btn)
          } else {
            $id('create-dim-dialog').close()
            location.reload()
          }
        } else {
          var errors = (data && data.errors) || {}
          Object.keys(errors).forEach(function (field) {
            var el = document.querySelector('#create-dim-dialog .cd-error[data-err="' + field + '"]')
            if (el) el.textContent = errors[field]
          })
          errEl.textContent = Object.keys(errors).length
            ? 'Fix the highlighted fields.'
            : (data && data.error) || 'Could not save'
          btn.disabled = false
          btn.textContent = 'Save'
        }
      })
  })
})()


// ---- Roller controls -------------------------------------------------
// Deliberately does NOT render its own candidate grid: the server-rendered
// one below already carries the map lightbox, structure overlays, score
// breakdown and pick actions. On a re-rank this refetches the regenerated
// viewer.html and swaps #grid, which is safe because every handler on the
// grid is delegated from document.
;(function () {
  if (location.protocol === 'file:') return
  var toggleBtn = document.getElementById('roll-toggle')
  var countEl = document.getElementById('roll-count')
  var progEl = document.getElementById('roll-progress')
  var fillEl = document.getElementById('rp-fill')
  var textEl = document.getElementById('rp-text')
  var dimEl = document.getElementById('roll-dim')
  var statusEl = document.getElementById('roll-status')
  var renderLowBtn = document.getElementById('render-low-toggle')
  var renderHighBtn = document.getElementById('render-high-toggle')
  if (!toggleBtn) return

  // The two render queues pause independently of the roller and of each
  // other — a stuck detail render must not block thumbnails, or the reverse.
  function toggleRenderPause (kind, btn) {
    var paused = btn.classList.contains('paused')
    btn.disabled = true
    post('/render/' + kind + '/' + (paused ? 'resume' : 'pause')).then(function () {
      btn.disabled = false
    })
  }
  if (renderLowBtn) renderLowBtn.addEventListener('click', function () {
    toggleRenderPause('low', renderLowBtn)
  })
  if (renderHighBtn) renderHighBtn.addEventListener('click', function () {
    toggleRenderPause('high', renderHighBtn)
  })

  var lastGeneration = -1
  var running = false

  ;(function fillDims () {
    var seen = {}
    document.querySelectorAll('[data-dim]').forEach(function (el) {
      var d = el.getAttribute('data-dim')
      if (d) seen[d] = 1
    })
    Object.keys(seen).sort().forEach(function (d) {
      var o = document.createElement('option')
      o.value = d; o.textContent = d
      dimEl.appendChild(o)
    })
  })()

  // Not a dimension: the sentinel the Filtered option carries. A slug can be
  // any lowercase word, so it is spelled as something no slug can be.
  var FILTERED = '__filtered__'

  function filteredDims () {
    return window.visibleDimensions ? window.visibleDimensions() : []
  }

  /**
   * Keeps the Filtered option's count honest. "Filtered" alone says nothing
   * about how big the batch is, and the batch is whatever the filters left —
   * which is the one thing worth knowing before pressing play.
   */
  function refreshFilteredOption () {
    var opt = dimEl.querySelector('option[value="' + FILTERED + '"]')
    if (!opt) return
    var n = filteredDims().length
    opt.textContent = 'Filtered (' + n + ')'
    opt.title = n
      ? 'Roll the ' + n + ' dimension(s) the grid is showing, in display order'
      : 'Nothing is visible to roll'
  }
  window.rollFilteredCount = refreshFilteredOption
  refreshFilteredOption()

  function post (path, body) {
    return fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    }).then(function (r) { return r.json() })
  }

  function refreshGrid () {
    fetch(location.pathname + '?t=' + Date.now())
      .then(function (r) { return r.text() })
      .then(function (html) {
        var doc = new DOMParser().parseFromString(html, 'text/html')
        var fresh = doc.getElementById('grid')
        var live = document.getElementById('grid')
        if (fresh && live) live.innerHTML = fresh.innerHTML
        if (window.wrapCardImages) window.wrapCardImages()
        if (window.applyFilters) window.applyFilters()
        // The swap discarded the expanded card along with the old markup;
        // the URL still says which one it was.
        if (window.applyViewerRoute) window.applyViewerRoute()
      })
      .catch(function () {})
  }

  function poll () {
    fetch('/pipeline-status').then(function (r) { return r.json() })
      .then(function (st) {
        running = !!st.running
        // The seeds input is only meaningful before you commit to a run;
        // while rolling, the same slot carries the progress instead.
        countEl.hidden = running
        progEl.hidden = !running
        if (!running) stopping = false
        toggleBtn.disabled = false
        toggleBtn.textContent = stopping ? '…' : running ? '■' : '▶'
        toggleBtn.classList.toggle('running', running)
        toggleBtn.title = toggleBtn.ariaLabel = stopping
          ? 'Stopping after the seeds in flight'
          : running ? 'Stop rolling' : 'Start rolling'
        dimEl.disabled = running
        if (running && st.dimensions) {
          // The BAR is dimensions, the text is seeds. A screen spends its
          // whole seed budget unless cancelled or told to yield, so the two
          // agree; the seed count is the live one, since a dimension mid-sweep
          // moves no dimension counter at all.
          // Rolling finishes long before rendering does; a bar pinned at
          // 100% for that tail would read as done, so it goes indeterminate
          // once there is nothing left to count.
          var doneRolling = st.surveyed >= st.dimensions
          fillEl.classList.toggle('indeterminate', doneRolling)
          fillEl.style.width = doneRolling
            ? '' : Math.min(100, st.surveyed / st.dimensions * 100) + '%'
          // Tier 1 sweeps the seed budget; tier 2 then measures the ten it
          // shortlisted, one at a time, and moves no counter at all. Naming
          // the phase is the difference between "stuck" and "working".
          var phase = (st.stage || '').split(' ')[0]
          var doing = phase === 'rolling'
            ? (st.passed || 0) + ' passed · measuring '
              + (st.shortlist_done || 0) + '/' + (st.shortlisted || 0)
            : phase === 'scoring' ? 'scoring named seeds'
            : phase
          var pend = (st.render_pending || 0) + (st.thumbnails_pending || 0)
          if (pend) doing += ' · ' + pend + ' render' + (pend === 1 ? '' : 's') + ' queued'
          textEl.textContent = doneRolling
            ? st.stage.replace('_', ' ')
            : st.surveyed + '/' + st.dimensions + ' dims · '
              + st.rolled + '/' + st.target + ' seeds · ' + doing
        }
        if (renderLowBtn) {
          renderLowBtn.classList.toggle('paused', !!st.render_paused_low)
          renderLowBtn.textContent = (st.render_paused_low ? '▶' : '⏸') + ' low'
          renderLowBtn.title = renderLowBtn.ariaLabel =
            (st.render_paused_low ? 'Resume' : 'Pause') + ' low-res renders'
        }
        if (renderHighBtn) {
          renderHighBtn.classList.toggle('paused', !!st.render_paused_high)
          renderHighBtn.textContent = (st.render_paused_high ? '▶' : '⏸') + ' high'
          renderHighBtn.title = renderHighBtn.ariaLabel =
            (st.render_paused_high ? 'Resume' : 'Pause') + ' high-res renders'
        }
        // Everything else that used to crowd the bar lives on the status
        // line under it, where it can wrap without reflowing the nav.
        var bits = []
        if (st.backfill) bits.push('enriching bank ' + st.backfill)
        if (st.enriched) bits.push(st.enriched + ' enriched')
        // Surveyed is the bar now. A dimension that spent its whole budget
        // without filling its board is the thing worth the space: it looks
        // identical to an unlucky one otherwise, and that is how twenty empty
        // boards went unexplained for hours.
        if (st.starved && st.starved.length) {
          bits.push(st.starved.length + ' short of a full board: ' + st.starved.join(', '))
        }
        // Thumbnails only. They are the whole bank's work, which is what
        // this line is about — name the dimension and say how much is left
        // rather than just "rendering". A detail render belongs to one
        // candidate's card and reports there instead.
        var lo = (st.rendering_low || [])[0]
        if (lo) bits.push('rendering ' + lo)
        if (st.render_pending) bits.push(st.render_pending + ' images queued')
        if (st.render_paused_low) bits.push('low-res renders paused')
        if (st.render_paused_high) bits.push('high-res renders paused')
        if (st.error) bits.push('error: ' + st.error)
        var detail = bits.join(' · ')
        // Second line under the counter, inside the nav group, so the
        // bar keeps its width while the detail changes length.
        var sub = document.getElementById('rp-sub')
        if (sub) sub.textContent = running ? detail : ''
        if (statusEl) statusEl.textContent = running ? '' : detail
        // One poll for the page. The open candidate's own detail render is
        // reported on its card rather than on the line above, which is about
        // the bank as a whole.
        if (window.lbRenderStatus) window.lbRenderStatus(st)
        if (st.generation !== lastGeneration) {
          lastGeneration = st.generation
          if (lastGeneration > 0) refreshGrid()
        }
      })
      .catch(function () {})
      .then(function () { setTimeout(poll, 2000) })
  }

  var stopping = false
  toggleBtn.addEventListener('click', function () {
    if (running) {
      // A seed already being measured runs to completion, so a stop lands
      // in seconds rather than instantly. Say so — an unchanged button for
      // half a minute reads as a dead control.
      stopping = true
      toggleBtn.disabled = true
      toggleBtn.title = toggleBtn.ariaLabel = 'Stopping after the seeds in flight'
      post('/pipeline/stop')
      return
    }
    toggleBtn.disabled = true
    var body = { count: parseInt(countEl.value, 10) || 100, dim: dimEl.value || null }
    if (dimEl.value === FILTERED) {
      var dims = filteredDims()
      if (!dims.length) {
        // A fact about the filters, not a server error. Posting a run that
        // could only be refused would report it as one.
        if (statusEl) {
          statusEl.textContent = 'Nothing is visible to roll — clear a filter, or pick All.'
        }
        toggleBtn.disabled = false
        return
      }
      // The sentinel names no dimension; the list is the instruction, and
      // its ORDER is what makes a sorted grid a work queue.
      body.dim = null
      body.dims = dims
    }
    post('/pipeline/start', body)
      .then(function (r) {
        if (r.error && statusEl) statusEl.textContent = r.error
      })
      .then(function () { toggleBtn.disabled = false })
  })

  poll()
})()


// Hover a structure row -> draw it on the map. The wanted band becomes a
// pair of rings, so "is that in range" is answered spatially instead of
// by comparing two numbers. Exact points are drawn only where they are
// real: a noise-placed set has no grid position to plot.
;(function () {
  var svg = document.getElementById('lb-overlay')
  var info = document.querySelector('#lightbox .lb-info')
  if (!svg || !info) return
  var NS = 'http://www.w3.org/2000/svg'

  function clear () { while (svg.firstChild) svg.removeChild(svg.firstChild) }

  function ring (r, cls, dash) {
    var c = document.createElementNS(NS, 'circle')
    c.setAttribute('cx', 50); c.setAttribute('cy', 50)
    c.setAttribute('r', r); c.setAttribute('class', cls)
    c.setAttribute('vector-effect', 'non-scaling-stroke')
    if (dash) c.setAttribute('stroke-dasharray', dash)
    svg.appendChild(c)
    return c
  }

  info.addEventListener('mouseover', function (e) {
    var row = e.target.closest('.mrow[data-band]')
    if (!row) return
    // The coverage of the render actually on screen, not of the hi-res one
    // that may never have been produced. See lbMapCoverage in the block above.
    var coverage = window.lbMapCoverage ? window.lbMapCoverage() : 0
    if (!coverage) return
    var band = row.dataset.band.split(',').map(Number)
    clear()
    // Same element the dartboard uses, same geometry requirement.
    if (window.alignLbOverlay) window.alignLbOverlay()
    // One projection, shared with dartboard.js and structicons.js — see
    // assets/project.js for why it is not written out by hand here.
    ring(window.lbProjectRadius(band[0], coverage), 'band', '2 2')
    ring(window.lbProjectRadius(band[1], coverage), 'band')
    var pos = row.dataset.pos
    if (pos) {
      pos.split(';').forEach(function (pair) {
        var xz = pair.split(',').map(Number)
        var c = document.createElementNS(NS, 'circle')
        c.setAttribute('cx', window.lbProject(xz[0], coverage))
        c.setAttribute('cy', window.lbProject(xz[1], coverage))
        c.setAttribute('r', 0.5)
        c.setAttribute('class', 'pt')
        svg.appendChild(c)
      })
    }
  })
  info.addEventListener('mouseleave', clear)
})()
