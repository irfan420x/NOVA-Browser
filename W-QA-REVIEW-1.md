# QA — Review round 1 (v2.2 security hardening, in-tree state ~09:45 SGT 2026-11-13)

Read-only review. No source file was edited by QA. No delivery reports
from the coordinator had arrived at review time — verdicts below are
from direct in-tree inspection (source + tests + hooks + builds run by
QA), not from worker self-reports. The tree was being edited live by
other workers throughout; every verdict names the file state it was
judged against. A follow-up round is needed after the F03 hook merge
and the test-script consolidation (see §7).

## 0. Suite + build evidence (the numbers everything else hangs on)

- Baseline `tests/run-tests.sh` (v2.1 script): **812/812 passed**
  (41 + 72 + 8 + 91 + 151 + 192 + 257). See `W-QA-BASELINE.md`.
- Baseline full build: **FAILED** — kotlinc error
  `NovaClients.kt:399` (Boolean passed where `PasswordProbeResult`
  was expected). The F05 runtime had landed without its hook. No
  baseline APK could be rebuilt from the tree; last known-good APK
  remains released v2.1 (16,790,495 bytes, md5
  `e298d85addba7da7754892675126e5ab`).
- QA combined full suite (all 11 test mains, one kotlinc invocation,
  `build/qa-full-classes`, run ~09:30 SGT): **1106/1106, ALL PASSED**
  — AdBlock 41, UrlUtils 72, Cosmetic 8, TabGroups 91, Extensions
  218, ReaderSuggestions 192, DevTools 257, PermissionQueue 71,
  ClearOnExit 44, HttpsEnforcer 59, IntentValidator 53.
  Old-suite counts are identical to baseline (no regression); the
  +294 are the new workstream tests (Extensions grew 151 → 218 with
  the F05 cases).
- Transient failure, now resolved (do NOT re-report as a bug): the
  `run-tests-f03f06.sh` run at ~09:20 showed ExtensionsTest 214
  passed / 4 failed (all YouTube-skipper cases). Cause was a mid-edit
  test file — the F05 worker fixed the skipper fixture
  (`builtIn = true`) at 01:14:59 UTC, after that run's kotlinc had
  compiled the older fixture. Re-run of the current file:
  **218/218 passed**. Lesson for the coordinator: don't judge a suite
  run while workers are still editing the files it compiles.
- Full build, current tree: **SUCCEEDS**. kotlinc passed with only
  pre-existing deprecation warnings (`startActivityForResult`,
  `databaseEnabled`). APK `build/apk/nova-browser-debug.apk`:
  **16,810,975 bytes, md5 `482a8271a9e36a44736a19776c8f1fe3`**,
  apksigner verify OK (debug cert), badging
  `versionCode='5' versionName='2.1'` (unchanged — correct, no
  version bump yet). Dex check: `HttpsEnforcer`, `IntentValidator`,
  `PermissionQueue`, `PasswordProbeResult`, `WipeCoordinator`,
  `ClearOnExit`, `NovaWebViewClient` all present in `classes3.dex`.
  Provenance note: this APK was produced by another agent's retry
  build (PID 19470, 01:39–01:45 UTC); QA verified the artifact
  independently (md5 / apksigner / badging / dex). An earlier F03
  build attempt died at `build-apk.sh: line 131` (unexpected EOF)
  because `tools/build-apk.sh` itself was edited at 01:38:51 UTC
  while that build was executing it — a torn read, not a code
  defect; the script is syntactically valid now (`bash -n` OK).
- No new dependency: all new code is pure Kotlin + Android SDK.
  `tools/deps.txt` changed only in its header comment. No theme
  change (`ui/Theme.kt` untouched). No behavior change beyond the
  six findings (traced in §6).

## 1. F01 — HTTPS-only enforcement — SHIP

