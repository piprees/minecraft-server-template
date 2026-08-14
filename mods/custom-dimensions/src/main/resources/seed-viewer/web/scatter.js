/* scatter.js — all ~7000 candidates as one dataset.
 *
 * The review is 81 separate small tasks only because the tool presents it
 * that way. It is actually one bank of measurements with a shape: dimensions
 * whose best candidate is mediocre everywhere, dimensions where one component
 * is dragging every seed down, single outliers far better than their
 * neighbours. None of that is visible one card at a time.
 *
 * The flat view (▤) was meant to be this and wasn't: its default sort is by
 * name, so turning it on just stripped the card chrome and left every
 * dimension's candidates clustered in the same order. You only got the
 * cross-dimension view by ALSO switching Sort to Score, and nothing said so.
 * This replaces it with something that is worth the mode switch.
 *
 * Two axes, each any of the four score components or the total; a dot per
 * candidate; colour by dimension family. Hovering names the candidate,
 * clicking opens it in the lightbox exactly as a tile would. It reads the
 * same data-parts the tiles carry, so it can never disagree with them.
 *
 * Rendered to <canvas>, not SVG: ~7000 nodes with hover behaviour is how you
 * make a page that stutters, and the review agent hit a browser hang doing
 * something adjacent (re-sorting ~800 cloned candidate nodes). One canvas and
 * a hit-test array stays flat regardless of bank size.
 */
