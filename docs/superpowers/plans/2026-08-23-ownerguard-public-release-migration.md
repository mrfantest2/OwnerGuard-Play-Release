# OwnerGuard v1.0.44 Public Release Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transfer the exact sanitized OwnerGuard 1.0.44 working tree into a fresh public GitHub repository with no private Git history, then run the authoritative public GitHub-hosted QA pipeline.

**Architecture:** A one-time bootstrap workflow in the new public repository checks out the exact private source SHA with a short-lived read-only token, scans it for forbidden credentials/signing material, copies only the working tree into a fresh public release branch, verifies byte-for-byte source/destination manifests, and pushes the branch. Because GitHub suppresses recursive Actions runs from `GITHUB_TOKEN` pushes, a subsequent connector-authored provenance/CI-trigger commit starts the imported public QA workflow. Production remains separately gated.

**Tech Stack:** GitHub Actions, actions/checkout, Bash, Git, SHA-256, Gradle/Android SDK via the imported release workflow.

**Spec:** `docs/superpowers/specs/2026-08-23-ownerguard-public-release-migration-design.md`

## Global Constraints

- Destination repository: `mrfantest2/OwnerGuard-Play-Release`.
- Destination repository visibility: public.
- Source repository: `mrfantest2/OwnerGuard_v1.0.27_INCIDENT_FILTERS`.
- Exact source commit: `af2ffe1b87cfd9199452a1947dfbc21d0acf98be`.
- Original lineage anchor: `6901a74275abdc0e53cac5f67c26b7d385f2f416`.
- Release branch: `release/1.0.44-play-pro-drive`.
- Package: `com.fantest.ownerguard`.
- versionCode: `10044`.
- versionName: `1.0.44`.
- targetSdk: `36`.
- Billing: `9.1.0`.
- Product ID: `ownerguard_pro_lifetime`.
- Never import private Git history.
- Never commit signing credentials or service-account credentials.
- GitHub-hosted runners only.
- `SOURCE_REPO_TOKEN` is one-time, short-lived, source-repo-only, `Contents: Read-only`.

---

### Task 1: Bootstrap isolated public migration branch

**Files:**
- Create: `docs/superpowers/specs/2026-08-23-ownerguard-public-release-migration-design.md`
- Create: `docs/superpowers/plans/2026-08-23-ownerguard-public-release-migration.md`
- Create: `.github/workflows/bootstrap-sanitized-owner-1.0.44.yml`

**Interfaces:**
- Consumes: public repository `main` and secret `SOURCE_REPO_TOKEN`.
- Produces: a workflow that only executes when `migration-trigger.txt` changes on `migration/v1.0.44-sanitized-snapshot`.

- [ ] Commit the approved design and implementation plan on `migration/v1.0.44-sanitized-snapshot`.
- [ ] Add a bootstrap workflow with `contents: write` and no Play/signing permissions.
- [ ] Hard-pin source repository and source commit values in workflow environment variables.
- [ ] Configure the source checkout with `persist-credentials: false`.

### Task 2: Validate private source before copying

**Files:**
- Modify: `.github/workflows/bootstrap-sanitized-owner-1.0.44.yml`

**Interfaces:**
- Consumes: `_source` checkout at the exact pinned SHA.
- Produces: a source manifest in `$RUNNER_TEMP`, or fails before destination mutation.

- [ ] Assert `_source` HEAD equals `af2ffe1b87cfd9199452a1947dfbc21d0acf98be`.
- [ ] Reject `signing/keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, `*.pem`, `*.key`, and service-account credential files.
- [ ] Reject PEM private-key blocks, service-account `private_key` JSON fields, and literal Gradle signing-password assignments.
- [ ] Generate a deterministic source path/SHA-256 manifest excluding only `_source/.git`.

### Task 3: Create exact fresh-history release snapshot

**Files:**
- Modify: `.github/workflows/bootstrap-sanitized-owner-1.0.44.yml`
- Generate on release branch: `docs/releases/1.0.44-public-migration-provenance.md`
- Generate on release branch: `docs/releases/1.0.44-source-sha256.txt`

**Interfaces:**
- Consumes: validated `_source` working tree and source manifest.
- Produces: `release/1.0.44-play-pro-drive` containing the exact source working tree plus public migration provenance files.

- [ ] Create/reset local branch `release/1.0.44-play-pro-drive` from the public migration branch only.
- [ ] Remove existing public working-tree files without touching destination `.git` or the isolated `_source` checkout.
- [ ] Remove `_source/.git`, copy `_source` byte-for-byte into the repository root, then remove `_source`.
- [ ] Generate a destination manifest before provenance additions and require exact equality with the source manifest.
- [ ] Add provenance text identifying source repository, exact source SHA, lineage anchor, destination, and fresh-history guarantee.
- [ ] Copy the source manifest to `docs/releases/1.0.44-source-sha256.txt`.

### Task 4: Re-scan and push the public release branch

**Files:**
- Modify: `.github/workflows/bootstrap-sanitized-owner-1.0.44.yml`

**Interfaces:**
- Consumes: staged release snapshot.
- Produces: remote `release/1.0.44-play-pro-drive` branch.

- [ ] Run the same forbidden credential/path checks against the destination tree.
- [ ] Configure a non-secret GitHub Actions commit identity.
- [ ] Commit with message `release: import sanitized OwnerGuard 1.0.44 snapshot`.
- [ ] Push `HEAD:release/1.0.44-play-pro-drive` with the destination repository `GITHUB_TOKEN`.
- [ ] Confirm the imported `OwnerGuard 1.0.44 GitHub Release` workflow exists on the release branch.

### Task 5: Trigger and inspect public QA

**Files:**
- Create on release branch: `docs/releases/1.0.44-public-ci-trigger.md`
- Existing imported workflow: `.github/workflows/ownerguard-1.0.44-play-pro-drive.yml`

**Interfaces:**
- Consumes: fresh public release branch created by the migration workflow.
- Produces: connector-authored push and a normal public GitHub Actions QA run.

- [ ] Create `docs/releases/1.0.44-public-ci-trigger.md` through the GitHub connector, with no `[play-production]` marker.
- [ ] Verify the connector-authored release-branch push starts the imported release workflow.
- [ ] Verify jobs actually start on GitHub-hosted `ubuntu-latest` instead of failing before step one.
- [ ] Inspect Python contract tests, Gradle dependency checks, unit tests, lint, build, and artifact steps.
- [ ] Inspect Android API 36 emulator install/launch/logcat smoke QA.
- [ ] Repair only repository-controlled defects using GitHub branches/PRs and rerun until QA passes.

### Task 6: Retire migration credential and prepare production gate

**Files:**
- No source file required for credential deletion.

**Interfaces:**
- Consumes: successful public source migration and QA.
- Produces: public repository that no longer needs private-source access.

- [ ] Delete the repository Actions secret `SOURCE_REPO_TOKEN` and revoke/expire its fine-grained token.
- [ ] Confirm no workflow in the public release branch requires private repository access.
- [ ] Configure `google-play-production` protected secrets only when production QA is ready.
- [ ] Do not create a `[play-production]` commit until QA and emulator QA are green.
