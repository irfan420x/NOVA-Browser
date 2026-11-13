# NOVA Browser

A fast, private Android web browser for NOVA OS — clean by default, with a
developer mode, its own extensions system, reader mode and a pause/resume
downloader. Built with Kotlin + Jetpack Compose in the NOVA Glass Premium
style (v2.1; Nebula Dark Premium before it).

- **Package:** `com.nova.browser` · **minSdk 29** · **targetSdk 35**
- **Version:** 2.1 (versionCode 5)

## v2.1 — Glass Premium theme (style only)

- **Glass Premium retheme** — the user checked v2.0 and said the theme was
  not beautiful, then picked Glass Premium: deep gradient background
  (#0B0E1E → #12142A) with a soft violet glow at the top, frosted
  translucent surfaces (white 6–10%), 1dp hairline borders on cards and
  bars, glass top/bottom bars. Light theme is light glass (white ~70%).
  Spec: `~/workspace/nova-design/NOVA-GLASS-DESIGN.md`.
- **No behavior change** — same taps, flows, permissions and data; 812/812
  JVM tests, APK ~16.8 MB.
- **Honest limits:** no true backdrop blur (Compose has no safe built-in
  backdrop-blur primitive — RenderEffect would blur the text itself; the
  only correct paths need an external library or a WebView bitmap capture).
  Menus/dialogs/sheets use smoked glass (mostly-opaque) so text stays
  readable over bright web pages.

## v2.0 — what's in

- **Tabs** — up to 12, grid switcher, sessions restored on launch, plus:
  tab groups (create/rename/color), recently-closed tabs (restore),
  tab search in the switcher, duplicate tab, and background-tab
  suspension (idle 10+ min or memory pressure frees the WebView; the tab
  reloads on switch-back — never POST-result tabs, never a tab with a
  download running)
- **Search** — Google by default (DuckDuckGo / Bing / Brave selectable);
  the address bar loads domains directly and searches anything else.
  Search suggestions appear as you type (history/bookmarks + the active
  engine's suggest API, debounced 300 ms; off = history-only; nothing is
  sent before submit in private mode — suggestions are empty there)
- **Bookmarks & history** — SQLite storage, pinned home shortcuts,
  top sites, history search, per-item delete, clear-all
- **Downloads** — custom pause/resume downloader (Range requests, partial
  files + `.meta` sidecars in the app-private dir, published to public
  `Downloads/NOVA/Browser/` via MediaStore on completion), with
  DownloadManager as fallback; progress, open, share, pause, resume,
  cancel and delete. Private downloads never leave the app-private
  dirs and are deleted when the session closes.
- **Reader mode** — article extraction (bundled `reader.js`) into a clean
  text-only view with text-size controls (50–200%) and a light/dark
  toggle; re-extracts when the page navigates, never shows a stale article
- **Per-site settings** — JavaScript, desktop mode and ad-block for the
  current site, from the menu; JS/desktop apply live and reload once
  (debounced) when they need it
- **Clear on exit** — optional wipe of history, cookies and cache when the
  browser closes cleanly (does not run when the app is swiped away or killed)
- **Incognito** — a separate `:incognito` process with its own WebView data
  directory; tabs are never saved, history is never recorded, and closing
  the session wipes the data directory and kills the process. Switching to
  normal tabs leaves the private session alive in its own task. Private
  downloads (custom + DownloadManager) live in app-private dirs and are
  deleted on close; DevTools never records in private mode; the bookmark
  star is hidden there.
- **Built-in ad blocker (3 layers)** — network filter (bundled curated list
  in `assets/adblock.txt` + your own custom rules), cosmetic hiding of the
  empty boxes ads leave behind, and a per-site toggle in the shield sheet.
  The shield shows how many ads and trackers each page tried to load.
- **YouTube Ad Skipper** — auto-skips pre-roll/mid-roll ads (clicks Skip,
  seeks past unskippable ads, mutes during ads), now a built-in extension
  in the registry with the same Settings toggle; it never runs on login
  or banking pages
- **Extensions / userscripts** — install userscripts from URL or paste,
  enable/disable per script, per-site on/off, run-at document-start/end.
  Built-ins: YouTube Ad Skipper + Cookie Banner Hider. Scripts run with
  full page privileges (they can read passwords and act as you) — install
  only scripts you trust; they never run on a login/banking page or a
  page with a password field unless you explicitly allow that host
- **DevTools dock** (Dev Mode, off by default) — Console with JS REPL,
  Elements tap-to-inspect, Network log with Copy as cURL (tokens and
  secret-bearing bodies are redacted), Storage viewer (values masked).
  Strict-CSP sites block injection and the dock says so. USB remote
  debugging via chrome://inspect is a separate opt-in with a warning.
- **HTTP Client / mini-Postman** (Dev Mode) — method/URL/headers/body,
  history saved on-device, Copy as cURL; auth headers are stripped on
  cross-origin redirects and redacted in history
- **HTTPS-only mode** — upgrades `http` to `https`, except localhost and
  your local network; if the upgrade fails, the error screen offers
  "Load with HTTP anyway" (an explicit per-site exception)
- **Desktop mode** — per tab, per site, or by default for new tabs
- **Find in page** — with match count and next/previous
- **Text size** — 50–200%, applies to open tabs immediately
- **Privacy** — `allowBackup=false`; clear history / cookies & site data /
  cache from Settings; SSL errors always ask the user, never proceed alone
- **Links & sharing** — open `http(s)` links, receive shared text,
  `tel:`/`mailto:`/`sms:`/`intent:`/`market:` open in the right app,
  launcher shortcuts for New tab / New private tab
- **Default browser** — request the role from Settings (Android 10+)
- **Error log** — in-app, with the exact error, so bugs can be reported
- **Nebula Dark Premium** — dark-first theme, light theme kept and restyled

## Deliberately NOT in v2.0

- **Real Chrome extensions** — they cannot run on WebView. v2.0 ships its
  own userscript system instead. A GeckoView evaluation (for real
  WebExtensions like uBlock Origin) is v3.0 work.
- **Eruda injection** — the DevTools dock is native Compose + JS bridges,
  not an injected DevTools page; strict-CSP sites block injection and the
  dock says so honestly.

## Build

Gradle does not work on this server, so the build is a manual pipeline
(aapt2 + kotlinc + d8 + apksigner), exactly like the other NOVA apps:

```sh
tools/build-apk.sh
# → build/apk/nova-browser-debug.apk
```

It reuses the NOVA Files toolchain (JDK, kotlinc, SDK) and a private
snapshot of its dependency jars/AARs in `deps-snapshot/` (90 jars + 45 AAR
packages), so another agent rebuilding NOVA Files can't change the inputs
mid-build. The d8 step is serialized — never run two d8 processes at once
on this VM (each takes `-Xmx2G` and concurrent runs OOM-kill the build).

To re-fetch dependencies from scratch (needs network):

```sh
tools/resolve_deps.py   # writes tools/deps.txt
tools/prep_deps.py      # downloads jars/AARs into deps/
```

## Tests

JVM unit tests for the pure-Kotlin units — plain `main()` + `check()`,
no framework:

```sh
tests/run-tests.sh
# 812/812: AdBlockEngine 41 · UrlUtils 72 · CosmeticFilter 8 ·
# TabGroups 91 · Extensions 151 · ReaderSuggestions 192 · DevTools 257
```

## Project layout

```
app/src/main/
  AndroidManifest.xml          MainActivity + PrivateActivity (:incognito) + FileProvider
  assets/adblock.txt           bundled filter list (~120 domain rules + patterns)
  assets/reader.js             article extraction for Reader mode
  assets/youtube-skipper.js    built-in extension: YouTube Ad Skipper
  assets/userscripts/          built-in extension: Cookie Banner Hider
  java/com/nova/browser/
    MainActivity.kt            normal browsing (ComponentActivity)
    PrivateActivity.kt         private browsing (own process + task, wipe on finish)
    adblock/                   AdBlockEngine (hash-set lookup) + FilterLists
    data/                      BrowserDb (SQLite), Settings, Downloads,
                               Tab, TabGroup, TabStore, Downloader (custom
                               engine), DownloadPublish, ClearOnExit,
                               ExtensionStore, HttpHistory, ErrorLog
    devtools/                  DevToolsHub, NetworkLog, CurlBuilder,
                               ConsoleLog, ElementInspector, JsonPretty,
                               HttpClientEngine
    extensions/                Userscript, ExtensionRuntime
    reader/                    ReaderExtractor, ReaderRunner
    ui/                        BrowserState + Compose screens (Home, Tabs,
                               Bookmarks, Downloads, Settings, Error log,
                               Extensions, DevTools, HTTP Client, Reader,
                               SiteSettingsSheet, AddressSuggestions)
    web/                       NovaWebView clients, UrlUtils, CosmeticFilter,
                               BrowserHost (activity delegate), Suggestions
                               (engine + PerSiteSettings)
  res/                         Nebula theme, launcher icon, shortcuts, file paths
tools/
  build-apk.sh                 the manual build (aapt2 + kotlinc + d8 + sign)
  deps.txt                     dependency list (reused from NOVA Files)
  resolve_deps.py / prep_deps.py / dedupe_jars.py
tests/                         JVM unit tests + run-tests.sh
```

## Known v2.0 limits (honest)

- **Completed custom downloads are session-scoped in the app's list** —
  the job list lives in memory; if the app is killed, a finished custom
  download disappears from Downloads (the file stays in
  Downloads/NOVA/Browser/, visible in NOVA Files). DownloadManager
  downloads do not have this limit — their rows live in the DB.
- **YouTube rotates its player selectors** — the skipper needs occasional
  updates. Server-stitched (SABR/SSAI) ads cannot be skipped client-side.
  Shorts skipping is heuristic and untested.
- **Reader mode is text-only by design** — images, links and embeds are
  dropped; some article pages are not detected (needs ≥2 paragraphs and
  ≥200 characters of text).
- **Search suggestions: Bing is not supported** — its suggest endpoint
  could not be verified; Bing users get history/bookmark suggestions only.
- **Clear on exit does not run** when the app is swiped away or killed —
  only on a clean close.
- **Suspended tabs lose scroll position, form input and JS state** — a
  suspended tab reloads as a fresh visit. POST-result tabs are never
  suspended for exactly this reason.
- **Per-site desktop mode applies before the first load** — the stored
  UA is set when the tab's WebView is configured, so a site with
  desktop ON loads its desktop layout on first visit. (It used to take
  effect one load late; fixed in the final audit pass.)
- **blob:/data: downloads are not supported yet** — files generated
  in-page by JavaScript (blob: exports) cannot be saved; the browser
  says so honestly instead of failing with a generic error.
- **Ad list is a curated starter list** (~120 rules), not full EasyList —
  it covers the big ad/tracker networks; custom rules can extend it.
- **Popups** open only on a real user tap (`window.open` without a gesture
  is blocked); some sites that open tabs on load may behave differently.
- **Back on the private home page closes the whole private session**
  (same as Chrome incognito); use "Switch to normal tabs" to keep it.
- **`ftp://` typed in the address bar becomes a search** — the WebView
  can't load it, and the address bar only treats `http(s)`/`file`/`about`
  as addresses.
- **WebView Safe Browsing** is enabled when the device's WebView supports
  it; some builds don't.

## License

See LICENSE.
