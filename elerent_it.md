# Integrazione Elerent (bike sharing) in OMNIMOVE

> Versione inglese: [elerent_en.md](elerent_en.md)

Elerent gestisce il servizio di bike/scooter sharing a Cassino sulla piattaforma
**ATOM Mobility** (API "RideAtom": <https://app.rideatom.com/api/docs>, tag *Sharing*).

OMNIMOVE mostra i mezzi Elerent disponibili e le zone operative sulla mappa del
traveller, **in sola lettura**: nessuno sblocco, nessun pagamento, nessuna scrittura
verso Elerent. L'integrazione vive interamente in `omnimove-backend` — CASSITRACK
non è coinvolto (le bici non sono flotta monitorata, sono dati di disponibilità).

La **App-Public-Key** di Elerent è ora configurata e l'integrazione gira contro la
piattaforma reale: le **zone disegnate sulla mappa sono quelle di Elerent**. Le
posizioni dei mezzi sono l'unica cosa che la chiave non apre — `/get-vehicles`
pretende anche un token bearer di utente — quindi la flotta resta quella
**simulata** finché Elerent non ne rilascia uno (§1.1).

## Chi è chi: Elerent, ATOM Mobility, RideAtom

I tre nomi che compaiono in questo documento giocano ruoli diversi:

- **Elerent** è l'*operatore*: possiede fisicamente le bici e i monopattini a
  Cassino e gestisce il servizio verso i clienti (app, tariffe, assistenza).
- **ATOM Mobility** è il *fornitore di tecnologia*: un'azienda (con sede a Riga,
  Lettonia) che offre una piattaforma software "white-label" per servizi di
  sharing di bici, monopattini, scooter e auto. Un operatore come Elerent non
  costruisce app, backend, gestione flotta e pagamenti da zero: li "affitta" da
  ATOM, che gli fornisce l'app brandizzata col suo nome, la dashboard di
  gestione e le API.
- **RideAtom** (`app.rideatom.com`) è il dominio dell'infrastruttura API di
  ATOM. Le API documentate su <https://app.rideatom.com/api/docs> non sono
  quindi API "di Elerent" in senso stretto, ma le API della piattaforma ATOM,
  identiche per tutti gli operatori che la usano. La **App-Public-Key** serve
  esattamente a questo: identifica l'installazione di *quale* operatore si sta
  interrogando — nel nostro caso quella di Elerent.

Questa struttura spiega due scelte che ritroverai più avanti: il
`RideAtomClient` fa parsing *difensivo* dei campi, perché le risposte possono
variare leggermente tra installazioni e versioni della piattaforma (§1); e vale
la pena chiedere se l'installazione espone un feed **GBFS**, perché molte
installazioni ATOM lo offrono di serie (§4.1).

---

## 1. Attivazione dell'integrazione reale

### Passo 1 — La chiave

La **App-Public-Key** di Elerent va nell'header `App-Public-Key` di ogni endpoint
del tag *Sharing*; è ciò che seleziona l'installazione di Elerent all'interno
della piattaforma ATOM condivisa. La chiave sta in `omnimove-backend/.env`
(gitignored), non nel repository.

Che la chiave sia davvero quella di Elerent e non un account demo è verificato:
chiamare `/get-zones` **senza** chiave restituisce le zone vetrina di ATOM (Riga,
Bordeaux), con una chiave non valida restituisce `{"message": "No access"}`, con
la nostra restituisce 671 zone di tutte le città Elerent — 29 delle quali a
Cassino.

### Passo 2 — Configurare le variabili d'ambiente

```bash
ELERENT_API_MOCK=false
ELERENT_PUBLIC_KEY=<chiave fornita da Elerent>
# opzionali:
ELERENT_USER_TOKEN=                                             # vedi §1.1
ELERENT_VEHICLES_FALLBACK_MOCK=true                             # default
ELERENT_API_URL=https://app.rideatom.com/openapi/v1.0/sharing   # default
```

La configurazione corrispondente è in `omnimove-backend/src/main/resources/application.yml`,
blocco `elerent.api` (base-url, public-key, user-token, mock,
vehicles-fallback-mock, centre-lat, centre-lon, radius-km).

### Passo 3 — Riavviare omnimove-backend

All'avvio il log indica quale provider è attivo:

- `MockElerentClient ready — N simulated vehicles in Cassino …` → il mock è
  caricato (come provider se `mock=true`, altrimenti come fallback dei mezzi)
- `RideAtomClient → https://… (key configured, user token absent, vehicle
  fallback on)` → API reale

