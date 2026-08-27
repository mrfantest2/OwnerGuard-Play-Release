# OwnerGuard 1.0.44 Compact UI + Incident Vault Export Design

## Goal

Finalize the OwnerGuard 1.0.44 user experience for Play Store readiness while preserving the existing dark navy + cyan OwnerGuard identity and all existing security behavior. The redesign must reduce unnecessary vertical scrolling, improve hierarchy and tap efficiency, and make the Incident Vault scalable for large evidence histories.

## Global constraints

- Work only on `release/1.0.44-play-pro-drive`; do not modify or merge `main`.
- Preserve package `com.fantest.ownerguard`, versionCode `10044`, versionName `1.0.44`, targetSdk 36, Billing 9.1.0, and product `ownerguard_pro_lifetime`.
- Preserve local-only core operation: no `fantest.win`, no mandatory hosted registration, no hosted-cloud dependency for normal OwnerGuard use.
- Preserve the existing six-digit local PIN, owner-face enrollment, credential monitoring, encrypted vault, local protection, Pro entitlement, encrypted Drive/cloud-folder backup, and Google Play managed update model.
- Preserve `FLAG_SECURE` for non-debug/release builds.
- Do not weaken release-contract, lint, security, emulator, signing, or production gates.
- Google Play production remains gated until the redesigned source/build QA and Android 16 emulator validation succeed and the final device test is accepted.

## Visual direction

Keep the current OwnerGuard visual language:

- dark navy application background;
- cyan primary actions;
- compact rounded navy surfaces;
- white primary text and muted blue-gray supporting text;
- security/status colors used sparingly for state, not decoration;
- existing drawer/header navigation remains recognizable.

The mockup approved in chat is the target density and hierarchy. This is not a brand replacement. It is a production implementation of the same design language with substantially less wasted vertical space.

## Shared density rules

- Header height stays approximately 56–60dp.
- Standard list/action rows target 48–56dp height.
- Primary full-width actions target 52–56dp rather than oversized 70–90dp controls.
- Related information is grouped into compact cards/list sections instead of one large card per value.
- Explanatory copy uses a small information card only when it materially changes user behavior.
- One visually dominant primary CTA per screen; secondary operations use compact rows or secondary buttons.
- Minimum touch target remains 48dp.

## Dashboard

Replace the vertically stacked dashboard with a denser overview:

1. Compact armed/status card at the top with:
   - armed/disarmed state;
   - credential monitor state;
   - vault readiness;
   - incident count.
2. One small informational strip for local protection / backup state.
3. Security status presented as a 2-column grid of compact cards:
   - owner face samples;
   - encrypted incidents;
   - evidence location;
   - backup/sync or current relevant local status.
4. One primary action: `Open incident vault`.
5. One compact secondary action: `Run capture test`.
6. `Protection controls` and `Local setup` become compact navigation rows under `Device controls`.

## Protection

- Show `Arm protection` and `Disarm protection` side-by-side where screen width permits.
- Convert each protection option to a compact settings row with checkbox, title, and short subtitle.
- Preserve all existing options and semantics.
- Keep the Android foreground-service explanation in one compact information card at the bottom.
- Avoid repeated large cards for each control.

## Local Setup

Convert the current sequence of oversized buttons into grouped compact rows.

### Vault & device

Rows:
- Permissions & battery — `Review required access`;
- PIN / password monitoring — `Pattern, PIN & password checks`;
- Owner-face enrollment — `Replace or re-enroll` / `Start enrollment`;
- Selfie recognition test — `Verify owner-face matching`;
- Clear owner-face data — `Remove all enrollments`;
- Vault encryption test — `Run self-test`.

### App lock

Rows:
- Lock after leaving — current timeout as subtitle;
- Lock now — `Secure OwnerGuard`.

### App updates

- Compact version/status row;
- one cyan `Check Google Play updates` action.

### OwnerGuard Pro

- Compact status card/row;
- one cyan `Unlock Pro backup` action.

No hosted account controls or legacy cloud account UI may reappear.

## Incident Vault

The Incident Vault must not become an unbounded vertical feed.

### Filtering

Primary date chips:
- `All`
- `Today`
- `This week`
- `Custom`

`Custom` opens a From/To date picker. Date filtering is applied before pagination.

Existing incident-type and owner-confidence filtering may remain available through a secondary filter control so the primary UI stays compact.

### Pagination

- Default page size: 24 incidents.
- UI displays bounded page navigation: previous, page indicator/numbers, next.
- Count copy shows the current slice, e.g. `1–24 of 57 incidents`.
- Changing filters or sort resets to page 1.
- Selection persists across page navigation while the same filter set remains active.

### Incident row density

Each incident row contains:
- selection checkbox in selection mode;
- compact thumbnail;
- incident type / reason;
- timestamp;
- photo/video summary;
- owner confidence/failed-attempt indicators where available;
- secure/open affordance.

Rows remain tappable to open event detail when not in selection mode.

### Multi-select

A `Select` action activates selection mode.

Selection mode supports:
- individual selection;
- Select page;
- Select all filtered results;
- Clear selection;
- Cancel selection mode;
- persistent selected count across pages.

### ZIP evidence export

Two export paths:

1. `Export ZIP` for explicitly selected incidents.
2. `Export this range` for all incidents matching the active date/filter range.

Export requirements:

- export decrypted copies only into the generated ZIP; do not alter encrypted originals;
- organize files by incident/date;
- include photos, video when present, and useful event metadata;
- include `evidence_manifest.csv` with at least timestamp, incident identifier, reason/type, filenames/media counts, failed credential count, owner similarity when available, and location when available;
- deterministic filename format such as `OwnerGuard_IncidentVault_2026-08-24_to_2026-08-27.zip` or `OwnerGuard_IncidentVault_Selected_2026-08-27.zip`;
- perform ZIP construction off the UI thread;
- show progress/status and notify completion/failure;
- share using Android's secure content-sharing mechanism rather than exposing arbitrary filesystem paths.

The ZIP is an owner-requested export. It must never replace or weaken the encrypted vault as the source of truth.

## Implementation architecture

The repository reconstructs canonical 1.0.44 through deterministic transformation scripts. The UI/export redesign must therefore be represented as a final deterministic transformation in the release stack, not as an ad-hoc generated artifact.

Prefer focused responsibilities:

- `VaultExportManager.java`: export selection/range model, decrypt-copy ZIP construction, manifest generation, and secure share handoff.
- `VaultActivity.java`: filtering, pagination, selection state, compact rendering, and invoking export.
- `MainActivity.java` / existing final UI transform: compact Dashboard, Protection, and Local Setup presentation without changing security/business behavior.
- final transformation script under `tools/` wires the canonical 1.0.44 source to these UI and vault-export invariants.
- `release_contract_1_0_44.py` and tests pin the user-visible and architectural invariants.

## Verification

Required before Play readiness:

1. Release-contract unit tests pass.
2. Deterministic reconstruction passes from a clean checkout.
3. Dependency validation passes.
4. Android unit tests pass.
5. Android lint passes with no disabled checks/baselines added to hide errors.
6. Debug APK and unsigned release APK/AAB build successfully.
7. Android 16 emulator smoke QA passes.
8. Compiled APK inspection confirms no `fantest.win` / forced registration regressions and confirms the new Vault UI/export strings are present.
9. Physical-device acceptance confirms the compact UI and at least one ZIP export path.
10. Only after acceptance is Google Play production publication authorized.
