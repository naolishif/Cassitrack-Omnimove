// BUGFIX (auth): this used to be a client-side route guard that read
// 'cassitrack_token'/'cassitrack_user' from localStorage and redirected
// to login if missing. Since the V-04 fix (httpOnly-cookie auth), login.js
// no longer writes anything to localStorage, so this guard's condition was
// always true and it unconditionally bounced every FLEET_MANAGER straight
// back to the login page after a successful login. The role check it
// performed is redundant anyway: SecurityConfig already gates
// /cassitrack-fleetmanager.html itself to FLEET_MANAGER/ROLE_FLEET_MANAGER
// server-side (a non-fleet-manager hitting this URL gets a 403 before this
// script ever runs), matching how cassitrack-admin.js has no client-side
// gate either. Removed.

const API          = '/cassitrack/api/v1';              // CASSITRACK — fleet, ETA  (context-path prefix required)

// A 401 from any of this page's ~39 fetch calls sends us back to login;
// Back out of a dead session re-checks with the server instead of showing a stale console
CassiSession.installFetchGuard();
CassiSession.bindSessionGuard();

// XSS defense: escape any API-supplied string before inserting into innerHTML
function escHtml(s) {
    return String(s ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

// CSP FIX (A05): style-src no longer allows 'unsafe-inline', so any genuinely
// dynamic per-instance colour/width value (route hue, crowding %, etc.) cannot
// be written as style="..." in an HTML/innerHTML string anymore. Instead, such
// elements carry harmless data-fg / data-bg / data-width-pct attributes, and
// this helper applies them via the CSSOM (element.style.xxx = ...), which is a
// property assignment, not inline-HTML/CSS parsing — entirely unaffected by the
// style-src directive. Call this right after inserting any markup that may
// contain such data-* attributes (innerHTML, or after a Leaflet popup opens).
function applyDynStyles(root) {
    if (!root) return;
    root.querySelectorAll('[data-fg]').forEach(el => { el.style.color = el.dataset.fg; });
    root.querySelectorAll('[data-bg]').forEach(el => { el.style.background = el.dataset.bg; });
    root.querySelectorAll('[data-width-pct]').forEach(el => { el.style.width = el.dataset.widthPct + '%'; });
}

// Derived dynamically so this works on both localhost and the public server.
// The fleet manager is always served by CassiTrack; OmniMove always runs on
// the same host, port 8180. Using window.location.hostname means the browser
// calls the right machine regardless of whether it's 127.0.0.1 or 193.205.60.151.
const OMNIMOVE_API = window.location.protocol + '//' + window.location.hostname + ':8180/api/v1';
const REFRESH = 15000;
const SC = {
    ON_TIME:'#22C55E',SLIGHTLY_LATE:'#F59E0B',
    SIGNIFICANTLY_LATE:'#EF4444',EARLY:'#06B6D4',UNKNOWN:'#4B5563',
    NO_TRIP:'#F59E0B'
};
// UNKNOWN reads "LIVE": the bus is transmitting but has not reached a stop
// yet, so there is nothing to compare against the timetable. Once it passes
// its first stop the status becomes a real one and — because the backend
// carries the last measurement across trip boundaries — never falls back
// here again for the rest of the service day.
const SL = {ON_TIME:'ON TIME',SLIGHTLY_LATE:'SLIGHTLY LATE',SIGNIFICANTLY_LATE:'LATE',EARLY:'EARLY',UNKNOWN:'LIVE',NO_TRIP:'⚠️ NO TRIP'};
const MODE_ICON = {BUS:'🚌',WALK:'🚶',BIKE:'🚲',SCOOTER:'🛴',CAR:'🚗',WAIT:'⏳'};
const MODE_COL  = {BUS:'#3B82F6',WALK:'#22C55E',BIKE:'#22C55E',SCOOTER:'#F59E0B',CAR:'#EF4444',WAIT:'#4B5563'};

// Map
let map;

window.addEventListener('load', () => {

    map = L.map('map', {
        center: [41.497, 13.822],
        zoom: 14
    });

    // OpenStreetMap, darkened in CSS — not CARTO.
    //
    // CARTO's dark_all basemap now needs an API key, and it does not say so in
    // a way any code can catch: the tile still comes back 200 with a valid PNG,
    // and "API KEY REQUIRED" is drawn INSIDE the image, diagonally across the
    // map. Nothing throws, nothing logs, and the watermark simply appears under
    // the buses. OMNIMOVE never had it because it draws plain OSM tiles.
    //
    // Plain OSM is light and this dashboard is dark, so the tile pane is
    // inverted in CSS (see .leaflet-tile-pane). The filter is scoped to the
    // tiles alone: routes, stops and vehicle markers live in other panes and
    // keep the colours they are given.
    L.tileLayer(
        'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
        {
            attribution: '© OpenStreetMap contributors',
            maxZoom: 19
        }
    ).addTo(map);

    fetch(`${API}/vehicles/fleet-size`).then(r=>r.json())
        .then(d=>{ if(d && d.total) fleetSize = d.total; })
        .catch(()=>{});

    fetch(`${API}/routes`).then(r=>r.json()).then(routes=>{
        routes.forEach((route, i)=>{
            // The line's own colour, the same value OmniMove draws. This map
            // used to generate a golden-angle hue per index instead, so the two
            // apps painted the same line differently and the colour a fleet
            // manager picked in Data Management was visible nowhere but the
            // swatch in that table. The generated hue is the fallback now, for
            // a route that genuinely has no colour set.
            const color = route.color
                ? '#' + String(route.color).replace(/^#/, '')
                : `hsl(${(i * 137.5) % 360}, 70%, 55%)`;
            routeColors[route.id] = color;
            routeColors[route.name] = color;

            // la polilinea della linea resta una per linea (ref salvata per filtro)
            //
            // Draw the real road geometry (route_shapes) when the backend
            // provides it: buses follow the streets, so a stop-to-stop
            // polyline would leave them visibly off the line. Routes with
            // no shape fall back to the original stop-to-stop rendering.
            const hasPath = Array.isArray(route.path) && route.path.length >= 2;
            const latlngs = hasPath
                ? route.path.map(p => [p.lat, p.lon])
                : route.stops.map(s => [s.lat, s.lon]);

            const pl = L.polyline(latlngs,
                // Solid when it traces the real roads; dashed stays the
                // visual cue that a line is only a schematic guess.
                {color, weight:4, opacity:.8, ...(hasPath ? {} : {dashArray:'8 8'})}
            ).addTo(map);
            routePolylines[route.id] = pl;

            // Click a line to filter the fleet down to that route; click the
            // same line again to clear. A 4px stroke is a small target, so a
            // wider transparent line is laid underneath to catch the click —
            // the visible weight stays unchanged.
            const hit = L.polyline(latlngs,
                // interactive:true is explicit — a fully transparent stroke
                // must still receive pointer events for this to work.
                {color, weight:18, opacity:0, interactive:true}).addTo(map);
            routeHitAreas[route.id] = hit;
            [pl, hit].forEach(layer=>{
                layer.on('click', e=>{
                    L.DomEvent.stopPropagation(e);   // don't let the map clear the selection
                    toggleRouteFilter(route.id);
                });
                layer.on('mouseover', ()=>{ if(routeFilter !== route.id) pl.setStyle({weight:6}); });
                layer.on('mouseout',  ()=>{ if(routeFilter !== route.id) pl.setStyle({weight:4}); });
            });

            // accumula le fermate, unendo le linee che le servono
            route.stops.forEach(s=>{
                if(!stopMap[s.id]) stopMap[s.id] = {name:s.name, lat:s.lat, lon:s.lon, lines:[], routeIds:new Set()};
                stopMap[s.id].routeIds.add(route.id);
                if(!stopMap[s.id].lines.some(l=>l.name===route.name))
                    stopMap[s.id].lines.push({name:route.name, color});
            });
        });

        // Populate the Route filter dropdown (Fleet Monitor sidebar).
        const rf = document.getElementById('routeFilter');
        if(rf){
            // The public line number (shortName) is NOT unique — several variants
            // can share it (e.g. two "2": full loop vs half-run). The option VALUE
            // is always the unique route id; the LABEL adds the description so the
            // user can tell same-numbered variants apart.
            rf.innerHTML = '<option value="">All routes</option>' +
                routes.map(r=>{
                    const num = r.name || r.id;
                    const label = r.longName ? `${num} · ${r.longName}` : num;
                    return `<option value="${escHtml(r.id)}">${escHtml(label)}</option>`;
                }).join('');
        }

        // un solo marker per fermata, con TUTTE le linee nel popup
        Object.values(stopMap).forEach(st=>{
            // CSP FIX (A05): per-line colour is genuinely dynamic (one of N route
            // hues) -> data-fg + applyDynStyles() on popupopen, instead of style="".
            const linesHtml = st.lines.map(l=>
                `<span class="line-fg" data-fg="${routeInk(l.color)}">${escHtml(l.name)}</span>`
            ).join(' · ');
            const sm = L.circleMarker([st.lat, st.lon],{
                radius:7, fillColor:'#0f1623', color:'#94A3B8', weight:2, opacity:1, fillOpacity:1
            }).addTo(map).bindPopup(`
                    <div class="stop-popup">
                        <div class="stop-popup-title">🚏 ${escHtml(st.name)}</div>
                        <div class="stop-popup-lines">Linee: ${linesHtml}</div>
                    </div>`);
            // Popup content is only parsed into the DOM when it actually opens
            // (Leaflet lazy-renders bindPopup() strings), so the dynamic colours
            // must be (re-)applied at that moment.
            sm.on('popupopen', e => applyDynStyles(e.popup.getElement()));
            // Raise it above the routes' invisible click targets straight
            // away, so stops are clickable on the very first paint rather
            // than only after the first vehicle poll re-runs the ordering.
            sm.bringToFront();
            st.marker = sm;
        });

        fetchVehicles();
    }).catch(e=>console.error('routes load failed',e));

    setTimeout(() => {
        map.invalidateSize();
    }, 500);

});


const markers={},vehicleData={};
let selectedVeh=null,lastUpdate=null;
const routeColors = {};   // id linea -> colore, riempita all'avvio
const routePolylines = {};// id linea -> polilinea Leaflet (per mostrare/nascondere)
const routeHitAreas  = {};// id linea -> polilinea invisibile e piu' spessa, solo per il click
let lastVehicles = [];    // ultima lista /vehicles ricevuta (per filtrare percorsi/fermate)
const stopMap = {};   // stopId -> { name, lat, lon, lines:[{name,color}] }
let fleetSize = 4;

// ── Bus map-visibility (user story) ──────────────────────────────────────
// Map visibility has ONE source of truth: buses.map_visible in the database,
// delivered on each vehicle payload as `map_visible`.
//
// There used to be a second, parallel mechanism here — an in-memory Set of
// hidden vehicle_ids driven by the 👁 button. Because the Data Management
// switch wrote to the database and the eye wrote to that Set, the two
// controls could disagree: neither reflected the other, and toggling one
// left the other showing the opposite state. Both now read and write the
// same persisted flag, so they are simply two views of one setting.
//
// A hidden bus stays fully tracked — its data keeps updating and it remains
// in the sidebar list, dimmed; only its map marker is suppressed.

// ── Route filter (Fleet Monitor) ─────────────────────────────────────────
// When set to a route id, only buses on that route are shown on the map and
// in the sidebar, and only that route's line + stops are drawn. null = all
// routes. Composes with the per-bus hide toggle and with focus mode.
let routeFilter    = null;   // route id, or null = all routes
let serviceFilter  = 'all';  // 'all' | 'in' (has trip) | 'out' (no trip)
let delayThreshold = 0;      // minutes; 0 = off, else keep only delay >= N
// How to draw a line nobody is running right now: 'dim' keeps it faint on
// the map (the road exists even outside service hours), 'hide' removes it
// with its stops, for an uncluttered view of what is actually moving.
let idleRoutesMode = 'dim';
// A bus is shown only if it passes EVERY active filter (logical AND).
function busPassesFilter(v){
    if(!v) return true;
    if(routeFilter && v.route_id !== routeFilter) return false;
    if(serviceFilter === 'in'  && !v.trip_id) return false;
    if(serviceFilter === 'out' &&  v.trip_id) return false;
    if(delayThreshold > 0 && (v.delay_minutes || 0) < delayThreshold) return false;
    return true;
}

// Add/remove a bus marker from the map according to its visibility state:
// a bus is on the map only if it is not hidden AND it passes the route filter.
function applyBusVisibility(id){
    const m = markers[id];
    if(!m) return;
    const v = vehicleData[id];
    // A bus that has reached its terminus keeps reporting from the depot
    // or the layover: it is parked, not in service. Showing it would
    // clutter the map with vehicles that are not going anywhere — it
    // reappears by itself as soon as its next run is assigned.
    const onDuty  = !!(v && v.trip_id);
    const visible = onDuty && !isBusHidden(id) && busPassesFilter(v);
    if(visible){ if(!map.hasLayer(m)) m.addTo(map); }
    else if(map.hasLayer(m)) map.removeLayer(m);
}

/**
 * Is this bus hidden from the map?
 *
 * Reads buses.map_visible, delivered on the vehicle payload — the single
 * flag behind both the Data Management "On map" switch and the sidebar 👁
 * button. Absent or true means visible, so a bus is only hidden when
 * somebody has explicitly turned it off.
 */
function isBusHidden(id){
    const v = vehicleData[id];
    return !!v && v.map_visible === false;   // absent/true → visible
}

/**
 * Flip a bus's map visibility from the sidebar 👁 button.
 *
 * Writes the same buses.map_visible flag the Data Management switch uses,
 * through the same endpoint — so the two controls can never disagree, and
 * the choice survives a page reload.
 *
 * Applied optimistically for an instant response, then reverted if the
 * server rejects it. The next /vehicles poll replaces the object wholesale
 * with the server's, so the local value is confirmed or corrected within
 * one refresh and cannot drift.
 */
async function toggleBusVisibility(id){
    const v = vehicleData[id];
    if(!v) return;
    if(v.bus_id == null && v.busId == null){
        // No registry row behind this vehicle: nothing to persist against.
        alert('This vehicle is not linked to a bus in the registry, so its map visibility cannot be saved.');
        return;
    }
    const busId   = v.busId != null ? v.busId : v.bus_id;
    const nextVis = isBusHidden(id);   // hidden -> show, visible -> hide

    const before = v.map_visible;
    v.map_visible = nextVis;                 // optimistic
    applyBusVisibility(id);
    updateRouteVisibility();                 // may be the route's last visible bus
    updateFleet(Object.values(vehicleData));

    try{
        const r = await fetch(`${API}/buses/${busId}/visibility?visible=${nextVis}`, {method:'PUT'});
        if(!r.ok) throw new Error(r.status);
        // Keep the Data Management table honest if it has already been loaded.
        const b = dmState.buses.find(x => x.busId === busId);
        if(b) b.mapVisible = nextVis;
    }catch(e){
        v.map_visible = before;              // revert
        applyBusVisibility(id);
        updateRouteVisibility();
        updateFleet(Object.values(vehicleData));
        alert('Could not update map visibility.');
    }
}

// CSP FIX (A05): schedule_status is a fixed 5-value domain (see SC above), so
// the bus marker icon needs no dynamic style at all — a data-status attribute
// plus static attribute-selector CSS rules (cassitrack-fleetmanager.css) covers
// every case. SC[status]||SC.UNKNOWN is preserved as a key-selection fallback.
/**
 * Inchiostro leggibile SOPRA un colore pieno.
 *
 * Diverso da routeInk(), che schiarisce un colore per renderlo leggibile sul
 * pannello scuro. Qui il colore E' lo sfondo, quindi la scelta è fra testo
 * chiaro e testo scuro secondo la luminanza: su un giallo linea il bianco
 * sparisce, sul blu il nero sparisce.
 */
function inkOn(bg){
    const m = /^#?([0-9a-f]{6})$/i.exec(String(bg).trim());
    if(!m) return '#FFFFFF';
    const n = parseInt(m[1], 16);
    // Luminanza percepita: l'occhio pesa molto il verde e poco il blu.
    const lum = (0.299*((n>>16)&255) + 0.587*((n>>8)&255) + 0.114*(n&255)) / 255;
    return lum > 0.6 ? '#0B1220' : '#FFFFFF';
}

/**
 * Il marker di un bus porta DUE informazioni indipendenti su due canali:
 *
 *   · la bolla  → come sta andando (azzurro anticipo, verde in orario,
 *                 giallo lieve ritardo, rosso oltre i 10 minuti, grigio
 *                 quando ancora non c'è una misura)
 *   · l'etichetta → su quale LINEA sta, col colore della linea stessa
 *
 * Prima portavano entrambe lo stato, quindi un canale visivo era sprecato:
 * il colore della linea, che il gestore sceglie in Data Management, sulla
 * mappa non compariva da nessuna parte.
 *
 * Le soglie della bolla sono quelle di ScheduleAdherenceService (−1 / +3 /
 * +10 minuti), non soglie proprie della mappa: lo stesso mezzo deve avere lo
 * stesso colore qui, nella tab Trips e nelle Analytics.
 */
function busIcon(id,status,routeColor){
    const st = SC[status] ? status : 'UNKNOWN';
    const col = routeColor || '#4B5563';
    // data-bg/data-fg invece di style="": la CSP vieta gli stili in linea,
    // e applyDynStyles() li trasferisce via CSSOM dopo il montaggio.
    return L.divIcon({className:'',html:`<div class="bus-icon-wrap"><div class="bus-icon-body" data-status="${st}"><span class="bus-icon-emoji">🚌</span></div><div class="bus-icon-label" data-bg="${escHtml(col)}" data-fg="${inkOn(col)}">${escHtml(id)}</div></div>`,iconSize:[60,58],iconAnchor:[18,50]});
}

async function fetchVehicles(){
    try{
        const r=await fetch(`${API}/vehicles`);
        if(!r.ok) throw new Error(r.status);

        let data=await r.json();

        lastUpdate=new Date();

        document.getElementById('hDot').className='dot dot-green';
        document.getElementById('hStatus').textContent=
            data.length>0
                ? `${data.length} bus${data.length>1?'es':''} active`
                : 'Connected — no buses';

        updateMap(data);
        updateFleet(data);
        if (typeof populateBusDropdown === 'function') populateBusDropdown(data);

    }catch(e){
        document.getElementById('hDot').className='dot dot-red';
        document.getElementById('hStatus').textContent='Backend offline';
    }
}

function updateMap(vehicles){
    lastVehicles = vehicles;

    // Drop the vehicles the backend no longer reports.
    //
    // /vehicles only returns buses seen within the last 5 minutes. Markers
    // were created but never removed, so a bus that went quiet — or that
    // finished its duty — stayed frozen on the map at its last known
    // position, indefinitely. Anything missing from this payload is gone:
    // clear its marker and its cached state.
    const stillHere = new Set(vehicles.map(v => v.vehicle_id));
    Object.keys(markers).forEach(id => {
        if(stillHere.has(id)) return;
        if(map.hasLayer(markers[id])) map.removeLayer(markers[id]);
        delete markers[id];
        delete vehicleData[id];
        // Was this the bus being followed? Release the focus, or the map
        // would keep panning to a vehicle that no longer exists.
        if(selectedVeh === id) selectedVeh = null;
    });

    vehicles.forEach(v=>{
        vehicleData[v.vehicle_id]=v;
        const st=!v.trip_id?'NO_TRIP':(v.schedule_status||'UNKNOWN'),pos=[v.lat,v.lon];
        // Colore della linea per l'etichetta. routeColors è riempita all'avvio
        // da /routes; il grigio è il ripiego per un mezzo senza corsa, che una
        // linea non ce l'ha.
        const rc = routeColors[v.route_id] || routeColors[v.route_name] || '#4B5563';
        if(markers[v.vehicle_id]){
            markers[v.vehicle_id].setLatLng(pos);
            markers[v.vehicle_id].setIcon(busIcon(v.vehicle_id,st,rc));
            applyDynStyles(markers[v.vehicle_id].getElement());
        }
        else{
            const m=L.marker(pos,{icon:busIcon(v.vehicle_id,st,rc)}).addTo(map);
            applyDynStyles(m.getElement());
            m.bindPopup(popupV(v));
            // CSP FIX (A05): popup content (popupV()) carries data-fg/data-bg
            // attributes for its dynamic route-colour and crowding-bar values;
            // apply them once Leaflet actually renders the popup into the DOM.
            m.on('popupopen', e => applyDynStyles(e.popup.getElement()));
            m.on('click',()=>selV(v.vehicle_id));
            // Closing the popup with its × means the same thing as
            // "← Back to fleet": drop the focus and show the whole fleet.
            //
            // Guarded on the bus still being the selected one. Selecting a
            // DIFFERENT bus makes Leaflet auto-close this popup, and by then
            // selectedVeh is already the other bus — without the guard that
            // would immediately deselect the bus just picked.
            m.on('popupclose',()=>{ if(selectedVeh===v.vehicle_id) selV(v.vehicle_id); });
            markers[v.vehicle_id]=m;
        }
        markers[v.vehicle_id].setPopupContent(popupV(v));
        // setPopupContent() re-renders the popup's inner DOM even while it's
        // currently open, so re-apply the dynamic styles in that case too.
        if (markers[v.vehicle_id].isPopupOpen()) applyDynStyles(markers[v.vehicle_id].getPopup().getElement());
        // Suppress the marker if this bus is hidden (data above still updated → bus stays tracked).
        applyBusVisibility(v.vehicle_id);
        // Focus follow: keep the map centred on the selected bus as it moves.
        if (selectedVeh === v.vehicle_id && !isBusHidden(v.vehicle_id))
            map.panTo([v.lat, v.lon], { animate: true, duration: 0.6 });
    });
    // refresh which routes/stops are shown (buses may have moved on/off routes,
    // or been toggled hidden — everything is recomputed from live data)
    updateRouteVisibility();
}

function popupV(v){
    // CSP FIX (A05): schedule_status -> fixed-domain data-status (static CSS).
    // routeColor()/crowding-bar values stay genuinely dynamic -> data-fg/data-bg.
    const st = !v.trip_id ? 'NO_TRIP' : (SC[v.schedule_status] ? v.schedule_status : 'UNKNOWN');
    const l=SL[v.schedule_status]||SL.UNKNOWN;
    const routeCol = routeColor(v.route_id);
    const cp=crowdPct(v),cc=crowdColor(v.crowding_level);
    const delayTxt = (typeof v.delay_minutes === 'number')
        ? (v.delay_minutes === 0 ? '0m'
            : `${v.delay_minutes > 0 ? '+' : ''}${v.delay_minutes}m`)
        : '—';
    return `
    <div class="vpop">
        <div class="vpop-top">
            <div class="vpop-id" data-status="${st}">🚌 ${escHtml(v.vehicle_id)}</div>
            <div class="vpop-badge" data-status="${st}">${l}</div>
        </div>
        <div class="vpop-grid">
            <div><div class="vpop-lbl">SPEED</div>${v.speed_kmh?v.speed_kmh.toFixed(1)+' km/h':'—'}</div>
            <div><div class="vpop-lbl">ROUTE</div><span data-fg="${routeCol}">${escHtml(v.route_name)||'—'}</span></div>
            <div><div class="vpop-lbl">DELAY</div><span class="vpop-delay" data-status="${st}">${delayTxt}</span></div>
            <div><div class="vpop-lbl">PASSENGERS</div>${v.estimated_passengers?'~'+v.estimated_passengers:'—'}</div>
            <div class="vpop-span2"><div class="vpop-lbl">LAST STOP</div>${escHtml(v.last_stop_name)||'—'}</div>
            <div class="vpop-span2"><div class="vpop-lbl">NEXT STOP</div>${escHtml(v.next_stop_name)||'—'}</div>
            <div class="vpop-span2"><div class="vpop-lbl">ADHERENCE</div><span class="vpop-delay" data-status="${st}">${adherenceText(v)}</span></div>
        </div>
        <div class="vpop-barwrap">
            <div class="vpop-bar" data-bg="${cc}" data-width-pct="${cp}"></div>
        </div>
        <div class="vpop-crowd">Crowding: ${escHtml(v.crowding_level)||'—'}</div>
    </div>`;
}

function updateFleet(all){
    // Route filter narrows every surface (analytics counts + sidebar list).
    const vehicles=(all||[]).filter(busPassesFilter);
    const onTime=vehicles.filter(v=>v.schedule_status==='ON_TIME'||v.schedule_status==='EARLY').length;
    const late=vehicles.filter(v=>v.schedule_status==='SLIGHTLY_LATE'||v.schedule_status==='SIGNIFICANTLY_LATE').length;
    // Analytics
    const totalFleet = fleetSize
    const punctuality = vehicles.length
        ? Math.round((onTime / vehicles.length) * 100)
        : 0;

    const totalPassengers = vehicles.reduce((sum,v)=>
        sum + (v.estimated_passengers || 0),0);

    const totalKm = vehicles.reduce((sum,v)=>
        sum + ((v.speed_kmh || 0) * 0.25),0);

// Update analytics UI
    document.getElementById('anActiveRatio').textContent =
        `${vehicles.length}/${totalFleet}`;

    document.getElementById('anPassengers').textContent =
        totalPassengers;
    const sActive=document.getElementById('sActive');
    const sOnTime=document.getElementById('sOnTime');
    const sLate=document.getElementById('sLate');

    if(sActive) sActive.textContent=vehicles.length||'0';
    if(sOnTime) sOnTime.textContent=onTime;
    if(sLate) sLate.textContent=late;

    const list=document.getElementById('vehicle-list');
    // Focus mode: when a bus is selected, the side panel shows ONLY that bus.
    if(selectedVeh && vehicleData[selectedVeh]){
        list.innerHTML='';
        list.appendChild(buildBusDetail(vehicleData[selectedVeh]));
        return;
    }
    if(vehicles.length===0){list.innerHTML=`<div class="empty"><div class="empty-icon">🚌</div><p>No active buses.<br>Start the GPS simulator.</p></div>`;return;}
    list.innerHTML='';
    vehicles.forEach(v=>{
        // CSP FIX (A05): st kept exactly as before (no ||SC.UNKNOWN coercion here
        // — an unrecognized status falls through to "no matching CSS rule", the
        // same no-op visual result as the original "background:undefined").
        const st=!v.trip_id?'NO_TRIP':(v.schedule_status||'UNKNOWN'),l=SL[st]||SL.UNKNOWN;
        const spd=v.speed_kmh?v.speed_kmh.toFixed(1):'0.0';
        const pax=v.estimated_passengers?'~'+v.estimated_passengers:'—';
        const cp=crowdPct(v),cc=crowdColor(v.crowding_level);
        const rc=routeColor(v.route_id);
        const hidden=isBusHidden(v.vehicle_id);
        const d=document.createElement('div');
        d.className='vcard'+(selectedVeh===v.vehicle_id?' selected':'')+(hidden?' hidden-bus':'');
        d.innerHTML=`<div class="vcard-stripe" data-status="${st}"></div><div class="vcard-top"><div class="vcard-id" data-fg="${rc}">${escHtml(v.vehicle_id)}</div><div class="vcard-actions"><div class="chip" data-status="${st}">${l}</div><button type="button" class="vis-toggle" data-hidden="${hidden}" aria-label="Toggle map visibility" title="${hidden?'Hidden from map — click to show':'Visible on map — click to hide'}">${hidden?'🚫':'👁'}</button></div></div><div class="vcard-grid"><div class="vitem"><div class="vitem-lbl">Speed</div><div class="vitem-val">${spd} km/h</div></div><div class="vitem"><div class="vitem-lbl">Passengers</div><div class="vitem-val">${pax}</div></div><div class="vitem"><div class="vitem-lbl">Crowding</div><div class="vitem-val">${escHtml(v.crowding_level)||'—'}</div></div><div class="vitem"><div class="vitem-lbl">Route</div><div class="vitem-val" data-fg="${rc}">${escHtml(v.route_name)||'—'}</div></div></div><div class="vadh" data-status="${st}">${adherenceText(v)}</div><div class="cbar"><div class="cbar-fill" data-bg="${cc}" data-width-pct="${cp}"></div></div>`;
        // d is a detached element (not yet appended), so this is synchronous
        // and safe to call right after setting innerHTML.
        applyDynStyles(d);
        d.addEventListener('click',()=>selV(v.vehicle_id));
        // the visibility toggle must NOT select the bus → stop the bubble
        const vt=d.querySelector('.vis-toggle');
        if(vt) vt.addEventListener('click',ev=>{ev.stopPropagation();toggleBusVisibility(v.vehicle_id);});
        list.appendChild(d);
    });
}

// Detail panel shown in the side tab when a single bus is selected.
function buildBusDetail(v){
    const st  = !v.trip_id ? 'NO_TRIP' : (SC[v.schedule_status] ? v.schedule_status : 'UNKNOWN');
    const l   = SL[v.schedule_status]||SL.UNKNOWN;
    const rc  = routeColor(v.route_id);
    const cp  = crowdPct(v.crowding_level), cc = cp>70?'#EF4444':cp>40?'#F59E0B':'#22C55E';
    const hidden   = isBusHidden(v.vehicle_id);
    const delayTxt = v.delay_minutes>0 ? `+${v.delay_minutes}m` : 'In orario';
    const eta = v.eta_seconds ? Math.round(v.eta_seconds/60)+' min' : '—';
    const el  = document.createElement('div');
    el.className='bus-detail';
    el.innerHTML=`
        <div class="bd-back" id="bdBack">← Back to fleet</div>
        <div class="bd-card">
            <div class="bd-head">
                <div class="bd-id" data-fg="${rc}">🚌 ${escHtml(v.vehicle_id)}</div>
                <div class="chip" data-status="${st}">${l}</div>
            </div>
            <div class="bd-grid">
                <div class="vitem bd-span2"><div class="vitem-lbl">Route</div><div class="vitem-val" data-fg="${rc}">${escHtml(v.route_name)||'—'}</div></div>
                <div class="vitem"><div class="vitem-lbl">Speed</div><div class="vitem-val">${v.speed_kmh?v.speed_kmh.toFixed(1)+' km/h':'—'}</div></div>
                <div class="vitem"><div class="vitem-lbl">Delay</div><div class="vitem-val vpop-delay" data-status="${st}">${delayTxt}</div></div>
                <div class="vitem"><div class="vitem-lbl">Passengers</div><div class="vitem-val">${v.estimated_passengers?'~'+v.estimated_passengers:'—'}</div></div>
                <div class="vitem"><div class="vitem-lbl">Crowding</div><div class="vitem-val">${escHtml(v.crowding_level)||'—'}</div></div>
                <div class="vitem"><div class="vitem-lbl">ETA next stop</div><div class="vitem-val">${eta}</div></div>
                <div class="vitem"><div class="vitem-lbl">Seats</div><div class="vitem-val">${v.numero_posti||'—'}</div></div>
                <!-- Field names as the DTO publishes them today: last_stop_name is
                     the stop just called at, next_stop_name the one ahead. This card
                     still used the pre-merge names (upcoming_stop_name no longer
                     exists), which is why it showed the next stop under "Last stop"
                     and a dash under "Next stop" while the map popup was right. -->
                <div class="vitem bd-span2"><div class="vitem-lbl">Last stop</div><div class="vitem-val">${escHtml(v.last_stop_name)||'—'}</div></div>
                <div class="vitem bd-span2"><div class="vitem-lbl">Next stop</div><div class="vitem-val">${escHtml(v.next_stop_name)||'—'}</div></div>
                <div class="vitem"><div class="vitem-lbl">Wheelchair</div><div class="vitem-val">${v.wheelchair_accessible?'♿ Yes':'—'}</div></div>
                <div class="vitem"><div class="vitem-lbl">Position</div><div class="vitem-val">${v.lat!=null?v.lat.toFixed(4):'—'}, ${v.lon!=null?v.lon.toFixed(4):'—'}</div></div>
            </div>
            <div class="cbar"><div class="cbar-fill" data-bg="${cc}" data-width-pct="${cp}"></div></div>
            <button type="button" class="bd-visbtn" id="bdVis">${hidden?'👁 Show on map':'🚫 Hide from map'}</button>
        </div>`;
    applyDynStyles(el);
    el.querySelector('#bdBack').addEventListener('click', ()=>selV(v.vehicle_id));       // same id → deselect
    el.querySelector('#bdVis').addEventListener('click', ()=>toggleBusVisibility(v.vehicle_id));
    return el;
}

function selV(id){
    if(selectedVeh===id){            // click the same bus again → deselect, back to full fleet list
        selectedVeh=null;
        // Close the popup too, so every route back to the full fleet — the
        // card's ×, "← Back to fleet", or re-clicking the bus — leaves the
        // map in the same state. Re-entrant safe: this fires popupclose,
        // whose handler sees selectedVeh is already null and does nothing.
        markers[id]?.closePopup();
    }else{
        selectedVeh=id;const v=vehicleData[id];
        if(v && !isBusHidden(id)){map.flyTo([v.lat,v.lon],16,{duration:1});markers[id]?.openPopup();}
    }
    updateRouteVisibility();
    updateFleet(Object.values(vehicleData));
}



// Helpers
function crowdPct(v){return typeof v.occupancy_pct === 'number' ? v.occupancy_pct : 0;}
function crowdColor(l){return {LOW:'#22C55E',MEDIUM:'#F59E0B',HIGH:'#EF4444',VERY_HIGH:'#EF4444'}[l]||'#4B5563';}
// Every caller of this paints TEXT on a dark panel, so it hands back the ink
// variant rather than the raw line colour. The polylines take routeColors
// directly and stay exactly the colour CassiTrack stores.
function routeColor(routeId){
    return routeInk(routeColors[routeId] || '#4B5563');
}

// ── Line colour → readable ink on the dark UI ─────────────────────
// The palette is tuned for lines drawn ON a map, where a 4px stroke at 2:1
// against the tiles reads fine. The same colour as 9px bold text on #0F1623
// does not: the darker lines (plum, olive, navy) come out unreadable. So for
// text the colour is lifted towards white just far enough to clear 4.5:1, which
// keeps the hue recognisable instead of falling back to a flat grey.
const PANEL_RGB = [0x0F, 0x16, 0x23];
const _inkCache = {};

function _relLum(rgb){
    const f = v => { v /= 255; return v <= 0.04045 ? v/12.92 : Math.pow((v+0.055)/1.055, 2.4); };
    return 0.2126*f(rgb[0]) + 0.7152*f(rgb[1]) + 0.0722*f(rgb[2]);
}
function _contrast(a, b){
    const la = _relLum(a), lb = _relLum(b);
    return (Math.max(la,lb) + 0.05) / (Math.min(la,lb) + 0.05);
}
function routeInk(color){
    if(_inkCache[color]) return _inkCache[color];
    const m = /^#([0-9a-f]{6})$/i.exec(String(color).trim());
    // hsl() fallbacks and anything unparsed are already light by construction
    if(!m) return _inkCache[color] = color;
    const n = parseInt(m[1], 16);
    const base = [(n>>16)&255, (n>>8)&255, n&255];
    let rgb = base, t = 0;
    while(_contrast(rgb, PANEL_RGB) < 4.5 && t < 0.85){
        t += 0.05;
        rgb = base.map(v => Math.round(v + (255 - v) * t));
    }
    return _inkCache[color] = '#' + rgb.map(v => v.toString(16).padStart(2,'0')).join('');
}
// Show on the map ONLY the route of the currently selected bus.
// With no bus selected, every route is shown (default behaviour).
function updateRouteVisibility(){
    // Which routes to draw:
    //  · a bus is selected  → only that bus's route
    //  · otherwise          → every route that has ≥1 VISIBLE (non-hidden) active bus
    // Routes with no bus on them — and routes whose only buses are toggled off —
    // are hidden, together with their stops.
    let showSet;
    // `explicit` = the user narrowed the view deliberately (picked a bus, or
    // set the route filter). Only then do we hide the other lines.
    let explicit = false;
    if(selectedVeh && vehicleData[selectedVeh]){
        const r = vehicleData[selectedVeh].route_id;
        showSet = new Set(r ? [r] : []);
        explicit = true;
    } else if(routeFilter){
        showSet = new Set([routeFilter]);
        explicit = true;
    } else {
        showSet = new Set();
        lastVehicles.forEach(v=>{ if(v.route_id && !isBusHidden(v.vehicle_id) && busPassesFilter(v)) showSet.add(v.route_id); });
    }
    // Route polylines.
    //
    // A route's geometry is permanent infrastructure: the road does not stop
    // existing because no bus happens to be on it right now. Hiding a line
    // whenever its buses were between scheduled runs made lines vanish for
    // long stretches (LINEA_2 sat idle most of the day before V13) and read
    // as a rendering bug rather than as "no service at this hour".
    //
    // So with nothing selected, every route stays drawn — dimmed when it has
    // no active bus, full strength when it does. A deliberate selection still
    // isolates one line, which is the point of selecting.
    Object.entries(routePolylines).forEach(([rid, pl])=>{
        const active = showSet.has(rid);
        const hit    = routeHitAreas[rid];
        // The invisible click target follows its line exactly — otherwise a
        // hidden route would still be clickable.
        if((explicit || idleRoutesMode === 'hide') && !active){
            if(map.hasLayer(pl)) map.removeLayer(pl);
            if(hit && map.hasLayer(hit)) map.removeLayer(hit);
            return;
        }
        if(!map.hasLayer(pl)) pl.addTo(map);
        if(hit && !map.hasLayer(hit)) hit.addTo(map);
        // The route being filtered on is drawn thicker, so the map shows
        // which line the sidebar list has been narrowed to.
        const selected = routeFilter === rid;
        pl.setStyle({opacity: active ? .8 : .25, weight: selected ? 7 : 4});
        // NOTE: deliberately no hit.bringToFront() here. Raising the click
        // targets put them above the stop circles — which are SVG paths in
        // the same pane — and made the stops unclickable. The stops are
        // raised instead, just below.
    });
    // Stops follow their route: hidden only when a deliberate selection has
    // filtered that route away. Otherwise they stay on the map alongside the
    // dimmed lines, so the network is always legible.
    Object.values(stopMap).forEach(st=>{
        if(!st.marker) return;
        const show = (!explicit && idleRoutesMode !== 'hide')
            || (st.routeIds && [...st.routeIds].some(rid=>showSet.has(rid)));
        if(show){
            if(!map.hasLayer(st.marker)) st.marker.addTo(map);
            // Stops must sit ABOVE the routes' invisible click targets.
            // A circleMarker is an SVG path, so it shares the overlay pane
            // with the polylines — without this the 18px hit area laid over
            // each line swallows the click and stops become unclickable.
            // Done after the polyline loop so the ordering is final: small
            // precise targets win over the broad line ones.
            st.marker.bringToFront();
        }
        else if(map.hasLayer(st.marker)) map.removeLayer(st.marker);
    });
}
// Re-evaluate every surface after any filter change (markers, routes, sidebar).
function refreshFilters(){
    // if the focused bus no longer passes the active filters, drop focus
    if(selectedVeh && vehicleData[selectedVeh] && !busPassesFilter(vehicleData[selectedVeh]))
        selectedVeh = null;
    Object.keys(markers).forEach(applyBusVisibility);   // re-evaluate every marker
    updateRouteVisibility();
    updateFleet(Object.values(vehicleData));
}
function setRouteFilter(rid){ routeFilter = rid || null; refreshFilters(); }

/**
 * Filter the fleet to one route, or clear it when that route is already
 * the active filter — so clicking the same line twice returns to "All".
 *
 * Keeps the sidebar dropdown in step: the map and the select are two ways
 * of setting the SAME filter, and they must never disagree. Also clears any
 * selected bus, because a bus selection overrides the route filter in
 * updateRouteVisibility() and would otherwise mask the click.
 */
function toggleRouteFilter(rid){
    const next = (routeFilter === rid) ? '' : rid;
    selectedVeh = null;
    const sel = document.getElementById('routeFilter');
    if(sel) sel.value = next;
    // setRouteFilter -> refreshFilters -> updateFleet already redraws the
    // sidebar list and the markers, so nothing else is needed here.
    setRouteFilter(next);
}
function setServiceFilter(val){ serviceFilter = val || 'all'; refreshFilters(); }
function setDelayThreshold(min){ delayThreshold = Math.max(0, parseInt(min, 10) || 0); refreshFilters(); }

function adherenceText(v){
    if(!v.trip_id) return 'Not in service';
    if(typeof v.delay_minutes !== 'number' || !v.delay_stop_name)
        return 'Awaiting first arrival';

    const stop = escHtml(v.delay_stop_name);
    const m    = v.delay_minutes;

    switch(v.schedule_status){
        case 'EARLY':
            return `${Math.abs(m)} min early at ${stop}`;
        case 'SLIGHTLY_LATE':
        case 'SIGNIFICANTLY_LATE':
            return `${m} min late at ${stop}`;
        case 'ON_TIME':
            return m === 0 ? `On time at ${stop}`
                : `On time at ${stop} (${m > 0 ? '+' : ''}${m} min)`;
        default:
            return 'Adherence unavailable';
    }
}

function chartColor(key, i){
    return routeInk(routeColors[key] || `hsl(${(i * 137.5) % 360}, 70%, 55%)`);
}
function fmtT(iso){if(!iso)return'—';return new Date(iso).toLocaleTimeString('it-IT',{hour:'2-digit',minute:'2-digit'});}
function fmtDist(m){if(!m)return'—';return m<1000?(Math.round(m)+'m'):(m/1000).toFixed(1)+'km';}
// ─────────────────────────────────────────────
// TOP NAVIGATION
// ─────────────────────────────────────────────

function switchTopView(viewId, btn){

    document.querySelectorAll('.main-view')
        .forEach(v=>v.classList.remove('active-view'));

    document.getElementById(viewId)
        .classList.add('active-view');

    document.querySelectorAll('.top-btn')
        .forEach(b=>b.classList.remove('active'));

    btn.classList.add('active');

    // Important for Leaflet map resize
    if(viewId === 'fleet-monitor'){
        setTimeout(()=>map.invalidateSize(),200);
    }
    // Navigating away from Data Management leaves the Active Trips panel
    // marked active but invisible — keep its 30 s poll from running on a
    // screen nobody is looking at.
    if(viewId !== 'data-management'){ tripsStopAutoRefresh(); tripHideDrawer(); }
}

// ── Data Management sub-navigation (Buses / Stops / …) ────────────────────
function switchDmPanel(panelId, btn){
    // Scoped to #data-management: the old #data-management-view was merged away.
    document.querySelectorAll('#data-management .dm-panel').forEach(p=>p.classList.remove('active'));
    const panel = document.getElementById(panelId);
    if(panel) panel.classList.add('active');
    document.querySelectorAll('.dm-subtab').forEach(b=>b.classList.remove('active'));
    if(btn) btn.classList.add('active');
    // Active Trips polls every 30 s, so the timer follows the panel: leaving
    // the tab must stop it, or every visit would stack another interval.
    // An open edit drawer belongs to that panel too.
    tripsStopAutoRefresh();
    tripHideDrawer();

    if(panelId === 'dm-panel-stops') loadStops();
    else if(panelId === 'dm-panel-routes') loadRoutesAdmin();
    // Both panels exist: Timetable edits the runs, Trips watches them.
    else if(panelId === 'dm-panel-trips'){
        tripsLoad();               // route names come with the trip rows
        tripsStartAutoRefresh();
    }
        // Buses now render through the US-01 registry UI (dmLoadBuses), not the
    // superseded bm* table. dmLoadRoutes() fills the route filter/assign lists.
    else if(panelId === 'dm-panel-buses'){
        if(!dmState.routes.length) dmLoadRoutes();
        dmLoadBuses();
    }
}

// ── Data Management: stops CRUD (Postgres) ────────────────────────────────
let stStops = [];
let stEditId = null;   // null = adding a new stop, otherwise the stop id being edited
let stSearch = '';     // free-text query (id / name / description)
let stActiveOnly = ''; // '' = any, 'true' = active only, 'false' = inactive only

async function loadStops(){
    const body = document.getElementById('stTableBody');
    if(body) body.innerHTML = `<tr><td colspan="6" class="bm-empty">Loading…</td></tr>`;
    try{
        const r = await fetch(`${API}/stops`, {headers:{'Accept':'application/json'}});
        if(!r.ok) throw new Error(r.status);
        stStops = await r.json();
        renderStopsAdmin();
    }catch(e){
        if(body) body.innerHTML = `<tr><td colspan="6" class="bm-empty">Failed to load stops.</td></tr>`;
    }
}

// Search + state filter, applied in AND over the in-memory list.
function stFiltered(){
    const q = stSearch.trim().toLowerCase();
    return stStops.filter(s=>{
        if(stActiveOnly !== '' && String(s.active !== false) !== stActiveOnly) return false;
        if(!q) return true;
        return [s.id, s.name, s.description]
            .some(f => String(f ?? '').toLowerCase().includes(q));
    });
}

function renderStopsAdmin(){
    const body = document.getElementById('stTableBody');
    if(!body) return;
    const rows = stFiltered();
    if(!stStops.length){ body.innerHTML = `<tr><td colspan="6" class="bm-empty">No stops yet — use “＋ Add stop”.</td></tr>`; return; }
    if(!rows.length){ body.innerHTML = `<tr><td colspan="6" class="bm-empty">No stops match your search.</td></tr>`; return; }
    body.innerHTML = '';
    rows.slice().sort((a,b)=>String(a.id).localeCompare(String(b.id))).forEach(s=>{
        const tr = document.createElement('tr');
        tr.innerHTML = `
                <td class="bm-mono">${escHtml(s.id)}</td>
                <td>${escHtml(s.name)||'—'}</td>
                <td class="bm-mono">${s.lat != null ? Number(s.lat).toFixed(5) : '—'}</td>
                <td class="bm-mono">${s.lon != null ? Number(s.lon).toFixed(5) : '—'}</td>
                <td>${s.active ? '<span class="bm-badge on">Active</span>' : '<span class="bm-badge off">Inactive</span>'}</td>
                <td class="bm-actions-col">
                    <button type="button" class="bm-row-btn" data-act="edit" data-id="${escHtml(s.id)}">Edit</button>
                    <button type="button" class="bm-row-btn danger" data-act="del" data-id="${escHtml(s.id)}">Delete</button>
                </td>`;
        body.appendChild(tr);
    });
}

function setStMsg(txt, ok){
    const el = document.getElementById('stFormMsg');
    if(!el) return;
    el.textContent = txt || '';
    el.classList.toggle('err', !!txt && !ok);
    el.classList.toggle('ok',  !!txt && !!ok);
}

function openStopForm(stop){
    stEditId = stop ? stop.id : null;
    document.getElementById('stFormTitle').textContent = stop ? `Edit stop ${stop.id}` : 'New stop';
    const idEl = document.getElementById('stId');
    idEl.value = stop ? stop.id : '';
    idEl.disabled = !!stop;   // id is the primary key → not editable on update
    document.getElementById('stName').value  = stop ? (stop.name || '') : '';
    document.getElementById('stLat').value    = stop && stop.lat != null ? stop.lat : '';
    document.getElementById('stLon').value    = stop && stop.lon != null ? stop.lon : '';
    document.getElementById('stActive').value = stop ? (stop.active === false ? 'false' : 'true') : 'true';
    document.getElementById('stDesc').value   = stop ? (stop.description || '') : '';
    setStMsg('');
    document.getElementById('stForm').hidden = false;
    (stop ? document.getElementById('stName') : idEl).focus();
}

function closeStopForm(){
    document.getElementById('stForm').hidden = true;
    stEditId = null;
    setStMsg('');
}

async function saveStop(){
    const payload = {
        id:          document.getElementById('stId').value,
        name:        document.getElementById('stName').value,
        lat:         parseFloat(document.getElementById('stLat').value),
        lon:         parseFloat(document.getElementById('stLon').value),
        description: document.getElementById('stDesc').value,
        active:      document.getElementById('stActive').value === 'true'
    };
    if(!payload.id || !payload.id.trim()){ setStMsg('Stop id is required.'); return; }
    if(!payload.name || !payload.name.trim()){ setStMsg('Name is required.'); return; }
    if(Number.isNaN(payload.lat) || Number.isNaN(payload.lon)){ setStMsg('Latitude and longitude are required.'); return; }

    const editing = stEditId != null;
    const url    = editing ? `${API}/stops/${encodeURIComponent(stEditId)}` : `${API}/stops`;
    const method = editing ? 'PUT' : 'POST';
    setStMsg('Saving…', true);
    try{
        const r = await fetch(url, {
            method,
            headers:{'Content-Type':'application/json'},
            body: JSON.stringify(payload)
        });
        if(r.ok){ closeStopForm(); await loadStops(); }
        else{
            let msg = 'Save failed.';
            try{ const j = await r.json(); if(j && j.error) msg = j.error; }catch(_){}
            setStMsg(msg);
        }
    }catch(e){ setStMsg('Network error while saving.'); }
}

async function deleteStop(id){
    const stop = stStops.find(s=>s.id===id);
    if(!window.confirm(`Delete stop ${stop ? stop.id : id}? This cannot be undone.`)) return;
    try{
        const r = await fetch(`${API}/stops/${encodeURIComponent(id)}`, {method:'DELETE'});
        if(r.ok){ await loadStops(); }
        else{
            let msg = 'Delete failed.';
            try{ const j = await r.json(); if(j && j.error) msg = j.error; }catch(_){}
            window.alert(msg);
        }
    }catch(e){ window.alert('Network error while deleting.'); }
}

// ── Data Management: routes CRUD (Postgres) ───────────────────────────────
let rtRoutes = [];
let rtEditId = null;   // null = adding a new route, otherwise the route id being edited
let rtSearch = '';     // free-text query (id / short name / long name)
let rtActiveOnly = ''; // '' = any, 'true' = active only, 'false' = inactive only

// NOTE: named *Admin since the Fleet Monitor keeps its own route state; this
// one owns the Data Management CRUD list.
// defined further down (a duplicate `function` name silently overwrites the
// earlier one, which is what broke this tab after the merge).
async function loadRoutesAdmin(){
    const body = document.getElementById('rtTableBody');
    if(body) body.innerHTML = `<tr><td colspan="6" class="bm-empty">Loading…</td></tr>`;
    try{
        const r = await fetch(`${API}/routes/manage`, {headers:{'Accept':'application/json'}});
        if(!r.ok) throw new Error(r.status);
        rtRoutes = await r.json();
        renderRoutesAdmin();
    }catch(e){
        if(body) body.innerHTML = `<tr><td colspan="6" class="bm-empty">Failed to load routes.</td></tr>`;
    }
}

// Search + state filter, applied in AND over the in-memory list.
function rtFiltered(){
    const q = rtSearch.trim().toLowerCase();
    return rtRoutes.filter(rt=>{
        if(rtActiveOnly !== '' && String(rt.active !== false) !== rtActiveOnly) return false;
        if(!q) return true;
        return [rt.id, rt.shortName, rt.longName]
            .some(f => String(f ?? '').toLowerCase().includes(q));
    });
}

function renderRoutesAdmin(){
    const body = document.getElementById('rtTableBody');
    if(!body) return;
    const rows = rtFiltered();
    if(!rtRoutes.length){ body.innerHTML = `<tr><td colspan="6" class="bm-empty">No routes yet — use “＋ Add route”.</td></tr>`; return; }
    if(!rows.length){ body.innerHTML = `<tr><td colspan="6" class="bm-empty">No routes match your search.</td></tr>`; return; }
    body.innerHTML = '';
    rows.slice().sort((a,b)=>String(a.id).localeCompare(String(b.id))).forEach(rt=>{
        const hex = rt.color ? ('#'+rt.color) : null;
        const tr = document.createElement('tr');
        tr.innerHTML = `
                <td class="bm-mono">${escHtml(rt.id)}</td>
                <td>${escHtml(rt.shortName)||'—'}</td>
                <td>${escHtml(rt.longName)||'—'}</td>
                <td>${hex ? `<span class="rt-swatch" data-bg="${escHtml(hex)}"></span><span class="bm-mono">${escHtml(rt.color)}</span>` : '—'}</td>
                <td>${rt.active ? '<span class="bm-badge on">Active</span>' : '<span class="bm-badge off">Inactive</span>'}</td>
                <td class="bm-actions-col">
                    <button type="button" class="bm-row-btn" data-act="edit" data-id="${escHtml(rt.id)}">Edit</button>
                    <button type="button" class="bm-row-btn danger" data-act="del" data-id="${escHtml(rt.id)}">Delete</button>
                </td>`;
        body.appendChild(tr);
    });
    applyDynStyles(body);   // colour swatches via data-bg (CSP-safe)
}

/** Bus dropdown for the line's first journey (trips.bus_id is NOT NULL). */

    // -- Route map editor -------------------------------------------------------
    // Draws the road geometry stored in route_shapes. Same interaction as the
    // standalone tools/crea_path.html that produced the current shapes:
    // left click = plain vertex, right click = vertex that is also a stop.
    // A stop snaps to the nearest existing stop within SNAP_M, so drawing over a
    // known stop reuses it instead of creating a duplicate.
    let rtMap = null;          // Leaflet instance of the editor
    let rtDrawPts = [];        // [{lat, lon, isStop, stopId, stopName, marker}]
    let rtDrawLine = null;     // preview polyline
    let rtHitLine  = null;     // copia invisibile e spessa: bersaglio dei click di inserimento
    let rtAllStops = [];       // existing stops, used for snapping
    const SNAP_M = 80;         // metres: within this, reuse the existing stop

    function metresBetween(aLat, aLon, bLat, bLon){
        const R = 6371000, toRad = d => d * Math.PI / 180;
        const dLat = toRad(bLat - aLat), dLon = toRad(bLon - aLon);
        const s = Math.sin(dLat/2)**2 +
                  Math.cos(toRad(aLat)) * Math.cos(toRad(bLat)) * Math.sin(dLon/2)**2;
        return R * 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1-s));
    }

    function nearestStop(lat, lon){
        let best = null, bestD = Infinity;
        rtAllStops.forEach(s => {
            if(s.lat == null || s.lon == null) return;
            const d = metresBetween(lat, lon, s.lat, s.lon);
            if(d < bestD){ bestD = d; best = s; }
        });
        return bestD <= SNAP_M ? best : null;
    }

    async function rtOpenMapEditor(){
        const wrap = document.getElementById('rtDrawWrap');
        if(!wrap) return;
        wrap.hidden = false;

        if(!rtAllStops.length){
            try{
                const r = await fetch(`${API}/stops`, {headers:{'Accept':'application/json'}});
                if(r.ok) rtAllStops = await r.json();
            }catch(e){ /* snapping just won't find anything */ }
        }

        if(!rtMap){
            // Wheel zoom is enabled only while the pointer is over the map:
            // hovering it you zoom as usual, moving off it the wheel scrolls
            // the form again (the map lives inside a scrollable form, and a
            // map that always grabs the wheel would trap the page).
            rtMap = L.map('rtMap', {center:[41.4901, 13.8265], zoom:15,
                                    doubleClickZoom:false, scrollWheelZoom:false});
            rtMap.on('mouseover', () => rtMap.scrollWheelZoom.enable());
            rtMap.on('mouseout',  () => rtMap.scrollWheelZoom.disable());
            // Same basemap as the main map — see the note there on why not CARTO
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
                        {attribution:'(c) OpenStreetMap contributors', maxZoom:19}).addTo(rtMap);

            // Existing stops shown faintly, as a drawing reference.
            rtAllStops.forEach(s => {
                if(s.lat == null || s.lon == null) return;
                L.circleMarker([s.lat, s.lon],
                    {radius:4, color:'#4B5563', weight:1, fillOpacity:.5})
                 .bindTooltip(s.name || s.id).addTo(rtMap);
            });

            rtMap.on('click',       e => rtAddVertex(e.latlng, false));
            rtMap.on('contextmenu', e => {
                if(e.originalEvent) L.DomEvent.preventDefault(e.originalEvent);
                rtAddVertex(e.latlng, true);
            });
        }
        // Re-measure once the container is actually laid out. A single fixed
        // timeout is fragile (fonts, images and the parent form settling can
        // push layout past it), so retry briefly until the map reports a real
        // width — this is what stops the map rendering as a corner sliver.
        let tries = 0;
        const settle = () => {
            rtMap.invalidateSize();
            const w = rtMap.getContainer().clientWidth;
            if(w < 50 && ++tries < 10){ setTimeout(settle, 80); return; }
            wrap.scrollIntoView({behavior:'smooth', block:'nearest'});
        };
        requestAnimationFrame(() => setTimeout(settle, 30));
    }

    /**
     * Disegna un vertice e ne collega click, tasto destro e trascinamento.
     *
     * Usa L.marker con una divIcon invece di L.circleMarker, che era la scelta
     * precedente: circleMarker non e' trascinabile, e senza trascinamento
     * inserire un punto sulla linea non serve a niente — il punto nasce
     * esattamente SOPRA la linea, quindi la geometria non cambia di un
     * millimetro finche' non lo si sposta.
     *
     * Le fermate NON sono trascinabili. Le loro coordinate non sono geometria
     * libera: sono quelle della fermata nel catalogo, e il simulatore lega
     * ogni arrivo al vertice confrontando le coordinate entro un metro.
     * Trascinarle romperebbe quel legame in silenzio, e la corsa smetterebbe
     * di essere simulata senza un errore visibile. Per cambiare una fermata si
     * usa il tasto destro (che la riporta a punto) e se ne marca un'altra.
     */
    function rtAttachMarker(pt){
        const cls = pt.isStop ? 'rt-vtx rt-vtx-stop' : 'rt-vtx rt-vtx-plain';
        const size = pt.isStop ? 16 : 11;
        pt.marker = L.marker([pt.lat, pt.lon], {
            draggable: !pt.isStop,
            keyboard: false,
            icon: L.divIcon({className:'', html:'<span class="'+cls+'"></span>',
                             iconSize:[size,size], iconAnchor:[size/2, size/2]})
        }).addTo(rtMap);

        if(!pt.isStop){
            // Durante il trascinamento si aggiornano solo le due polilinee, non
            // l'intero disegno: rtRedraw ricostruisce anche l'elenco fermate
            // sotto la mappa, e rifarlo a ogni pixel di movimento fa scattare
            // il puntatore.
            pt.marker.on('drag', e => {
                const ll = e.target.getLatLng();
                pt.lat = +ll.lat.toFixed(6);
                pt.lon = +ll.lng.toFixed(6);
                rtUpdateLines();
            });
            pt.marker.on('dragend', () => {
                // Leaflet emette un click subito dopo il rilascio: senza questa
                // guardia, trascinare un vertice lo cancellerebbe.
                pt._justDragged = true;
                setTimeout(() => { pt._justDragged = false; }, 0);
                rtRedraw();
            });
        }
        pt.marker.bindTooltip(pt.stopName || (pt.isStop ? (pt.stopId || 'stop') : 'point'));
        pt.marker.on('click', ev => {
            L.DomEvent.stopPropagation(ev);
            if(pt._justDragged) return;          // era la fine di un trascinamento
            const i = rtDrawPts.indexOf(pt);
            if(i >= 0) rtRemoveVertex(i);
        });
        // Tasto destro su un vertice: lo trasforma da punto a fermata e
        // viceversa, SENZA toccare la geometria.
        //
        // Prima l'unica azione su un vertice era cancellarlo, e i vertici si
        // aggiungono solo in coda: cambiare una fermata a meta' percorso
        // significava smontare tutto quello che veniva dopo. Il gesto e' lo
        // stesso della mappa vuota — destro = fermata — quindi non c'e' una
        // regola nuova da imparare: destro riguarda l'essere fermata, sinistro
        // l'esistere.
        pt.marker.on('contextmenu', ev => {
            L.DomEvent.stopPropagation(ev);
            if(ev.originalEvent) L.DomEvent.preventDefault(ev.originalEvent);
            rtToggleStop(pt);
        });
    }

    /** Rende un vertice una fermata, o smette di considerarlo tale. */
    function rtToggleStop(pt){
        if(pt.isStop){
            pt.isStop = false;
            pt.stopId = null;
            pt.stopName = null;
        }else{
            const near = nearestStop(pt.lat, pt.lon);
            if(near){
                // Si aggancia alla fermata esistente e ne prende le coordinate
                // esatte: il simulatore lega ogni arrivo al vertice entro un
                // metro, quindi salvare il punto cliccato invece della fermata
                // vera romperebbe quel legame.
                pt.stopId = near.id; pt.stopName = near.name;
                pt.lat = near.lat;   pt.lon = near.lon;
            }else{
                const name = window.prompt('Name of the new stop here:');
                if(!name || !name.trim()) return;
                pt.stopId = null; pt.stopName = name.trim();
            }
            pt.isStop = true;
        }
        // Il marker cambia forma e colore: si ridisegna da zero.
        if(pt.marker) rtMap.removeLayer(pt.marker);
        rtAttachMarker(pt);
        rtRedraw();
    }

    function rtAddVertex(latlng, isStop){
        const lat = +latlng.lat.toFixed(6), lon = +latlng.lng.toFixed(6);
        const pt = {lat, lon, isStop, stopId:null, stopName:null};

        if(isStop){
            const near = nearestStop(lat, lon);
            if(near){
                pt.stopId = near.id;
                pt.stopName = near.name || near.id;
                // Snap to the stop's EXACT coordinates, not to where the click
                // landed. The scheduled simulator binds each scheduled stop to a
                // shape vertex by comparing coordinates within ~1 m (COORD_TOL),
                // and drops the whole trip when no vertex matches — a click even
                // a few metres off would silently stop that bus from running.
                pt.lat = near.lat;
                pt.lon = near.lon;
            }else{
                const name = window.prompt('Name of the new stop at this point:');
                if(!name || !name.trim()) return;      // cancelled -> no vertex added
                pt.stopName = name.trim();
            }
        }

        rtAttachMarker(pt);

        rtDrawPts.push(pt);
        rtRedraw();
    }

    /**
     * Inserisce un vertice semplice nel tratto cliccato.
     *
     * Serve a modificare un percorso senza smontarlo: prima i vertici si
     * potevano solo accodare, quindi correggere una curva a meta' strada
     * voleva dire cancellare tutto cio' che veniva dopo e ridisegnarlo.
     *
     * Il punto inserito e' un vertice puramente grafico. Se poi deve diventare
     * una fermata basta il tasto destro sopra, come su qualsiasi altro
     * vertice: le due azioni si compongono invece di duplicarsi.
     */
    function rtInsertOnSegment(latlng){
        if(rtDrawPts.length < 2) return;

        // Si lavora in pixel, non in gradi: a questa latitudine un grado di
        // longitudine vale circa tre quarti di uno di latitudine, quindi la
        // distanza "piu' vicina" calcolata sui gradi sceglierebbe il tratto
        // sbagliato sulle diagonali.
        const clicked = rtMap.latLngToLayerPoint(latlng);
        const pts = rtDrawPts.map(p => rtMap.latLngToLayerPoint(L.latLng(p.lat, p.lon)));

        let bestIdx = -1, bestD = Infinity, bestPoint = null;
        for(let i = 0; i + 1 < pts.length; i++){
            const a = pts[i], b = pts[i+1];
            const dx = b.x - a.x, dy = b.y - a.y;
            const len2 = dx*dx + dy*dy;
            // Segmento degenere (due vertici sovrapposti): niente su cui
            // proiettare, si salta invece di dividere per zero.
            if(len2 === 0) continue;
            let t = ((clicked.x - a.x) * dx + (clicked.y - a.y) * dy) / len2;
            t = Math.max(0, Math.min(1, t));
            const px = a.x + t*dx, py = a.y + t*dy;
            const d  = Math.hypot(clicked.x - px, clicked.y - py);
            if(d < bestD){ bestD = d; bestIdx = i; bestPoint = L.point(px, py); }
        }
        if(bestIdx < 0) return;

        // Si usa il punto PROIETTATO sul tratto, non dove ha colpito il click:
        // il vertice cade cosi' esattamente sulla linea, e trascinando il
        // percorso non compaiono microscopici zig-zag.
        const ll = rtMap.layerPointToLatLng(bestPoint);
        const pt = {lat:+ll.lat.toFixed(6), lon:+ll.lng.toFixed(6),
                    isStop:false, stopId:null, stopName:null};
        rtAttachMarker(pt);
        rtDrawPts.splice(bestIdx + 1, 0, pt);
        rtRedraw();
    }

    function rtRemoveVertex(i){
        const [pt] = rtDrawPts.splice(i, 1);
        if(pt && pt.marker) rtMap.removeLayer(pt.marker);
        rtRedraw();
    }

    function rtClearDrawing(){
        rtDrawPts.forEach(p => { if(p.marker) rtMap.removeLayer(p.marker); });
        rtDrawPts = [];
        rtRedraw();
    }

    /**
     * Aggiorna solo le due polilinee, senza ricostruire il resto.
     *
     * Chiamata a ogni pixel di trascinamento: rtRedraw rifa' anche l'elenco
     * delle fermate sotto la mappa, e farlo di continuo rende il trascinamento
     * a scatti.
     */
    function rtUpdateLines(){
        const ll = rtDrawPts.map(p => [p.lat, p.lon]);
        if(rtDrawLine) rtDrawLine.setLatLngs(ll);
        if(rtHitLine)  rtHitLine.setLatLngs(ll);
    }

    /** Refresh the preview line, the counter and the stop rows below the map. */
    function rtRedraw(){
        if(rtDrawLine){ rtMap.removeLayer(rtDrawLine); rtDrawLine = null; }
        if(rtHitLine){  rtMap.removeLayer(rtHitLine);  rtHitLine  = null; }
        if(rtDrawPts.length >= 2){
            // interactive:false + bringToBack: the preview line is redrawn
            // after the markers, so by default it sits on top of them and
            // swallows the clicks meant to delete a vertex.
            rtDrawLine = L.polyline(rtDrawPts.map(p=>[p.lat,p.lon]),
                                    {color:'#3B82F6', weight:4, opacity:.85,
                                     interactive:false}).addTo(rtMap);
            rtDrawLine.bringToBack();

            // Bersaglio dei click di inserimento: stessa geometria, invisibile
            // e molto piu' spessa, cosi' un tratto di quattro pixel diventa
            // comodo da colpire.
            //
            // E' una linea SEPARATA e non la stessa resa cliccabile, perche'
            // quella sopra deve restare interactive:false: viene ridisegnata
            // dopo i marker, e da cliccabile si ritroverebbe sopra di loro a
            // ingoiare i click destinati a cancellare un vertice. Questa nasce
            // gia' spedita in fondo, quindi i marker vincono sempre.
            rtHitLine = L.polyline(rtDrawPts.map(p=>[p.lat,p.lon]),
                                   {color:'#000', weight:16, opacity:0,
                                    interactive:true}).addTo(rtMap);
            rtHitLine.bringToBack();
            rtHitLine.on('click', ev => {
                L.DomEvent.stopPropagation(ev);
                rtInsertOnSegment(ev.latlng);
            });
        }
        const stops = rtDrawPts.filter(p=>p.isStop);
        const c = document.getElementById('rtDrawCount');
        if(c) c.textContent = rtDrawPts.length + ' point' + (rtDrawPts.length===1?'':'s')
                            + ' · ' + stops.length + ' stop' + (stops.length===1?'':'s');
        rtSyncStopRows(stops);
    }

    /**
     * Mirror the stops drawn on the map into the rows below, keeping any time
     * already typed. Those rows stay the only place where times are entered.
     */
    function rtSyncStopRows(stops){
        const list = document.getElementById('rtStops');
        if(!list) return;
        const times = Array.from(list.querySelectorAll('.tt-manual-row .tt-time')).map(i=>i.value);
        list.innerHTML = '';
        stops.forEach((s, i) => {
            const row = document.createElement('div');
            row.className = 'tt-stop-row tt-manual-row';
            row.dataset.fromMap  = '1';
            row.dataset.stopId   = s.stopId || '';
            row.dataset.stopName = s.stopName || '';
            row.dataset.lat      = s.lat;
            row.dataset.lon      = s.lon;
            const label = escHtml(s.stopName || s.stopId || '—');
            if(rtEditId){
                // MODIFICA: nessun orario da digitare. Una corsa ha i propri
                // orari e la cascata li ricalcola; qui si mostra lo scarto
                // proposto dalla linea, che dice la forma del percorso nel
                // tempo senza fingere di essere un campo compilabile.
                const ps  = rtPattern[i];
                const kept = ps && ps.stopId === s.stopId;
                row.innerHTML = '<span class="tt-seq">' + (i+1) + '</span>'
                    + '<span class="tt-stop-name">' + label + '</span>'
                    + (kept
                        ? '<span class="tt-offset">+' + rtFmtOffset(ps.offsetSeconds) + '</span>'
                        : '<span class="tt-offset tt-offset-new">new</span>');
            }else{
                const t = times[i] || (i === 0 ? '08:00' : '');
                row.innerHTML = '<span class="tt-seq">' + (i+1) + '</span>'
                    + '<span class="tt-stop-name">' + label + '</span>'
                    + '<input type="time" class="bm-input tt-time" value="' + escHtml(t) + '">';
            }
            list.appendChild(row);
        });
    }

