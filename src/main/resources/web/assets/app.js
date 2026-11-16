/*
 * Create Railways — live web map (part of the Create-Railways-Dynmap-Bridge plugin).
 * Data comes from the server as a Server-Sent Events stream (/api/stream):
 *   network  — track geometry and portals (only when the network changes),
 *   stations — stations,
 *   trains   — trains (every 1–2 s; movement between updates is animated here).
 * The language ("en" / "ru") comes from config.yml via /api/config.json and can be
 * overridden with ?lang=en or ?lang=ru in the page address.
 */
"use strict";

(() => {
  // ------------------------------------------------------------------ translations

  const I18N = {
    en: {
      defaultTitle: "Create Railways Map",
      connecting: "Connecting…",
      online: "Online",
      reconnecting: "No connection — reconnecting…",
      closed: "No connection (server is off or too many viewers) — retrying in 10 s",
      togglePanel: "Collapse / expand",
      dimension: "Dimension",
      layers: "Layers",
      layerTracks: "Tracks",
      layerPortals: "Portals",
      layerStations: "Stations",
      layerStationLabels: "Station names",
      layerTrains: "Trains",
      layerTrainLabels: "Train names",
      trains: "Trains",
      search: "Search a train or station",
      openDynmap: "Open Dynmap",
      following: "Following:",
      stop: "Stop",
      trackStandard: "Standard track",
      trackNarrow: "Narrow gauge",
      trackWide: "Wide gauge",
      trackMonorail: "Monorail",
      portalTo: "Portal → {0}",
      atStation: "At the station:",
      comingHere: "Heading here:",
      none: "none",
      derailed: "Derailed",
      stoppedAt: "Stopped: {0}",
      stopped: "Stopped",
      speed: "{0} km/h",
      nothingFound: "Nothing found",
      noTrains: "No trains yet",
      "minecraft:overworld": "Overworld",
      "minecraft:the_nether": "Nether",
      "minecraft:the_end": "The End",
    },
    ru: {
      defaultTitle: "Карта железных дорог Create",
      connecting: "Подключение…",
      online: "В сети",
      reconnecting: "Нет связи — переподключение…",
      closed: "Нет связи (сервер выключен или слишком много зрителей) — повтор через 10 с",
      togglePanel: "Свернуть / развернуть",
      dimension: "Измерение",
      layers: "Слои",
      layerTracks: "Пути",
      layerPortals: "Порталы",
      layerStations: "Станции",
      layerStationLabels: "Подписи станций",
      layerTrains: "Поезда",
      layerTrainLabels: "Подписи поездов",
      trains: "Поезда",
      search: "Поиск поезда или станции",
      openDynmap: "Открыть Dynmap",
      following: "Слежу за:",
      stop: "Остановить",
      trackStandard: "Обычные пути",
      trackNarrow: "Узкая колея",
      trackWide: "Широкая колея",
      trackMonorail: "Монорельс",
      portalTo: "Портал → {0}",
      atStation: "На станции:",
      comingHere: "Едут сюда:",
      none: "нет",
      derailed: "Сошёл с рельс",
      stoppedAt: "Стоит: {0}",
      stopped: "Стоит",
      speed: "{0} км/ч",
      nothingFound: "Ничего не найдено",
      noTrains: "Поездов пока нет",
      "minecraft:overworld": "Обычный мир",
      "minecraft:the_nether": "Незер",
      "minecraft:the_end": "Энд",
    },
  };
  let strings = I18N.en;

  /** Translated text; {0}, {1}… are replaced with the arguments. */
  function t(key, ...args) {
    const text = strings[key] ?? I18N.en[key] ?? key;
    return text.replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)] ?? ""));
  }

  function applyLanguage(code) {
    strings = I18N[code] || I18N.en;
    document.documentElement.lang = I18N[code] ? code : "en";
    document.querySelectorAll("[data-i18n]").forEach((el) => (el.textContent = t(el.dataset.i18n)));
    document.querySelectorAll("[data-i18n-placeholder]").forEach((el) => (el.placeholder = t(el.dataset.i18nPlaceholder)));
    document.querySelectorAll("[data-i18n-title]").forEach((el) => {
      el.title = t(el.dataset.i18nTitle);
      el.setAttribute("aria-label", el.title);
    });
  }

  // ------------------------------------------------------------------ display settings

  const TRACK_STYLES = [
    { match: "monorail", color: "#c792ea", label: "trackMonorail" },
    { match: "narrow", color: "#7fd1b9", label: "trackNarrow" },
    { match: "wide", color: "#f2c14e", label: "trackWide" },
    { match: "", color: "#aab4c3", label: "trackStandard" },
  ];
  const TRAIN_COLORS = { moving: "#f0a23b", stopped: "#5aa9e6", derailed: "#e5534b" };
  const TELEPORT_DISTANCE = 96; // if a train "jumps" farther than this, move it instantly instead of animating

  const state = {
    config: { dimensions: {} },
    network: { tracks: [], portals: [] },
    stations: [],
    trains: new Map(),
    networkDims: new Set(),
    stationDims: new Set(),
    interval: 1000,
    dim: null,
    follow: null,
    fittedOnce: false,
    animating: false,
  };

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const ll = (p) => [p[2], p[0]];
  const dimLabel = (d) => state.config.dimensions?.[d] || (strings[d] ?? I18N.en[d]) || d;
  const trackStyle = (type) => TRACK_STYLES.find((s) => (type || "").includes(s.match));

  // ------------------------------------------------------------------ map

  // X to the right (east), Z down (south) — like Minecraft maps. 1 block = 1 pixel at zoom 0.
  const crs = L.Util.extend({}, L.CRS.Simple, { transformation: new L.Transformation(1, 0, 1, 0) });
  const map = L.map("map", {
    crs,
    minZoom: -6,
    maxZoom: 4,
    zoomSnap: 0.25,
    zoomDelta: 0.5,
    wheelPxPerZoomLevel: 90,
    zoomControl: false,
    preferCanvas: true,
  });
  L.control.zoom({ position: "topright" }).addTo(map);
  map.attributionControl.setPrefix('<a href="https://leafletjs.com" target="_blank" rel="noopener">Leaflet</a>');
  map.setView([0, 0], -1);

  for (const [name, z] of [["tracks", 410], ["portals", 420], ["stations", 430], ["trains", 440]]) {
    map.createPane(name).style.zIndex = z;
  }
  const renderers = {
    tracks: L.canvas({ pane: "tracks", padding: 0.5 }),
    portals: L.canvas({ pane: "portals", padding: 0.5 }),
    stations: L.canvas({ pane: "stations", padding: 0.5 }),
    trains: L.canvas({ pane: "trains", padding: 0.5 }),
  };
  const layers = {
    tracks: L.layerGroup().addTo(map),
    portals: L.layerGroup().addTo(map),
    stations: L.layerGroup().addTo(map),
    trains: L.layerGroup().addTo(map),
  };

  // ------------------------------------------------------------------ tracks and portals

  function bezier(p, out) {
    const [a, b, c, d] = [p[0], p[1], p[2], p[3]];
    const length = Math.hypot(b[0] - a[0], b[2] - a[2]) + Math.hypot(c[0] - b[0], c[2] - b[2]) + Math.hypot(d[0] - c[0], d[2] - c[2]);
    const steps = Math.max(4, Math.min(48, Math.ceil(length / 2)));
    for (let i = 0; i <= steps; i++) {
      const t = i / steps;
      const mt = 1 - t;
      const k0 = mt * mt * mt, k1 = 3 * mt * mt * t, k2 = 3 * mt * t * t, k3 = t * t * t;
      out.push([
        k0 * a[2] + k1 * b[2] + k2 * c[2] + k3 * d[2],
        k0 * a[0] + k1 * b[0] + k2 * c[0] + k3 * d[0],
      ]);
    }
    return out;
  }

  function renderTracks() {
    layers.tracks.clearLayers();
    // All pieces of one style become one multi-polyline, so thousands of tracks draw quickly.
    const groups = new Map();
    for (const track of state.network.tracks) {
      if (track.d !== state.dim) continue;
      const style = trackStyle(track.t);
      const phantom = (track.t || "").includes("phantom");
      const key = style.label + (phantom ? "|phantom" : "");
      if (!groups.has(key)) groups.set(key, { style, phantom, lines: [] });
      groups.get(key).lines.push(track.p.length === 4 ? bezier(track.p, []) : track.p.map(ll));
    }
    for (const g of groups.values()) {
      L.polyline(g.lines, {
        renderer: renderers.tracks,
        color: g.style.color,
        weight: 3,
        opacity: g.phantom ? 0.4 : 0.95,
        dashArray: g.phantom ? "4 6" : null,
        lineCap: "round",
        lineJoin: "round",
        interactive: false,
      }).addTo(layers.tracks);
    }
    renderLegend(groups);
  }

  function renderLegend(groups) {
    const used = new Map();
    for (const g of groups.values()) used.set(g.style.label, g.style.color);
    $("legend").innerHTML = used.size > 1
      ? [...used].map(([label, color]) => `<span><i style="background:${color}"></i>${esc(t(label))}</span>`).join("")
      : "";
  }

  function renderPortals() {
    layers.portals.clearLayers();
    for (const portal of state.network.portals) {
      for (const [d, p, other] of [[portal.fd, portal.f, portal.td], [portal.td, portal.t, portal.fd]]) {
        if (d !== state.dim) continue;
        L.circleMarker(ll(p), {
          renderer: renderers.portals,
          radius: 7,
          color: "#b07cff",
          weight: 3,
          fillColor: "#2b1a45",
          fillOpacity: 1,
        })
          .bindTooltip(esc(t("portalTo", dimLabel(other))), { direction: "top" })
          .on("click", () => selectDimension(other, p))
          .addTo(layers.portals);
      }
    }
  }

  // ------------------------------------------------------------------ stations

  function renderStations() {
    layers.stations.clearLayers();
    const labels = $("layer-station-labels").checked;
    for (const station of state.stations) {
      if (station.d !== state.dim) continue;
      const marker = L.circleMarker(ll(station.p), {
        renderer: renderers.stations,
        radius: 5,
        color: "#11151c",
        weight: 2,
        fillColor: "#ffffff",
        fillOpacity: 1,
      });
      if (labels) {
        marker.bindTooltip(esc(station.name), { permanent: true, direction: "right", offset: [6, 0], className: "station-label" });
      } else {
        marker.bindTooltip(esc(station.name), { direction: "right", offset: [6, 0], className: "station-label" });
      }
      marker.bindPopup(() => stationPopup(station));
      marker.addTo(layers.stations);
    }
  }

  function stationPopup(station) {
    const here = [];
    const coming = [];
    for (const train of state.trains.values()) {
      if (train.info.station === station.name) here.push(train.info.name);
      else if (train.info.dest === station.name) coming.push(train.info.name);
    }
    const list = (items) => items.length ? items.map(esc).join(", ") : `<span class="muted">${esc(t("none"))}</span>`;
    return `<h3>${esc(station.name)}</h3>
      <div class="muted">X ${Math.round(station.p[0])}, Y ${Math.round(station.p[1])}, Z ${Math.round(station.p[2])}</div>
      <div>${esc(t("atStation"))} ${list(here)}</div>
      <div>${esc(t("comingHere"))} ${list(coming)}</div>`;
  }

  // ------------------------------------------------------------------ trains

  function trainStatus(info) {
    if (info.derailed) return "derailed";
    return Math.abs(info.speed) < 0.001 ? "stopped" : "moving";
  }

  function lerpPoint(a, b, k) {
    return [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k];
  }

  function distance(a, b) {
    return Math.hypot(a[0] - b[0], a[2] - b[2]);
  }

  /** Whether carriages can be animated smoothly from position a to position b. */
  function compatible(a, b) {
    if (!a || a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
      const x = a[i], y = b[i];
      if (x.ld !== y.ld || x.td !== y.td || !x.l !== !y.l || !x.t !== !y.t) return false;
      if (x.l && distance(x.l, y.l) > TELEPORT_DISTANCE) return false;
      if (x.t && distance(x.t, y.t) > TELEPORT_DISTANCE) return false;
    }
    return true;
  }

  function carsAt(train, now) {
    const k = Math.max(0, Math.min(1, (now - train.start) / state.interval));
    if (k >= 1 || train.from === train.to) return train.to;
    return train.to.map((car, i) => {
      const from = train.from[i];
      return {
        ld: car.ld,
        td: car.td,
        l: car.l && lerpPoint(from.l, car.l, k),
        t: car.t && lerpPoint(from.t, car.t, k),
      };
    });
  }

  function leadPoint(cars) {
    const first = cars[0];
    if (!first) return null;
    return first.l ? { d: first.ld, p: first.l } : first.t ? { d: first.td, p: first.t } : null;
  }

  function createTrainLayers(train) {
    train.line = L.polyline([], {
      renderer: renderers.trains,
      weight: 7,
      opacity: 1,
      lineCap: "round",
      interactive: true,
    }).on("click", () => followTrain(train.id));
    train.head = L.circleMarker([0, 0], {
      renderer: renderers.trains,
      radius: 4,
      color: "#11151c",
      weight: 2,
      fillColor: "#ffffff",
      fillOpacity: 1,
    }).on("click", () => followTrain(train.id));
    train.line.addTo(layers.trains);
  }

  function styleTrain(train) {
    const color = TRAIN_COLORS[trainStatus(train.info)];
    train.line.setStyle({ color });
    const labels = $("layer-train-labels").checked;
    if (train.labelShown !== labels) {
      // Permanent label or hover label: recreate the tooltip when the setting changes.
      train.head.unbindTooltip();
      train.head.bindTooltip(esc(train.info.name), { permanent: labels, direction: "right", offset: [8, 0], className: "train-label" });
      train.labelShown = labels;
    } else if (train.labelName !== train.info.name) {
      train.head.setTooltipContent(esc(train.info.name));
    }
    train.labelName = train.info.name;
  }

  function drawTrain(train, now) {
    const cars = carsAt(train, now);
    const segments = [];
    for (const car of cars) {
      const lead = car.l && car.ld === state.dim ? car.l : null;
      const trail = car.t && car.td === state.dim ? car.t : null;
      if (lead && trail) segments.push([ll(lead), ll(trail)]);
      else if (lead || trail) segments.push([ll(lead || trail), ll(lead || trail)]);
    }
    train.line.setLatLngs(segments);
    const head = leadPoint(cars);
    if (head && head.d === state.dim) {
      train.head.setLatLng(ll(head.p));
      if (!layers.trains.hasLayer(train.head)) train.head.addTo(layers.trains);
    } else if (layers.trains.hasLayer(train.head)) {
      layers.trains.removeLayer(train.head);
    }
  }

  function animate() {
    const now = performance.now();
    let moving = false;
    for (const train of state.trains.values()) {
      if (train.settled) continue;
      drawTrain(train, now);
      if (train.from === train.to || now - train.start >= state.interval) {
        train.settled = true; // the last frame was drawn exactly at the target position
      } else {
        moving = true;
      }
    }
    if (moving) {
      requestAnimationFrame(animate);
    } else {
      state.animating = false;
    }
  }

  function startAnimation() {
    if (!state.animating) {
      state.animating = true;
      requestAnimationFrame(animate);
    }
  }

  function onTrains(data) {
    state.interval = Math.max(250, data.interval || 1000);
    const now = performance.now();
    const seen = new Set();
    for (const info of data.trains) {
      seen.add(info.id);
      let train = state.trains.get(info.id);
      if (!train) {
        train = { id: info.id, info, from: info.cars, to: info.cars, start: now };
        createTrainLayers(train);
        state.trains.set(info.id, train);
      } else {
        const current = carsAt(train, now);
        train.from = compatible(current, info.cars) ? current : info.cars;
        train.to = info.cars;
        train.start = now;
        train.info = info;
      }
      train.settled = false;
      styleTrain(train);
      drawTrain(train, now);
    }
    for (const [id, train] of state.trains) {
      if (!seen.has(id)) {
        layers.trains.removeLayer(train.line);
        layers.trains.removeLayer(train.head);
        state.trains.delete(id);
        if (state.follow === id) stopFollow();
      }
    }
    startAnimation();
    renderTrainList();
    updateDimensions();
    if (state.follow) {
      const train = state.trains.get(state.follow);
      const head = train && leadPoint(train.to);
      if (head && head.d === state.dim) {
        map.panTo(ll(head.p), { animate: true, duration: state.interval / 1000, easeLinearity: 1, noMoveStart: true });
      }
    }
  }

  function redrawAllTrains() {
    const now = performance.now();
    for (const train of state.trains.values()) {
      styleTrain(train);
      drawTrain(train, now);
    }
  }

  // ------------------------------------------------------------------ train list

  function trainStateText(train) {
    const info = train.info;
    const kmh = Math.round(Math.abs(info.speed) * 20 * 3.6);
    let text;
    if (info.derailed) text = t("derailed");
    else if (Math.abs(info.speed) < 0.001) text = info.station ? t("stoppedAt", info.station) : t("stopped");
    else text = t("speed", kmh);
    if (info.dest && info.dest !== info.station && !info.derailed) text += ` → ${info.dest}`;
    const head = leadPoint(train.to);
    if (head && head.d !== state.dim) text += ` · ${dimLabel(head.d)}`;
    return text;
  }

  function renderTrainList() {
    const query = $("train-search").value.trim().toLowerCase();
    const trains = [...state.trains.values()].sort((a, b) => a.info.name.localeCompare(b.info.name, document.documentElement.lang, { numeric: true }));
    $("train-count").textContent = trains.length;
    const items = [];
    for (const train of trains) {
      const info = train.info;
      const haystack = `${info.name} ${info.station || ""} ${info.dest || ""}`.toLowerCase();
      if (query && !haystack.includes(query)) continue;
      const color = TRAIN_COLORS[trainStatus(info)];
      items.push(`<li data-id="${esc(train.id)}" class="${state.follow === train.id ? "active" : ""}">
        <span class="train-dot" style="background:${color}"></span>
        <span class="train-info"><div class="train-name">${esc(info.name)}</div><div class="train-state">${esc(trainStateText(train))}</div></span>
      </li>`);
    }
    if (!items.length) {
      items.push(`<li class="empty">${esc(trains.length ? t("nothingFound") : t("noTrains"))}</li>`);
    }
    $("train-list").innerHTML = items.join("");
  }

  $("train-list").addEventListener("click", (event) => {
    const item = event.target.closest("li[data-id]");
    if (item) followTrain(item.dataset.id);
  });
  $("train-search").addEventListener("input", renderTrainList);

  function followTrain(id) {
    const train = state.trains.get(id);
    if (!train) return;
    const head = leadPoint(train.to);
    if (!head) return;
    state.follow = id;
    if (head.d !== state.dim) selectDimension(head.d);
    map.setView(ll(head.p), Math.max(map.getZoom(), 1));
    $("follow-name").textContent = train.info.name;
    $("follow").hidden = false;
    renderTrainList();
  }

  function stopFollow() {
    state.follow = null;
    $("follow").hidden = true;
    renderTrainList();
  }

  $("follow-stop").addEventListener("click", stopFollow);
  map.on("dragstart", () => state.follow && stopFollow());

  // ------------------------------------------------------------------ dimensions and view

  function knownDimensions() {
    const dims = new Set(Object.keys(state.config.dimensions || {}));
    for (const d of state.networkDims) dims.add(d);
    for (const d of state.stationDims) dims.add(d);
    for (const t of state.trains.values()) for (const c of t.to) { if (c.ld) dims.add(c.ld); if (c.td) dims.add(c.td); }
    if (state.dim) dims.add(state.dim);
    return [...dims];
  }

  function updateDimensions() {
    const select = $("dimension");
    const dims = knownDimensions();
    const current = [...select.options].map((o) => o.value).join("|");
    if (current === dims.join("|")) return;
    select.innerHTML = dims.map((d) => `<option value="${esc(d)}">${esc(dimLabel(d))}</option>`).join("");
    select.value = state.dim;
  }

  function selectDimension(dim, center) {
    if (!dim || dim === state.dim) {
      if (center) map.setView(ll(center), Math.max(map.getZoom(), 0));
      return;
    }
    state.dim = dim;
    updateDimensions();
    $("dimension").value = dim;
    renderTracks();
    renderPortals();
    renderStations();
    redrawAllTrains();
    renderTrainList();
    if (center) map.setView(ll(center), Math.max(map.getZoom(), 0));
    else fitDimension();
    saveView();
  }

  $("dimension").addEventListener("change", (e) => {
    if (state.follow) stopFollow();
    selectDimension(e.target.value);
  });

  function fitDimension() {
    const points = [];
    for (const t of state.network.tracks) if (t.d === state.dim) { points.push(ll(t.p[0]), ll(t.p[t.p.length - 1])); }
    for (const s of state.stations) if (s.d === state.dim) points.push(ll(s.p));
    if (points.length) {
      map.fitBounds(L.latLngBounds(points), { padding: [40, 40], maxZoom: 1 });
      return true;
    }
    return false;
  }

  function saveView() {
    if (!state.dim) return;
    const c = map.getCenter();
    const hash = `#${encodeURIComponent(state.dim)}/${Math.round(c.lng)}/${Math.round(c.lat)}/${map.getZoom()}`;
    history.replaceState(null, "", hash);
  }

  function loadView() {
    const parts = location.hash.replace(/^#/, "").split("/");
    if (parts.length !== 4) return false;
    const [dim, x, z, zoom] = [decodeURIComponent(parts[0]), Number(parts[1]), Number(parts[2]), Number(parts[3])];
    if (!dim || [x, z, zoom].some(Number.isNaN)) return false;
    state.dim = dim;
    map.setView([z, x], zoom);
    return true;
  }

  map.on("moveend", saveView);
  map.on("mousemove", (e) => {
    $("coords").textContent = `X: ${Math.round(e.latlng.lng)}  Z: ${Math.round(e.latlng.lat)}`;
  });

  // ------------------------------------------------------------------ layers

  function bindLayerToggle(id, layer) {
    $(id).addEventListener("change", (e) => (e.target.checked ? layer.addTo(map) : map.removeLayer(layer)));
  }
  bindLayerToggle("layer-tracks", layers.tracks);
  bindLayerToggle("layer-portals", layers.portals);
  bindLayerToggle("layer-stations", layers.stations);
  bindLayerToggle("layer-trains", layers.trains);
  $("layer-station-labels").addEventListener("change", renderStations);
  $("layer-train-labels").addEventListener("change", redrawAllTrains);

  $("panel-toggle").addEventListener("click", () => $("panel").classList.toggle("collapsed"));
  if (window.innerWidth < 600) $("panel").classList.add("collapsed");

  // ------------------------------------------------------------------ data from the server

  function setStatus(kind, text) {
    $("status").className = `status status-${kind}`;
    $("status-text").textContent = text;
  }

  function onNetwork(data) {
    state.network = { tracks: data.tracks || [], portals: data.portals || [] };
    state.networkDims = new Set(state.network.tracks.map((t) => t.d));
    for (const p of state.network.portals) { state.networkDims.add(p.fd); state.networkDims.add(p.td); }
    updateDimensions();
    renderTracks();
    renderPortals();
    if (!state.fittedOnce) {
      state.fittedOnce = fitDimension();
    }
  }

  function onStations(data) {
    state.stations = data.stations || [];
    state.stationDims = new Set(state.stations.map((s) => s.d));
    updateDimensions();
    renderStations();
    if (!state.fittedOnce) {
      state.fittedOnce = fitDimension();
    }
  }

  let source = null;
  function connect() {
    if (source) source.close();
    source = new EventSource("api/stream");
    source.addEventListener("network", (e) => onNetwork(JSON.parse(e.data)));
    source.addEventListener("stations", (e) => onStations(JSON.parse(e.data)));
    source.addEventListener("trains", (e) => onTrains(JSON.parse(e.data)));
    source.onopen = () => setStatus("ok", t("online"));
    source.onerror = () => {
      if (source.readyState === EventSource.CLOSED) {
        setStatus("bad", t("closed"));
        setTimeout(connect, 10000);
      } else {
        setStatus("bad", t("reconnecting"));
      }
    };
  }

  function applyConfig(config) {
    state.config = Object.assign({ dimensions: {} }, config);
    const override = new URLSearchParams(location.search).get("lang");
    applyLanguage(override || state.config.language || "en");
    const title = state.config.title || t("defaultTitle");
    $("title").textContent = title;
    document.title = title;
    $("layer-station-labels").checked = state.config.stationLabels !== false;
    if (state.config.dynmapUrl) {
      $("dynmap-link").href = state.config.dynmapUrl;
      $("dynmap-link").hidden = false;
    }
    if (state.config.version) $("version").textContent = `v${state.config.version}`;
    if (loadView()) {
      state.fittedOnce = true;
    } else {
      state.dim = state.config.defaultDimension || "minecraft:overworld";
    }
    updateDimensions();
  }

  fetch("api/config.json")
    .then((r) => (r.ok ? r.json() : {}))
    .catch(() => ({}))
    .then((config) => {
      applyConfig(config);
      connect();
    });
})();
