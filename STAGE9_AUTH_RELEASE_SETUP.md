# Stage 9: Google OAuth and release setup

Status: INTERNAL-COMPANY STAGE 9 CLOSED for the scoped development boundary;
deferred external configuration and runtime qualification remain outstanding.
This document is a future pre-deployment setup/evidence checklist, not a
public-release qualification claim.

## Scope decision — internal company development

The owner decided on 2026-09-07 that SOTAware Construct will remain internal to
the company and will not be publicly released in the current planning horizon.
The current Stage 9 boundary is the Android-signed debug APK, the checked-in
privacy/authentication safeguards, and the real internal Workspace
sign-in/Drive-grant/restart/sign-out evidence recorded below. Public launch,
external OAuth audience/verification, public privacy/homepage/terms pages, Play
App Signing, and a signed public release are not applicable to this closure and
are not claimed as complete.

A stable company signing key and release-certificate OAuth registration are
deferred prerequisites for durable employee APK/private distribution and
updates. The live-provider D2/D8/D9/D11/D13 and provider/network rows remain
pre-deployment qualification follow-ups; synthetic tests, instrumentation, and
review evidence do not silently become live-provider pass claims.

## Repository contract

The checked-in implementation currently has these boundaries:

- The installable identity is `com.sotaware.construct`, with `versionCode = 1`
  and `versionName = "1.0.0"`. The Kotlin source namespace remains
  `com.example.myapplication` intentionally; Google Cloud registration must
  use the `applicationId`, not the source namespace.
- `GoogleCredentialDriveAuth` uses Android Credential Manager with
  `GoogleIdTokenCredential`. The explicit sign-in button uses
  `GetSignInWithGoogleOption` so the full account chooser is available;
  returning startup uses `GetGoogleIdOption` filtered to authorized accounts,
  auto-select when possible, and `preferImmediatelyAvailableCredentials`.
  Startup restoration is attempted only after a prior accepted Drive grant set
  the local restore flag. Credential Manager may still show its returning-account
  selector, so this is not a silent-auth guarantee. Both requests pass the Web
  OAuth client ID as `serverClientId`; `AuthorizationClient` is a separate step
  for Drive consent.
- The only Drive scope requested and accepted is
  `https://www.googleapis.com/auth/drive.file`. The app rejects broader Drive
  scopes and keeps the access token in memory only. Stage 4's
  `SyncCoordinator`/`GoogleDriveGateway` remains the synchronization owner.
- `app/build.gradle.kts` currently pins
  `androidx.credentials:credentials:1.6.0`,
  `androidx.credentials:credentials-play-services-auth:1.6.0`,
  `com.google.android.libraries.identity.googleid:googleid:1.2.0`, and
  `com.google.android.gms:play-services-auth:21.6.0`. The current Android guide
  displays a newer Credential Manager alpha in its example; this checklist
  does not authorize a dependency change. Qualify the checked-in versions.
- `AndroidManifest.xml` sets `android:allowBackup="false"`, points to both
  legacy and Android 12+ backup rule files, and uses a non-exported
  `${applicationId}.fileprovider`. `file_paths.xml` exposes only
  `files/camera_captures/`. The backup XML excludes all listed data domains,
  including device-transfer domains.

### Current local qualification note (2026-09-07)

The owner supplied a non-secret Web OAuth client ID through the user-level
Gradle properties file outside the repository. The current debug build carries
that value in `BuildConfig.GOOGLE_WEB_CLIENT_ID`; the value is intentionally not
repeated here. On the authorized TB336FU tablet (Android 16/API 36), the real
`com.sotaware.construct` debug app completed internal Workspace sign-in and
Drive grant, restored its signed-in/root state after force-stop and relaunch,
cleared the local session on sign-out, and remained signed out after canceling
an explicit chooser attempt. This is the current internal-scope debug evidence.
It does not claim the synthetic external-account, release-certificate,
process-recreation, revocation, provider/network, or full Drive transfer rows
below; those remain pre-deployment follow-ups.