async function rtLoadBusOptions(){
    const sel = document.getElementById('rtBus');
    if(!sel || sel.options.length) return;
    try{
        const r = await fetch(`${API}/buses`, {headers:{'Accept':'application/json'}});
        if(!r.ok) return;
        const buses = await r.json();
        sel.innerHTML = buses.map(b=>`<option value="${escHtml(b.busId)}">${escHtml(b.targa)}</option>`).join('');
    }catch(e){ /* leave empty */ }
}

function setRtMsg(txt, ok){
    const el = document.getElementById('rtFormMsg');
    if(!el) return;
    el.textContent = txt || '';
    el.classList.toggle('err', !!txt && !ok);
    el.classList.toggle('ok',  !!txt && !!ok);
}

// Percorso caricato nell'editor durante una modifica. Distingue "non ho
// toccato il percorso" (si salvano solo i campi) da "l'ho aperto e magari
// cambiato" (serve la cascata): senza questa distinzione, rinominare una linea
// ne riscriverebbe l'orario di tutte le corse per niente.
let rtPathLoaded = false;
// Pattern della linea come sta nel database, caricato insieme al percorso.
// Serve solo a MOSTRARE gli scarti: gli orari veri appartengono alle corse.
let rtPattern = [];

/** 156 -> "2:36", per gli scarti dalla partenza. */
function rtFmtOffset(sec){
    const s = Math.max(0, sec|0);
    return Math.floor(s/60) + ':' + String(s%60).padStart(2,'0');
}