- Unit `web/HttpsEnforcer.kt`: correct. Decision table matches the
  doc: http sub-resource → UPGRADE (307, method-preserving);
  localhost/LAN (`UrlUtils.isLocalHost`) → ALLOW; per-site exception
  (suffix rule, dot-boundary safe) → ALLOW, and wins over a failed
  upgrade; hostless `http://` → BLOCK; `upgradeFailedHosts` → BLOCK;
  https-only off → ALLOW; non-http → ALLOW. `decideForRequest`
  derives redirect-ness from `tab.url` (never `view.url`).
  Tests: `HttpsEnforcerTest` **59/59**, read in full — covers
  upgrade shape, all LAN forms, exception suffix traps
  (`badexample.com`, `example.com.evil.com`), downgrade-redirect
  detection incl. null/blank page, main-frame skip, off-mode.
- Hook `NovaClients.shouldInterceptRequest` (merged 01:17:44 UTC):
  correct anchor, correct order — F01 runs BEFORE the ad-block
  early returns (ad-block-off no longer bypasses enforcement) and
  after the POST-marking; covers all methods (POST upgrades via
  307); uses `tab.url`; main-frame redirect upgrades set
  `tab.wasUpgradedFromHttp` via `view.post` (sub-resources don't);
  BLOCK logs + empty response; `WebResourceResponse` 307
  constructor args in the right order. `upgradeFailedHosts` is not
  passed (default empty) — matches the documented state that
  nothing populates it yet.
- Notes (not blockers): (a) upgrades are logged via
  `finish(true, "HTTPS_UPGRADE")`, i.e. DevTools Network marks them
  `blocked=true` — cosmetic mislabel, consider a distinct flag;
  (b) `upgradeFailedHosts` is unpopulated, so BLOCK effectively
  fires only for hostless URLs — deliberate per the doc;
  (c) WebView actually following a 307 returned from the
  interceptor, and `mixedContentMode` staying
  `COMPATIBILITY_MODE`, both need **phone verification** (cannot be
  proven on JVM); (d) `ws://` is invisible to the interceptor —
  documented honest limit.

## 2. F02 — Intent URL validation — SHIP

- Unit `web/IntentValidator.kt`: correct and thorough. Rejects
  explicit `component`, `SEL` selector, non-VIEW actions, non-http(s)
  data schemes, any extra except `S.browser_fallback_url`, nested
  intents, bad percent-encoding, malformed/unterminated intents;
  fallback must be http(s) with a host; package-without-fallback and
  no-gesture → `NeedsConfirmation` (never silent). `+` in the
  fallback is preserved (no URLDecoder space bug).
  Tests: `IntentValidatorTest` **53/53**, read in full — covers all
  of the above plus tel:/javascript:/custom schemes, duplicate
  fields, unknown fields, fallback-only intents.
- Hook `NovaClients.shouldOverrideUrlLoading` "intent" branch
  (merged 01:35:24 UTC): validate-first; `Allow` launches via
  `Intent.parseUri` on the ORIGINAL url (never a rebuilt Intent);
  try-launch-then-fallback (not `resolveActivity` — correct for
  Android 11+ package visibility); fallback-only loads the fallback
  in-browser; `NeedsConfirmation` opens `host.confirmExternalIntent`
  and `onAllow` launches from the already-validated decision WITHOUT
  re-validating (correct — re-validation would loop to
  NeedsConfirmation for package-without-fallback); `Reject` logs +
  drops the navigation.
- Dialog `BrowserHost.confirmExternalIntent` (added to `WebHost`
  interface + `BrowserHost`): single live dialog (earlier one
  dismissed), names package or URL + fallback, Allow runs `onAllow`
  once, Deny/cancel do nothing (navigation already consumed),
  activity-finishing failure answers "no" instead of crashing.
- Minor notes: `Reject` logs the full intent URL into the on-device
  ErrorLog (same pattern as v2.1's `parseIntentUrl` — not a
  regression, but the URL can carry payload data); `market:` /
  `tel:` / `mailto:` / `sms:` branches are unchanged and outside
  this finding's scope.

## 3. F05 — Userscript fail-closed (tri-state probe) — SHIP

- Unit `extensions/ExtensionRuntime.kt`: correct. `PasswordProbeResult`
  tri-state; `fromProbeResult` maps null / "error" / "null" /
  garbage / "" → UNKNOWN (never NO_PASSWORD), accepts bare and
  JSON-quoted "true"/"false" with whitespace; UNKNOWN blocks
  USER scripts (`isSensitivePageForUserScripts`) but not built-ins
  (`isSensitivePage`); explicit per-host allow overrides; default
  probe arg is UNKNOWN (a caller that forgets the probe fails
  closed). Probe JS walks light DOM + open shadow roots + same-origin
  iframes per-frame try/catch, returns "true"/"false"/"error".
  Residuals are documented honestly (closed shadow roots,
  cross-origin iframes, post-injection dynamic fields).
  Tests: `ExtensionsTest` **218/218**, read in full — decision
  table for all three probe states × both script kinds, mapping
  cases, sensitive-host list, probe-JS structure checks.
- Hook `NovaClients.injectExtensions` (merged 01:35:24 UTC):
  `fromProbeResult(result)` maps the callback; UNKNOWN logs
  distinctly ("user scripts blocked (fail closed)"); tri-state
  passed to `scriptsForUrl`; old fail-open Boolean + comment gone;
  B-6 SPA-navigation guard retained. This also resolves the
  baseline build failure (§0).
- Regression trace: skipper + cookie hider (built-ins) still run on
  normal pages under UNKNOWN (test-pinned); both still blocked on
  HAS_PASSWORD pages and sensitive hosts; user scripts blocked on
  UNKNOWN unless explicitly allowed for that host. YouTube ad
  skipping on normal watch pages is unchanged.

## 4. F03 — Permission queue — NO-SHIP (hook not merged)

- Unit `web/PermissionQueue.kt`: correct and thorough. FIFO queue,
  ids (never position) for matching Android results; allowlist is
  exactly VIDEO_CAPTURE + AUDIO_CAPTURE; PROTECTED_MEDIA_ID and
  unknown/future resources are DENIED at enqueue (never granted);
  partial Android grants grant only the surviving resources;
  `resolveUserDecision` grants only `awaitingUserResources` (denied
  parts can never be smuggled into a grant); decided entries can
  never be re-granted; `deny()`/`pruneDecided()` for teardown.
  Tests: `PermissionQueueTest` **71/71**, read in full — allowlist,
  mixed requests, partial grants, user decline, two concurrent
  requests decided independently, FIFO drain, edge cases.
- **Hook NOT merged**: `BrowserHost.onPermissionRequest` is still
  the v2.1 code (single `pendingPermissionRequest`, auto-grants
  resources needing no Android permission via
  `request.grant(request.resources)`, `handlePermissionResult`
  grants non-Android resources via `else -> null`). The finding —
  auto-grant + dropped concurrent requests — is still live in the
  merged tree. The unit alone does not fix it.
- Fixes required: wire `PermissionQueue` into `BrowserHost`
  (`onPermissionRequest` → enqueue + deny-immediately for DENIED
  entries; `handlePermissionResult` → `applyAndroidResult` by id +
  user-confirmation dialog showing the origin;
  `nextNeedingAndroid` head-first processing; deny pending entries
  on navigation/teardown so nothing hangs); then rebuild + rerun
  the suite. The queue has no timeout — the hook spec must say who
  denies a request whose Android flow never completes.
- Also: the unit tests currently have **no runner** — see §7.

## 5. F06 — Clear-on-exit (async cookies) — SHIP-WITH-FIXES

- Unit `data/ClearOnExit.kt` (`WipeCoordinator`): correct.
  Order history → cookies (callback gates flush → storage →
  cache); flush runs only after the cookie callback; a callback
  that never fires produces NO result (no invented success); every
  throwing step counts as "not cleared" and the rest still run;
  `WipeResult`/`WipeStatus` truth table (VERIFIED only when every
  part is true). Tests: `ClearOnExitTest` **44/44**, read in full —
  all-ok / cookie-false / never-calls-back / per-step throws /
  all-fail / truth table / UNVERIFIED-vs-FAILED distinction.
- Wiring, half done: `MainActivity.onDestroy` already calls
  `ClearOnExit.wipe` with the WebView-cache lambda (unchanged from
  v2.1 wiring) — the wipe half is live.
- **Fixes required**: `ClearOnExit.checkInterruptedWipe` is
  **never called anywhere** (grep, excluding its own file: zero
  callers), and `lastWipeStatus` / `lastWipeTimeMs` are **never
  read by any UI**. The F06 point — making the unverified/killed
  case VISIBLE instead of claiming success — is dead code as
  merged. Add the `MainActivity.onCreate` call the doc specifies
  ("call once at startup … before any wipe can run") and surface
  the last status in Settings (or record the deliberate decision
  not to surface it). Until then the kill case is detected by
  nobody.
- Also: same no-runner gap as F03 — see §7.

## 6. F04 — Docs + reproducible build — SHIP

- `tools/deps-checksums.txt`: 1516 pinned sha256 files.
  Verified by QA: `tools/verify-deps.sh` → **"deps-snapshot OK:
  1516 pinned files verified"**, exit 0, no uncovered-file
  warnings.
- `README.md` (updated 01:35 UTC): the audit P1 is fixed — the
  `resolve_deps.py` command now uses `$(grep -v '^#'
  tools/deps.txt)` with the explanation that a bare `$(cat …)`
  passes comment words as artifacts; documents `verify-deps.sh`,
  the re-fetch checksum mismatch caveat, and re-pinning steps.
  `tools/deps.txt` header carries the same fix.
- `NOVA-BROWSER-PLAN.md` (updated 01:35 UTC): v2.2 row added
  (F01–F07 scope, honest limits, and it records the F05 call-site
  inconsistency "since fixed at the call site"). Small inconsistency
  to correct: the row says **F01–F07** but the round has six
  findings/workstreams (F01, F02, F03, F04, F05, F06) — confirm
  whether an F07 exists or fix the range.
- README also honestly documents the moving-target tree and that a
  clean-tree build from the v2.1 tag was the verification anchor.
  No version bump in `tools/build-apk.sh` (still 5 / 2.1) — correct
  at this stage; the coordinator owns the v2.2 bump.

## 7. Open items for the coordinator (blocking a final SHIP)

1. **F03 hook merge** (§4) — the only NO-SHIP. Nothing else can
   ship as "F03 fixed" until `BrowserHost` uses the queue.
2. **F06 startup call + status display** (§5) — one `onCreate`
   line + a Settings line (or an explicit deferral).
3. **Test-runner consolidation**: `tests/run-tests-f03f06.sh` was
   DELETED by another agent at 01:26 UTC, and the updated
   `tests/run-tests.sh` (01:18:38) includes HttpsEnforcer +
   IntentValidator but NOT PermissionQueue / ClearOnExit. Those two
   suites (71 + 44 checks, both green in QA's combined run) now
   have no project runner — fold them into `run-tests.sh` (needs
   `android.jar` on the kotlinc classpath, as the deleted script
   did) so one command runs all 1106 checks.
4. **Phone verification** (cannot be done on this server):
   WebView following the interceptor 307 (incl. POST sub-resources);
   F02 confirmation dialog UX on a real intent link; F03
   camera/mic prompt flow with two concurrent requests; F05 probe
   on a real login page (shadow-DOM / iframe cases); clear-on-exit
   status after a clean exit vs a swipe-kill.
5. **Plan range**: confirm or correct "F01–F07" in the PLAN v2.2 row.

## 8. Regression spot-checks (v2.1 flows, by code trace)

Search / address bar (`loadUrl` upgrade path, `resolveInput`),
tabs, tab suspension, downloads, Settings, SSL dialog, Reader,
DevTools record paths, incognito script gating — no file touched
except `NovaClients` (F01/F02/F05 hooks, all traced above) and
`BrowserHost` (F02 dialog only). Ad-block: F01 runs before it;
upgraded requests don't touch `blockedAds`/`blockedTrackers`
counters. Extensions: built-in/user split preserved per §3.
No regressions found in code or in suite counts (old suites
identical to the 812 baseline).
