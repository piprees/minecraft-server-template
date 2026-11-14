/* map.DOMAIN shell: sidebar of every dimension, one tiled map with layers.
 *
 * Each dimension is a full page load (/<slug>, underscores as dashes).
 *
 * Data contract (written by /customdim map export):
 *   /manifest.json               { dimensions: [ { slug, name, description, spawn, background,
 *                                  renderedAt, exploredAt, version, thumb, thumbVersion,
 *                                  layers, explored } ] }, every dimension; layers is absent
 *                                  until its generated tiles exist.
 *   /maps/<slug>/layers.json     { radius, tileSize, maxZoom, blocksPerPixel[z], side[z],
 *                                  layers: [ { id, kind, name, y, bottom, top, path } ],
 *                                  legend: { biomes: [ { id, name, colour } ], cuts: [...] },
 *                                  markers }. Pixel i of zoom z is the block
 *                                  -radius + i * blocksPerPixel[z]; north is up.
 *   /maps/<slug>/<path>/<z>/<x>/<y>.png   a layer's tile; a missing tile is transparent.
 *   /maps/<slug>/explored.json   { path, index: { z: ["x/y", ...] } }: the explored tiles.
 *   /maps/<slug>/markers.json    { structures: [ { type, name, x, z, box } ] }
 */