The current code additionally routes Drive consent through a non-exported,
app-owned result trampoline carrying a random operation ID. The pending request
is accepted only when that ID, the retained manager owner, numeric generation,
and exact Google identity all still match. Configuration recreation is covered
by a synthetic Activity test, and true process death fails closed by losing the
in-memory pending owner. D13 still requires the real delayed-provider workflow
below; fail-closed synthetic coverage is not a qualification waiver.

## Deferred external Google Cloud setup

These steps are for future employee/private-distribution or public-release
qualification, not for the current internal Stage 9 closure. Complete them in
one Google Cloud project owned by the app owner. Do not create credentials,
change a project, or use a production Drive account as part of an unattended
qualification run.

1. In Google Cloud Console, select an existing project or create a project for
   SOTAware Construct. Enable only **Google Drive API** under APIs & Services >
   Library. The current Android path does not require an API key, service
   account, Firebase configuration file, OAuth redirect URI, or an OAuth client
   secret.
2. Open Google Auth Platform > Branding and configure the app name
   **SOTAware Construct**, a monitored support email, and a current project
   contact email. Add the production homepage, privacy policy, and terms URLs
   before public release; they are not supplied by this repository.
3. For an external/public qualification, under Audience use **External** and
   keep the publishing status **Testing** during qualification. Add a synthetic
   Google test account owned by the tester, plus any other explicitly authorized
   testers. If a later employee-only distribution is restricted to one Google
   Workspace organization, choose the organization-specific audience permitted
   by the owner's policy instead; the current internal scope does not require
   external audience/verification. Testing projects allow up to 100 listed test
   users and test-user authorizations expire seven days after consent, so
   re-consent when that window expires.
4. Under Data Access, add exactly
   `https://www.googleapis.com/auth/drive.file`. Do not add
   `https://www.googleapis.com/auth/drive`, `drive.appdata`, or another Drive
   scope. `drive.file` is the least-privilege per-file scope intended for files
   created by the app or explicitly opened/shared by the user. The scope must
   appear both in this Cloud consent configuration and in the app's
   `AuthorizationRequest`.
5. Under Google Auth Platform > Clients, create or verify the Android client
   registration(s) for the final package name:

   ```text
   Package name: com.sotaware.construct
   Certificate: the SHA-1 of the artifact being tested
   ```

   The current internal debug artifact uses the debug certificate. Before
   durable employee/private distribution, register the stable company release
   certificate too. If the Cloud Console represents each package/SHA-1 pair as
   a separate Android client, create one client per pair; if it offers
   additional fingerprints on one client, add each pair there. Before testing a
   Play-installed artifact, also register the Google Play app-signing
   certificate for this same package.
6. Create one **Web application** OAuth client in the same project. Its client
   ID is the public value that Credential Manager needs; it is not a password.
   Only the public client ID is used by this Android flow; leave any generated
   client secret out of the app configuration and chat. Record
   the resulting `...apps.googleusercontent.com` ID in the local Gradle input
   described below.
7. Leave the Cloud project in Testing until the future pre-deployment matrix
   below passes. Before a public launch, complete the applicable brand/OAuth
   verification and move the production audience/client configuration to the
   owner's release project. Do not reuse a testing-only client for production
   without checking its audience, test users, certificate, and consent
   configuration.

### Certificate fingerprints

Google's Android OAuth registration uses SHA-1. Keep SHA-256 alongside it for
Play/API registration and package ownership records. Fingerprints are public
certificate information and may be entered in Cloud or Play Console; never
share the private key or either password.

Collect the debug certificate without assuming a fixed keystore location:

```powershell
.\gradlew.bat :app:signingReport
```

The local debug signing report verified during the September 4 Stage 9
continuation reported these public fingerprints for `com.sotaware.construct`:

```text
SHA-1:   11:0A:B8:16:78:5F:3C:77:D9:A1:23:A6:67:69:A5:5D:FA:F7:ED:26
SHA-256: A4:B9:BF:DB:7C:E9:56:3C:27:66:43:9E:30:4E:27:99:25:69:4B:90:AA:BF:A7:AC:F8:FA:58:59:00:42:12:43
```