Non serve altro: frontend, endpoint REST e service sono identici nei due casi.

### 1.1 Cosa apre davvero la public key

La pagina di documentazione presenta l'intero tag *Sharing* come una famiglia
sola, ma la specifica dichiara un requisito di sicurezza diverso per operazione,
e il server lo applica:

| Endpoint | Auth dichiarata | Realtà con la nostra chiave |
|---|---|---|
| `POST /get-zones` | `App-Public-Key` | **200** — zone Elerent reali |
| `POST /get-vehicles` — body `{user_latitude, user_longitude, radius_in_km}` | `App-Public-Key` **+** `Authorization` | **401 `Unauthorized Access`** |

`/get-vehicles` restituisce posizioni, targa (`nr`), batteria e tipo, ma solo per
un utente autenticato: il token bearer lo emette il sistema account di ATOM
(verifica del numero di telefono), e l'unica alternativa documentata — il
parametro `user_id` — "works with secret key only", cioè una credenziale che un
operatore non consegna a un planner di terze parti.

Ci sono quindi due strade per avere le posizioni reali, ed entrambe sono una
domanda da fare a Elerent, non una modifica al codice:

1. un **token di servizio** per OMNIMOVE, da mettere in `ELERENT_USER_TOKEN`; il
   client lo invia già come `Authorization: Bearer …` quando è valorizzato;
2. un **feed GBFS**, che non richiede alcuna credenziale — vedi §4.1.

Nel frattempo `RideAtomClient` intercetta il 401, logga un warning una volta sola
e delega `getVehicles()` a `MockElerentClient`
(`elerent.api.vehicles-fallback-mock`, default `true`). La mappa mostra quindi una
flotta simulata dentro zone Elerent reali; mettendo il flag a `false` non si
mostra alcun mezzo.

### 1.2 Cosa arriva per Cassino

`/get-zones` risponde con un **array JSON nudo** contenente tutte le zone di tutte
le città Elerent (~670, 660 KB). Il client tiene quelle entro `radius-km` da
`centre-lat/centre-lon` — 29 per Cassino — così il browser non si ritrova a
disegnare i poligoni di mezza Italia a ogni caricamento:

| Tipo | Numero | Cosa sono |
|---|---|---|
| `PARKING_ZONE` | 17 | 16 stalli da 10–200 m, più un poligono da 2,2 km che copre centro, stazione e ospedale |
| `NO_PARKING_ZONE` | 7 | 100–590 m, tutte in centro |
| `NO_GO_ZONE` | 2 | una da 1,1 km e una da 13,7 km che include il campus di Folcara |
| `SPEED_LIMIT_ZONE` | 3 | "6 km/h", attorno all'isola pedonale |

Ogni zona porta anche `zone_vehicle_types`, i tipi di veicolo a cui si applica
(`BIKE`, `E_BIKE`, `SCOOTER`…). Arriva al browser come `vehicle_types`, compare
nel popup della zona e filtra il controllo sul drop-off: una zona di divieto
valida solo per i monopattini non sposta più la fine di una corsa in bici. A
Cassino 26 zone valgono per bici e monopattini indifferentemente, e le 3 zone
"6 km/h" si applicano solo a `SCOOTER` ed `E_BIKE`.

Due dettagli del payload su cui il codice precedente si sarebbe rotto:
`zone_area` è un **poligono GeoJSON**, quindi le coordinate sono
`[longitudine, latitudine]` annidate di un livello per anello — ordine e forma
opposti alle coppie `[lat, lon]` che `GeoUtils` e Leaflet si aspettano — e
`zone_color` è esadecimale nudo (`21C378`), senza `#`. Le zone circolari
(`park_place_zone`, nessuna a Cassino) hanno `zone_area` null e portano invece
`zone_point` + `zone_radius`.

### 1.3 Sosta obbligata, e cosa comporta per un itinerario

Elerent ha confermato la regola che la geometria suggeriva: **la corsa può
terminare solo dentro una parking zone**. `BikeSharingService` tratta quindi
`PARKING_ZONE`, `PAID_PARKING_ZONE` e `PARK_PLACE_ZONE` come area operativa — la
piattaforma non ha un tipo "area di servizio" a sé — e `NO_PARKING_ZONE` /
`NO_GO_ZONE` come vietate. Il test sulle zone vietate viene prima, così una
`NO_PARKING_ZONE` non può combaciare sulla sottostringa "PARK" ed essere scambiata
per un posto dove parcheggiare.

