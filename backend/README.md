# Mangavault backend

A Rust Cloudflare Worker that crawls MangaDex, MangaPill, AsuraScans and WeebCentral into one D1 database and serves the API in [`docs/api.md`](../docs/api.md). It stores metadata and image links only, never images.

## Layout

| Path | What |
| --- | --- |
| `crates/core` | Site adapters behind one interface (`SourceId`), the shared models, and title matching / chapter merging. Pure Rust, tested with fixtures. |
| `crates/worker` | The Worker: HTTP API, D1 storage, merged catalog, accounts and sync, cron crawler. |
| `migrations/` | D1 schema. |
| `scripts/deploy.sh` | Deploys with the domain and database id from the gitignored `../config.env`. |

## How the catalog works

- Every site's copy of a series is an `entry`. Entries whose normalized titles (or alternative titles) match share one `series`, which is what the app shows as a manga.
- The series' chapter list is the union of every site's chapters by chapter number. Each chapter keeps its sites in preference order (scanlation sites first for manhwa/manhua, MangaDex first for manga), and page loading falls back down that list.
- Every 15 minutes the cron reads each site's latest-updates page and refreshes the series that changed, most popular first, up to `CRAWL_REFRESH_BUDGET`. Every 6 hours it walks `CRAWL_POPULAR_PAGES` more pages of each site's popular list.
- Opening a series whose details are older than 6 hours refreshes it on the spot; searching with few local results also asks the sites live (at most once a day per query).

## Develop

Needs Rust with the `wasm32-unknown-unknown` target, Node 20+, and `cargo install worker-build`.

```sh
npm install
cargo test                     # parsers, matching, JWT verification
npm run migrate:local          # create the local D1 schema
echo 'DEV_AUTH=true' > .dev.vars
npm run dev                    # http://localhost:8787/v1
curl -X POST localhost:8787/v1/auth/dev -d '{"name":"me"}'   # a token for /v1/me/*
```

Run the crawler locally with `npx wrangler dev --local --test-scheduled` and `curl "localhost:8787/__scheduled?cron=*/15+*+*+*+*"`.

## Deploy

One-time setup:

1. `npx wrangler login`, then `npx wrangler d1 create mangavault` and note the database id.
2. Set `APPLE_AUDIENCES` (your iOS bundle id) and `GOOGLE_AUDIENCES` (your Google OAuth client ids) in `wrangler.toml` `[vars]`.

The deploy needs two values, `API_DOMAIN` (a hostname on a zone in your Cloudflare account) and `D1_DATABASE_ID`. They are never committed: `scripts/deploy.sh` reads them from environment variables, falling back to the repo's gitignored `config.env`. It applies pending migrations, deploys, and attaches the custom domain.

### From your machine

Copy `../config.env.example` to `../config.env`, fill in both values, then `npm ci && npm run deploy` (needs Rust with the `wasm32-unknown-unknown` target and `cargo install worker-build@0.8.7`).

### Cloudflare Workers Builds (deploy on push to main)

In the Worker's Settings > Build:

| Setting | Value |
| --- | --- |
| Git branch | `main` |
| Root directory | `backend` |
| Build command | empty |
| Deploy command | `npm run deploy` |
| Build variables | `API_DOMAIN` and `D1_DATABASE_ID` |
| Non-production branch builds | off |

The Worker's name in the dashboard must match `name` in `wrangler.toml` (`mangavault`). `scripts/build.sh` (wrangler's build step) installs Rust and worker-build in the build image, so the first build takes a few minutes.

### After the first deploy

- **Is it up?** `curl https://$API_DOMAIN/` returns the API name and version, and `curl https://$API_DOMAIN/v1/home` returns the Home rows. They are empty until the first crawl.
- **Custom domain.** The deploy attaches `API_DOMAIN` as a Custom Domain, which needs the domain's zone in the same Cloudflare account. Check it under the Worker's Settings > Domains & Routes. `workers_dev` is off, so there is no `*.workers.dev` URL.
- **First crawl.** The crons run every 15 minutes (latest updates) and every 6 hours (popular). To run one now, add a secret `ADMIN_TOKEN` (Worker > Settings > Variables and Secrets, type Secret; any long random string), then:

  ```sh
  curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "https://$API_DOMAIN/v1/admin/crawl?job=popular"
  curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "https://$API_DOMAIN/v1/admin/crawl?job=latest"
  ```

  Searching also crawls on demand: `curl "https://$API_DOMAIN/v1/search?q=solo%20leveling"` asks every site when the catalog has few matches.

### Sign-in (Apple and Google)

The apps sign in with Apple or Google, and the Worker checks the token's signature and its audience, then issues its own session token. The Worker accepts a token only when its `aud` is listed in `APPLE_AUDIENCES` or `GOOGLE_AUDIENCES`. Set those as Workers Builds build variables, comma-separated. The deploy passes them on, so a deploy never resets them.

| Provider | Create | Goes in |
| --- | --- | --- |
| Apple | In the Apple Developer portal, an App ID with the *Sign in with Apple* capability, using the iOS app's bundle id | `APPLE_AUDIENCES` = the bundle id |
| Google, Android | In Google Cloud Console > APIs & Services > Credentials (after setting up the OAuth consent screen): a **Web application** client, plus an **Android** client with package name and SHA-1 of your signing key (`./gradlew signingReport`) | `GOOGLE_AUDIENCES` includes the **Web** client id; the Android build gets it as `GOOGLE_WEB_CLIENT_ID` in `config.env` |
| Google, iOS | Same place: an **iOS** client with the bundle id | `GOOGLE_AUDIENCES` includes the iOS client id; the iOS build gets it as `GOOGLE_IOS_CLIENT_ID` in `config.env` |

Example: `GOOGLE_AUDIENCES=123-web.apps.googleusercontent.com,123-ios.apps.googleusercontent.com`. Client ids aren't secrets, but they live in `config.env` and build variables alongside the domain.

The default crawl budgets assume the Workers Paid plan. On the Free plan (50 subrequests and 50 D1 queries per invocation), set `CRAWL_REFRESH_BUDGET` to about 2 and `CRAWL_POPULAR_PAGES` to 1.
