# NOVA Extensions — `.novaext` format & FBState behaviour (v2.4)

> **Status:** design spec for NOVA Browser **v2.4** (version code **8**, version
> name **2.4** — bumped by Lane D in `tools/build-apk.sh`).
> **Scope:** this document is the whole design. Lane B and Lane C implement
> from it. No code is written here and no behaviour outside this document
> is in scope for v2.4.
>
> **What already exists (this design reuses, not reinvents):**
> `extensions/Userscript.kt` (Userscript model, `MatchPattern` glob
> semantics, `UserscriptParser`, `MAX_SCRIPT_SIZE_BYTES = 200 KB`),
> `extensions/ExtensionRuntime.kt` (`scriptsForUrl` / `scriptsForRunAt`,
> sensitive-page gating, `installFromText`, `slugify`),
> `data/ExtensionStore.kt` (`extensions/extensions.json` index +
> `extensions/scripts/<id>.js`, per-site disable lists, sensitive-host
> opt-ins, `:incognito` private-mode guard, atomic index writes with
> `.bak`), `ui/ExtensionsScreen.kt` (existing Extensions UI),
> `web/NovaClients.kt` (`injectExtensions` — probe, then
> `evaluateJavascript` per script), and the built-in registry
> (YouTube Ad Skipper, Cookie Banner Hider).
>
> **Theme:** Expressive Dark (v2.3). Every new screen in this spec reuses
> the existing `ui/Theme.kt` components (`GlassTopAppBar`, `GlassCard`,
> `NebulaEmptyState`, …). No new style, no Nebula reintroduction.
>
> **Privacy (plan §8):** no account, no server. Extensions and FBState
> data live on the device only. Nothing in this spec sends browsing data
> anywhere.

---

## 1. The `.novaext` package

### 1.1 Zip layout

A `.novaext` file is a ZIP archive. Exact layout — nothing else is allowed:

```
example.novaext
├── manifest.json          (required, at zip root, ≤ 16 KB)
├── main.js                (required, exactly one .js file, at zip root, ≤ 200 KB)
└── icon.png               (optional, at zip root, PNG only, ≤ 128 KB)
```

Rules:

- **No subdirectories.** Every entry must sit at the zip root. Any entry
  whose name contains `/` (other than a trailing `/` on a directory entry,
  which is itself rejected) is rejected.
- **No path traversal.** Any entry name containing `..`, starting with
  `/` or `\`, containing `\`, or containing a drive prefix (`C:`) is
  rejected before anything is extracted. Extraction never writes outside
  the app's own `extensions/` directory: the installer reads entries
  in-memory and writes exactly one file, `extensions/scripts/<id>.js`,
  where `<id>` comes from `slugify(manifest.name)` and is re-checked with
  `ExtensionRuntime.isValidScriptId`.
- **Allowlist, not blocklist.** The only permitted entries are
  `manifest.json`, exactly one `*.js` file, and optionally `icon.png`.
  Any other entry (README, second script, `.txt`, nested folder, …) is
  rejected — see the error table in §1.5.
- **Size limits** (checked before unzipping anything beyond the central
  directory): whole zip ≤ **512 KB**; at most **8 entries** (the
  allowlist makes 3 the real maximum; the count cap is a zip-bomb
  backstop). Each entry is also capped individually
  (manifest 16 KB, script 200 KB, icon 128 KB).
- **Duplicate entry names** (case-insensitive) are rejected.
- The zip may use DEFLATE or STORE. Encrypted / password-protected zips
  are rejected ("not a .novaext package" — the reader cannot open them,
  and v2.4 does not ask the user for a zip password).

### 1.2 One zip = one Userscript

**Decision: one `.novaext` package installs exactly one `Userscript`.**
Multi-script packages are NOT supported in v2.4.

Reasons: the whole existing engine is one-script-per-record —
`Userscript` has one `source`, one `matches` list, one `runAt`;
`ExtensionStore` persists one `<id>.js` per record; the Extensions
screen shows one row per script with one enable switch and one
per-site list. A multi-script package would need a new model, a new
store shape, and a new injection order — that is a v2.5-or-later
decision, not a v2.4 one. A package that needs two scripts ships as
two packages.

### 1.3 `manifest.json` schema

`manifest.json` is a JSON object. Unknown fields are ignored (forward
compatibility — a future field must not break a v2.4 install), except
that a field with a wrong *type* where a known field is expected is a
validation error.

| Field | Type | Required | Constraint | Maps to |
|---|---|---|---|---|
| `manifest_version` | integer | yes | must be exactly `1` | — (format version; a future v2 manifest is a new spec) |
| `name` | string | yes | 1–100 chars; single line — no `\n` or `\r`, no control chars | `Userscript.name` → `id` via `slugify(name)` |
| `version` | string | yes | 1–20 chars; single line; must match `^[0-9]+\.[0-9]+(\.[0-9]+)?(-[A-Za-z0-9.-]+)?$` (e.g. `1.0`, `1.2.3`, `2.0-beta.1`) | `Userscript.version` |
| `description` | string | no | ≤ 500 chars; single line — no `\n` or `\r` | `Userscript.description` (default `""`) |
| `matches` | array of strings | yes | 1–20 entries; each must be a valid `@match` pattern (see below) | `Userscript.matches` |
| `run-at` | string | no | `"document-end"` (default) or `"document-start"`; any other value is an error, not a silent default | `Userscript.runAt` |
| `content_script` | string | yes | must name the single `.js` file in the zip (e.g. `"main.js"`); bare filename only — no path, no `/` | selects the script source |
| `icon` | string | no | if present, must be exactly `"icon.png"` | `extensions/icons/<id>.png` (validated + stored; not displayed in v2.4 — reserved, see §1.6) |
| `permissions` | array of strings | no | at most 5 entries; the **only** allowed value is `"sensitive-pages"` (see §1.4) | install-review text only — grants nothing |

**`matches` pattern validation.** Each entry must satisfy the shape
`MatchPattern` already implements: `scheme://host/path`, where scheme
is `*`, `http`, or `https` (`*` means http/https only, never
ftp/file/about); host is non-empty with no whitespace; path is present
(a pattern with no path is read as `/*`). The exact glob semantics are
unchanged from `Userscript.kt`: `*.example.com` matches subdomains only
(never the bare host), `*` in a label matches any run of characters
except `.`, path `*` matches anything including `/` and `?`, scheme/host
compare case-insensitively, path case-sensitively. A pattern that fails
the shape check is rejected with the error in §1.5 — the installer never
installs a script whose scope it cannot parse.

