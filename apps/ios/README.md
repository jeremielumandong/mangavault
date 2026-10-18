# Mangavault for iOS

SwiftUI app for iOS 17+, built from `design/prototype` against the API in `docs/api.md`.

## Build

You need a Mac with Xcode 16 or newer and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).

```sh
cd apps/ios
./scripts/configure.sh   # writes Config/Config.local.xcconfig from ../../config.env
xcodegen generate        # writes Mangavault.xcodeproj (gitignored)
open Mangavault.xcodeproj
```

Run `xcodegen generate` again after adding or removing files. The project file is never committed, so `project.yml` is the source of truth.

Tests:

```sh
swift test --package-path Packages/MangavaultKit          # pure logic, runs on macOS
xcodebuild test -project Mangavault.xcodeproj -scheme Mangavault \
  -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO
```

## Configuration

The backend domain is a build setting and never lives in the repository. `scripts/configure.sh` reads the repo-root `config.env` (copy `config.env.example`; it is gitignored). Environment variables override the file, which is how CI passes repository secrets. The script writes `Config/Config.local.xcconfig`, which is gitignored too, and `Config/Base.xcconfig` includes it when present.

| Key | Used for | Default |
| --- | --- | --- |
| `API_DOMAIN` | Backend host. The app calls `https://<API_DOMAIN>/v1`. A value that already has a scheme is used as is, for example `http://localhost:8787` for a local Worker. | `api.example.com` |
| `GOOGLE_IOS_CLIENT_ID` | iOS OAuth client from Google Cloud. The reversed id becomes the redirect URL scheme. Leave it empty and the Google button reports that sign-in isn't configured. | empty |
| `DEVELOPMENT_TEAM` | Apple team id for device builds. | empty |
| `MV_BUNDLE_ID` | Bundle id. Change it if `dev.mangavault.app` is taken on your team. | `dev.mangavault.app` |

The backend must list the bundle id in `APPLE_AUDIENCES` and the iOS Google client id in `GOOGLE_AUDIENCES` (see `backend/wrangler.toml`), or sign-in is rejected.

In CI (`.github/workflows/ios.yml`) the domain comes from the `API_DOMAIN` repository secret. Without it the build uses the placeholder, which is enough to compile and run the tests.

## Signing and running on a device

1. Set `DEVELOPMENT_TEAM` in `config.env` (or in Xcode under Signing & Capabilities), run `./scripts/configure.sh` and `xcodegen generate`.
2. Sign in with Apple needs the capability on the App ID. Automatic signing adds it from `Mangavault/Mangavault.entitlements` when your team is a paid developer account. Personal teams can't use Sign in with Apple; the rest of the app works without an account.
3. Debug builds also show a "Dev sign-in" button, which calls `/v1/auth/dev` and only works against a backend that has dev auth turned on.

## Layout

```
project.yml                 XcodeGen spec
Config/                     Base.xcconfig (+ gitignored Config.local.xcconfig)
scripts/configure.sh        writes the local xcconfig
Packages/MangavaultKit/     API models and client, sync merge rules, reader sequencing,
                            formatting, search filters, Google PKCE; unit-tested with `swift test`
Mangavault/
  App/                      app entry, root tabs, AppModel (navigation, sheets, toasts, accounts)
  Data/                     LocalStore (local-first, JSON on disk), SyncEngine, AuthService
  Images/                   ImagePipeline (headers, memory + disk cache, ImageIO downsampling)
  Features/                 Home, Search, Details, Library, History, Settings, Account, Onboarding, Reader
  Support/                  theme tokens, fonts, shared components, platform helpers
  Resources/                fonts (OFL), asset catalog
MangavaultTests/            app-level tests run in the simulator
```

Everything works without an account. Favorites, progress, read marks and history are stored on the phone and queued for sync. After sign-in, `SyncEngine` pushes the queue and pulls `/v1/me/*` changes, newest `updatedAt` wins.

The reader uses a `UICollectionView` for webtoon scroll and a `UIPageViewController` for the paged modes. Pages are decoded at the width they are drawn, so long webtoon strips stay sharp without holding full-size bitmaps in memory.
