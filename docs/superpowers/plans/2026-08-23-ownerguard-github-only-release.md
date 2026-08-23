# OwnerGuard v1.0.44 GitHub-Only Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make GitHub the complete source, build, QA, artifact, signing, and Google Play production pipeline for OwnerGuard 1.0.44.

**Architecture:** Preserve the deterministic repository transformation stack through 1.0.44, then centralize release validation in one GitHub-hosted workflow. Branch pushes run QA only; production publishing is explicitly gated and uses protected GitHub Environment secrets reconstructed only inside the runner.

**Tech Stack:** GitHub Actions, Ubuntu GitHub-hosted runners, Gradle/AGP, Android SDK/emulator, Python 3, Android Publisher API.

**Spec:** `docs/superpowers/specs/2026-08-23-ownerguard-github-only-release-design.md`

## Global Constraints

- Repository: `mrfantest2/OwnerGuard_v1.0.27_INCIDENT_FILTERS`.
- Release branch: `release/1.0.44-play-pro-drive`.
- Starting validated commit: `6901a74275abdc0e53cac5f67c26b7d385f2f416`.
- Package: `com.fantest.ownerguard`.
- versionCode: `10044`.
- versionName: `1.0.44`.
- targetSdk: `36`.
- Billing: `9.1.0`.
- Product ID: `ownerguard_pro_lifetime`.
- GitHub-hosted runners only; no self-hosted/device/local-PC dependency.
- Never commit credentials or print secret values.
- Production environment: `google-play-production`.

---

### Task 1: Remove repository signing credentials

**Files:**
- Delete: `signing/keystore.properties`
- Delete: `signing/ownerguard-release.jks`
- Modify: `.gitignore`

- [ ] Remove tracked signing properties and keystore from the active release lineage.
- [ ] Ignore keystores, signing properties, service-account JSON, and common private-key formats.
- [ ] Ensure production signing is reconstructed only from protected Actions secrets.

### Task 2: Add reusable release contract validation

**Files:**
- Create: `tools/release_contract_1_0_44.py`
- Create: `tools/tests/test_release_contract_1_0_44.py`

**Interfaces:**
- `validate_tree(root: pathlib.Path) -> list[str]` returns release-contract violations.
- CLI exits non-zero and prints only non-secret validation errors when violations exist.

- [ ] Test exact package/version/SDK/Billing/product requirements.
- [ ] Test Play-managed update requirements and forbidden sideload gates.
- [ ] Test Pro backup/restore and v1.0.43 Cloud/upload lineage markers.
- [ ] Test that tracked credential paths are absent from the working tree.

### Task 3: Consolidate deterministic Android release preparation

**Files:**
- Create: `tools/prepare_release_1_0_44.py`

**Interfaces:**
- CLI applies the complete existing transformation stack in canonical order and then invokes the release contract validator.

- [ ] Execute each existing lineage transformation exactly once in order.
- [ ] Fail immediately if any transformation or final contract check fails.
- [ ] Keep the underlying source transformations unchanged unless CI identifies a repository-controlled defect.

### Task 4: Replace v1.0.44 workflow with GitHub-only QA and production release

**Files:**
- Modify: `.github/workflows/ownerguard-1.0.44-play-pro-drive.yml`
- Delete: `.github/workflows/ownerguard-selfhosted-probe.yml`

- [ ] Use `ubuntu-latest` exclusively.
- [ ] Add source/version validation and credential-leak checks.
- [ ] Run Python contract tests and Gradle unit tests.
- [ ] Run Android lint.
- [ ] Run `assembleDebug`, `assembleRelease`, and `bundleRelease`.
- [ ] Run emulator install/launch/logcat smoke QA with GitHub-hosted Android tooling.
- [ ] Preserve reports/logcat as Actions artifacts.
- [ ] Gate production signing and Play upload behind `google-play-production` and explicit publish intent.
- [ ] Validate required secrets without printing them.
- [ ] Build signed APK/AAB from ephemeral secret-backed signing files.
- [ ] Verify APK/AAB metadata, certificate fingerprints, SHA-256, and exact commit SHA.
- [ ] Publish through `tools/play_publish_1_0_44.py` to production and preserve response evidence.

### Task 5: Validate and promote

- [ ] Fast-forward `release/1.0.44-play-pro-drive` to the GitHub-only pipeline commit.
- [ ] Inspect the resulting GitHub Actions checks/logs and repair repository-controlled failures iteratively.
- [ ] Only after GitHub QA passes, create a minimal `[play-production]` trigger commit on the release branch.
- [ ] Inspect production signing/build/Play results.
- [ ] Stop only for a genuine external Google Play/account/credential blocker or confirmed production submission/release evidence.