Accendere la regola ha fatto emergere un buco nella ricerca del drop-off. La
`no_go_zone` 4411 è il poligono "tutto ciò che sta fuori dal servizio": copre il
**73% dell'area entro 2 km dal centro**, con un buco sulla città. Uscire appena
dal suo bordo, che è ciò che il codice faceva per una zona vietata, porta sul
confine dell'area di servizio: legale da attraversare, ma non un posto dove si
possa lasciare il mezzo. I due vincoli vanno risolti insieme, quindi
`findLegalDropOff()` cerca ora il punto più vicino che sia dentro una parking zone
**e** fuori da ogni zona vietata a quel mezzo, verificando ogni candidato con
`checkDestinationZones()` prima di accettarlo. Ogni zona offre due candidati —
appena dentro il bordo più vicino, e il suo centro — perché gli stalli sono larghi
10–200 m e un punto sul bordo può essere inutilizzabile mentre il centro va bene.

Misurato su 1249 destinazioni distribuite entro 2 km dal centro, ogni drop-off
prodotto è ora legale (prima erano 971 su 1132 non validi). Quanto spesso il
viaggiatore vede l'avviso dipende da dove sta andando:

| Distanza dal centro | Destinazioni dove la corsa può terminare così com'è |
|---|---|
| entro 500 m | 71% |
| entro 1 km | 38% |
| entro 2 km | 10% |

Nel centro quindi la maggior parte delle corse non cambia, mentre una destinazione
periferica termina in uno stallo con l'ultimo tratto a piedi (in media 540 m
sull'intero disco di 2 km). Il caso più vistoso è il campus di Folcara: cade
dentro la no-go zone 4411, quindi una corsa in bici finisce a ~440 m.

### Endpoint volutamente non usati

`start-ride`, `end-ride`, `pause`, `send-vehicle-commands`, `purchase`: sono
operazioni di scrittura, richiedono un token utente e appartengono a una fase
futura (handoff con deep-link verso l'app Elerent — §4.3).

---

## 2. Flusso dati Elerent → OMNIMOVE

```mermaid
sequenceDiagram
    participant B as Browser (traveller)
    participant C as JourneyController
    participant S as BikeSharingService<br/>(cache in-memory)
    participant K as BikeSharingClient<br/>(RideAtom o Mock)
    participant E as Elerent / RideAtom API

    Note over B: caricamento pagina, poi ogni 60 s
    B->>C: GET /api/v1/journeys/bikes (JWT)
    C->>S: getAvailableBikes()
    alt cache valida (< 60 s)
        S-->>C: lista dalla cache (nessuna chiamata esterna)
    else cache scaduta
        S->>K: getVehicles(41.4901, 13.8303, 5 km)
        K->>E: POST /get-vehicles (App-Public-Key)
        E-->>K: vehicles JSON
        K-->>S: List<BikeVehicleDTO>  (vuota se errore)
        S-->>C: lista aggiornata
    end
    C-->>B: JSON → marker 🚲/🛴 sulla mappa Leaflet
```

Componenti (tutti in `omnimove-backend`):

| Componente | File | Ruolo |
|---|---|---|
| Client (interfaccia) | `client/BikeSharingClient.java` | Contratto read-only: `getVehicles()`, `getZones()` |
| Client reale | `client/RideAtomClient.java` | Chiama RideAtom; parsing tollerante, GeoJSON → `[lat, lon]`, zone filtrate sull'area di servizio; su errore → lista vuota |
| Client mock | `client/MockElerentClient.java` | Flotta simulata deterministica (seed fisso) su punti reali di Cassino |
| Service | `service/BikeSharingService.java` | Cache TTL: 60 s mezzi, 10 min zone |
| REST | `controller/JourneyController.java` | `GET /api/v1/journeys/bikes`, `GET /api/v1/journeys/bikes/zones` |
| DTO | `dto/BikeVehicleDTO.java`, `dto/BikeZoneDTO.java` | Formato snake_case verso il browser; le zone portano i tipi di veicolo a cui si applicano |
| Frontend | `static/omnimove-traveller.js` | Marker, zone, polling 60 s, filtro con i chip modalità |

Gli endpoint sono protetti come tutti i `/api/v1/journeys/**`: serve un utente
autenticato (TRAVELLER o ADMIN, JWT nel header `Authorization`).

---

## 3. Approccio: quando partono le richieste? I dati vengono conservati?

### Quando OMNIMOVE contatta il server Elerent (anche simulato)

Il browser **non parla mai direttamente con Elerent**: chiama solo il backend
OMNIMOVE. Le chiamate verso Elerent partono esclusivamente dal
`BikeSharingService`, ed è lui a decidere *quando*:

1. Il frontend interroga `GET /journeys/bikes` al caricamento della pagina e poi
   **ogni 60 secondi** (polling), più un refresh delle zone una sola volta.
2. Il service risponde **dalla cache** se il dato ha meno di 60 secondi
   (10 minuti per le zone, che cambiano raramente).
3. Solo a cache scaduta parte **una** chiamata `POST /get-vehicles` verso Elerent.

Conseguenza: **al massimo ~1 richiesta al minuto verso Elerent, indipendentemente
dal numero di utenti connessi**. Cento browser che fanno polling generano sempre
la stessa singola chiamata upstream. Questo protegge l'API del provider (ed
eventuali rate limit) e rende il costo dell'integrazione costante.

