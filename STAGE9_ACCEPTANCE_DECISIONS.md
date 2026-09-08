# Stage 9 Acceptance Decisions

Status: CLOSED for the internal-company development scope recorded below. This
is not a public-release or complete pre-deployment qualification claim.

## Scope decision — internal company development

- On 2026-09-07 the owner decided that SOTAware Construct will remain an
  internal company application and will not be publicly released in the
  current planning horizon.
- The current Stage 9 closure boundary is the debug installable
  `com.sotaware.construct`, the checked-in privacy/authentication safeguards,
  the Android-signed debug APK, and the recorded internal Workspace
  sign-in/Drive-grant/restart/sign-out evidence. The local JVM, build, lint,
  connected-device, and independent-review evidence below remains the
  technical basis for that scoped closure.
- Public launch, external OAuth audience/verification, public
  privacy/homepage/terms pages, Play App Signing, and a signed public release
  are outside this internal-company scope. None is claimed as complete here.
- A stable company signing key and release-certificate OAuth registration are
  deferred prerequisites for durable employee APK/private distribution and
  updates. They are not blockers for this internal development closure.
- Live-provider D2/D8/D9/D11/D13 and provider/network cases remain
  pre-deployment qualification follow-ups. Synthetic tests and instrumentation
  prove the code and harness boundaries recorded below; they do not silently
  become live-provider pass claims.

## Privacy and backup

- Android Auto Backup is disabled with `android:allowBackup="false"`.
- Legacy backup rules and Android 12+ data-extraction rules exclude all app
  data domains as defense in depth, including device-transfer domains.
- Stage 10 must independently verify that backup or device transfer cannot
  restore stale document, synchronization, or authentication state.

## Production identity and signing

- The permanent production application ID is `com.sotaware.construct`.
- Version code is `1`; version name is `1.0.0`.
- Production/upload signing material must remain outside this repository and
  be supplied only through untracked user Gradle properties or environment
  variables. It is deferred until durable employee APK/private distribution
  or updates are planned; debug signing is sufficient for the current internal
  development boundary.
- A signed release build and installation are not required for this scoped
  Stage 9 closure. They remain required for the eventual employee-distribution
  or public-release qualification, including the broader Stage 10 gate.

## Google identity and Drive authorization

- Authentication uses Credential Manager / Sign in with Google; Drive access
  is a separate AuthorizationClient flow.
- The only requested Drive scope is `https://www.googleapis.com/auth/drive.file`.
  Full Drive and `drive.appdata` are not permitted without a newly documented,
  accepted architectural need.
- The established Stage 4 SyncCoordinator, DriveGateway, snapshot, identity,
  and conflict boundaries remain authoritative and must not be bypassed.
- The current internal debug path uses the `com.sotaware.construct` package
  and its debug certificate. A future release/private-distribution path must
  register the corresponding release certificate as well. Credential Manager
  requires a web client ID; no OAuth client secret, private key, or keystore
  password belongs in source control or chat.

## Deferred pre-deployment qualification

- A stable company release/upload signing credential, a signed release
  artifact, and release-certificate OAuth registration are deferred until
  durable employee APK/private distribution or updates are planned.
- External Google Cloud/Auth Platform configuration, including external
  audience/verification, public consent/branding pages, and synthetic test
  users, is deferred until pre-deployment or public-release qualification.
- A Google Play Services-capable physical test device was available and
  authorized during the September 4 continuation (TB336FU, Android 16/API 36).
  The owner later supplied a non-secret Web client ID through the user-level
  Gradle properties and completed internal Workspace sign-in and Drive-grant
  checks on the real debug install, including restart, sign-out, and chooser
  cancellation. A configured synthetic test account, externally verified Cloud
  registrations, a release certificate, and the live provider cases remain
  pre-deployment follow-ups for the complete end-to-end matrix.

## Latest qualification state — 2026-09-07 (internal scope)

- The current debug candidate contains the supplied Web client ID through an
  untracked user Gradle property; no client secret or private credential is in
  the repository or evidence.
- Real-device evidence covers the internal Workspace path: sign-in and Drive
  grant, force-stop/restart restoration, local sign-out, and cancellation of a
  later explicit chooser attempt. This is the live evidence required by the
  current internal scope. It does not qualify external synthetic-account
  consent, denial/revocation, two-account switching, process recreation while
  consent is pending, full Drive upload/download, provider/network behavior, or
  a signed release artifact; those remain pre-deployment follow-ups.
- The latest focused Stage 9 JVM (49 tests), full JVM (480 total; 477
  executed, 3 existing Windows symlink capability skips), debug and
  Android-test assembly, lint, and complete connected suite (33/33) pass with
  the results recorded below.
- The independent read-only review initially found that fixture mismatch. The
  targeted delta review confirmed the repair as `PASS WITH FOLLOW-UPS`; D13
  process recreation and the external OAuth/revocation/account-switching,
  transfer, provider/network, and signed-release rows remain pre-deployment
  follow-ups and are not claimed as passed.
- Final integrated review exposed a test-only coroutine scheduling race in two
  cancellation assertions. Both tests now join their outer test `Deferred`
  after the manager has drained its owned child job; the HTTP/request oracles
  and production ownership are unchanged. The repaired Stage 9 wildcard run
  passed 49/49, and the independent delta review returned `PASS`.
- Stage 9 is **CLOSED for the internal-company scope**. Stage 10 remains
  pending for the broader final qualification that will be needed before
  employee/private distribution or a public release.

## D13 implementation repair and current local evidence — 2026-09-07

- Pending Drive consent is now correlated by an immutable random operation ID
  plus the exact retained `DriveSyncManager` owner, authorization generation,
  and `GoogleIdentity`. Activity recreation keeps the manager and tracker under
  one `BlueprintViewModel`; true process recreation creates a new empty owner
  and therefore rejects the prior result rather than relabeling it.
- Synthetic unit tests cover operation/owner/identity/generation collisions and
  exact pending cleanup. An Android Activity recreation test verifies manager
  owner retention, and the app-owned consent trampoline round-trips the original
  operation ID and provider data. These close the identified code race but do
  not substitute for the real delayed-provider D13 workflow.
- Current local evidence is Stage 9 JVM 49/49, full JVM 480 total tests (477
  executed, 3 existing Windows symlink skips), complete device suite 33/33,
  debug and Android-test assembly PASS, and lint PASS with 0 errors. The final
  integrated Luna delta review is `PASS`; live D2/D8/D9/D11/D13 and
  provider/network cases, plus signed release qualification, remain
  pre-deployment follow-ups and are not current closure blockers.

The exact future Cloud registration, local non-secret client-ID input, public
debug certificate fingerprints, and one-time external keystore procedure are
in `STAGE9_AUTH_RELEASE_SETUP.md`. Current implementation and actual gate
results are recorded in `CODEX_AUDIT_IMPLEMENTATION_LOG.md`; synthetic
JVM/device regressions do not qualify real external Google authorization,
provider/network behavior, or signed release auth, which remain
pre-deployment follow-ups.