These identify this workstation's debug certificate only. Re-run the report
when the workstation or signing configuration changes; the release/upload and
Play app-signing fingerprints still require their actual certificates.

Alternatively, inspect the usual debug keystore (the command prompts if its
password is needed):

```powershell
keytool -list -v `
  -alias androiddebugkey `
  -keystore "$env:USERPROFILE\.android\debug.keystore"
```

After creating the user-owned upload keystore below, collect its fingerprints:

```powershell
keytool -list -v `
  -alias sotaware-upload `
  -keystore "$env:USERPROFILE\Documents\SOTAware\keys\sotaware-upload.jks"
```

For an APK, verify the certificate actually inside the file with the Android
SDK's `apksigner verify --print-certs <artifact.apk>`. For a signed AAB, use
`keytool -printcert -jarfile <artifact.aab>`. Modern APK signatures may not be
JAR signatures, so the AAB command is not an APK verification substitute. Do
not infer a release fingerprint from the debug report.

## Deferred one-time local upload/release key

The repository's future release signing configuration requires a keystore path
that is absolute and outside the repository. This key is not required for the
current internal debug closure. Before durable employee APK/private
distribution or updates, run the following PowerShell block to create an
upload keystore under the user's Documents directory. It deliberately omits
`-storepass` and `-keypass`; `keytool` prompts interactively for both. Run it
once on the owner's workstation, preserve the resulting file and passwords in
the owner's password manager, and keep a protected backup of the keystore.

```powershell
New-Item -ItemType Directory -Force `
  -Path "$env:USERPROFILE\Documents\SOTAware\keys" | Out-Null

keytool -genkeypair -v `
  -keystore "$env:USERPROFILE\Documents\SOTAware\keys\sotaware-upload.jks" `
  -storetype JKS `
  -alias sotaware-upload `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000 `
  -dname "CN=SOTAware Construct"
```

Use a strong, unique keystore password and key password; do not put either in
this file, chat, source, command history, or a committed Gradle property. The
10000-day validity exceeds Android's recommended 25-year minimum for an
application key. Do not use the Android debug keystore as a production or
upload key.

If Google Play App Signing is used, this locally controlled key is the upload
key. The locally signed release APK/AAB has the upload certificate, while APKs
delivered by Google Play have Google's app-signing certificate. Register both
certificates for the artifacts that will be tested. Google Play exposes the
app-signing and upload SHA-1/SHA-256 values under Release > Setup > App
integrity/App signing. A Play upload-key reset does not replace the
Google-held app-signing certificate.

## Local Gradle configuration

`app/build.gradle.kts` resolves each value from the user-level Gradle property
first and then the matching environment variable. Put the properties in the
user's untracked Gradle user-home file (normally
`%USERPROFILE%\.gradle\gradle.properties`, or the active custom
`GRADLE_USER_HOME`), never in this repository.

The Web client ID is already used by the current internal debug build. The
keystore and release-password inputs below remain future distribution inputs;
do not create or record them as part of the current internal closure.

| Purpose | User-level Gradle property | Environment variable | Value |
| --- | --- | --- | --- |
| Keystore path | `sotawareReleaseStoreFile` | `SOTAWARE_RELEASE_STORE_FILE` | Absolute path to `sotaware-upload.jks` outside the repo |
| Keystore password | `sotawareReleaseStorePassword` | `SOTAWARE_RELEASE_STORE_PASSWORD` | Local secret, supplied out of band |
| Key alias | `sotawareReleaseKeyAlias` | `SOTAWARE_RELEASE_KEY_ALIAS` | `sotaware-upload` |
| Key password | `sotawareReleaseKeyPassword` | `SOTAWARE_RELEASE_KEY_PASSWORD` | Local secret, supplied out of band |
| Credential Manager Web client ID | `sotawareGoogleWebClientId` | `SOTAWARE_GOOGLE_WEB_CLIENT_ID` | Public Web client ID ending in `.apps.googleusercontent.com` |

