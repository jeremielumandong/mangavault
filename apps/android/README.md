# Mangavault for Android

Kotlin, Jetpack Compose and Material 3. minSdk 26, target and compile SDK 36, application id `dev.mangavault.app`.

## Layout

```
apps/android/
├── core/   Pure Kotlin (JVM) module: API client, models, local store, sync, formatting. Unit-tested.
└── app/    The Android app: Compose UI, Coil image loading, Credential Manager sign-in.
```

`core` has no Android dependencies, so its tests run anywhere with a JDK:

- `api/` is the OkHttp + kotlinx.serialization client for `docs/api.md`. Errors come back as `ApiException` with the backend's `code`; network failures are status 0.
- `store/` holds the on-device data. `UserData` covers the library, progress, read marks, history and pending changes. `Prefs` holds device preferences. `JsonFileState` is a JSON file behind a `StateFlow`, written atomically. Every edit is a pure function in `UserOps`.
- `sync/SyncEngine` pushes pending changes, then pulls `/v1/me/*` changes since the last cursor. Both sides compare `updatedAt`, and the newer one wins.
- `util/Format` formats chapter labels, dates and history groups. `ReaderModes` picks the default reading mode from the title type.

`app` is organized by screen under `ui/`: onboarding, home, search, details, library, history, settings, reader, account. `AppContainer` wires the API, store, sync and toasts together.

## Configuration

Two values, `API_DOMAIN` and `GOOGLE_WEB_CLIENT_ID`, are read at build time and never committed. The build looks for them in this order:

1. A Gradle property: `./gradlew assembleDebug -PapiDomain=… -PgoogleWebClientId=…`
2. The environment variables `API_DOMAIN` and `GOOGLE_WEB_CLIENT_ID`.
3. The repository-root `config.env` (gitignored; copy `config.env.example`).
4. Fallbacks: `api.example.com`, and an empty client id.

The domain becomes `BuildConfig.API_BASE_URL = "https://<domain>/v1"`. With no client id, tapping the Google button shows a message that sign-in isn't configured. Debug builds also show **Dev sign-in** (`POST /v1/auth/dev`), which works against a backend with dev auth enabled.

The Google client id is the **web** OAuth client, the one listed in the Worker's `GOOGLE_AUDIENCES`. Credential Manager also needs an Android OAuth client in the same Google Cloud project, registered with this app's package name and your signing certificate's SHA-1.

## Build and run

You need JDK 17+ and the Android SDK (Android Studio sets both up). Then:

```sh
cd apps/android
./gradlew :core:test                 # pure-Kotlin tests
./gradlew :app:testDebugUnitTest     # app JVM tests
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug          # onto a connected device or emulator
```

To install a CI build, download the `mangavault-debug-apk` artifact from the Android workflow run and run `adb install -r app-debug.apk`.

## CI

`.github/workflows/android.yml` runs on pull requests and on pushes to `main` that touch `apps/android/**`. It runs the tests, the debug build and lint, then uploads the APK and the reports. `API_DOMAIN` and `GOOGLE_WEB_CLIENT_ID` come from repository secrets. Without them the build uses the placeholders above.

## Notes

- **Local first.** Favorites, progress and history work without an account and are stored in app-private files. The session token goes in `noBackupFilesDir`, and backups are off. The first sign-in with local data asks whether to merge it into the account.
- **Images.** Every cover and page request sends the `coverHeaders` or `imageHeaders` that came with it. Page bitmaps can be up to 16384 px tall, so webtoon strips stay sharp.
- **Reader.** Pages ahead of the reading position are prefetched into Coil's disk cache; the count is a setting, and data saver lowers it on metered networks. The next chapter loads near the end of the current one. The reading mode is saved per title. The other reader settings are shared defaults.
