# Integrare la sharing mobility (bike / scooter / monopattini) in OMNIMOVE via API

> Documento didattico — versione 2026-08-03. Nessuna modifica al codice: qui si progetta
> soltanto. Versione inglese equivalente: `omnimove_integration_en.md`.

---

## 1. Obiettivo e contesto

OMNIMOVE e' il journey planner multimodale di Cassino: dati origine e destinazione,
`JourneyPlannerService` costruisce fino a quattro opzioni — **BUS, BIKE, SCOOTER, WALK** — le
ordina con tre profili (FAST / BUDGET / ECO) e le arricchisce con meteo, Green Index (CO2),
costo e ritardi bus live da CASSITRACK.

Oggi BIKE e SCOOTER sono **stimate, non reali**:

- `planBike()` / `planScooter()` chiedono a Google Maps solo il percorso `bicycling`, poi
  calcolano il costo con tariffe statiche di `application.yml` (`elerent.bike.unlock`,
  `elerent.bike.per-minute`, `elerent.scooter.unlock`, `elerent.scooter.per-minute`);
- lo scooter ricava il tempo dalla distanza a velocita' fissa (`SPEED_SCOOTER = 20.0` km/h);
- **nessuno verifica che un mezzo esista davvero vicino all'utente**, che sia carico o che non
  sia gia' prenotato.

Obiettivo: collegare un operatore di sharing reale via API, cosi' che l'opzione diventi
*"monopattino Elerent a 120 m, batteria 68%, autonomia 14 km, 1,00 € + 0,25 €/min, tocca per
sbloccare"* invece di una stima teorica. Lo standard da usare e' **GBFS**.

---

## 2. GBFS in 5 minuti