;(function () {
  if (location.protocol === 'file:') return

  var host = document.getElementById('scatter-view')
  if (!host) return
  var canvas = host.querySelector('canvas')
  var xSel = host.querySelector('.sc-x')
  var ySel = host.querySelector('.sc-y')
  var tip = host.querySelector('.sc-tip')
  var countEl = host.querySelector('.sc-count')
  if (!canvas || !xSel || !ySel) return

  var AXES = [
    ['total', 'total score'],
    ['namesake', 'spawn biome'],
    ['variety', 'biome variety'],
    ['terrain', 'terrain shape'],
    ['structures', 'structures'],
  ]
  var FAMILY_VAR = {
    overworld: '--chart-2',
    nether: '--chart-4',
    end: '--chart-3',
    paradise_lost: '--chart-1',
  }
  var PAD = 42
  var pts = []
  var hot = -1

  function fill(sel, def) {
    sel.innerHTML = ''
    AXES.forEach(function (a) {
      var o = document.createElement('option')
      o.value = a[0]
      o.textContent = a[1]
      if (a[0] === def) o.selected = true
      sel.appendChild(o)
    })
  }
  fill(xSel, 'terrain')
  fill(ySel, 'structures')

  function familyOf(card) {
    var f = (card.dataset.family || '').split(' ')[0]
    return FAMILY_VAR[f] ? f : 'overworld'
  }

  function collect() {
    pts = []
    document.querySelectorAll('.dim-card').forEach(function (card) {
      // Honour the same filters the grid is showing. A scatter that ignores
      // the family buttons would quietly answer a different question.
      if (card.classList.contains('hidden')) return
      var fam = familyOf(card)
      card.querySelectorAll('.cand').forEach(function (el) {
        var parts
        try {
          parts = JSON.parse(el.dataset.parts || '{}')
        } catch (e) {
          return
        }
        pts.push({
          el: el,
          dim: el.dataset.dim || '',
          seed: el.dataset.seed || '',
          total: parseFloat(el.dataset.score || 0),
          parts: parts,
          fam: fam,
        })
      })
    })
    if (countEl) {
      countEl.textContent =
        pts.length + ' candidates across ' +
        document.querySelectorAll('.dim-card:not(.hidden)').length + ' dimensions'
    }
  }

  function valueOf(p, key) {
    if (key === 'total') return p.total
    var v = p.parts[key]
    return v == null ? null : v * 100
  }

  function css(v) {
    return getComputedStyle(document.documentElement).getPropertyValue(v).trim()
  }

  function draw() {
    var dpr = window.devicePixelRatio || 1
    var w = canvas.clientWidth
    var h = canvas.clientHeight
    if (!w || !h) return
    canvas.width = w * dpr
    canvas.height = h * dpr
    var ctx = canvas.getContext('2d')
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, w, h)

    var xk = xSel.value
    var yk = ySel.value
    var ink = css('--foreground') || '#eee'
    var muted = css('--muted-foreground') || '#999'

    ctx.strokeStyle = css('--border') || '#333'
    ctx.fillStyle = muted
    ctx.font = '11px ui-sans-serif, system-ui, sans-serif'
    ctx.lineWidth = 1
    for (var g = 0; g <= 4; g++) {
      var gx = PAD + ((w - PAD - 12) * g) / 4
      var gy = h - PAD - ((h - PAD - 12) * g) / 4
      ctx.beginPath()
      ctx.moveTo(gx, 12)
      ctx.lineTo(gx, h - PAD)
      ctx.moveTo(PAD, gy)
      ctx.lineTo(w - 12, gy)
      ctx.stroke()
      ctx.textAlign = 'center'
      ctx.fillText(String(g * 25), gx, h - PAD + 14)
      ctx.textAlign = 'right'
      ctx.fillText(String(g * 25), PAD - 6, gy + 4)
    }
    ctx.fillStyle = ink
    ctx.textAlign = 'center'
    ctx.fillText(xSel.options[xSel.selectedIndex].text, PAD + (w - PAD) / 2, h - 8)
    ctx.save()
    ctx.translate(12, PAD + (h - PAD) / 2)
    ctx.rotate(-Math.PI / 2)
    ctx.fillText(ySel.options[ySel.selectedIndex].text, 0, 0)
    ctx.restore()

    pts.forEach(function (p) {
      var xv = valueOf(p, xk)
      var yv = valueOf(p, yk)
      if (xv == null || yv == null) {
        p.x = p.y = null
        return
      }
      p.x = PAD + ((w - PAD - 12) * Math.max(0, Math.min(100, xv))) / 100
      p.y = h - PAD - ((h - PAD - 12) * Math.max(0, Math.min(100, yv))) / 100
      ctx.globalAlpha = 0.62
      ctx.fillStyle = css(FAMILY_VAR[p.fam]) || muted
      ctx.beginPath()
      ctx.arc(p.x, p.y, 3, 0, Math.PI * 2)
      ctx.fill()
    })
    ctx.globalAlpha = 1

    if (hot >= 0 && pts[hot] && pts[hot].x != null) {
      var p2 = pts[hot]
      ctx.strokeStyle = ink
      ctx.lineWidth = 2
      ctx.beginPath()
      ctx.arc(p2.x, p2.y, 6, 0, Math.PI * 2)
      ctx.stroke()
    }
  }

  function nearest(mx, my) {
    var best = -1
    var bd = 144
    pts.forEach(function (p, i) {
      if (p.x == null) return
      var d = (p.x - mx) * (p.x - mx) + (p.y - my) * (p.y - my)
      if (d < bd) {
        bd = d
        best = i
      }
    })
    return best
  }

  canvas.addEventListener('mousemove', function (e) {
    var r = canvas.getBoundingClientRect()
    var i = nearest(e.clientX - r.left, e.clientY - r.top)
    if (i === hot) return
    hot = i
    draw()
    if (i < 0) {
      tip.hidden = true
      return
    }
    var p = pts[i]
    tip.hidden = false
    tip.style.left = Math.min(p.x + 12, r.width - 190) + 'px'
    tip.style.top = Math.max(p.y - 34, 4) + 'px'
    tip.innerHTML =
      '<b>' + p.dim + '</b><br>' + p.total.toFixed(1) +
      " <span class='sc-seed'>" + p.seed + '</span>'
  })
  canvas.addEventListener('mouseleave', function () {
    hot = -1
    tip.hidden = true
    draw()
  })
  canvas.addEventListener('click', function () {
    // Same destination a tile click has: no second way to look at a candidate.
    if (hot >= 0 && pts[hot]) pts[hot].el.click()
  })

  xSel.addEventListener('change', draw)
  ySel.addEventListener('change', draw)
  window.addEventListener('resize', draw)

  window.refreshScatter = function () {
    if (host.hidden) return
    collect()
    draw()
  }

  var toggle = document.getElementById('f-scatter')
  if (toggle) {
    toggle.addEventListener('change', function () {
      host.hidden = !toggle.checked
      document.body.classList.toggle('scatter-on', toggle.checked)
      if (toggle.checked) window.refreshScatter()
    })
  }
})()