Con il mock attivo il "server Elerent" è una classe locale: il flusso e i tempi
sono identici, semplicemente `getVehicles()` restituisce la flotta simulata senza
uscire in rete. Per questo la demo si comporta esattamente come farà la versione
reale.

### Persistenza: i dati vengono conservati localmente?

**No.** I dati Elerent sono volutamente **effimeri**:

- vivono solo nella **cache in-memory** del processo (`BikeSharingService`:
  due campi + timestamp, stesso pattern del `WeatherService`);
- **non** vengono scritti in PostgreSQL, Redis né InfluxDB;
- a ogni riavvio del backend la cache riparte vuota e viene ripopolata alla
  prima richiesta;
- in caso di errore (chiave mancante, API irraggiungibile, timeout) il client
  logga un warning e restituisce lista vuota: la mappa resta funzionante, il
  layer bici semplicemente scompare. Nessuna eccezione risale mai al browser.

La scelta è deliberata: la posizione di una bici libera è un dato "usa e getta"
che invecchia in secondi; conservarlo non avrebbe valore per il journey planner e
creerebbe solo dati stantii. Se in futuro servissero analisi storiche
(es. disponibilità media per zona), il punto giusto dove aggiungere la scrittura è
`BikeSharingService`, verso InfluxDB, senza toccare client né controller.

---

## 4. Evoluzioni previste (fuori dallo scope attuale)

### 4.1 GBFS come sorgente dati alternativa (o aggiuntiva)

**Che cos'è.** GBFS (*General Bikeshare Feed Specification*) è lo standard
aperto per la pubblicazione dei dati di disponibilità dei servizi di mobilità
condivisa. È un insieme di semplici file JSON serviti via HTTP — quelli
rilevanti per noi sono `free_bike_status.json` (posizione, batteria e
disponibilità di ogni mezzo free-floating), `station_information.json` /
`station_status.json` (stalli, se presenti) e `geofencing_zones.json` (zone
operative e di divieto in formato GeoJSON). I feed sono pubblici per
definizione: **nessuna API key, nessuna autenticazione**, e la specifica dichiara
perfino la frequenza di polling tramite il campo `ttl`.

**Perché ci interessa.** Molte installazioni ATOM Mobility espongono un feed
GBFS accanto all'API proprietaria RideAtom, e il GBFS porta le posizioni dei
mezzi senza alcuna credenziale — esattamente il dato che `/get-vehicles` ci
nega (§1.1).

**Dove sarebbe.** ATOM pubblica il feed di ogni operatore sul sottodominio
dell'operatore, nella forma
`https://<operatore>.rideatom.com/gbfs/<system-id>/v3.0/gbfs` (lo schema usato da
Yoio, Yaldi, 3electra e altri registrati nel `systems.csv` di MobilityData).
`elerent.rideatom.com` esiste e risponde su `/gbfs`, ma con `Incorrect data.` per
ogni system id da 1 a 1000, ed Elerent non è nel catalogo MobilityData — quindi o
il feed non è abilitato per questo account, o è pubblicato sotto un id che non
possiamo indovinare. **Vale una mail a Elerent o ad ATOM**: risolverebbe insieme
il problema del token mancante e quello della chiave API.

**Come implementarlo.** È lo scenario per cui il design attuale è stato pensato:

1. aggiungere `client/GbfsClient.java` che implementa `BikeSharingClient` — due
   chiamate GET (`free_bike_status.json`, `geofencing_zones.json`), mappate sui
   DTO esistenti `BikeVehicleDTO` / `BikeZoneDTO`;
2. introdurre una proprietà `elerent.api.provider` (`mock` | `rideatom` | `gbfs`)
   e usarla nelle annotazioni `@ConditionalOnProperty` per scegliere
   l'implementazione (oggi lo switch è il booleano `elerent.api.mock`; un enum a
   tre valori è la generalizzazione naturale);