**Example manifest** (a dark-mode-forcing script):

```json
{
  "manifest_version": 1,
  "name": "Force Dark Example",
  "version": "1.0",
  "description": "Forces a dark background on example.com pages.",
  "matches": ["*://example.com/*", "*://*.example.com/*"],
  "run-at": "document-end",
  "content_script": "main.js",
  "icon": "icon.png",
  "permissions": []
}
```

### 1.4 Permissions — what the NOVA host actually grants

Honest statement, carried over from `Userscript.kt` / `ExtensionRuntime.kt`:

- A content script runs **as the page**, with full page privileges. It
  can read page content (including password fields and non-HttpOnly
  cookies), use the page's own `fetch()`/`XHR`, and act as the user on
  the page. There is **no native JS bridge** to app code in v2.4, so
  there is no privileged `nova.*` API — and "no native network API"
  must never be read as "cannot exfiltrate": the page's network is fully
  available to the script. The only boundaries are the `@match` scope
  and the sensitive-page gating (§3).
- **v2.4 grants no extra permission beyond running on matched hosts.**
  There is no cookie API, no storage API, no tabs API, no clipboard API
  and no background-page API for user scripts. Declaring any of
  `"cookies"`, `"storage"`, `"tabs"`, `"clipboard"`, `"fetch"`,
  `"webRequest"`, `"background"`, or anything else in `permissions` is a
  **validation error** (see §1.5) — the installer refuses to pretend to
  grant something the runtime cannot enforce. The plan §5 `nova.storage`
  / `nova.tabs` / `nova.fetch` / `nova.notify` APIs are future work,
  not v2.4.
- The single allowed token, `"sensitive-pages"`, is a **declaration of
  intent only**: it says the author *wants* the script to run on
  sensitive pages. It grants nothing. The script still runs on a
  sensitive page only when the user explicitly allows that host for that
  script in the Extensions screen (§3) — and the install review shows an
  extra warning line when the token is present: *"This extension asks to
  run on login and banking pages. It will still need your explicit
  allow for each host."*

### 1.5 Validation order and exact error strings

