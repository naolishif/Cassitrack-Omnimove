# Valutazione d'impatto sulla protezione dei dati (DPIA)
## OMNIMOVE / CassiTrack — piattaforma di mobilità urbana e riuso dei dati a fini di ricerca

**Titolare del trattamento:** Università degli Studi di Cassino e del Lazio Meridionale
**Redatta ai sensi dell'art. 35 del Regolamento (UE) 2016/679**
**Versione:** 0.1 — BOZZA TECNICA
**Data:** 28 agosto 2026

---

> ## ⚠️ STATO DEL DOCUMENTO — LEGGERE PRIMA DI USARLO
>
> Questa è una **bozza tecnica** redatta dal gruppo di sviluppo. Descrive il
> sistema come è effettivamente implementato e propone le misure di mitigazione.
>
> **Non è una DPIA valida finché non è stata:**
>
> 1. completata nei campi contrassegnati con `«DA COMPLETARE»`;
> 2. **verificata dal Responsabile della Protezione dei Dati** dell'Ateneo — il
>    parere dell'RPD è obbligatorio (art. 35, par. 2) e va **verbalizzato**, anche
>    se negativo o parziale;
> 3. approvata dal titolare, che è l'Ateneo e non il gruppo di progetto.
>
> Alcune valutazioni qui contenute (in particolare §5.4 e §5.5) richiedono
> competenze giuridiche e di relazioni sindacali che esulano dal gruppo di
> sviluppo. Sono segnalate come tali e **vanno riviste da chi ha titolo**.

---

## 1. Perché questa DPIA è obbligatoria

L'art. 35, par. 1, impone la valutazione d'impatto quando un trattamento può
presentare un rischio elevato per i diritti e le libertà delle persone fisiche.
Nel caso in esame ricorrono più criteri fra quelli individuati dalle *Linee guida
WP248 rev.01* e dall'elenco del Garante (Provv. n. 467 dell'11 ottobre 2018):

| Criterio | Ricorre | Perché |
|---|---|---|
| Valutazione o punteggio, inclusa la profilazione | Sì | Suggerimenti personalizzati basati sullo storico viaggi e sul questionario di profilazione |
| Monitoraggio sistematico | Sì | Registrazione continuativa degli spostamenti pianificati |
| Dati trattati su larga scala | Sì (potenziale) | Bacino di utenza cittadino e universitario |
| Dati relativi a soggetti vulnerabili | Sì | Studenti: squilibrio di potere rispetto all'Ateneo |
| Uso innovativo di tecnologie | Sì | Rilevazione BLE a bordo, assistente basato su LLM |
| Trattamento che impedisce l'esercizio di un diritto o l'uso di un servizio | Parziale | L'accesso al servizio richiede la registrazione |
| Interconnessione di insiemi di dati | Sì (in progetto) | Riuso a fini di ricerca, potenziale linkage con altre fonti |

Ricorrendo **più di due criteri**, la DPIA è dovuta. L'estensione della
conservazione a fini di ricerca la rende necessaria anche in assenza degli altri
elementi.

---

## 2. Descrizione sistematica dei trattamenti (art. 35, par. 7, lett. a)

### 2.1 Contesto e finalità

OMNIMOVE è l'applicazione rivolta ai cittadini: pianificazione di percorsi
multimodali, orari, fermate, preferiti, assistente virtuale. CassiTrack è il
sistema di back-office del gestore del trasporto: anagrafiche di linee, corse e
mezzi, e posizione in tempo reale dei veicoli.

Il progetto prevede l'estensione a **piattaforma di ricerca**: riuso dei dati di
mobilità per studi scientifici dell'Ateneo, con conservazione prolungata.

### 2.2 Categorie di interessati

| Categoria | Note |
|---|---|
| Utenti registrati di OMNIMOVE | Prevalentemente studenti e personale dell'Ateneo, oltre a cittadini |
| Personale amministrativo | Utenti con ruolo `ADMIN` |
| Autisti | Ruolo `DRIVER` presente in CassiTrack — **v. §5.5** |
| **Terzi non utenti** | Persone i cui dispositivi Bluetooth vengono rilevati a bordo — **v. §5.4** |

L'ultima categoria è la più critica: sono persone che **non hanno alcun rapporto
con il servizio, non sono informate e non possono esercitare alcun diritto**.

### 2.3 Categorie di dati

