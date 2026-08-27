# OwnerGuard 1.0.44 Compact UI + Vault Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Productionize the approved compact OwnerGuard UI concept and add bounded Incident Vault pagination, date filters, persistent multi-select, and secure ZIP evidence export without changing the app's security model.

**Architecture:** Keep the canonical 1.0.44 reconstruction model. Add one final deterministic transform that compacts Dashboard/Protection/Local Setup, rewrites the Vault presentation/state model, and creates a focused `VaultExportManager` helper for ZIP generation. Pin all critical UI/export behavior in release-contract tests so later historical transforms cannot silently reintroduce oversized or legacy behavior.

**Tech Stack:** Android Java, Android framework Views, encrypted OwnerGuard vault primitives (`VaultCrypto`), `FileProvider`/secure sharing, Java ZIP/CSV APIs, Python deterministic release transforms/tests, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-08-27-ownerguard-compact-ui-vault-export-design.md`

## Global Constraints

- Work only on `release/1.0.44-play-pro-drive`; do not modify or merge `main`.
- Preserve `com.fantest.ownerguard`, versionCode 10044, versionName 1.0.44, targetSdk 36, Billing 9.1.0, and `ownerguard_pro_lifetime`.
- Preserve local-only operation and forbid `fantest.win` / forced hosted registration.
- Preserve six-digit PIN, owner-face enrollment, credential monitoring, encrypted vault, Pro entitlement, Drive/cloud-folder backup, Play-managed updates, and release `FLAG_SECURE`.
- Do not weaken lint, security, emulator, signing, or production gates.
- Do not trigger Google Play production during implementation/QA.

---

### Task 1: Pin UI and Vault Export Contract

**Files:**
- Modify: `tools/tests/test_release_contract_1_0_44.py`
- Modify: `tools/release_contract_1_0_44.py`

**Interfaces:**
- Consumes: canonical reconstructed source tree.
- Produces: static invariants for compact UI, pagination, selection, date filtering, and ZIP export.

- [ ] **Step 1: Write failing tests**

Add tests requiring final reconstructed source to contain these invariants:

```python
assert 'DEFAULT_PAGE_SIZE = 24' in vault
assert 'Select all filtered' in vault
assert 'Export this range' in vault
assert 'Today' in vault and 'This week' in vault and 'Custom' in vault
assert 'VaultExportManager.exportIncidents' in vault
assert 'evidence_manifest.csv' in export_manager
assert 'ZipOutputStream' in export_manager
assert 'VaultCrypto.decryptBytes' in export_manager
assert 'Open incident vault' in main
assert 'Permissions & battery' in main
```

Also require `tools/fix_compact_ui_vault_export.py` to exist in `prepare_release_1_0_44.py` after implementation.

- [ ] **Step 2: Run the release-contract unit tests and verify RED**

Run in GitHub QA through the push-triggered workflow.
Expected: contract test failure mentioning the missing compact UI/vault export transform or invariants.

- [ ] **Step 3: Extend the static release contract**

Add required tokens for:

```text
VaultActivity.java: DEFAULT_PAGE_SIZE = 24, Export this range, Select all filtered
VaultExportManager.java: evidence_manifest.csv, ZipOutputStream, VaultCrypto.decryptBytes
MainActivity.java: Open incident vault, Permissions & battery, PIN / password monitoring
```

Do not remove existing local-only/security invariants.

- [ ] **Step 4: Commit**

Commit message:

```text
test: pin compact UI and vault export contract
```

---

### Task 2: Compact Dashboard, Protection, and Local Setup

**Files:**
- Create: `tools/fix_compact_ui_vault_export.py`
- Modify through transform: `app/src/main/java/com/fantest/ownerguard/MainActivity.java`
- Modify through transform if needed: `app/src/main/java/com/fantest/ownerguard/OwnerGuardDrawerShell.java`

**Interfaces:**
- Consumes: reconstructed 1.0.44 MainActivity after all earlier local-only/security transforms.
- Produces: compact Dashboard, Protection, Local Setup builders using existing actions/business logic.

- [ ] **Step 1: Add deterministic transform skeleton**

The transform must locate the final UI methods by method signatures/known stable anchors and replace only presentation code. It must fail closed with `SystemExit` when an expected anchor is absent.

- [ ] **Step 2: Implement compact Dashboard**

Required structure:

```text
status summary card
small local-protection info strip
2-column security status grid
Open incident vault primary CTA
Run capture test secondary CTA
Protection controls compact row
Local setup compact row
```

Reuse existing status/actions; do not duplicate security state.

- [ ] **Step 3: Implement compact Protection page**

Render Arm/Disarm as compact paired actions and each option as a 48–56dp settings row with checkbox/title/subtitle. Preserve all current preference keys and action callbacks.

- [ ] **Step 4: Implement compact Local Setup page**

Create grouped rows:

```text
Vault & device
  Permissions & battery
  PIN / password monitoring
  Owner-face enrollment
  Selfie recognition test
  Clear owner-face data
  Vault encryption test
