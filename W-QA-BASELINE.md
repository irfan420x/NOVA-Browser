# QA — Baseline (v2.1 tree as found, 2026-11-13 ~09:06–09:20 SGT)

QA workstream for the NOVA Browser v2.2 security hardening round.
Read-only review: no source file was edited by QA. This file records the
regression reference every later suite run is judged against.

## How the baseline was taken

- Tree at task start was NOT pristine v2.1: the five fix workers were
  already landing files live while the baseline ran (new files dated
  2026-11-13 01:07–01:10 UTC: `web/HttpsEnforcer.kt`,
  `web/IntentValidator.kt`, `web/PermissionQueue.kt`,
  `tests/HttpsEnforcerTest.kt`, `tests/PermissionQueueTest.kt`,
  `tests/ClearOnExitTest.kt`, `tests/run-tests-f03f06.sh`,
  `tools/deps-checksums.txt`, `tools/verify-deps.sh`; modified:
  `extensions/ExtensionRuntime.kt`, `data/ClearOnExit.kt`,
  `tests/ExtensionsTest.kt`). `tests/run-tests.sh` itself is still the
  v2.1 script (2026-11-12 17:06) and does NOT compile any of the new
  units, so the baseline suite below is the v2.1 suite plus whatever
  F05 test edits had landed in `ExtensionsTest.kt` by compile time.
- All runs under `flock /tmp/nova-browser-build.lock`, per the brief.

## Baseline JVM suite — `tests/run-tests.sh` — 812/812 PASSED

Run 2026-11-13 ~09:06 SGT, exit 0, `ALL JVM TESTS PASSED`:

| Suite | Passed |
|---|---|
| AdBlockEngineTest | 41 |
| UrlUtilsTest | 72 |
| CosmeticFilterTest | 8 |
| TabGroupsTest | 91 |
| ExtensionsTest | 151 |
| ReaderSuggestionsTest | 192 |
| DevToolsTest | 257 |
| **Total** | **812** |

This matches the brief's v2.1 reference (812/812). Any later full-suite
run below 812 total, or with any failure, is a regression.

## Baseline full build — FAILED (pre-existing breakage, not caused by QA)

`tools/build-apk.sh` under flock, 2026-11-13 ~09:07–09:11 SGT.
Steps 1–3 (aapt2 compile, aapt2 link, javac R.java) passed. Step 4
(kotlinc) failed with exactly one error:

```
app/src/main/java/com/nova/browser/web/NovaClients.kt:399:99: error:
argument type mismatch: actual type is 'kotlin.Boolean', but
'com.nova.browser.extensions.ExtensionRuntime.PasswordProbeResult' was expected.
    ExtensionRuntime.scriptsForUrl(scripts, url, state.settings.youtubeAdSkipper, hasPasswordField)
```

Cause: the F05 worker's `ExtensionRuntime` rewrite (tri-state
`PasswordProbeResult`, default UNKNOWN) landed in the tree WITHOUT its
`NovaClients.injectExtensions` hook update. The hook still computes a
Boolean (`hasPasswordField = result?.trim() == "true"`) and passes it
where the tri-state is now required. No signed APK could be produced
from this tree; `build/apk/app-unsigned.apk` present afterwards is only
the aapt2-link artifact (1,016,141 bytes, unsigned), NOT a deliverable.
`apksigner verify` was not runnable on it (no valid APK; also `java`
was not on PATH in that follow-up shell — QA's own invocation issue,
not a build finding).

Consequence for the baseline: **there is no rebuildable v2.1 baseline
APK from this tree**. The last known-good v2.1 APK remains the released
one (versionCode 5, 16,790,495 bytes, md5
e298d85addba7da7754892675126e5ab, per the v2.1 delivery record). The
tree MUST compile again before any v2.2 verdict can be SHIP — the F05
hook fix is the blocker, and it belongs to the F05 workstream (or the
coordinator's merge), not to QA (read-only).

## Baseline hook-point map (for tracing worker hooks)

- F01 HTTPS-only: `NovaClients.shouldOverrideUrlLoading` upgrades only
  main-frame navigations; `shouldInterceptRequest` does no HTTPS
  enforcement (ad-block only, GET sub-resources); `mixedContentMode`
  is `COMPATIBILITY_MODE`. `Settings.httpsOnly` default true,
  `isHttpsException` per-site suffix rule, `UrlUtils.isLocalHost`
  exempts localhost/LAN.
- F02 intent://: `NovaClients.shouldOverrideUrlLoading` "intent"
  branch → `parseIntentUrl` (raw `Intent.parseUri`, no validation) →
  `host.openExternal`. No gesture check, no component/selector check.
- F03 permissions: `BrowserHost.onPermissionRequest` — single
  `pendingPermissionRequest` (second request overwrites the first),
  auto-grants resources needing no Android permission
  (`request.grant(request.resources)`), `handlePermissionResult`
  grants non-Android resources via `else -> null`.
- F05 userscripts: `NovaClients.injectExtensions` — probe is the old
  single `querySelector('input[type=password]')`, fail-open
  (`result == "true"`, null → false), Boolean passed to `scriptsForUrl`.
- F06 clear-on-exit: `data/ClearOnExit.kt` already contains the
  callback-gated `WipeCoordinator` rework (VERIFIED/PARTIAL/FAILED/
  UNVERIFIED/NOT_RUN); `MainActivity.onDestroy` wiring to be confirmed
  against the F03/F06 hook spec.
- F04 docs: `tools/deps-checksums.txt` + `tools/verify-deps.sh`
  landed 01:08 UTC; `README.md` / `NOVA-BROWSER-PLAN.md` still at
  2026-11-12 23:21 (no F04 doc edits yet at baseline time).
