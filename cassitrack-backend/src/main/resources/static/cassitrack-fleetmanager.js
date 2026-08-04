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

        L.tileLayer(
            'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
            {
                attribution: '© OpenStreetMap © CARTO',
                maxZoom: 19
            }
        ).addTo(map);

        fetch(`${API}/vehicles/fleet-size`).then(r=>r.json())
            .then(d=>{ if(d && d.total) fleetSize = d.total; })
            .catch(()=>{});

        fetch(`${API}/routes`).then(r=>r.json()).then(routes=>{
            routes.forEach((route, i)=>{
                const color = `hsl(${(i * 137.5) % 360}, 70%, 55%)`;
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
                    `<span class="line-fg" data-fg="${l.color}">${escHtml(l.name)}</span>`
                ).join(' · ');
                const sm = L.circleMarker([st.lat, st.lon],{
                    radius:7, fillColor:'#0f1623', color:'#94A3B8', weight:2, opacity:1, fillOpacity:1
                }).addTo(map).bindPopup(`
                    <div class="stop-popup">
                        <div class="stop-popup-title">🚏 ${escHtml(st.name)}</div>
                        <div class="stop-popup-lines">Linee: ${linesHtml} · Magni Autoservizi</div>
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
        const visible = !isBusHidden(id) && busPassesFilter(vehicleData[id]);
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
    function busIcon(id,status){
        const st = SC[status] ? status : 'UNKNOWN';
        return L.divIcon({className:'',html:`<div class="bus-icon-wrap"><div class="bus-icon-body" data-status="${st}"><span class="bus-icon-emoji">🚌</span></div><div class="bus-icon-label" data-status="${st}">${escHtml(id)}</div></div>`,iconSize:[60,58],iconAnchor:[18,50]});
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
        vehicles.forEach(v=>{
            vehicleData[v.vehicle_id]=v;
            const st=!v.trip_id?'NO_TRIP':(v.schedule_status||'UNKNOWN'),pos=[v.lat,v.lon];
            if(markers[v.vehicle_id]){markers[v.vehicle_id].setLatLng(pos);markers[v.vehicle_id].setIcon(busIcon(v.vehicle_id,st));}
            else{
                const m=L.marker(pos,{icon:busIcon(v.vehicle_id,st)}).addTo(map);
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
        const l=SL[v.schedule_status]||'LIVE';
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
        const l   = SL[v.schedule_status]||'LIVE';
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
                <div class="vitem bd-span2"><div class="vitem-lbl">Last stop</div><div class="vitem-val">${escHtml(v.next_stop_name)||'—'}</div></div>
                <div class="vitem bd-span2"><div class="vitem-lbl">Next stop</div><div class="vitem-val">${escHtml(v.upcoming_stop_name)||'—'}</div></div>
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
    function routeColor(routeId){
        return routeColors[routeId] || '#4B5563';
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
            if(explicit && !active){
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
            const show = !explicit
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
        return routeColors[key] || `hsl(${(i * 137.5) % 360}, 70%, 55%)`;
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
    }
 
    // ── Data Management sub-navigation (Buses / Stops / …) ────────────────────
    function switchDmPanel(panelId, btn){
        // Scoped to #data-management: the old #data-management-view was merged away.
        document.querySelectorAll('#data-management .dm-panel').forEach(p=>p.classList.remove('active'));
        const panel = document.getElementById(panelId);
        if(panel) panel.classList.add('active');
        document.querySelectorAll('.dm-subtab').forEach(b=>b.classList.remove('active'));
        if(btn) btn.classList.add('active');
        if(panelId === 'dm-panel-stops') loadStops();
        else if(panelId === 'dm-panel-routes') loadRoutesAdmin();
        else if(panelId === 'dm-panel-timetable'){ ttLoadOptions(); loadTimetable(); }
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

    // NOTE: named *Admin to avoid colliding with the analytics-filter loadRoutes()
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

    async function openRouteForm(route){
        rtEditId = route ? route.id : null;
        document.getElementById('rtFormTitle').textContent =
            route ? `Edit route ${route.id}` : 'New route + its first run';

        // The itinerary is defined only when creating: editing it later would
        // rewrite the schedule of every run already on this line.
        const itn = document.getElementById('rtItinerary');
        const busField = document.getElementById('rtBusField');
        if(itn) itn.hidden = !!route;
        if(busField) busField.hidden = !!route;
        if(!route){
            await ttLoadStopOptions();
            await rtLoadBusOptions();
            const list = document.getElementById('rtStops');
            if(list){ list.innerHTML = ''; itnAddStop('rtStops'); itnAddStop('rtStops'); }
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
            payload.stops = itnCollect('rtStops');
            payload.busId = parseInt(document.getElementById('rtBus').value, 10) || null;
            if(payload.stops.length < 2){ setRtMsg('An itinerary needs at least two stops.'); return; }
            if(payload.stops.some(s=>!s.stopId || !s.arrival)){
                setRtMsg('Every stop needs both a stop and a time.'); return;
            }
            if(!payload.busId){ setRtMsg('Pick the bus for the first journey.'); return; }
        }
        const url    = editing ? `${API}/routes/${encodeURIComponent(rtEditId)}` : `${API}/routes`;
        const method = editing ? 'PUT' : 'POST';
        setRtMsg('Saving…', true);
        try{
            const r = await fetch(url, {
                method,
                headers:{'Content-Type':'application/json'},
                body: JSON.stringify(payload)
            });
            if(r.ok){ closeRouteForm(); await loadRoutesAdmin(); }
            else{
                let msg = 'Save failed.';
                try{ const j = await r.json(); if(j && j.error) msg = j.error; }catch(_){}
                setRtMsg(msg);
            }
        }catch(e){ setRtMsg('Network error while saving.'); }
    }

    async function deleteRoute(id){
        const route = rtRoutes.find(x=>x.id===id);
        if(!window.confirm(`Delete route ${route ? route.id : id}? This cannot be undone.`)) return;
        try{
            const r = await fetch(`${API}/routes/${encodeURIComponent(id)}`, {method:'DELETE'});
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
    let ttTrips = [];
    let ttOpenTripId = null;   // run whose stop times are being edited

    async function loadTimetable(){
        const body = document.getElementById('ttTableBody');
        if(body) body.innerHTML = `<tr><td colspan="7" class="bm-empty">Loading…</td></tr>`;
        const params = new URLSearchParams();
        const s = document.getElementById('ttSearch');
        const fr = document.getElementById('ttFilterRoute');
        const fb = document.getElementById('ttFilterBus');
        if(s && s.value.trim()) params.set('search', s.value.trim());
        if(fr && fr.value) params.set('routeId', fr.value);
        if(fb && fb.value) params.set('busId', fb.value);
        try{
            const r = await fetch(`${API}/timetable?${params.toString()}`, {headers:{'Accept':'application/json'}});
            if(!r.ok) throw new Error(r.status);
            ttTrips = await r.json();
            renderTimetable();
        }catch(e){
            if(body) body.innerHTML = `<tr><td colspan="7" class="bm-empty">Failed to load the timetable.</td></tr>`;
        }
    }

    function renderTimetable(){
        const body = document.getElementById('ttTableBody');
        if(!body) return;
        if(!ttTrips.length){ body.innerHTML = `<tr><td colspan="7" class="bm-empty">No runs match — or none exist yet.</td></tr>`; return; }
        body.innerHTML = ttTrips.map(t => `
            <tr>
                <td class="bm-mono">${escHtml(t.tripId)}</td>
                <td>${escHtml(t.routeLabel)||'—'}</td>
                <td class="bm-mono">${escHtml(t.targa)||'—'}</td>
                <td class="bm-mono">${escHtml(t.departure)}</td>
                <td class="bm-mono">${escHtml(t.arrival)}</td>
                <td>${t.stopCount}</td>
                <td class="bm-actions-col">
                    <button type="button" class="bm-row-btn" data-act="times" data-id="${escHtml(t.tripId)}">Times</button>
                    <button type="button" class="bm-row-btn danger" data-act="del" data-id="${escHtml(t.tripId)}">Delete</button>
                </td>
            </tr>`).join('');
    }

    /** Fill the line/bus dropdowns (filters + create form) from existing data. */
    async function ttLoadOptions(){
        try{
            const [routesRes, busesRes] = await Promise.all([
                fetch(`${API}/buses/route-options`, {headers:{'Accept':'application/json'}}),
                fetch(`${API}/buses`, {headers:{'Accept':'application/json'}})
            ]);
            const routes = routesRes.ok ? await routesRes.json() : [];
            const buses  = busesRes.ok  ? await busesRes.json()  : [];

            const routeOpts = routes.map(rt=>`<option value="${escHtml(rt.id)}">${escHtml(rt.label)}</option>`).join('');
            const busOpts   = buses.map(b=>`<option value="${escHtml(b.busId)}">${escHtml(b.targa)}</option>`).join('');

            const fr = document.getElementById('ttFilterRoute');
            const fb = document.getElementById('ttFilterBus');
            const cr = document.getElementById('ttRoute');
            const cb = document.getElementById('ttBus');
            if(fr) fr.innerHTML = '<option value="">All lines</option>' + routeOpts;
            if(fb) fb.innerHTML = '<option value="">All buses</option>' + busOpts;
            if(cr) cr.innerHTML = routeOpts;
            if(cb) cb.innerHTML = busOpts;
        }catch(e){ /* dropdowns stay empty; the table still works */ }
    }

    function ttSetMsg(id, txt, ok){
        const el = document.getElementById(id);
        if(!el) return;
        el.textContent = txt || '';
        el.classList.toggle('err', !!txt && !ok);
        el.classList.toggle('ok',  !!txt && !!ok);
    }

    // ── CSV export (shared) ───────────────────────────────────────────────────
    // Built in the browser from the rows already on screen, so the file always
    // matches what you are looking at (filters included) and no new endpoint is
    // exposed. Excel-friendly on purpose: a ';' separator and a UTF-8 BOM, or
    // Excel with Italian locale puts every row in one column and mangles accents.
    function csvCell(value){
        if(value === null || value === undefined) return '';
        const s = String(value);
        // Quote when the value could break the row, and double any inner quote.
        return /[";\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
    }

    /**
     * @param filename downloaded file name
     * @param columns  [{header, get}] — get(row) returns the cell value
     * @param rows     array of objects to export
     */
    function downloadCsv(filename, columns, rows){
        if(!rows || !rows.length){ window.alert('Nothing to export — the table is empty.'); return; }
        const lines = [columns.map(c => csvCell(c.header)).join(';')];
        rows.forEach(r => lines.push(columns.map(c => csvCell(c.get(r))).join(';')));
        const blob = new Blob(['﻿' + lines.join('\r\n')],
                              {type: 'text/csv;charset=utf-8;'});
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }

    /** Timestamp suffix so repeated exports don't overwrite each other. */
    function csvStamp(){
        const d = new Date(), p = n => String(n).padStart(2,'0');
        return `${d.getFullYear()}${p(d.getMonth()+1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`;
    }

    function exportBusesCsv(){
        downloadCsv(`buses-${csvStamp()}.csv`, [
            {header:'Bus ID',      get:b=>b.busId},
            {header:'Plate',       get:b=>b.targa},
            {header:'Route',       get:b=>b.routeName},
            {header:'Route live',  get:b=>b.routeLive === true ? 'yes' : b.routeLive === false ? 'scheduled' : ''},
            {header:'Capacity',    get:b=>b.numeroPosti},
            {header:'Wheelchair',  get:b=>b.wheelchairAccessible ? 'yes' : 'no'},
            {header:'Status',      get:b=>b.status},
            {header:'Antenna ID',  get:b=>b.currentVehicleId},
            {header:'On map',      get:b=>b.mapVisible ? 'yes' : 'no'}
        ], dmBuses);
    }

    function exportStopsCsv(){
        downloadCsv(`stops-${csvStamp()}.csv`, [
            {header:'Stop ID',     get:s=>s.id},
            {header:'Name',        get:s=>s.name},
            {header:'Latitude',    get:s=>s.lat},
            {header:'Longitude',   get:s=>s.lon},
            {header:'Active',      get:s=>s.active === false ? 'no' : 'yes'},
            {header:'Description', get:s=>s.description}
        ], stFiltered());          // what the table currently shows
    }

    function exportRoutesCsv(){
        downloadCsv(`routes-${csvStamp()}.csv`, [
            {header:'Route ID',   get:r=>r.id},
            {header:'Short name', get:r=>r.shortName},
            {header:'Long name',  get:r=>r.longName},
            {header:'Colour',     get:r=>r.color},
            {header:'Active',     get:r=>r.active === false ? 'no' : 'yes'}
        ], rtFiltered());
    }

    function exportTimetableCsv(){
        downloadCsv(`timetable-${csvStamp()}.csv`, [
            {header:'Run ID',   get:t=>t.tripId},
            {header:'Line',     get:t=>t.routeLabel},
            {header:'Bus',      get:t=>t.targa},
            {header:'Departs',  get:t=>t.departure},
            {header:'Arrives',  get:t=>t.arrival},
            {header:'Stops',    get:t=>t.stopCount}
        ], ttTrips);
    }

    /**
     * Detailed timetable export: one row per run AND stop, with arrival times.
     * The rows aren't in the browser (the table only holds summaries), so they
     * are fetched with the filters currently applied.
     */
    async function exportStopTimesCsv(){
        const params = new URLSearchParams();
        const s  = document.getElementById('ttSearch');
        const fr = document.getElementById('ttFilterRoute');
        const fb = document.getElementById('ttFilterBus');
        if(s && s.value.trim()) params.set('search', s.value.trim());
        if(fr && fr.value) params.set('routeId', fr.value);
        if(fb && fb.value) params.set('busId', fb.value);
        try{
            const r = await fetch(`${API}/timetable/stop-times?${params.toString()}`,
                                  {headers:{'Accept':'application/json'}});
            if(!r.ok) throw new Error(r.status);
            const rows = await r.json();
            downloadCsv(`stop-times-${csvStamp()}.csv`, [
                {header:'Run ID',    get:x=>x.tripId},
                {header:'Line',      get:x=>x.routeLabel},
                {header:'Bus',       get:x=>x.targa},
                {header:'Stop #',    get:x=>x.sequence},
                {header:'Stop ID',   get:x=>x.stopId},
                {header:'Stop name', get:x=>x.stopName},
                {header:'Arrival',   get:x=>x.arrival}
            ], rows);
        }catch(e){
            window.alert('Could not build the detailed timetable export.');
        }
    }

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

    async function ttCreateRun(){
        const payload = {
            routeId:   document.getElementById('ttRoute').value,
            busId:     parseInt(document.getElementById('ttBus').value, 10),
            departure: document.getElementById('ttDeparture').value
        };
        if(!payload.routeId){ ttSetMsg('ttFormMsg', 'Pick a line.'); return; }
        if(!(payload.busId > 0)){ ttSetMsg('ttFormMsg', 'Pick a bus.'); return; }
        if(!payload.departure){ ttSetMsg('ttFormMsg', 'Pick a departure time.'); return; }
        ttSetMsg('ttFormMsg', 'Creating…', true);
        try{
            const r = await fetch(`${API}/timetable`, {
                method:'POST',
                headers:{'Content-Type':'application/json'},
                body: JSON.stringify(payload)
            });
            if(r.ok){
                document.getElementById('ttForm').hidden = true;
                await loadTimetable();
            }else{
                let msg = 'Could not create the run.';
                try{ const j = await r.json(); if(j && (j.error||j.message)) msg = j.error||j.message; }catch(_){}
                ttSetMsg('ttFormMsg', msg);
            }
        }catch(e){ ttSetMsg('ttFormMsg', 'Network error while creating the run.'); }
    }

    /** Open the stop-by-stop time editor for one run. */
    async function ttOpenTimes(tripId){
        const box = document.getElementById('ttDetail');
        const list = document.getElementById('ttStops');
        if(!box || !list) return;
        ttOpenTripId = tripId;
        document.getElementById('ttForm').hidden = true;
        box.hidden = false;
        ttSetMsg('ttDetailMsg', '');
        list.innerHTML = '<div class="bm-msg">Loading…</div>';
        try{
            const r = await fetch(`${API}/timetable/${encodeURIComponent(tripId)}`, {headers:{'Accept':'application/json'}});
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
            const r = await fetch(`${API}/timetable/${encodeURIComponent(ttOpenTripId)}/times`, {
                method:'PUT',
                headers:{'Content-Type':'application/json'},
                body: JSON.stringify({stops})
            });
            if(r.ok){
                ttSetMsg('ttDetailMsg', 'Saved.', true);
                await loadTimetable();
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
            const r = await fetch(`${API}/timetable/${encodeURIComponent(tripId)}`, {method:'DELETE'});
            if(r.ok){
                if(ttOpenTripId === tripId){ document.getElementById('ttDetail').hidden = true; ttOpenTripId = null; }
                await loadTimetable();
            }else{
                let msg = 'Delete failed.';
                try{ const j = await r.json(); if(j && (j.error||j.message)) msg = j.error||j.message; }catch(_){}
                window.alert(msg);
            }
        }catch(e){ window.alert('Network error while deleting.'); }
    }

    async function logoutUser(){
        // BUGFIX (auth): this was gated behind `if (token)`, but nothing has
        // written 'cassitrack_token' to localStorage since the httpOnly-cookie
        // migration, so token was always null/falsy and POST /auth/logout was
        // never actually called — the JWT was never blacklisted server-side
        // and the session cookie was never cleared, so "logging out" didn't
        // really log you out. The cookie is sent automatically with this
        // same-origin request, so no Authorization header is needed.
        await fetch('/cassitrack/api/v1/auth/logout', { method: 'POST' }).catch(() => {});
        window.location.href='cassitrack-login.html';
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
    // ANALYTICS FILTER STATE
    // ═══════════════════════════════════════════════════════════

    const activeFilters = {
        preset:    'today',
        startTime: null,
        endTime:   null,
        routeIds:  [],   // array of route ID strings
        busId:     '',
        groupBy:   'hour',
        status:    ''
    };

    // Operating hours from schedule (loaded once)
    let scheduleFirstHour = 6;
    let scheduleLastHour  = 22;

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
        syncGroupByToPreset(preset);
    }

    function syncGroupByToPreset(preset) {
        const sel = document.getElementById('filterGroupBy');
        if (preset === 'today' || preset === 'yesterday') {
            sel.value = 'hour';
            activeFilters.groupBy = 'hour';
        } else {
            sel.value = 'day';
            activeFilters.groupBy = 'day';
        }
    }

    function presetToRange(preset) {
        const now  = new Date();
        const pad  = n => String(n).padStart(2, '0');
        const iso  = d => d.toISOString();

        const startOfDay = d => {
            const x = new Date(d); x.setHours(0,0,0,0); return x;
        };
        const endOfDay = d => {
            const x = new Date(d); x.setHours(23,59,59,999); return x;
        };

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

    // ── Build URL params from current filter state ─────────────

    function buildFilterParams(overrideGroupBy) {
        const p   = activeFilters.preset;
        const grp = overrideGroupBy || document.getElementById('filterGroupBy').value || activeFilters.groupBy;
        const { start, stop } = presetToRange(p);

        const params = new URLSearchParams();
        if (start) params.set('startTime', start);
        if (stop)  params.set('endTime',   stop);
        if (activeFilters.routeIds.length > 0)
            params.set('routeIds', activeFilters.routeIds.join(','));
        if (activeFilters.busId)
            params.set('busId', activeFilters.busId);
        params.set('groupBy', grp);
        return params.toString();
    }

    // ── Route dropdown ─────────────────────────────────────────

    let allRoutes = [];

    async function loadRoutes() {
        try {
            // BUGFIX (auth): dead localStorage-token header removed — auth is
            // via the httpOnly cookie, sent automatically on same-origin fetches.
            const r = await fetch(`${API}/analytics/routes`);
            if (!r.ok) return;
            allRoutes = await r.json();
            renderRouteDropdown();
        } catch(e) {
            console.warn('Could not load routes', e);
        }
    }

    function renderRouteDropdown() {
        const list = document.getElementById('routeCheckList');
        if (!allRoutes.length) {
            // CSP FIX (A05): purely static styling -> reuse the .rdrop-item-empty class.
            list.innerHTML = '<div class="rdrop-item-empty">No routes found</div>';
            return;
        }
        // CSP FIX (A05): per-route checkbox count is unbounded/runtime-determined,
        // so the onchange="" handler is replaced with a data-route-id attribute and
        // a single delegated 'change' listener bound once on #routeCheckList (below).
        list.innerHTML = allRoutes.map(rt => `
            <div class="rdrop-item">
                <input type="checkbox" id="rc_${escHtml(rt.id)}" value="${escHtml(rt.id)}" data-route-id="${escHtml(rt.id)}"
                    ${activeFilters.routeIds.includes(rt.id) ? 'checked' : ''}>
                <label for="rc_${escHtml(rt.id)}">${escHtml(rt.shortName || rt.id)}${rt.longName ? ' — ' + escHtml(rt.longName) : ''}</label>
            </div>`).join('');
    }

    function toggleRouteDropdown() {
        document.getElementById('routeDropdown').classList.toggle('open');
    }

    // Close dropdown when clicking outside
    document.addEventListener('click', e => {
        const wrap = document.getElementById('routeDropdownWrap');
        if (wrap && !wrap.contains(e.target))
            document.getElementById('routeDropdown').classList.remove('open');
    });

    function toggleRoute(routeId, checked) {
        if (checked) {
            if (!activeFilters.routeIds.includes(routeId)) activeFilters.routeIds.push(routeId);
        } else {
            activeFilters.routeIds = activeFilters.routeIds.filter(id => id !== routeId);
        }
        renderSelectedPills();
        updateRouteDropdownBtn();
    }

    function clearRoutes() {
        activeFilters.routeIds = [];
        renderSelectedPills();
        updateRouteDropdownBtn();
        renderRouteDropdown();
    }

    function renderSelectedPills() {
        // CSP FIX (A05): pill count is unbounded/runtime-determined, so the
        // onclick="" handler is replaced with a data-route-id attribute and a
        // single delegated 'click' listener bound once on #selectedRoutePills.
        const container = document.getElementById('selectedRoutePills');
        container.innerHTML = activeFilters.routeIds.map(id => {
            const rt = allRoutes.find(r => r.id === id);
            const label = rt ? (rt.shortName || rt.id) : id;
            return `<div class="route-pill" data-route-id="${escHtml(id)}">
                        ${escHtml(label)}<span class="rpx">✕</span>
                    </div>`;
        }).join('');
    }

    function updateRouteDropdownBtn() {
        const btn = document.getElementById('routeDropdownBtn');
        const n   = activeFilters.routeIds.length;
        btn.textContent = n === 0 ? 'All Routes ▾' : `${n} Route${n > 1 ? 's' : ''} ▾`;
    }

    // Populate bus dropdown from live vehicle list
    function populateBusDropdown(vehicles) {
        const sel  = document.getElementById('filterBus');
        const prev = sel.value;
        const ids  = [...new Set(vehicles.map(v => v.vehicle_id))].sort();
        sel.innerHTML = '<option value="">All Buses</option>' +
            ids.map(id => `<option value="${id}" ${prev === id ? 'selected' : ''}>${id}</option>`).join('');
    }

    // ── Operating hours from schedule ──────────────────────────

    async function loadOperatingHours() {
        try {
            // BUGFIX (auth): dead localStorage-token header removed — auth is
            // via the httpOnly cookie, sent automatically on same-origin fetches.
            const r = await fetch(`${API}/analytics/operating-hours`);
            if (!r.ok) return;
            const data = await r.json();
            if (data._global) {
                scheduleFirstHour = data._global.firstHour ?? 6;
                scheduleLastHour  = data._global.lastHour  ?? 22;
            }
        } catch(e) {
            console.warn('Could not load operating hours', e);
        }
    }

    // ── Build chart labels (hours or dates) ────────────────────

    function buildChartLabels(groupBy, data) {
        if (groupBy === 'day') {
            // Collect all unique date keys across all routes, sorted
            const days = new Set();
            Object.values(data).forEach(bySlot => Object.keys(bySlot).forEach(k => days.add(k)));
            return [...days].sort();
        }
        // Hour labels from actual schedule
        const labels = [];
        for (let h = scheduleFirstHour; h < scheduleLastHour; h++)
            labels.push(`${String(h).padStart(2,'0')}:00`);
        return labels;
    }

    // ── Chart rendering ────────────────────────────────────────

    let routesChartInstance = null;
    let delayChartInstance  = null;

    const CHART_COLORS = ['#3B82F6','#06B6D4','#8B5CF6','#22C55E','#F59E0B','#EF4444'];

    function renderBarChart(canvasId, instanceRef, data, tooltipUnit) {
        const canvas = document.getElementById(canvasId);
        if (!canvas) return instanceRef;

        // ID convention: delayChart → delayChartEmpty, routesChart → routesChartEmpty
        const emptyEl = document.getElementById(canvasId + 'Empty');
        const wrap    = canvas.parentElement;

        const groupBy   = document.getElementById('filterGroupBy').value || 'hour';
        const routeKeys = Object.keys(data).sort();

        if (routeKeys.length === 0) {
            canvas.style.display = 'none';
            if (emptyEl) emptyEl.style.display = 'block';
            if (instanceRef) instanceRef.destroy();
            return null;
        }

        // Show canvas (wrap is always visible — hiding the wrap causes Chart.js to lose its size reference)
        canvas.style.display = '';
        if (emptyEl) emptyEl.style.display = 'none';

        const labels   = buildChartLabels(groupBy, data);
        const datasets = routeKeys.sort().map((key, i) => ({
                label: key,
                data: labels.map(slot => data[key][slot] ?? null),
                backgroundColor: chartColor(key, i),
                borderRadius: 4,
                maxBarThickness: groupBy === 'day' ? 18 : 14
            }));

        if (instanceRef) instanceRef.destroy();

        return new Chart(canvas, {
            type: 'bar',
            data: { labels, datasets },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { labels: { color: '#E2E8F0', font: { family: 'DM Mono', size: 10 } } },
                    tooltip: { callbacks: { label: ctx => `${ctx.dataset.label}: ${ctx.parsed.y ?? 0} ${tooltipUnit}` } }
                },
                scales: {
                    x: {
                        ticks: { color: '#4B5563', font: { family: 'DM Mono', size: 9 },
                                 maxRotation: groupBy === 'day' ? 45 : 0 },
                        grid: { color: 'rgba(255,255,255,.04)' }
                    },
                    y: {
                        beginAtZero: true,
                        title: { display: true, text: tooltipUnit === 'pax' ? 'Passengers' : 'Delay (min)',
                                 color: '#4B5563', font: { family: 'DM Mono', size: 10 } },
                        ticks: { color: '#4B5563', font: { family: 'DM Mono', size: 9 } },
                        grid: { color: 'rgba(255,255,255,.04)' }
                    }
                }
            }
        });
    }

    async function loadPassengersByRoute() {
        try {
            // BUGFIX (auth): dead localStorage-token header removed — auth is
            // via the httpOnly cookie, sent automatically on same-origin fetches.
            const qs    = buildFilterParams();
            const r = await fetch(`${API}/analytics/passengers-by-route?${qs}`);
            if (!r.ok) throw new Error(r.status);
            const data = await r.json();
            routesChartInstance = renderBarChart('routesChart', routesChartInstance, data, 'pax');

            // Update average occupancy KPI
            const allVals = [];
            Object.values(data).forEach(s => Object.values(s).forEach(v => { if (v != null) allVals.push(v); }));
            const avg = allVals.length > 0 ? Math.round(allVals.reduce((a,b)=>a+b,0)/allVals.length) : null;
            document.getElementById('kpiPassengers').textContent = avg != null ? avg : '—';
        } catch(e) {
            console.error('Failed to load passengers by route', e);
            // NOTE: `canvas` is not defined in this scope (pre-existing bug, kept
            // as-is — only the inline style="" string is swapped for a CSS class,
            // per CSP-fix scope; this catch block would already throw a
            // ReferenceError before reaching here, exactly as it did before).
            canvas.parentElement.innerHTML = `<div class="chart-msg">Error loading data.</div>`;
        }
    }

    async function loadDelayByRoute() {
        try {
            // BUGFIX (auth): dead localStorage-token header removed — auth is
            // via the httpOnly cookie, sent automatically on same-origin fetches.
            const qs    = buildFilterParams();
            const r = await fetch(`${API}/analytics/delay-by-route?${qs}`);
            if (!r.ok) throw new Error(r.status);
            const data = await r.json();
            delayChartInstance = renderBarChart('delayChart', delayChartInstance, data, 'min');

            const routeKeys = Object.keys(data);
            if (routeKeys.length === 0) {
                canvas.parentElement.innerHTML = `<div class="chart-msg">No data yet — run the simulator.</div>`;
                return;
            }

            const labels = buildHourlyLabels();

            const datasets = routeKeys.sort().map((key, i) => ({
                label: key,
                data: labels.map(slot => data[key][slot] ?? null),
                backgroundColor: chartColor(key, i),
                borderRadius: 4,
                maxBarThickness: 14
            }));

            canvas.width = Math.max(700, labels.length * 90);

            // Update average delay KPI
            const allVals = [];
            Object.values(data).forEach(s => Object.values(s).forEach(v => { if (v != null) allVals.push(v); }));
            const avg = allVals.length > 0
                ? (allVals.reduce((a,b)=>a+b,0)/allVals.length).toFixed(1) : 0;
            document.getElementById('kpiDelay').textContent = avg > 0 ? `+${avg}m` : '0m';
        } catch(e) {
            console.error('Failed to load delay by route', e);
            // NOTE: same pre-existing undefined-`canvas` bug as above, intentionally
            // preserved — only swapping style="" for the .chart-msg class.
            canvas.parentElement.innerHTML = `<div class="chart-msg">Error loading data.</div>`;
        }
    }

    async function loadCo2Kpi() {
        try {
            // BUGFIX (auth): dead localStorage-token header removed — auth is
            // via the httpOnly cookie, sent automatically on same-origin fetches.
            const { start, stop } = presetToRange(activeFilters.preset);
            const qs = new URLSearchParams();
            if (start) qs.set('startTime', start);
            if (stop)  qs.set('endTime',   stop);
            if (activeFilters.routeIds.length > 0) qs.set('routeIds', activeFilters.routeIds.join(','));
            if (activeFilters.busId) qs.set('busId', activeFilters.busId);

            const r = await fetch(`${API}/analytics/co2?${qs}`);
            if (!r.ok) return;
            const d = await r.json();

            const kg = d.co2_saved_kg ?? 0;
            document.getElementById('kpiCo2').textContent = kg >= 1000
                ? `${(kg/1000).toFixed(1)}t` : `${kg}kg`;

            if (d.passenger_km != null)
                document.getElementById('kpiCo2Sub').textContent =
                    `${d.passenger_km} pax-km saved`;
        } catch(e) {
            console.error('Failed to load CO2 KPI', e);
        }
    }

    async function loadSummaryKPIs() {
        // On-time % from live adherence
        try {
            // BUGFIX (auth): dead localStorage-token header removed — auth is
            // via the httpOnly cookie, sent automatically on same-origin fetches.
            const r = await fetch(`${API}/analytics/adherence`);
            if (r.ok) {
                const d = await r.json();
                const counts = d.status_counts || {};
                const total  = d.total_active || 0;
                const onTime = (counts.ON_TIME || 0) + (counts.EARLY || 0);
                const pct    = total > 0 ? Math.round(onTime / total * 100) : 0;
                document.getElementById('kpiOnTimePct').textContent = `${pct}%`;
                document.getElementById('kpiOnTimeSub').textContent =
                    `${onTime}/${total} buses on schedule`;
            }

            // Fleet donut from live vehicles
            const rv = await fetch(`${API}/vehicles`);
            if (rv.ok) {
                const vehs = await rv.json();
                updateFleetDonut(vehs);
            }
        } catch(e) {
            console.error('Error loading summary KPIs', e);
        }
    }

    function updateFleetDonut(vehicles) {
        const donut   = document.querySelector('.fake-donut');
        const kpiSpan = document.getElementById('kpiOnTime');
        if (!donut || !vehicles.length) return;

        kpiSpan.textContent = '';

        const total    = vehicles.length;
        const onTime   = vehicles.filter(v => v.schedule_status === 'ON_TIME' || v.schedule_status === 'EARLY').length;
        const slight   = vehicles.filter(v => v.schedule_status === 'SLIGHTLY_LATE').length;
        const late     = vehicles.filter(v => v.schedule_status === 'SIGNIFICANTLY_LATE').length;
        const noTrip   = vehicles.filter(v => !v.trip_id).length;

        const onTimePct  = (onTime  / total) * 100;
        const slightPct  = (slight  / total) * 100;
        const latePct    = (late    / total) * 100;
        const noTripPct  = (noTrip  / total) * 100;

        // NOTE: assigning to element.style.background via the CSSOM is a property
        // mutation, not inline-HTML/CSS-string parsing — already unaffected by
        // removing 'unsafe-inline' from style-src, no change needed here.
        donut.style.background = `conic-gradient(
            #22C55E 0% ${onTimePct}%,
            #F59E0B ${onTimePct}% ${onTimePct + slightPct}%,
            #EF4444 ${onTimePct + slightPct}% ${onTimePct + slightPct + latePct}%,
            #F59E0B ${onTimePct + slightPct + latePct}% ${onTimePct + slightPct + latePct + noTripPct}%,
            #1A2744 ${onTimePct + slightPct + latePct + noTripPct}% 100%
        )`;
    }

    // ── Apply filters (main refresh) ───────────────────────────

    function applyFilters() {
        activeFilters.busId   = document.getElementById('filterBus').value;
        activeFilters.groupBy = document.getElementById('filterGroupBy').value;

        // If custom preset, read date inputs
        if (activeFilters.preset === 'custom') {
            const { start, stop } = presetToRange('custom');
            activeFilters.startTime = start;
            activeFilters.endTime   = stop;
        }

        loadAllAnalytics();
    }

    function loadAllAnalytics() {
        // Update chart subtitles to reflect active filters
        const grp       = document.getElementById('filterGroupBy').value || 'hour';
        const presetLbl = { today:'Today', yesterday:'Yesterday', week:'Last 7 Days',
                            month:'This Month', lastmonth:'Last Month', custom:'Custom Range' };
        const periodTxt = presetLbl[activeFilters.preset] || '';
        const slotTxt   = grp === 'day' ? 'per day' : 'per hour';
        const sub       = `${periodTxt} · ${slotTxt}`;
        const ds = document.getElementById('delayChartSub');
        const ps = document.getElementById('paxChartSub');
        if (ds) ds.textContent = `Delay by line · ${sub}`;
        if (ps) ps.textContent = `Passenger demand by line · ${sub}`;

        loadPassengersByRoute();
        loadDelayByRoute();
        loadSummaryKPIs();
        loadCo2Kpi();
    }

    // ── Wire up the Analytics nav button ──────────────────────

    document.querySelectorAll('.top-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            if (btn.textContent.trim() === 'Analytics') {
                loadAllAnalytics();
            }
        });
    });

    // Load routes + operating hours once on page load
    window.addEventListener('load', () => {
        loadRoutes();
        loadOperatingHours();
    });

    // ─────────────────────────────────────────────────────────────────
    // CSP FIX (A03/A05): bind every previously-inline onX="" attribute here
    // instead. Static, fixed-count elements get a direct id-based listener;
    // dynamically-generated, variable-count elements (route checkboxes/pills)
    // use event delegation bound once on their stable parent container.
    // ─────────────────────────────────────────────────────────────────
    document.getElementById('topBtnFleetMonitor').addEventListener('click', e => switchTopView('fleet-monitor', e.currentTarget));
    document.getElementById('topBtnAnalytics').addEventListener('click', e => switchTopView('analytics-view', e.currentTarget));
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
    const dmTabTimetable = document.getElementById('dmTabTimetable');
    if(dmTabTimetable) dmTabTimetable.addEventListener('click', e => switchDmPanel('dm-panel-timetable', e.currentTarget));

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

    // Data Management > timetable
    const ttSearchEl = document.getElementById('ttSearch');
    let ttSearchTimer = null;
    if(ttSearchEl) ttSearchEl.addEventListener('input', () => {
        clearTimeout(ttSearchTimer);                 // debounce: filtering is server-side
        ttSearchTimer = setTimeout(loadTimetable, 250);
    });
    const ttFilterRouteEl = document.getElementById('ttFilterRoute');
    if(ttFilterRouteEl) ttFilterRouteEl.addEventListener('change', loadTimetable);
    const ttFilterBusEl = document.getElementById('ttFilterBus');
    if(ttFilterBusEl) ttFilterBusEl.addEventListener('change', loadTimetable);
    const ttResetBtn = document.getElementById('ttResetBtn');
    if(ttResetBtn) ttResetBtn.addEventListener('click', () => {
        if(ttSearchEl) ttSearchEl.value = '';
        if(ttFilterRouteEl) ttFilterRouteEl.value = '';
        if(ttFilterBusEl) ttFilterBusEl.value = '';
        loadTimetable();
    });
    const ttAddBtn = document.getElementById('ttAddBtn');
    if(ttAddBtn) ttAddBtn.addEventListener('click', () => {
        document.getElementById('ttDetail').hidden = true;
        document.getElementById('ttForm').hidden = false;
        ttSetMsg('ttFormMsg',
            "The stops come from the line's itinerary — a run only sets when and with which bus.", true);
    });
    const ttCancelBtn = document.getElementById('ttCancelBtn');
    if(ttCancelBtn) ttCancelBtn.addEventListener('click', () => {
        document.getElementById('ttForm').hidden = true;
    });
    const ttSaveBtn = document.getElementById('ttSaveBtn');
    if(ttSaveBtn) ttSaveBtn.addEventListener('click', ttCreateRun);
    // CSV export — one button per panel, exporting the rows currently shown
    const dmExportBtn = document.getElementById('dmExportBtn');
    if(dmExportBtn) dmExportBtn.addEventListener('click', exportBusesCsv);
    const stExportBtn = document.getElementById('stExportBtn');
    if(stExportBtn) stExportBtn.addEventListener('click', exportStopsCsv);
    const rtExportBtn = document.getElementById('rtExportBtn');
    if(rtExportBtn) rtExportBtn.addEventListener('click', exportRoutesCsv);
    const ttExportBtn = document.getElementById('ttExportBtn');
    if(ttExportBtn) ttExportBtn.addEventListener('click', exportTimetableCsv);
    const ttExportStopsBtn = document.getElementById('ttExportStopsBtn');
    if(ttExportStopsBtn) ttExportStopsBtn.addEventListener('click', exportStopTimesCsv);

    // Routes > itinerary editor (defines the line's path on create)
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
    const ttTableBody = document.getElementById('ttTableBody');
    if(ttTableBody) ttTableBody.addEventListener('click', e => {
        const btn = e.target.closest('button[data-act]');
        if(!btn) return;
        const id = btn.dataset.id;
        if(btn.dataset.act === 'times') ttOpenTimes(id);
        else if(btn.dataset.act === 'del') ttDeleteRun(id);
    });

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

    document.getElementById('routeDropdownBtn').addEventListener('click', toggleRouteDropdown);
    document.getElementById('clearRoutesBtn').addEventListener('click', clearRoutes);
    document.getElementById('applyFiltersBtn').addEventListener('click', applyFilters);

    // Event delegation: route checkboxes/pills are generated for an unknown,
    // runtime-determined number of routes — bind once on the stable parent.
    document.getElementById('routeCheckList').addEventListener('change', e => {
        const cb = e.target.closest('input[type="checkbox"][data-route-id]');
        if (cb) toggleRoute(cb.dataset.routeId, cb.checked);
    });
    document.getElementById('selectedRoutePills').addEventListener('click', e => {
        const pill = e.target.closest('.route-pill[data-route-id]');
        if (pill) { toggleRoute(pill.dataset.routeId, false); renderRouteDropdown(); }
    });

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