App lock
  Lock after leaving
  Lock now
App updates
OwnerGuard Pro
```

No hosted-account/legacy cloud UI may be rendered.

- [ ] **Step 5: Run reconstruction/compile tests**

Expected: canonical preparation succeeds and Java compiles.

- [ ] **Step 6: Commit**

Commit message:

```text
feat: compact OwnerGuard dashboard and setup UI
```

---

### Task 3: Add Vault Pagination, Date Filters, and Persistent Selection

**Files:**
- Modify through transform: `app/src/main/java/com/fantest/ownerguard/VaultActivity.java`
- Modify: `tools/fix_compact_ui_vault_export.py`

**Interfaces:**
- Produces fields/methods used by export:
  - `private static final int DEFAULT_PAGE_SIZE = 24`
  - active filtered list
  - `Set<String>` selected incident IDs/paths
  - custom date bounds
  - selection-mode state

- [ ] **Step 1: Implement date-filter model**

Use explicit modes:

```java
private static final int DATE_ALL = 0;
private static final int DATE_TODAY = 1;
private static final int DATE_WEEK = 2;
private static final int DATE_CUSTOM = 3;
```

Custom mode stores start/end day boundaries and opens a From/To date picker.

- [ ] **Step 2: Apply filtering before pagination**

Algorithm:

```java
List<EventRow> filtered = applyFilters(loadAllEvents());
int total = filtered.size();
int pageCount = Math.max(1, (total + DEFAULT_PAGE_SIZE - 1) / DEFAULT_PAGE_SIZE);
currentPage = Math.max(0, Math.min(currentPage, pageCount - 1));
int from = currentPage * DEFAULT_PAGE_SIZE;
int to = Math.min(total, from + DEFAULT_PAGE_SIZE);
List<EventRow> pageRows = filtered.subList(from, to);
```

Changing sort/date/type/owner filters resets `currentPage = 0`.

- [ ] **Step 3: Implement compact pagination UI**

Provide previous/next controls, current page indicator/numbers, and count text such as:

```text
1–24 of 57 incidents
```

- [ ] **Step 4: Implement persistent selection state**

Use stable incident directory absolute path or canonical relative ID as the selection key. Selection persists across page navigation within the current filtered result set.

Support:

```text
Select
Select page
Select all filtered
Clear selection
Cancel
```

- [ ] **Step 5: Preserve normal tap behavior**

When not selecting, tapping a row opens the event detail. In selection mode, tapping toggles the checkbox instead.

- [ ] **Step 6: Run unit/compile/lint validation**

Expected: no new lint errors and no behavior regressions in existing vault detail flow.

- [ ] **Step 7: Commit**

Commit message:

```text
feat: paginate and multi-select incident vault
```

---

### Task 4: Add Secure ZIP Evidence Export

**Files:**
- Create through transform: `app/src/main/java/com/fantest/ownerguard/VaultExportManager.java`
- Modify through transform: `app/src/main/java/com/fantest/ownerguard/VaultActivity.java`
- Modify if required: `app/src/main/AndroidManifest.xml`
- Modify if required: `app/src/main/res/xml/file_paths.xml`
- Modify: `tools/fix_compact_ui_vault_export.py`

**Interfaces:**
- `VaultExportManager.exportIncidents(Activity activity, List<File> incidentDirs, String exportLabel, Callback callback)`
- `Callback.onProgress(int completed, int total)`
- `Callback.onComplete(File zipFile)`
- `Callback.onError(String message)`

- [ ] **Step 1: Implement export manager**

Use a single background executor and `ZipOutputStream`. For every selected incident:

1. decrypt each `.ogv` media/metadata file with `VaultCrypto.decryptBytes`;
2. write decrypted copies directly to ZIP entries without replacing vault originals;
3. use normalized safe entry names under date/incident folders;
4. collect one CSV row per incident.

- [ ] **Step 2: Generate `evidence_manifest.csv`**

Header:

```csv
timestamp,incident_id,reason,incident_type,photo_count,has_video,failed_credential_attempts,owner_similarity,location
```

CSV quoting must escape commas, quotes, and line breaks.

- [ ] **Step 3: Add selected export action**

Selection toolbar button:

```text
Export ZIP
```

Pass only selected incidents.

- [ ] **Step 4: Add range export action**

Normal filter UI action:

```text
Export this range
```

Pass all currently filtered incidents before pagination.

- [ ] **Step 5: Add progress and secure share handoff**

Show non-blocking progress text/dialog. On completion, share using the app's existing secure content mechanism or a correctly-scoped FileProvider. Never expose `file://` URIs.

