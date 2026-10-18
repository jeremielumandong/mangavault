# Mangavault API (v1)

Base URL: `https://${API_DOMAIN}/v1`. `API_DOMAIN` is never committed: it comes from the gitignored `config.env` (see `config.env.example`) or from CI secrets. All responses are JSON (`content-type: application/json`).
Timestamps are ISO 8601 UTC strings. IDs are integers assigned by the backend.

Errors use one shape with a matching HTTP status:

```json
{ "error": { "code": "not_found", "message": "Manga 42 not found" } }
```

Codes: `bad_request` (400), `unauthorized` (401), `not_found` (404), `upstream` (502, a source site failed and nothing is cached), `internal` (500).

Authenticated endpoints take `Authorization: Bearer <token>`. Everything under "Catalog" works without a token.

## One merged catalog

The app shows one catalog, not one per site. The backend crawls MangaDex, MangaPill, AsuraScans, MgEko, Webtoons and Tapas, and merges the same series from different sites into a single **manga** entry (matched on normalized titles and alternative titles). Its chapter list is merged too: one row per chapter number, served by the best available site, with the other sites kept as fallbacks. Clients never need to know which site a chapter comes from; they just send the `imageHeaders` that come with each chapter's pages.

## Models

### MangaSummary

Used in every list.

```json
{
  "id": 42,
  "title": "Solo Leveling",
  "coverUrl": "https://...",
  "coverHeaders": {},
  "type": "manhwa",
  "status": "ongoing",
  "latestChapter": 200.0,
  "updatedAt": "2026-10-15T08:00:00Z"
}
```

- `type`: `manga` | `manhwa` | `manhua` | `comic` | `null`
- `status`: `ongoing` | `completed` | `hiatus` | `cancelled` | `null`
- `latestChapter`: highest known chapter number across all sites, or `null`.
- `coverHeaders`: headers to send when loading `coverUrl` (usually empty).

### MangaDetail

`MangaSummary` plus:

```json
{
  "altTitles": ["Na Honjaman Level-Up"],
  "authors": ["Chugong"],
  "artists": ["DUBU"],
  "description": "E-class hunter ...",
  "genres": ["Action", "Fantasy"],
  "chapterCount": 201,
  "detailsFetchedAt": "2026-10-17T22:00:00Z"
}
```

### Chapter

```json
{
  "id": 9001,
  "mangaId": 42,
  "number": 112.5,
  "title": "The Ash Garden",
  "volume": "12",
  "publishedAt": "2026-10-10T00:00:00Z",
  "index": 113
}
```

`index` is the chapter's position in reading order (0 = first chapter). Use it, not `number`, for previous/next. A chapter `id` is stable even if the backend later switches which site serves it.

### Page

```json
{ "index": 0, "url": "https://...", "width": 720, "height": 4000 }
```

`width`/`height` are `null` when the source doesn't give them; clients should then reserve a 2:3 placeholder (paged) or a tall placeholder (vertical) and fix it once the image header is decoded.

## Catalog

### `GET /v1/genres`
`{ "items": ["Action", "Adventure", ...] }` (all genres seen in the catalog, alphabetical).

### `GET /v1/manga`
Browse the crawled catalog.

| Query | Values | Default |
| --- | --- | --- |
| `sort` | `popular`, `latest` (last chapter update), `new` (first seen), `title` | `popular` |
| `genre` | genre name; repeat for AND | none |
| `excludeGenre` | genre name; repeatable | none |
| `status` | status value | any |
| `type` | type value | any |
| `page` | 1-based | 1 |
| `limit` | 1..100 | 30 |

Response: `{ "items": [MangaSummary], "page": 1, "hasMore": true }`

### `GET /v1/search?q=...`
Same query parameters as `/v1/manga` (default sort is relevance). Searches the catalog first; when fewer than `limit` matches exist and `page=1`, the backend also searches the sources live, stores what it finds, and includes it. Response shape is the same as `/v1/manga`.

### `GET /v1/manga/{id}`
`MangaDetail`. If details were never fetched or are older than 6 hours, the backend refreshes them from every site it knows the series on before answering (on-demand crawl). Sites that fail are skipped and cached data is used.

### `GET /v1/manga/{id}/chapters`
`{ "items": [Chapter] }` in reading order (ascending `index`). Pass `?order=desc` for newest first. Refreshed together with details.

### `GET /v1/chapters/{id}/pages`
```json
{
  "chapter": Chapter,
  "pages": [Page],
  "imageHeaders": { "Referer": "..." },
  "prevChapterId": 9000,
  "nextChapterId": 9002
}
```
Page links are cached; fetched from the site on first request. If the preferred site fails, the backend tries the next site that has this chapter number before returning `upstream`. `imageHeaders` apply to every page in the response. `prevChapterId`/`nextChapterId` are `null` at either end. Clients should prefetch the next chapter's pages near the end of a chapter.