**GBFS** (*General Bikeshare Feed Specification*, https://gbfs.org) e' lo standard *de facto*
per i dati **in tempo reale** dei servizi di sharing: bici, e-bike, monopattini, scooter, car
sharing free-floating. E' l'equivalente di GTFS-Realtime per la micromobilita', mantenuto da
MobilityData (spec: https://github.com/MobilityData/gbfs). Per noi conta che sia
**read-only, pubblico e quasi sempre senza autenticazione**, e che sia **JSON su HTTPS**:
si consuma con un normale `WebClient`, come gia' facciamo con CASSITRACK. Il catalogo dei feed
pubblici e' in `systems.csv` di MobilityData: **circa 34 feed italiani** al 2026-08-03.

### 2.1 Struttura dei file

Il punto di ingresso e' sempre `gbfs.json` (*discovery file*): elenca gli altri.

| File | Contenuto | Ci serve? |
|---|---|---|
| `gbfs.json` | indice dei feed, per lingua | **si', punto di ingresso** |
| `system_information.json` | operatore, timezone, contatti | si' (metadati) |
| `vehicle_types.json` | `form_factor`, `propulsion_type`, autonomia max | **si', per mappare BIKE vs SCOOTER** |
| `free_bike_status.json` | **mezzi free-floating: id, lat, lon, batteria, autonomia, disponibilita', deep link** | **si', e' il cuore** |
| `station_information.json` / `station_status.json` | stazioni fisiche e posti liberi | si', se l'operatore e' station-based (es. BikeMi) |
| `system_pricing_plans.json` | sblocco, €/min, valuta | **si', sostituisce le tariffe hardcoded** |
| `geofencing_zones.json` | GeoJSON di aree vietate / no-parking | si', per non proporre soste illegali |
| `system_hours.json`, `system_alerts.json` | orari, avvisi | opzionale |

### 2.2 TTL e refresh

Ogni file ha tre campi obbligatori in testa:

```json
{ "last_updated": 1754216400, "ttl": 60, "version": "2.3", "data": { } }
```

`last_updated` = epoch dell'ultimo aggiornamento lato operatore; `ttl` = **secondi di
validita'** della cache (`0` = non cacheare); `version` = versione della spec. Regola di buona
educazione, spesso condizione contrattuale: **mai fare polling piu' veloce del `ttl`**.
Tipicamente 60 s per `free_bike_status`, ore o giorni per i file statici.

### 2.3 Versioni

**1.x** legacy; **2.2 / 2.3** lo standard oggi piu' diffuso in Italia, quello che assumiamo;
**3.x** l'ultima, che rinomina `free_bike_status.json` in **`vehicle_status.json`** e rende
multilingua la struttura `data`. Difesa pratica: leggere `version`, supportare 2.x nativamente
e 3.x con un mapper dedicato; leggere i nomi dei file **dal discovery**, mai costruirli
concatenando stringhe; non dare mai per scontato un campo opzionale.

---

## 3. Esempio pratico: consumare Dott Roma passo passo

**Dott** (monopattini elettrici) pubblica un feed GBFS pubblico **senza autenticazione** per
Roma: funziona subito, da terminale, senza registrarsi.

### Passo 1 — discovery

```bash
curl -s https://gbfs.api.ridedott.com/public/v2/rome/gbfs.json | jq .
```

```json
{
  "last_updated": 1754216400,
  "ttl": 60,
  "version": "2.2",
  "data": {
    "en": {
      "feeds": [
        { "name": "system_information",   "url": "https://gbfs.api.ridedott.com/public/v2/rome/system_information.json" },
        { "name": "free_bike_status",     "url": "https://gbfs.api.ridedott.com/public/v2/rome/free_bike_status.json" },
        { "name": "vehicle_types",        "url": "https://gbfs.api.ridedott.com/public/v2/rome/vehicle_types.json" },
        { "name": "geofencing_zones",     "url": "https://gbfs.api.ridedott.com/public/v2/rome/geofencing_zones.json" },
        { "name": "system_pricing_plans", "url": "https://gbfs.api.ridedott.com/public/v2/rome/system_pricing_plans.json" }
      ]
    }
  }
}
```

I nomi dei feed sono **dati**, non convenzioni: il client costruisce una mappa `name -> url`.

### Passo 2 — i mezzi disponibili

```bash
curl -s https://gbfs.api.ridedott.com/public/v2/rome/free_bike_status.json | jq '.data.bikes | length'
```

Un record reale (catturato il 2026-08-03):

```json
{
  "bike_id": "7b7eeef5-ac9a-4905-96f4-67e7e092d429",
  "lat": 41.883018, "lon": 12.523322,
  "current_range_meters": 3536,
  "current_fuel_percent": 0.22,
  "is_disabled": false, "is_reserved": false,
  "vehicle_type_id": "dott_scooter",
  "rental_uris": { "android": "https://dott.app.link/...", "ios": "https://dott.app.link/..." }
}
```

| Campo | Uso in OMNIMOVE |
|---|---|
| `bike_id` | identificativo opaco e **rotante** (cambia a fine noleggio): mai persisterlo come chiave di dominio |
| `lat` / `lon` | distanza utente→mezzo (haversine), poi tratta a piedi con Google |
| `current_range_meters` | filtro di fattibilita': se `< distanza percorso`, il mezzo non basta |
| `current_fuel_percent` | 0.0–1.0 → in UI "batteria 22%" |
| `is_disabled`, `is_reserved` | **entrambi `false`** per poter proporre il mezzo |
| `vehicle_type_id` | join su `vehicle_types.json` → `form_factor` → modalita' OMNIMOVE |
| `rental_uris` | deep link all'app dell'operatore: il "call to action" finale |

### Passo 3 — dal tipo mezzo alla modalita'

```bash
curl -s https://gbfs.api.ridedott.com/public/v2/rome/vehicle_types.json | jq '.data.vehicle_types'
```

```json
[ { "vehicle_type_id": "dott_scooter", "form_factor": "scooter",
    "propulsion_type": "electric", "max_range_meters": 30000, "name": "Dott e-scooter" } ]
```

Mappatura adottata: `bicycle` (human o `electric_assist`) → **BIKE**; `scooter`,
`scooter_standing`, `scooter_seated` → **SCOOTER**; `moped`, `car`, `other` → **ignorati**
(fuori scope del planner attuale).

### Passo 4 — tariffe reali invece di quelle hardcoded

```bash
curl -s https://gbfs.api.ridedott.com/public/v2/rome/system_pricing_plans.json | jq '.data.plans[0]'
```

```json
{ "plan_id": "rome-payg", "name": "Pay as you go", "currency": "EUR",
  "price": 1.00, "is_taxable": false,
  "per_min_pricing": [ { "start": 0, "rate": 0.25, "interval": 1 } ] }
```

`price` = sblocco, `per_min_pricing[].rate` = €/minuto: esattamente la coppia che oggi
OMNIMOVE tiene fissa in `application.yml`. Col feed diventa dato dell'operatore.

### Passo 5 — i mezzi entro 300 m da un punto

```bash
curl -s https://gbfs.api.ridedott.com/public/v2/rome/free_bike_status.json \
 | jq '[.data.bikes[] | select(.is_disabled==false and .is_reserved==false)
        | select(((.lat-41.8830)*111320|fabs) < 300 and ((.lon-12.5233)*82500|fabs) < 300)]
       | length'
```

E' l'equivalente in shell di cio' che fara' `SharedMobilityService.findNearest(...)`.

### Altri feed italiani pubblici (utili per i test)

| Operatore | Citta' | Discovery URL |
|---|---|---|
| Dott | Roma | `https://gbfs.api.ridedott.com/public/v2/rome/gbfs.json` |
| Bird | Roma / Milano / Firenze | `https://mds.bird.co/gbfs/v2/public/rome/gbfs.json` |
| Lime | Napoli / Bari | `https://data.lime.bike/api/partners/v2/gbfs/naples/gbfs.json` |
| Zeus | Anzio (Lazio) | `https://zeus.city/api/v1/mds/gbfs/anzio/gbfs.json` |
| BikeMi | Milano (station-based) | `https://gbfs.urbansharing.com/bikemi.com/gbfs.json` |

Aggregatore libero (128 reti italiane, formato proprio non GBFS): **Citybikes**,
`https://api.citybik.es/v2` — comodo per un colpo d'occhio, ma non e' lo standard.

---

## 4. Progetto di integrazione in OMNIMOVE

**Principio guida: replicare il pattern `CassitrackClient`.** In OMNIMOVE tutti i dati della
flotta passano da un unico client (`it.unicas.omnimove.client.CassitrackClient`), wrapper
`WebClient` con base URL da configurazione che **degrada silenziosamente**: ogni metodo cattura
l'eccezione e restituisce lista vuota, cosi' il planner funziona anche con CASSITRACK spento.
La sharing mobility deve avere lo stesso contratto: **un solo punto di integrazione, nessun
errore propagato al viaggiatore**.

> Sezione di **sola progettazione**: nessuna classe qui descritta e' stata scritta.

### 4.1 Classi proposte

```
it.unicas.omnimove
├── client/GbfsClient.java              <-- NUOVO, gemello di CassitrackClient
├── dto/gbfs/
│   ├── GbfsEnvelopeDTO.java            last_updated, ttl, version, data<T>
│   ├── GbfsDiscoveryDTO.java           data.<lang>.feeds[] -> name/url
│   ├── GbfsVehicleDTO.java             bike_id, lat, lon, current_range_meters,
│   │                                   current_fuel_percent, is_disabled, is_reserved,
│   │                                   vehicle_type_id, rental_uris
│   ├── GbfsVehicleTypeDTO.java         vehicle_type_id, form_factor, propulsion_type
│   ├── GbfsStationInfoDTO.java  /  GbfsStationStatusDTO.java
│   ├── GbfsPricingPlanDTO.java         plan_id, currency, price, per_min_pricing[]
│   └── GbfsGeofencingZoneDTO.java      GeoJSON FeatureCollection (fase 2)
├── dto/SharedVehicleResponse.java      DTO di confine (mai il DTO GBFS grezzo)
└── service/
    ├── SharedMobilityService.java          filtro, mapping, nearest, tariffe
    ├── GbfsRefreshService.java             polling schedulato che rispetta il ttl
    └── SharedMobilitySettingsService.java  feature flag (gemello di GoogleApiSettingsService)
```

Convenzioni gia' in vigore da rispettare: package `it.unicas.omnimove.*`, **constructor
injection** (`@RequiredArgsConstructor`), Lombok, JSON **snake_case** via `@JsonProperty`,
controller sottili con la logica nei service, **mai entita' esposte**, `@Value` per la config.

### 4.2 `GbfsClient` — forma proposta

```
@Component
public class GbfsClient {
    Map<String,String> discoverFeeds(String providerId);           // name -> url, cache lunga
    List<GbfsVehicleDTO>     fetchFreeVehicles(String providerId); // free_bike_status | vehicle_status
    List<GbfsVehicleTypeDTO> fetchVehicleTypes(String providerId);
    List<GbfsPricingPlanDTO> fetchPricingPlans(String providerId);
    boolean isAvailable(String providerId);                        // sonda il discovery
}
```

Requisiti non funzionali: **timeout corto** (2–3 s), perche' il planner e' sincrono e non puo'
restare appeso su un feed lento; **nessuna eccezione verso l'alto** (lista vuota + `log.warn`,
come `CassitrackClient.getActiveVehicles()`); **gestione versione** (se `version` inizia per
`3.`, leggere `vehicle_status`); **un `WebClient` per provider**, base URL dal discovery.

### 4.3 `SharedMobilityService` — logica di dominio

1. **Disponibilita'**: scartare `is_disabled == true` o `is_reserved == true`.
2. **Mapping tipo → modalita'** secondo la tabella del §3 passo 3.
3. **Nearest vehicle**: haversine sui candidati, top-N, poi (opzionale) una chiamata Google
   `walking` per la distanza reale a piedi — **lo stesso schema di
   `JourneyPlannerService.findNearestStopId()`**, che fa haversine top-3 e poi Google.
4. **Autonomia**: scartare i mezzi con `current_range_meters` < distanza del percorso.
5. **Tariffe**: se il feed espone `system_pricing_plans` usare quelle, **altrimenti** ricadere
   sui valori `elerent.*` di `application.yml`. Il fallback e' obbligatorio: e' cio' che rende
   l'integrazione retro-compatibile.

### 4.4 Cache e refresh

`GbfsRefreshService`: `@Scheduled(fixedDelay = 30_000)` sui feed veloci rispettando il `ttl`
dichiarato; discovery, `vehicle_types` e `pricing_plans` ogni ora; bootstrap su
`ApplicationReadyEvent` con backoff esponenziale, come gia' fa `NetexImportService`.

**Redis** (gia' in stack, `StringRedisTemplate`) come cache condivisa, nello stile delle chiavi
telemetria `bus:latest:<id>`: `gbfs:vehicles:<providerId>` con TTL = `ttl` del feed;
`gbfs:types:<providerId>` e `gbfs:pricing:<providerId>` TTL 1 h; `gbfs:discovery:<providerId>`
TTL 24 h.

**Nessuna tabella nuova, nessuna migrazione Flyway** per i mezzi: sono dati volatili con
identificativo a rotazione. Persisterli sarebbe rumore tecnico e discutibile sul piano privacy.

### 4.5 Punti di innesto nel planner

In `JourneyPlannerService`, `planBike()` e `planScooter()` diventano:

1. chiedere a `SharedMobilityService` il mezzo disponibile piu' vicino all'origine;
2. **se non ce n'e' nessuno entro il raggio configurato → restituire `null`**, cioe' escludere
   l'opzione: e' coerente con la politica gia' in vigore (oggi l'opzione sparisce quando Google
   non restituisce un percorso reale);
3. aggiungere una **gamba `WALK`** origine → mezzo prima della gamba BIKE/SCOOTER (`JourneyLeg`
   supporta gia' `mode`, `from`, `to`, `duration_minutes`, `distance_metres`, `instruction`);
4. calcolare il costo con le tariffe del feed;
5. esporre `rental_uris` nel campo `instruction` o — meglio — con un nuovo
   `@JsonProperty("deep_link")` su `JourneyLeg`: e' un DTO, **non** un'entita', quindi **niente
   migrazione**.

Green Index invariato: `GreenIndexService` assegna gia' 0 g CO2/km a BIKE e SCOOTER.

### 4.6 Endpoint REST (opzionale, per la mappa)

`GET /api/v1/journeys/vehicles/nearby?lat=&lon=&radius_m=&mode=` in `JourneyController`:
autenticato (TRAVELLER o ADMIN) come tutto `/api/v1/journeys/**`; validazione con
`spring-boot-starter-validation` (lat −90..90, lon −180..180, `radius_m` 50..2000); **rate
limit** via `RateLimiterService`, bucket nuovo `vehicles-nearby` 60/utente/ora in linea con
`stop-arrivals`; risposta `List<SharedVehicleResponse>`, **mai** i DTO GBFS grezzi.

### 4.7 Configurazione — niente hardcoded

In `application.yml`, nello stile del blocco `cassitrack:` esistente:

```yaml
sharedmobility:
  enabled: ${GBFS_ENABLED:false}
  search-radius-metres: ${GBFS_SEARCH_RADIUS_M:400}
  refresh-seconds: ${GBFS_REFRESH_SECONDS:30}
  providers:
    elerent:
      discovery-url: ${GBFS_ELERENT_URL:}          # vuoto = provider disattivato
      api-key:       ${GBFS_ELERENT_API_KEY:}      # solo se l'operatore lo richiede
    dott-demo:
      discovery-url: ${GBFS_DOTT_URL:https://gbfs.api.ridedott.com/public/v2/rome/gbfs.json}
```

e in `.env.example`: `GBFS_ENABLED=false`, `GBFS_ELERENT_URL=`, `GBFS_ELERENT_API_KEY=`,
`GBFS_SEARCH_RADIUS_M=400`. **Chiave assente = funzionalita' spenta, non errore**: e' la regola
gia' applicata a Google Maps, meteo e Anthropic in tutto il progetto.

Per il toggle a runtime dalla dashboard admin, seguire il pattern `app_settings`: nuova
migrazione **`V16__sharedmobility_settings.sql`** (mai modificare le V1–V15 gia' applicate) che
inserisce `sharedmobility.enabled` con `ON CONFLICT DO NOTHING`, piu' un
`SharedMobilitySettingsService` gemello di `GoogleApiSettingsService` (cache
`ConcurrentHashMap`, write-through, default sicuro). Meglio un service fratello che allargare
la whitelist di quello Google, volutamente limitata a due chiavi.

---

## 5. Elerent: chi sono e cosa chiedere

**Elerent** (https://elerent.com) e' un operatore italiano di sharing (e-scooter, e-bike,
scooter elettrici) presente in circa **40 citta' italiane, Cassino inclusa**. E' il partner
naturale del progetto: opera fisicamente sul territorio che OMNIMOVE serve ed e' gia' citato
nel codice come fornitore delle tariffe bike/scooter.

**Stato al 2026-08-03: Elerent non pubblica alcuna API o feed GBFS.** Va richiesto formalmente.

### 5.1 Checklist da sottoporre a Elerent

1. **Un feed GBFS 2.3+** (o 3.x) in HTTPS con almeno: `gbfs.json`, `system_information.json`,
   `vehicle_types.json`, `free_bike_status.json` (o `vehicle_status.json` in 3.x),
   `geofencing_zones.json`, `system_pricing_plans.json`.
2. **Refresh ≤ 60 secondi** su `free_bike_status`, con `ttl` dichiarato coerente.
3. **Copertura almeno di Cassino** — idealmente un feed per citta', o un feed unico con
   `geofencing_zones` che separi le aree operative.
4. **Deep link di noleggio** (`rental_uris.android` / `.ios` / `.web`) per portare l'utente
   dalla schermata OMNIMOVE allo sblocco nell'app Elerent.
5. **Accesso**: preferibile pubblico e senza autenticazione (come Dott, Bird, Lime); in
   subordine una **API key di sola lettura** — la nostra configurazione la prevede gia'.
6. **Condizioni d'uso**: rate limit ammesso, obblighi di attribuzione, licenza dei dati
   (idealmente ODbL o CC-BY), contatto tecnico per i disservizi.
7. **Ambiente di test / staging** con dati finti, per sviluppare senza toccare la produzione.

Argomento negoziale utile: **il feed GBFS porta traffico all'operatore**, non lo sottrae —
prenotazione e pagamento restano nell'app Elerent, OMNIMOVE fa da vetrina.

### 5.2 Alternativa: MDS (e perche' non fa per noi)

**MDS** (*Mobility Data Specification*,
https://github.com/openmobilityfoundation/mobility-data-specification) e' lo standard per i
dati **a livello di viaggio** (percorsi completi, eventi di stato dei mezzi). E' molto piu'
ricco di GBFS, ma e' pensato per la **vigilanza delle autorita' cittadine**, non per app
passeggeri; l'accesso e' tipicamente riservato e contrattualizzato con l'ente pubblico; e
contiene dati sensibili sul piano privacy. Quindi **GBFS e' la richiesta giusta per OMNIMOVE**;
MDS resta un'ipotesi solo se il Comune di Cassino diventasse partner del progetto.

### 5.3 Fallback se Elerent non risponde in tempo

1. **Feed simulato** in stile Elerent, generato in locale (§6) — l'opzione scelta per settembre.
2. **Puntare a un operatore pubblico su un'altra citta'** (Dott Roma, Zeus Anzio) dichiarando
   in demo che le coordinate non sono di Cassino: dimostra che il client funziona con feed veri.
3. **Restare sulle stime attuali** (`elerent.*` in `application.yml`): il codice non regredisce,
   semplicemente non guadagna la disponibilita' reale.

---

## 6. Piano demo settembre 2026 — simulare un feed Elerent

Obiettivo: **mostrare l'integrazione funzionante prima che esista un accordo**, con dati su
Cassino, senza mai far credere che il feed sia ufficiale.

### 6.1 File statici serviti in locale

Una cartella `tools/gbfs-sim/` con `gbfs.json`, `system_information.json`,
`vehicle_types.json`, `system_pricing_plans.json` e `free_bike_status.json` (l'unico
rigenerato di continuo).

`gbfs.json` con URL che puntano al server locale:

```json
{ "last_updated": 1756900000, "ttl": 60, "version": "2.3",
  "data": { "it": { "feeds": [
    { "name": "system_information",   "url": "http://localhost:8090/system_information.json" },
    { "name": "vehicle_types",        "url": "http://localhost:8090/vehicle_types.json" },
    { "name": "free_bike_status",     "url": "http://localhost:8090/free_bike_status.json" },
    { "name": "system_pricing_plans", "url": "http://localhost:8090/system_pricing_plans.json" }
  ] } } }
```

`free_bike_status.json` con coordinate di Cassino (indicative, da rifinire sui punti reali):

```json
{ "last_updated": 1756900000, "ttl": 60, "version": "2.3",
  "data": { "bikes": [
    { "bike_id": "sim-elr-0001", "lat": 41.493833, "lon": 13.828778,
      "current_range_meters": 18400, "current_fuel_percent": 0.68,
      "is_disabled": false, "is_reserved": false,
      "vehicle_type_id": "elerent_scooter",
      "rental_uris": { "web": "https://elerent.com/" } },
    { "bike_id": "sim-elr-0002", "lat": 41.490600, "lon": 13.832000,
      "current_range_meters": 6100, "current_fuel_percent": 0.24,
      "is_disabled": false, "is_reserved": false,
      "vehicle_type_id": "elerent_scooter",
      "rental_uris": { "web": "https://elerent.com/" } },
    { "bike_id": "sim-elr-0101", "lat": 41.475700, "lon": 13.814500,
      "current_range_meters": 0, "current_fuel_percent": 0.92,
      "is_disabled": false, "is_reserved": false,
      "vehicle_type_id": "elerent_bike",
      "rental_uris": { "web": "https://elerent.com/" } }
  ] } }
```

Punti utili per la demo: **Stazione FS Cassino** (~41.4938, 13.8288), **centro / Piazza
Miranda** (~41.4906, 13.8320), **Campus UNICAS Folcara** (~41.4757, 13.8145).

```bash
cd tools/gbfs-sim && python3 -m http.server 8090
# poi in .env:  GBFS_ENABLED=true   GBFS_ELERENT_URL=http://localhost:8090/gbfs.json
curl -s http://localhost:8090/gbfs.json | jq '.data.it.feeds[].name'
curl -s http://localhost:8090/free_bike_status.json | jq '.data.bikes | length'
```

Stessa verifica del §3, sorgente diversa: e' esattamente il punto.

### 6.2 Mezzi che si muovono: riusare il pattern di `gps_simulator2.py`

Nella root del repo c'e' gia' `gps_simulator2.py`, il simulatore GPS dei bus CASSITRACK: carica
mezzi e percorsi da PostgreSQL, **interpola le posizioni fra punti reali**, pubblica a intervallo
regolare (`--interval`) e ha un caricatore `.env` minimale. E' lo scheletro che serve.

Uno script gemello `tools/elerent_gbfs_sim.py` dovrebbe: partire da N posizioni di parcheggio
verosimili a Cassino (stazione, centro, campus, piazze); a ogni tick (10–30 s) spostare qualche
mezzo di poche decine di metri, far scendere `current_fuel_percent`, ricalcolare
`current_range_meters` e alternare qualche `is_reserved`; **riscrivere `free_bike_status.json`
in modo atomico** (file temporaneo + `os.replace`, cosi' il backend non legge mai un JSON
troncato); aggiornare `last_updated` a ogni scrittura mantenendo `ttl: 60`.

Vantaggio: la UI mostra mezzi che si muovono e batterie che calano, e il backend intanto
esercita **il vero codice GBFS**, non una modalita' finta.

### 6.3 Copione della demo (5 minuti)

1. Mostrare che il feed **Dott Roma reale** risponde (§3, passi 1–2): lo standard esiste ed e'
   pubblico.
2. Mostrare il feed **simulato Elerent Cassino** con la stessa identica struttura.
3. Pianificare Stazione → Campus Folcara: l'opzione SCOOTER ora dice *"monopattino a 120 m,
   batteria 68%"* con la gamba a piedi fino al mezzo.
4. Fermare il simulatore o marcare tutti i mezzi `is_disabled: true` → l'opzione SCOOTER
   **sparisce dai risultati** invece di dare errore: e' la degradazione controllata, la stessa
   filosofia con cui il planner tratta CASSITRACK offline.
5. Chiudere spiegando che sostituire il simulatore con Elerent significa cambiare **una sola
   variabile d'ambiente**, `GBFS_ELERENT_URL`.

### 6.4 Regole di onesta' per la demo

Etichettare sempre, in UI e nelle slide, i dati simulati come **"feed dimostrativo — non dati
Elerent ufficiali"**; non usare loghi o marchi Elerent come se il servizio fosse gia' integrato;
dare ai `bike_id` simulati un prefisso riconoscibile (`sim-elr-…`).

---

## 7. Riferimenti

**Standard**
- GBFS — sito ufficiale: https://gbfs.org
- GBFS — specifica e catalogo `systems.csv`: https://github.com/MobilityData/gbfs
- MDS: https://github.com/openmobilityfoundation/mobility-data-specification

**Feed italiani pubblici (verificati il 2026-08-03)**
- Dott Roma: https://gbfs.api.ridedott.com/public/v2/rome/gbfs.json
- Bird Roma (anche Milano, Firenze): https://mds.bird.co/gbfs/v2/public/rome/gbfs.json
- Lime Napoli (anche Bari): https://data.lime.bike/api/partners/v2/gbfs/naples/gbfs.json
- Zeus Anzio: https://zeus.city/api/v1/mds/gbfs/anzio/gbfs.json
- BikeMi Milano (station-based): https://gbfs.urbansharing.com/bikemi.com/gbfs.json
- Citybikes API (aggregatore, 128 reti italiane): https://api.citybik.es/v2

**Operatore target**
- Elerent: https://elerent.com — attivo in ~40 citta' italiane, Cassino inclusa; nessuna API
  pubblica al 2026-08-03.

**Codice OMNIMOVE citato**
- `omnimove-backend/src/main/java/it/unicas/omnimove/client/CassitrackClient.java` — pattern client da replicare
- `omnimove-backend/src/main/java/it/unicas/omnimove/service/JourneyPlannerService.java` — `planBike()`, `planScooter()`, `findNearestStopId()`
- `omnimove-backend/src/main/java/it/unicas/omnimove/service/GoogleApiSettingsService.java` — feature flag su `app_settings`
- `omnimove-backend/src/main/resources/application.yml` — blocco `elerent:` con le tariffe attuali
- `gps_simulator2.py` (root del repo) — pattern del simulatore da riusare