The effective user-level file has the following shape. Replace placeholders on
the owner's machine only; this example contains no usable secret:

```properties
sotawareReleaseStoreFile=C:/Users/<account>/Documents/SOTAware/keys/sotaware-upload.jks
sotawareReleaseStorePassword=<local-keystore-password>
sotawareReleaseKeyAlias=sotaware-upload
sotawareReleaseKeyPassword=<local-key-password>
sotawareGoogleWebClientId=<web-client-id>.apps.googleusercontent.com
```

The Web client ID is compiled into `BuildConfig.GOOGLE_WEB_CLIENT_ID` and is
passed to both Google credential request builders. If it is empty, the
adapter intentionally fails closed at runtime. Release tasks
`assembleRelease`, `bundleRelease`, `packageRelease`, and `installRelease`
depend on `verifySotawareReleaseSigning`, which fails until all four signing
inputs are present. Environment variables are suitable for a CI secret store;
do not echo them or persist them in a repository file.

No `google-services.json` is consumed by the current code. The Android client
IDs are Cloud-side package/certificate registrations; only the Web client ID
is an app build input for Credential Manager.

## Pre-deployment qualification prerequisites and evidence

For future pre-deployment qualification, use a dedicated synthetic Google
account and a Google Play Services-capable, task-owned emulator or test device.
Use synthetic PDFs, annotations, and photos in a test Drive root. Do not wipe a
personal install, account, device, or Drive data. A fresh install or app-data
reset in an isolated test profile is the clean-install condition.

Record for every run: build variant and version, `com.sotaware.construct`,
artifact SHA-1/SHA-256, Android API level, Google Play Services version, Cloud
project/client IDs (IDs only), test-account alias, requested scope, and
pass/fail evidence. Never record tokens, passwords, private keys, raw
documents, or unrestricted Drive payloads.

### Supporting repository checks

- [ ] Run the existing Stage 9 JVM tests, including
  `DriveAuthorizationSessionTest`, and retain the result as supporting
  evidence for stale-generation, missing-token, missing-scope, and broader
  scope rejection.
- [ ] Verify the final manifest package at install time is
  `com.sotaware.construct`; source package names under
  `com.example.myapplication` do not change this requirement.
- [ ] Verify the release certificate inside the APK/AAB matches the
  certificate whose SHA-1 was registered for that artifact.
- [ ] Confirm backup rules and the narrow FileProvider policy remain unchanged.
  The Android Auto Backup guidance warns that some Android 12+ OEMs can treat
  `allowBackup=false` differently for device-to-device transfer; Stage 10 must
  perform the separate transfer/stale-state check.

### Debug auth and transfer matrix

The debug rows use the debug SHA-1 Android client and the configured Web client
ID. Run them through the actual app UI and the Stage 4 synchronization path.
The internal Workspace sign-in, Drive grant, restart restoration, local
sign-out, and chooser-cancellation observations are recorded as current-scope
evidence above. D2, D8, D9, D10, D11, and D13 live two-account,
revocation/provider-network, transfer, and delayed-provider cases are
pre-deployment follow-ups and are intentionally not marked as passed here.