**OMNIMOVE**

| Dato | Tabella | Natura |
|---|---|---|
| Nome, cognome, e-mail, password (bcrypt), stato verifica | `users` | Identificativo |
| Preferenze di viaggio e notifica | `user_preferences` | Comune |
| Fermate e tratte preferite | `favorite_stops`, `favorite_routes` | **Rivelatorio di abitudini** |
| Storico viaggi: origine, destinazione, modalità, distanza, costo, CO₂, indice, data/ora | `journey_log` | **Dato di localizzazione** |
| Eventi di sicurezza, indirizzo IP | `security_audit_events` | Comune, con IP |
| Registro consensi, IP, user agent | `user_consents` | Comune |
| Testo delle richieste all'assistente | non persistito localmente | **Contenuto libero** |

**CassiTrack**

| Dato | Tabella | Natura |
|---|---|---|
| Posizione, velocità, direzione dei mezzi | `vehicle_positions` | Non personale *di per sé* |
| `ble_device_count` — conteggio dispositivi Bluetooth | `vehicle_positions` | **V. §5.4** |
| Utenti di back-office, inclusi ruoli `DRIVER` | `users` | Identificativo |

### 2.4 Criticità strutturale rilevata nel codice

`journey_log.origin_name` e `dest_name` sono **campi di testo libero** popolati
con quanto l'utente digita nella ricerca. Non sono identificativi di fermata né
coordinate.

Questo ha due conseguenze rilevanti:

1. **Il campo può contenere un indirizzo di residenza** digitato per esteso. È un
   dato più identificativo di una fermata, ed è archiviato in chiaro.
2. **Rende impossibile la generalizzazione spaziale** necessaria per
   l'anonimizzazione: non si può ricondurre a una zona un testo arbitrario.

Misura conseguente: `V23` aggiunge le coordinate a `journey_log`, e la pipeline
di ricerca **esclude in modo fail-closed** ogni riga non riconducibile a una zona.
I nomi in chiaro **non vengono mai copiati** nel livello di ricerca.

### 2.5 Destinatari e trasferimenti

| Destinatario | Ruolo | Dati | Paese |
|---|---|---|---|
| Google Ireland / LLC | Responsabile | Coordinate origine/destinazione (chiamata server-to-server: l'IP dell'utente non è esposto) | IE / US |
| Anthropic PBC | Responsabile | Testo delle richieste all'assistente | US |
| OpenWeather Ltd. | Responsabile | Coordinate approssimative | UK |
| OpenStreetMap Foundation | Autonomo | **IP dell'utente** (chiamata dal browser) | NL/UK |
| Fornitore posta elettronica | Responsabile | Indirizzo e-mail | `«DA COMPLETARE»` |

Trasferimenti extra-UE fondati sulla decisione di adeguatezza EU-U.S. Data
Privacy Framework del 10 luglio 2023 e/o su Clausole Contrattuali Standard.
**`«DA COMPLETARE»`: verificare l'esistenza e l'archiviazione dei DPA firmati con
ciascun fornitore.** Un fornitore senza accordo ex art. 28 non può essere usato.

### 2.6 Misure già implementate

Rilevate nel codice al momento della redazione:

- password con bcrypt; JWT in cookie `HttpOnly`, `SameSite=Strict`;
- verifica dell'indirizzo e-mail, blocco dopo 5 tentativi falliti, rate limiting;
- log di sicurezza con mascheramento di e-mail e IP nei file di log;
- CSP restrittiva, HSTS, `frame-ancestors 'none'`;
- font e librerie **self-hosted**: nessuna comunicazione dell'IP a CDN terzi;
- registro dei consensi append-only con versione della policy;
- export dei dati dell'interessato (artt. 15 e 20);
- la console amministrativa espone in elenco **solo** id, nome, e-mail, ruolo.

---

## 3. Necessità e proporzionalità (art. 35, par. 7, lett. b)

### 3.1 Base giuridica per finalità