function rtHidePreview(){
    const el = document.getElementById('rtPreview');
    if(el){ el.hidden = true; el.innerHTML = ''; }
}

/**
 * Carica nell'editor il percorso salvato della linea.
 *
 * I vertici che sono fermate arrivano con il loro stopId (lo aggiunge
 * getShape accoppiandoli al pattern): senza, risalvare farebbe sembrare ogni
 * fermata gia' esistente una fermata nuova da creare.
 */
async function rtLoadExistingPath(routeId){
    const r = await fetch(`${API}/routes/${encodeURIComponent(routeId)}/shape`,
                          {headers:{'Accept':'application/json'}});
    if(!r.ok) throw new Error('shape');
    const info = await r.json();

    await rtOpenMapEditor();
    rtClearDrawing();
    // Il pattern caricato serve a mostrare gli scarti accanto alle fermate.
    // Va tenuto DOPO rtOpenMapEditor, che popola rtAllStops: senza i nomi
    // l'elenco mostrerebbe gli id grezzi (GIA, EDN, CRS...), che non dicono
    // niente a chi guarda una mappa.
    rtPattern = info.stops || [];
    const nameById = {};
    rtAllStops.forEach(s => { nameById[s.id] = s.name; });
    rtPattern.forEach(ps => { if(ps.name) nameById[ps.stopId] = ps.name; });

    (info.path || []).forEach(v => {
        const pt = {lat:v.lat, lon:v.lon, isStop:!!v.isStop, stopId:v.stopId || null};
        if(pt.stopId && nameById[pt.stopId]) pt.stopName = nameById[pt.stopId];
        rtAttachMarker(pt);
        rtDrawPts.push(pt);
    });
    rtRedraw();
    if(rtDrawPts.length){
        rtMap.fitBounds(rtDrawPts.map(p=>[p.lat,p.lon]), {padding:[30,30]});
    }
    rtPathLoaded = true;
    return info;
}

