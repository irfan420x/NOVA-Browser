# NOVA Browser v2.0 — UI/UX Polish Checklist (W7, Phase 1)

Baseline: v1.2 (versionCode 3). Screens reviewed: Theme.kt, BrowserScreen.kt,
SettingsScreen.kt, DownloadsScreen.kt, TabSwitcherScreen.kt, BookmarksScreen.kt,
HomeScreen.kt, ErrorLogScreen.kt. No edits made in Phase 1.

## 1. Checklist (from NOVA-NEBULA-DESIGN.md + W7 brief)

### Colors — tokens, exact hex (verified in Theme.kt: all match)
- Background #0A0C1C · Surface #14172B · Elevated #1B1F38 · Outline white 8% (0x14FFFFFF)
- Primary violet #8B5CF6 · Gradient #7C3AED → #3B82F6 · Secondary cyan #22D3EE
- Tertiary pink #EC4899 (sparing) · Text #EDEFF7 · Muted #9AA3C7
- Error #F87171 · Success #34D399
- Rules: use tokens / MaterialTheme.colorScheme, never hardcoded hex; errors in error
  color (never primary); light theme = soft surfaces (#F4F5FB bg, #FFFFFF cards, same violet);
  no white-on-white / invisible text (muted text only on correct surface).

### Radii
- Cards 20dp + 1dp surface-outline border, no hard shadow · Buttons 14dp
  (primary buttons gradient, text buttons primary violet) · Chips/pills 12dp
  (selected = gradient/primary fill, unselected = surface elevated) · FAB 16dp gradient.
- Selected tab: gradient pill or gradient underline; unselected tabs in secondary text.

### Typography
- Existing M3 type scale; titles on-surface full weight; subtitles/metadata muted;
  numbers that matter primary text, medium weight.

### Spacing
- List rows: surface cards with 12dp spacing between them (or clean rows with 1dp
  dividers — one choice per screen, consistent). Screen padding 16dp standard.

### Empty states — icon + title + one-line help + action
- Centered icon in a gradient-tinted circle, title, one line of help, one action.

### Loading states
- Progress shown while data loads; no dead screens; no flash of the empty state
  before the load finishes.

### Error states
- Message + retry/action; error color; failures surfaced, never silent.

### TalkBack
- contentDescription on every interactive control (IconButton, clickable card/row,
  slider, radio group); decorative icons null; selected state announced;
  status/toast messages announced (live region / semantics); no unlabeled control.

### Font scaling (large fontScale)
- No clipped/overlapping text: no fixed heights that clip wrapped text; maxLines +
  ellipsis where needed; content scrollable where it can grow.

### Rotation
- No lost state (screen position, typed text, unsaved drafts, find bar, dialogs);
  no clipped layout. State via rememberSaveable / savedInstanceState / persistence.

### Narrow screens (360dp)
- No horizontal overflow; rows shrink with weight(1f) + ellipsis or wrap;
  buttons fit; touch targets stay 48dp.

---

## 2. Baseline gaps (v1.2) — file + what is wrong

### Shared / Theme.kt (HOOK SPECS for coordinator — W7 may not edit)
- **T1** `NebulaEmptyState` (Theme.kt) takes icon + single message only — no title
  param, no action slot. Every empty state therefore cannot meet the
  icon+title+help+action pattern. Hook: add title + actionLabel + onAction
  (new overload), keep old signature working.
- **T2** `NovaShapes.small = 12dp`, and M3 Button defaults to shapes.small → ALL
  Buttons/OutlinedButtons render at 12dp radius; spec says buttons 14dp.
  Hook: set shapes.small = 14dp in NovaShapes (check: OutlinedTextFields set
  explicit 14dp already; chips set explicit 12dp; Card uses medium 20dp — so the
  blast radius is essentially buttons only), or give each button NovaButtonShape.
- **T3** `SettingsSection` headers use primary violet + labelLarge Bold; spec says
  titles use on-surface at full weight. Consistent across screens — recommend
  KEEP as established pattern; coordinator to confirm (not a bug either way).

### BrowserScreen.kt
- **B1** Status toast: hardcoded `Color(0xFF1B1F38)` bg + `Color.White` text instead
  of the NebulaElevated token; and no semantics/liveRegion — TalkBack never
  announces "Bookmarked" / error toasts.
- **B2** `TabCountBadge` IconButton has NO contentDescription — TalkBack reads only
  the bare number ("3") with no "tabs" context. Needs e.g. "Tabs, 3 open".
- **B3** `ErrorOverlay` is NOT vertically scrollable — on small-height landscape /
  large fontScale, title + message + Reload + HTTP-anyway + warning + Go home can
  clip/overflow. Needs verticalScroll.
- **B4** Shield badge count is a sibling Box outside the IconButton — TalkBack may
  announce the bare count without context. Minor; merge semantics into the button.
- **B5** Address TextField (editing mode) has placeholder only, no label. Minor.

### SettingsScreen.kt
- **S1** Text-size Slider has no semantics label — TalkBack announces a bare slider
  value; the "Text size" label sits in a separate Column, not associated.
- **S2** RadioButton rows: no label association between RadioButton and adjacent
  Text (no merged semantics). Minor.
- **S3** `customRules` + `rulesExpanded` are plain `remember`, not
  `rememberSaveable` — rotating while editing custom rules discards the draft.
  (Same class: `confirm`, `status`.)
- **S6** `SettingsSwitch` rows: Switch has no contentDescription; Row not merged
  semantics — title/subtitle/switch announce as separate items. Minor.

### DownloadsScreen.kt
- **D1** No loading state: `items` starts as emptyList() → "No downloads yet"
  flashes on every entry while the DB/DownloadManager query runs.
- **D2** Error feedback uses PRIMARY color: "Couldn't open/share/delete …" shown
  in violet — errors must use error color.
- **D3** Row spacing 8dp vs spec 12dp (`Arrangement.spacedBy(8.dp)`).
- **D4** Empty state has no title and no action (blocked by T1).
- **D5** `DownloadRow` at large fontScale / 360dp: filename + up to 3 IconButtons +
  Cancel TextButton in one Row — filename has weight(1f) + ellipsis so it shrinks,
  but tight; verify on device.
- **D6** Delete has no confirmation (behavior note, not polish scope — other NOVA
  apps confirm destructive deletes).

### TabSwitcherScreen.kt
- **TS1** `TabCard` fixed height 128dp: at large fontScale, header row + 2-line
  title + URL line can clip inside the fixed height. Use minHeight, not fixed.
- **TS2** `TabCard` clickable has no contentDescription/semantics — TalkBack reads
  inner texts but nothing identifies it as a tab or its selected state. Needs
  semantics { contentDescription = "Tab: <title>" } + selected state.
- **TS3** Empty state "No tabs open." — no title, no help, no action (should offer
  "New tab"). Blocked by T1.
- **TS4** Grid Fixed(2) at 360dp → ~158dp cards; fine at default font, compounds
  TS1 at large fontScale.
- **TS6** Private-session Card uses 1dp primary-50% border (spec: card borders use
  surface outline) — deliberate accent; recommend KEEP.

### BookmarksScreen.kt
- **K1** No loading state: bookmarks/history start empty → empty state flashes on
  entry while DB queries run (same as D1).
- **K2** Row spacing 8dp vs spec 12dp (both lists).
- **K3** Empty states: bookmarks message has help but no title/action; history
  "No history yet." has no help, no action. Blocked by T1.
- **K5** Delete bookmark/history-entry: no confirmation, no error feedback if the
  DB op fails (silent). Same class as D2.
- **K6** `TabRow` selected indicator is a solid primary underline; spec says
  gradient pill or gradient underline. Minor deviation.

### HomeScreen.kt
- **H1** `HomeAction` / `ShortcutChip` clickable Columns: icons null (fine) but no
  merged semantics — icon and label announce separately. Minor.
- **H2** Shortcut grid (4 cols) at 360dp + large fontScale truncates titles heavily
  (ellipsis). Acceptable; verify on device.
- **H4** Private banner Card violet-50% border — deliberate accent; KEEP (same as TS6).

### ErrorLogScreen.kt
- **E1** Empty state is plain `Text("No errors recorded.")` — no icon, does not use
  `NebulaEmptyState`; inconsistent with every other screen.
- **E2** Row spacing 8dp vs spec 12dp.
- **E3** "Clear" (TopAppBar action) is destructive with no confirmation (behavior
  note — Settings' clear actions do confirm).

### Cross-cutting: rotation
- **R1** `MainActivity.onCreate` creates a fresh `BrowserState` with no
  savedInstanceState restore; `screen`, `menuExpanded`, `shieldOpen`,
  `addressEditing`, `bookmarksTab`, `findBarForTabId` are plain mutableStateOf.
  Rotating from Settings/Downloads/Bookmarks/Tabs/Error Log returns the user to
  the browser screen; open menu/shield/find bar closes; typed address text and
  home/bookmarks search queries are lost. Tabs themselves survive via
  saveTabs() on pause. Fix needs shared files (MainActivity/BrowserState) —
  HOOK SPEC: persist `screen` (+ `bookmarksTab`) across config change and use
  rememberSaveable for typed text/drafts in screens.
- **R2** Zero `rememberSaveable` usages in the whole app (grep confirmed). Every
  screen's local UI state (queries, drafts, dialog flags) is rotation-volatile.
  Per-screen instances: S3, BrowserScreen `addressText`, HomeScreen `query`,
  BookmarksScreen `query`, DownloadsScreen `refreshKey`.

### Cross-cutting: font scale / narrow screen
- **F1** Only hard clip risks found: TS1 (TabCard 128dp) and B3 (ErrorOverlay
  non-scrollable). Everything else uses weight(1f) + ellipsis or scrollable
  containers (LazyColumn / verticalScroll). Bottom bar uses fixed 48dp icon
  targets — fine at large fonts.
- **N1** No horizontal overflow found by static inspection at 360dp. Tightest
  spots to verify on device (360dp + fontScale 1.3): FindBar, DownloadRow,
  TabSwitcher top button pair, Settings text-size row (label + slider both
  weight(1f) → slider ~160dp, usable).
- **N2** `TabCountBadge` border radius 6dp — not a spec token; Chrome-style badge,
  intentional, KEEP.

### Colors audit — passed
- Theme.kt tokens match the spec hex-for-hex; light theme matches spec soft
  surfaces. All screens use MaterialTheme.colorScheme.* or Nebula* tokens except
  B1 (toast literal). No other hardcoded hex found.

### What already meets the standard
- Theme tokens exact; cards default 20dp via shapes.medium; chips explicit 12dp;
  gradient used for shortcut circles / empty-state tint; browser loading progress
  bar; ErrorOverlay message + Reload + Go home; SSL dialog; TalkBack labels on
  most IconButtons (Back, Menu, Bookmark, Reload, Home, Close find, prev/next
  match, Open/Share/Delete, Pin, Delete bookmark); Settings scrollable;
  Downloads polls with progress; BackHandler coverage on sub-screens.

## 3. Phase 2 status
- Standing by for coordinator assignment of MERGED screens, one at a time.
  Editable only: ui/TabSwitcherScreen.kt, ui/ExtensionsScreen.kt,
  ui/DevToolsScreen.kt, ui/HttpClientScreen.kt, ui/ReaderScreen.kt,
  ui/SiteSettingsSheet.kt, ui/AddressSuggestions.kt.
- Hook specs needing coordinator/shared-file decisions: T1 (empty-state helper),
  T2 (button radius 14dp), T3 (section-header color — recommend keep), R1 (rotation
  state survival), B1 (toast token + live region — in shared BrowserScreen.kt).
- No edits made; version untouched (v1.2 / code 3); nothing pushed.