| Finalità | Base giuridica | Note |
|---|---|---|
| Gestione account e autenticazione | Art. 6.1.b — contratto | Conferimento necessario |
| Pianificazione percorsi, preferiti | Art. 6.1.b — contratto | |
| Sicurezza, prevenzione abusi | Art. 6.1.f — legittimo interesse | Bilanciamento da verbalizzare |
| Statistiche aggregate di servizio | Art. 6.1.f — legittimo interesse | Solo su dati aggregati |
| Suggerimenti personalizzati (profilazione) | **Art. 6.1.a — consenso** | Facoltativo, revocabile, servizio invariato se negato |
| **Riuso a fini di ricerca scientifica** | **Art. 6.1.e — interesse pubblico** | V. §3.2 |
| Prova dei consensi | Art. 7, par. 1 | |

### 3.2 La ricerca non si fonda sul consenso: motivazione

Scelta deliberata, da sottoporre all'RPD.

Il consenso **non** è la base giuridica appropriata:

- **è revocabile** (art. 7, par. 3): la revoca a distanza di anni comprometterebbe
  serie storiche e riproducibilità di risultati già pubblicati;
- **difficilmente è libero**: fra Ateneo e propri studenti sussiste lo squilibrio
  di potere di cui al considerando 43 e alle *Linee guida EDPB 05/2020*;
- **produce bias di selezione**: un campione autoselezionato compromette la
  validità scientifica dello studio.

Si applica invece il quadro previsto dal Regolamento per la ricerca:

- **art. 5.1.b** — l'ulteriore trattamento a fini di ricerca scientifica non è
  considerato incompatibile con le finalità originarie;
- **art. 5.1.e** — conservazione per periodi più lunghi ammessa se il trattamento
  è effettuato unicamente a fini di ricerca, fatte salve le garanzie dell'art. 89;
- **art. 89, par. 1** — minimizzazione e pseudonimizzazione come garanzie;
- **art. 110-bis** del D.lgs. 196/2003 e **Regole deontologiche per trattamenti a
  fini statistici o di ricerca scientifica** (Provv. Garante n. 515 del 19
  dicembre 2018, Allegato A.4 al Codice) — **vincolanti, da applicare**;
- la ricerca è compito istituzionale dell'Ateneo (L. 240/2010), il che sostanzia
  l'interesse pubblico ex art. 6.1.e.

**Conseguenze operative, non facoltative:**

- informativa specifica sul riuso a fini di ricerca (art. 13);
- **diritto di opposizione** ex art. 21, par. 6: opt-out, non opt-in, esercitabile
  in autonomia dal profilo e con effetto anche retroattivo sui dati già promossi;
- il **consenso esplicito resta necessario** per gli studi in cui il partecipante
  è identificabile: questionari, interviste, linkage con carriera universitaria.

> `«DA COMPLETARE»` — Il parere dell'RPD su questa impostazione è il presupposto
> di tutta la §5.7. Se l'RPD ritenesse necessario il consenso, l'architettura a
> tre livelli va ridisegnata.

### 3.3 Minimizzazione applicata

- il livello di ricerca **non** riceve nome, cognome, e-mail, IP, user agent;
- il livello di ricerca **non** riceve i testi liberi di origine e destinazione,
  ma solo l'identificativo di zona;
- `cost_euros` è escluso perché derivabile da modalità e distanza: dato ridondante;
- l'orario è ridotto a fascia oraria; la data esatta è conservata solo al livello
  pseudonimo, dove serve per l'analisi longitudinale;
- il livello aggregato non contiene alcun identificativo di soggetto.

---

## 4. Metodo di valutazione del rischio

Rischio = **Probabilità × Gravità**, entrambe su scala 1–4 (1 trascurabile,
4 massima). La gravità è valutata **dal punto di vista dell'interessato**, non
dell'Ateneo: il danno rilevante è quello alla persona.

| Punteggio | Livello | Conseguenza |
|---|---|---|
| 1–3 | Basso | Accettabile |
| 4–7 | Medio | Mitigazione pianificata |
| 8–11 | Alto | Mitigazione obbligatoria prima del rilascio |
| 12–16 | Molto alto | Trattamento sospeso; valutare consultazione preventiva (art. 36) |

---

## 5. Registro dei rischi e misure (art. 35, par. 7, lett. c e d)

### 5.1 R1 — Re-identificazione a partire dai dati di mobilità

**Scenario.** Un ricercatore, o chi acceda ai dati, ricollega una serie di viaggi
a una persona. Le coppie origine-destinazione ricorrenti nella fascia mattutina
dei giorni feriali coincidono con residenza e sede di studio o lavoro.

