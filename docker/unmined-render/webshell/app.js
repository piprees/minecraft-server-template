/* map.DOMAIN shell — sidebar + single-dimension map loader.
 *
 * Each dimension is a full page load (/<slug>); the browser's View
 * Transition API handles cross-fade between pages. No client-side
 * routing — caching is king.
 *
 * Data contract (written by render-loop.sh):
 *   /manifest.json              — { dimensions: [ { slug, name, family,
 *                                   rendered, spawn, version, renderedAt } ] }
 *   /maps/<slug>/…              — uNmINeD web output per dimension
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

  function buildSidebar(manifest) {
    var currentSlug = slugFromPath();
    var frag = document.createDocumentFragment();
    manifest.forEach(function (dim) {
      var li = document.createElement('li');
      var a = document.createElement('a');
      a.href = '/' + encodeURIComponent(toPath(dim.slug));
      a.textContent = dim.name;
      a.dataset.slug = dim.slug;
      if (!dim.rendered) {
        a.classList.add('dim-pending');
        var hint = document.createElement('span');
        hint.className = 'dim-when';
        hint.textContent = 'awaiting first explorer';
        a.appendChild(hint);
      } else if (dim.renderedAt) {
        var when = document.createElement('span');
        when.className = 'dim-when';
        when.textContent = 'rendered ' + new Date(dim.renderedAt * 1000).toLocaleString();
        a.appendChild(when);
      }
      if (dim.slug === currentSlug || (!findDim(manifest, currentSlug) && dim === manifest[0])) {
        a.setAttribute('aria-current', 'page');
      }
      li.appendChild(a);
      frag.appendChild(li);
    });
    listEl.innerHTML = '';
    listEl.appendChild(frag);
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