/** Chiede al backend cosa cambierebbe, senza salvare. */
async function rtFetchPreview(routeId, path){
    const r = await fetch(`${API}/routes/${encodeURIComponent(routeId)}/shape/preview`, {
        method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify(path)
    });
    if(!r.ok){
        let msg = 'Preview failed.';
        try{ const j = await r.json(); if(j && j.error) msg = j.error; }catch(_){}
        throw new Error(msg);
    }
    return r.json();
}

/** Mostra il riepilogo dell'anteprima e restituisce il testo per la conferma. */
function rtShowPreview(pv){
    const el = document.getElementById('rtPreview');
    if(!el) return '';
    if(!pv.changed){
        el.hidden = false;
        el.innerHTML = '<div class="rt-preview-ok">The path was redrawn, but its stops are unchanged — no run will be re-timed.</div>';
        return '';
    }
    const li = (arr, cls) => arr.map(s => `<li class="${cls}">${escHtml(s)}</li>`).join('');
    el.hidden = false;
    el.innerHTML = `
        <div class="rt-preview-title">This will change ${pv.tripsAffected} run(s) of this line</div>
        <div class="rt-preview-grid">
            <div><span class="rt-preview-lbl">Stops</span> ${pv.stopsBefore} → ${pv.stopsAfter}</div>
            <div><span class="rt-preview-lbl">Buses re-anchored</span> ${pv.busesReanchored}</div>
        </div>
        ${pv.added.length   ? `<ul class="rt-preview-list">${li(pv.added,'rt-add')}</ul>`     : ''}
        ${pv.removed.length ? `<ul class="rt-preview-list">${li(pv.removed,'rt-del')}</ul>`   : ''}
        <div class="rt-preview-note">
            Stops that stay keep their exact times. Only the new ones get a time,
            interpolated inside each run's own schedule.
            ${pv.busesReanchored ? ' Buses currently on this line will show “LIVE” until they reach their next stop.' : ''}
        </div>`;
    const parts = [`${pv.tripsAffected} run(s) of this line will be re-timed.`];
    if(pv.added.length)   parts.push(`Added: ${pv.added.join(', ')}.`);
    if(pv.removed.length) parts.push(`Removed: ${pv.removed.join(', ')}.`);
    if(pv.busesReanchored) parts.push(`${pv.busesReanchored} bus(es) will lose their delay measurement until the next stop.`);
    return parts.join('\n') + '\n\nProceed?';
}

async function openRouteForm(route){
    rtEditId = route ? route.id : null;
    document.getElementById('rtFormTitle').textContent =
        route ? `Edit route ${route.id}` : 'New route + its first run';

    // The itinerary (map + stop/time rows) belongs to creation only: it defines
    // the line's path and its first run. Editing changes the line's own fields;
    // the path and the stops are not touched from here.
    const itn       = document.getElementById('rtItinerary');
    const busField  = document.getElementById('rtBusField');
    const stopsList = document.getElementById('rtStops');
    const pathEdit  = document.getElementById('rtPathEdit');
    if(itn)      itn.hidden      = !!route;
    if(busField) busField.hidden = !!route;

    // In modifica il percorso non e' piu' intoccabile: da V24 le fermate
    // appartengono alla linea e cambiarle si propaga alle corse. Resta pero'
    // dietro un pulsante, perche' la maggior parte delle modifiche a una linea
    // riguarda nome, colore o stato.
    if(pathEdit) pathEdit.hidden = !route;
    rtPathLoaded = false;
    rtHidePreview();

    // Il pulsante "Edit path" riadatta il pannello dell'itinerario alla
    // modifica (niente righe di orario, altra etichetta). Va rimesso a posto
    // qui, o riaprendo il form in CREAZIONE si troverebbe un pannello monco:
    // senza "+ Add stop" e con il titolo sbagliato.
    const addStopBtn = document.getElementById('rtAddStopBtn');
    if(addStopBtn) addStopBtn.hidden = !!route;
    const itnHead = document.querySelector('#rtItinerary .tt-manual-head .bm-lbl');
    if(itnHead) itnHead.textContent = route
            ? 'Path and stops of this line'
            : 'First run of this line — stops and times';
    const itnHint = document.querySelector('#rtItinerary .rt-itn-hint');
    if(itnHint) itnHint.hidden = !!route;
    const pathHint = document.getElementById('rtPathHint');
    if(pathHint) pathHint.textContent =
            'Changing the stops of this line re-times all of its runs. '
          + 'You will see exactly what changes before anything is saved.';

    if(!route){
        await ttLoadStopOptions();
        await rtLoadBusOptions();
        // Start from a blank drawing every time the form is opened.
        if(rtMap) rtClearDrawing(); else rtDrawPts = [];
        const drawWrap = document.getElementById('rtDrawWrap');
        if(drawWrap) drawWrap.hidden = true;
        if(stopsList){ stopsList.hidden = false; stopsList.innerHTML = ''; itnAddStop('rtStops'); itnAddStop('rtStops'); }
    }

    const idEl = document.getElementById('rtId');
    idEl.value = route ? route.id : '';
    idEl.disabled = !!route;   // id is the primary key → not editable on update
    document.getElementById('rtShort').value  = route ? (route.shortName || '') : '';
    document.getElementById('rtLong').value   = route ? (route.longName || '') : '';
    document.getElementById('rtColor').value  = route && route.color ? ('#' + route.color) : '#3B82F6';
    document.getElementById('rtActive').value = route ? (route.active === false ? 'false' : 'true') : 'true';
    setRtMsg('');
    document.getElementById('rtForm').hidden = false;
    (route ? document.getElementById('rtShort') : idEl).focus();
}

function closeRouteForm(){
    document.getElementById('rtForm').hidden = true;
    rtEditId = null;
    rtPathLoaded = false;
    rtHidePreview();
    setRtMsg('');
}

