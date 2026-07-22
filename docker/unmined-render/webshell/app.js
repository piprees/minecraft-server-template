/* map.DOMAIN shell — sidebar routing + uNmINeD map loading.
 *
 * Data contract (written by render-loop.sh):
 *   /manifest.json              — { "dimensions": [ { slug, name, family,
 *                                   spawn: [x,y,z]|null, version, renderedAt } ] }
 *                                 Served no-cache; `version` changes on every
 *                                 re-render of that dimension.
 *   /maps/<slug>/…              — uNmINeD web output per dimension. Every
 *                                 request carries ?v=<version>, so tiles can
 *                                 be cached as immutable at the edge and a
 *                                 re-render mints fresh URLs (cache busting).
 *   /maps/<slug>/markers.json   — [ { x, z, text, image?, … } ] merged into
 *                                 the uNmINeD marker layer at load.
 *
 * URLs are clean paths: map.DOMAIN/<slug> (History API; nginx falls back to
 * /index.html for any non-file path). The #rx=&rz= hash from uNmINeD's
 * red-dot marker links is preserved across dimension switches.
 */
(function () {
  'use strict';

  // Skybox-ish background per family — the map fades to this colour while
  // switching, and empty tile space shows it behind rendered chunks.
  var FAMILY_BG = {
    overworld: '#7ba4ff',
    nether: '#1d0b09',
    end: '#0e0c16',
    paradise_lost: '#bfe3f5'
  };
  var DEFAULT_BG = '#0c1319';

  var manifest = [];
  var unmined = null;
  var currentSlug = null;
  var loadToken = 0;

  var mapEl = document.getElementById('map');
  var fadeEl = document.getElementById('fade');
  var emptyEl = document.getElementById('map-empty');
  var listEl = document.getElementById('dim-list');
  var statusEl = document.getElementById('map-status');
  var toggleEl = document.getElementById('sidebar-toggle');

  // --- Sidebar toggle -------------------------------------------------------
  function setSidebar(open, persist) {
    document.body.classList.toggle('sidebar-closed', !open);
    toggleEl.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (persist) {
      try { localStorage.setItem('mapSidebarOpen', open ? '1' : '0'); } catch (e) { /* private mode */ }
    }
  }
  toggleEl.addEventListener('click', function () {
    setSidebar(document.body.classList.contains('sidebar-closed'), true);
  });
  (function initSidebar() {
    var stored = null;
    try { stored = localStorage.getItem('mapSidebarOpen'); } catch (e) { /* private mode */ }
    var small = window.matchMedia('(max-width: 700px)').matches;
    setSidebar(stored !== null ? stored === '1' : !small, false);
  })();

  // --- Manifest + routing ---------------------------------------------------
  fetch('/manifest.json', { cache: 'no-cache' })
    .then(function (r) { return r.json(); })
    .then(function (m) {
      manifest = (m && m.dimensions) || [];
      buildSidebar();
      if (!manifest.length) { emptyEl.hidden = false; return; }
      window.addEventListener('popstate', route);
      route();
    })
    .catch(function () { emptyEl.hidden = false; });

  function findDim(slug) {
    for (var i = 0; i < manifest.length; i++) {
      if (manifest[i].slug === slug) return manifest[i];
    }
    return null;
  }

  // URLs use hyphens (/the-gauntlet); dimension slugs use underscores.
  // Slugs never contain hyphens (config names are [a-z][a-z0-9_]*), so the
  // mapping is bijective. Underscore URLs still resolve for old links.
  function toPath(slug) { return slug.replace(/_/g, '-'); }
  function fromPath(path) { return path.replace(/-/g, '_'); }

  function slugFromPath() {
    return fromPath(decodeURIComponent(location.pathname.replace(/^\/+|\/+$/g, '')));
  }

  function route() {
    var slug = slugFromPath();
    if (!findDim(slug)) slug = manifest[0].slug;
    if (slug !== currentSlug) loadDimension(slug);
  }

  function buildSidebar() {
    var frag = document.createDocumentFragment();
    manifest.forEach(function (dim) {
      var li = document.createElement('li');
      var a = document.createElement('a');
      a.href = '/' + encodeURIComponent(toPath(dim.slug));
      a.textContent = dim.name;
      a.dataset.slug = dim.slug;
      if (dim.renderedAt) {
        var when = document.createElement('span');
        when.className = 'dim-when';
        when.textContent = 'rendered ' + new Date(dim.renderedAt * 1000).toLocaleString();
        a.appendChild(when);
      }
      a.addEventListener('click', function (e) {
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
        e.preventDefault();
        if (dim.slug === currentSlug) return;
        history.pushState(null, '', '/' + encodeURIComponent(toPath(dim.slug)) + location.hash);
        loadDimension(dim.slug);
      });
      li.appendChild(a);
      frag.appendChild(li);
    });
    listEl.innerHTML = '';
    listEl.appendChild(frag);
  }

  function setActive(slug) {
    listEl.querySelectorAll('a').forEach(function (a) {
      if (a.dataset.slug === slug) a.setAttribute('aria-current', 'page');
      else a.removeAttribute('aria-current');
    });
  }

  // --- Script/data loading with per-load cleanup ----------------------------
  var injectedScripts = [];
  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      var s = document.createElement('script');
      s.src = src;
      s.onload = function () { resolve(); };
      s.onerror = function () { reject(new Error('failed: ' + src)); };
      document.head.appendChild(s);
      injectedScripts.push(s);
    });
  }
  function cleanupInjected() {
    injectedScripts.forEach(function (s) { s.remove(); });
    injectedScripts = [];
    delete window.UnminedMapProperties;
    delete window.UnminedRegions;
  }

  // --- Dimension switching with fade -----------------------------------------
  function loadDimension(slug) {
    var dim = findDim(slug);
    if (!dim) return;
    currentSlug = slug;
    var token = ++loadToken;
    var bg = dim.background || FAMILY_BG[dim.family] || DEFAULT_BG;

    setActive(slug);
    document.title = dim.name + ' — World Map';
    statusEl.textContent = 'Loading ' + dim.name + '…';

    // Fade the old tiles out to the new dimension's skybox colour…
    fadeEl.style.backgroundColor = bg;
    fadeEl.classList.add('active');

    var afterFade = new Promise(function (resolve) { setTimeout(resolve, 280); });
    var v = String(dim.version || 0);
    var base = '/maps/' + encodeURIComponent(slug) + '/';

    afterFade
      .then(function () {
        if (token !== loadToken) return Promise.reject(new Error('superseded'));
        // …swap the map while fully covered.
        if (unmined && unmined.olMap) unmined.olMap.setTarget(null);
        unmined = null;
        mapEl.innerHTML = '';
        cleanupInjected();
        return loadScript(base + 'unmined.map.properties.js?v=' + v);
      })
      .then(function () { return loadScript(base + 'unmined.map.regions.js?v=' + v); })
      .then(function () {
        return fetch(base + 'markers.json?v=' + v)
          .then(function (r) { return r.ok ? r.json() : []; })
          .catch(function () { return []; });
      })
      .then(function (markers) {
        if (token !== loadToken) return;
        var props = window.UnminedMapProperties;
        var regions = window.UnminedRegions;
        if (!props || !regions) throw new Error('map metadata missing');
        props.markers = (props.markers || []).concat(markers || []);
        props.background = bg;
        if (dim.spawn && dim.spawn.length === 3) {
          props.centerX = dim.spawn[0];
          props.centerZ = dim.spawn[2];
        }
        unmined = new Unmined(mapEl, props, regions);
        patchTileUrls(unmined, base, v);
        mapEl.style.backgroundColor = bg;
        fadeEl.classList.remove('active');
        statusEl.textContent = dim.name + ' loaded';
      })
      .catch(function (err) {
        if (String(err && err.message) === 'superseded') return;
        fadeEl.classList.remove('active');
        statusEl.textContent = 'Could not load ' + dim.name;
      });
  }

  // uNmINeD builds page-relative tile URLs; rebase them onto /maps/<slug>/
  // and stamp ?v=<version> so the edge cache treats every render as a new
  // immutable object.
  function patchTileUrls(instance, base, v) {
    var layer = instance.olMap.getLayers().item(0);
    var source = layer.getSource();
    var orig = source.getTileUrlFunction();
    source.setTileUrlFunction(function (coord, ratio, proj) {
      var url = orig(coord, ratio, proj);
      return url ? base + url + '?v=' + v : undefined;
    });
    source.refresh();
  }
})();