| ID | Scenario | Action | Pass criteria |
| --- | --- | --- | --- |
| D1 | Clean first-time sign-in | On an isolated fresh debug install, tap Sign in with Google and choose the synthetic account. | Credential Manager returns a stable identity; no legacy GSI UI appears; no token is persisted; the Drive consent step is reached. |
| D2 | Account selection | Put two test Google accounts on the device and use the explicit Google sign-in button to choose each account. | The selected account is the one shown in app state; the other account receives no local or Drive work. |
| D3 | Successful Drive authorization | Grant only `drive.file`; use **Create SOTAware Backup Folder** to reuse an app-marked root or create one in Drive root. | AuthorizationClient returns an access token and the exact `drive.file` grant; Drive becomes usable only after the grant is accepted. |
| D4 | Denied Drive authorization | Deny or cancel the Drive consent resolution. | No active Drive session, upload, cursor advance, or success message remains. |
| D5 | Canceled sign-in | Dismiss/cancel the Credential Manager chooser before identity completion. | No identity or Drive session is installed; a later explicit attempt can retry. |
| D6 | Returning user and restart | After D3, force-stop/reopen the app and allow startup restoration. | Startup runs only when the local restore flag records a prior accepted Drive grant. Credential Manager filters to authorized accounts and may auto-select or show its returning-account selector; startup does not launch Drive consent resolution. Sync resumes only for the same account/root/document. |
| D7 | Sign-out | Tap Sign out while a Drive session and document binding are active. | Stage 4 work is fenced and joined; local Drive session/token, restore flag, and UI authorization state clear even if provider state clearing fails; no later upload uses the old account. Record the revocation limitation below. |
| D8 | Account switch | Sign in as account A, start work, then explicitly sign in as account B while A work is pending. | A's late result cannot install into B; A's sync binding is canceled/joined; B starts a new generation and cannot use A's root or token. |
| D9 | Revoked grant and reauthorization | Revoke the app's Google access for the synthetic account in Google Account security, then retry the explicit sign-in/Drive authorization flow. | The exact 401-rejected token is cleared from Google's cache using `AuthorizationClient.clearToken` before requesting another grant; failed/canceled invalidation remains pending for retry. Reauthorization obtains a new grant; no failed attempt advances remote metadata or reports success. |
| D10 | Provider/network failure | Exercise offline mode and a Play Services/provider failure at sign-in, authorization, and transfer boundaries. | The failure is visible as unavailable/failed; cancellation remains cancellation; no empty document, stale token, cursor, or partial upload is treated as success. |
| D11 | Upload/download | With a synthetic PDF containing annotations, measurements, notes, scale, shapes, and photos, use manual or lifecycle sync, then download through the app. | The actual Stage 4 coordinator and gateway round-trip the complete state and required photo bytes under the same account/root/document identity. |
| D12 | Scope boundary and root lookup | Use the app-marked root flow; if a separate test harness examines an unshared pre-existing item/folder, do so without activating the retained browser. | The active path searches for `appProperties` marker `sotaware_backup_root=1` and creates a marked root when absent. The app does not gain broad Drive access; any unshared-item failure is recorded as a scope limitation. |
| D13 | Process recreation during consent | On the isolated test installation, recreate the app process while Drive consent is pending; retry with the same and a different test account before delivering the old result. | A delayed result cannot acquire a newer attempt's identity/generation, install the old account's token, or start sync. Record the actual Activity Result/provider behavior; current synthetic tests do not qualify this lifecycle case. |

### Signed release matrix

Build and install an actually signed release artifact in an isolated package
environment. Repeat the identity and transfer cases that can differ by signing
certificate, at minimum R1-R7. A local APK signed with `sotaware-upload` uses
the upload/release Android OAuth registration. A Play internal-track install
uses the Google Play app-signing registration and must be tested separately.

| ID | Scenario | Action | Pass criteria |
| --- | --- | --- | --- |
| R1 | Signed artifact identity | Verify the APK/AAB certificate, package, version, and Web client wiring before install. | Artifact is signed, installs as `com.sotaware.construct` version `1.0.0`, and its exact certificate pair is registered in Cloud. |
| R2 | First sign-in | On an isolated release install, perform clean first-time sign-in with the synthetic account. | Credential Manager and the account chooser work with the release certificate; no debug-only client is required. |
| R3 | Drive grant | Grant `drive.file`, deny it once, then grant it successfully. | Denial leaves Drive unavailable; the successful grant installs only the current release session and exact least-privilege scope. |
| R4 | Restart/sign-out/switch | Exercise returning-user restart, sign-out, and A/B account switching. | No release process restart or late result crosses account, document, or generation boundaries. |
| R5 | Revocation/network | Revoke the grant and exercise offline/provider errors. | The release app clears unauthorized state and reauthorizes without false sync success. |
| R6 | Drive transfer | Upload and download the full synthetic annotation/photo fixture through the real UI and Stage 4 coordinator. | The signed release round-trips all required domains and bytes; no broad Drive scope or legacy path is used. |
| R7 | Play-delivered artifact | If Play App Signing is enabled, install a Play-signed internal-track artifact and repeat R2-R6 with the Play app-signing SHA-1. | The Google-signed certificate, not only the local upload certificate, is registered and works. |

