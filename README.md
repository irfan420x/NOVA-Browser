# NOVA Browser

A fast, private Android web browser for NOVA OS — clean by default, with a
developer mode coming in v1.1. Built with Kotlin + Jetpack Compose in the
NOVA Nebula Dark Premium style.

- **Package:** `com.nova.browser` · **minSdk 29** · **targetSdk 35**
- **Version:** 1.0 (versionCode 1)

## v1.0 — what's in

- **Tabs** — up to 12, grid switcher, sessions restored on launch
- **Search** — Google by default (DuckDuckGo / Bing / Brave selectable);
  the address bar loads domains directly and searches anything else
- **Bookmarks & history** — SQLite storage, pinned home shortcuts,
  top sites, history search, per-item delete, clear-all
- **Downloads** — system DownloadManager into `Downloads/NOVA/Browser/`,
  with progress, open, share, cancel and delete (pause/resume is v1.1 work:
  DownloadManager has no public pause API)
- **Incognito** — a separate `:incognito` process with its own WebView data
  directory; tabs are never saved, history is never recorded, and closing
  the session wipes the data directory and kills the process. Switching to
  normal tabs leaves the private session alive in its own task.
- **Built-in ad blocker (3 layers)** — network filter (bundled curated list
  in `assets/adblock.txt` + your own custom rules), cosmetic hiding of the
  empty boxes ads leave behind, and a per-site toggle in the shield sheet.
  The shield shows how many ads and trackers each page tried to load.
- **HTTPS-only mode** — upgrades `http` to `https`, except localhost and
  your local network; if the upgrade fails, the error screen offers
  "Load with HTTP anyway" (an explicit per-site exception)
- **Desktop mode** — per tab, or by default for new tabs
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

## Deliberately NOT in v1.0

- **Extensions** (v1.1) — real Chrome extensions can't run on WebView;
  v1.0 ships the built-in ad blocker + its own filter system instead.
- **DevTools** (v1.1) — Console, Elements, Network log, Storage viewer.
- **HTTP Client / mini-Postman** (v1.2) — local HTML preview, localhost/LAN
  tools, SEO inspector.
- **Video/PDF viewer, custom download engine with Range resume** — later.

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

JVM unit tests for the pure-Kotlin units (ad-block engine, URL helpers,
cosmetic filter) — plain `main()` + `check()`, no framework:

```sh
tests/run-tests.sh
# AdBlockEngineTest: 38 passed · UrlUtilsTest: 49 passed · CosmeticFilterTest: 8 passed
```

## Project layout

```
app/src/main/
  AndroidManifest.xml          MainActivity + PrivateActivity (:incognito) + FileProvider
  assets/adblock.txt           bundled filter list (~120 domain rules + patterns)
  java/com/nova/browser/
    MainActivity.kt            normal browsing (ComponentActivity)
    PrivateActivity.kt         private browsing (own process + task, wipe on finish)
    adblock/                   AdBlockEngine (hash-set lookup) + FilterLists
    data/                      BrowserDb (SQLite), Settings, Downloads, Tab, ErrorLog
    ui/                        BrowserState + Compose screens (Home, Tabs,
                               Bookmarks, Downloads, Settings, Error log)
    web/                       NovaWebView clients, UrlUtils, CosmeticFilter,
                               BrowserHost (activity delegate)
  res/                         Nebula theme, launcher icon, shortcuts, file paths
tools/
  build-apk.sh                 the manual build (aapt2 + kotlinc + d8 + sign)
  deps.txt                     dependency list (reused from NOVA Files)
  resolve_deps.py / prep_deps.py / dedupe_jars.py
tests/                         JVM unit tests + run-tests.sh
```

## Known v1.0 limits (honest)

- **No pause/resume on downloads** — DownloadManager has no public API
  for it; the list shows the system pause state and offers cancel +
  re-download. A custom Range-resume engine is v1.1 work.
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
