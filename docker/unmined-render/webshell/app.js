/* map.DOMAIN shell — sidebar + single-dimension map loader.
 *
 * Each dimension is a full page load (/<slug>); the browser's View
 * Transition API handles cross-fade between pages. No client-side
 * routing — caching is king.
 *
 * Data contract (written by render-loop.sh):
 *   /manifest.json              — { dimensions: [ { slug, name, type,
 *                                   typeLabel, family, difficulty, theme,
 *                                   spawnBiome, rendered, spawn, version,
 *                                   renderedAt, thumb } ] }
 *   /maps/<slug>/…              — uNmINeD web output per dimension
 *   /maps/<slug>/thumb.webp     — sidebar card preview (absent = unrendered)
 *   /maps/<slug>/markers.json   — custom markers merged into the map
 */
(function () {
  'use strict';

  var FAMILY_BG = {
    overworld: '#7ba4ff',
    nether: '#1d0b09',
    end: '#0e0c16',
    paradise_lost: '#bfe3f5'
  };
  var DEFAULT_BG = '#0c1319';

  var mapEl = document.getElementById('map');
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

  // --- Helpers ---------------------------------------------------------------
  function toPath(slug) { return slug.replace(/_/g, '-'); }
  function fromPath(path) { return path.replace(/-/g, '_'); }

  function slugFromPath() {
    return fromPath(decodeURIComponent(location.pathname.replace(/^\/+|\/+$/g, '')));
  }

  // --- Load manifest and render ----------------------------------------------
  fetch('/manifest.json', { cache: 'no-cache' })
    .then(function (r) { return r.json(); })
    .then(function (m) {
      var manifest = (m && m.dimensions) || [];
      if (!manifest.length) { emptyEl.hidden = false; return; }
      buildSidebar(manifest);
      loadCurrentDimension(manifest);
    })
    .catch(function () { emptyEl.hidden = false; });

  function findDim(manifest, slug) {
    for (var i = 0; i < manifest.length; i++) {
      if (manifest[i].slug === slug) return manifest[i];
    }
    return null;
  }

  // One preview card per dimension: thumbnail (when rendered), name, an
  // info line built from whatever config-derived fields the manifest
  // carries (type/difficulty/theme/spawn biome — any of these may be
  // absent for a sparsely-configured dimension, so the line only joins
  // the ones actually present), and the render/pending status line.
  function buildSidebar(manifest) {
    var currentSlug = slugFromPath();
    var frag = document.createDocumentFragment();
    manifest.forEach(function (dim) {
      var li = document.createElement('li');
      var a = document.createElement('a');
      a.href = '/' + encodeURIComponent(toPath(dim.slug));
      a.dataset.slug = dim.slug;
      if (!dim.rendered) { a.classList.add('dim-pending'); }

      a.appendChild(buildThumb(dim));

      var body = document.createElement('span');
      body.className = 'dim-body';

      var name = document.createElement('span');
      name.className = 'dim-name';
      name.textContent = dim.name;
      body.appendChild(name);

      var metaParts = [dim.typeLabel, dim.difficulty, dim.theme, dim.spawnBiome]
        .filter(function (v) { return v; });
      if (metaParts.length) {
        var metaText = metaParts.join(' · ');
        var meta = document.createElement('span');
        meta.className = 'dim-meta';
        meta.textContent = metaText;
        body.appendChild(meta);
        // Name and info line are both CSS-truncated in the fixed-width
        // sidebar; the title tooltip is the only way a mouse user sees the
        // full text (screen readers already get it from the untruncated
        // textContent above).
        a.title = dim.name + ' — ' + metaText;
      } else {
        a.title = dim.name;
      }

      var whenText = dim.rendered
        ? (dim.renderedAt ? 'rendered ' + new Date(dim.renderedAt * 1000).toLocaleString() : '')
        : 'awaiting first explorer';
      if (whenText) {
        var when = document.createElement('span');
        when.className = 'dim-when';
        when.textContent = whenText;
        body.appendChild(when);
      }
      a.appendChild(body);

      if (dim.slug === currentSlug || (!findDim(manifest, currentSlug) && dim === manifest[0])) {
        a.setAttribute('aria-current', 'page');
      }
      li.appendChild(a);
      frag.appendChild(li);
    });
    listEl.innerHTML = '';
    listEl.appendChild(frag);
  }

  // The card thumbnail frame. No <img> is created at all when the manifest
  // has no thumbnail URL, so there is never a moment where a broken-image
  // icon can show; if the file 404s anyway (render succeeded but the
  // spawn-area thumbnail render did not) the error handler falls back to
  // the same empty-frame treatment.
  function buildThumb(dim) {
    var thumb = document.createElement('span');
    thumb.className = 'dim-thumb';
    if (!dim.thumb) {
      thumb.classList.add('dim-thumb-empty');
      thumb.setAttribute('aria-hidden', 'true');
      return thumb;
    }
    var img = document.createElement('img');
    img.src = dim.thumb + '?v=' + String(dim.version || 0);
    img.alt = dim.name + ' — aerial map preview';
    img.loading = 'lazy';
    img.decoding = 'async';
    img.addEventListener('error', function () {
      img.remove();
      thumb.classList.add('dim-thumb-empty');
      thumb.setAttribute('aria-hidden', 'true');
    });
    thumb.appendChild(img);
    return thumb;
  }

  function loadCurrentDimension(manifest) {
    var slug = slugFromPath();
    var dim = findDim(manifest, slug);
    if (!dim) dim = manifest.find(function (d) { return d.rendered; }) || manifest[0];
    if (!dim || !dim.rendered) {
      emptyEl.hidden = false;
      emptyEl.querySelector('h1').textContent = dim ? dim.name : 'No maps yet';
      emptyEl.querySelector('p').textContent = 'This dimension hasn’t been explored yet — venture in and check back after the next render pass.';
      return;
    }

    var bg = dim.background || FAMILY_BG[dim.family] || DEFAULT_BG;
    var v = String(dim.version || 0);
    var base = '/maps/' + encodeURIComponent(dim.slug) + '/';

    document.title = dim.name + ' — World Map';
    mapEl.style.backgroundColor = bg;
    statusEl.textContent = 'Loading ' + dim.name + '…';

    var propsScript = document.createElement('script');
    propsScript.src = base + 'unmined.map.properties.js?v=' + v;
    propsScript.onload = function () {
      var regionsScript = document.createElement('script');
      regionsScript.src = base + 'unmined.map.regions.js?v=' + v;
      regionsScript.onload = function () {
        fetch(base + 'markers.json?v=' + v)
          .then(function (r) { return r.ok ? r.json() : []; })
          .catch(function () { return []; })
          .then(function (markers) { initMap(dim, bg, base, v, markers); });
      };
      regionsScript.onerror = function () { statusEl.textContent = 'Could not load ' + dim.name; };
      document.head.appendChild(regionsScript);
    };
    propsScript.onerror = function () { statusEl.textContent = 'Could not load ' + dim.name; };
    document.head.appendChild(propsScript);
  }

  function initMap(dim, bg, base, v, markers) {
    var props = window.UnminedMapProperties;
    var regions = window.UnminedRegions;
    if (!props || !regions) {
      statusEl.textContent = 'Could not load ' + dim.name;
      return;
    }
    props.markers = (props.markers || []).concat(markers || []);
    props.background = bg;
    if (dim.spawn && dim.spawn.length === 3) {
      props.centerX = dim.spawn[0];
      props.centerZ = dim.spawn[2];
    }
    var unmined = new Unmined(mapEl, props, regions);
    patchTileUrls(unmined, base, v);
    statusEl.textContent = dim.name + ' loaded';
  }

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