async function saveRoute(){
    const payload = {
        id:        document.getElementById('rtId').value,
        shortName: document.getElementById('rtShort').value,
        longName:  document.getElementById('rtLong').value,
        color:     document.getElementById('rtColor').value.replace(/^#/, ''),
        active:    document.getElementById('rtActive').value === 'true'
    };
    if(!payload.id || !payload.id.trim()){ setRtMsg('Route id is required.'); return; }
    if(!payload.shortName || !payload.shortName.trim()){ setRtMsg('Short name is required.'); return; }

    const editing = rtEditId != null;

    // Creating: attach the itinerary + the bus of the first journey. The
    // backend stores the path as that journey's scheduled stops.
    if(!editing){
        payload.busId = parseInt(document.getElementById('rtBus').value, 10) || null;

        // Drawn on the map: send the full geometry. Every vertex goes to
        // route_shapes; the ones flagged as stops also become the calls of
        // the line's first run, with the times typed in the rows below.
        if(rtDrawPts.length >= 2){
            const rows = Array.from(document.querySelectorAll('#rtStops .tt-manual-row'));
            const timeByIdx = rows.map(r => r.querySelector('.tt-time').value);
            let stopIdx = 0;
            payload.path = rtDrawPts.map(pt => ({
                lat: pt.lat, lon: pt.lon, isStop: !!pt.isStop,
                stopId: pt.stopId, stopName: pt.stopName,
                arrival: pt.isStop ? (timeByIdx[stopIdx++] || '') : null
            }));
            const drawnStops = payload.path.filter(p => p.isStop);
            if(drawnStops.length < 2){ setRtMsg('Mark at least two stops on the map (right click).'); return; }
            if(drawnStops.some(s => !s.arrival)){ setRtMsg('Every stop needs a time.'); return; }
        }else{
            payload.stops = itnCollect('rtStops');
            if(payload.stops.length < 2){ setRtMsg('An itinerary needs at least two stops.'); return; }
            if(payload.stops.some(s=>!s.stopId || !s.arrival)){
                setRtMsg('Every stop needs both a stop and a time.'); return;
            }
        }
        if(!payload.busId){ setRtMsg('Pick the bus for the first journey.'); return; }
    }
    const url    = editing ? `${API}/routes/${encodeURIComponent(rtEditId)}` : `${API}/routes`;
    const method = editing ? 'PUT' : 'POST';

    // Modifica con percorso aperto: prima si mostra l'impatto, poi si scrive.
    // Le due scritture restano separate perche' sono due cose diverse — i
    // campi della linea e il suo percorso — e la seconda puo' ri-tempificare
    // decine di corse. Si chiede conferma una volta sola, sull'insieme.
    let shapePath = null;
    if(editing && rtPathLoaded){
        if(rtDrawPts.length < 2){ setRtMsg('The path needs at least two points.'); return; }
        shapePath = rtDrawPts.map(pt => ({
            lat: pt.lat, lon: pt.lon, isStop: !!pt.isStop,
            stopId: pt.stopId, stopName: pt.stopName, arrival: null
        }));
        if(shapePath.filter(p=>p.isStop).length < 2){
            setRtMsg('Mark at least two stops on the path (right click).'); return;
        }
        setRtMsg('Checking what would change…', true);
        let pv;
        try{ pv = await rtFetchPreview(rtEditId, shapePath); }
        catch(e){ setRtMsg(e.message || 'Preview failed.'); return; }

        const question = rtShowPreview(pv);
        // Nessuna domanda se le fermate non cambiano: ridisegnare la strada
        // non tocca nessun orario, e chiedere conferma per nulla insegna solo
        // a cliccare "ok" senza leggere.
        if(question && !window.confirm(question)){ setRtMsg(''); return; }
    }

    setRtMsg('Saving…', true);
    try{
        const r = await fetch(url, {
            method,
            headers:{'Content-Type':'application/json'},
            body: JSON.stringify(payload)
        });
        if(!r.ok){
            let msg = 'Save failed.';
            try{ const j = await r.json(); if(j && j.error) msg = j.error; }catch(_){}
            setRtMsg(msg); return;
        }

        if(shapePath){
            const s = await fetch(`${API}/routes/${encodeURIComponent(rtEditId)}/shape`, {
                method:'PUT', headers:{'Content-Type':'application/json'},
                body: JSON.stringify(shapePath)
            });
            if(!s.ok){
                let msg = 'The line was saved, but its path was not.';
                try{ const j = await s.json(); if(j && j.error) msg = j.error; }catch(_){}
                setRtMsg(msg); return;   // il form resta aperto: c'e' ancora da fare
            }
        }
        closeRouteForm();
        await loadRoutesAdmin();
    }catch(e){ setRtMsg('Network error while saving.'); }
}

async function deleteRoute(id){
    const route = rtRoutes.find(x=>x.id===id);

    // Si chiede PRIMA al backend cosa sparirebbe. Cancellare una linea con le
    // sue corse e' irreversibile e le FK sono ON DELETE CASCADE, quindi il
    // database lo farebbe in silenzio: "cancella LINEA_16" e "cancella 26
    // corse e 208 arrivi" sono la stessa azione ma non la stessa frase, e chi
    // clicca deve leggere la seconda.
    let impact = null;
    try{
        const r = await fetch(`${API}/routes/${encodeURIComponent(id)}/delete-impact`,
                              {headers:{'Accept':'application/json'}});
        if(r.ok) impact = await r.json();
    }catch(e){ /* si prosegue con la conferma semplice */ }

    const label = impact ? impact.label : (route ? route.id : id);
    let question;
    let cascade = false;

    if(impact && impact.trips > 0){
        cascade = true;
        const lines = [
            `Delete line ${label} AND everything scheduled on it?`,
            '',
            `· ${impact.trips} run(s)`,
            `· ${impact.calls} scheduled arrival(s)`,
            `· ${impact.patternStops} stop(s) in its route`,
            `· ${impact.shapeVertices} drawn point(s)`
        ];
        if(impact.busesOnLine > 0){
            lines.push('', `${impact.busesOnLine} bus(es) are running this line right now. `
                         + 'They will disappear from the map at the next poll.');
        }
        lines.push('', 'The stops themselves are NOT deleted — only this line and its timetable.',
                       'This cannot be undone.');
        question = lines.join('\n');
    }else{
        question = `Delete line ${label}? This cannot be undone.`;
    }

    if(!window.confirm(question)) return;

    try{
        const url = `${API}/routes/${encodeURIComponent(id)}` + (cascade ? '?cascade=true' : '');
        const r = await fetch(url, {method:'DELETE'});
        if(r.ok){ await loadRoutesAdmin(); }
        else{
            let msg = 'Delete failed.';
            try{ const j = await r.json(); if(j && j.error) msg = j.error; }catch(_){}
            window.alert(msg);
        }
    }catch(e){ window.alert('Network error while deleting.'); }
}

// ── Data Management: timetable (trips + stop times) ───────────────────────
// A run is one journey of a line at a given departure. Creating one copies
// the line's stop sequence and shifts every time — the same thing the V5
// migration did by hand, done from the UI instead.
let ttOpenTripId = null;   // run whose stop times are being edited

/** Fill the line/bus dropdowns (filters + create form) from existing data. */

function ttSetMsg(id, txt, ok){
    const el = document.getElementById(id);
    if(!el) return;
    el.textContent = txt || '';
    el.classList.toggle('err', !!txt && !ok);
    el.classList.toggle('ok',  !!txt && !!ok);
}

// ── Table export — CSV, XLSX, PDF ─────────────────────────────────────────
// The rows are still gathered here, from what the table is showing, so the file
// keeps matching the screen filters included — that was always the point of
// building the CSV in the browser. What moved to the server is the RENDERING:
// one report, three formats, so a CSV and a PDF of the same table cannot
// disagree, and the PDF is a laid-out document rather than window.print() of a
// dashboard. Same shape as the OMNIMOVE analytics export.

/** Everything a table needs to become a file. rows() may be async. */
const EXPORTS = {
    buses: {
        title: 'Buses',
        subtitle: () => filterSummary([
            ['Search',  valueOf('dmSearch')],
            ['Route',   labelOf('dmFilterRoute')],
            ['Status',  labelOf('dmFilterStatus')]
        ]),
        columns: [
            {header:'Bus ID',      get:b=>b.busId},
            {header:'Plate',       get:b=>b.targa},
            {header:'Route',       get:b=>b.routeName},
            {header:'Route live',  get:b=>b.routeLive === true ? 'yes' : b.routeLive === false ? 'scheduled' : ''},
            {header:'Capacity',    get:b=>b.numeroPosti},
            {header:'Wheelchair',  get:b=>b.wheelchairAccessible ? 'yes' : 'no'},
            {header:'Status',      get:b=>b.status},
            {header:'Antenna ID',  get:b=>b.currentVehicleId}
        ],
        // Already the filtered set: dmRenderTable keeps what it drew
        rows: () => dmBuses
    },
    stops: {
        title: 'Stops',
        subtitle: () => filterSummary([
            ['Search', valueOf('stSearch')],
            ['Active', labelOf('stFilterActive')]
        ]),
        columns: [
            {header:'Stop ID',     get:s=>s.id},
            {header:'Name',        get:s=>s.name},
            {header:'Latitude',    get:s=>s.lat},
            {header:'Longitude',   get:s=>s.lon},
            {header:'Active',      get:s=>s.active === false ? 'no' : 'yes'},
            {header:'Description', get:s=>s.description}
        ],
        rows: () => stFiltered()
    },
    routes: {
        title: 'Routes',
        subtitle: () => filterSummary([
            ['Search', valueOf('rtSearch')],
            ['Active', labelOf('rtFilterActive')]
        ]),
        columns: [
            {header:'Route ID',   get:r=>r.id},
            {header:'Short name', get:r=>r.shortName},
            {header:'Long name',  get:r=>r.longName},
            {header:'Colour',     get:r=>r.color},
            {header:'Active',     get:r=>r.active === false ? 'no' : 'yes'}
        ],
        rows: () => rtFiltered()
    },
    trips: {
        title: 'Timetable — runs',
        subtitle: () => filterSummary([
            ['Search', valueOf('tripsSearch')],
            ['Route',  labelOf('tripsRoute')],
            ['Bus',    labelOf('tripsBus')],
            ['Status', labelOf('tripsStatus')]
        ]),
        columns: [
            {header:'Trip ID',       get:t=>t.trip_id},
            {header:'Route',         get:t=>t.route_name},
            {header:'Plate',         get:t=>t.plate},
            {header:'Antenna',       get:t=>t.vehicle_id},
            {header:'Start',         get:t=>t.start_time},
            {header:'End (sched.)',  get:t=>t.end_time},
            {header:'End (real)',    get:t=>t.actual_end_time},
            {header:'Stops done',    get:t=>t.stops_done},
            {header:'Stops total',   get:t=>t.stops_total},
            {header:'Progress %',    get:t=>t.progress_pct},
            {header:'Phase',         get:t=>t.phase},
            {header:'Status',        get:t=>t.status},
            {header:'Delay (min)',   get:t=>t.delay_minutes}
        ],
        rows: () => tripsLast.filter(tripsMatches)
    },
    stopTimes: {
        title: 'Timetable — stop times',
        subtitle: () => filterSummary([
            ['Search', valueOf('tripsSearch')],
            ['Route',  labelOf('tripsRoute')],
            ['Bus',    labelOf('tripsBus')],
            ['Status', labelOf('tripsStatus')]
        ]),
        columns: [
            {header:'Run ID',    get:x=>x.tripId},
            {header:'Line',      get:x=>x.routeLabel},
            {header:'Bus',       get:x=>x.targa},
            {header:'Stop #',    get:x=>x.sequence},
            {header:'Stop ID',   get:x=>x.stopId},
            {header:'Stop name', get:x=>x.stopName},
            {header:'Arrival',   get:x=>x.arrival}
        ],
        // The only one not already in the browser: the table holds summaries,
        // so the detail is fetched with the filters the panel is showing.
        rows: async () => {
            const params = new URLSearchParams();
            const s  = document.getElementById('tripsSearch');
            const fr = document.getElementById('tripsRoute');
            const fb = document.getElementById('tripsBus');
            if (s && s.value.trim()) params.set('search', s.value.trim());
            if (fr && fr.value) params.set('routeId', fr.value);
            if (fb && fb.value) params.set('busId', fb.value);
            const r = await fetch(`${API}/trips/stop-times?${params.toString()}`,
                {headers:{'Accept':'application/json'}});
            if (!r.ok) throw new Error(r.status);
            return await r.json();
        }
    },
    // The statistics report: every panel of the analytics tab, not just the
    // table at the bottom of it. Each chart is a table underneath — a line and
    // a number — and a chart that cannot be filed is a chart nobody can quote.
    analytics: {
        title: 'Fleet analytics',
        subtitle: () => filterSummary([
            ['Period', PRESET_LABEL[activeFilters.preset] || ''],
            ['Bus',    labelOf('filterBus')]
        ]),
        sections: () => analyticsSections()
    }
};

/**
 * The analytics tab as a filed document: the headline figures first, then one
 * section per panel, then the vehicle table.
 *
 * <p>Built from the payloads the panels themselves were drawn from —
 * lastNetwork and lastAdherenceVehicles — so the report cannot say something
 * the screen does not. Sections with nothing in them are left out rather than
 * printed as empty headings: a period with no measured delays is a fact the
 * summary already states, and a bare "Delay by line" with no rows under it
 * reads as a fault.
 */
function analyticsSections() {
    const d = lastNetwork || {};
    const sections = [];
    const num = v => (v === null || v === undefined) ? '—' : String(v);
    const min = v => (v === null || v === undefined) ? '—' : Number(v).toFixed(1);

    // ── Summary ──
    const byHour = d.occupancy_by_hour || [];
    const peak = byHour.length
        ? byHour.reduce((a, b) => (b.pct > a.pct ? b : a))
        : null;

    const byDay = d.delay_by_weekday || {};
    const measuredDays = Object.entries(byDay).filter(([, v]) => v != null);
    const worstDay = measuredDays.length
        ? measuredDays.reduce((a, b) => (Math.abs(b[1]) > Math.abs(a[1]) ? b : a))
        : null;

    const summary = [
        ['Period',                 PRESET_LABEL[activeFilters.preset] || '—'],
        ['Active bus lines',       num(d.active_lines)],
        ['Average delay (min)',    min(d.avg_delay_minutes)],
        ['Change vs previous period',
            d.delay_delta == null ? 'no comparable previous period'
                                  : (d.delay_delta > 0 ? '+' : '') + Number(d.delay_delta).toFixed(1) + ' min'],
        ['Busiest hour',           peak ? `${peak.slot} (${peak.pct}% full)` : '—'],
        ['Worst weekday',          worstDay ? `${worstDay[0]} (${Number(worstDay[1]).toFixed(1)} min)` : '—'],
        ['Buses reporting',        String((lastAdherenceVehicles || []).length)]
    ];
    sections.push({ title: 'Summary', headers: ['Measure', 'Value'], rows: summary });

    // ── Active buses on road, per line ──
    const onRoad = d.buses_on_road || [];
    if (onRoad.length) sections.push({
        title: 'Active buses on road',
        headers: ['Line', 'Buses'],
        rows: onRoad.map(r => [cellText(r.label), cellText(r.buses)])
    });

    // ── Average delay per line ──
    const byLine = d.delay_by_line || [];
    if (byLine.length) sections.push({
        title: 'Average delay by line',
        headers: ['Line', 'Delay (min)'],
        rows: byLine.map(r => [cellText(r.label), min(r.delay_minutes)])
    });

    // ── Delay by weekday. Every day is listed, measured or not: the gap is
    //    the finding when a line only runs on weekdays. ──
    const days = Object.keys(byDay);
    if (days.length) sections.push({
        title: 'Average delay by weekday',
        headers: ['Day', 'Delay (min)'],
        rows: days.map(k => [k, min(byDay[k])])
    });

    // ── Occupancy by hour ──
    if (byHour.length) sections.push({
        title: 'Occupancy by hour',
        headers: ['Hour', 'Occupancy (%)'],
        rows: byHour.map(r => [cellText(r.slot), cellText(r.pct)])
    });

    // ── The vehicles themselves ──
    const fleet = lastAdherenceVehicles || [];
    if (fleet.length) sections.push({
        title: 'Schedule adherence — by vehicle',
        headers: ['Bus', 'Line', 'Status', 'Speed (km/h)', 'Delay (min)', 'Crowding'],
        rows: fleet.map(v => [
            cellText(v.vehicle_id),
            cellText(v.route_name || ''),
            cellText(SL[v.status] || v.status),
            v.speed_kmh != null ? Number(v.speed_kmh).toFixed(1) : '',
            v.delay_minutes != null ? cellText(v.delay_minutes) : '',
            cellText(v.crowding || '')
        ])
    });

    return sections;
}

/** The value of a text filter, or '' — so an untouched field says nothing. */
function valueOf(id) {
    const el = document.getElementById(id);
    return el && el.value ? el.value.trim() : '';
}

/** What a <select> READS, not the id it carries: "Line 05" beats "LINEA-05". */
function labelOf(id) {
    const el = document.getElementById(id);
    if (!el || !el.value) return '';
    const opt = el.selectedOptions && el.selectedOptions[0];
    return opt ? opt.textContent.trim() : el.value;
}

/** "Search: folcara · Route: Line 05". Empty when nothing is filtered. */
function filterSummary(pairs) {
    return pairs.filter(p => p[1]).map(p => `${p[0]}: ${p[1]}`).join('  ·  ');
}

/** Cells as strings: the renderer decides what looks like a number. */
function cellText(value) {
    return (value === null || value === undefined) ? '' : String(value);
}

/**
 * Builds the report and asks the server to render it.
 *
 * <p>The blob comes back with the filename in Content-Disposition, so the three
 * formats are named by one rule rather than by three sprinkled templates.
 */
async function runExport(dataset, format) {
    const spec = EXPORTS[dataset];
    if (!spec) return;

    const btns = document.querySelectorAll(`.exp-btn[data-export="${dataset}"]`);
    btns.forEach(b => b.disabled = true);

    try {
        // Two shapes: a plain table gives columns and rows, the analytics report
        // gives whole sections. Everything below this line sees only sections.
        const sections = spec.sections
            ? await spec.sections()
            : [{
                title: '',
                headers: spec.columns.map(c => c.header),
                rows: (await spec.rows() || []).map(r => spec.columns.map(c => cellText(c.get(r))))
              }];

        if (!sections.length || sections.every(sec => !sec.rows.length)) {
            window.alert('Nothing to export — there is no data on screen yet.');
            return;
        }

        const body = {
            title: spec.title,
            subtitle: spec.subtitle ? spec.subtitle() : '',
            sections
        };

        const res = await fetch(`${API}/reports/export?format=${encodeURIComponent(format)}`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(body)
        });
        if (!res.ok) throw new Error(res.status);

        const blob = await res.blob();
        const name = filenameFrom(res.headers.get('Content-Disposition'))
                  || `${dataset}-${csvStamp()}.${format}`;
        saveBlob(blob, name);

    } catch (e) {
        window.alert('Could not build the export. ' + (e && e.message ? '(' + e.message + ')' : ''));
    } finally {
        btns.forEach(b => b.disabled = false);
    }
}

/** The name the server chose, out of the header it set. */
function filenameFrom(disposition) {
    if (!disposition) return null;
    const m = /filename="?([^"]+)"?/.exec(disposition);
    return m ? m[1] : null;
}