**Perché è grave.** De Montjoye et al., *Unique in the Crowd* (Scientific Reports,
2013): quattro punti spazio-temporali identificano univocamente il 95% degli
individui. **Rimuovere `user_id` produce dati pseudonimi, non anonimi**, che
restano soggetti al Regolamento.

Il criterio di riferimento è il **WP29, Opinione 05/2014**: il dato è anonimo solo
se non consente *singling out*, *linkability* e *inference*. Sui microdati di
mobilità grezzi nessuno dei tre test è superato.

| Prob. | Grav. | Rischio |
|---|---|---|
| 3 | 4 | **12 — Molto alto** (senza misure) |

**Misure.**
- architettura a tre livelli (§6): l'unico livello a conservazione illimitata è
  quello **aggregato**;
- generalizzazione spaziale in zone, mai fermata o testo libero;
- generalizzazione temporale in fascia oraria e tipo di giorno;
- **soppressione delle celle piccole**: pubblicazione solo per combinazioni con
  `n_viaggi ≥ k` **e** `soggetti_distinti ≥ k`, con `k ≥ 5` (raccomandato 10);
- pseudonimo HMAC-SHA256 con **salt custodito fuori dal database**;
- **divieto assoluto di diffusione di microdati**, anche pseudonimizzati, anche
  come materiale supplementare di pubblicazioni.

**Rischio residuo:** 4 — Medio.

### 5.2 R2 — Attacco differenziale sui rilasci aggregati

**Scenario.** Rilasci aggregati successivi calcolati su un dataset in evoluzione
permettono, per differenza fra due release, di isolare i viaggi di un singolo.

| Prob. | Grav. | Rischio |
|---|---|---|
| 2 | 4 | **8 — Alto** |

**Misure.** Le aggregazioni sono materializzate in **release congelate e
versionate** (`research.release`), non ricalcolate su richiesta; nessun accesso a
query libere sul livello aggregato; la soglia `k` è registrata per ogni release.

**Rischio residuo:** 3 — Basso.

### 5.3 R3 — Indirizzi di residenza in chiaro nello storico

**Scenario.** `journey_log.origin_name` contiene il testo digitato dall'utente,
potenzialmente l'indirizzo di casa, conservato in chiaro e visibile a chi abbia
accesso al database operativo.

| Prob. | Grav. | Rischio |
|---|---|---|
| 3 | 3 | **9 — Alto** |

**Misure.** Purge del livello operativo a 12 mesi; esclusione totale dei campi
liberi dal livello di ricerca; accesso al database operativo limitato e
tracciato. **Da valutare**: sostituire in scrittura il testo libero con il
riferimento a fermata o coordinate arrotondate.

**Rischio residuo:** 4 — Medio.

### 5.4 R4 — Rilevazione Bluetooth di persone non utenti

**Scenario.** `vehicle_positions.ble_device_count` conta i dispositivi Bluetooth a
bordo come stima dei passeggeri. Il conteggio è aggregato, ma **la rilevazione a
monte opera su indirizzi MAC, che sono dati personali**. Gli interessati sono
persone che non usano il servizio, non sono informate e non possono opporsi.

> `«DA COMPLETARE — VERIFICA TECNICA PRIORITARIA»`
> Il codice applicativo riceve solo un intero. **Va accertato che cosa fa
> l'unità di bordo**: se conserva i MAC, se li sottopone ad hash, con quale sale,
> per quanto tempo, e se il dato esce dal dispositivo. Finché la risposta non è
> documentata, il rischio non è valutabile e va assunto come elevato.

| Prob. | Grav. | Rischio |
|---|---|---|
| 3 | 4 | **12 — Molto alto** (in assenza di verifica) |

**Misure attese.** Nessuna conservazione dei MAC; conteggio calcolato in memoria
sul dispositivo con hash a sale rotante e finestra breve; informativa a bordo;
inclusione nel registro dei trattamenti. Se l'unità di bordo conserva i MAC, la
funzione va **disattivata** fino all'adeguamento.

**Rischio residuo:** non determinabile prima della verifica.

### 5.5 R5 — Controllo a distanza dell'attività degli autisti

**Scenario.** CassiTrack prevede il ruolo `DRIVER`. Allo stato **non risulta un
collegamento fra utente-autista e `vehicle_positions`**, quindi il rischio non è
attuale. Nel momento in cui un autista venisse associato a un mezzo o a una
corsa, posizione, velocità e orari diventerebbero strumento di controllo a
distanza dell'attività lavorativa.