- [ ] **Step 6: Add cleanup policy**

Generated ZIPs live only in cache/export storage and can be overwritten/cleaned later; encrypted vault originals remain untouched.

- [ ] **Step 7: Run compile/lint/build verification**

Expected: debug and unsigned release variants build and lint remains green.

- [ ] **Step 8: Commit**

Commit message:

```text
feat: export incident evidence as secure ZIP
```

---

### Task 5: Wire Reconstruction and Final Contract

**Files:**
- Modify: `tools/prepare_release_1_0_44.py`
- Modify: `tools/release_contract_1_0_44.py`
- Modify: `tools/tests/test_release_contract_1_0_44.py`

**Interfaces:**
- Produces deterministic final source with the compact UI/export transform applied last.

- [ ] **Step 1: Append transform to release stack**

Add:

```python
"fix_compact_ui_vault_export.py",
```

after the existing final compatibility/security transforms.

- [ ] **Step 2: Run contract tests**

Expected: all release-contract tests PASS.

- [ ] **Step 3: Run deterministic preparation twice conceptually/idempotently**

The transform must detect already-applied invariants and not duplicate code/UI.

- [ ] **Step 4: Commit**

Commit message:

```text
build: wire compact UI and vault export into 1.0.44
```

---

### Task 6: GitHub QA, Emulator, and APK/AAB Acceptance

**Files:**
- No source changes unless a verified QA failure requires a targeted fix.

**Interfaces:**
- Consumes final release branch commit.
- Produces verified QA artifact and Play-ready unsigned release AAB pending signing/production authorization.

- [ ] **Step 1: Verify source/security/build QA**

Require all workflow steps:

```text
release-contract tests
canonical reconstruction
dependency validation
Android unit tests
Android lint
debug + unsigned release APK/AAB build
evidence upload
```

- [ ] **Step 2: Verify Android 16 emulator smoke QA**

Require emulator job conclusion `success`.

- [ ] **Step 3: Download QA artifact**

Extract the exact debug APK from the successful run.

- [ ] **Step 4: Inspect compiled APK**

Confirm:

```text
fantest.win absent
forced registration strings absent
compact UI strings present
Today / This week / Custom present
Select all filtered present
Export ZIP present
Export this range present
evidence_manifest.csv present
```

- [ ] **Step 5: Provide APK for physical acceptance**

Test Dashboard density, Protection rows, Local Setup density, pagination, multi-select, selected ZIP export, and range ZIP export.

- [ ] **Step 6: Hold production gate**

Do not add `[play-production]` and do not dispatch `publish=true` until physical acceptance is explicitly confirmed.
