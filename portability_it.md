# Portabilità di CASSITRACK e OMNIMOVE verso altre città

> Analisi di portabilità — agosto 2026. Documento gemello: `portability_en.md`.
> Documenti correlati: `cassitrack_integration_it.md`, `omnimove_integration_it.md`, `payments_integration_it.md`.

## 1. Domanda e verdetto

**Il sistema, così com'è, potrebbe funzionare in un'altra città?**

| Sistema | Così com'è | Solo configurazione | Con ricarica dati | Con modifiche al codice |
|---|---|---|---|---|
| CASSITRACK | ❌ | ❌ | ❌ (necessaria ma non sufficiente) | ✅ **3–5 giorni** (fork una-tantum) |
| OMNIMOVE | ❌ | ❌ | ❌ (necessaria ma non sufficiente) | ✅ **2–3 giorni** (fork una-tantum) |

**Nessuno dei due ha problemi di architettura.** Il modello di dominio è city-agnostic (coordinate WGS84, vocabolario GTFS-like, nessun vincolo geografico nello schema). La "cassinità" è concentrata in **costanti cablate, stringhe di branding e dati seed** — non nel design. OMNIMOVE è messo meglio perché ha già fatto la scelta giusta: i dati di trasporto **non sono seminati nelle migrazioni ma importati a runtime** dal feed NeTEx di CASSITRACK.

Vincolo strutturale comune: entrambi sono **single-tenant / single-city**. Nessuna entità `City`/`Agency`/`Tenant` nello schema: due città = due deployment completi (DB, Redis, Influx, broker separati).

---

## 2. CASSITRACK

### 2.1 Blocker critici

**B1 — Geofence cablato nell'ingestione MQTT (critico, silenzioso).**
`cassitrack-backend/.../mqtt/MqttMessageHandler.java:56-59`:

```java
private static final double LAT_MIN = 41.40;  LAT_MAX = 41.60;
private static final double LON_MIN = 13.70;  LON_MAX = 14.00;
```

Applicato in `isValid()` (righe 165-166): qualsiasi telemetria fuori dal box di Cassino (~20×25 km) viene **scartata in silenzio** (solo una riga di audit "validation failed"). Un bus di Milano non arriverebbe mai al DB, a Influx né alla mappa. È il singolo blocker più grave — e il fix a più alta leva: esternalizzare 4 costanti in `@Value`, o calcolare il box dalle fermate in DB più un margine.

**B2 — L'orario esiste solo come SQL scritto a mano.**
`trips` e `scheduled_stops` non hanno né controller né UI di scrittura (la Data Management UI ha solo i tab Buses/Stops/Routes — `cassitrack-fleetmanager.html:351-353`). L'unico modo di creare un orario è il generatore PL/pgSQL `pg_temp.gen_line(...)` in `V5__refresh_timetable_magni.sql:52-60`, chiamato con array letterali. Conseguenze a cascata senza orario:
- una linea con meno di 2 fermate schedulate **non compare** in `GET /api/v1/routes` (`RouteController.java:69-77`);
- ogni bus risulta `NO_TRIP` → niente linea, ritardo, ETA, aderenza; pagina analytics vuota.

**B3 — Le migrazioni Flyway mescolano schema e seed di Cassino.**
V2 (linee, fermate, bus MAGNI-00x, utenti `@cassitrack.it`), V4/V5 (aprono con `DELETE FROM` e riseminano le linee e le 20 fermate reali di Cassino), V10 (555 righe di geometrie stradali cassinati), V11–V14 (patch specifiche della flotta). Non si possono saltare — Flyway esegue l'intera catena. Fix pulito: separare `db/schema` da `db/seed-<città>` e pilotare `spring.flyway.locations` da env (`application.yml:30`).

### 2.2 Blocker minori

| # | Cosa | Dove |
|---|---|---|
| B4 | Geometrie di linea senza percorso runtime (pipeline: `tools/crea_path.html` → JSON → `tools/import_route_shapes.py` → migrazione a mano). Mitigato: senza geometrie la mappa ripiega su polilinee fermata-fermata tratteggiate | `V10:5-10`, `RouteController.java:59-63` |
| B5 | Centro mappa cablato `[41.497, 13.822]`, nessun `fitBounds` | `cassitrack-fleetmanager.js:64-65` |
| B7 | Timezone `Europe/Rome` cablata in 5 punti — e **incoerente**: `AnalyticsService.java:208,391` usa `systemDefault()` (bug latente già oggi) | `ETAService.java:46-47`, `ScheduleAdherenceService.java:32`, `TripResolutionService.java:42`, `StopController.java:42`, `AiOrchestrationService.java:78` |
| B8 | Branding "MAGNI / Cassino / Linea 16" in ~14 punti (UI, OpenAPI, prompt AI) | `application.yml:115`, `AiOrchestrationService.java:200-205,321-324`, `cassitrack-analytics.html:26`, ecc. |
| B9 | Topic MQTT non parametrizzati (`cassitrack/+/position`), `ProducerRef` SIRI `"CASSITRACK"` cablato, codespace NeTEx `"CASSITRACK:"` in 11 punti. **Nota**: NeTEx/SIRI sono solo export — non esiste un importer, quindi gli standard oggi non aiutano l'onboarding | `application.yml:82-83`, `Siri.java:58`, `NetexController.java` |