function saveBlob(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

/** Timestamp suffix, kept for the fallback name when the header is missing. */
function csvStamp(){
    const d = new Date(), p = n => String(n).padStart(2,'0');
    return `${d.getFullYear()}${p(d.getMonth()+1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`;
}

// One handler for every export button in the page, present or future: the
// dataset and the format are on the button.
document.addEventListener('click', e => {
    const btn = e.target.closest('.exp-btn');
    if (btn) runExport(btn.dataset.export, btn.dataset.format);
});

// ── Itinerary editor (shared) ─────────────────────────────────────────────
// Used by the Routes form to define a line's path. A run never defines its
// own stops: it inherits the line's itinerary.
let ttStopOptions = '';   // <option> list of every stop, built once

async function ttLoadStopOptions(){
    if(ttStopOptions) return;
    try{
        const r = await fetch(`${API}/stops`, {headers:{'Accept':'application/json'}});
        if(!r.ok) return;
        const stops = await r.json();
        ttStopOptions = stops
            .slice()
            .sort((a,b)=>String(a.name||a.id).localeCompare(String(b.name||b.id)))
            .map(s=>`<option value="${escHtml(s.id)}">${escHtml(s.name||s.id)}</option>`)
            .join('');
    }catch(e){ /* leave empty; the row will show no options */ }
}

/** Append one editable call to a list. Times default to +2 min after the last. */
function itnAddStop(listId){
    const list = document.getElementById(listId);
    if(!list) return;
    const rows = list.querySelectorAll('.tt-manual-row');
    let nextTime = '08:00';
    if(rows.length){
        const last = rows[rows.length-1].querySelector('.tt-time').value || '08:00';
        const [h,m] = last.split(':').map(Number);
        const t = (h*60 + m + 2) % (24*60);
        nextTime = String(Math.floor(t/60)).padStart(2,'0') + ':' + String(t%60).padStart(2,'0');
    }
    const row = document.createElement('div');
    row.className = 'tt-stop-row tt-manual-row';
    row.innerHTML = `
            <span class="tt-seq"></span>
            <select class="bm-input tt-stop-select">${ttStopOptions}</select>
            <input type="time" class="bm-input tt-time" value="${nextTime}">
            <button type="button" class="bm-row-btn danger tt-del-stop" title="Remove this stop">✕</button>`;
    list.appendChild(row);
    itnRenumber(listId);
}

function itnRenumber(listId){
    document.querySelectorAll('#'+listId+' .tt-manual-row').forEach((r,i)=>{
        r.querySelector('.tt-seq').textContent = (i+1);
    });
}

/** Read an itinerary editor into the payload shape the backend expects. */
function itnCollect(listId){
    return Array.from(document.querySelectorAll('#'+listId+' .tt-manual-row')).map(r=>({
        stopId:  r.querySelector('.tt-stop-select').value,
        arrival: r.querySelector('.tt-time').value
    }));
}

/** Open the stop-by-stop time editor for one run. */
async function ttOpenTimes(tripId){
    const box = document.getElementById('ttDetail');
    const list = document.getElementById('ttStops');
    if(!box || !list) return;
    ttOpenTripId = tripId;
    box.hidden = false;
    // scroll it into view: the panel is tall and the editor sits above the table
    box.scrollIntoView({behavior:'smooth', block:'nearest'});
    ttSetMsg('ttDetailMsg', '');
    list.innerHTML = '<div class="bm-msg">Loading…</div>';
    try{
        const r = await fetch(`${API}/trips/${encodeURIComponent(tripId)}/stops`, {headers:{'Accept':'application/json'}});
        if(!r.ok) throw new Error(r.status);
        const d = await r.json();
        document.getElementById('ttDetailTitle').textContent =
            `Run ${d.tripId} · ${d.routeLabel||''} · bus ${d.targa||'—'}`;
        list.innerHTML = d.stops.map(s=>`
                <div class="tt-stop-row">
                    <span class="tt-seq">${s.sequence}</span>
                    <span class="tt-stop-name">${escHtml(s.stopName)}</span>
                    <input type="time" class="bm-input tt-time" value="${escHtml(s.arrival)}"
                           data-seq="${s.sequence}" data-stop-id="${escHtml(s.stopId)}">
                </div>`).join('');
    }catch(e){
        list.innerHTML = '<div class="bm-msg err">Could not load the stop times.</div>';
    }
}

async function ttSaveTimes(){
    if(!ttOpenTripId) return;
    const inputs = document.querySelectorAll('#ttStops .tt-time');
    const stops = Array.from(inputs).map(i=>({
        stopId:   i.dataset.stopId,
        sequence: parseInt(i.dataset.seq, 10),
        arrival:  i.value
    }));
    if(stops.some(s=>!s.arrival)){ ttSetMsg('ttDetailMsg', 'Every stop needs a time.'); return; }
    ttSetMsg('ttDetailMsg', 'Saving…', true);
    try{
        const r = await fetch(`${API}/trips/${encodeURIComponent(ttOpenTripId)}/times`, {
            method:'PUT',
            headers:{'Content-Type':'application/json'},
            body: JSON.stringify({stops})
        });
        if(r.ok){
            ttSetMsg('ttDetailMsg', 'Saved.', true);
            await tripsLoad();
        }else{
            let msg = 'Could not save the times.';
            try{ const j = await r.json(); if(j && (j.error||j.message)) msg = j.error||j.message; }catch(_){}
            ttSetMsg('ttDetailMsg', msg);
        }
    }catch(e){ ttSetMsg('ttDetailMsg', 'Network error while saving.'); }
}

async function ttDeleteRun(tripId){
    if(!window.confirm(`Delete run ${tripId}? Its stop times will be removed too.`)) return;
    try{
        const r = await fetch(`${API}/trips/${encodeURIComponent(tripId)}`, {method:'DELETE'});
        if(r.ok){
            if(ttOpenTripId === tripId){ document.getElementById('ttDetail').hidden = true; ttOpenTripId = null; }
            await tripsLoad();
        }else{
            let msg = 'Delete failed.';
            try{ const j = await r.json(); if(j && (j.error||j.message)) msg = j.error||j.message; }catch(_){}
            window.alert(msg);
        }
    }catch(e){ window.alert('Network error while deleting.'); }
}

async function logoutUser(){
    // Token blacklisted server-side, cookie expired, storage wiped, page dropped
    // from the history stack. (Earlier this was gated behind `if (token)` reading a
    // localStorage key nothing writes since the httpOnly-cookie migration, so the logout
    // call never fired at all — hence the shared module, one implementation for all three
    // consoles.)
    await CassiSession.endSession();
}
setInterval(()=>{
    const t=document.getElementById('hTime');
    if(lastUpdate){const s=Math.round((Date.now()-lastUpdate.getTime())/1000);t.textContent=`· next in ${Math.max(0,15-s)}s`;}
},1000);

setTimeout(()=>document.getElementById('loading').classList.add('gone'),1500);
window.addEventListener('load', () => {

    fetchVehicles();

    setInterval(fetchVehicles, REFRESH);

});

// ═══════════════════════════════════════════════════════════
// ANALYTICS
//
// Two kinds of surface live in this tab and they are not filtered alike:
//
//   · Live panels (KPIs, fleet status, delay per bus, active buses) read
//     /analytics/summary and /analytics/adherence, which describe the fleet
//     as it is right now. A period has no meaning for them, so the filter
//     bar does not touch them — the NOW chip in each header says so.
//   · Network Activity reads /analytics/busiest-hours, which is historical
//     and does honour startTime/endTime/busId.
//
// The line multi-select and the hour/day group-by were dropped along with
// the per-route charts they used to drive: nothing left on this tab reads
// them, and a filter that changes nothing is worse than no filter.
// ═══════════════════════════════════════════════════════════

const activeFilters = {
    preset:    'today',
    startTime: null,
    endTime:   null,
    busId:     ''
};

const PRESET_LABEL = {
    today:'Today', yesterday:'Yesterday', week:'Last 7 Days',
    month:'This Month', lastmonth:'Last Month', custom:'Custom Range'
};

// Last adherence payload, kept so the CSV export writes exactly what is on
// screen. Distinct from the map's lastVehicles: that one is /vehicles, this
// one carries the measured delay the adherence endpoint adds on top.
let lastAdherenceVehicles = [];
/** Last /analytics/network payload — what the charts and the report both read. */
let lastNetwork = null;

// ── Preset logic ──────────────────────────────────────────

function setPreset(btn, preset) {
    document.querySelectorAll('.preset-btn[data-preset]')
        .forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    activeFilters.preset = preset;

    const custom = document.getElementById('customDateRange');
    custom.style.display = preset === 'custom' ? 'flex' : 'none';

    if (preset !== 'custom') {
        activeFilters.startTime = null;
        activeFilters.endTime   = null;
    }
}

function presetToRange(preset) {
    const now = new Date();
    const iso = d => d.toISOString();

    const startOfDay = d => { const x = new Date(d); x.setHours(0,0,0,0);        return x; };
    const endOfDay   = d => { const x = new Date(d); x.setHours(23,59,59,999);   return x; };

    switch (preset) {
        case 'today':
            return { start: iso(startOfDay(now)), stop: iso(endOfDay(now)) };
        case 'yesterday': {
            const y = new Date(now); y.setDate(y.getDate() - 1);
            return { start: iso(startOfDay(y)), stop: iso(endOfDay(y)) };
        }
        case 'week': {
            const w = new Date(now); w.setDate(w.getDate() - 6);
            return { start: iso(startOfDay(w)), stop: iso(endOfDay(now)) };
        }
        case 'month': {
            const m = new Date(now.getFullYear(), now.getMonth(), 1);
            return { start: iso(startOfDay(m)), stop: iso(endOfDay(now)) };
        }
        case 'lastmonth': {
            const lm  = new Date(now.getFullYear(), now.getMonth() - 1, 1);
            const lme = new Date(now.getFullYear(), now.getMonth(), 0);
            return { start: iso(startOfDay(lm)), stop: iso(endOfDay(lme)) };
        }
        case 'custom': {
            const from = document.getElementById('filterFrom').value;
            const to   = document.getElementById('filterTo').value;
            if (!from) return { start: null, stop: null };
            const s = new Date(from); s.setHours(0,0,0,0);
            const e = to ? new Date(to) : new Date(from);
            e.setHours(23,59,59,999);
            return { start: iso(s), stop: iso(e) };
        }
        default:
            return { start: null, stop: null };
    }
}

function buildFilterParams() {
    const { start, stop } = presetToRange(activeFilters.preset);
    const params = new URLSearchParams();
    if (start) params.set('startTime', start);
    if (stop)  params.set('endTime',   stop);
    if (activeFilters.busId) params.set('busId', activeFilters.busId);
    return params.toString();
}

// Filled from the live vehicle poll (fetchVehicles), not from a call of its own
function populateBusDropdown(vehicles) {
    const sel  = document.getElementById('filterBus');
    const prev = sel.value;
    const ids  = [...new Set(vehicles.map(v => v.vehicle_id))].sort();
    sel.innerHTML = '<option value="">All Buses</option>' +
        ids.map(id => `<option value="${escHtml(id)}" ${prev === id ? 'selected' : ''}>${escHtml(id)}</option>`).join('');
}

// ── 1) NETWORK OVERVIEW → the KPI band and three of the four panels ──
//
// One request, four panels. They must agree with each other — a headline
// "avg delay +3.4 min" above a per-line chart computed from a different scan
// is a bug waiting to be reported — so the backend derives them all from a
// single pass and returns them together.

let linesChartInstance   = null;
let weekdayChartInstance = null;
let peakChartInstance    = null;

// Chart.js defaults shared by the three canvases below.
const AN_TICK  = { color: '#4B5563', font: { family: 'DM Mono', size: 9 } };
const AN_GRID_X = { grid: { display: false }, ticks: AN_TICK };
const AN_GRID_Y = { grid: { color: 'rgba(255,255,255,.04)' }, ticks: AN_TICK };

/** Show a canvas or the "nothing here" note in its place, never both. */
function anToggleChart(wrapId, emptyId, hasData) {
    const wrap  = document.getElementById(wrapId);
    const empty = document.getElementById(emptyId);
    if (wrap)  wrap.style.display  = hasData ? ''      : 'none';
    if (empty) empty.style.display = hasData ? 'none'  : 'block';
}

async function loadNetwork() {
    try {
        const r = await fetch(`${API}/analytics/network?${buildFilterParams()}`);
        if (!r.ok) throw new Error(r.status);
        const d = await r.json();

        // Kept whole: the statistics report is built from the same payload the
        // panels are drawn from, so the document and the screen cannot disagree.
        lastNetwork = d;

        renderNetworkKpis(d);
        renderLinesChart(d.buses_on_road   || []);
        renderWeekdayChart(d.delay_by_weekday || {});
        renderPeakChart(d.occupancy_by_hour   || []);
        renderDelayByLine(d.delay_by_line     || []);
    } catch (e) {
        console.error('Failed to load network overview', e);
        anToggleChart('lines-chart-wrap',   'linesChartEmpty',   false);
        anToggleChart('weekday-chart-wrap', 'weekdayChartEmpty', false);
        anToggleChart('peak-chart-wrap',    'peakChartEmpty',    false);
    }
}

function renderNetworkKpis(d) {
    const set = (id, txt) => { const el = document.getElementById(id); if (el) el.textContent = txt; };

    set('kpiActiveLines', d.active_lines ?? '—');

    // The delay value keeps its "min" suffix in a child span, so write only
    // the number node rather than clobbering the markup.
    const delayEl = document.getElementById('kpiAvgDelay');
    if (delayEl && delayEl.firstChild) {
        const v = d.avg_delay_minutes;
        const num = v == null ? '—' : (v > 0 ? `+${v.toFixed(1)}` : v.toFixed(1));
        delayEl.firstChild.nodeValue = num;
    }

    // Late is bad, early is not good — so the arrow colours by direction of
    // change, and a missing comparison says so instead of showing "0.0".
    const sub = document.getElementById('kpiAvgDelaySub');
    if (sub) {
        const delta = d.delay_delta;
        sub.classList.remove('kpi-up', 'kpi-down');
        if (delta == null) {
            sub.textContent = 'no comparable previous period';
        } else if (Math.abs(delta) < 0.05) {
            sub.textContent = 'unchanged vs previous period';
        } else {
            const worse = delta > 0;
            sub.classList.add(worse ? 'kpi-up' : 'kpi-down');
            sub.textContent = `${worse ? '▲' : '▼'} ${worse ? '+' : ''}${delta.toFixed(1)} vs previous period`;
        }
    }

    const linesSub = document.getElementById('kpiActiveLinesSub');
    if (linesSub) {
        const busTxt = activeFilters.busId ? ` · ${activeFilters.busId}` : '';
        linesSub.textContent = `lines with recorded service${busTxt}`;
    }
}

// ── Panel 1: buses on road, per line ───────────────────────

function renderLinesChart(rows) {
    const canvas = document.getElementById('linesChart');
    if (!canvas) return;

    if (linesChartInstance) linesChartInstance.destroy();
    anToggleChart('lines-chart-wrap', 'linesChartEmpty', rows.length > 0);
    if (!rows.length) { linesChartInstance = null; return; }

    const values = rows.map(r => r.buses);
    // Highlight the lines carrying the most vehicles: at a glance, where the
    // fleet is concentrated right now.
    const busiest = Math.max(...values);

    linesChartInstance = new Chart(canvas, {
        type: 'bar',
        data: {
            labels: rows.map(r => r.label),
            datasets: [{
                data: values,
                backgroundColor: values.map(v => v >= busiest && busiest > 1 ? '#06B6D4' : '#3B82F6'),
                borderRadius: 4,
                maxBarThickness: 18
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false },
                tooltip: { callbacks: {
                    title: ctx => rows[ctx[0].dataIndex].name || rows[ctx[0].dataIndex].label,
                    label: ctx => `${ctx.parsed.y} bus${ctx.parsed.y === 1 ? '' : 'es'} on road`
                } }
            },
            scales: {
                x: AN_GRID_X,
                y: { ...AN_GRID_Y, beginAtZero: true,
                     ticks: { ...AN_TICK, precision: 0 },
                     title: { display: true, text: 'Buses',
                              color: '#4B5563', font: { family: 'DM Mono', size: 10 } } }
            }
        }
    });
}

// ── Panel 2: average delay per weekday ─────────────────────

function renderWeekdayChart(byDay) {
    const canvas = document.getElementById('weekdayChart');
    if (!canvas) return;

    const labels = Object.keys(byDay);
    const values = labels.map(k => byDay[k]);
    const measured = values.filter(v => v != null);

    if (weekdayChartInstance) weekdayChartInstance.destroy();
    anToggleChart('weekday-chart-wrap', 'weekdayChartEmpty', measured.length > 0);
    if (!measured.length) { weekdayChartInstance = null; return; }

    // Name the worst day in the header chip — the one thing an operator wants
    // out of this chart without reading it.
    const worstEl = document.getElementById('worstDayLbl');
    if (worstEl) {
        const worst = labels.reduce((a, b) =>
            (byDay[b] ?? -Infinity) > (byDay[a] ?? -Infinity) ? b : a, labels[0]);
        worstEl.textContent = byDay[worst] != null
            ? `Worst day: ${worst} (+${byDay[worst].toFixed(1)} min)`
            : 'Worst day: —';
    }

    weekdayChartInstance = new Chart(canvas, {
        type: 'line',
        data: {
            labels,
            datasets: [{
                label: 'Avg delay (min)',
                data: values,
                borderColor: '#F59E0B',
                backgroundColor: 'rgba(245,158,11,.12)',
                borderWidth: 2.4,
                tension: 0.4,
                pointRadius: 3,
                pointBackgroundColor: '#F59E0B',
                pointBorderColor: '#F59E0B',
                fill: true,
                // Days the period never covered come through as null. Leaving
                // the line broken there is the honest reading: we did not
                // measure a low delay, we measured nothing.
                spanGaps: false
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false },
                tooltip: { callbacks: {
                    label: ctx => ctx.parsed.y == null ? 'no data'
                                : `${ctx.parsed.y > 0 ? '+' : ''}${ctx.parsed.y.toFixed(1)} min`
                } }
            },
            scales: { x: AN_GRID_X, y: { ...AN_GRID_Y, suggestedMin: 0 } }
        }
    });
}

// ── Panel 3: occupancy per hour ────────────────────────────

function renderPeakChart(rows) {
    const canvas = document.getElementById('peakChart');
    if (!canvas) return;

    if (peakChartInstance) peakChartInstance.destroy();
    anToggleChart('peak-chart-wrap', 'peakChartEmpty', rows.length > 0);
    if (!rows.length) { peakChartInstance = null; return; }

    const values = rows.map(r => r.pct);

    const peakEl = document.getElementById('peakLbl');
    if (peakEl) {
        const i = values.indexOf(Math.max(...values));
        peakEl.textContent = `Peak hour: ${rows[i].slot} (${values[i]}%)`;
    }

    // Thresholds, not a gradient: 88% is where a bus starts leaving people at
    // the stop, 50% is comfortable. The colour answers "is this a problem?".
    const colourOf = p => p >= 88 ? SC.SIGNIFICANTLY_LATE
                        : p >= 50 ? SC.SLIGHTLY_LATE
                        : SC.EARLY;

    peakChartInstance = new Chart(canvas, {
        type: 'bar',
        data: {
            labels: rows.map(r => r.slot),
            datasets: [{
                data: values,
                backgroundColor: values.map(colourOf),
                borderRadius: 4,
                maxBarThickness: 18
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false },
                tooltip: { callbacks: { label: ctx => `${ctx.parsed.y}% of seats taken` } }
            },
            scales: {
                x: { ...AN_GRID_X, ticks: { ...AN_TICK, maxRotation: 0, autoSkip: true, maxTicksLimit: 12 } },
                y: { ...AN_GRID_Y, beginAtZero: true, suggestedMax: 100,
                     ticks: { ...AN_TICK, callback: v => `${v}%` } }
            }
        }
    });
}

// ── Panel 4: average delay per line ────────────────────────

function renderDelayByLine(rows) {
    const el = document.getElementById('delayList');
    if (!el) return;

    if (!rows.length) {
        el.innerHTML = `<div class="chart-empty an-shown">No measured delays in this period — a line reports one once a bus passes a stop.</div>`;
        return;
    }

    // Scale against the worst line, with a floor of 6 minutes so a network
    // that is barely late does not render as if it were falling apart.
    const maxDelay = Math.max(6, ...rows.map(r => Math.abs(r.delay_minutes) || 0));
    const colourOf = m => m >= 4 ? SC.SIGNIFICANTLY_LATE
                        : m >= 2 ? SC.SLIGHTLY_LATE
                        : SC.ON_TIME;

    el.innerHTML = rows.map(r => {
        const val  = r.delay_minutes;
        const col  = colourOf(val);
        const pct  = Math.min(100, (Math.abs(val) / maxDelay) * 100);
        const sign = val > 0 ? '+' : '';
        return `
        <div class="an-bar-row">
            <div class="an-bar-top">
                <span class="an-bar-name">${escHtml(r.label)}</span>
                <span class="an-bar-val" data-fg="${col}">${sign}${val.toFixed(1)} min</span>
            </div>
            <div class="an-bar-track">
                <div class="an-bar-fill" data-bg="${col}" data-width-pct="${pct}"></div>
            </div>
        </div>`;
    }).join('');
    applyDynStyles(el);
}

// ── 2) ADHERENCE → the Active vehicles table ───────────────
//
// Live by definition, so it ignores the period filter. It survives the move
// to a line-oriented dashboard because it is the one per-vehicle view left,
// and because it is what the CSV export writes: what you download is what
// you were looking at.

async function loadAdherence() {
    try {
        const r = await fetch(`${API}/analytics/adherence`);
        if (!r.ok) throw new Error(r.status);
        const d = await r.json();

        lastAdherenceVehicles = d.vehicles || [];
        renderVehicleTable(lastAdherenceVehicles);
    } catch (e) {
        console.error('Failed to load adherence', e);
    }
}

function renderVehicleTable(vehicles) {
    const tbody = document.getElementById('vehicleTable');
    if (!tbody) return;

    if (!vehicles.length) {
        tbody.innerHTML = `<tr><td colspan="6" class="an-cell-empty">No buses transmitting right now.</td></tr>`;
        return;
    }

    tbody.innerHTML = vehicles.map(v => {
        const status = v.status || 'UNKNOWN';
        const delay  = v.delay_minutes != null
            ? (v.delay_minutes > 0 ? `+${v.delay_minutes} min` : `${v.delay_minutes} min`)
            : '—';
        const delayCol = v.delay_minutes > 0 ? SC.SLIGHTLY_LATE : SC.ON_TIME;
        return `
        <tr>
            <td>${escHtml(v.vehicle_id)}</td>
            <td>${escHtml(v.route_name) || '—'}</td>
            <td><span class="chip" data-status="${escHtml(status)}">${escHtml(SL[status] || status)}</span></td>
            <td>${v.speed_kmh != null ? v.speed_kmh.toFixed(1) + ' km/h' : '—'}</td>
            <td data-fg="${delayCol}">${delay}</td>
            <td>${escHtml(v.crowding) || '—'}</td>
        </tr>`;
    }).join('');
    applyDynStyles(tbody);
}

// ── Orchestration ──────────────────────────────────────────

function applyFilters() {
    activeFilters.busId = document.getElementById('filterBus').value;

    if (activeFilters.preset === 'custom') {
        const { start, stop } = presetToRange('custom');
        activeFilters.startTime = start;
        activeFilters.endTime   = stop;
    }

    loadAllAnalytics();
}

function loadAllAnalytics() {
    const period = PRESET_LABEL[activeFilters.preset] || '';
    const busTxt = activeFilters.busId ? ` · ${activeFilters.busId}` : '';

    const sub = document.getElementById('weekdayChartSub');
    if (sub) sub.textContent = `Minutes · ${period}${busTxt}`;

    const report = document.getElementById('reportName');
    if (report) report.textContent = `CASSITRACK Analytics — ${period}`;

    loadNetwork();
    loadAdherence();
}

// ── Wire up the Analytics nav button ──────────────────────

document.querySelectorAll('.top-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        if (btn.textContent.trim() === 'Analytics') {
            loadAllAnalytics();
        }
    });
});

// ─────────────────────────────────────────────────────────────────
// CSP FIX (A03/A05): bind every previously-inline onX="" attribute here
// instead. Static, fixed-count elements get a direct id-based listener;
// dynamically-generated, variable-count elements (route checkboxes/pills)
// use event delegation bound once on their stable parent container.
// ─────────────────────────────────────────────────────────────────
document.getElementById('topBtnFleetMonitor').addEventListener('click', e => switchTopView('fleet-monitor', e.currentTarget));
document.getElementById('topBtnAnalytics').addEventListener('click', e => switchTopView('analytics-view', e.currentTarget));
document.getElementById('topBtnAbout').addEventListener('click', e => switchTopView('about-us', e.currentTarget));
// NOTE: the second Data Management button (topBtnDataManagement -> the old
// #data-management-view) was removed when the two rival panels were merged
// into a single #data-management view. Its listener lives further down,
// bound to topBtnDataMgmt.
document.getElementById('topBtnLogout').addEventListener('click', logoutUser);

// Data Management sub-navigation
const dmTabBuses = document.getElementById('dmTabBuses');
if(dmTabBuses) dmTabBuses.addEventListener('click', e => switchDmPanel('dm-panel-buses', e.currentTarget));
const dmTabStops = document.getElementById('dmTabStops');
if(dmTabStops) dmTabStops.addEventListener('click', e => switchDmPanel('dm-panel-stops', e.currentTarget));
const dmTabRoutes = document.getElementById('dmTabRoutes');
if(dmTabRoutes) dmTabRoutes.addEventListener('click', e => switchDmPanel('dm-panel-routes', e.currentTarget));
const dmTabTrips = document.getElementById('dmTabTrips');
if(dmTabTrips) dmTabTrips.addEventListener('click', e => switchDmPanel('dm-panel-trips', e.currentTarget));

// Active Trips: manual refresh, and Edit delegated to the tbody because the
// rows are replaced wholesale every 30 s.
const tripsRefreshBtn = document.getElementById('tripsRefreshBtn');
if(tripsRefreshBtn) tripsRefreshBtn.addEventListener('click', () => {
    tripsLoad();
    tripsStartAutoRefresh();   // restart the clock so the next poll is a full 30 s away
});
const tripsTableBody = document.getElementById('tripsTableBody');
if(tripsTableBody) tripsTableBody.addEventListener('click', e => {
    const edit  = e.target.closest('[data-trip-edit]');
    if(edit){ tripOpenEdit(edit.dataset.tripEdit); return; }
    const stops = e.target.closest('[data-trip-stops]');
    if(stops){ ttOpenTimes(stops.dataset.tripStops); return; }
    const del   = e.target.closest('[data-trip-del]');
    if(del) ttDeleteRun(del.dataset.tripDel);
});

// Trips filters. All client-side over the day's rows already in memory, so
// every one of these is instant and none of them refetches.
const tripsFilterInputs = [
    ['tripsSearch', 'search', 'input'],
    ['tripsPhase',  'phase',  'change'],
    ['tripsStatus', 'status', 'change'],
    ['tripsSpan',   'span',   'change'],
    ['tripsRoute',  'route',  'change'],
    ['tripsBus',    'bus',    'change'],
    // Absolute window: only meaningful while span === 'custom'.
    ['tripsFrom',   'from',   'input'],
    ['tripsTo',     'to',     'input']
];
// Show the two time inputs only for "Custom time", and seed them with the
// current hour so the first pick is one click away instead of a blank field.
const tripsSpanEl   = document.getElementById('tripsSpan');
const tripsCustomEl = document.getElementById('tripsCustomSpan');
if(tripsSpanEl && tripsCustomEl){
    tripsSpanEl.addEventListener('change', e => {
        const custom = e.target.value === 'custom';
        tripsCustomEl.hidden = !custom;
        if(custom){
            const from = document.getElementById('tripsFrom');
            if(from && !from.value){
                const d = new Date();
                from.value = String(d.getHours()).padStart(2,'0') + ':'
                           + String(d.getMinutes()).padStart(2,'0');
                tripsFilter.from = from.value;
            }
        }
        // Il listener generico su tripsSpan applica gia' il filtro subito dopo.
    });
}

tripsFilterInputs.forEach(([id, key, evt]) => {
    const el = document.getElementById(id);
    if(!el) return;
    el.addEventListener(evt, e => {
        const v = e.target.value;
        tripsFilter[key] = key === 'search' ? v.trim().toLowerCase() : v;
        tripsApplyFilter();
    });
});
const tripsClearBtn = document.getElementById('tripsClearBtn');
if(tripsClearBtn) tripsClearBtn.addEventListener('click', () => {
    Object.keys(tripsFilter).forEach(k => tripsFilter[k] = '');
    tripsFilterInputs.forEach(([id]) => {
        const el = document.getElementById(id);
        if(el) el.value = '';
    });
    const cs = document.getElementById('tripsCustomSpan');
    if(cs) cs.hidden = true;          // il custom si chiude col Clear
    tripsApplyFilter();
});

// Active Trips: edit drawer
const tripDrawerClose = document.getElementById('tripDrawerClose');
if(tripDrawerClose) tripDrawerClose.addEventListener('click', tripCloseDrawer);
const tripCancelBtn = document.getElementById('tripCancelBtn');
if(tripCancelBtn) tripCancelBtn.addEventListener('click', tripCloseDrawer);
const tripDrawerBackdrop = document.getElementById('tripDrawerBackdrop');
if(tripDrawerBackdrop) tripDrawerBackdrop.addEventListener('click', tripCloseDrawer);
const tripSaveBtn = document.getElementById('tripSaveBtn');
if(tripSaveBtn) tripSaveBtn.addEventListener('click', tripSave);
const tripsNewBtn = document.getElementById('tripsNewBtn');
if(tripsNewBtn) tripsNewBtn.addEventListener('click', tripOpenCreate);

// Data Management > buses CRUD is wired further down (dm* handlers) — the
// superseded bm* inline-form wiring was removed with its panel.

// Data Management > stops CRUD
const stAddBtn = document.getElementById('stAddBtn');
if(stAddBtn) stAddBtn.addEventListener('click', () => openStopForm(null));
const stCancelBtn = document.getElementById('stCancelBtn');
if(stCancelBtn) stCancelBtn.addEventListener('click', closeStopForm);
const stSaveBtn = document.getElementById('stSaveBtn');
if(stSaveBtn) stSaveBtn.addEventListener('click', saveStop);
const stTableBody = document.getElementById('stTableBody');
if(stTableBody) stTableBody.addEventListener('click', e => {
    const btn = e.target.closest('button[data-act]');
    if(!btn) return;
    const id = btn.dataset.id;   // stop id is a string
    if(btn.dataset.act === 'edit'){ const s = stStops.find(x=>x.id===id); if(s) openStopForm(s); }
    else if(btn.dataset.act === 'del'){ deleteStop(id); }
});

// Data Management > stops search/filter (client-side → re-render only)
const stSearchEl = document.getElementById('stSearch');
if(stSearchEl) stSearchEl.addEventListener('input', e => { stSearch = e.target.value; renderStopsAdmin(); });
const stFilterActiveEl = document.getElementById('stFilterActive');
if(stFilterActiveEl) stFilterActiveEl.addEventListener('change', e => { stActiveOnly = e.target.value; renderStopsAdmin(); });
const stResetBtn = document.getElementById('stResetBtn');
if(stResetBtn) stResetBtn.addEventListener('click', () => {
    stSearch = ''; stActiveOnly = '';
    if(stSearchEl) stSearchEl.value = '';
    if(stFilterActiveEl) stFilterActiveEl.value = '';
    renderStopsAdmin();
});

// Data Management > routes search/filter (client-side → re-render only)
const rtSearchEl = document.getElementById('rtSearch');
if(rtSearchEl) rtSearchEl.addEventListener('input', e => { rtSearch = e.target.value; renderRoutesAdmin(); });
const rtFilterActiveEl = document.getElementById('rtFilterActive');
if(rtFilterActiveEl) rtFilterActiveEl.addEventListener('change', e => { rtActiveOnly = e.target.value; renderRoutesAdmin(); });
const rtResetBtn = document.getElementById('rtResetBtn');
if(rtResetBtn) rtResetBtn.addEventListener('click', () => {
    rtSearch = ''; rtActiveOnly = '';
    if(rtSearchEl) rtSearchEl.value = '';
    if(rtFilterActiveEl) rtFilterActiveEl.value = '';
    renderRoutesAdmin();
});

// Export buttons need no wiring here: one delegated handler reads the dataset
// and the format off the button that was clicked. See EXPORTS / runExport.

// Routes > map editor for the road geometry
const rtDrawToggle = document.getElementById('rtDrawToggle');
if(rtDrawToggle) rtDrawToggle.addEventListener('click', () => {
    const wrap = document.getElementById('rtDrawWrap');
    if(wrap && !wrap.hidden){ wrap.hidden = true; return; }   // toggle off
    rtOpenMapEditor();
});
const rtUndoBtn = document.getElementById('rtUndoBtn');
if(rtUndoBtn) rtUndoBtn.addEventListener('click', () => {
    if(rtDrawPts.length) rtRemoveVertex(rtDrawPts.length - 1);
});
const rtClearBtn = document.getElementById('rtClearBtn');
if(rtClearBtn) rtClearBtn.addEventListener('click', rtClearDrawing);