(function () {
  'use strict';

  var DEFAULT_BG = '#0c1319';
  var OVERZOOM = 3;

  var mapEl = document.getElementById('map');
  var baseEl = document.getElementById('tiles-base');
  var exploredEl = document.getElementById('tiles-explored');
  var markersEl = document.getElementById('markers');
  var emptyEl = document.getElementById('map-empty');
  var listEl = document.getElementById('dim-list');
  var statusEl = document.getElementById('map-status');
  var toggleEl = document.getElementById('sidebar-toggle');
  var layersEl = document.getElementById('layers');
  var layersToggle = document.getElementById('layers-toggle');
  var baseLayersEl = document.getElementById('base-layers');
  var legendEl = document.getElementById('legend');
  var coordsEl = document.getElementById('coords');
  var labelEl = document.getElementById('marker-label');
  var showExplored = document.getElementById('show-explored');
  var showMarkers = document.getElementById('show-markers');

  function store(key, value) {
    try {
      if (value === undefined) return localStorage.getItem(key);
      localStorage.setItem(key, value);
    } catch (e) { /* private mode */ }
    return null;
  }

  // --- Sidebar toggle -------------------------------------------------------
  function setSidebar(open, persist) {
    document.body.classList.toggle('sidebar-closed', !open);
    toggleEl.setAttribute('aria-expanded', open ? 'true' : 'false');
    if (persist) store('mapSidebarOpen', open ? '1' : '0');
    if (view.ready) view.render();
  }
  toggleEl.addEventListener('click', function () {
    setSidebar(document.body.classList.contains('sidebar-closed'), true);
  });

  layersToggle.addEventListener('click', function () {
    var open = layersEl.hidden;
    layersEl.hidden = !open;
    layersToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
  });

  function toPath(slug) { return slug.replace(/_/g, '-'); }
  function fromPath(path) { return path.replace(/-/g, '_'); }
  function slugFromPath() {
    return fromPath(decodeURIComponent(location.pathname.replace(/^\/+|\/+$/g, '')));
  }
  function getJson(url) {
    return fetch(url, { cache: 'no-cache' }).then(function (r) {
      if (!r.ok) throw new Error(url + ' ' + r.status);
      return r.json();
    });
  }

  // --- The tiled view -------------------------------------------------------
  // State: centre in blocks (x east, z south) and a fractional zoom; zoom z
  // shows blocksPerPixel[0] / 2^z blocks per screen pixel.
  var view = {
    ready: false, info: null, base: null, cx: 0, cz: 0, zoom: 0,
    layer: null, exploredIndex: null, exploredPath: null, exploredVersion: 0,
    markers: [], cache: {},

    bpp: function () { return this.info.blocksPerPixel[0] / Math.pow(2, this.zoom); },
    maxZoom: function () { return this.info.maxZoom + OVERZOOM; },

    fit: function () {
      var w = mapEl.clientWidth || 1;
      var h = mapEl.clientHeight || 1;
      var span = 2 * this.info.radius;
      var bpp0 = this.info.blocksPerPixel[0];
      this.zoom = Math.max(0, Math.log2(bpp0 * Math.min(w, h) / span));
      this.cx = 0;
      this.cz = 0;
    },

    setZoom: function (z, px, py) {
      z = Math.max(0, Math.min(this.maxZoom(), z));
      var w = mapEl.clientWidth;
      var h = mapEl.clientHeight;
      if (px === undefined) { px = w / 2; py = h / 2; }
      var before = this.toBlock(px, py);
      this.zoom = z;
      var after = this.toBlock(px, py);
      this.cx += before[0] - after[0];
      this.cz += before[1] - after[1];
      this.clamp();
      this.render();
    },

    pan: function (dx, dy) {
      var b = this.bpp();
      this.cx -= dx * b;
      this.cz -= dy * b;
      this.clamp();
      this.render();
    },

    clamp: function () {
      var r = this.info.radius;
      this.cx = Math.max(-r, Math.min(r, this.cx));
      this.cz = Math.max(-r, Math.min(r, this.cz));
    },

    toBlock: function (px, py) {
      var b = this.bpp();
      return [this.cx + (px - mapEl.clientWidth / 2) * b, this.cz + (py - mapEl.clientHeight / 2) * b];
    },

    toScreen: function (x, z) {
      var b = this.bpp();
      return [(x - this.cx) / b + mapEl.clientWidth / 2, (z - this.cz) / b + mapEl.clientHeight / 2];
    },

    render: function () {
      if (!this.ready) return;
      this.drawTiles(baseEl, this.layer.path, null, this.info.version);
      if (showExplored.checked && this.exploredIndex) {
        exploredEl.hidden = false;
        this.drawTiles(exploredEl, this.exploredPath, this.exploredIndex, this.exploredVersion);
      } else {
        exploredEl.hidden = true;
      }
      this.drawMarkers();
    },

    drawTiles: function (el, path, index, version) {
      var info = this.info;
      var level = Math.max(0, Math.min(info.maxZoom, Math.round(this.zoom)));
      var tileBlocks = info.tileSize * info.blocksPerPixel[level];
      var size = tileBlocks / this.bpp();
      var w = mapEl.clientWidth;
      var h = mapEl.clientHeight;
      var tl = this.toBlock(0, 0);
      var br = this.toBlock(w, h);
      var n = Math.ceil(info.side[level] / info.tileSize);
      var x0 = Math.max(0, Math.floor((tl[0] + info.radius) / tileBlocks));
      var y0 = Math.max(0, Math.floor((tl[1] + info.radius) / tileBlocks));
      var x1 = Math.min(n - 1, Math.floor((br[0] + info.radius) / tileBlocks));
      var y1 = Math.min(n - 1, Math.floor((br[1] + info.radius) / tileBlocks));
      var listed = index ? index[String(level)] : null;
      var keep = {};
      for (var ty = y0; ty <= y1; ty++) {
        for (var tx = x0; tx <= x1; tx++) {
          if (listed && listed.indexOf(tx + '/' + ty) < 0) continue;
          var key = path + '/' + level + '/' + tx + '/' + ty;
          keep[key] = true;
          var img = this.cache[key];
          if (!img) {
            img = document.createElement('img');
            img.alt = '';
            img.draggable = false;
            img.decoding = 'async';
            img.dataset.key = key;
            img.addEventListener('error', hide);
            img.src = this.base + path + '/' + level + '/' + tx + '/' + ty + '.png?v=' + version;
            this.cache[key] = img;
          }
          if (img.parentNode !== el) el.appendChild(img);
          var p = this.toScreen(-info.radius + tx * tileBlocks, -info.radius + ty * tileBlocks);
          img.style.transform = 'translate(' + p[0] + 'px,' + p[1] + 'px)';
          img.style.width = size + 'px';
          img.style.height = size + 'px';
        }
      }
      var kids = Array.prototype.slice.call(el.children);
      for (var i = 0; i < kids.length; i++) {
        var k = kids[i].dataset.key;
        if (keep[k]) continue;
        el.removeChild(kids[i]);
        delete this.cache[k];
      }
      el.classList.toggle('pixelated', this.zoom > info.maxZoom);
    },

    drawMarkers: function () {
      markersEl.hidden = !showMarkers.checked;
      if (!showMarkers.checked) return;
      var w = mapEl.clientWidth;
      var h = mapEl.clientHeight;
      for (var i = 0; i < this.markers.length; i++) {
        var m = this.markers[i];
        var p = this.toScreen(m.x, m.z);
        var on = p[0] > -20 && p[1] > -20 && p[0] < w + 20 && p[1] < h + 20;
        m.el.hidden = !on;
        if (on) m.el.style.transform = 'translate(' + p[0] + 'px,' + p[1] + 'px)';
      }
    }
  };

  function hide() { this.style.visibility = 'hidden'; }

  function showEmpty(title, text) {
    emptyEl.hidden = false;
    document.getElementById('controls').hidden = true;
    layersToggle.hidden = true;
    if (title) emptyEl.querySelector('h1').textContent = title;
    if (text) emptyEl.querySelector('p').textContent = text;
  }

  // --- Input ----------------------------------------------------------------
  var drag = null;
  var pointers = {};
  mapEl.addEventListener('pointerdown', function (e) {
    pointers[e.pointerId] = [e.clientX, e.clientY];
    mapEl.setPointerCapture(e.pointerId);
    drag = { x: e.clientX, y: e.clientY, pinch: pinchState() };
  });
  mapEl.addEventListener('pointermove', function (e) {
    var rect = mapEl.getBoundingClientRect();
    if (view.ready) {
      var b = view.toBlock(e.clientX - rect.left, e.clientY - rect.top);
      coordsEl.textContent = 'x ' + Math.floor(b[0]) + '  z ' + Math.floor(b[1]);
    }
    if (!pointers[e.pointerId] || !drag || !view.ready) return;
    pointers[e.pointerId] = [e.clientX, e.clientY];
    var pinch = pinchState();
    if (pinch && drag.pinch) {
      view.setZoom(view.zoom + Math.log2(pinch.d / drag.pinch.d), pinch.x - rect.left, pinch.y - rect.top);
      view.pan(pinch.x - drag.pinch.x, pinch.y - drag.pinch.y);
      drag.pinch = pinch;
      return;
    }
    view.pan(e.clientX - drag.x, e.clientY - drag.y);
    drag.x = e.clientX;
    drag.y = e.clientY;
  });
  function release(e) {
    delete pointers[e.pointerId];
    var left = Object.keys(pointers);
    drag = left.length ? { x: pointers[left[0]][0], y: pointers[left[0]][1], pinch: pinchState() } : null;
  }
  mapEl.addEventListener('pointerup', release);
  mapEl.addEventListener('pointercancel', release);
  function pinchState() {
    var ids = Object.keys(pointers);
    if (ids.length < 2) return null;
    var a = pointers[ids[0]];
    var b = pointers[ids[1]];
    return { d: Math.max(1, Math.hypot(a[0] - b[0], a[1] - b[1])), x: (a[0] + b[0]) / 2, y: (a[1] + b[1]) / 2 };
  }
  mapEl.addEventListener('wheel', function (e) {
    if (!view.ready) return;
    e.preventDefault();
    var rect = mapEl.getBoundingClientRect();
    view.setZoom(view.zoom - e.deltaY / 500, e.clientX - rect.left, e.clientY - rect.top);
  }, { passive: false });
  mapEl.addEventListener('dblclick', function (e) {
    var rect = mapEl.getBoundingClientRect();
    view.setZoom(Math.round(view.zoom) + 1, e.clientX - rect.left, e.clientY - rect.top);
  });
  mapEl.addEventListener('keydown', function (e) {
    var step = 80;
    var moves = { ArrowLeft: [step, 0], ArrowRight: [-step, 0], ArrowUp: [0, step], ArrowDown: [0, -step] };
    if (moves[e.key]) { view.pan(moves[e.key][0], moves[e.key][1]); e.preventDefault(); }
    if (e.key === '+' || e.key === '=') view.setZoom(view.zoom + 1);
    if (e.key === '-') view.setZoom(view.zoom - 1);
  });
  document.getElementById('zoom-in').addEventListener('click', function () { view.setZoom(Math.round(view.zoom) + 1); });
  document.getElementById('zoom-out').addEventListener('click', function () { view.setZoom(Math.round(view.zoom) - 1); });
  window.addEventListener('resize', function () { view.render(); });
  showExplored.addEventListener('change', function () { store('mapExplored', showExplored.checked ? '1' : '0'); view.render(); });
  showMarkers.addEventListener('change', function () { store('mapMarkers', showMarkers.checked ? '1' : '0'); view.render(); });

  // --- Manifest, sidebar and the chosen dimension ----------------------------
  (function init() {
    var stored = store('mapSidebarOpen');
    var small = window.matchMedia('(max-width: 700px)').matches;
    setSidebar(stored !== null ? stored === '1' : !small, false);
    if (store('mapExplored') === '0') showExplored.checked = false;
    if (store('mapMarkers') === '1') showMarkers.checked = true;
  })();

  getJson('/manifest.json')
    .then(function (m) {
      var dims = (m && m.dimensions) || [];
      if (!dims.length) { showEmpty(); return; }
      buildSidebar(dims);
      loadDimension(dims);
    })
    .catch(function () { showEmpty(); });

  function buildSidebar(dims) {
    var current = slugFromPath() || dims[0].slug;
    var frag = document.createDocumentFragment();
    dims.forEach(function (dim) {
      var li = document.createElement('li');
      var a = document.createElement('a');
      a.href = '/' + encodeURIComponent(toPath(dim.slug));
      a.appendChild(buildThumb(dim));
      var body = document.createElement('span');
      body.className = 'dim-body';
      var name = document.createElement('span');
      name.className = 'dim-name';
      name.textContent = dim.name;
      body.appendChild(name);
      var meta = document.createElement('span');
      meta.className = 'dim-meta';
      meta.textContent = !dim.layers ? 'not drawn yet' : dim.explored ? 'explored ground drawn' : 'nobody has been here yet';
      body.appendChild(meta);
      a.title = dim.description ? dim.name + ' — ' + dim.description : dim.name;
      a.appendChild(body);
      if (dim.slug === current) a.setAttribute('aria-current', 'page');
      li.appendChild(a);
      frag.appendChild(li);
    });
    listEl.innerHTML = '';
    listEl.appendChild(frag);
    var here = listEl.querySelector('[aria-current="page"]');
    if (here && here.scrollIntoView) here.scrollIntoView({ block: 'nearest' });
  }

  function buildThumb(dim) {
    var thumb = document.createElement('span');
    thumb.className = 'dim-thumb';
    if (dim.background) thumb.style.backgroundColor = dim.background;
    if (!dim.thumb) {
      thumb.classList.add('dim-thumb-empty');
      thumb.setAttribute('aria-hidden', 'true');
      return thumb;
    }
    var img = document.createElement('img');
    img.src = dim.thumb + '?v=' + String(dim.thumbVersion || dim.version || 0);
    img.alt = dim.name + ' — map preview';
    img.loading = 'lazy';
    img.decoding = 'async';
    img.addEventListener('error', function () {
      img.remove();
      thumb.classList.add('dim-thumb-empty');
    });
    thumb.appendChild(img);
    return thumb;
  }

  function loadDimension(dims) {
    var slug = slugFromPath();
    var dim = null;
    for (var i = 0; i < dims.length; i++) if (dims[i].slug === (slug || dims[0].slug)) dim = dims[i];
    if (!dim || !dim.layers) {
      showEmpty(dim ? dim.name : 'Unknown world', dim ? 'This world has not been drawn yet.' : 'No world goes by that name.');
      return;
    }
    document.title = dim.name + ' — World Map';
    mapEl.style.backgroundColor = dim.background || DEFAULT_BG;
    statusEl.textContent = 'Loading ' + dim.name + '…';
    var base = '/maps/' + encodeURIComponent(dim.slug) + '/';
    var v = '?v=' + String(dim.version || 0);
    Promise.all([
      getJson(dim.layers + v),
      dim.explored ? getJson(base + 'explored.json' + v).catch(function () { return null; }) : Promise.resolve(null),
      getJson(base + 'markers.json' + v).catch(function () { return { structures: [] }; })
    ]).then(function (got) {
      var info = got[0];
      info.version = dim.renderedAt || 0;
      view.info = info;
      view.base = base;
      if (got[1] && got[1].index) {
        view.exploredIndex = got[1].index;
        view.exploredPath = got[1].path || 'explored';
        view.exploredVersion = got[1].updatedAt || 0;
      } else {
        showExplored.checked = false;
        showExplored.disabled = true;
      }
      buildLayers(info, dim.slug);
      buildMarkers(got[2].structures || []);
      view.fit();
      if (dim.spawn && dim.spawn.length === 3 && view.zoom < 1) {
        view.cx = dim.spawn[0];
        view.cz = dim.spawn[2];
        view.clamp();
      }
      view.ready = true;
      view.render();
      statusEl.textContent = dim.name + ' loaded';
    }).catch(function () { statusEl.textContent = 'Could not load ' + dim.name; });
  }

  function layerLabel(l) {
    return l.y !== undefined && l.y !== null ? l.name + ' · y ' + l.y : l.name;
  }

  function buildLayers(info, slug) {
    var wanted = store('mapLayer:' + slug);
    var chosen = info.layers[0];
    info.layers.forEach(function (l) { if (l.id === wanted) chosen = l; });
    info.layers.forEach(function (l) {
      var label = document.createElement('label');
      var input = document.createElement('input');
      input.type = 'radio';
      input.name = 'base-layer';
      input.value = l.id;
      input.checked = l === chosen;
      input.addEventListener('change', function () {
        view.layer = l;
        store('mapLayer:' + slug, l.id);
        showLegend(info, l);
        view.render();
      });
      label.appendChild(input);
      label.appendChild(document.createTextNode(' ' + layerLabel(l)));
      baseLayersEl.appendChild(label);
    });
    view.layer = chosen;
    showLegend(info, chosen);
  }

  function showLegend(info, layer) {
    var rows = [];
    if (layer.kind === 'biomes') rows = (info.legend && info.legend.biomes) || [];
    if (layer.kind === 'cut') rows = (info.legend && info.legend.cuts) || [];
    legendEl.innerHTML = '';
    legendEl.hidden = !rows.length;
    if (!rows.length) return;
    var h = document.createElement('h3');
    h.textContent = layer.kind === 'biomes' ? 'Biomes' : 'In a cut';
    legendEl.appendChild(h);
    var ul = document.createElement('ul');
    rows.slice(0, 40).forEach(function (r) {
      var li = document.createElement('li');
      var sw = document.createElement('span');
      sw.className = 'swatch';
      sw.style.backgroundColor = r.colour;
      li.appendChild(sw);
      li.appendChild(document.createTextNode(r.name));
      ul.appendChild(li);
    });
    legendEl.appendChild(ul);
  }

  function buildMarkers(list) {
    markersEl.innerHTML = '';
    view.markers = list.map(function (s) {
      var el = document.createElement('button');
      el.type = 'button';
      el.className = 'marker';
      el.title = s.name + ' (' + s.x + ', ' + s.z + ')';
      el.setAttribute('aria-label', el.title);
      el.addEventListener('click', function (e) {
        e.stopPropagation();
        labelEl.textContent = el.title;
        labelEl.hidden = false;
      });
      markersEl.appendChild(el);
      return { x: s.x, z: s.z, el: el };
    });
    if (!list.length) {
      showMarkers.checked = false;
      showMarkers.disabled = true;
    }
  }
})();
