# Piano — Portare il client OMNIMOVE su app mobile con Capacitor

> **Obiettivo.** Trasformare il **flusso traveller** del client web OMNIMOVE
> (HTML/CSS/JS "vanilla" + mappa Leaflet) in un'app nativa **Android/iOS** usando
> **Capacitor**, **senza riscrivere la UI** e mantenendo lo stack attuale.
>
> Questo documento è pensato per essere **didattico**: prima spiega *perché* servono
> certe modifiche, poi *cosa* fare passo per passo. Se è la prima volta che affronti il
> tema "web app → app nativa", leggi con attenzione la sezione **1 (Concetti chiave)**:
> senza quei concetti gli step operativi sembrano magia.

---

## 0. Stato attuale del codice (da dove partiamo)

Nel client (`omnimove-backend/src/main/resources/static/`) oggi abbiamo:

- **Base delle API relativa** — presuppone che pagina e API stiano sullo **stesso server**:
  - `omnimove-traveller.js:26` → `const API_BASE = '/omnimove/api/v1';`
  - più qualche URL scritta a mano (`/omnimove/api/v1/auth/logout`, `/auth/account`).
- **Autenticazione tramite cookie `httpOnly` `SameSite`**: dopo il login il JWT sta in un
  cookie che il browser invia **da solo**, ma che **JavaScript non può leggere** (scelta di
  sicurezza contro l'XSS). Esiste del codice `Authorization: Bearer` ma è **morto**
  (`omnimove-traveller.js:225` legge un token da `localStorage` che nessuno scrive più).
- **Librerie caricate da CDN**: Leaflet 1.9.4 (`cdn.jsdelivr.net`) e i font Google.
- **Sito multi-pagina**: `omnimove-login.html`, `omnimove-traveller.html`,
  `omnimove-admin.html`, `reset-password.html`; navigazione con `window.location.href`.
- **Geolocalizzazione** con `navigator.geolocation` (con un fallback su coordinata demo fissa).
- **Backend**: context-path `/omnimove`, in ascolto su Tomcat (porta interna `8180`).
  Nessun manifest / service worker (quindi **non** è ancora una PWA).

---

## 1. Concetti chiave (leggere prima di tutto)

### 1.1 Cos'è Capacitor (e perché è sufficiente da solo)
Capacitor è un **runtime nativo**: crea un progetto Android/iOS in cui la tua web-app gira
dentro una **WebView** (un browser "incorporato" nell'app) e le mette a disposizione le
**funzioni native** del telefono (GPS, storage sicuro, notifiche, fotocamera, splash screen)
tramite plugin. In pratica **impacchetta** il sito che hai già in un'app installabile e
pubblicabile sugli store. Non richiede nessun framework aggiuntivo: la tua UI vanilla resta
identica. Capacitor è il "guscio"; **non cambia** la logica dell'app.

### 1.2 Il concetto di "origine" (origin) — è la chiave di tutto
Un'**origine** è la terna `schema + host + porta` (es. `https://devaidalab.unicas.it:443`).
Il browser tratta due origini diverse come **mondi separati**, con regole di sicurezza
severe su cosa può parlare con cosa.

- **Oggi (web)**: la pagina e le API vivono sulla **stessa origine** (il backend serve sia
  l'HTML sia `/omnimove/api/v1`). Per questo una URL **relativa** come `/omnimove/api/v1`
  funziona e il cookie viene inviato senza problemi.
- **Con Capacitor**: i file dell'app sono serviti **in locale** dalla WebView, con
  un'origine tipo `https://localhost` (Android) o `capacitor://localhost` (iOS). Il backend,
  invece, è **remoto** (`https://devaidalab.unicas.it`). Sono **origini diverse.**

Conseguenze dirette di questo cambio di origine (e sono i nostri "blocchi"):
1. le **URL relative non risolvono** più → servono URL **assolute** verso il backend;
2. il **cookie `SameSite` non viene inviato** cross-origin → l'auth va spostata su **token**;
3. le richieste sono **cross-origin** → il backend deve abilitare **CORS** per l'app.

### 1.3 CORS (Cross-Origin Resource Sharing)
Quando una pagina chiama un'origine **diversa** dalla propria, il browser lo permette solo se
il server risponde con header che dicono "sì, accetto richieste da quell'origine"
(`Access-Control-Allow-Origin`, ecc.). Senza CORS configurato, le chiamate dell'app **falliscono**.
Quindi dovremo dire al backend: "accetta le richieste che arrivano dall'origine dell'app nativa".

### 1.4 Cookie `httpOnly` vs token `Bearer`
- **Cookie `httpOnly`** (oggi): ottimo per il **web** (immune all'XSS perché JS non lo legge),
  ma **inutilizzabile** dall'app nativa perché è legato all'origine del backend e `SameSite`
  ne blocca l'invio da un'altra origine.
- **Token `Bearer`** (per l'app): dopo il login il client riceve il JWT nel **corpo della
  risposta**, lo salva in uno **storage sicuro** nativo e lo allega a ogni richiesta con
  l'header `Authorization: Bearer <token>`. È lo standard per le app mobili.

> **Trade-off da mettere a verbale.** Il token in storage è un po' più esposto all'XSS
> rispetto al cookie `httpOnly`. Mitigazioni: storage sicuro a livello OS, **TTL breve** del
> token (già 1h), eventuale refresh, e mantenere l'escaping XSS già presente (`escHtml/escAttr`).
> Il backend continuerà a **supportare entrambi** i canali (cookie per il web, Bearer per l'app).

### 1.5 "Secure context" e perché serve HTTPS
Molte funzioni del browser (tra cui la **geolocalizzazione**) funzionano solo in un
**contesto sicuro**: `https://` oppure `localhost`. In produzione l'app parlerà con il
backend in **HTTPS**; in sviluppo useremo `localhost`/IP di LAN in HTTP (con un'eccezione
esplicita, valida **solo in dev**).

### 1.6 Reverse proxy nginx (il nostro caso di produzione)
Il server ufficiale è **`devaidalab.unicas.it`**. Davanti a Tomcat c'è **nginx**, che:
- ascolta sulla porta **443** (HTTPS) con il certificato TLS del dominio;
- **inoltra** ("reverse proxy") le richieste al **Tomcat** interno (porta `8180`),
  mantenendo il context-path `/omnimove`.

Quindi l'app userà **`https://devaidalab.unicas.it/omnimove/...`** (porta 443, sottintesa),
e nginx si occupa di girare il traffico a Tomcat. Vedi la config di esempio nella **sezione 3**.

---

## 2. I due ambienti: **DEV** e **PROD**

L'app contiene gli asset in locale ma chiama un backend **remoto**. "Quale backend?" è una
scelta di **build**. Prevediamo due configurazioni, selezionate al momento della
compilazione (nessun `if` sparso nel codice).

| Aspetto | **DEV** (sviluppo) | **PROD** (server ufficiale) |
|---|---|---|
| Backend | macchina locale/LAN, Tomcat `:8180` | `devaidalab.unicas.it` (nginx `:443` → Tomcat) |
| Protocollo | HTTP in chiaro (solo dev) | **HTTPS** (TLS su nginx) |
| API base dell'app | `http://<IP-o-10.0.2.2>:8180/omnimove/api/v1` | `https://devaidalab.unicas.it/omnimove/api/v1` |
| CORS lato server | origini native **+** host/IP dev | **solo** origini native (+ eventuale dominio PWA) |
| Rete in chiaro | abilitata (eccezione cleartext) | **disabilitata** |
| Segreti/chiavi | valori di test | valori reali, HSTS, rotazione |

**Regola pratica sugli indirizzi in DEV** (fonte di errori tipica):
- **Emulatore Android** → `localhost` del PC host si raggiunge come **`10.0.2.2`** (non `127.0.0.1`).
- **Telefono fisico** → usa l'**IP di LAN** del PC (es. `192.168.1.50`), stessa rete Wi‑Fi.
- **Simulatore iOS** → `localhost` funziona direttamente.

---

## 3. Architettura di rete in PRODUZIONE (nginx 443 → Tomcat)

Schema del flusso:

```
App nativa (WebView, origine https://localhost)
        │  HTTPS verso il dominio
        ▼
nginx  :443  (TLS: certificato devaidalab.unicas.it)
        │  proxy_pass in HTTP interno
        ▼
Tomcat :8180  (context-path /omnimove  →  app Spring Boot OMNIMOVE)
```

Esempio di configurazione nginx (indicativa):

```nginx
# Redirect di cortesia da HTTP a HTTPS
server {
    listen 80;
    server_name devaidalab.unicas.it;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    server_name devaidalab.unicas.it;

    ssl_certificate     /etc/nginx/ssl/devaidalab.fullchain.pem;
    ssl_certificate_key /etc/nginx/ssl/devaidalab.privkey.pem;

    # Inoltra tutto ciò che sta sotto /omnimove al Tomcat interno
    location /omnimove/ {
        proxy_pass http://127.0.0.1:8180;

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;   # dice a Spring "sono dietro HTTPS"
    }
}
```

**Perché `X-Forwarded-Proto` è importante.** Tomcat riceve la richiesta in HTTP (da nginx),
ma "esternamente" è HTTPS. Con quell'header Spring capisce di essere **dietro un proxy HTTPS**
e genera cookie `Secure` e redirect corretti. Nel backend va abilitato:

```yaml
# application.yml (profilo prod)
server:
  forward-headers-strategy: framework   # onora X-Forwarded-* dietro reverse proxy
```

---

## 4. Passi operativi (step by step)

### Step 1 — Isolare il "bundle app" del solo flusso traveller
L'app mobile è per i **viaggiatori**: niente console admin.

- Creare una cartella dedicata `omnimove-app/www/` che contenga **solo**
  `omnimove-traveller.*`, `omnimove-login.*`, `reset-password.*` e i relativi asset.
- **Escludere** `omnimove-admin.*`.
- Questa cartella sarà il `webDir` di Capacitor. Il backend continua a servire il sito web
  completo come oggi: la versione mobile è una **copia curata**, non una frattura del progetto.
- Consigliato: uno **script** che copia gli asset da `static/` verso `omnimove-app/www/`,
  così web e mobile restano allineati.

### Step 2 — Un file di configurazione per ambiente
Introdurre un file caricato **per primo**, che espone la configurazione a runtime.

`omnimove-app/www/env.js` (rigenerato per ambiente, **non** committare valori sensibili):
```js
window.OMNIMOVE_ENV = {
  apiBase: 'https://devaidalab.unicas.it/omnimove/api/v1', // in dev: http://10.0.2.2:8180/...
  env: 'prod'
};
```
Tenere due template versionati: `env.dev.js` e `env.prod.js`. In tutte le `*.html`
includere `<script src="env.js"></script>` **prima** degli altri script.

### Step 3 — Rendere le chiamate API assolute e configurabili
- In `omnimove-traveller.js:26`:
  ```js
  const API_BASE = (window.OMNIMOVE_ENV && window.OMNIMOVE_ENV.apiBase)
                   || '/omnimove/api/v1'; // fallback per l'uso web classico
  ```
- Sostituire le poche URL scritte a mano (`/omnimove/api/v1/auth/logout`, `/auth/account`…)
  con `API_BASE + '/auth/...'`.
- Stesso pattern in `omnimove-login.js` e `reset-password.js`.
- **Vantaggio**: un unico codice funziona sia come web servito dal backend (fallback relativo)
  sia come app nativa (base assoluta dell'ambiente).

### Step 4 — Autenticazione: aggiungere il canale token `Bearer`
Lato **client**:
- Al login, leggere il JWT dalla **risposta JSON** e salvarlo in **storage sicuro** nativo
  (plugin `@capacitor/preferences` o Secure Storage), non in `localStorage`.
- Ogni `fetch` verso `API_BASE` allega `Authorization: Bearer <token>`.
- Logout/eliminazione account usano il token.

Lato **backend** (modifica minima e retro-compatibile):
- `POST /auth/login` deve restituire il JWT **anche nel body** (oltre a impostare il cookie
  per il canale web). Così web e app condividono lo stesso backend.
- Verificare che il filtro JWT (`JwtFilter`) accetti **sia** il cookie **sia** l'header
  `Authorization: Bearer` (mantenere entrambi i canali).

### Step 5 — Self-hosting degli asset (niente CDN a runtime)
- Scaricare in locale, in `omnimove-app/www/vendor/`: `leaflet.js`, `leaflet.css` (+ immagini
  dei marker) e i **font** (Orbitron, Plus Jakarta Sans, Syne, DM Mono).
- Aggiornare i `<link>`/`<script>` nelle `*.html` per puntare ai file locali.
- **Perché**: un'app deve funzionare anche con rete debole, non deve dipendere da domini terzi,
  e gli store premiano app autoconsistenti; inoltre evita problemi con eventuale CSP dell'app.

### Step 6 — CORS lato backend per i due ambienti
- Origini native da consentire: `https://localhost`, `capacitor://localhost`
  (e `http://localhost` per alcuni casi).
- **DEV**: consentire **anche** l'host/IP di sviluppo.
- **PROD**: consentire **solo** le origini native (+ eventuale dominio PWA).
- Configurare via `application.yml`/env **per profilo** (`dev`/`prod`).
- **Da sistemare prima**: esiste una **doppia configurazione CORS** in conflitto
  (`CorsConfig` vs `SecurityConfig`) — consolidarne **una sola** prima di aggiungere le origini.

### Step 7 — Inizializzare Capacitor
Nella cartella `omnimove-app/`:
```bash
npm init -y
npm install @capacitor/core @capacitor/cli
npx cap init "OMNIMOVE" "it.unicas.omnimove" --web-dir=www
npm install @capacitor/android
npx cap add android
# iOS solo su macOS:
# npm install @capacitor/ios && npx cap add ios
```
`capacitor.config.json`:
```jsonc
{
  "appId": "it.unicas.omnimove",   // reverse-domain, coerente col package Java
  "appName": "OMNIMOVE",
  "webDir": "www",
  "server": { "androidScheme": "https" }  // "secure context": abilita GPS ecc.
}
```

### Step 8 — Plugin e permessi nativi
- **Geolocalizzazione**: `npm install @capacitor/geolocation`. Usare l'API del plugin (o
  affiancarla a `navigator.geolocation`) per gestire i **permessi**; migliorare il fallback
  "permesso negato" oggi silenzioso (coordinata demo fissa).
- **Storage sicuro** del token: `@capacitor/preferences`.
- **Splash screen / icone**: `@capacitor/splash-screen` (+ generazione asset).
- Dichiarazioni permessi:
  - **Android** (`AndroidManifest.xml`): `INTERNET`,
    `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`.
  - **iOS** (`Info.plist`): `NSLocationWhenInUseUsageDescription` (testo mostrato all'utente).

### Step 9 — Rete per ambiente (chiaro in dev, HTTPS in prod)
- **DEV (Android)**: consentire HTTP verso l'host dev con un
  `network_security_config.xml` (limitato al dominio/IP di sviluppo).
- **DEV (iOS)**: eccezione ATS in `Info.plist` per l'host dev.
- **PROD**: **nessuna** eccezione in chiaro → tutto **HTTPS** verso
  `https://devaidalab.unicas.it` (TLS gestito da nginx).
- Le eccezioni cleartext devono restare **fuori** dalla build di prod (le gestiamo con i due
  ambienti dello Step 2/10).

### Step 10 — Build & Run
In `omnimove-app/package.json`:
```jsonc
"scripts": {
  "build:dev":  "node scripts/apply-env.js dev  && npx cap sync",
  "build:prod": "node scripts/apply-env.js prod && npx cap sync",
  "run:android":  "npx cap run android",
  "open:android": "npx cap open android"
}
```
(`apply-env.js` copia `env.dev.js`/`env.prod.js` in `www/env.js` e imposta la network config giusta.)

Flusso tipico:
```bash
# DEV — backend locale attivo su :8180
npm run build:dev
npm run run:android      # emulatore/dispositivo → punta al backend dev

# PROD — con il dominio online in HTTPS
npm run build:prod
npm run open:android     # build firmata per lo store
```

### Step 11 — Checklist di test
- [ ] Login / registrazione / reset-password funzionano via **token Bearer** (dev e prod).
- [ ] Nessuna chiamata fallisce per **CORS** o per URL relativa.
- [ ] Mappa Leaflet e font caricano **da locale** (nessuna richiesta a CDN).
- [ ] Geolocalizzazione con permesso **concesso** e **negato** (UX corretta).
- [ ] Journey planner (ricerca, opzioni, leg, polyline) identico al web.
- [ ] Bottom sheet, safe-area e tastiera su schermo su **dispositivo reale**.
- [ ] Build **prod** senza eccezioni cleartext e con base API su `https://devaidalab.unicas.it`.

### Step 12 — Pubblicazione sugli store (cenni)
- **Android**: keystore di firma, `versionCode`/`versionName`, pacchetto **AAB** per Play Store.
- **iOS**: certificati/provisioning, App Store Connect (**richiede macOS**).
- **Privacy**: dichiarare l'uso della posizione e la policy sui dati.

> **Nota piattaforme.** Su questa macchina **Windows** si può sviluppare/buildare **solo
> Android**. La build **iOS** richiede un **Mac** (o CI macOS) con Xcode.

---

## 5. Riepilogo file da creare/modificare

**Nuovi (progetto app):**
- `omnimove-app/` — progetto Capacitor (`package.json`, `capacitor.config.json`).
- `omnimove-app/www/` — bundle traveller (copia curata degli asset).
- `omnimove-app/www/env.js` + template `env.dev.js` / `env.prod.js`.
- `omnimove-app/www/vendor/` — Leaflet + font self-hosted.
- `omnimove-app/scripts/apply-env.js` — selezione ambiente a build-time.
- Config di rete per piattaforma (Android `network_security_config.xml`; iOS ATS).

**Modifiche (client esistente, retro-compatibili col web):**
- `omnimove-traveller.js` — `API_BASE` da `OMNIMOVE_ENV`; auth Bearer + storage sicuro;
  geolocalizzazione via plugin; pulizia del codice token oggi morto.
- `omnimove-login.js` / `reset-password.js` — base API da env; salvataggio token dal body.
- `*.html` — asset locali al posto dei CDN; `env.js` incluso per primo.

**Modifiche (backend, minime):**
- `POST /auth/login` — restituire il JWT anche nel body.
- **CORS** — consolidare `CorsConfig`/`SecurityConfig` e impostare le origini **per profilo**.
- `application.yml` (prod) — `server.forward-headers-strategy: framework` (dietro nginx).
- Profili `dev`/`prod` (origini CORS, flag).

**Infra (produzione):**
- **nginx** su `devaidalab.unicas.it`: TLS su **443**, `proxy_pass` a **Tomcat :8180**,
  con `X-Forwarded-Proto https` (vedi sezione 3).

---

## 6. Ordine consigliato (milestone)

1. **M1 — Sbloccanti (utili anche per una PWA):** Step 2–3 (env + API assolute),
   Step 4 (auth Bearer), Step 5 (asset locali), Step 6 (CORS). Alla fine, il client funziona
   già da un'origine **diversa** dal backend.
2. **M2 — Guscio nativo (solo Android su Windows):** Step 1 (bundle), Step 7 (init),
   Step 8 (plugin/permessi), Step 9 (rete dev), Step 10 (build:dev + run). App di sviluppo funzionante.
3. **M3 — Produzione & store:** verifica nginx/TLS su `devaidalab.unicas.it`, `build:prod`,
   Step 11 (test), Step 12 (rilascio). iOS quando disponibile un Mac/CI macOS.

> Le attività di **M1** sono utili **a prescindere**: rendono il client indipendente
> dall'origine e sono il prerequisito comune sia per Capacitor sia per una eventuale PWA.
