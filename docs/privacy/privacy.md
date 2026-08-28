# Adeguamento privacy e riuso dei dati a fini di ricerca

**Branch:** `feature/privacy-consent` → `develop`
**Titolare del trattamento:** Università degli Studi di Cassino e del Lazio Meridionale
**Ultimo aggiornamento:** 28 agosto 2026

Nota di lettura per chi rivede: questo documento riassume **cosa cambia e perché**.
L'analisi dei rischi sta in [`DPIA-omnimove-cassitrack.md`](DPIA-omnimove-cassitrack.md);
i testi rivolti agli utenti sono `static/privacy.html` e `static/cookie-policy.html`.

---

## 1. In breve

Sette commit, in due blocchi distinti che possono essere valutati separatamente:

| Blocco | Commit | Cosa fa | Attivo dopo il merge? |
|---|---|---|---|
| **A — Conformità di base** | `83994d1`, `0353f57` | Self-hosting degli asset, informativa, cookie policy, registro consensi, export dati | **Sì, subito** |
| **B — Ricerca** | `1d9871b`, `9027607`, `95946ee`, `1d17cd4`, `035a1cb` | DPIA, schema a tre livelli, pipeline, opposizione | **No**, disattivato per impostazione predefinita |

Il blocco B introduce tabelle e codice ma **non fa nulla** finché
`RESEARCH_ENABLED` resta `false`. È deliberato: non va acceso prima del parere
dell'RPD.

---

## 2. Blocco A — Conformità di base

### 2.1 Il problema che risolve

Le quattro pagine caricavano font da `fonts.googleapis.com` e librerie da
`cdn.jsdelivr.net`. Ogni visita **comunicava l'indirizzo IP dell'utente a terzi
negli Stati Uniti senza base giuridica** — la fattispecie su cui il Garante è
intervenuto in materia di Google Fonts nel 2022.

### 2.2 Cosa è cambiato

**Asset self-hosted.** Font, Leaflet e Chart.js sono serviti dal nostro dominio,
sotto `static/vendor/`. I file di Leaflet e Chart.js sono **byte-identici** a
quelli del CDN: gli hash SHA-256 coincidono con gli attributi `integrity` che
l'HTML già dichiarava. Nessun cambiamento funzionale.

`tools/vendor_fonts.py` rigenera i font (sottoinsiemi `latin` e `latin-ext`).

**La CSP non consente più quei domini.** Non è una svista: impedisce che un tag
verso un CDN venga reintrodotto per errore. Se qualcuno ne aggiunge uno, il
browser lo blocca e il problema si vede subito invece che dopo mesi.

**Banner cookie — perché ha un solo pulsante.** Dopo il self-hosting non resta
nulla che richieda consenso: sessione, lingua e stato del banner sono strumenti
tecnici, esenti ai sensi dell'art. 122. Un finto «Accetta / Rifiuta» chiederebbe
un consenso non dovuto e abituerebbe le persone a cliccare a vuoto.

Il banner conforme completo (pari evidenza dei pulsanti, nulla caricato prima
della scelta, nessuna riproposizione per sei mesi dopo un rifiuto) è comunque
**implementato** dietro la costante `THIRD_PARTY_ASSETS` in
`omnimove-consent.js`: si accende da sé se una terza parte non tecnica viene
reintrodotta.

**Registrazione.** Due caselle separate, nessuna preselezionata: presa visione
dell'informativa (obbligatoria) e profilazione (facoltativa, l'account si crea
comunque). Unirle in una sola casella le invaliderebbe entrambe.

**Registro dei consensi** (`V22`). Tabella in sola aggiunta: la revoca inserisce
`granted = false`, non aggiorna la riga precedente, così la cronologia resta
dimostrabile ai sensi dell'art. 7, par. 1. Registra la versione della policy: se
il testo cambia, i consensi vengono raccolti di nuovo.

**Diritti dell'interessato.** `GET /api/v1/privacy/export` restituisce in JSON
tutto ciò che conserviamo sul chiamante (artt. 15 e 20), raggiungibile dal
profilo. Esclude hash della password e token di reset: sono credenziali, e
restituirle indebolirebbe la sicurezza dell'account. Ogni export è tracciato nel
log di sicurezza.

---

## 3. Blocco B — Ricerca

### 3.1 La scelta di fondo: non è il consenso

Per il riuso a fini di ricerca la base giuridica è l'**art. 6, par. 1, lett. e)
— interesse pubblico**, non il consenso. Le ragioni, per esteso, sono nella DPIA
§ 3.2; in sintesi:

- il consenso è **revocabile**: una revoca dopo anni comprometterebbe serie
  storiche e riproducibilità di risultati già pubblicati;
- **difficilmente sarebbe libero**: fra Ateneo e propri studenti c'è lo
  squilibrio di potere di cui al considerando 43 e alle Linee guida EDPB 05/2020;
- produrrebbe **bias di selezione**, compromettendo la validità scientifica.