**Quadro normativo.** **Art. 4 della L. 300/1970 (Statuto dei Lavoratori)**: gli
strumenti da cui derivi la possibilità di controllo a distanza possono essere
impiegati solo previo **accordo sindacale** o **autorizzazione dell'Ispettorato
Nazionale del Lavoro**, e comunque previa informativa specifica ai lavoratori. Si
aggiunge il *Provvedimento del Garante sui metadati e sugli strumenti di lavoro*.

> **Non è una formalità sanabile a posteriori.** L'art. 4 è assistito da sanzione
> penale e l'autorizzazione deve precedere l'attivazione dello strumento.
> Questa valutazione **esula dalle competenze del gruppo di sviluppo** e va
> rimessa all'Ateneo e al gestore del servizio, che è il datore di lavoro.

| Prob. | Grav. | Rischio |
|---|---|---|
| 2 (oggi) → 4 (se attivato) | 4 | **8 → 16** |

**Misure.** Vincolo di progettazione: **nessuna associazione persistente fra
identità dell'autista e traccia GPS** finché non è acquisito il titolo ex art. 4.
Da recepire come requisito, non come raccomandazione.

**Rischio residuo:** 4 se il vincolo è rispettato.

### 5.6 R6 — Accesso eccessivo dell'amministratore ai dati di viaggio

**Scenario.** Un amministratore consulta abitudini di spostamento di singoli
utenti senza necessità: ne deriva la conoscenza di residenza, orari e luoghi
frequentati.

| Prob. | Grav. | Rischio |
|---|---|---|
| 3 | 3 | **9 — Alto** |

**Misure.** L'elenco utenti espone solo dati di account; lo storico individuale è
accessibile solo dal dettaglio della singola persona; **ogni accesso è
registrato** in `security_audit_events`; le statistiche di servizio sono calcolate
su dati aggregati.

> `«DA COMPLETARE»` — La registrazione dell'accesso al dettaglio del singolo
> utente **non è ancora implementata**, perché l'endpoint non esiste sul ramo di
> integrazione. È un requisito vincolante per chi lo realizzerà.

**Rischio residuo:** 4 — Medio.

### 5.7 R7 — Conservazione illimitata in assenza di purge

**Scenario.** L'informativa dichiara la cancellazione dello storico a 12 mesi. La
cancellazione automatica **non è implementata**: i dati si accumulano senza
limite e la dichiarazione resa agli interessati è inesatta.

| Prob. | Grav. | Rischio |
|---|---|---|
| 4 | 3 | **12 — Molto alto** |

**Misure.** `research.purge_operational()` in `V23`. **Da collegare a uno
scheduler: finché non gira, il rischio resta invariato.** In alternativa
immediata, correggere l'informativa.

**Rischio residuo:** 3 — Basso, **solo a job attivo**.

### 5.8 R8 — Violazione dei dati del livello di ricerca

| Prob. | Grav. | Rischio |
|---|---|---|
| 2 | 4 | **8 — Alto** |

**Misure.** Schema separato con ruolo dedicato in sola lettura; salt del
pseudonimo fuori dal database, cosicché l'esfiltrazione del solo schema di
ricerca non consenta la riconduzione alle identità; accessi tracciati; cifratura
dei backup; procedura di notifica ex artt. 33–34 `«DA COMPLETARE»`.

**Rischio residuo:** 4 — Medio.

### 5.9 R9 — Comunicazione di contenuti personali all'assistente LLM

**Scenario.** L'utente digita dati personali propri o altrui nella richiesta
all'assistente; il testo è trasmesso al fornitore extra-UE.

| Prob. | Grav. | Rischio |
|---|---|---|
| 3 | 2 | **6 — Medio** |

**Misure.** Avviso in prossimità del campo di input; nessuna persistenza locale
delle conversazioni; verifica del DPA e dei termini di non addestramento sui dati
`«DA COMPLETARE»`.

**Rischio residuo:** 3 — Basso.

### 5.10 R10 — Profilazione priva di base valida

| Prob. | Grav. | Rischio |
|---|---|---|
| 2 | 3 | **6 — Medio** |

**Misure.** Consenso separato, non preselezionato, registrato con versione della
policy; revoca dal profilo con effetto immediato; il servizio resta pienamente
utilizzabile in caso di rifiuto.

