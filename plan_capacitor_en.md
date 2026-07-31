# Plan — Bringing the OMNIMOVE client to mobile with Capacitor

> **Goal.** Turn the **traveller flow** of the OMNIMOVE web client (plain "vanilla"
> HTML/CSS/JS + Leaflet map) into a native **Android/iOS** app using **Capacitor**,
> **without rewriting the UI** and keeping the current stack.
>
> This document is meant to be **didactic**: it first explains *why* certain changes are
> needed, then *what* to do, step by step. If this is your first "web app → native app"
> journey, read section **1 (Key concepts)** carefully — without those ideas the operative
> steps look like magic.

---

## 0. Current state of the code (our starting point)

In the client (`omnimove-backend/src/main/resources/static/`) today we have:

- **Relative API base** — it assumes page and API live on the **same server**:
  - `omnimove-traveller.js:26` → `const API_BASE = '/omnimove/api/v1';`
  - plus a few hand-written URLs (`/omnimove/api/v1/auth/logout`, `/auth/account`).
- **Authentication via an `httpOnly` `SameSite` cookie**: after login the JWT lives in a
  cookie the browser sends **on its own**, but which **JavaScript cannot read** (a security
  choice against XSS). Some `Authorization: Bearer` code exists but is **dead**
  (`omnimove-traveller.js:225` reads a token from `localStorage` that nobody writes anymore).
- **Libraries loaded from a CDN**: Leaflet 1.9.4 (`cdn.jsdelivr.net`) and Google Fonts.
- **Multi-page site**: `omnimove-login.html`, `omnimove-traveller.html`,
  `omnimove-admin.html`, `reset-password.html`; navigation via `window.location.href`.
- **Geolocation** via `navigator.geolocation` (with a fallback to a fixed demo coordinate).
- **Backend**: context-path `/omnimove`, running on Tomcat (internal port `8180`).
  No manifest / service worker (so it is **not** a PWA yet).

---

## 1. Key concepts (read this first)

### 1.1 What Capacitor is (and why it alone is enough)
Capacitor is a **native runtime**: it creates an Android/iOS project where your web app runs
inside a **WebView** (a browser "embedded" in the app) and gives it access to the phone's
**native features** (GPS, secure storage, notifications, camera, splash screen) through
plugins. In practice it **packages** the site you already have into an installable app you can
publish on the stores. It needs no extra framework: your vanilla UI stays identical. Capacitor
is the "shell"; it **does not change** the app's logic.

### 1.2 The concept of "origin" — this is the key to everything
An **origin** is the triple `scheme + host + port` (e.g. `https://devaidalab.unicas.it:443`).
The browser treats two different origins as **separate worlds**, with strict security rules on
who may talk to whom.

- **Today (web)**: page and API live on the **same origin** (the backend serves both the HTML
  and `/omnimove/api/v1`). That is why a **relative** URL like `/omnimove/api/v1` works and the
  cookie is sent with no trouble.
- **With Capacitor**: the app's files are served **locally** by the WebView, from an origin
  like `https://localhost` (Android) or `capacitor://localhost` (iOS). The backend, instead, is
  **remote** (`https://devaidalab.unicas.it`). They are **different origins.**

Direct consequences of this origin change (these are our "blockers"):
1. **relative URLs no longer resolve** → we need **absolute** URLs to the backend;
2. the **`SameSite` cookie is not sent** cross-origin → auth must move to a **token**;
3. requests become **cross-origin** → the backend must enable **CORS** for the app.

### 1.3 CORS (Cross-Origin Resource Sharing)
When a page calls an origin **different** from its own, the browser allows it only if the
server replies with headers saying "yes, I accept requests from that origin"
(`Access-Control-Allow-Origin`, etc.). Without CORS configured, the app's calls **fail**. So we
must tell the backend: "accept requests coming from the native app's origin".