// Routes > itinerary editor (defines the line's path on create)
// Modifica del percorso di una linea esistente: carica il tracciato salvato
// nell'editor e lo rende modificabile. Il pannello dei tempi resta chiuso —
// in modifica gli orari non si scrivono a mano, li ricalcola la cascata.
const rtEditPathBtn = document.getElementById('rtEditPathBtn');
if(rtEditPathBtn) rtEditPathBtn.addEventListener('click', async () => {
    if(!rtEditId) return;
    rtEditPathBtn.disabled = true;
    const hint = document.getElementById('rtPathHint');
    try{
        const itn = document.getElementById('rtItinerary');
        if(itn) itn.hidden = false;
        const stopsList = document.getElementById('rtStops');
        if(stopsList){ stopsList.hidden = true; stopsList.innerHTML = ''; }
        const addBtn = document.getElementById('rtAddStopBtn');
        if(addBtn) addBtn.hidden = true;
        const head = document.querySelector('#rtItinerary .tt-manual-head .bm-lbl');
        if(head) head.textContent = 'Path and stops of this line';
        const itnHint = document.querySelector('#rtItinerary .rt-itn-hint');
        if(itnHint) itnHint.hidden = true;

        const info = await rtLoadExistingPath(rtEditId);
        if(hint){
            hint.textContent = info.tripCount
                ? `${info.tripCount} run(s) on this line will be re-timed if you change its stops.`
                : 'This line has no runs yet.';
        }
    }catch(e){
        if(hint) hint.textContent = 'Could not load the saved path of this line.';
    }finally{
        rtEditPathBtn.disabled = false;
    }
});

const rtAddStopBtn = document.getElementById('rtAddStopBtn');
if(rtAddStopBtn) rtAddStopBtn.addEventListener('click', () => itnAddStop('rtStops'));
const rtStopsEl = document.getElementById('rtStops');
if(rtStopsEl) rtStopsEl.addEventListener('click', e => {
    const del = e.target.closest('.tt-del-stop');
    if(!del) return;
    del.closest('.tt-manual-row').remove();
    itnRenumber('rtStops');
});
const ttDetailCloseBtn = document.getElementById('ttDetailCloseBtn');
if(ttDetailCloseBtn) ttDetailCloseBtn.addEventListener('click', () => {
    document.getElementById('ttDetail').hidden = true;
    ttOpenTripId = null;
});
const ttTimesSaveBtn = document.getElementById('ttTimesSaveBtn');
if(ttTimesSaveBtn) ttTimesSaveBtn.addEventListener('click', ttSaveTimes);
// Data Management > routes CRUD
const rtAddBtn = document.getElementById('rtAddBtn');
if(rtAddBtn) rtAddBtn.addEventListener('click', () => openRouteForm(null));
const rtCancelBtn = document.getElementById('rtCancelBtn');
if(rtCancelBtn) rtCancelBtn.addEventListener('click', closeRouteForm);
const rtSaveBtn = document.getElementById('rtSaveBtn');
if(rtSaveBtn) rtSaveBtn.addEventListener('click', saveRoute);
const rtTableBody = document.getElementById('rtTableBody');
if(rtTableBody) rtTableBody.addEventListener('click', e => {
    const btn = e.target.closest('button[data-act]');
    if(!btn) return;
    const id = btn.dataset.id;   // route id is a string
    if(btn.dataset.act === 'edit'){ const rt = rtRoutes.find(x=>x.id===id); if(rt) openRouteForm(rt); }
    else if(btn.dataset.act === 'del'){ deleteRoute(id); }
});

// Fleet Monitor > filters (route + service + min delay)
const routeFilterEl = document.getElementById('routeFilter');
if(routeFilterEl) routeFilterEl.addEventListener('change', e => setRouteFilter(e.target.value));

const serviceFilterEl = document.getElementById('serviceFilter');
if(serviceFilterEl) serviceFilterEl.addEventListener('change', e => setServiceFilter(e.target.value));

const idleRoutesEl = document.getElementById('idleRoutes');
if(idleRoutesEl) idleRoutesEl.addEventListener('change', e => {
    idleRoutesMode = e.target.value;
    updateRouteVisibility();    // pure redraw: no data is refetched
});

const delayFilterEl = document.getElementById('delayFilter');
if(delayFilterEl) delayFilterEl.addEventListener('input', e => {
    const v = parseInt(e.target.value, 10) || 0;
    const lbl = document.getElementById('delayFilterVal');
    if(lbl) lbl.textContent = v > 0 ? '≥' + v + 'm' : 'off';
    setDelayThreshold(v);
});

document.getElementById('presetBtnToday').addEventListener('click', e => setPreset(e.currentTarget, 'today'));
document.getElementById('presetBtnYesterday').addEventListener('click', e => setPreset(e.currentTarget, 'yesterday'));
document.getElementById('presetBtnWeek').addEventListener('click', e => setPreset(e.currentTarget, 'week'));
document.getElementById('presetBtnMonth').addEventListener('click', e => setPreset(e.currentTarget, 'month'));
document.getElementById('presetBtnLastMonth').addEventListener('click', e => setPreset(e.currentTarget, 'lastmonth'));
document.getElementById('presetBtnCustom').addEventListener('click', e => setPreset(e.currentTarget, 'custom'));

document.getElementById('applyFiltersBtn').addEventListener('click', applyFilters);
// The analytics panel exports through the same three buttons as every other
// table. The old "PDF report" printed the dashboard — chrome, sidebar and all,
// at whatever size the window happened to be — which is a screenshot, not a
// report; it is now the same laid-out A4 document the other tables produce.

const dmState = {
    search:     '',
    status:     '',
    routeId:    '',
    editingId:  null,   // null = creating, number = editing
    deletingId: null,
    routes:     [],
    // Last loaded bus list. Kept so a visibility toggle can resolve
    // busId -> antenna id without re-querying the API.
    buses:      []
};

/* ── Loading ──────────────────────────────────────────────────── */

async function dmLoadRoutes() {
    try {
        const r = await fetch(`${API}/buses/route-options`);
        if (!r.ok) return;
        dmState.routes = await r.json();

        const options = dmState.routes
            .map(rt => `<option value="${escHtml(rt.id)}">${escHtml(rt.label)}</option>`)
            .join('');

        // Only the filter dropdown remains — the drawer no longer lets you
        // assign a route, since the table derives it from the timetable.
        document.getElementById('dmFilterRoute').innerHTML =
            '<option value="">All routes</option><option value="UNASSIGNED">No trips</option>' + options;
    } catch (e) {
        console.warn('Could not load route options', e);
    }
}

async function dmLoadBuses() {
    const body = document.getElementById('dmTableBody');
    body.innerHTML = '<tr><td colspan="9" class="dm-empty">Loading…</td></tr>';

    const params = new URLSearchParams();
    if (dmState.search)  params.set('search',  dmState.search);
    if (dmState.status)  params.set('status',  dmState.status);
    if (dmState.routeId) params.set('routeId', dmState.routeId);

    try {
        const r = await fetch(`${API}/buses?${params.toString()}`);
        if (!r.ok) throw new Error(r.status);
        dmState.buses = await r.json();
        dmRenderTable(dmState.buses);
    } catch (e) {
        body.innerHTML = '<tr><td colspan="9" class="dm-empty">Could not load buses.</td></tr>';
        document.getElementById('dmCount').textContent = '';
    }
}

/* ── Rendering ────────────────────────────────────────────────── */

/**
 * What the bus is doing RIGHT NOW, from the live feed — deliberately
 * separate from the registry `status` column beside it.
 *
 * `status` answers "is this vehicle part of the working fleet?"
 * (ACTIVE / INACTIVE / MAINTENANCE — set by the fleet manager, persisted).
 * This answers "is it out there on a run?" and is derived from telemetry,
 * so a serviceable ACTIVE bus parked between scheduled runs correctly reads
 * ACTIVE + NO TRIP rather than the two being conflated.
 *
 * Reads the same vehicleData the Fleet Monitor polls, so it costs no extra
 * request; it refreshes whenever the Data Management table is reloaded.
 */
function dmLivePill(b) {
    if (!b.currentVehicleId)
        return '<span class="dm-muted">no unit</span>';   // nothing transmits for this bus

    const v = vehicleData[b.currentVehicleId];
    if (!v)
        return '<span class="dm-pill dm-pill-inactive">OFFLINE</span>';   // unit fitted, silent

    return v.trip_id
        ? '<span class="dm-pill dm-pill-active">ON TRIP</span>'
        : '<span class="dm-pill dm-pill-maintenance">NO TRIP</span>';     // moving or idle, unscheduled
}

/* ── Active Trips ────────────────────────────────────────────────
   Operational view: what the timetable says should be running now, and
   how each trip is actually progressing. Driven by the timetable rather
   than by telemetry, so a scheduled trip whose bus has gone silent still
   appears — flagged NO SIGNAL, which is the row worth noticing.

   A trip that has been seen reaching its last stop is marked COMPLETED and
   stays listed for 15 minutes, so the end of a run can be reviewed instead
   of disappearing the moment it happens. */

const TRIPS_COLSPAN = 11;
const TRIPS_REFRESH_MS = 30000;
let tripsTimer = null;      // 30 s auto-refresh, only while the tab is open

function tripsStartAutoRefresh() {
    tripsStopAutoRefresh();                       // never stack intervals
    tripsTimer = setInterval(tripsLoad, TRIPS_REFRESH_MS);
}

function tripsStopAutoRefresh() {
    if (tripsTimer) { clearInterval(tripsTimer); tripsTimer = null; }
}

// Last payload, so filtering costs nothing and the edit drawer can render a
// trip's details without refetching.
let tripsLast = [];

const tripsFilter = { search: '', phase: '', status: '', span: '', route: '', bus: '',
                      from: '', to: '' };

async function tripsLoad() {
    const body = document.getElementById('tripsTableBody');
    if (!body) return;
    try {
        // The whole service day in one call. It is a few dozen rows, so
        // filtering happens in the browser — instant, and no round-trip per
        // keystroke in the search box.
        const r = await fetch(`${API}/trips`);
        if (!r.ok) throw new Error(r.status);
        tripsLast = await r.json();
        tripsFillFilterOptions(tripsLast);
        tripsApplyFilter();
    } catch (e) {
        body.innerHTML = `<tr><td colspan="${TRIPS_COLSPAN}" class="dm-empty">`
            + 'Could not load trips.</td></tr>';
        document.getElementById('tripsCount').textContent = '';
    }
}

/** Route and bus lists come from the data, so they never offer an empty choice. */
function tripsFillFilterOptions(trips) {
    const fill = (id, pairs, current) => {
        const sel = document.getElementById(id);
        if (!sel) return;
        const first = sel.options[0] ? sel.options[0].outerHTML : '';
        sel.innerHTML = first + pairs
            .map(([v, label]) => `<option value="${escHtml(v)}">${escHtml(label)}</option>`)
            .join('');
        sel.value = current;          // survive the 30 s refresh
    };

    const routes = [...new Map(trips.filter(t => t.route_id)
        .map(t => [t.route_id, t.route_name || t.route_id])).entries()]
        .sort((a, b) => a[1].localeCompare(b[1]));

    const buses = [...new Map(trips.filter(t => t.bus_id != null)
        .map(t => [String(t.bus_id),
            t.plate + (t.vehicle_id ? ` (${t.vehicle_id})` : '')])).entries()]
        .sort((a, b) => a[1].localeCompare(b[1]));

    fill('tripsRoute', routes, tripsFilter.route);
    fill('tripsBus',   buses,  tripsFilter.bus);
}

/** Minutes since midnight, local time — the same clock the timetable uses. */
function tripsNowMinutes() {
    const d = new Date();
    return d.getHours() * 60 + d.getMinutes();
}

function tripsMatches(t) {
    const f = tripsFilter;
    if (f.phase  && t.phase  !== f.phase)  return false;
    if (f.status && t.status !== f.status) return false;
    if (f.route  && t.route_id !== f.route) return false;
    if (f.bus    && String(t.bus_id) !== f.bus) return false;

    if (f.span) {
        const start = tripsMinutesBetween('00:00', t.start_time);
        if (start === null) return false;

        if (f.span === 'custom') {
            // Absolute window, unlike the presets below which are relative to now.
            // A trip counts as inside it when the two OVERLAP: asking "what runs
            // between 14:00 and 15:00" must also return the run that left at 13:50
            // and is still going, not only those departing inside the hour.
            const end = tripsMinutesBetween('00:00', t.end_time);
            const from = tripsMinutesBetween('00:00', f.from);
            const to   = f.to ? tripsMinutesBetween('00:00', f.to) : from;
            if (from === null) return true;                 // nothing chosen yet
            const tripEnd = (end === null || end < start) ? start : end;
            if (tripEnd < from || start > to) return false;  // no overlap
        } else {
            const now = tripsNowMinutes();
            if (f.span === 'past') {
                if (start >= now) return false;
            } else if (start < now || start > now + parseInt(f.span, 10)) {
                return false;
            }
        }
    }

    if (f.search) {
        const hay = [t.trip_id, t.route_name, t.route_id, t.plate, t.vehicle_id]
            .filter(Boolean).join(' ').toLowerCase();
        if (!hay.includes(f.search)) return false;
    }
    return true;
}

function tripsApplyFilter() {
    tripsRender(tripsLast.filter(tripsMatches));
}

function tripsRender(trips) {
    const body  = document.getElementById('tripsTableBody');
    const count = document.getElementById('tripsCount');
    const when  = document.getElementById('tripsUpdated');

    if (!trips.length) {
        // Distinguish "nothing matches your filter" from "no service":
        // the first is undone by clearing, the second is a fact about the day.
        const filtered = Object.values(tripsFilter).some(Boolean);
        body.innerHTML = `<tr><td colspan="${TRIPS_COLSPAN}" class="dm-empty">`
            + (filtered ? 'No trips match these filters.'
                : 'No trips in the timetable.')
            + '</td></tr>';
        count.textContent = '';
        when.textContent  = 'checked ' + new Date().toLocaleTimeString();
        return;
    }

    body.innerHTML = trips.map(t => `
        <tr class="${tripsRowClass(t)}">
            <td class="dm-muted dm-mono">${escHtml(t.trip_id)}</td>
            <td>${escHtml(t.route_name) || '<span class="dm-muted">—</span>'}</td>
            <td>${t.plate ? `<span class="dm-plate">${escHtml(t.plate)}</span>`
        : '<span class="dm-muted">unassigned</span>'}</td>
            <td class="dm-mono dm-muted">${t.vehicle_id
        ? escHtml(t.vehicle_id)
        : '<span class="dm-muted">no antenna</span>'}</td>
            <td class="dm-mono">${escHtml(t.start_time)}</td>
            <td class="dm-mono dm-muted">${escHtml(t.end_time)}</td>
            <td class="dm-mono">${tripsActualEndCell(t)}</td>
            <td>${tripsProgressCell(t)}</td>
            <td>${tripsPhasePill(t)}</td>
            <td>${tripsStatusPill(t) || '<span class="dm-muted">—</span>'}</td>
            <td class="dm-right">
                <button class="dm-row-btn" data-trip-edit="${escHtml(t.trip_id)}">Edit</button>
                <button class="dm-row-btn" data-trip-stops="${escHtml(t.trip_id)}"
                        title="Stop-by-stop times of this trip">Stops</button>
                <button class="dm-row-btn dm-row-del" data-trip-del="${escHtml(t.trip_id)}"
                        title="Delete this trip">Delete</button>
            </td>
        </tr>`).join('');

    const n = p => trips.filter(t => t.phase === p).length;
    const parts = [];
    if (n('ACTIVE'))      parts.push(`${n('ACTIVE')} active`);
    if (n('NOT_STARTED')) parts.push(`${n('NOT_STARTED')} not started`);
    if (n('FINISHED'))    parts.push(`${n('FINISHED')} finished`);
    count.textContent = parts.join(' · ')
        + (trips.length !== tripsLast.length
            ? `   (${trips.length} of ${tripsLast.length})` : '');
    when.textContent  = 'updated ' + new Date().toLocaleTimeString();
    applyDynStyles(body);     // CSP-safe: widths are data-attributes, set via CSSOM
}

/**
 * Actual finish, against the scheduled one.
 *
 * Blank while a trip is still running. Blank ALSO when a trip ended without
 * being witnessed at its final stop — the bus passed too far away, or the
 * arrival never reached InfluxDB. "We did not see it finish" and "it has
 * not finished" look the same here, which is a real limitation of measuring
 * completion from observed arrivals.
 */
function tripsActualEndCell(t) {
    if (!t.actual_end_time) return '<span class="dm-muted">—</span>';
    const diff = tripsMinutesBetween(t.end_time, t.actual_end_time);
    const tag  = (diff === null || diff === 0) ? ''
        : `<div class="trip-sub">${diff > 0 ? '+' : ''}${diff}m vs sched.</div>`;
    return `${escHtml(t.actual_end_time)}${tag}`;
}

/** "HH:mm" − "HH:mm" in minutes; null if either is unparseable. */
function tripsMinutesBetween(scheduled, actual) {
    const p = s => {
        const m = /^(\d{2}):(\d{2})$/.exec(s || '');
        return m ? (+m[1]) * 60 + (+m[2]) : null;
    };
    const a = p(actual), s = p(scheduled);
    if (a === null || s === null) return null;
    return a - s;
}

/** Progress measured in stops actually reached, not time elapsed. */
function tripsProgressCell(t) {
    // A trip that has not departed has no progress to show. An empty bar
    // at 0/12 would read as "stuck at the terminus" rather than "later".
    if (t.phase === 'NOT_STARTED')
        return `<span class="dm-muted">${t.stops_total} stops</span>`;

    const pct = Math.max(0, Math.min(100, t.progress_pct || 0));
    const colour = t.status === 'COMPLETED' ? '#3B82F6'
        // A silent bus keeps whatever progress it last reported;
        // the bar is dimmed so a stalled trip does not read as a
        // healthy one.
        : (!t.live ? '#4B5563'
            : (t.status === 'SIGNIFICANTLY_LATE' ? '#EF4444'
                : (t.status === 'SLIGHTLY_LATE' ? '#F59E0B' : '#22C55E')));

    // A count inferred from the clock ("by now it should have passed stop
    // 4") is marked, because it otherwise looks exactly like a measured one.
    const counted = t.progress_observed
        ? `${t.stops_done}/${t.stops_total} stops`
        : `~${t.stops_done}/${t.stops_total} stops <span class="trip-inferred"
                 title="Estimated from the timetable — no arrival observed yet">est.</span>`;

    const next = t.next_stop_name
        ? `<div class="trip-sub">next: ${escHtml(t.next_stop_name)}</div>`
        : '';
    return `<div class="cbar"><div class="cbar-fill" data-bg="${colour}" data-width-pct="${pct}"></div></div>
                <div class="trip-sub">${counted}</div>${next}`;
}

/** Row tint by phase, so the shape of the day reads without squinting. */
function tripsRowClass(t) {
    if (t.phase === 'FINISHED')    return 'trip-done';
    if (t.phase === 'NOT_STARTED') return 'trip-future';
    return 'trip-active';
}

function tripsPhasePill(t) {
    const map = {
        ACTIVE:      ['trip-pill-active', 'ACTIVE'],
        NOT_STARTED: ['trip-pill-future', 'NOT STARTED'],
        // Past its scheduled end but the bus is still reporting and has
        // not reached the terminus: late, not done.
        OVERDUE:     ['trip-pill-overdue','OVERDUE'],
        FINISHED:    ['trip-pill-done',   'FINISHED']
    };
    const [cls, label] = map[t.phase] || ['dm-pill-inactive', '—'];
    const pill = `<span class="dm-pill ${cls}">${label}</span>`;
    return pill + tripsHealthPill(t);
}

/**
 * Vehicle health, shown next to the phase. Only STALLED: "no signal" is
 * already covered by the status column, and would otherwise appear twice.
 * a trip can be OVERDUE *because* the bus is STALLED, and the pair says
 * both what is happening and why. Nothing is drawn when all is well.
 */
function tripsHealthPill(t) {
    if (t.health === 'STALLED')
        return ' <span class="dm-pill trip-pill-stalled" title="Reporting, but has not moved for 10 minutes">STALLED</span>';
    return '';
}

function tripsStatusPill(t) {
    // Before departure there is nothing to say about punctuality, and the
    // phase pill beside it already says the trip has not left.
    if (!t.status) return '';

    const d = t.delay_minutes;
    const delay = (typeof d === 'number' && d !== 0)
        ? ` <span class="dm-muted">${d > 0 ? '+' : ''}${d}m</span>` : '';

    if (t.status === 'COMPLETED')
        return `<span class="dm-pill trip-pill-done">COMPLETED</span>${delay}`;
    if (t.status === 'NO_SIGNAL' || !t.live)
        return '<span class="dm-pill dm-pill-inactive">NO SIGNAL</span>';

    const label = {
        ON_TIME: 'ON TIME', EARLY: 'EARLY', SLIGHTLY_LATE: 'SLIGHTLY LATE',
        SIGNIFICANTLY_LATE: 'LATE', NO_TRIP: 'NO TRIP', UNKNOWN: 'LIVE'
    }[t.status] || escHtml(t.status || '—');
    const cls = (t.status === 'ON_TIME' || t.status === 'EARLY') ? 'dm-pill-active'
        : (t.status === 'UNKNOWN' ? 'dm-pill-inactive' : 'dm-pill-maintenance');
    return `<span class="dm-pill ${cls}">${label}</span>${delay}`;
}

/* ── Active Trips: edit drawer ──────────────────────────────────── */

let tripEditing = null;      // trip_id open in the drawer, null while creating
let tripMode    = 'edit';    // 'edit' | 'create'

/**
 * Show the fields belonging to one mode and hide the other's.
 *
 * Toggled through the CSSOM rather than a style="" attribute, because the
 * page's CSP drops 'unsafe-inline' from style-src.
 */