### 2.3 Cosa è già portabile

Tutta l'infrastruttura è env-driven (Postgres, Redis, Influx, broker MQTT, JWT, token SSE, CORS, chiave Anthropic, context path). I CRUD di fermate/linee/bus accettano qualunque coordinata del mondo. Lo schema è PostGIS SRID 4326 e `route_shapes` è modellato esplicitamente su GTFS `shapes.txt`. Le soglie operative (aderenza 3/10 min, gate 80 m, crowding) sono generiche.

### 2.4 Onboarding nuova città (passi)

1. **Dati** (60–70% dell'effort): fermate con coordinate → linee → **orario** (nuova migrazione col pattern `gen_line`, rispettando la regola headway > tempo di giro di `V13:27-40`) → geometrie (opzionali) → bus con `current_vehicle_id` = radio id degli OBU → reset utenti.
2. **Chirurgia migrazioni**: tenere V1/V7/V8/V9 (schema), sostituire V2–V6 e V10–V14 (seed).
3. **Codice**: esternalizzare bbox (B1), centro mappa (B5), timezone (B7), stringhe (B8), codespace/topic (B9).
4. **Ops**: DB, bucket Influx, Redis, credenziali broker, CORS dedicati (già tutto env-driven).

---

## 3. OMNIMOVE

### 3.1 Cosa è già portabile (la parte buona)

- **Nessun dato di trasporto nelle migrazioni**: `NetexImportService.importDataFromCassitrack()` cancella e reimporta tutto (fermate, linee, trips, orari, bus, geometrie) dal feed a runtime. Le migrazioni creano solo schema (+2 utenti demo).
- Google Distance Matrix parametrizzato per richiesta (lat/lon pure, nessun bias regionale).
- Endpoint CASSITRACK interamente configurabile: `CASSITRACK_URL`, `CASSITRACK_API_TOKEN`, `CASSITRACK_NETEX_URL`.
- Il frontend non ha destinazioni preimpostate: autocomplete e marker derivano da `GET /journeys/stops`, con `fitBounds` sulle fermate caricate.
- Tariffe micromobilità come properties (`elerent.*`); `GreenIndexService` (fattori EEA) e `TrafficAwareETAService` non richiedono alcuna modifica.

### 3.2 Blocker — i più insidiosi **falliscono in silenzio**

**A1 — Fallback su coordinate di Cassino nel planner (il fix più importante del progetto).**
`JourneyPlannerService.java:593-605`: se una fermata è nulla/sconosciuta, `getStopLat/Lon` restituisce `41.4925 / 13.8306` (centro di Cassino). In un'altra città produrrebbe **itinerari plausibili ma privi di senso, senza alcun errore**. Deve restituire `Optional`/lanciare, non una costante magica.

**A2 — La mappa "torna a casa".** `omnimove-traveller.js:979`: chiudere un viaggio fa `setView` fisso su Cassino — bug reale anche oggi. (La `:237` all'avvio è solo un flash, corretta subito da `fitBounds`.)

**A3 — GPS negato ⇒ utente teletrasportato a Via Folcara.** `omnimove-traveller.js:482-484`: la promise *risolve* con coordinate cassinati fittizie invece di fallire — l'utente non viene mai avvisato.

**Meteo per nome-città fisso.** `WeatherService.java:36-40,90`: interroga OpenWeatherMap con `weather.api.city: Cassino` (proprietà peraltro **assente da `.env.example`**), non con le coordinate del viaggio. Nomi ambigui (Cambridge, Springfield) si risolvono in modo imprevedibile.

**Operatore e tariffe saldati nel codice.** "Elerent" e `elerent.it` nelle etichette del planner (`JourneyPlannerService.java:437-473`); `COST_BUS = 1.00` (riga 62) e `SPEED_SCOOTER = 20.0` km/h (riga 61) costanti e non properties; simbolo `€` cablato nella UI; negozio biglietti mock con "Bus Line 16 / zona urbana A" (`omnimove-traveller.html:150-256`).

**Prompt AI vincolato a Cassino.** `AiOrchestrationService.java:298-309` ("assistente per Cassino... campus Folcara... riporta la conversazione al trasporto di Cassino") + fallback offline che citano "Bus 16".

**Timezone e locale.** `Europe/Rome` in 4 punti (`JourneyPlannerService.java:518,874`, `AiOrchestrationService.java:178`): fuori CET tutti i tempi di attesa sarebbero sbagliati (`arrivalSeconds` interpretato su mezzanotte di Roma). Locale `it-IT`/`en-GB` letterali nel JS.

**Accoppiamento a un solo feed.** Un solo `CassitrackClient` con `baseUrl` fissato alla costruzione; l'import **cancella l'intero dataset** prima di reimportare; il dialetto del feed è CASSITRACK-specifico (id `NAMESPACE:Type:LOCALID`, campi estesi italiani `targa`/`numeroPosti`) — un feed NeTEx/GTFS standard richiede un adapter. Inoltre `SiriConsumerService.java:26` cabla `localhost:8280` (oggi disattivato, blocker latente).

**Assunzioni di scala** (non cassinati, ma mordono su città grandi): `findNearestStopId` fa `findAll()` + haversine su ogni fermata + 3 chiamate Google bloccanti; il contesto AI fa una chiamata HTTP **per ogni fermata per ogni messaggio**; cap fisso `.limit(500)` sulle fermate; marker non clusterizzati. Oltre ~200 fermate serve rilavorazione (+1–2 settimane).

### 3.3 Onboarding nuova città (passi)

0. **Prerequisito a monte** (il vero collo di bottiglia, esterno a OMNIMOVE): un CASSITRACK della nuova città già attivo che pubblichi NeTEx nello stesso dialetto.
1. Infrastruttura fresca (DB **nuovo**, non clone del DB di Cassino — è l'unico caso in cui la V15, correttamente guardata con `WHERE EXISTS`, può fare danni), nuovi segreti.
2. Configurazione: puntare `CASSITRACK_*` alla nuova istanza; `weather.api.city/country` (e aggiungerle a `.env.example`); tariffe `elerent.*` → operatore locale.
3. Codice (in ordine di gravità): fallback A1 → `setView` A2 → GPS A3 → `COST_BUS`/`SPEED_SCOOTER` a properties → `mobility.operator.name/url` → `omnimove.city.name/timezone` (meteo, AI, 4 siti timezone) → rebranding → ticket-shop mock → `SiriConsumerService` → locale configurabile.
4. Dati: eliminare i ~40 utenti simulati di `V4__sim_users.sql` (password nota condivisa `test1234`) e ruotare le credenziali demo di V2. Al boot l'app importa il NeTEx da sola (`OmnimoveApplication.java:43-76`, 10 retry).
5. Verifica: mappa centrata localmente, meteo della città giusta, AI che non nomina Cassino, attese coerenti con l'orario locale nel fuso locale.

---

## 4. Stime di effort combinate

| Scenario | CASSITRACK | OMNIMOVE |
|---|---|---|
| Fork una-tantum, città italiana di dimensioni simili | **3–5 giorni** (di cui 60–70% produzione dati: orario SQL + geometrie) | **2–3 giorni** |
| Città non italiana / fuori CET | incluso sopra + timezone | **+2–3 giorni** (timezone, locale, stringhe italiane residue) |
| Città più grande (>500 fermate) | — | **+1–2 settimane** (indice spaziale, cap, cluster, contesto AI) |
| Renderli genuinamente configurabili (sempre single-tenant) | **1,5–2,5 settimane** | **~1 settimana** |
| Onboarding senza SQL (importer GTFS/NeTEx + API orario) | **+3–5 settimane** — trasforma "giorni per città" in "un pomeriggio per città" | **+1–2 settimane** (adapter per feed standard) |
| Vero multi-tenant (più città in un deployment) | **+3–4 settimane** | **+3–4 settimane** |

---

## 5. Le prime 5 mosse (a più alta leva)

1. **Esternalizzare il bounding box MQTT** di CASSITRACK (4 righe che, fuori da un box di 20×25 km, azzerano silenziosamente l'intero prodotto).
2. **Eliminare i fallback silenziosi su coordinate di Cassino** in OMNIMOVE (`JourneyPlannerService.java:593-605`, `omnimove-traveller.js:482-484,979`).
3. **Separare schema e seed** nella catena Flyway di CASSITRACK (`db/schema` + `db/seed-<città>` via `spring.flyway.locations`).
4. **Config unica di città**: `app.city`, `app.timezone`, `mobility.operator.*` consumate da mappa, meteo, AI e branding di entrambi i sistemi.
5. **Importer GTFS** per CASSITRACK (lo schema è già GTFS-shaped): la mossa strategica che rende l'onboarding di una città un lavoro da un pomeriggio.

## 6. Lezioni per il corso

- **"Portabile per architettura" ≠ "portabile in pratica"**: entrambi i sistemi hanno il design giusto ma decine di costanti cablate. La disciplina sta nel *non scrivere mai* un letterale geografico/aziendale fuori dalla configurazione.
- **I silent failure sono la categoria peggiore di bug**: un geofence che scarta senza errore e un fallback che inventa coordinate producono sistemi che *sembrano* funzionare. Meglio un crash esplicito di un dato plausibile ma falso.
- **Seed vs import**: OMNIMOVE (import a runtime) si porta in 2–3 giorni; CASSITRACK (seed nelle migrazioni) ne richiede 3–5 e SQL a mano. La differenza è tutta in quella scelta.
- **Gli standard servono solo se si importano**: esportare NeTEx non aiuta l'onboarding; importarlo lo trasformerebbe.