**Rischio residuo:** 2 — Basso.

### 5.11 Quadro di sintesi

| # | Rischio | Iniziale | Residuo |
|---|---|---|---|
| R1 | Re-identificazione da dati di mobilità | 12 | 4 |
| R2 | Attacco differenziale | 8 | 3 |
| R3 | Indirizzi in chiaro | 9 | 4 |
| R4 | Rilevazione BLE di non utenti | 12 | **non determinato** |
| R5 | Controllo a distanza autisti | 8 → 16 | 4 *se vincolo rispettato* |
| R6 | Accesso amministrativo eccessivo | 9 | 4 |
| R7 | Assenza di purge | 12 | 3 *se job attivo* |
| R8 | Violazione dati di ricerca | 8 | 4 |
| R9 | Contenuti verso LLM | 6 | 3 |
| R10 | Profilazione | 6 | 2 |

---

## 6. Architettura a tre livelli

Attuata in `V23__research_tiers.sql`.

| Livello | Contenuto | Accesso | Conservazione |
|---|---|---|---|
| **1 — Operativo** | `journey_log` con `user_id` | Applicazione | **12 mesi** |
| **2 — Ricerca, pseudonimo** | Schema `research`, pseudonimo HMAC, zone, fascia oraria; nessun testo libero | Progetti approvati, sola lettura, accessi tracciati | **Durata del progetto, max 10 anni** |
| **3 — Aggregato, anonimo** | Matrice O/D con soppressione `k` | Aperto, pubblicabile | **Illimitata** |

Il livello 3 è fuori dall'ambito del Regolamento (considerando 26): è lì che vive
la conservazione illimitata richiesta dal progetto. Il livello 2 consente
l'analisi longitudinale ma **resta dato personale**, con durata definita: una
conservazione "indefinita" su dati pseudonimi non è difendibile.

**Opposizione.** Il registro dei consensi accoglie il tipo `RESEARCH_USE`, con
semantica di **registro delle opposizioni**: l'assenza di righe significa
inclusione, poiché la base giuridica è l'interesse pubblico e non il consenso.
Una riga con `granted = false` esclude l'interessato dalle promozioni successive
e ne consente la rimozione dal livello 2, il cui pseudonimo è ricalcolabile.

---

## 7. Consultazione dell'RPD e degli interessati

- **RPD** (art. 35, par. 2): `«DA COMPLETARE — parere obbligatorio, da verbalizzare»`
- **Interessati** (art. 35, par. 9): `«DA COMPLETARE»` — si raccomanda la
  consultazione delle rappresentanze studentesche, trattandosi in prevalenza di
  studenti dell'Ateneo.
- **Rappresentanze sindacali**: necessaria per R5, prima di qualsiasi
  associazione fra autisti e tracce GPS.
- **Comitato etico di Ateneo**: `«DA COMPLETARE»` per la componente di ricerca.

---

## 8. Esito e consultazione preventiva

L'art. 36 impone la consultazione preventiva del Garante se, **dopo** le misure,
permane un rischio elevato.

Sulla base della §5.11, i rischi residui sono riconducibili a livello medio o
basso **a tre condizioni**, tutte oggi non soddisfatte:

1. **R4 chiarito** — verifica documentata del comportamento dell'unità di bordo
   sui MAC address;
2. **R7 chiuso** — job di purge effettivamente in esecuzione;
3. **R5 vincolato** — divieto formalizzato di associare identità dell'autista e
   traccia GPS in assenza di titolo ex art. 4 St. Lav.

**Finché queste tre condizioni non sono soddisfatte, il rilascio in produzione
della componente di ricerca non dovrebbe avere luogo.** Il punto R4, in
particolare, potrebbe da solo richiedere la consultazione preventiva.

`«DA COMPLETARE — determinazione del titolare, sentito l'RPD»`

---

## 9. Riesame

La DPIA è riesaminata almeno **ogni 24 mesi** e comunque in caso di: nuove
finalità di ricerca, linkage con altre fonti, modifica delle categorie di dati,
attivazione dell'associazione autista-veicolo, modifica della soglia `k`,
violazione di dati.

| Versione | Data | Autore | Modifiche |
|---|---|---|---|
| 0.1 | 2026-08-28 | Gruppo di sviluppo | Prima bozza tecnica |
