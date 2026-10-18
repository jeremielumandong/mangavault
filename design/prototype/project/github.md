repo: jeremielumandong/mangavault
branch: claude/project-thread-i3bj12

## Last sync
date: 2026-10-18T00:08:56Z

### Updated in this project
- Aligned prototype with docs/api.md: one merged catalog, sort/status/type values, live search fallback
- Library categories are free text (custom categories), set per title from Details
- On-demand details refresh indicator and chapter-level `upstream` error state in the reader
- Sync merge copy matches last-write-wins

## Screen map
| Screen | Repo files |
| --- | --- |
| Home | docs/api.md (GET /v1/home, /v1/me/continue, /v1/me/updates) |
| Search + filters | docs/api.md (GET /v1/search, /v1/manga, /v1/genres) |
| Manga details | docs/api.md (GET /v1/manga/{id}, /chapters), backend/migrations/0001_init.sql |
| Library | docs/api.md (/v1/me/library) |
| History | docs/api.md (/v1/me/history) |
| Reader | docs/api.md (GET /v1/chapters/{id}/pages, /v1/me/progress, /v1/me/read) |
| Sign-in / merge | docs/api.md (/v1/auth/*) |
