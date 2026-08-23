# OwnerGuard v1.0.44 GitHub-Only Release Design

## Goal

Make `mrfantest2/OwnerGuard_v1.0.27_INCIDENT_FILTERS` the complete source, build, QA, artifact, signing, and Google Play production-release authority for OwnerGuard 1.0.44, using GitHub-hosted runners only.

## Source authority

- Authoritative branch: `release/1.0.44-play-pro-drive`.
- Validated starting commit: `6901a74275abdc0e53cac5f67c26b7d385f2f416`.
- The repository's existing deterministic transformation stack remains the mechanism that reconstructs the complete Android lineage through 1.0.44; no local backup or device copy is a source.
- A newer commit supersedes the pinned commit only after GitHub CI validates it.

## Release identity

- Package: `com.fantest.ownerguard`
- versionCode: `10044`
- versionName: `1.0.44`
- targetSdk: `36`
- Google Play Billing: `9.1.0`
- One-time Pro product: `ownerguard_pro_lifetime`

## Architecture

One authoritative GitHub Actions workflow owns the release lifecycle. Release-branch pushes run source/security validation, unit tests, Android lint, debug/release builds, artifact verification, and Android-emulator smoke QA. Google Play publication is separately gated by an explicit production trigger so iterative source commits cannot submit duplicate Play releases.

The workflow reconstructs the verified OwnerGuard Android lineage through 1.0.44 before every Android validation/build job. GitHub-hosted `ubuntu-latest` runners are the only execution environment. Android emulator QA uses the Android SDK/emulator available on GitHub-hosted infrastructure and verifies installation, launcher startup, process survival, and absence of OwnerGuard fatal Java crashes.

## Signing and credentials

No keystore, password, service-account JSON, or OAuth/build secret is stored in repository source. Production signing is reconstructed ephemerally from protected GitHub Environment secrets and destroyed with the runner.

Protected environment: `google-play-production`.

Required production secrets:

- `OWNERGUARD_UPLOAD_KEYSTORE_B64`
- `OWNERGUARD_KEYSTORE_PASSWORD`
- `OWNERGUARD_KEY_ALIAS`
- `OWNERGUARD_KEY_PASSWORD`
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`

The workflow validates only secret presence and never prints secret values. Missing protected values cause a clear fail-closed production job.

## Security checks

CI rejects tracked signing credentials, keystore files, obvious private-key blocks, service-account private keys, `REQUEST_INSTALL_PACKAGES` in the transformed Play manifest, legacy unknown-app readiness gates, and unexpected release identity drift. Gradle dependency reporting and Android lint are retained as evidence.

## QA and evidence

The GitHub pipeline retains:

- debug build result
- unit-test reports
- Android lint reports
- emulator logcat and smoke-test evidence
- signed release APK and AAB
- APK/AAB metadata verification
- SHA-256 checksums
- exact source commit SHA
- signing-certificate fingerprint evidence without exposing private key material
- Google Play API submission/status response

## Google Play publication

Publishing uses the repository's Android Publisher API automation and the protected service account. The production job verifies package/version/build inputs before upload, submits versionCode 10044 to `production`, records the API response, and performs a post-commit status query when the API permits it. It must report account verification, authorization, Play App Signing, policy/declaration, or service-account blockers exactly rather than bypassing them.

## Release-trigger policy

Normal pushes to `release/1.0.44-play-pro-drive` perform QA only. Production publication occurs only when a commit message contains `[play-production]` or when a manually dispatched workflow explicitly requests publishing. This prevents release duplication during repair iterations.

## Legacy dependency removal

No workflow may run on `self-hosted`, require MASTER-PC/KDT/MasterForge/DeckForge/Steam Deck/OnePlus/Samsung hardware, or call the historical `Khalil-Digital-Twin` OwnerGuard workflow. The old self-hosted probe is removed.