### 1.4 `httpOnly` cookie vs `Bearer` token
- **`httpOnly` cookie** (today): great for the **web** (immune to XSS because JS can't read it),
  but **unusable** from the native app because it is bound to the backend's origin and
  `SameSite` blocks sending it from another origin.
- **`Bearer` token** (for the app): after login the client receives the JWT in the **response
  body**, stores it in native **secure storage**, and attaches it to every request via the
  `Authorization: Bearer <token>` header. This is the standard for mobile apps.

> **Trade-off to record.** A token in storage is slightly more exposed to XSS than an
> `httpOnly` cookie. Mitigations: OS-level secure storage, **short token TTL** (already 1h),
> optional refresh, and keeping the existing XSS escaping (`escHtml/escAttr`). The backend will
> keep **supporting both** channels (cookie for the web, Bearer for the app).

### 1.5 "Secure context" and why HTTPS is required
Many browser features (including **geolocation**) only work in a **secure context**: `https://`
or `localhost`. In production the app will talk to the backend over **HTTPS**; in development
we will use `localhost`/LAN IP over HTTP (with an explicit exception, valid **only in dev**).

### 1.6 The nginx reverse proxy (our production setup)
The official server is **`devaidalab.unicas.it`**. In front of Tomcat there is **nginx**, which:
- listens on port **443** (HTTPS) with the domain's TLS certificate;
- **forwards** ("reverse proxy") requests to the internal **Tomcat** (port `8180`), preserving
  the `/omnimove` context-path.

So the app will use **`https://devaidalab.unicas.it/omnimove/...`** (port 443, implicit), and
nginx handles routing traffic to Tomcat. See the sample config in **section 3**.

---

## 2. The two environments: **DEV** and **PROD**

The app bundles its assets locally but calls a **remote** backend. "Which backend?" is a
**build** choice. We provide two configurations, selected at build time (no `if` scattered
across the code).

| Aspect | **DEV** (development) | **PROD** (official server) |
|---|---|---|
| Backend | local/LAN machine, Tomcat `:8180` | `devaidalab.unicas.it` (nginx `:443` → Tomcat) |
| Protocol | plain HTTP (dev only) | **HTTPS** (TLS on nginx) |
| App API base | `http://<IP-or-10.0.2.2>:8180/omnimove/api/v1` | `https://devaidalab.unicas.it/omnimove/api/v1` |
| Server-side CORS | native origins **+** dev host/IP | **only** native origins (+ optional PWA domain) |
| Cleartext network | enabled (cleartext exception) | **disabled** |
| Secrets/keys | test values | real values, HSTS, rotation |

**Practical rule about addresses in DEV** (a classic source of errors):
- **Android emulator** → the host PC's `localhost` is reached as **`10.0.2.2`** (not `127.0.0.1`).
- **Physical phone** → use the PC's **LAN IP** (e.g. `192.168.1.50`), same Wi‑Fi network.
- **iOS simulator** → `localhost` works directly.

---

## 3. Production network architecture (nginx 443 → Tomcat)

Flow diagram:

```
Native app (WebView, origin https://localhost)
        │  HTTPS to the domain
        ▼
nginx  :443  (TLS: devaidalab.unicas.it certificate)
        │  internal HTTP proxy_pass
        ▼
Tomcat :8180  (context-path /omnimove  →  OMNIMOVE Spring Boot app)
```

Sample nginx configuration (indicative):

```nginx
# Courtesy redirect from HTTP to HTTPS
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

    # Forward everything under /omnimove to the internal Tomcat
    location /omnimove/ {
        proxy_pass http://127.0.0.1:8180;

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;   # tells Spring "I'm behind HTTPS"
    }
}
```

**Why `X-Forwarded-Proto` matters.** Tomcat receives the request over HTTP (from nginx), but
"externally" it is HTTPS. With that header Spring understands it is **behind an HTTPS proxy**
and generates `Secure` cookies and correct redirects. Enable it in the backend:

```yaml
# application.yml (prod profile)
server:
  forward-headers-strategy: framework   # honor X-Forwarded-* behind a reverse proxy
```

---

## 4. Operative steps (step by step)

### Step 1 — Isolate the "app bundle" of the traveller flow only
The mobile app is for **travellers**: no admin console.

- Create a dedicated folder `omnimove-app/www/` containing **only**
  `omnimove-traveller.*`, `omnimove-login.*`, `reset-password.*` and their assets.
- **Exclude** `omnimove-admin.*`.
- This folder becomes Capacitor's `webDir`. The backend keeps serving the full website as
  today: the mobile version is a **curated copy**, not a fork of the project.
- Recommended: a **script** that copies assets from `static/` into `omnimove-app/www/`, so web
  and mobile stay aligned.

### Step 2 — One configuration file per environment
Introduce a file loaded **first**, exposing runtime configuration.

`omnimove-app/www/env.js` (regenerated per environment, do **not** commit sensitive values):
```js
window.OMNIMOVE_ENV = {
  apiBase: 'https://devaidalab.unicas.it/omnimove/api/v1', // in dev: http://10.0.2.2:8180/...
  env: 'prod'
};
```
Keep two versioned templates: `env.dev.js` and `env.prod.js`. In every `*.html` include
`<script src="env.js"></script>` **before** the other scripts.

### Step 3 — Make API calls absolute and configurable
- In `omnimove-traveller.js:26`:
  ```js
  const API_BASE = (window.OMNIMOVE_ENV && window.OMNIMOVE_ENV.apiBase)
                   || '/omnimove/api/v1'; // fallback for the classic web use
  ```
- Replace the few hand-written URLs (`/omnimove/api/v1/auth/logout`, `/auth/account`…) with
  `API_BASE + '/auth/...'`.
- Same pattern in `omnimove-login.js` and `reset-password.js`.
- **Benefit**: a single codebase works both as web served by the backend (relative fallback)
  and as a native app (absolute base from the environment).

### Step 4 — Authentication: add the `Bearer` token channel
On the **client**:
- At login, read the JWT from the **JSON response** and store it in native **secure storage**
  (`@capacitor/preferences` plugin or Secure Storage), not in `localStorage`.
- Every `fetch` to `API_BASE` attaches `Authorization: Bearer <token>`.
- Logout / account deletion use the token.

On the **backend** (minimal, backward-compatible change):
- `POST /auth/login` must return the JWT **also in the body** (besides setting the cookie for
  the web channel). This way web and app share the same backend.
- Verify the JWT filter (`JwtFilter`) accepts **both** the cookie **and** the
  `Authorization: Bearer` header (keep both channels).

### Step 5 — Self-host the assets (no CDN at runtime)
- Download locally, into `omnimove-app/www/vendor/`: `leaflet.js`, `leaflet.css` (+ marker
  images) and the **fonts** (Orbitron, Plus Jakarta Sans, Syne, DM Mono).
- Update the `<link>`/`<script>` tags in the `*.html` to point to local files.
- **Why**: an app must work even on a weak network, must not depend on third-party domains, and
  stores favor self-contained apps; it also avoids issues with any app-side CSP.

### Step 6 — Backend CORS for the two environments
- Native origins to allow: `https://localhost`, `capacitor://localhost` (and `http://localhost`
  in some cases).
- **DEV**: **also** allow the development host/IP.
- **PROD**: allow **only** the native origins (+ optional PWA domain).
- Configure via `application.yml`/env **per profile** (`dev`/`prod`).
- **Fix first**: there is a **conflicting double CORS configuration** (`CorsConfig` vs
  `SecurityConfig`) — consolidate to **a single one** before adding the origins.

### Step 7 — Initialize Capacitor
In the `omnimove-app/` folder:
```bash
npm init -y
npm install @capacitor/core @capacitor/cli
npx cap init "OMNIMOVE" "it.unicas.omnimove" --web-dir=www
npm install @capacitor/android
npx cap add android
# iOS only on macOS:
# npm install @capacitor/ios && npx cap add ios
```
`capacitor.config.json`:
```jsonc
{
  "appId": "it.unicas.omnimove",   // reverse-domain, consistent with the Java package
  "appName": "OMNIMOVE",
  "webDir": "www",
  "server": { "androidScheme": "https" }  // "secure context": enables GPS, etc.
}
```

### Step 8 — Native plugins and permissions
- **Geolocation**: `npm install @capacitor/geolocation`. Use the plugin API (or pair it with
  `navigator.geolocation`) to handle **permissions**; improve the currently silent
  "permission denied" fallback (fixed demo coordinate).
- **Secure storage** for the token: `@capacitor/preferences`.
- **Splash screen / icons**: `@capacitor/splash-screen` (+ asset generation).
- Permission declarations:
  - **Android** (`AndroidManifest.xml`): `INTERNET`,
    `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`.
  - **iOS** (`Info.plist`): `NSLocationWhenInUseUsageDescription` (text shown to the user).

### Step 9 — Networking per environment (cleartext in dev, HTTPS in prod)
- **DEV (Android)**: allow HTTP to the dev host with a `network_security_config.xml` (limited to
  the dev domain/IP).
- **DEV (iOS)**: an ATS exception in `Info.plist` for the dev host.
- **PROD**: **no** cleartext exception → everything over **HTTPS** to
  `https://devaidalab.unicas.it` (TLS handled by nginx).
- Cleartext exceptions must stay **out** of the prod build (handled via the two environments in
  Step 2/10).

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
(`apply-env.js` copies `env.dev.js`/`env.prod.js` into `www/env.js` and sets the right network config.)

Typical flow:
```bash
# DEV — local backend running on :8180
npm run build:dev
npm run run:android      # emulator/device → points to the dev backend

# PROD — with the domain online over HTTPS
npm run build:prod
npm run open:android     # signed build for the store
```

### Step 11 — Test checklist
- [ ] Login / registration / reset-password work via **Bearer token** (dev and prod).
- [ ] No call fails due to **CORS** or a relative URL.
- [ ] Leaflet map and fonts load **locally** (no CDN request).
- [ ] Geolocation with permission **granted** and **denied** (correct UX).
- [ ] Journey planner (search, options, legs, polyline) identical to the web.
- [ ] Bottom sheet, safe-area and on-screen keyboard on a **real device**.
- [ ] **Prod** build with no cleartext exceptions and API base on `https://devaidalab.unicas.it`.

### Step 12 — Store publishing (notes)
- **Android**: signing keystore, `versionCode`/`versionName`, **AAB** package for the Play Store.
- **iOS**: certificates/provisioning, App Store Connect (**requires macOS**).
- **Privacy**: declare location usage and the data policy.

> **Platform note.** On this **Windows** machine you can develop/build **Android only**. The
> **iOS** build requires a **Mac** (or macOS CI) with Xcode.

---

## 5. Files to create/modify (summary)

**New (app project):**
- `omnimove-app/` — Capacitor project (`package.json`, `capacitor.config.json`).
- `omnimove-app/www/` — traveller bundle (curated asset copy).
- `omnimove-app/www/env.js` + templates `env.dev.js` / `env.prod.js`.
- `omnimove-app/www/vendor/` — self-hosted Leaflet + fonts.
- `omnimove-app/scripts/apply-env.js` — build-time environment selection.
- Per-platform network config (Android `network_security_config.xml`; iOS ATS).

**Changes (existing client, backward-compatible with the web):**
- `omnimove-traveller.js` — `API_BASE` from `OMNIMOVE_ENV`; Bearer auth + secure storage;
  geolocation via plugin; cleanup of the now-dead token code.
- `omnimove-login.js` / `reset-password.js` — API base from env; save token from the body.
- `*.html` — local assets instead of CDNs; `env.js` included first.

**Changes (backend, minimal):**
- `POST /auth/login` — also return the JWT in the body.
- **CORS** — consolidate `CorsConfig`/`SecurityConfig` and set origins **per profile**.
- `application.yml` (prod) — `server.forward-headers-strategy: framework` (behind nginx).
- `dev`/`prod` profiles (CORS origins, flags).

**Infra (production):**
- **nginx** on `devaidalab.unicas.it`: TLS on **443**, `proxy_pass` to **Tomcat :8180**, with
  `X-Forwarded-Proto https` (see section 3).

---

## 6. Recommended order (milestones)

1. **M1 — Unblockers (useful for a PWA too):** Steps 2–3 (env + absolute APIs), Step 4 (Bearer
   auth), Step 5 (local assets), Step 6 (CORS). At the end, the client already works from an
   origin **different** from the backend.
2. **M2 — Native shell (Android only on Windows):** Step 1 (bundle), Step 7 (init), Step 8
   (plugins/permissions), Step 9 (dev networking), Step 10 (build:dev + run). Working dev app.
3. **M3 — Production & store:** verify nginx/TLS on `devaidalab.unicas.it`, `build:prod`,
   Step 11 (tests), Step 12 (release). iOS once a Mac/macOS CI is available.

> The **M1** work is useful **regardless**: it makes the client independent from the origin and
> is the common prerequisite for both Capacitor and a possible PWA.