3. non cambia nient'altro: `BikeSharingService` (con la sua cache),
   `JourneyController`, i DTO e tutto il frontend sono agnostici rispetto al
   provider.

La roadmap (`email_team_roadmap.md`) rimanda già a un esempio GBFS funzionante
basato su Dott Roma, utilizzabile sia come feed di riferimento durante lo
sviluppo sia come test di compatibilità del client.

### 4.2 Disponibilità reale nel journey planning — ✅ implementato

`planBike()` e `planScooter()` sono ora ancorati alla flotta reale (o mock)
tramite l'helper condiviso `planSharedVehicle()` in `JourneyPlannerService`:

- **Tratta verso il mezzo più vicino**: `BikeSharingService.findNearest()`
  sceglie il mezzo disponibile più vicino del tipo giusto; una tratta WALK
  (origine → mezzo, con coordinate per la mappa) viene anteposta alla tratta in
  bici, così la durata totale include la camminata e il confronto con BUS/WALK
  è onesto. Se il mezzo è a meno di 40 m la tratta WALK viene omessa.
- **Disponibilità onesta**: se nessun mezzo è entro la distanza pedonale
  massima, l'opzione sparisce e un avviso spiega il perché. La distanza massima
  è una **preferenza utente** (`max_bike_walk_metres`, default **500 m**,
  migration V16) modificabile nel pannello Preferenze (250 m–1 km). Con
  *preferisci bici al bus* attivo, il fallback già esistente di `plan()`
  ricalcola automaticamente il bus.
- **La batteria informa, non filtra mai**: un mezzo con poca batteria viene
  comunque proposto, con le tacchette renderizzate dal frontend — verde 3
  tacchette ≥ 60 % (carica), gialla 2 tacchette 25–59 % (critica), rossa 1
  tacchetta 10–24 % / 0 tacchette < 10 % (scarica). Lo stesso badge compare nei
  popup della mappa e nella timeline del viaggio (`bike_battery_pct`
  sull'opzione).
- **Controllo zone sulla destinazione**: `checkDestinationZones()` (ray-casting
  point-in-polygon più zone circolari, `util/GeoUtils`) imposta `bike_warning`
  sull'opzione quando la destinazione è fuori dalla zona operativa o dentro una
  zona di divieto di sosta; la card lo mostra come badge di avviso.

Tutto questo consuma gli stessi dati in cache già usati dalla mappa, quindi
**non aggiunge alcuna chiamata** verso Elerent. L'opzione trasporta anche
`bike_id`, `bike_plate` e `bike_walk_metres`, mostrati nel summary della card e
nella timeline.

### 4.3 Sblocco e pagamenti: solo handoff con deep-link

**Il confine.** Tutto ciò che è descritto in questo documento è volutamente in
sola lettura. *Noleggiare* davvero un mezzo (`start-ride`, `end-ride`, `pause`,
`purchase`…) è un problema di classe diversa: quegli endpoint agiscono per conto
di uno specifico utente, richiedono un token `Authorization` personale emesso
dal sistema account di Elerent/ATOM e muovono denaro reale con effetti nel mondo
fisico (una bici si sblocca davvero).

**Livello 1 — handoff con deep-link (il passo previsto).** Quando il traveller
tocca una bici, OMNIMOVE apre l'app Elerent (o la sua pagina store / fallback
web) tramite deep link, idealmente preselezionando il mezzo scelto. Identità,
pagamento, sblocco e responsabilità restano tutti in capo a Elerent, dove già
funzionano. È il livello 1 della roadmap pagamenti (`payments_integration_*.md`,
citata dalla roadmap di team) e costa pochissimo: uno schema URL nel popup,
nessun lavoro backend.

**Perché OMNIMOVE non deve mai sbloccare con la sola public key.** La
`App-Public-Key` identifica l'*applicazione*, non un *utente*. Provare a pilotare
le corse attraverso di essa (es. con la variante `user_id` + secret key
dell'API) significherebbe far custodire a OMNIMOVE le credenziali segrete di
Elerent e farlo agire da intermediario di pagamento: si accollerebbe obblighi
PCI e di responsabilità, l'assistenza clienti per le corse bloccate e i flussi
di rimborso — nulla di tutto ciò appartiene a un journey planner. Un'integrazione
più profonda (sblocco in-app, livello 2+) va costruita esclusivamente su un
flusso per-utente in stile OAuth concordato con Elerent, in cui il cliente si
autentica presso Elerent e OMNIMOVE non tocca mai le sue credenziali né i suoi
strumenti di pagamento.
