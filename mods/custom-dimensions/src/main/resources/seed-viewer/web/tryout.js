// Try-out controls: fly around a candidate before choosing it.
//
// The world is built on the server tick, not on the request thread, so the
// first POST answers ready:false and this polls until it can teleport. The
// button says which of those two things is happening — "building" and
// "flying" are different states and a single spinner hides that.
;(function () {
  if (location.protocol === 'file:') return

  function post (path, body) {
    return fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    }).then(function (r) { return r.json() })
  }

  function enter (btn, dim, seed, attempt) {
    post('/tryout', { dim: dim, seed: seed })
      .then(function (r) {
        if (r.error) {
          btn.textContent = r.error
          btn.disabled = false
          return
        }
        if (r.ready) {
          btn.textContent = 'Flying — fly around, then pick'
          btn.disabled = false
          return
        }
        // World creation is one per tick and generates its first chunks on
        // arrival; a dozen tries at 1.5s is a comfortable ceiling on that.
        if (attempt > 12) {
          btn.textContent = 'Still building — press again'
          btn.disabled = false
          return
        }
        btn.textContent = 'Building the world…'
        setTimeout(function () { enter(btn, dim, seed, attempt + 1) }, 1500)
      })
      .catch(function () {
        btn.textContent = 'Could not reach the server'
        btn.disabled = false
      })
  }

  document.addEventListener('click', function (e) {
    var go = e.target.closest('.action-btn.tryout')
    if (go) {
      go.disabled = true
      go.textContent = 'Building the world…'
      enter(go, go.dataset.dim, go.dataset.seed, 0)
      return
    }
    var back = e.target.closest('.action-btn.tryout-back')
    if (back) {
      back.disabled = true
      back.textContent = 'Returning…'
      post('/tryout/back').then(function () {
        back.disabled = false
        back.textContent = 'Back to spawn'
      })
    }
  })
})()