Validation runs in this order; the first failure stops the install and
its exact string is shown to the user (in the Extensions screen status
line, same as today's `Install failed: …`). Strings are user-facing and
final — Lane B/C must use them verbatim, not paraphrase.

| # | Check | Exact error string |
|---|---|---|
| 1 | File is not a readable ZIP (bad magic, corrupt, encrypted) | `That file is not a .novaext package — choose a .novaext file.` |
| 2 | Zip size > 512 KB | `The .novaext file is larger than 512 KB.` |
| 3 | More than 8 entries | `The package has too many files (more than 8).` |
| 4 | Entry name is unsafe (`..`, leading `/` or `\`, `\`, drive prefix) | `The package contains an unsafe file path "<name>".` |
| 5 | Entry is not in the allowlist (not `manifest.json`, the one `.js`, or `icon.png`; any subdirectory / directory entry) | `The package contains an unexpected file "<name>" — only manifest.json, one .js script and icon.png are allowed.` |
| 6 | Duplicate entry name (case-insensitive) | `The package contains the file "<name>" more than once.` |
| 7 | `manifest.json` missing | `manifest.json is missing from the package.` |
| 8 | `manifest.json` > 16 KB | `manifest.json is larger than 16 KB.` |
| 9 | `manifest.json` is not valid JSON | `manifest.json is not valid JSON.` |
| 10 | `manifest_version` missing or not `1` | `manifest_version must be 1.` |
| 11 | `name` missing / empty / > 100 chars / multi-line | `The manifest needs a "name" (1–100 characters, one line).` |
| 12 | `version` missing / malformed | `The version "<v>" is not valid — use numbers like 1.0 or 1.2.3.` (missing version uses the same string with `<v>` empty — Lane B/C: use `The manifest needs a "version" (like 1.0).` when the field is absent) |
| 13 | `description` > 500 chars / multi-line | `The description is too long — keep it under 500 characters on one line.` |
| 14 | `matches` missing / empty / > 20 entries | `The manifest needs at least one "matches" entry.` |
| 15 | A `matches` entry fails the shape check | `"<pattern>" is not a valid match pattern — use a shape like *://example.com/*.` |
| 16 | `run-at` present and not `document-start` / `document-end` | `"run-at" must be "document-start" or "document-end".` |
| 17 | `content_script` missing / empty / contains `/` | `The manifest needs a "content_script" file (for example "main.js").` |
| 18 | The named script file is not in the zip | `The script file "<f>" is missing from the package.` |
| 19 | More than one `.js` file in the zip | `The package has more than one script file — one .novaext package installs one script.` |
| 20 | Script file contains a `// ==UserScript==` header block | `The script file contains a // ==UserScript== header — remove it; the manifest is the source of truth.` |
| 21 | Script size > 200 KB | `Script is larger than 200 KB and was rejected` (reused verbatim from `installFromText`) |
| 22 | `icon` present and not `"icon.png"` | `The "icon" must be "icon.png".` |
| 23 | `icon.png` present and not a PNG (magic bytes `89 50 4E 47`) | `icon.png is not a PNG image.` |
| 24 | `icon.png` > 128 KB | `The icon is larger than 128 KB.` |
| 25 | `permissions` contains a disallowed value | `The permission "<p>" is not supported in v2.4 — the only permission a package may declare is "sensitive-pages".` |
| 26 | `slugify(name)` is not a valid script id | `The script name "<name>" cannot be used as an id — use only letters, digits and dashes` (reused from `installFromText`) |
| 27 | Id collides with a built-in id | `A built-in script already uses the name "<name>"` (reused from `installFromText`) |
| 28 | Id already installed | `A script with the id "<id>" is already installed — delete it first to reinstall` (reused from `installFromText`) |

Notes on the checks: check 20 exists because the manifest is the single
source of truth — a script that carries both a header and a manifest
would have two competing names/scopes, and silently picking one would
be a lie. Checks 26–28 are the same checks `installFromText` already
performs, in the same words, so a user sees one consistent set of
messages whether they install from text, URL, or zip.

### 1.6 How a zip maps onto the existing model (the adapter)

No new model, no new store shape. The mapping is:

1. **Parse the zip** (pure Kotlin, JVM-testable, new function in
   `ExtensionRuntime`, e.g. `installFromZip(bytes, existingIds)` returning
   the existing `InstallResult` type): run the §1.5 checks in order,
   read `manifest.json` + the named script file (+ `icon.png` if present).
2. **Synthesize a canonical `// ==UserScript==` header from the manifest**
   and prepend it to the script source:
   `// @name`, `// @version`, `// @description` (if non-empty), one
   `// @match` line per match, `// @run-at`. The values are already
   validated single-line in §1.5, so synthesis cannot inject a second
   header line. The synthesized source is what gets persisted — this
   keeps `UserscriptParser` the single source of truth for metadata and
   means the Extensions screen's existing "Source" viewer shows the
   manifest-derived header, exactly like a pasted script.
3. **Reuse the existing install path unchanged:** the synthesized source
   goes through `installFromText(source, existingIds)` (checks 21, 26–28
   plus header parse), then `ExtensionStore.install(context, script)`
   (which re-validates size/header/id itself — that defence-in-depth
   stays). If `icon.png` was present and valid, store it at
   `extensions/icons/<id>.png` next to the script file; the v2.4 UI does
   not display it (reserved for a future version — the file is stored so
   a later UI can use it without reinstalls).
4. **Failure atomicity:** nothing is written to the store until every
   check passes. A failed zip install leaves no partial script file, no
   index entry, and no icon file (write the script file through the
   existing `ExtensionStore.install`, then the icon; if the icon write
   fails, the install still counts as done and the failure is logged —
   the icon is cosmetic, the script is the install).

What the adapter does **not** do: it does not invent a new id scheme
(`slugify(name)` as today), it does not add fields to `Userscript`, and
it does not change `ExtensionStore`'s index format. A zip-installed
script is indistinguishable in the store from a pasted one — same row,
same per-site lists, same enable switch, same delete flow.

### 1.7 Example package (shipped in the repo)

`extensions/example-dark-reader/` is a ready-made demo of the §1
format, so a user can try zip install without authoring a package
first:

- **What it is:** "Example Dark Reader" v1.0 — a content script that
  forces a dark background (`#121214`) with light text on
  `example.com` pages (`*://example.com/*`,
  `*://*.example.com/*`, `document-end`). No network, reads nothing,
  touches only the page's own DOM.
- **Files:** `extensions/example-dark-reader/manifest.json` +
  `main.js` (source), and the ready-made
  `extensions/example-dark-reader.novaext` zip built from exactly
  those two files (zip root only, no subdirectories).
- **How to install it:** Extensions screen → "Install from file" →
  pick `example-dark-reader.novaext` → review → Install. It appears
  as a normal user-installed row (enable switch ON, delete to remove).
- **Verified:** `tests/ZipInstallTest.kt` loads the shipped zip bytes
  and installs them through `NovaExtZip.installFromZip` (id
  `example-dark-reader`, matches `example.com`) — the shipped file is
  tested, not assumed valid.

---

## 2. Install / enable / disable / delete flows

All flows live in the existing `ExtensionsScreen` (same components,
same Expressive Dark theme). A `.novaext` install adds one entry point
and reuses everything else.

### 2.1 Install

Entry points (both local-only, no network):

- **Extensions screen → "Install from file".** Opens the system file
  picker (Storage Access Framework, `application/zip` + `*.novaext`).
  The user picks a file on the device; the app reads it, validates it
  (§1.5), and shows the install review dialog.
- **NOVA Files (or any file manager) → open a `.novaext` file.** The app
  declares an intent filter for the `.novaext` extension; opening the
  file routes to the same validation + review flow. (Lane B/C: intent
  filter + a cold-start path that lands on the Extensions screen with
  the pending install. If the app is in private mode when the intent
  arrives, the install is refused — see §2.4.)

**Install review dialog** (extends the existing `pendingInstall`
review; same dialog, two added lines for zip installs):

- Title: `Install "<name>"?`
- Lines: version + run-at (as today), the full `@match` scope (as
  today), the privilege warning (as today), plus for zip installs:
  `Installed from file "<filename>" · <size> KB` and, when
  `permissions` contains `"sensitive-pages"`, the extra warning from
  §1.4.
- Buttons: `Install` / `Cancel`. Nothing is stored until `Install` is
  tapped. Dismissing loses the pending install (nothing stored — safe,
  as today).

**After install:** status line `"\"<name>\" installed"`, list refreshes,
the new row appears with its enable switch ON, its match summary, and
its per-site section (empty). Installing does **not** enable the script
on any sensitive host — that is always a separate, explicit step (§3).

**Reinstall / update:** there is no silent overwrite and no auto-update
in v2.4. Installing a package whose id already exists fails with the
§1.5 check-28 string. To update, the user deletes the old version and
installs the new one. (A version bump in the manifest does not change
the id — the id comes from the name only.)

### 2.2 Enable / disable

Unchanged from today: the row's switch toggles `ExtensionStore.setEnabled`
(for built-ins, the stored override; for the YouTube skipper the switch
is the Settings master toggle). Disabling stops injection on the next
navigation — it does not unload a script already running in an open
page (same honest limit as today: injection happens per navigation).

### 2.3 Delete

Unchanged from today: user-installed scripts (including zip-installed
ones) show Delete → confirm dialog → `ExtensionStore.delete` removes
the index record and the `<id>.js` file (and the stored icon file, if
any). Built-ins can never be deleted, only disabled. Delete cannot be
undone.

### 2.4 Private mode

Unchanged from today: the Extensions screen is not routed from
`PrivateActivity`, and `ExtensionStore` is guarded — in the
`:incognito` process, reads return built-ins only and every write
returns false. A `.novaext` intent received while in private mode is
refused with: `Extensions are not available in private mode`. FBState
is likewise unavailable in private mode (§4.7).

---

## 3. Sensitive-page rule

Unchanged engine, stated here so Lane B/C applies it to the new pieces:

- A page is sensitive when its host is in the sensitive-host list
  (`ExtensionRuntime.SENSITIVE_HOST_SUFFIXES` — includes
  `facebook.com`) **or** the password-field probe finds
  `input[type=password]` (light DOM, open shadow DOM, same-origin
  iframes). The probe result is tri-state (`HAS_PASSWORD` /
  `NO_PASSWORD` / `UNKNOWN`).
- **User-installed scripts (including zip-installed): fail closed.**
  On `UNKNOWN` they are blocked exactly as on `HAS_PASSWORD`. They run
  on a sensitive page only on a host the user explicitly allowed for
  that script (`allowedSensitiveHosts`, via the Extensions screen's
  per-site "Sensitive sites" section + the existing confirmation dialog).
- **Built-in scripts (YouTube Ad Skipper, Cookie Banner Hider, FBState):
  fail open on `UNKNOWN`.** Blocked on a KNOWN password page and on
  sensitive hosts; running otherwise. An explicit per-host allow
  overrides every row (see the decision table in `ExtensionRuntime`).
- The allow-host rules are unchanged: a host must be a real host with at
  least one dot, not a bare TLD / public suffix
  (`ExtensionStore.isValidSensitiveAllowHost`); allowing a host covers
  it and its subdomains.
- The injection hook (`NovaClients.injectExtensions`) is unchanged: probe
  first, drop the injection if the WebView has navigated away, then
  `scriptsForUrl` with the probe result, document-start before
  document-end, one injection per URL per navigation
  (`InjectionDedupe`). A page that rejects injection keeps working.

---

## 4. FBState — NOVA-native Facebook session export / import

FBState is the NOVA-native equivalent of the C3C UFC Utility's FBState
feature (behaviour reference: `v2.4-work/c3c-analysis.md` — no C3C code
or assets are used). C3C is a Firefox MV3 popup using `chrome.cookies`;
NOVA Browser is an Android WebView app and **has no `chrome.cookies`
API at all**. Everything below is designed around what Android WebView
actually offers.

### 4.1 Decision: FBState is a BUILT-IN extension

FBState ships inside the app, appears as a built-in row in the
Extensions screen, and **cannot be deleted** (only disabled). Reasons:

- It cannot be expressed as a `Userscript` at all: its work is native
  (reading/writing cookies through `android.webkit.CookieManager`),
  not page JavaScript. A `.novaext` package has no way to obtain cookie
  access — there is no cookie API for user scripts in v2.4 (§1.4).
- It is first-party, reviewed code, like the skipper and the hider — so
  it gets the built-in sensitive-page rule (§3, §4.6), not the
  fail-closed user-script rule.
- It needs a native UI (its own screen under the Extensions entry) and
  native clipboard/file access; none of that lives in a content script.

Registry entry (added to `ExtensionRuntime.builtInScripts` alongside
the skipper and the hider):

| Field | Value |
|---|---|
| id | `fbstate` |
| name | `FBState` |
| version | `2.4` (tracks the app version, as the skipper does) |
| description | `Export and import your Facebook session cookies, on this device only.` |
| matches | `*://facebook.com/*`, `*://*.facebook.com/*` |
| run-at | `document-end` |
| built-in | `true`, enabled by default, cannot be deleted |

**Companion content script.** FBState ships a minimal asset,
`assets/extensions/fbstate.js`, whose entire v2.4 job is to set a
marker (`window.__novaFbstate = { version: "2.4" }`, guarded against
double injection like the cookie hider's `window.__novaCookieHider`).
It reads nothing, touches no DOM, sends nothing, and clicks nothing.
Its purpose is to give the extension a runtime presence governed by
the same gating as every other built-in — so the Extensions screen can
show it, disable it, and hold its sensitive-host allow entry — while
**all cookie work stays in native code** (§4.2). If a future version
needs page-side behaviour, it grows here; in v2.4 the marker is the
whole script.

**Disabling FBState** (the row's switch) disables the whole feature:
the marker script stops injecting **and** the FBState screen refuses
to open (`FBState is disabled — enable it in Extensions to use it`).
One master switch, no half-states.

### 4.2 How FBState reads and writes cookies (native only — never page JS)

- **Read:** `android.webkit.CookieManager.getInstance().getCookie(url)`.
  This is the only cookie source. It returns a single string,
  `name=value; name=value`, for the cookies applicable to that URL.
  **It returns no flags and no metadata**: no `domain`, no `path`, no
  `secure`, no `httpOnly`, no `expires`/`creation`/`lastAccessed`. Flag
  fidelity is therefore impossible from this source — §4.3 records
  unknowable fields as `null` instead of fabricating them (C3C
  fabricated `creation`/`lastAccessed` as "now"; NOVA does not).
- **Coverage:** one `getCookie` call cannot see host-only cookies of a
  sibling subdomain, so export queries three URLs and merges:
  `https://www.facebook.com/`, then `https://facebook.com/`, then
  `https://m.facebook.com/`. Merge rule: dedupe by cookie name, first
  occurrence wins in that order. Parsing rule: split on `"; "`, split
  each pair on the *first* `=` (values may contain `=`); a pair with no
  `=` is skipped. Values are kept verbatim — no URL-decoding, no
  re-encoding. Honest limit: a cookie value containing `"; "` is
  ambiguous in this string format and may parse incorrectly; there is
  no better source in WebView, and the doc does not pretend otherwise.
- **Write:** `CookieManager.setCookie(url, cookieString)` where the
  string carries attributes, e.g.
  `name=value; Domain=.facebook.com; Path=/; Expires=<date>; Secure`.
  Then `CookieManager.flush()` after every write/delete batch (otherwise
  the write may sit in memory and be lost if the app is killed).
  - `Secure` is honoured when the imported item says `secure: true`
    (the attribute is passed through in the cookie string).
  - `HttpOnly` **cannot be set** through `CookieManager.setCookie` —
    WebView drops the attribute. An imported cookie that was HttpOnly
    becomes JavaScript-readable after import. This is a platform limit,
    stated on the import screen (§4.5) and not hidden.
  - `Expires` is honoured when the imported item carries a valid future
    expiry; otherwise the C3C-compatible fallback of +365 days from
    import time is used (§4.4, decision 2).
- **Delete (soft logout / replace mode):** set the cookie expired per
  name — `name=; Domain=.facebook.com; Path=/; Expires=Thu, 01 Jan 1970
  00:00:00 GMT` — then `flush()`. There is no per-cookie
  `CookieManager.remove` on all API levels; the expired-cookie write is
  the supported mechanism.
- **Threading:** all `CookieManager` calls run on an IO dispatcher, never
  the main thread; results are posted back to the UI thread. The
  FBState screen never runs `evaluateJavascript` to read cookies — page
  JS must never be the cookie path (a page script could be tampered
  with; the native call cannot).
- **No login-state check:** like C3C, FBState does not verify after
  import that the cookies actually log the user in. It reloads the
  Facebook tabs and the user sees the result. The screen says so:
  `Import does not check that the session works — the Facebook tab will reload and show the result.`

### 4.3 The five open questions — decided

**Q1 — Bare array vs versioned wrapper?**
**Versioned wrapper for NOVA export; bare array accepted on import.**
Export writes:

```json
{
  "format": "nova-fbstate",
  "version": 1,
  "exportedAt": "2026-11-24T15:00:00.000Z",
  "cookies": [
    {
      "key": "c_user",
      "value": "1000...",
      "domain": ".facebook.com",
      "path": "/",
      "hostOnly": false,
      "secure": null,
      "httpOnly": null,
      "expires": null
    }
  ]
}
```

(All values above are placeholders; no real cookie values are shown
anywhere in this spec.) Reasons: C3C's bare array has no version field,
so a future format change is undetectable — NOVA export must not repeat
that. Import still accepts the bare C3C array (and C3C's base64 and
encrypted variants — §4.5), so existing C3C/UFC tooling and saved data
keep working. `exportedAt` is the export time (ISO-8601 UTC); per-cookie
`creation`/`lastAccessed` are **omitted entirely** — the source has no
such data and NOVA does not fabricate timestamps.

**Q2 — Preserve real secure / httpOnly / expiry, or mirror C3C's drop-and-force-1-year?**
**Preserve what the format carries; record what the source cannot know
as `null`; force 1-year only as a fallback.** On export, `secure`,
`httpOnly` and `expires` are `null` (unknown from `CookieManager` —
§4.2), never `false` and never a fabricated date. On import, an item's
`secure: true` is honoured, a valid `expires` is honoured, and only an
item with no usable expiry gets +365 days from import time. Reasons:
fidelity where fidelity is possible; honesty where it is not. C3C's
forced 1-year expiry is kept as the *default*, not the rule — so a
C3C export (no expiry fields) imports exactly as C3C would import it,
while a NOVA export round-trips its own expiry.

**Q3 — Encrypted export: match C3C's weak fixed-counter AES-CTR, or a stronger random-IV scheme?**
**Stronger scheme for NOVA export; C3C's scheme supported on import only.**
- NOVA encrypted export: key = PBKDF2-HMAC-SHA256(password, random
  16-byte salt, 100,000 iterations) → AES-256-GCM with a random 12-byte
  IV. Output is a versioned text envelope, one line:
  `NOVA-FBSTATE-ENC-v1:<base64(salt || iv || ciphertext)>` (salt and IV
  prepended, standard layout). The password is never stored, never
  logged, and held in memory only for the operation; minimum password
  length 8 characters.
- C3C encrypted import (hex string, AES-CTR, key = SHA-256(password),
  fixed counter `[0,…,0,1]`): still supported on import for
  compatibility with existing C3C-encrypted data. NOVA **never produces**
  it. Weakness is contained to reading old data, not writing new data.
- **Where encryption happens:** in native Kotlin code
  (`javax.crypto` — no new dependency), never in page JS. WebView has
  no WebCrypto in content scripts, and FBState has no content-script
  crypto role at all. The FBState screen collects the password in a
  native dialog and hands it to the native crypto path.

**Q4 — Canonical domain value?**
**`.facebook.com` everywhere.** Export writes `"domain": ".facebook.com"`
for every cookie; import sets `Domain=.facebook.com` for every cookie.
Reasons: it is what C3C's import already forced, it is the correct
cookie-domain semantics (covers `www`, `m`, and bare `facebook.com`),
and one value ends the export/import mismatch (C3C exported
`"facebook.com"` and imported `".facebook.com"`).

**Q5 — Wipe-then-set vs merge on import?**
**Merge by default; wipe-then-set only as an explicit, confirmed option.**
Default import sets/overwrites the imported cookies (same name +
domain + path) and leaves every other Facebook cookie untouched.
Reason: C3C's wipe-first is destructive when the pasted data is
incomplete — e.g. missing `sb`/`dbln` silently logs the device out of
its remembered session. The import dialog carries a checkbox, off by
default: `Replace all current Facebook cookies first` — when checked,
the dialog shows a warning line (`All current Facebook cookies will be
deleted before import. If the imported data is incomplete, you may be
logged out.`) and the import then deletes all `facebook.com` cookies
first, then sets the new ones. The summary after import always reports
both numbers: `Imported N Facebook cookies (M skipped — not facebook.com).`

### 4.4 Export flows

The FBState screen (native Compose, reached from the FBState row in
Extensions) mirrors C3C's popup layout: one large textarea holding the
FBState data — both the export output display and the import input —
with the action rows around it.

- **Export JSON** (default): writes the §4.3 wrapper JSON into the
  textarea, pretty-printed with tab indentation (matching C3C's
  `JSON.stringify(data, null, "\t")`). The header above the textarea
  shows the cookie count found, e.g. `23 Facebook cookies found`.
- **Export Base64:** base64 of the wrapper JSON (NOVA-native; C3C's
  base64 was of the bare array — import auto-detects both).
- **Export Encrypted:** password dialog ("Export encrypted FBState
  data" — password + confirm, minimum 8 characters) → §4.3-Q3 envelope
  into the textarea.
- Export writes into the textarea only. It does **not** auto-copy and
  does **not** auto-download — the user then uses Copy / Save to file,
  exactly as in C3C.

### 4.5 Import flows

Input sources: the textarea (typed/pasted) **and** a "Load from file"
button (system file picker, local file only — an addition over C3C,
which had no file import; loading a file fills the textarea, it does
not import directly, so the user always sees what is about to be
imported).

Auto-detection, in order:

1. Text starts with `NOVA-FBSTATE-ENC-v1:` → prompt for password
   ("Import encrypted FBState data"), decrypt with the §4.3-Q3 scheme,
   then parse the plaintext as JSON.
2. `JSON.parse` succeeds → if it is the NOVA wrapper
   (`format == "nova-fbstate"`), use its `cookies` array (reject any
   other `version` with `This data is from a newer FBState version — update NOVA Browser to import it.`); if it is a bare array, use it
   as C3C data.
3. Else base64-decode then `JSON.parse` → wrapper or bare array as in (2).
4. Else text matches `/^[0-9a-fA-F]+$/` with even length → treat as hex
   ciphertext, prompt for password, decrypt with C3C's AES-CTR scheme,
   then `JSON.parse`.
5. Else error: `Invalid FBState data.`

Validation after detection: the data must be an array (or a wrapper
whose `cookies` is an array) — anything else is `Invalid FBState data.`
Empty textarea is `Please input FBState data first.` Per-item rules:
an item needs a non-empty string `key` and a string `value` (empty
string allowed); `path` defaults to `/`; `domain`, when present, must
end with `facebook.com` — items whose domain does not are **skipped**
and counted in the summary (C3C skipped them silently; NOVA reports
the count). Items missing `key`/`value` are skipped and counted the
same way. Wrong password is `Invalid password.`

Import procedure: (a) unless "Replace all" is checked, merge (§4.3-Q5);
if it is checked, delete all current `facebook.com` cookies first;
(b) `setCookie` each accepted item (§4.2 — `secure` honoured,
`expires` honoured or +365 days, `httpOnly` accepted but **not**
applied, with the on-screen note in §4.2); (c) `flush()`; (d) reload
every open tab whose host ends with `facebook.com` (native tab reload
through the browser coordinator — **not** `chrome.tabs`, which does
not exist here); (e) show the summary
`Imported N Facebook cookies (M skipped — not facebook.com).` There is
no success toast beyond that summary line, and the textarea keeps the
pasted text (as in C3C).

Error strings (final, verbatim): `Please input FBState data first.`,
`Invalid FBState data.`, `Invalid password.`,
`This data is from a newer FBState version — update NOVA Browser to import it.`

### 4.6 Sensitive-page rule for FBState

`facebook.com` **is** in the sensitive-host list, and FBState's
companion content script (§4.1) must run there — so FBState needs the
explicit opt-in that §3 requires for any script on a sensitive host:

- FBState is a built-in (first-party, reviewed code), so it uses the
  **built-in rule**: blocked on a KNOWN password page
  (`HAS_PASSWORD`), runs otherwise; on `UNKNOWN` it runs, gated only by
  the host list. The per-host allow entry is still required — a
  sensitive host blocks even built-ins unless allowed.
- **The allow entry is created at first use, with user confirmation.**
  The first time the user opens the FBState screen (or taps Export /
  Import), if `facebook.com` is not yet in FBState's
  `allowedSensitiveHosts`, a confirmation dialog appears and the
  feature waits behind it. Exact text:

  > **Title:** `Allow FBState on Facebook?`
  >
  > **Body:** `FBState needs to run on facebook.com to export and import your Facebook session. Facebook is a sensitive site: its pages can contain your password and act as your logged-in account. FBState only reads and writes Facebook cookies through the app itself — it never sends them anywhere. Allow FBState on facebook.com (and its subdomains)?`
  >
  > **Buttons:** `Allow` / `Cancel`

  On `Allow`, the app stores the entry via
  `ExtensionStore.setSensitiveHostAllowed(context, "fbstate", "facebook.com", true)`
  and proceeds. On `Cancel`, the FBState screen stays closed (or the
  Export/Import action does nothing) — no allow entry is created, and
  the dialog is shown again next time. Removing the entry later is the
  existing per-site flow in the FBState row's expanded section (same
  "Sensitive sites" UI as every other script).
- Note what this allow entry governs: the **content script's** presence
  on facebook.com pages. The native cookie work (§4.2) does not go
  through the page and is not gated by the probe — but it is gated by
  the same confirmation: the FBState screen cannot be used until the
  user has confirmed, so there is one consent moment for the whole
  feature, not two.

### 4.7 Clipboard, file, soft logout, private mode

- **Copy to clipboard:** native `ClipboardManager` — copies whatever is
  in the textarea (export output or pasted import text — same button, no
  format awareness, as in C3C). Label: `Copy to clipboard`.
- **Paste from clipboard:** native clipboard read into the textarea.
- **Save to file:** writes the textarea text to
  `Downloads/NOVA/Browser/` (the browser's download directory, plan
  §9). Filenames by format: JSON → `fbstate-facebook.json`
  (`application/json`); Base64 → `fbstate-facebook.b64` (`text/plain`);
  Encrypted → `fbstate-facebook.enc` (`text/plain`). No auto-download
  on export.
- **Clear:** empties the textarea.
- **Soft Logout** (trash icon, tooltip
  `Delete local FBState data (logout without invalidating the session)`):
  confirm dialog (`Are you sure you want to delete local FBState data (soft logout)?`, Yes/No) → deletes all `facebook.com` cookies
  **except `sb` and `dbln`** (the two C3C keeps — they preserve the
  device's remembered-login state) → reloads Facebook tabs → success
  line `Local FBState data deleted successfully.`
- **Hard rule — export never leaves the device:** FBState performs zero
  network requests of its own. Export is copy-to-clipboard / save-to-file
  **only**, on-device only. There is no share-to-cloud, no upload, no
  sync, no "send" path. The only network effect of the whole feature is
  the normal page reload of Facebook tabs after import / soft logout.
  Clipboard content is the user's explicit copy action; the app never
  writes the clipboard on its own.
- **Private mode:** FBState is unavailable in `:incognito`. The screen
  refuses to open there, and the native cookie calls are never made
  from the private process (private-profile cookies are a separate
  store; exporting them would leak a private session). The Extensions
  screen itself is already unreachable from private mode (§2.4).

### 4.8 What FBState does NOT do

- No network calls of its own (see §4.7). No background work, no alarms,
  no auto-backup, no sync, no multi-account / multi-profile support.
- No cookie-name allowlist/blocklist on export or import (name-agnostic,
  as C3C was — it exports/imports whatever Facebook cookies exist).
- No session-cookie special-casing. No validation that the imported
  cookies actually log the user in (§4.2).
- No page-JS cookie access — cookies are never read through
  `evaluateJavascript` or `document.cookie`.
- No `httpOnly` restoration (§4.2 — platform limit, stated on screen).

---

## 5. What v2.4 does NOT do (extensions in general)

- **No network fetch of extensions.** Install is local zip only (file
  picker / Files-app open) plus the existing paste-text and URL-fetch
  paths for plain userscripts. No extension store, no gallery, no
  browsing or searching extensions online.
- **No `.crx` / `.xpi` support.** Real Chrome/Firefox extensions do not
  run on WebView; that waits for the GeckoView decision (plan §5, v3.0).
- **No background pages / service workers, no extension storage API,
  no `nova.*` JS bridge** (§1.4 — the plan §5 APIs are future work).
- **No signature verification for `.novaext`** (plan §9 mentions signing
  for v1.2; not in v2.4). Install remains a user-trust decision after
  the review dialog — stated in the review text, not hidden.
- **No auto-update** of installed extensions (§2.1 — delete + reinstall).
- **No multi-script packages** (§1.2).
- **No icon display** in v2.4 (§1.6 — icon is validated and stored,
  shown in a later version).

---

## 6. Implementation notes for Lane B / Lane C (checklist, not code)

1. `ExtensionRuntime.installFromZip(bytes: ByteArray, existingIds: Set<String>): InstallResult`
   — pure Kotlin, JVM-testable: §1.5 checks in order, manifest parse
   (`org.json`, no new dependency), header synthesis, then delegate to
   `installFromText`. Add JVM tests mirroring `ExtensionsTest` for every
   row of the §1.5 table (bad zip, traversal, missing manifest, bad
   match, duplicate id, disallowed permission, …).
2. `ExtensionStore.install` — **unchanged**. Icon file write to
   `extensions/icons/<id>.png` after a successful install.
3. `ExtensionsScreen` — add "Install from file" (SAF picker) + the two
   extra review lines (§2.1) + the FBState built-in row with an
   "Open FBState" action. Reuse `pendingInstall`, `confirmSensitiveAllow`,
   per-site sections, and status strings as they are.
4. `ExtensionRuntime.builtInScripts` — register `fbstate` (id, matches,
   asset `assets/extensions/fbstate.js`); add the marker-only asset.
5. FBState screen (new Compose screen, Expressive Dark, existing
   components): textarea + Export JSON / Base64 / Encrypted + Import +
   Copy / Save / Paste / Clear + Soft Logout + "Load from file" +
   "Replace all" checkbox + cookie-count header + the `httpOnly` limit
   note + the first-use confirmation dialog (§4.6, exact text).
6. FBState native layer (new, IO dispatcher): `CookieManager` read
   (three URLs, §4.2 merge) / write / expired-delete / `flush()`; format
   detect + parse (§4.5); wrapper/Base64/C3C-bare/C3C-hex handling;
   NOVA AES-256-GCM envelope + C3C AES-CTR import-decrypt
   (`javax.crypto`, no new dependency); tab reload via the coordinator
   for hosts ending in `facebook.com`.
7. Intent filter for `.novaext` (Files-app open) + private-mode refusal
   (§2.4). `build-apk.sh` version bump to code 8 / name 2.4 is Lane D.
8. Tests: JVM tests for the zip validator, the FBState format
   detect/parse (wrapper, bare array, base64, both encrypted forms,
   wrong password, non-Facebook domain skip, merge vs replace), and the
   manifest→header synthesis. No test may contain a real cookie value —
   placeholders only, as in this document.

---

## 7. Sources

- `v2.4-work/c3c-analysis.md` — C3C UFC Utility v2.0.1 behaviour
  reference (export/import formats, encrypted variant, soft logout).
- `NOVA-BROWSER-PLAN.md` §5 (NOVA Extensions design) and §8 (privacy:
  no network, sensitive sites default-off).
- `app/src/main/java/com/nova/browser/extensions/Userscript.kt`,
  `ExtensionRuntime.kt`; `app/src/main/java/com/nova/browser/data/ExtensionStore.kt`;
  `app/src/main/java/com/nova/browser/ui/ExtensionsScreen.kt`;
  `app/src/main/java/com/nova/browser/web/NovaClients.kt`
  (injection hook); `app/src/main/assets/userscripts/cookie-banner-hider.js`.
