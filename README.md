# Mangavault

A fast manga, manhwa and webtoon reader: one merged catalog crawled from several sites, native apps for iOS and Android, and a Rust backend on Cloudflare Workers + D1.

| Path | What |
| --- | --- |
| [`backend/`](backend/README.md) | Rust Worker: crawler, merged catalog, API, accounts and sync |
| [`docs/api.md`](docs/api.md) | The API contract the apps use |
| [`apps/android/`](apps/android/README.md) | Android app (Kotlin, Jetpack Compose) |
| [`apps/ios/`](apps/ios/README.md) | iOS app (SwiftUI) |
| `design/prototype/` | Claude Design handoff for the app UI |

Deployment settings such as the API domain live in a gitignored `config.env` (copy [`config.env.example`](config.env.example)); they are never committed.
