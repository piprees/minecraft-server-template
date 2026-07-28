/* app.js — the seed viewer's client.
 *
 * Moved verbatim out of viewer_template.html's three inline <script>
 * blocks, in their original order. Loaded with `defer` from
 * assets/app.js, so it now runs after parsing rather than mid-document;
 * every block was already an IIFE reading elements declared above it, so
 * that is strictly safer than it was.
 *
 * `openConfigDialog` is still assigned to window: the New dimension
 * button carries an inline onclick.
 */

;(function () {
  var live = location.protocol !== 'file:'

  // --- Candidate detail lightbox ---
  var lb = document.getElementById('lightbox')
  var lbImg = lb.querySelector('.lb-image img')
  var lbInfo = lb.querySelector('.lb-info')
  var lbStructs = lb.querySelector('.lb-structs')
  var lbStructsBtn = lb.querySelector('.lb-structs-toggle')
  var lbStructsOn = true
  var lbReturnFocus = null
  // Structure overlays are separate transparent PNGs written next to the
  // renders ({seed}_structures.png / {seed}_hires_structures.png). Probe
  // for one matching the displayed render; hide the toggle when absent.
  function lbSetStructs(src) {
    lbStructs.classList.remove('on')
    lbStructs.removeAttribute('src')
    lbStructsBtn.style.display = 'none'
    if (!src) return
    var base = src.split('?')[0]
    if (!/\.png$/.test(base) || /_structures\.png$/.test(base)) return
    var url = base.replace(/\.png$/, '_structures.png')
    var probe = new Image()
    probe.onload = function () {
      lbStructs.src = probe.src
      lbStructsBtn.style.display = ''
      if (lbStructsOn) lbStructs.classList.add('on')
    }
    probe.src = url + '?t=' + Date.now()
  }
  lbStructsBtn.addEventListener('click', function () {
    lbStructsOn = !lbStructsOn
    lbStructsBtn.setAttribute('aria-pressed', String(lbStructsOn))
    lbStructs.classList.toggle('on', lbStructsOn && !!lbStructs.getAttribute('src'))
  })
  function lbShow() {
    if (!lb.classList.contains('open')) {
      lbReturnFocus = document.activeElement
      lb.classList.add('open')
    }
    lb.querySelector('.lb-close').focus()
  }
  function lbHide() {
    lb.classList.remove('open')
    if (lbReturnFocus && document.contains(lbReturnFocus)) lbReturnFocus.focus()
    lbReturnFocus = null
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
      lbSetStructs(img.src)
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
  function openCandInLightbox(cand) {
    if (!cand) return
    lbCurrentCand = cand
    var img = cand.querySelector('img')
    if (!img) return
    lbImg.src = img.src
    lbSetStructs(img.src)
    var hires = img.dataset.hires
    if (hires) {
      var p = new Image()
      p.onload = function () {
        lbImg.src = hires
        lbSetStructs(hires)
      }
      p.src = hires
    }
    var detail = cand.querySelector('.cand-detail')
    lbInfo.innerHTML = detail ? detail.innerHTML : ''
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
      if (expanded) setExpanded(expanded, false)
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

  // --- Filter + sort state from URL hash ---
  function readHash() {
    var p = new URLSearchParams(location.search.slice(1))
    return {
      family: p.get('family') || 'All',
      type: p.get('type') || '',
      mood: p.get('mood') || '',
      search: p.get('q') || '',
      flagged: p.get('flagged') === '1',
      shortlisted: p.get('shortlisted') === '1',
      ungrouped: p.get('ungrouped') === '1',
      showHidden: p.get('hidden') === '1',
      sort: p.get('sort') || 'name',
    }
  }
  function writeHash(s) {
    var p = new URLSearchParams()
    if (s.family !== 'All') p.set('family', s.family)
    if (s.type) p.set('type', s.type)
    if (s.mood) p.set('mood', s.mood)
    if (s.search) p.set('q', s.search)
    if (s.flagged) p.set('flagged', '1')
    if (s.shortlisted) p.set('shortlisted', '1')
    if (s.ungrouped) p.set('ungrouped', '1')
    if (s.showHidden) p.set('hidden', '1')
    if (s.sort !== 'name') p.set('sort', s.sort)
    var qs = p.toString()
    history.replaceState(null, '', qs ? '?' + qs : location.pathname)
  }

  var state = readHash()
  var typeEl = document.getElementById('f-type')
  var moodEl = document.getElementById('f-mood')
  var sortEl = document.getElementById('f-sort')
  var searchEl = document.getElementById('f-search')
  var flaggedEl = document.getElementById('f-flagged')
  var shortlistedEl = document.getElementById('f-shortlisted')
  var ungroupedEl = document.getElementById('f-ungrouped')
  var hiddenEl = document.getElementById('f-hidden')
  var grid = document.getElementById('grid')
  var ugGrid = document.getElementById('ungrouped-grid')

  function candSort(a, b) {
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
        if (state.flagged && parseFloat(c.dataset.score || 100) >= 70) return
        var clone = c.cloneNode(true)
        clone.style.display = ''
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

  // --- Review progress: dims with a pinned winner, shortlisted seed, or hidden ---
  function updateProgress() {
    var cards = document.querySelectorAll('.dim-card')
    var total = cards.length,
      done = 0
    cards.forEach(function (c) {
      if (c.dataset.pinned === '1' || c.dataset.shortlisted === '1' || c.dataset.hidden === '1') done++
    })
    var el = document.getElementById('review-progress')
    if (!el) return
    // A bar, not a sentence. This is the one number that answers "how much of
    // this job is left" across a session measured in hours, and it was
    // previously a long clause nobody would re-read on every filter change.
    var pct = total ? Math.round((done / total) * 100) : 0
    el.innerHTML =
      '<span class="review-meter" role="img" aria-label="' +
      done + ' of ' + total + ' dimensions reviewed">' +
      '<span class="review-meter-fill" style="width:' + pct + '%"></span></span>' +
      '<b>' + done + '</b> of <b>' + total + '</b> dimensions reviewed' +
      '<span class="review-note"> — pinned, shortlisted or hidden counts as reviewed</span>'
  }

  // The filtered set going empty used to render as blank space under a stats
  // line that still claimed "81 dimensions", so a typo'd search was
  // indistinguishable from a broken page.
  function updateEmptyState(visibleCount) {
    var el = document.getElementById('empty-state')
    if (!el) return
    el.hidden = visibleCount > 0
  }

  function applyState() {
    document.querySelectorAll('.family-btn').forEach(function (b) {
      var active = b.dataset.family === state.family
      b.classList.toggle('active', active)
      b.setAttribute('aria-pressed', active ? 'true' : 'false')
    })
    typeEl.value = state.type
    moodEl.value = state.mood
    sortEl.value = state.sort
    searchEl.value = state.search
    flaggedEl.checked = state.flagged
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
        var shl = !state.shortlisted || c.querySelector('.cand[data-shortlisted]') !== null
        var hid = state.showHidden || c.dataset.hidden !== '1'
        c.classList.toggle('hidden', !(fam && typ && moo && txt && flg && shl && hid))
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
            return a.dataset.name.localeCompare(b.dataset.name)
        }
      })
      visible.forEach(function (c) {
        grid.appendChild(c)
      })
      sortCandsInGroups()
      updateEmptyState(visible.length)
    }
    if (state.ungrouped) updateEmptyState(ugGrid.children.length)
    updateProgress()
    // The scatter honours the same filters; a scatter showing everything
    // while the grid shows one family answers a different question.
    if (window.refreshScatter) window.refreshScatter()
    writeHash(state)
  }

  document.querySelectorAll('.family-btn').forEach(function (b) {
    b.addEventListener('click', function () {
      state.family = b.dataset.family
      applyState()
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
      state.shortlisted = false
      applyState()
      searchEl.focus()
    })
  }
  typeEl.addEventListener('change', function () {
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
  shortlistedEl.addEventListener('change', function () {
    state.shortlisted = shortlistedEl.checked
    applyState()
  })
  ungroupedEl.addEventListener('change', function () {
    state.ungrouped = ungroupedEl.checked
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
  // All dimensions scale relative to the largest border diameter.
  // A 1024b world is 1/16th of a 16384b overworld.
  // No red ring on cards — just relative sizing.
  // Lightbox hires (32K render) gets a red ring at 50% showing the border.
  var RENDER_SIZE = 1024,
    BASE_SCALE = 8
  var maxDiameter = 0
  document.querySelectorAll('.dim-card').forEach(function (card) {
    var r = parseFloat(card.dataset.radius || 0)
    if (r * 2 > maxDiameter) maxDiameter = r * 2
  })
  document.querySelectorAll('.dim-card').forEach(function (card) {
    var radius = parseFloat(card.dataset.radius || 0)
    var dimScale = parseFloat(card.dataset.dimScale || 1)
    if (!radius) return
    var diameter = radius * 2
    var imgScale = Math.max(0.05, diameter / maxDiameter)
    var normalCoverage = RENDER_SIZE * Math.max(1, Math.floor(BASE_SCALE / dimScale))
    var hiresCoverage = Math.round((2048 * 16) / dimScale)
    card.dataset.borderDiameter = diameter
    card.querySelectorAll('img').forEach(function (img) {
      var wrap = img.parentElement
      if (wrap.classList.contains('img-wrap')) return
      var div = document.createElement('div')
      div.className = 'img-wrap'
      div.dataset.borderScale = imgScale.toFixed(4)
      img.parentNode.insertBefore(div, img)
      div.appendChild(img)
      var lbl = document.createElement('div')
      lbl.className = 'border-label'
      lbl.textContent = normalCoverage + 'b'
      lbl.dataset.normalCoverage = normalCoverage
      lbl.dataset.hiresCoverage = hiresCoverage
      div.appendChild(lbl)
    })
  })

  applyState()

  // --- Image auto-refresh: retry missing images + upgrade to hires ---
  ;(function autoRefreshImages() {
    var pending = new Set()
    document.querySelectorAll('img[data-hires]').forEach(function (img) {
      pending.add(img)
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
        img.src = hi.src
        var wrap = img.parentElement
        if (wrap) {
          var badge = wrap.querySelector('.hires-badge')
          if (badge) badge.classList.add('visible')
          var lbl = wrap.querySelector('.border-label')
          if (lbl && lbl.dataset.hiresCoverage) lbl.textContent = lbl.dataset.hiresCoverage + 'b'
        }
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
  function setExpanded(card, on) {
    card.classList.toggle('expanded', on)
    // aria-expanded belongs on the control, not on the container it reveals.
    var trigger = card.querySelector('.compact-trigger')
    if (trigger) trigger.setAttribute('aria-expanded', on ? 'true' : 'false')
  }
  function expandCard(card) {
    if (card.classList.contains('expanded')) return
    document.querySelectorAll('.dim-card.expanded').forEach(function (c) {
      setExpanded(c, false)
    })
    setExpanded(card, true)
    sortCandsInGroups()
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
          updateProgress()
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
      mode === 'fork' ? 'Fork "' + dim + '" as new dimension' : mode === 'edit' ? 'Configure "' + dim + '"' : 'New dimension'
    $id('cd-name').value = mode === 'fork' ? dim + '_fork' : mode === 'edit' ? dim : ''
    $id('cd-name').readOnly = mode === 'edit'
    $id('cd-create').disabled = false
    $id('cd-create').textContent = mode === 'edit' ? 'Save' : 'Create'
    var configPromise =
      mode === 'create'
        ? Promise.resolve({ config: {} })
        : fetch('/dim-config?dim=' + encodeURIComponent(dim)).then(function (r) {
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
  if (!toggleBtn) return

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
        if (window.applyFilters) window.applyFilters()
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
        toggleBtn.textContent = running ? '■' : '▶'
        toggleBtn.classList.toggle('running', running)
        toggleBtn.title = toggleBtn.ariaLabel =
          running ? 'Stop rolling' : 'Start rolling'
        dimEl.disabled = running
        if (running && st.target) {
          // Rolling finishes in seconds; rendering then runs for minutes.
          // A bar pinned at 100% for that whole tail would read as done,
          // so it goes indeterminate once there is nothing left to count.
          var doneRolling = st.rolled >= st.target
          fillEl.classList.toggle('indeterminate', doneRolling)
          fillEl.style.width = doneRolling
            ? '' : Math.min(100, st.rolled / st.target * 100) + '%'
          textEl.textContent = doneRolling
            ? st.stage.replace('_', ' ')
            : st.rolled + '/' + st.target
        }
        // Everything else that used to crowd the bar lives on the status
        // line under it, where it can wrap without reflowing the nav.
        var bits = []
        if (st.backfill) bits.push('enriching bank ' + st.backfill)
        if (st.enriched) bits.push(st.enriched + ' enriched')
        if (st.surveyed) bits.push(st.surveyed + ' surveyed')
        if ((st.rendering_low || []).length) bits.push('rendering low')
        if ((st.rendering_high || []).length) bits.push('rendering high')
        if (st.error) bits.push('error: ' + st.error)
        var detail = bits.join(' · ')
        // Second line under the counter, inside the nav group, so the
        // bar keeps its width while the detail changes length.
        var sub = document.getElementById('rp-sub')
        if (sub) sub.textContent = running ? detail : ''
        if (statusEl) statusEl.textContent = running ? '' : detail
        if (st.generation !== lastGeneration) {
          lastGeneration = st.generation
          if (lastGeneration > 0) refreshGrid()
        }
      })
      .catch(function () {})
      .then(function () { setTimeout(poll, 2000) })
  }

  toggleBtn.addEventListener('click', function () {
    if (running) { post('/pipeline/stop'); return }
    toggleBtn.disabled = true
    post('/pipeline/start', {
      count: parseInt(countEl.value, 10) || 100,
      dim: dimEl.value || null
    })
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
    var list = row.closest('[data-coverage]')
    var coverage = list && parseFloat(list.dataset.coverage)
    if (!coverage) return
    var band = row.dataset.band.split(',').map(Number)
    clear()
    // viewBox is 0-100 across the full render; a distance d in blocks is
    // d/coverage of the full width, so its radius is that x 100.
    ring(band[0] / coverage * 100, 'band', '2 2')
    ring(band[1] / coverage * 100, 'band')
    var pos = row.dataset.pos
    if (pos) {
      pos.split(';').forEach(function (pair) {
        var xz = pair.split(',').map(Number)
        var c = document.createElementNS(NS, 'circle')
        c.setAttribute('cx', 50 + xz[0] / coverage * 100)
        c.setAttribute('cy', 50 + xz[1] / coverage * 100)
        c.setAttribute('r', 0.5)
        c.setAttribute('class', 'pt')
        svg.appendChild(c)
      })
    }
  })
  info.addEventListener('mouseleave', clear)
})()