Il Regolamento già consente quel che serve: l'art. 5, par. 1, lett. b) rende il
riuso a fini di ricerca non incompatibile con le finalità originarie, e la lett. e)
ammette la conservazione prolungata, fatte salve le garanzie dell'art. 89.

**Conseguenza pratica sul codice, da tenere presente in revisione:** il tipo
`RESEARCH_USE` è un **registro delle opposizioni**, non dei consensi.

> **L'assenza di righe significa «incluso».** Solo una riga con
> `granted = false` esclude l'interessato.

Per questo il relativo interruttore nel profilo è **acceso** in partenza, e per
questo la validità di `RESEARCH_USE` non decade quando cambia la versione della
policy: riscrivere l'informativa non deve reincludere in silenzio chi aveva
chiesto di restare fuori.

### 3.2 Perché tre livelli

Togliere `user_id` **non è anonimizzare**. Le coppie origine-destinazione
ricorrenti nelle mattine feriali *sono* la casa e la sede di studio o lavoro di
una persona; de Montjoye et al. (2013) mostrano che quattro punti
spazio-temporali identificano il 95% degli individui. Il criterio applicato è
quello del WP29, Opinione 05/2014: singling out, linkability, inference.

| Livello | Contenuto | Accesso | Conservazione |
|---|---|---|---|
| **1 — Operativo** | `journey_log` con `user_id` | Applicazione | 12 mesi |
| **2 — Ricerca** | Schema `research`, pseudonimo HMAC, zone, fascia oraria | Progetti approvati, sola lettura, accessi tracciati | Durata del progetto |
| **3 — Aggregato** | Matrice O/D con soppressione delle celle piccole | Aperto, pubblicabile | **Illimitata** |

Solo il livello 3 è anonimo ai sensi del considerando 26: è lì che vive la
conservazione senza limiti. Il livello 2 resta dato personale, con durata
definita.

**Il dettaglio che conta davvero.** La soppressione controlla **sia i viaggi sia
i soggetti distinti**: venti viaggi fatti da una sola persona non sono una folla,
sarebbero quella persona. È la regola che nella pratica separa
un'anonimizzazione reale da una apparente.

### 3.3 Un problema strutturale emerso

`journey_log.origin_name` e `dest_name` sono **testo libero digitato
dall'utente**. Possono contenere un indirizzo di casa, e non sono generalizzabili
in zone.

Rimedio: `V23` aggiunge le coordinate, che il percorso di scrittura ora
registra. La pipeline è **fail-closed** — un viaggio non riconducibile a una zona
viene scartato, mai promosso con una posizione precisa — e **il testo libero non
raggiunge mai il livello di ricerca**.

### 3.4 Opposizione con effetto retroattivo

Spegnere l'interruttore cancella anche le righe **già promosse** al livello 2:
l'art. 21 non è solo per il futuro. Lo pseudonimo è ricalcolabile da `user_id` e
salt, quindi quelle righe si ritrovano. L'interfaccia riporta quanti record sono
stati effettivamente cancellati.

---

## 4. Modifiche che richiedono attenzione in revisione

### 4.1 Incompatibilità: `/api/v1/auth/register`

**L'endpoint ora rifiuta le richieste prive di `privacyNoticeAccepted: true`.**

Il frontend è aggiornato. **Qualsiasi altro chiamante va adeguato** — in
particolare il flusso Google sign-in su `massi_sprint_10`, che crea utenti e deve
raccogliere gli stessi consensi.

### 4.2 Numerazione delle migration

`develop` arriva a `V17`; `massi_sprint_10` occupa `V18`–`V21`. Questo branch usa
**`V22`, `V23`, `V24`** proprio per non collidere al merge.

### 4.3 File toccati anche altrove

`omnimove-login.html`, `omnimove-traveller.js`, `omnimove-i18n.js` e
`application.yml` sono modificati anche su `massi_sprint_10`. Conflitti probabili,
tutti in punti circoscritti.

### 4.4 Console amministrativa

La DPIA § 6 e l'informativa § 6 dichiarano che l'elenco utenti mostra **solo dati
di account**, e che lo storico dei viaggi è visibile solo dal dettaglio della
singola persona con accesso tracciato.

Su `develop` è vero oggi. **Su `massi_sprint_10` non lo è**: la dashboard mostra
ultimo viaggio e preferiti in elenco. O si spostano nel dettaglio con
registrazione dell'accesso, o va riscritto quel paragrafo — ma le due cose devono
coincidere.

---

## 5. Configurazione

Nessuna variabile nuova è obbligatoria: senza toccare nulla, il blocco A è attivo
e il blocco B resta inerte. Elenco completo in `.env.example`.

| Variabile | Predefinito | Note |
|---|---|---|
| `PRIVACY_POLICY_VERSION` | `2026-08-28` | Da allineare a `POLICY_VERSION` in `omnimove-consent.js` e alle date nelle pagine legali |
| `RESEARCH_ENABLED` | `false` | Accende la pipeline. **Non attivare prima del parere dell'RPD** |
| `RESEARCH_PSEUDONYM_SALT` | *(vuoto)* | Obbligatorio, min. 32 caratteri, **solo** se la pipeline è attiva |
| `RESEARCH_OPERATIONAL_RETENTION_DAYS` | `365` | Deve coincidere con quanto dichiara l'informativa § 7 |
| `RESEARCH_TIER2_RETENTION_DAYS` | `3650` | |
| `RESEARCH_K_THRESHOLD` | `10` | Minimo consentito 5 |