### `GET /v1/home`
One request for the Home screen's discover rows.
```json
{
  "popular": [MangaSummary],
  "latest": [MangaSummary],
  "new": [MangaSummary]
}
```
Each row holds up to 20 items. Personal rows (continue reading, updated favorites) come from `/v1/me/...` endpoints below.

### `GET /v1/stats`
How big the catalog is, counted the way the lists count it (series that have chapters or haven't been fetched yet).
```json
{ "series": 12345, "chapters": 456789, "latestUpdateAt": "2026-10-18T08:00:00Z" }
```
`latestUpdateAt` is the newest chapter update in the catalog, or `null`. Cached for 5 minutes.

## Accounts

### `POST /v1/auth/apple`
Body: `{ "identityToken": "<JWT from Sign in with Apple>", "name": "optional display name" }`

### `POST /v1/auth/google`
Body: `{ "idToken": "<Google ID token>" }`

Both verify the token's signature against the provider's public keys and its audience against the backend's configured client IDs, then return:

```json
{ "token": "<opaque bearer token>", "user": { "id": 1, "name": "Jeremie", "email": "x@y.z" } }
```

Tokens last 180 days.

### `POST /v1/auth/dev`
Local development only (enabled by `DEV_AUTH=true`): body `{ "name": "Test" }`, same response as above.

### `POST /v1/auth/open`
Single-user mode only (enabled by `AUTH_MODE=open`): body `{}`. Signs in as the one owner account with no credentials, same response as above. Apps built with `AUTH_MODE=open` call it on launch and hide sign-in. Anyone who can reach the API can then read and change the owner's library, so use it only on a personal deployment.

### `POST /v1/auth/logout`
Revokes the current token. `204`.

### `POST /v1/admin/crawl?job=latest|popular`
Operator only: runs a crawl now instead of waiting for the cron. Needs `Authorization: Bearer <ADMIN_TOKEN>` (a Worker secret); returns 404 when `ADMIN_TOKEN` is unset. Response: `{ "job": "popular", "series": 120 }`.

### `GET /v1/me`
`{ "user": { "id": 1, "name": "...", "email": "..." } }`

## Sync (all require a token)

Sync is last-write-wins per item using the client's `updatedAt`. Clients keep a local copy, push changes, and pull with `since`.

### Library (favorites)

`GET /v1/me/library?since=<iso>` →
```json
{
  "items": [
    {
      "manga": MangaSummary,
      "category": "Reading",
      "addedAt": "...",
      "updatedAt": "...",
      "deleted": false,
      "unreadCount": 3
    }
  ],
  "serverTime": "..."
}
```
With `since`, includes removed items (`deleted: true`) changed after that time. `category` is free text (the app offers Reading, Plan to read, Completed, plus custom ones), or `null`.

`PUT /v1/me/library/{mangaId}` body `{ "category": "Reading", "updatedAt": "..." }` → `204`

`DELETE /v1/me/library/{mangaId}?updatedAt=<iso>` → `204`

### Reading progress

`GET /v1/me/progress?since=<iso>` →
```json
{
  "items": [
    {
      "mangaId": 42,
      "chapterId": 9001,
      "chapterNumber": 112.0,
      "page": 14,
      "pageCount": 38,
      "completed": false,
      "hidden": false,
      "updatedAt": "..."
    }
  ],
  "serverTime": "..."
}
```
One row per manga: where the user is now. `hidden` means the user removed it from Continue reading; the next read clears it.

`PUT /v1/me/progress` body `{ "items": [ { "mangaId", "chapterId", "page", "pageCount", "completed", "hidden", "updatedAt" } ] }` → `{ "accepted": 3 }`. Rows older than the stored `updatedAt` are ignored. Each accepted row that isn't `hidden` also writes a history entry and marks chapters as read when `completed` is true. `hidden` defaults to `false`.

### Read chapters

`GET /v1/me/read?mangaId=42` → `{ "chapterIds": [9000, 9001] }`

`GET /v1/me/read?since=<iso>` (no `mangaId`) → every read-state change after `since`, for a full sync:
`{ "items": [ { "chapterId": 9001, "mangaId": 42, "read": true, "updatedAt": "..." } ], "serverTime": "..." }`

`PUT /v1/me/read` body `{ "chapterIds": [1,2,3], "read": true, "updatedAt": "..." }` → `204`. Used for "mark read/unread" and "mark all previous as read".

### History

`GET /v1/me/history?limit=50&before=<iso>` →
```json
{ "items": [ { "manga": MangaSummary, "chapterId": 9001, "chapterNumber": 112.0, "page": 14, "readAt": "..." } ] }
```
One entry per manga (latest read), newest first.

`DELETE /v1/me/history/{mangaId}` and `DELETE /v1/me/history` (clear all) → `204`.

### Continue reading and updates

`GET /v1/me/continue` → `{ "items": [ { "manga": MangaSummary, "progress": Progress, "chapter": Chapter } ] }` (up to 20, most recent first, excluding hidden rows and completed series with nothing new).

`GET /v1/me/updates` → `{ "items": [ { "manga": MangaSummary, "newChapters": 3 } ] }` favorites with chapters newer than the user's progress, newest update first.