function tripSetMode(mode) {
    tripMode = mode;
    const editOnly   = document.getElementById('tripFormEditOnly');
    const createOnly = document.getElementById('tripFormCreateOnly');
    if (editOnly)   editOnly.style.display   = mode === 'edit'   ? '' : 'none';
    if (createOnly) createOnly.style.display = mode === 'create' ? '' : 'none';

    // The permanence warning applies to both, but says different things.
    const warn = document.getElementById('tripFormPermanentWarning');
    if (warn) warn.innerHTML = mode === 'create'
        ? '<strong>This trip is permanent.</strong> The timetable has no per-day '
        + 'version, so this run is added to <em>every day</em>, not just today.'
        : '<strong>This change is permanent.</strong> The timetable has no per-day '
        + 'version, so this trip will run with the new settings <em>every day</em>, '
        + 'not just today. For a one-off swap during a breakdown, wait for the '
        + 'disruption feature.';
}

/**
 * New trip.
 *
 * Routes are offered only where a trip already exists, because the new
 * trip's timings are copied from one — a route with no template cannot be
 * scheduled, and listing it would produce an error instead of a trip.
 */
async function tripOpenCreate() {
    tripEditing = null;
    tripSetMode('create');
    tripsStopAutoRefresh();

    document.getElementById('tripDrawerTitle').textContent = 'New trip';
    document.getElementById('tripFormError').textContent   = '';

    const dep = document.getElementById('tripFormDeparture');
    dep.disabled = false;
    dep.classList.remove('dm-input-locked');
    dep.value = '';
    document.getElementById('tripFormDepartureHint').textContent =
        'The bus must be free, with at least 15 minutes either side of its other trips.';
    document.getElementById('tripFormBusHint').textContent =
        'Any bus may be chosen; the turnaround check runs when you save.';

    const routes = [...new Map(tripsLast.filter(t => t.route_id)
        .map(t => [t.route_id, t.route_name || t.route_id])).entries()]
        .sort((a, b) => a[1].localeCompare(b[1]));
    document.getElementById('tripFormRouteSelect').innerHTML =
        '<option value="">— Choose a route —</option>'
        + routes.map(([id, label]) =>
            `<option value="${escHtml(id)}">${escHtml(label)}</option>`).join('');

    // Every bus, not just free ones: "free" depends on a departure time the
    // operator has not chosen yet.
    const sel = document.getElementById('tripFormBus');
    sel.innerHTML = '<option value="">Loading…</option>';
    tripOpenDrawer();
    try {
        const buses = await (await fetch(`${API}/buses`)).json();
        sel.innerHTML = '<option value="">— Choose a bus —</option>'
            + buses.map(b => {
                const notes = [];
                if (b.status !== 'ACTIVE')  notes.push(String(b.status).toLowerCase());
                if (!b.currentVehicleId)    notes.push('no antenna');
                const suffix = notes.length ? ` — ${notes.join(', ')}` : '';
                return `<option value="${b.busId}">${escHtml(b.targa)}${escHtml(suffix)}</option>`;
            }).join('');
    } catch (e) {
        sel.innerHTML = '<option value="">Could not load buses</option>';
    }
}

function tripOpenDrawer() {
    document.getElementById('tripDrawer').classList.add('open');
    document.getElementById('tripDrawer').setAttribute('aria-hidden', 'false');
    document.getElementById('tripDrawerBackdrop').classList.add('open');
}

/** DOM only. Safe to call while panels are being switched. */
function tripHideDrawer() {
    const d = document.getElementById('tripDrawer');
    if (!d) return;
    d.classList.remove('open');
    d.setAttribute('aria-hidden', 'true');
    document.getElementById('tripDrawerBackdrop').classList.remove('open');
    document.getElementById('tripFormError').textContent = '';
    tripEditing = null;
    tripMode    = 'edit';   // next open starts from a known state
}

function tripCloseDrawer() {
    tripHideDrawer();
    // The 30 s poll is paused while editing so the row cannot move underneath
    // the operator; resume only if the panel is still the visible one.
    const panel = document.getElementById('dm-panel-trips');
    if (panel && panel.classList.contains('active')) tripsStartAutoRefresh();
}

async function tripOpenEdit(tripId) {
    const t = tripsLast.find(x => x.trip_id === tripId);
    if (!t) return;

    tripEditing = tripId;
    tripSetMode('edit');
    tripsStopAutoRefresh();   // don't let a refresh redraw under the form

    document.getElementById('tripFormBusHint').textContent =
        'Only buses with no other trip during this window are listed.';
    document.getElementById('tripDrawerTitle').textContent = 'Edit trip';
    document.getElementById('tripFormId').textContent      = t.trip_id;
    document.getElementById('tripFormRoute').textContent   = t.route_name || '—';
    document.getElementById('tripFormWindow').textContent  =
        `${t.start_time} – ${t.end_time}`
        + (t.actual_end_time ? `  (finished ${t.actual_end_time})` : '');

    // Departure is editable only before the bus leaves. Moving a run that
    // is already under way would shift stops the bus has physically passed,
    // silently changing the meaning of every delay measured against them.
    const dep  = document.getElementById('tripFormDeparture');
    const hint = document.getElementById('tripFormDepartureHint');
    const canMove = t.phase === 'NOT_STARTED';
    dep.value    = t.start_time || '';
    dep.disabled = !canMove;
    dep.classList.toggle('dm-input-locked', !canMove);
    hint.textContent = canMove
        ? 'The whole trip shifts by the same amount, so its running time is unchanged.'
        : (t.phase === 'ACTIVE'
            ? 'This trip is already running and cannot be rescheduled.'
            : 'This trip has finished and cannot be rescheduled.');
    document.getElementById('tripFormProgress').textContent =
        `${t.stops_done}/${t.stops_total} stops`
        + (t.progress_observed ? '' : ' (estimated)');
    document.getElementById('tripFormError').textContent = '';

    const sel = document.getElementById('tripFormBus');
    sel.innerHTML = '<option value="">Loading…</option>';
    tripOpenDrawer();

    try {
        const r = await fetch(`${API}/trips/${encodeURIComponent(tripId)}/available-buses`);
        if (!r.ok) throw new Error(r.status);
        const buses = await r.json();

        if (!buses.length) {
            sel.innerHTML = '<option value="">No bus is free for this window</option>';
            return;
        }
        sel.innerHTML = buses.map(b => {
            const notes = [];
            if (b.current)                notes.push('current');
            if (b.status !== 'ACTIVE')    notes.push(String(b.status).toLowerCase());
            if (b.no_antenna)             notes.push('no antenna');
            const suffix = notes.length ? ` — ${notes.join(', ')}` : '';
            return `<option value="${b.bus_id}" ${b.current ? 'selected' : ''}>`
                + `${escHtml(b.plate)}${escHtml(suffix)}</option>`;
        }).join('');
    } catch (e) {
        sel.innerHTML = '<option value="">Could not load buses</option>';
    }
}

/**
 * Create the trip.
 *
 * All three fields are required and validated here only for the obvious
 * omissions — the turnaround and midnight rules live on the server, which
 * is the only place that can see the rest of the timetable.
 */
async function tripSaveNew() {
    const err     = document.getElementById('tripFormError');
    const routeId = document.getElementById('tripFormRouteSelect').value;
    const busId   = parseInt(document.getElementById('tripFormBus').value, 10);
    const dep     = document.getElementById('tripFormDeparture').value;

    if (!routeId)                 { err.textContent = 'Choose a route.';          return; }
    if (!Number.isInteger(busId)) { err.textContent = 'Choose a bus.';            return; }
    if (!dep)                     { err.textContent = 'Choose a departure time.'; return; }
    err.textContent = '';

    try {
        const r = await fetch(`${API}/trips`, {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ route_id: routeId, bus_id: busId, departure: dep })
        });
        if (!r.ok) {
            const data = await r.json().catch(() => ({}));
            err.textContent = data.message || `Could not create the trip (${r.status}).`;
            return;
        }
        tripCloseDrawer();
        tripsLoad();
    } catch (e) {
        err.textContent = 'Could not reach the server.';
    }
}

/**
 * Save the bus and, for a trip that has not left, the departure time.
 *
 * Two endpoints rather than one: they are independent changes with
 * different rules, and the server rejects a departure move on a running
 * trip regardless of what the form allows.
 *
 * The bus goes FIRST on purpose. The departure endpoint refuses a time that
 * would overlap another trip of the same bus, so it has to run against the
 * bus the trip is going to have, not the one it is leaving behind.
 */
async function tripSave() {
    if (tripMode === 'create') return tripSaveNew();
    if (!tripEditing) return;
    const err   = document.getElementById('tripFormError');
    const busId = parseInt(document.getElementById('tripFormBus').value, 10);
    const dep   = document.getElementById('tripFormDeparture');
    const t     = tripsLast.find(x => x.trip_id === tripEditing);

    if (!Number.isInteger(busId)) {
        err.textContent = 'Choose a bus first.';
        return;
    }
    err.textContent = '';

    const put = async (url) => {
        const r = await fetch(url, { method: 'PUT' });
        if (r.ok) return null;
        const data = await r.json().catch(() => ({}));
        return data.message || `Save failed (${r.status}).`;
    };

    try {
        const id = encodeURIComponent(tripEditing);

        if (!t || busId !== t.bus_id) {
            const msg = await put(`${API}/trips/${id}/bus?busId=${busId}`);
            if (msg) { err.textContent = msg; return; }
        }

        // Only send a time the user actually changed: an unchanged value is
        // a no-op server-side, but sending it would surface a "already
        // departed" error on trips nobody was trying to move.
        if (!dep.disabled && dep.value && t && dep.value !== t.start_time) {
            const msg = await put(`${API}/trips/${id}/departure?time=${encodeURIComponent(dep.value)}`);
            if (msg) {
                // The bus change above may already have gone through, so say
                // what did and did not happen rather than just "failed".
                err.textContent = msg + ' The bus change was saved.';
                tripsLoad();
                return;
            }
        }

        tripCloseDrawer();
        tripsLoad();      // reflect it immediately rather than waiting 30 s
    } catch (e) {
        err.textContent = 'Could not reach the server.';
    }
}

function dmStatusPill(status) {
    const map = {
        ACTIVE:      ['dm-pill-active',      'ACTIVE'],
        INACTIVE:    ['dm-pill-inactive',    'INACTIVE'],
        MAINTENANCE: ['dm-pill-maintenance', 'MAINTENANCE']
    };
    const [cls, label] = map[status] || ['dm-pill-inactive', escHtml(status || '—')];
    return `<span class="dm-pill ${cls}">${label}</span>`;
}

/**
 * Route cell. The value is derived server-side from the timetable
 * (antenna → bus → trip in service now), so it is never a stale manual
 * assignment:
 *   routeLive === true  → the line the bus is running right now
 *   routeLive === false → outside service hours: the lines it serves per
 *                         the timetable, shown muted
 *   no route at all     → the bus has no trips assigned
 */
function dmRouteCell(b) {
    if (!b.routeName) return '<span class="dm-muted">No trips</span>';
    if (b.routeLive) return escHtml(b.routeName);
    return `<span class="dm-muted" title="Not in service now — lines from the timetable">${escHtml(b.routeName)} <span class="dm-route-tag">scheduled</span></span>`;
}

let dmBuses = [];   // last rendered rows — reused by the CSV export

function dmRenderTable(buses) {
    dmBuses = buses || [];
    const body = document.getElementById('dmTableBody');
    const count = document.getElementById('dmCount');

    if (!buses.length) {
        const filtering = dmState.search || dmState.status || dmState.routeId;
        body.innerHTML = `<tr><td colspan="9" class="dm-empty">${
            filtering ? 'No buses match these filters.' : 'No buses yet — click “+ New bus”.'
        }</td></tr>`;
        count.textContent = '';
        return;
    }

    body.innerHTML = buses.map(b => `
        <tr>
            <td class="dm-muted">#${b.busId}</td>
            <td class="dm-plate">${escHtml(b.targa)}${b.wheelchairAccessible ? ' ♿' : ''}</td>
            <td>${dmRouteCell(b)}</td>
            <td class="dm-num">${b.numeroPosti}</td>
            <td>${dmStatusPill(b.status)}</td>
            <td>${dmLivePill(b)}</td>
            <td class="dm-muted">${b.currentVehicleId ? escHtml(b.currentVehicleId) : '—'}</td>
            <td class="dm-center">
                <label class="dm-switch">
                    <input type="checkbox" data-vis-id="${b.busId}" ${b.mapVisible ? 'checked' : ''}>
                    <span class="dm-slider"></span>
                </label>
            </td>
            <td class="dm-right">
                <button class="dm-row-btn" data-edit-id="${b.busId}">Edit</button>
                <button class="dm-row-btn dm-row-del" data-del-id="${b.busId}"
                        data-plate="${escHtml(b.targa)}">Delete</button>
            </td>
        </tr>`).join('');

    count.textContent = `${buses.length} bus${buses.length === 1 ? '' : 'es'}`;
}

/* ── Drawer ───────────────────────────────────────────────────── */

function dmOpenDrawer() {
    document.getElementById('dmDrawer').classList.add('open');
    document.getElementById('dmDrawer').setAttribute('aria-hidden', 'false');
    document.getElementById('dmDrawerBackdrop').classList.add('open');
    setTimeout(() => document.getElementById('dmTarga').focus(), 260);
}

function dmCloseDrawer() {
    document.getElementById('dmDrawer').classList.remove('open');
    document.getElementById('dmDrawer').setAttribute('aria-hidden', 'true');
    document.getElementById('dmDrawerBackdrop').classList.remove('open');
    dmHideError();
}

function dmOpenCreate() {
    dmState.editingId = null;
    document.getElementById('dmDrawerTitle').textContent = 'New bus';
    document.getElementById('dmTarga').value      = '';
    document.getElementById('dmCapacity').value   = '';
    document.getElementById('dmStatus').value     = 'ACTIVE';
    document.getElementById('dmVehicleId').value  = '';
    document.getElementById('dmAccessible').checked = false;
    document.getElementById('dmMapVisible').checked = true;
    dmHideError();
    dmOpenDrawer();
}

async function dmOpenEdit(id) {
    try {
        const r = await fetch(`${API}/buses/${id}`);
        if (!r.ok) throw new Error(r.status);
        const b = await r.json();

        dmState.editingId = b.busId;
        document.getElementById('dmDrawerTitle').textContent = 'Edit bus ' + b.targa;
        document.getElementById('dmTarga').value      = b.targa || '';
        document.getElementById('dmCapacity').value   = b.numeroPosti ?? '';
        document.getElementById('dmStatus').value     = b.status || 'ACTIVE';
        document.getElementById('dmVehicleId').value  = b.currentVehicleId || '';
        document.getElementById('dmAccessible').checked = !!b.wheelchairAccessible;
        document.getElementById('dmMapVisible').checked = !!b.mapVisible;
        dmHideError();
        dmOpenDrawer();
    } catch (e) {
        alert('Could not load that bus.');
    }
}

function dmShowError(msg) {
    const el = document.getElementById('dmFormError');
    el.textContent = msg;
    el.classList.add('show');
}

function dmHideError() {
    document.getElementById('dmFormError').classList.remove('show');
}

/* ── Save ─────────────────────────────────────────────────────── */

async function dmSave() {
    dmHideError();

    const targa    = document.getElementById('dmTarga').value.trim();
    const capacity = parseInt(document.getElementById('dmCapacity').value, 10);

    if (!targa)                     return dmShowError('Plate is required.');
    if (!capacity || capacity < 1)  return dmShowError('Capacity must be at least 1.');

    const payload = {
        targa:                targa,
        numeroPosti:          capacity,
        // routeId intentionally omitted: derived server-side, not editable.
        status:               document.getElementById('dmStatus').value,
        currentVehicleId:     document.getElementById('dmVehicleId').value.trim() || null,
        wheelchairAccessible: document.getElementById('dmAccessible').checked,
        mapVisible:           document.getElementById('dmMapVisible').checked
    };

    const btn = document.getElementById('dmSaveBtn');
    btn.disabled = true;
    btn.textContent = 'Saving…';

    try {
        const editing = dmState.editingId !== null;
        const r = await fetch(editing ? `${API}/buses/${dmState.editingId}` : `${API}/buses`, {
            method:  editing ? 'PUT' : 'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify(payload)
        });

        if (!r.ok) {
            if (r.status === 403) return dmShowError('You need FLEET_MANAGER rights to do this.');
            const data = await r.json().catch(() => ({}));
            return dmShowError(data.message || `Save failed (${r.status}).`);
        }

        dmCloseDrawer();
        dmLoadBuses();
    } catch (e) {
        dmShowError('Could not reach the server.');
    } finally {
        btn.disabled = false;
        btn.textContent = 'Save';
    }
}

/* ── Map-visibility toggle ────────────────────────────────────── */

async function dmToggleVisibility(id, visible, checkbox) {
    try {
        const r = await fetch(`${API}/buses/${id}/visibility?visible=${visible}`, { method: 'PUT' });
        if (!r.ok) throw new Error(r.status);

        // The database is updated, but the map reads map_visible from the
        // /vehicles poll — so without this the change only showed up on the
        // next refresh, up to ~15 s later. Apply it to the local state at
        // once so switching to Fleet Monitor already shows the result.
        // The next poll then confirms the same value; nothing fights.
        const bus = dmState.buses.find(b => b.busId === id);
        const vid = bus && bus.currentVehicleId;
        if (bus) bus.mapVisible = visible;                  // keep the table honest too
        if (vid && vehicleData[vid]) {
            vehicleData[vid].map_visible = visible;
            applyBusVisibility(vid);       // add/remove the marker now
            updateRouteVisibility();       // its route may gain/lose its last bus
            updateFleet(Object.values(vehicleData));   // sidebar counts + list
        }
    } catch (e) {
        checkbox.checked = !visible;          // revert the switch on failure
        alert('Could not update map visibility.');
    }
}

/* ── Delete ───────────────────────────────────────────────────── */

function dmAskDelete(id, plate) {
    dmState.deletingId = id;
    document.getElementById('dmConfirmText').textContent =
        `Delete bus ${plate}? This removes it from the registry permanently.`;
    document.getElementById('dmConfirmBackdrop').classList.add('open');
}

function dmCloseConfirm() {
    dmState.deletingId = null;
    document.getElementById('dmConfirmBackdrop').classList.remove('open');
}

async function dmDoDelete() {
    const id = dmState.deletingId;
    if (id === null) return;
    try {
        const r = await fetch(`${API}/buses/${id}`, { method: 'DELETE' });
        if (r.status === 403) { alert('You need FLEET_MANAGER rights to delete buses.'); return; }
        if (!r.ok && r.status !== 204) {
            // The server explains *why* a delete is refused (e.g. "assigned to
            // 24 trips"). Show that instead of a bare status code.
            const data = await r.json().catch(() => ({}));
            alert(data.message || data.error || `Delete failed (${r.status}).`);
            return;
        }
        dmCloseConfirm();
        dmLoadBuses();
    } catch (e) {
        alert('Could not reach the server.');
    }
}

/* ── Filters ──────────────────────────────────────────────────── */

let dmSearchTimer = null;

function dmApplySearch(value) {
    clearTimeout(dmSearchTimer);
    dmSearchTimer = setTimeout(() => {
        dmState.search = value.trim();
        dmLoadBuses();
    }, 250);                                  // debounce so we don't hit the API per keystroke
}

function dmResetFilters() {
    dmState.search = dmState.status = dmState.routeId = '';
    document.getElementById('dmSearch').value = '';
    document.getElementById('dmFilterStatus').value = '';
    document.getElementById('dmFilterRoute').value = '';
    dmLoadBuses();
}

/* ── Wiring (all addEventListener — CSP-safe) ─────────────────── */

document.getElementById('topBtnDataMgmt').addEventListener('click', e => {
    switchTopView('data-management', e.currentTarget);
    // Always land on Buses so the view has a defined starting panel.
    switchDmPanel('dm-panel-buses', document.getElementById('dmTabBuses'));
});

document.getElementById('dmSearch').addEventListener('input', e => dmApplySearch(e.target.value));
document.getElementById('dmFilterStatus').addEventListener('change', e => {
    dmState.status = e.target.value; dmLoadBuses();
});
document.getElementById('dmFilterRoute').addEventListener('change', e => {
    dmState.routeId = e.target.value; dmLoadBuses();
});
document.getElementById('dmResetBtn').addEventListener('click', dmResetFilters);
document.getElementById('dmNewBtn').addEventListener('click', dmOpenCreate);

document.getElementById('dmDrawerClose').addEventListener('click', dmCloseDrawer);
document.getElementById('dmCancelBtn').addEventListener('click', dmCloseDrawer);
document.getElementById('dmDrawerBackdrop').addEventListener('click', dmCloseDrawer);
document.getElementById('dmSaveBtn').addEventListener('click', dmSave);

document.getElementById('dmConfirmCancel').addEventListener('click', dmCloseConfirm);
document.getElementById('dmConfirmOk').addEventListener('click', dmDoDelete);
document.getElementById('dmConfirmBackdrop').addEventListener('click', e => {
    if (e.target.id === 'dmConfirmBackdrop') dmCloseConfirm();
});

// Rows are generated at runtime → delegate from the stable <tbody>
document.getElementById('dmTableBody').addEventListener('click', e => {
    const edit = e.target.closest('[data-edit-id]');
    if (edit) { dmOpenEdit(Number(edit.dataset.editId)); return; }

    const del = e.target.closest('[data-del-id]');
    if (del) { dmAskDelete(Number(del.dataset.delId), del.dataset.plate); }
});

document.getElementById('dmTableBody').addEventListener('change', e => {
    const cb = e.target.closest('input[data-vis-id]');
    if (cb) dmToggleVisibility(Number(cb.dataset.visId), cb.checked, cb);
});

// Esc closes whatever is open
document.addEventListener('keydown', e => {
    if (e.key !== 'Escape') return;
    if (document.getElementById('dmConfirmBackdrop').classList.contains('open')) dmCloseConfirm();
    else if (document.getElementById('dmDrawer').classList.contains('open')) dmCloseDrawer();
});