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

1. `npx wrangler login`, then `npx wrangler d1 create mangavault`.
2. Copy `../config.env.example` to `../config.env` and set `API_DOMAIN` (a hostname on a zone in your Cloudflare account) and `D1_DATABASE_ID`.
3. Set `APPLE_AUDIENCES` (your iOS bundle id) and `GOOGLE_AUDIENCES` (your Google OAuth client ids) in `wrangler.toml` `[vars]`.
4. `npm run deploy`. This applies migrations, deploys, and attaches the custom domain.

The default crawl budgets assume the Workers Paid plan. On the Free plan (50 subrequests and 50 D1 queries per invocation), set `CRAWL_REFRESH_BUDGET` to about 2 and `CRAWL_POPULAR_PAGES` to 1.
