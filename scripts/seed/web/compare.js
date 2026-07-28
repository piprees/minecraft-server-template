/* compare.js — lock two candidates side by side and diff them.
 *
 * The tool's whole job is "which of these two worlds would I rather play in",
 * and until now answering it meant opening one candidate, scrolling ~2700px
 * of detail, closing it, opening the next, and holding four numbers in your
 * head across the gap. Every part of that is avoidable: both candidates'
 * component scores are already computed and now ride on the tile as
 * data-parts, so the subtraction can just be done and shown.
 *
 * Anatomy follows shadcn's Dialog: native <dialog> + showModal(), so focus
 * trapping, Escape and inert-background are the platform's job rather than
 * ours. The viewer already proves real background clicks are blocked while a
 * native dialog is open; a hand-rolled modal would have to re-earn that.
 *
 * Selection is deliberately capped at two. Three panels of Minecraft terrain
 * at a useful size do not fit a laptop, and the decision being made is
 * pairwise anyway.
 */
;(function () {
  if (location.protocol === 'file:') return

  var LABELS = {
    namesake: 'spawn biome',
    variety: 'biome variety',
    terrain: 'terrain shape',
    structures: 'structures',
  }
  var ORDER = ['namesake', 'variety', 'terrain', 'structures']

  var picked = [] // .cand elements, max 2, in the order they were chosen
  var dlg = document.getElementById('compare-dialog')
  var tray = document.getElementById('compare-tray')
  if (!dlg || !tray) return
  var trayCount = tray.querySelector('.compare-count')
  var openBtn = tray.querySelector('.compare-open')
  var clearBtn = tray.querySelector('.compare-clear')

  function parseParts(el) {
    try {
      return JSON.parse(el.dataset.parts || '{}')
    } catch (e) {
      return {}
    }
  }

  function weightsFor(el) {
    // The dimension's weights live on the criteria block of its own card; the
    // score mix differs per dimension, so a delta that ignores it is a lie.
    var card = el.closest('.dim-card')
    var out = {}
    if (!card) return out
    var txt = (card.querySelector('.criteria') || {}).textContent || ''
    var m = txt.match(
      /spawn (\d+)% · variety (\d+)% · terrain (\d+)% · structures (\d+)%/
    )
    if (!m) return out
    out.namesake = +m[1]
    out.variety = +m[2]
    out.terrain = +m[3]
    out.structures = +m[4]
    return out
  }

  function syncTray() {
    tray.hidden = picked.length === 0
    trayCount.textContent =
      picked.length === 1 ? '1 selected — pick one more' : '2 selected'
    openBtn.disabled = picked.length < 2
    document.querySelectorAll('.cand.comparing').forEach(function (el) {
      if (picked.indexOf(el) < 0) el.classList.remove('comparing')
    })
    picked.forEach(function (el) {
      el.classList.add('comparing')
    })
  }

  function toggle(cand) {
    var i = picked.indexOf(cand)
    if (i >= 0) picked.splice(i, 1)
    // Third pick replaces the OLDEST, so a run of "no, this one" comparisons
    // never needs an explicit deselect.
    else if (picked.length >= 2) picked = [picked[1], cand]
    else picked.push(cand)
    syncTray()
  }

  function panelHTML(el) {
    var render = el.dataset.render || ''
    var seed = el.dataset.seed || ''
    var dim = el.dataset.dim || ''
    var score = parseFloat(el.dataset.score || 0)
    var spawn = el.dataset.spawn || ''
    var chip = el.querySelector('.relief-chip')
    return (
      "<div class='cmp-panel'>" +
      "<div class='cmp-map'><img src='" +
      render +
      "' alt='Map render — " +
      dim +
      ' seed ' +
      seed +
      "' onerror=\"this.onerror=null;this.replaceWith(Object.assign(document.createElement('div'),{className:'no-render',textContent:'render queued'}))\">" +
      (chip ? chip.outerHTML : '') +
      '</div>' +
      "<div class='cmp-head'><span class='cmp-score'>" +
      score.toFixed(1) +
      "</span><span class='cmp-seed'>" +
      seed +
      '</span></div>' +
      "<div class='cmp-spawn'>spawn: <b>" +
      spawn +
      '</b></div>' +
      '</div>'
    )
  }

  function render() {
    if (picked.length < 2) return
    var a = picked[0],
      b = picked[1]
    var pa = parseParts(a),
      pb = parseParts(b)
    var w = weightsFor(a)
    var wsum =
      ORDER.reduce(function (t, k) {
        return t + (w[k] || 0)
      }, 0) || 1

    var rows = ORDER.filter(function (k) {
      return pa[k] != null || pb[k] != null
    }).map(function (k) {
      var ea = ((pa[k] || 0) * (w[k] || 0) * 100) / wsum
      var eb = ((pb[k] || 0) * (w[k] || 0) * 100) / wsum
      var d = ea - eb
      var winner = Math.abs(d) < 0.05 ? 'tie' : d > 0 ? 'a' : 'b'
      return (
        "<tr class='comp-" +
        k +
        "'>" +
        "<th scope='row'><i class='swatch'></i>" +
        (LABELS[k] || k) +
        '</th>' +
        "<td class='cmp-num" +
        (winner === 'a' ? ' cmp-win' : '') +
        "'>" +
        ea.toFixed(1) +
        '</td>' +
        "<td class='cmp-delta'>" +
        (winner === 'tie'
          ? '—'
          : (d > 0 ? '▲ ' : '▼ ') + Math.abs(d).toFixed(1)) +
        '</td>' +
        "<td class='cmp-num" +
        (winner === 'b' ? ' cmp-win' : '') +
        "'>" +
        eb.toFixed(1) +
        '</td>' +
        '</tr>'
      )
    })

    var sa = parseFloat(a.dataset.score || 0),
      sb = parseFloat(b.dataset.score || 0)
    dlg.querySelector('.cmp-body').innerHTML =
      "<div class='cmp-panels'>" + panelHTML(a) + panelHTML(b) + '</div>' +
      "<table class='cmp-table'><caption class='sr-only'>Score components " +
      'compared</caption><thead><tr><th scope=\'col\'>component</th>' +
      "<th scope='col'>left</th><th scope='col'>difference</th>" +
      "<th scope='col'>right</th></tr></thead><tbody>" +
      rows.join('') +
      "</tbody><tfoot><tr><th scope='row'>total</th>" +
      "<td class='cmp-num" + (sa >= sb ? ' cmp-win' : '') + "'>" + sa.toFixed(1) + '</td>' +
      "<td class='cmp-delta'>" +
      (Math.abs(sa - sb) < 0.05
        ? '—'
        : (sa > sb ? '▲ ' : '▼ ') + Math.abs(sa - sb).toFixed(1)) +
      '</td>' +
      "<td class='cmp-num" + (sb > sa ? ' cmp-win' : '') + "'>" + sb.toFixed(1) + '</td>' +
      '</tr></tfoot></table>' +
      "<div class='cmp-actions'>" +
      "<button type='button' class='btn btn-primary cmp-use' data-dim='" +
      a.dataset.dim + "' data-seed='" + a.dataset.seed + "'>Use left</button>" +
      "<button type='button' class='btn btn-primary cmp-use' data-dim='" +
      b.dataset.dim + "' data-seed='" + b.dataset.seed + "'>Use right</button>" +
      '</div>'
  }

  document.addEventListener('click', function (e) {
    var btn = e.target.closest('.cmp-pick')
    if (btn) {
      e.stopPropagation()
      e.preventDefault()
      toggle(btn.closest('.cand'))
      return
    }
    var use = e.target.closest('.cmp-use')
    if (use) {
      use.disabled = true
      use.textContent = 'saving…'
      fetch('/pick', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dim: use.dataset.dim, seed: use.dataset.seed }),
      }).then(function () {
        location.reload()
      })
    }
  })

  openBtn.addEventListener('click', function () {
    render()
    dlg.showModal()
  })
  clearBtn.addEventListener('click', function () {
    picked = []
    syncTray()
  })
  dlg.querySelector('.cmp-close').addEventListener('click', function () {
    dlg.close()
  })

  syncTray()
})()
