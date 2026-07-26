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
                routePolylines[route.id] = L.polyline(route.stops.map(s=>[s.lat,s.lon]),
                    {color,weight:4,opacity:.8,dashArray:'8 8'}).addTo(map);

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
    let lastVehicles = [];    // ultima lista /vehicles ricevuta (per filtrare percorsi/fermate)
    const stopMap = {};   // stopId -> { name, lat, lon, lines:[{name,color}] }
    let fleetSize = 4;

    // ── Bus map-visibility (user story) ──────────────────────────────────────
    // vehicle_ids currently HIDDEN from the map. The bus stays fully tracked
    // (data keeps updating, it still appears in lists) — only its map marker is
    // suppressed. A plain in-memory Set persists for the session but not across
    // a refresh, exactly as required.
    const hiddenBuses = new Set();

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
        const visible = !hiddenBuses.has(id) && busPassesFilter(vehicleData[id]);
        if(visible){ if(!map.hasLayer(m)) m.addTo(map); }
        else if(map.hasLayer(m)) map.removeLayer(m);
    }

    // Flip a bus's visibility and keep BOTH surfaces (map sidebar + Data
    // Management table) in sync — they both read the same hiddenBuses Set.
    function toggleBusVisibility(id){
        if(hiddenBuses.has(id)) hiddenBuses.delete(id); else hiddenBuses.add(id);
        applyBusVisibility(id);
        updateRouteVisibility();   // a toggled-off bus also hides its route/stops (if it was the last one)
        updateFleet(Object.values(vehicleData));
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
                markers[v.vehicle_id]=m;
            }
            markers[v.vehicle_id].setPopupContent(popupV(v));
            // setPopupContent() re-renders the popup's inner DOM even while it's
            // currently open, so re-apply the dynamic styles in that case too.
            if (markers[v.vehicle_id].isPopupOpen()) applyDynStyles(markers[v.vehicle_id].getPopup().getElement());
            // Suppress the marker if this bus is hidden (data above still updated → bus stays tracked).
            applyBusVisibility(v.vehicle_id);
            // Focus follow: keep the map centred on the selected bus as it moves.
            if (selectedVeh === v.vehicle_id && !hiddenBuses.has(v.vehicle_id))
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
        const cp=crowdPct(v.crowding_level),cc=cp>70?'#EF4444':cp>40?'#F59E0B':'#22C55E';
        const delayTxt=v.delay_minutes>0?`+${v.delay_minutes}m`:'In orario';
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
            <div class="vpop-span2"><div class="vpop-lbl">LAST STOP</div>${escHtml(v.next_stop_name)||'—'}</div>
            <div class="vpop-span2"><div class="vpop-lbl">NEXT STOP</div>${escHtml(v.upcoming_stop_name)||'—'}</div>
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
            const cp=crowdPct(v.crowding_level),cc=cp>70?'#EF4444':cp>40?'#F59E0B':'#22C55E';
            const rc=routeColor(v.route_id);
            const hidden=hiddenBuses.has(v.vehicle_id);
            const d=document.createElement('div');
            d.className='vcard'+(selectedVeh===v.vehicle_id?' selected':'')+(hidden?' hidden-bus':'');
            d.innerHTML=`<div class="vcard-stripe" data-status="${st}"></div><div class="vcard-top"><div class="vcard-id" data-fg="${rc}">${escHtml(v.vehicle_id)}</div><div class="vcard-actions"><div class="chip" data-status="${st}">${l}</div><button type="button" class="vis-toggle" data-hidden="${hidden}" aria-label="Toggle map visibility" title="${hidden?'Hidden from map — click to show':'Visible on map — click to hide'}">${hidden?'🚫':'👁'}</button></div></div><div class="vcard-grid"><div class="vitem"><div class="vitem-lbl">Speed</div><div class="vitem-val">${spd} km/h</div></div><div class="vitem"><div class="vitem-lbl">Passengers</div><div class="vitem-val">${pax}</div></div><div class="vitem"><div class="vitem-lbl">Crowding</div><div class="vitem-val">${escHtml(v.crowding_level)||'—'}</div></div><div class="vitem"><div class="vitem-lbl">Route</div><div class="vitem-val" data-fg="${rc}">${escHtml(v.route_name)||'—'}</div></div></div><div class="cbar"><div class="cbar-fill" data-bg="${cc}" data-width-pct="${cp}"></div></div>`;
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
        const hidden   = hiddenBuses.has(v.vehicle_id);
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
        }else{
            selectedVeh=id;const v=vehicleData[id];
            if(v && !hiddenBuses.has(id)){map.flyTo([v.lat,v.lon],16,{duration:1});markers[id]?.openPopup();}
        }
        updateRouteVisibility();
        updateFleet(Object.values(vehicleData));
    }



    // Helpers
    function crowdPct(l){return{LOW:18,MEDIUM:48,HIGH:74,VERY_HIGH:95}[l]||0;}
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
        if(selectedVeh && vehicleData[selectedVeh]){
            const r = vehicleData[selectedVeh].route_id;
            showSet = new Set(r ? [r] : []);
        } else if(routeFilter){
            showSet = new Set([routeFilter]);
        } else {
            showSet = new Set();
            lastVehicles.forEach(v=>{ if(v.route_id && !hiddenBuses.has(v.vehicle_id) && busPassesFilter(v)) showSet.add(v.route_id); });
        }
        // Route polylines
        Object.entries(routePolylines).forEach(([rid, pl])=>{
            if(showSet.has(rid)){ if(!map.hasLayer(pl)) pl.addTo(map); }
            else if(map.hasLayer(pl)) map.removeLayer(pl);
        });
        // Stops: shown only if they belong to at least one visible route
        Object.values(stopMap).forEach(st=>{
            if(!st.marker) return;
            const show = st.routeIds && [...st.routeIds].some(rid=>showSet.has(rid));
            if(show){ if(!map.hasLayer(st.marker)) st.marker.addTo(map); }
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
    function setServiceFilter(val){ serviceFilter = val || 'all'; refreshFilters(); }
    function setDelayThreshold(min){ delayThreshold = Math.max(0, parseInt(min, 10) || 0); refreshFilters(); }
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

    // ─────────────────────────────────────────────
    // DATA MANAGEMENT — fleet buses CRUD (Postgres)
    // ─────────────────────────────────────────────
    let bmBuses = [];          // last loaded list
    let bmEditId = null;       // null = adding a new bus, otherwise the bus id being edited

    async function loadBuses(){
        const body = document.getElementById('bmTableBody');
        if(body) body.innerHTML = `<tr><td colspan="7" class="bm-empty">Loading…</td></tr>`;
        try{
            const r = await fetch(`${API}/buses`, {headers:{'Accept':'application/json'}});
            if(!r.ok) throw new Error(r.status);
            bmBuses = await r.json();
            renderBusesAdmin();
        }catch(e){
            if(body) body.innerHTML = `<tr><td colspan="7" class="bm-empty">Failed to load buses.</td></tr>`;
        }
    }

    function renderBusesAdmin(){
        const body = document.getElementById('bmTableBody');
        if(!body) return;
        if(!bmBuses.length){ body.innerHTML = `<tr><td colspan="7" class="bm-empty">No buses yet — use “＋ Add bus”.</td></tr>`; return; }
        body.innerHTML = '';
        bmBuses.slice().sort((a,b)=>(a.busId||0)-(b.busId||0)).forEach(b=>{
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${escHtml(b.busId)}</td>
                <td class="bm-mono">${escHtml(b.targa)||'—'}</td>
                <td>${escHtml(b.numeroPosti)}</td>
                <td>${b.wheelchairAccessible ? '♿ Yes' : '—'}</td>
                <td>${b.disponibile ? '<span class="bm-badge on">Available</span>' : '<span class="bm-badge off">Unavailable</span>'}</td>
                <td class="bm-mono">${escHtml(b.currentVehicleId)||'—'}</td>
                <td class="bm-actions-col">
                    <button type="button" class="bm-row-btn" data-act="edit" data-id="${escHtml(b.busId)}">Edit</button>
                    <button type="button" class="bm-row-btn danger" data-act="del" data-id="${escHtml(b.busId)}">Delete</button>
                </td>`;
            body.appendChild(tr);
        });
    }

    function setBmMsg(txt, ok){
        const el = document.getElementById('bmFormMsg');
        if(!el) return;
        el.textContent = txt || '';
        el.classList.toggle('err', !!txt && !ok);
        el.classList.toggle('ok',  !!txt && !!ok);
    }

    function openBusForm(bus){
        bmEditId = bus ? bus.busId : null;
        document.getElementById('bmFormTitle').textContent = bus ? `Edit bus #${bus.busId}` : 'New bus';
        document.getElementById('bmTarga').value      = bus ? (bus.targa || '') : '';
        document.getElementById('bmPosti').value      = bus && bus.numeroPosti != null ? bus.numeroPosti : '';
        document.getElementById('bmVehicleId').value  = bus ? (bus.currentVehicleId || '') : '';
        document.getElementById('bmWheelchair').value = (bus && bus.wheelchairAccessible) ? 'true' : 'false';
        document.getElementById('bmDisponibile').value= bus ? (bus.disponibile ? 'true' : 'false') : 'true';
        setBmMsg('');
        document.getElementById('bmForm').hidden = false;
        document.getElementById('bmTarga').focus();
    }

    function closeBusForm(){
        document.getElementById('bmForm').hidden = true;
        bmEditId = null;
        setBmMsg('');
    }

    async function saveBus(){
        const payload = {
            targa:                document.getElementById('bmTarga').value,
            numeroPosti:          parseInt(document.getElementById('bmPosti').value, 10),
            wheelchairAccessible: document.getElementById('bmWheelchair').value === 'true',
            disponibile:          document.getElementById('bmDisponibile').value === 'true',
            currentVehicleId:     document.getElementById('bmVehicleId').value
        };
        // light client-side validation (server re-validates authoritatively)
        if(!payload.targa || !payload.targa.trim()){ setBmMsg('Plate is required.'); return; }
        if(!(payload.numeroPosti > 0)){ setBmMsg('Seats must be a positive number.'); return; }

        const editing = bmEditId != null;
        const url    = editing ? `${API}/buses/${bmEditId}` : `${API}/buses`;
        const method = editing ? 'PUT' : 'POST';
        setBmMsg('Saving…', true);
        try{
            const r = await fetch(url, {
                method,
                headers:{'Content-Type':'application/json'},
                body: JSON.stringify(payload)
            });
            if(r.ok){
                closeBusForm();
                await loadBuses();
            }else{
                let msg = 'Save failed.';
                try{ const j = await r.json(); if(j && j.error) msg = j.error; }catch(_){}
                setBmMsg(msg);
            }
        }catch(e){ setBmMsg('Network error while saving.'); }
    }

    async function deleteBus(id){
        const bus = bmBuses.find(b=>b.busId===id);
        const label = bus ? (bus.targa || ('#'+id)) : ('#'+id);
        if(!window.confirm(`Delete bus ${label}? This cannot be undone.`)) return;
        try{
            const r = await fetch(`${API}/buses/${id}`, {method:'DELETE'});
            if(r.ok){ await loadBuses(); }
            else{
                let msg = 'Delete failed.';
                try{ const j = await r.json(); if(j && j.error) msg = j.error; }catch(_){}
                window.alert(msg);
            }
        }catch(e){ window.alert('Network error while deleting.'); }
    }

    // ── Data Management sub-navigation (Buses / Stops / …) ────────────────────
    function switchDmPanel(panelId, btn){
        document.querySelectorAll('#data-management-view .dm-panel').forEach(p=>p.classList.remove('active'));
        const panel = document.getElementById(panelId);
        if(panel) panel.classList.add('active');
        document.querySelectorAll('.bm-subnav-btn').forEach(b=>b.classList.remove('active'));
        if(btn) btn.classList.add('active');
        if(panelId === 'dm-panel-stops') loadStops();
        else if(panelId === 'dm-panel-routes') loadRoutes();
        else if(panelId === 'dm-panel-buses') loadBuses();
    }

    // ── Data Management: stops CRUD (Postgres) ────────────────────────────────
    let stStops = [];
    let stEditId = null;   // null = adding a new stop, otherwise the stop id being edited

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

    function renderStopsAdmin(){
        const body = document.getElementById('stTableBody');
        if(!body) return;
        if(!stStops.length){ body.innerHTML = `<tr><td colspan="6" class="bm-empty">No stops yet — use “＋ Add stop”.</td></tr>`; return; }
        body.innerHTML = '';
        stStops.slice().sort((a,b)=>String(a.id).localeCompare(String(b.id))).forEach(s=>{
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

    async function loadRoutes(){
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

    function renderRoutesAdmin(){
        const body = document.getElementById('rtTableBody');
        if(!body) return;
        if(!rtRoutes.length){ body.innerHTML = `<tr><td colspan="6" class="bm-empty">No routes yet — use “＋ Add route”.</td></tr>`; return; }
        body.innerHTML = '';
        rtRoutes.slice().sort((a,b)=>String(a.id).localeCompare(String(b.id))).forEach(rt=>{
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

    function setRtMsg(txt, ok){
        const el = document.getElementById('rtFormMsg');
        if(!el) return;
        el.textContent = txt || '';
        el.classList.toggle('err', !!txt && !ok);
        el.classList.toggle('ok',  !!txt && !!ok);
    }

    function openRouteForm(route){
        rtEditId = route ? route.id : null;
        document.getElementById('rtFormTitle').textContent = route ? `Edit route ${route.id}` : 'New route';
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
        const url    = editing ? `${API}/routes/${encodeURIComponent(rtEditId)}` : `${API}/routes`;
        const method = editing ? 'PUT' : 'POST';
        setRtMsg('Saving…', true);
        try{
            const r = await fetch(url, {
                method,
                headers:{'Content-Type':'application/json'},
                body: JSON.stringify(payload)
            });
            if(r.ok){ closeRouteForm(); await loadRoutes(); }
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
            if(r.ok){ await loadRoutes(); }
            else{
                let msg = 'Delete failed.';
                try{ const j = await r.json(); if(j && j.error) msg = j.error; }catch(_){}
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
    document.getElementById('topBtnDataManagement').addEventListener('click', e => { switchTopView('data-management-view', e.currentTarget); switchDmPanel('dm-panel-buses', document.getElementById('dmTabBuses')); });
    document.getElementById('topBtnLogout').addEventListener('click', logoutUser);

    // Data Management sub-navigation
    const dmTabBuses = document.getElementById('dmTabBuses');
    if(dmTabBuses) dmTabBuses.addEventListener('click', e => switchDmPanel('dm-panel-buses', e.currentTarget));
    const dmTabStops = document.getElementById('dmTabStops');
    if(dmTabStops) dmTabStops.addEventListener('click', e => switchDmPanel('dm-panel-stops', e.currentTarget));
    const dmTabRoutes = document.getElementById('dmTabRoutes');
    if(dmTabRoutes) dmTabRoutes.addEventListener('click', e => switchDmPanel('dm-panel-routes', e.currentTarget));

    // Data Management > buses CRUD
    const bmAddBtn = document.getElementById('bmAddBtn');
    if(bmAddBtn) bmAddBtn.addEventListener('click', () => openBusForm(null));
    const bmCancelBtn = document.getElementById('bmCancelBtn');
    if(bmCancelBtn) bmCancelBtn.addEventListener('click', closeBusForm);
    const bmSaveBtn = document.getElementById('bmSaveBtn');
    if(bmSaveBtn) bmSaveBtn.addEventListener('click', saveBus);
    const bmTableBody = document.getElementById('bmTableBody');
    if(bmTableBody) bmTableBody.addEventListener('click', e => {
        const btn = e.target.closest('button[data-act]');
        if(!btn) return;
        const id = parseInt(btn.dataset.id, 10);
        if(btn.dataset.act === 'edit'){ const b = bmBuses.find(x=>x.busId===id); if(b) openBusForm(b); }
        else if(btn.dataset.act === 'del'){ deleteBus(id); }
    });

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
