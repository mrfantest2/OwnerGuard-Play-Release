# OwnerGuard v1.0.44 Sanitized Public Release Migration Design

## Goal

Make `mrfantest2/OwnerGuard-Play-Release` the clean public GitHub source/build/QA/release authority for OwnerGuard 1.0.44 without importing the private repository's Git history or any historical signing credentials.

## Source provenance

- Private source repository: `mrfantest2/OwnerGuard_v1.0.27_INCIDENT_FILTERS`.
- Exact sanitized source commit: `af2ffe1b87cfd9199452a1947dfbc21d0acf98be`.
- Original validated lineage anchor: `6901a74275abdc0e53cac5f67c26b7d385f2f416`.
- Public destination repository: `mrfantest2/OwnerGuard-Play-Release`.
- Public release branch: `release/1.0.44-play-pro-drive`.

The migration copies only the working tree of the exact source commit. It does not fork, import, fetch, mirror, or push the private repository's Git object history into the public repository.

## Release identity

The migrated release contract remains unchanged:

- Package: `com.fantest.ownerguard`
- versionCode: `10044`
- versionName: `1.0.44`
- targetSdk: `36`
- Google Play Billing: `9.1.0`
- One-time/non-consumable Pro product: `ownerguard_pro_lifetime`

## One-time private-source access

A short-lived fine-grained GitHub personal access token is stored only as the public repository Actions secret `SOURCE_REPO_TOKEN`.

The token must have repository access only to `mrfantest2/OwnerGuard_v1.0.27_INCIDENT_FILTERS` and `Contents: Read-only`. It must not have write, administration, secrets, Actions, packages, or organization permissions.

The bootstrap workflow uses this token only for `actions/checkout` of the exact source SHA with `persist-credentials: false`. The token is never printed, copied into the public working tree, persisted in Git configuration, or used for destination pushes. Destination writes use the destination repository's own `GITHUB_TOKEN` with `contents: write`.

After successful migration, `SOURCE_REPO_TOKEN` must be deleted from the public repository and the short-lived token should be revoked or allowed to expire.

## Migration algorithm

1. Run only from `migration/v1.0.44-sanitized-snapshot` when `migration-trigger.txt` is pushed.
2. Checkout the public destination branch with its normal `GITHUB_TOKEN`.
3. Checkout private source commit `af2ffe1b87cfd9199452a1947dfbc21d0acf98be` into an isolated `_source` directory using `SOURCE_REPO_TOKEN` and `persist-credentials: false`.
4. Assert the checked-out source HEAD equals the pinned SHA.
5. Reject forbidden credential/signing files and private-key/service-account material before any copy occurs.
6. Generate a path + SHA-256 manifest of the source working tree, excluding only source Git metadata.
7. Create/reset local destination branch `release/1.0.44-play-pro-drive` from the migration branch, remove destination working-tree files, remove source Git metadata, and copy the source working tree byte-for-byte.
8. Generate a destination manifest before adding migration provenance and require an exact diff match with the source manifest.
9. Add public migration provenance and the source SHA-256 manifest.
10. Perform a second staged-tree credential/path scan.
11. Commit the fresh public snapshot and push only the new public release branch.
12. After the release branch exists, make one connector-authored non-production provenance/CI-trigger commit on that branch. This push is intentionally outside the migration workflow's `GITHUB_TOKEN`, so the imported release workflow starts normally.

## Fresh-history guarantee

The public release branch may inherit only commits created inside `OwnerGuard-Play-Release` (README, migration specification/plan/bootstrap). No commit SHA from the private repository is used as a parent and no `.git` directory from the private checkout is copied.

The source commit SHA is stored only as provenance text.

## Security gates

Migration fails closed if any source or staged destination contains:

- `signing/keystore.properties`
- `*.jks`, `*.keystore`, `*.p12`, `*.pem`, or `*.key`
- service-account JSON filenames
- PEM private-key blocks
- Google service-account `private_key` JSON fields
- literal Gradle `storePassword` or `keyPassword` assignments

The scan intentionally permits workflow references such as `${OWNERGUARD_KEYSTORE_PASSWORD}` because those are variable references rather than literal credentials.

## Public CI handoff

The imported source already contains the authoritative `OwnerGuard 1.0.44 GitHub Release` workflow. GitHub suppresses recursive workflow execution for pushes made with a workflow's own `GITHUB_TOKEN`, so the migration push only creates the fresh release branch. A subsequent connector-authored provenance/CI-trigger commit on `release/1.0.44-play-pro-drive` starts public GitHub-hosted QA on `ubuntu-latest`:

- release-contract validation
- dependency verification
- Python/unit tests
- Android lint
- debug/release/AAB build
- Android API 36 emulator install/launch/logcat smoke QA

Neither migration commit nor the connector-authored CI-trigger commit contains `[play-production]`, so no production Play submission occurs.

## Production gating

Only after public QA and emulator QA pass will the protected `google-play-production` environment be configured/used for production signing and Google Play publication. Signing material and the Play service-account credential remain GitHub protected secrets and are reconstructed only ephemerally on GitHub-hosted runners.

## Success criteria

Migration is complete only when:

1. the public release branch exists with fresh public-only history;
2. source/destination manifests match exactly before provenance files are added;
3. no forbidden signing/credential material is tracked;
4. the connector-authored release-branch push starts public GitHub-hosted QA and emulator jobs;
5. QA passes, or exposes a repository-controlled defect that can be repaired in GitHub;
6. the private source token is no longer required and is revoked/deleted after successful import.
