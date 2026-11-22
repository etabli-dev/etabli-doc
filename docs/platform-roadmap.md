# Platform roadmap

Établi Doc · state as of 2026-11-08 · this is a plan. Observed results stay in the
[current status page](current-status.qmd) and the [refinement record](refinement-2026-10.md);
Android release steps are detailed in
[android/SUBMISSION.md](https://github.com/etabli-dev/etabli-doc/blob/main/android/SUBMISSION.md).

The [2026-11-08 Apple refinement](apple-audit-2026-11-08.md) records the implemented iOS metadata/detail parity and current unsigned build verification. Live-service and signed-distribution gates remain open.

The [2026-11-09 Android refinement](android-audit-2026-11-09.md) implements A1 (combined filters), A3/I3 (50 documents ordered by date added) and A5 (specific rejected-login errors). The original planning rows below retain their acceptance criteria; A2, A4 and A6 remain open.

## 1. Platform role

Établi Doc is a **mobile-only** app for Android and iOS, on phones and tablets (the iOS
target already includes iPad). It is for finding a document in your own Paperless-ngx
archive away from the desk: search, narrow by tag, correspondent or type, and look at
the original.

No Ubuntu/Debian, Windows or macOS application is planned. Paperless-ngx ships a full
web interface that already works well in desktop browsers and offers more than this
client (upload, editing, bulk actions), so a desktop build would add maintenance without
adding capability.

| Platform | Role | Used for |
|---|---|---|
| Android | release target | phones and tablets; signed APK on GitHub Releases, Google Play, later F-Droid |
| iOS / iPadOS | release target | iPhone and iPad; TestFlight, then App Store |
| Ubuntu/Debian, Windows, macOS | not planned | the Paperless-ngx web interface in a browser |

## 2. Current state

| Platform | Status | Technology | How it is built | What is verified | Distribution |
|---|---|---|---|---|---|
| Android | in development | Kotlin 1.9.24, Jetpack Compose (Material 3), OkHttp 4.12, DataStore, Android Keystore; minSdk 26, compile/target SDK 36; AGP 8.13.2, Gradle 8.13, JDK 17 | `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease`; CI job `android` in `.github/workflows/android.yml` (ubuntu-24.04) | CI on 2026-10-31: unit and TLS-fixture tests, lint, debug APK, unsigned release AAB. Locally: production-Keystore instrumented tests on an API 36 emulator (2026-10-31) and an account-free UI journey on an API 35 emulator (2026-11-02). Not verified: a live Paperless-ngx server, a physical device, a release-signed build | GitHub Releases `v0.1.0` (2026-06-24): one APK signed with the Android debug key, built before the October–November changes. No Google Play or F-Droid listing |
| iOS | in development | SwiftUI, URLSession, Keychain; iOS 17+; iPhone and iPad | Xcode project `ios/EtabliDoc.xcodeproj`; CI job `ios` (macos-15): `python3 scripts/check_swift.py` plus an unsigned simulator build | CI on 2026-10-31: Foundation client checks and the full unsigned simulator build. Not verified: any device run, a live server, signing | none (no TestFlight, no App Store) |
| Ubuntu/Debian | not planned | — | — | — | — |
| Windows | not planned | — | — | — | — |
| macOS | not planned | — | — | — | — (see decision D8 on iPad apps on Apple silicon Macs) |

Shared limits on both platforms: only the first result page is shown; upload, editing and
deletion are not implemented; HTTPS only, authenticated redirects refused, 8 MiB JSON
bound, 45-second request budget.

## 3. Architecture

**Now.** Two independent native codebases with no shared code:

- `android/`: `net/PBClient.kt` (Paperless client, DataStore settings, Keystore-encrypted
  token), `net/HttpTransport.kt` (bounded, cancellable OkHttp), `ui/screens/` (Compose).
- `ios/`: `App/Net/Client.swift` (`PBClient`, URLSession, Keychain), `App/Net/HttpTransport.swift`,
  `App/Shell/` (SwiftUI views).

What the two share today is the server contract (`/api/token/`, `/api/documents/`,
`/api/tags/`, `/api/correspondents/`, `/api/document_types/`, `/api/documents/{id}/download/`),
the limits above and the Coder design tokens copied by `tool/sync_style.sh`.

**Target.** Keep two native apps; iOS stays SwiftUI. With no desktop target there is no
reason for a Kotlin Multiplatform module, and the client layer is a few hundred lines per
platform. Keep the platforms in step with three cheap tools instead:

1. the parity tables in section 4, updated in the same commit as any feature change;
2. one set of HTTP reply fixtures in a new top-level `fixtures/paperless/` folder, read by
   both `android/app/src/test/.../PBClientTest.kt` and `scripts/check_client.swift`, so both
   clients are tested against identical server replies;
3. the acceptance column of each parity row as the behaviour specification (query
   parameters, limits, error mapping).

## 4. Per-platform execution

### 4.1 Android

**Feature gap: present on iOS, missing on Android**

| # | Feature | Where it exists (iOS) | Android work | Accepted when |
|---|---|---|---|---|
| A1 | Filter by tag, correspondent and document type, combinable with the full-text query | `ios/App/Shell/DocumentsView.swift` (`filterChips`); `ios/App/Net/Client.swift` `listDocuments(query:tagID:correspondentID:docTypeID:pageSize:)` sending `tags__id__all`, `correspondent__id`, `document_type__id` | Add the three parameters to `PBClient.listDocuments`; `listCorrespondents()` and `listDocumentTypes()` already exist in `PBClient.kt` but are unused. Add filter menus and a "clear" action to `DocumentsScreen.kt` | JVM fixture test asserts the exact query string for each filter alone and combined; a failed name lookup shows a warning but keeps the document list (as iOS does) |
| A2 | Open the original document (PDF or image) | `Client.swift` `downloadOriginal(id:)` streams to a temporary file with a 256 MiB bound; `DocumentsView.swift` shows it with `.quickLookPreview` and deletes it on dismiss | Add a streaming download to `HttpTransport.kt` with the same 256 MiB bound into the app cache; show PDFs and images in-app (decision D6); delete the temporary file when the viewer closes and on next launch | Fixture tests for success, oversize (fails before the limit is exceeded on disk), HTTP error and cancellation; no file left in the cache after closing the viewer |
| A3 | Explicit page size 50 and one shared ordering | `listDocuments(pageSize: 50)` with `ordering=-added` | Android requests the server's default page size ordered by `-created`. Request `page_size=50` and use the ordering chosen in decision D7 on both platforms | Fixture test asserts `page_size` and `ordering`; README "first result page" text matches |
| A4 | Search as you type (250 ms debounce) and pull to refresh | `DocumentsView.scheduleSearch()`, `.refreshable` | Replace the Search button with a debounced query that reuses the existing generation/cancellation guard; add Material 3 pull-to-refresh | An older reply never overwrites a newer query (existing generation test pattern) |
| A5 | Specific "username or password rejected" message | `PBError.invalidCredentials` for HTTP 400/401 from `/api/token/` | Map 400/401 in `PBClient.connect` to a dedicated error instead of "Server returned HTTP 400" | Fixture test for 400 and 401; previous account still intact afterwards |
| A6 | Theme choice Auto / Light / Dark | `ios/App/Shell/SettingsView.swift` (`ThemePreference`) | Store the choice in DataStore and pass it to `CoderTheme(darkOverride = …)`, which already accepts an override; Établi Focus has an Android implementation to mirror (`android/app/src/main/java/com/raban/etabli/focus/prefs/ThemePreference.kt`) | Choice survives relaunch; "Auto" follows the system |

**Build and run**

```sh
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug bundleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest        # emulator or test device attached
```

Release candidate, once `android/keystore.properties` exists (see SUBMISSION.md):

```sh
./gradlew --no-daemon testDebugUnitTest lintRelease assembleRelease bundleRelease
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
```

**Packages and channels**

- *GitHub Releases:* release-signed APK plus `SHA256SUMS`; the hash in the release notes
  must equal GitHub's asset digest. A release-signed APK cannot update the debug-signed
  `v0.1.0` install: the release notes must say to uninstall it first (the token is excluded
  from backup, so users simply reconnect).
- *Google Play:* AAB (`bundleRelease`) with Play App Signing; internal testing, then closed
  testing, then production. The first upload of a new app is done in Play Console; later
  uploads can be automated.
- *F-Droid:* later (M8). Only listing text exists (`fastlane/metadata/android/en-US`); there
  is no build recipe yet. The dependencies (AndroidX, Compose, OkHttp) are free software, so
  a source build is realistic. With reproducible builds F-Droid can publish the developer
  signature, keeping GitHub, Play and F-Droid installs interchangeable (decision D1).

**Signing prerequisites (owner):** an app signing key and an upload key per decision D1,
kept outside the repository with backups; a Google Play developer account (D4); a privacy
policy URL (D5). CI secrets in a GitHub environment named `release` with the owner as
required reviewer: `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`; later `PLAY_SERVICE_ACCOUNT_JSON` for automated Play uploads.

**CI**

- Keep `android.yml` unchanged as the per-push verification.
- Add `.github/workflows/android-release.yml`: triggered by tags `v*` and
  `workflow_dispatch`; one job on ubuntu-24.04 in environment `release`; workflow-level
  `permissions: contents: read`, `contents: write` only on the job that creates the draft
  release; reuse the pinned `actions/checkout`, `actions/setup-java` and
  `android-actions/setup-android` SHAs from `android.yml`. Steps: decode the keystore into
  `$RUNNER_TEMP`, write `android/keystore.properties` with an absolute `storeFile`, run tests,
  `lintRelease assembleRelease bundleRelease`, verify the signature (fail if the certificate
  is `CN=Android Debug`) and 16 KB alignment, write `SHA256SUMS`, then
  `gh release create "$GITHUB_REF_NAME" --draft` with the APK and `SHA256SUMS`; keep the AAB
  as a workflow artifact for Play. Delete the keystore files in an `if: always()` step.
- Later: an internal-track Play upload step through the Play Developer API, pinned to a full
  commit SHA like every other action.

**Acceptance criteria (Android)**

- A1–A6 done, each with the automated test named in its row, green in CI.
- Live journey on a disposable Paperless-ngx test account (D3): connect, search, every filter
  alone and combined, open a PDF and an image original, cancel a running download,
  disconnect; failures stay visible.
- Release APK: `apksigner` shows the owner's certificate; the 16 KB alignment check passes;
  installs and runs on API 26 and API 36; an upgrade from the previous signed build keeps the
  account.
- Play: the internal-track build installs from the Play Store; the pre-launch report shows no
  crash; the Data safety answers match the shipped artifact.

### 4.2 iOS

**Feature gap: present on Android, missing on iOS**

| # | Feature | Where it exists (Android) | iOS work | Accepted when |
|---|---|---|---|---|
| I1 | Tag names on result rows (iOS shows only a tag count) | `DocumentsScreen.kt` `DocRow` (up to six tag chips) | Show tag names in `DocumentsView.swift` rows using the already loaded `tags` list | Row shows names; unknown IDs fall back to `#id` |
| I2 | Original filename on result rows | `DocRow` | Add `original_file_name` to the row metadata | Visible when present, omitted when null |
| I3 | Ordering and date shown | Android shows the created date and orders by `-created`; iOS shows "added" and orders by `-added` | Apply decision D7 together with A3 | Both platforms list the same documents in the same order for the same query |

**Build and run** (a Mac with Xcode; CI uses the default Xcode of macos-15):

```sh
python3 scripts/check_swift.py
xcodebuild -project ios/EtabliDoc.xcodeproj -scheme EtabliDoc -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Archive and upload to App Store Connect (TestFlight):

```sh
xcodebuild archive -project ios/EtabliDoc.xcodeproj -scheme EtabliDoc -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/EtabliDoc.xcarchive \
  DEVELOPMENT_TEAM="$APPLE_TEAM_ID" MARKETING_VERSION="$VERSION" CURRENT_PROJECT_VERSION="$BUILD" \
  -allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH" \
  -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID"
xcodebuild -exportArchive -archivePath build/EtabliDoc.xcarchive -exportPath build/export \
  -exportOptionsPlist "$RUNNER_TEMP/export.plist" -allowProvisioningUpdates \
  -authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID" \
  -authenticationKeyIssuerID "$ASC_ISSUER_ID"
```

`export.plist` sets `method` = `app-store-connect`, `destination` = `upload` and `teamID`. Write
it at run time: the repository's `.gitignore` excludes `ExportOptions.plist`.

**Distribution path:** TestFlight internal testing (no review) → external TestFlight (Beta
App Review) → App Store. Already in place: App icon, privacy manifest
(`ios/App/PrivacyInfo.xcprivacy`, declaring the UserDefaults and file-timestamp reasons),
`ITSAppUsesNonExemptEncryption = NO` in both build configurations. Still needed: iPhone and
iPad screenshots (the target includes iPad), privacy policy and support URLs, App Privacy
answers, age rating, and review notes with a reachable demo Paperless-ngx account (D3).

**Signing prerequisites (owner):** Apple Developer Program membership and Team ID (D4); the
bundle ID `com.raban.etabli.doc` registered with an App Store Connect app record; an App Store
Connect API team key (Key ID, Issuer ID, `.p8`) whose role may manage signing (Admin, or App
Manager with access to certificates and profiles). If the key cannot manage cloud signing, also
an Apple Distribution certificate exported as `.p12`. CI secrets in environment `release`:
`APPLE_TEAM_ID`, `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8_B64`, and if needed
`IOS_DIST_CERT_P12_B64`, `IOS_DIST_CERT_PASSWORD`.

**CI**

- Keep the `ios` job in `android.yml` as the per-push check.
- Add `.github/workflows/ios-release.yml` (macos-15, tags `v*` and `workflow_dispatch`,
  environment `release`, `permissions: contents: read`): decode the API key into
  `$RUNNER_TEMP`, import the certificate into a temporary keychain if one is used, run
  `scripts/check_swift.py`, archive, export and upload as above; delete key and keychain in an
  `if: always()` step.

**Acceptance criteria (iOS)**

- I1–I3 done; `scripts/check_swift.py` and the simulator build green in CI.
- On an iPhone and an iPad (TestFlight build) against the test account: connect, search, all
  filters, Quick Look preview, temporary file removed after dismissal, account kept after
  relaunch, disconnect.
- VoiceOver reads rows and filter menus; the largest Dynamic Type size stays usable; iPad
  rotation and Split View keep the layout usable.
- The TestFlight build is processed and installs from the TestFlight app; App Review approves
  the submission.

### 4.3 Ubuntu/Debian — not planned

No Linux application is planned. Paperless-ngx's web interface works in any desktop browser
and covers everything this app does plus upload and editing; a native client would duplicate
it. On Ubuntu or Debian, open your Paperless-ngx address in Firefox or Chromium and keep it as
a bookmark or browser shortcut.

### 4.4 Windows — not planned

No Windows application is planned, for the same reason: the Paperless-ngx web interface in
Edge, Firefox or Chrome is the desktop client.

### 4.5 macOS — not planned

No macOS application is planned; use the Paperless-ngx web interface in Safari or another
browser. Note that App Store Connect offers iPhone and iPad apps on Apple silicon Macs by
default; decision D8 covers whether to opt out until that is tested.

## 5. Milestones

Owner prerequisites (section 6) can be prepared in parallel with M1–M2.

1. **M1 — Android parity.** Implement A5, A3, A1, A4, A2, A6 in that order (smallest first).
   *Exit:* every A row marked done in this file with its test; `android.yml` green; account-free
   emulator journey repeated.
2. **M2 — iOS parity and shared fixtures.** I1–I3; move reply fixtures to `fixtures/paperless/`
   and read them from both test suites. *Exit:* both suites use the shared fixtures;
   `check_swift.py` and the simulator build green in CI.
3. **M3 — Android live acceptance.** Needs D3. *Exit:* the Android journey in 4.1 passes on an
   emulator and one physical device; results recorded in `docs/current-status.qmd`.
4. **M4 — Signed Android build on GitHub Releases.** Needs D1, D2. Add `android-release.yml`.
   *Exit:* a draft release with a release-signed APK and `SHA256SUMS`; signature, 16 KB alignment
   and upgrade path checked; README availability updated.
5. **M5 — Google Play.** Needs D4, D5. *Exit:* internal track installed; closed testing done
   (if the account requires it); production release approved.
6. **M6 — TestFlight.** Needs D4. Add `ios-release.yml`. *Exit:* a processed TestFlight build
   installed on an iPhone and an iPad; the iOS journey in 4.2 passes.
7. **M7 — App Store.** *Exit:* listing complete, App Review approved, release published.
8. **M8 — F-Droid (optional).** Needs D9. *Exit:* fdroiddata merge request with a source-build
   recipe accepted, or the decision not to publish recorded here.

At each exit, update the README availability lines and `docs/current-status.qmd` with what
was actually observed.

## 6. Owner decisions and prerequisites

| ID | Decision or prerequisite | Recommendation |
|---|---|---|
| D1 | Android signing model: own app signing key uploaded to Play App Signing plus a separate upload key, or a Google-generated app signing key | Own app signing key: GitHub, Play and reproducible F-Droid builds then share one signature and users can switch channels |
| D2 | Version scheme | Tag `vX.Y.Z` sets `versionName` and `MARKETING_VERSION`; a monotonically increasing integer sets `versionCode` and `CURRENT_PROJECT_VERSION`; first signed release above `0.1.0` |
| D3 | Disposable Paperless-ngx test instance with a demo account reachable from the internet | Required for live acceptance, Play "App access" and App Review; never the personal archive |
| D4 | Store accounts: Google Play developer account (personal or organisation) and Apple Developer Program (individual or organisation) | Check Play's rule for new personal accounts (currently a closed test with 12 opted-in testers for 14 days before production) |
| D5 | Privacy policy and support URLs | Host them on the documentation site; both stores require a privacy policy |
| D6 | Android document viewer: in-app (PDF renderer and image view) or hand-off to another app via `ACTION_VIEW` | In-app for PDFs and images, hand-off only for other types: closest to iOS Quick Look and keeps documents inside the app |
| D7 | Ordering of results: date created or date added | Date added, matching Paperless-ngx's "recently added" use on a phone; either is fine if both platforms agree |
| D8 | Offer the iPad app on Apple silicon Macs | Opt out in App Store Connect until it has been tested, consistent with macOS being "not planned" |
| D9 | F-Droid main repository with reproducible builds, or no F-Droid | Decide after M5 |
| D10 | Where CI signing secrets live: per-repository environment or organisation secrets for the suite | Per-repository `release` environment with required reviewer |