> **Il salt non deve mai cambiare** una volta che la pipeline ha girato. Uno
> nuovo produce pseudonimi diversi: la stessa persona verrebbe scissa in due
> soggetti e le opposizioni già espresse non troverebbero più le righe da
> cancellare. Va conservato **fuori dal database che protegge**, altrimenti un
> dump dello schema di ricerca tornerebbe collegabile alle identità.

Con `RESEARCH_ENABLED=true` e salt assente o troppo corto **l'applicazione si
rifiuta di avviarsi**. È voluto: una pipeline silenziosamente inattiva
conserverebbe i viaggi per sempre mentre l'informativa promette 12 mesi.

---

## 6. Verifiche eseguite

Su Postgres 15 e con avvio reale dell'applicazione, non solo compilazione.

**Migration** — `V1`→`V24` applicate in sequenza da Flyway attraverso
l'applicazione stessa.

**Pipeline**

| Caso | Esito atteso | Esito |
|---|---|---|
| Cella con 12 soggetti distinti | pubblicata | ✅ |
| Cella con 20 viaggi ma **1 solo soggetto** | soppressa | ✅ |
| Utente che si oppone | escluso dalla promozione | ✅ |
| Righe senza coordinate | scartate | ✅ |
| Release sovrapposta a una esistente | respinta | ✅ |
| Trimestri adiacenti | accettati | ✅ |
| Purge oltre il watermark di promozione | rifiutata | ✅ |
| `k < 5` | rifiutato | ✅ |
| Opposizione dopo la promozione | righe cancellate dal livello 2 | ✅ |

**Avvio** — parte con pipeline attiva (i tre cron vengono interpretati,
`ddl-auto: validate` passa con le colonne nuove) e con pipeline disattiva
(predefinito); **rifiuta** l'avvio con salt corto.

**Endpoint**

| Caso | Esito |
|---|---|
| Pagine legali e asset senza autenticazione | 200 |
| Registrazione senza spunta privacy | rifiutata |
| Registrazione con spunta | riuscita |
| Banner anonimo che registra la scelta | accettato |
| Export senza autenticazione | 401 |
| Tipo di consenso non previsto | rifiutato |
| Font woff2 | `font/woff2`, magic bytes `wOF2` integri |

**Non verificato:** l'aspetto delle pagine in un browser reale, e l'assenza di
richieste ai CDN nel pannello Network. Vanno guardati a occhio prima del merge.

---

## 7. Cosa resta aperto

**Prima di pubblicare le pagine legali**

1. `privacy.html` contiene **segnaposto evidenziati in giallo**: sede, PEC,
   contatti dell'RPD. Vanno compilati.
2. Il testo va **validato dall'RPD** e il trattamento iscritto nel registro
   ex art. 30.

**Prima di attivare la ricerca**

3. **Parere dell'RPD sull'impianto** dell'art. 6, par. 1, lett. e). Se ritenesse
   necessario il consenso, l'architettura va ridisegnata: è il presupposto di
   tutto il blocco B.
4. **Documentare il firmware dell'ESP32.** La valutazione «rischio basso» sulla
   rilevazione Bluetooth poggia interamente sull'affermazione che i MAC non sono
   conservati; l'art. 5, par. 2, richiede di poterlo *dimostrare*.
5. **Sostituire la zonizzazione a griglia** da 500 m, che è un segnaposto
   funzionante, con sezioni di censimento ISTAT o quartieri reali. Il resto della
   pipeline è indifferente a come sono definite le zone.
6. **Comitato etico di Ateneo** per la componente di ricerca.

**Indipendente dal merge**

7. **Attivare la pipeline** è l'unico punto in cui il sistema **non fa ciò che
   dichiara agli utenti**: finché resta spenta, l'informativa promette 12 mesi e i
   viaggi restano per sempre. Fra tutte le voci aperte, è quella da chiudere per
   prima.
8. **Art. 4 dello Statuto dei Lavoratori.** Il sistema non traccia l'identità
   degli autisti, quindi il rischio non è attuale. Ma la norma guarda alla
   *possibilità* di controllo: se il gestore dispone dei turni di servizio, tracce
   GPS più turni ricostruiscono comunque l'attività del singolo. Probabilmente già
   coperto da un accordo di flotta esistente — va verificato, e compete al datore
   di lavoro.

---

## 8. Nota operativa

Dopo aver cambiato branch, **eseguire `./mvnw clean` prima di avviare**. In
`target/classes/db/migration` restano le migration del branch precedente e Flyway
si ferma con:

```
Found more than one migration with version 16
```

Il messaggio non lascia intuire che la causa è un artefatto di build vecchio. Con
più branch che rinumerano le migration, capiterà a chiunque.