The external OAuth configuration, synthetic Play Services test account, and
signed release installation are not required for the current internal-company
Stage 9 closure. They become required before durable employee/private
distribution or a public release. JVM tests alone do not prove Credential
Manager, consent UI, Play Services, certificate registration, provider/network
behavior, or Drive transfer; those remain pre-deployment follow-ups.

## Current implementation findings to resolve or explicitly accept

These are concrete observations from the current source; this document does
not change them.

1. **Sign-out does not revoke the remote Google grant.** The current UI fences
   synchronization, clears the in-memory `DriveAuthorizationSession`, clears the
   local restore flag, and calls `CredentialManager.clearCredentialState()`.
   That method does not call `AuthorizationClient.revokeAccess`, and the auth
   adapter has no revoke method. Therefore D7 proves local sign-out only. D9
   must use Google Account security/revocation to prove reauthorization, or the
   implementation needs a separately reviewed revoke-access action before
   claiming full disconnect semantics.
2. **`drive.file` limits arbitrary folder browsing.** The active path now
   searches only for an app-created root marked with
   `appProperties.sotaware_backup_root=1` and creates that marked root when it
   is absent. The legacy `listFolders("root")`, `listSharedDrives()`, and
   `listFoldersInSharedDrive(...)` browser code remains in the source but is
   unreachable from the active root flow. Google's `drive.file` scope does not
   grant a general Drive browser: it exposes app-created files and files the
   user explicitly opens or shares with the app. Qualification must use the
   marked app-created/explicitly shared test root. Supporting arbitrary
   existing My Drive or shared-drive browsing requires a separately reviewed
   Picker/scope design decision; do not solve it by adding full `drive` or
   `drive.appdata`.
3. **External configuration is intentionally absent from the repository.** An
   empty `GOOGLE_WEB_CLIENT_ID` fails closed, and release tasks fail closed
   without the four signing inputs. This is expected until the human-owned
   Cloud project and keystore are configured; it is not evidence of a
   successful auth or release gate.

## Official references verified for this checklist

- [Implement Sign in with Google with Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation)
- [About Sign in with Google and its prerequisites](https://developer.android.com/identity/sign-in/credential-manager-siwg)
- [`GetGoogleIdOption` API reference](https://developers.google.com/identity/android-credential-manager/android/reference/com/google/android/libraries/identity/googleid/GetGoogleIdOption)
- [`GoogleIdTokenCredential` API reference](https://developers.google.com/identity/android-credential-manager/android/reference/com/google/android/libraries/identity/googleid/GoogleIdTokenCredential)
- [`AuthorizationClient` API reference](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient)
- [`AuthorizationRequest.Builder` API reference](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder)
- [Create OAuth access credentials](https://developers.google.com/workspace/guides/create-credentials)
- [Configure the OAuth consent screen and choose scopes](https://developers.google.com/workspace/guides/configure-oauth-consent)
- [Choose Google Drive API scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Google Play services client authentication and SHA-1](https://developers.google.com/android/guides/client-auth)
- [Sign your app and configure Play App Signing](https://developer.android.com/studio/publish/app-signing)
- [Back up user data with Auto Backup](https://developer.android.com/identity/data/autobackup)
- [Get started with the Google Auth Platform](https://support.google.com/cloud/answer/15544987)
- [Manage the Google Auth Platform audience and test users](https://support.google.com/cloud/answer/15549945)
