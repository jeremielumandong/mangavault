//! Per-user sync: library, reading progress, read chapters, history.
//! Every write is last-write-wins on the client's `updatedAt`.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};
use serde_json::json;
use worker::{Method, Request};

use crate::api::{ApiError, ApiResult, Query, json, no_content, parse_id, summaries};
use crate::auth::User;
use crate::catalog::{self, MangaSummary, SERIES_COLS};
use crate::{App, args, time};

pub async fn route(app: &App, user: User, mut req: Request, rest: &[&str]) -> ApiResult {
    let q = Query::of(&req)?;
    let u = user.id;
    use Method::*;
    match (req.method(), rest) {
        (Get, ["library"]) => library(app, u, q.get("since")).await,
        (Put, ["library", id]) => {
            let body: LibraryPut = body(&mut req).await?;
            library_put(app, u, parse_id(id)?, body).await
        }
        (Delete, ["library", id]) => {
            library_delete(app, u, parse_id(id)?, q.get("updatedAt")).await
        }
        (Get, ["progress"]) => progress(app, u, q.get("since")).await,
        (Put, ["progress"]) => {
            let body: ProgressPut = body(&mut req).await?;
            progress_put(app, u, body).await
        }
        (Get, ["read"]) => read(app, u, &q).await,
        (Put, ["read"]) => {
            let body: ReadPut = body(&mut req).await?;
            read_put(app, u, body).await
        }
        (Get, ["history"]) => history(app, u, &q).await,
        (Delete, ["history"]) => {
            app.db
                .run("DELETE FROM history WHERE user_id = ?", args![u])
                .await?;
            no_content()
        }
        (Delete, ["history", id]) => {
            let id = catalog::resolve_id(&app.db, parse_id(id)?).await?;
            app.db
                .run(
                    "DELETE FROM history WHERE user_id = ? AND series_id = ?",
                    args![u, id],
                )
                .await?;
            no_content()
        }
        (Get, ["continue"]) => continue_reading(app, u).await,
        (Get, ["updates"]) => updates(app, u).await,
        _ => Err(ApiError::not_found("no such /v1/me route")),
    }
}

async fn body<T: for<'a> Deserialize<'a>>(req: &mut Request) -> Result<T, ApiError> {
    req.json()
        .await
        .map_err(|e| ApiError::bad_request(format!("bad JSON body: {e}")))
}

/// A client timestamp in storage format, never later than now (a fast
/// clock would otherwise win every future conflict).
fn client_time(s: Option<&str>) -> Result<String, ApiError> {
    let now = time::now_iso();
    let t = match s {
        Some(s) => time::normalize(s)
            .ok_or_else(|| ApiError::bad_request(format!("bad timestamp {s:?}")))?,
        None => now.clone(),
    };
    Ok(if t > now { now } else { t })
}

fn since(s: Option<&str>) -> Result<String, ApiError> {
    match s {
        Some(s) => {
            time::normalize(s).ok_or_else(|| ApiError::bad_request(format!("bad since {s:?}")))
        }
        None => Ok(String::new()),
    }
}

/// Summaries for a set of series ids, keyed by id.
async fn summaries_by_id(app: &App, ids: &[i64]) -> Result<HashMap<i64, MangaSummary>, ApiError> {
    if ids.is_empty() {
        return Ok(HashMap::new());
    }
    let rows = summaries(
        app,
        &format!(
            "SELECT {SERIES_COLS} FROM series s WHERE s.id IN (SELECT value FROM json_each(?))"
        ),
        args![serde_json::to_string(ids).unwrap()],
    )
    .await?;
    Ok(rows.into_iter().map(|s| (s.id, s)).collect())
}

async fn require_series(app: &App, id: i64) -> Result<i64, ApiError> {
    let id = catalog::resolve_id(&app.db, id).await?;
    let exists: Option<i64> = app
        .db
        .value("SELECT id FROM series WHERE id = ?", args![id], "id")
        .await?;
    exists.ok_or_else(|| ApiError::not_found(format!("manga {id} not found")))
}

// ── Library ────────────────────────────────────────────────────────────

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LibraryPut {
    category: Option<String>,
    updated_at: Option<String>,
}

async fn library(app: &App, u: i64, since_q: Option<&str>) -> ApiResult {
    #[derive(Deserialize)]
    struct Row {
        series_id: i64,
        category: Option<String>,
        added_at: String,
        updated_at: String,
        deleted: i64,
        unread: i64,
    }
    let rows: Vec<Row> = app
        .db
        .all(
            "SELECT l.series_id, l.category, l.added_at, l.updated_at, l.deleted, \
             (SELECT COUNT(*) FROM chapter c WHERE c.series_id = l.series_id AND c.sort > COALESCE( \
                (SELECT pc.sort + CASE WHEN p.completed THEN 0 ELSE -0.0000001 END FROM progress p \
                 JOIN chapter pc ON pc.id = p.chapter_id WHERE p.user_id = l.user_id AND p.series_id = l.series_id), -1e18)) AS unread \
             FROM library l WHERE l.user_id = ? AND l.updated_at > ? AND (l.deleted = 0 OR ? != '') \
             ORDER BY l.updated_at DESC",
            args![u, since(since_q)?, since(since_q)?],
        )
        .await?;
    let ids: Vec<i64> = rows.iter().map(|r| r.series_id).collect();
    let mut manga = summaries_by_id(app, &ids).await?;
    let items: Vec<_> = rows
        .into_iter()
        .filter_map(|r| {
            Some(json!({
                "manga": manga.remove(&r.series_id)?,
                "category": r.category,
                "addedAt": r.added_at,
                "updatedAt": r.updated_at,
                "deleted": r.deleted != 0,
                "unreadCount": r.unread,
            }))
        })
        .collect();
    json(&json!({ "items": items, "serverTime": time::now_iso() }))
}

async fn library_put(app: &App, u: i64, id: i64, body: LibraryPut) -> ApiResult {
    let id = require_series(app, id).await?;
    let t = client_time(body.updated_at.as_deref())?;
    app.db
        .run(
            "INSERT INTO library (user_id, series_id, category, added_at, updated_at, deleted) VALUES (?1, ?2, ?3, ?4, ?4, 0) \
             ON CONFLICT(user_id, series_id) DO UPDATE SET category = excluded.category, updated_at = excluded.updated_at, \
             deleted = 0, added_at = CASE WHEN library.deleted THEN excluded.added_at ELSE library.added_at END \
             WHERE excluded.updated_at >= library.updated_at",
            args![u, id, body.category, &t],
        )
        .await?;
    no_content()
}

async fn library_delete(app: &App, u: i64, id: i64, updated_at: Option<&str>) -> ApiResult {
    let id = catalog::resolve_id(&app.db, id).await?;
    let t = client_time(updated_at)?;
    // A tombstone, so other devices learn about the removal on their next pull.
    app.db
        .run(
            "UPDATE library SET deleted = 1, updated_at = ?3 WHERE user_id = ?1 AND series_id = ?2 AND updated_at <= ?3",
            args![u, id, &t],
        )
        .await?;
    no_content()
}

// ── Progress ───────────────────────────────────────────────────────────

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProgressItem {
    manga_id: i64,
    chapter_id: i64,
    page: i64,
    page_count: Option<i64>,
    #[serde(default)]
    completed: bool,
    updated_at: Option<String>,
}

#[derive(Deserialize)]
struct ProgressPut {
    items: Vec<ProgressItem>,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct ProgressRow {
    #[serde(rename(deserialize = "series_id"))]
    manga_id: i64,
    #[serde(rename(deserialize = "chapter_id"))]
    chapter_id: i64,
    #[serde(rename(deserialize = "number"))]
    chapter_number: Option<f64>,
    page: i64,
    #[serde(rename(deserialize = "page_count"))]
    page_count: Option<i64>,
    #[serde(deserialize_with = "int_bool")]
    completed: bool,
    #[serde(rename(deserialize = "updated_at"))]
    updated_at: String,
}

fn int_bool<'de, D: serde::Deserializer<'de>>(d: D) -> Result<bool, D::Error> {
    Ok(f64::deserialize(d)? != 0.0)
}

const PROGRESS_SELECT: &str = "SELECT p.series_id, p.chapter_id, c.number, p.page, p.page_count, p.completed, p.updated_at \
     FROM progress p LEFT JOIN chapter c ON c.id = p.chapter_id";

async fn progress(app: &App, u: i64, since_q: Option<&str>) -> ApiResult {
    let rows: Vec<ProgressRow> = app
        .db
        .all(
            &format!("{PROGRESS_SELECT} WHERE p.user_id = ? AND p.updated_at > ? ORDER BY p.updated_at DESC"),
            args![u, since(since_q)?],
        )
        .await?;
    json(&json!({ "items": rows, "serverTime": time::now_iso() }))
}

async fn progress_put(app: &App, u: i64, body: ProgressPut) -> ApiResult {
    if body.items.len() > 200 {
        return Err(ApiError::bad_request("at most 200 items per request"));
    }
    let mut accepted = 0;
    for item in body.items {
        // The chapter decides the series, so merged-away ids can't desync them.
        let series_id: Option<i64> = app
            .db
            .value(
                "SELECT series_id FROM chapter WHERE id = ?",
                args![item.chapter_id],
                "series_id",
            )
            .await?;
        let Some(series_id) = series_id else { continue };
        if catalog::resolve_id(&app.db, item.manga_id).await? != series_id {
            continue;
        }
        let t = client_time(item.updated_at.as_deref())?;
        let changed = app
            .db
            .run(
                "INSERT INTO progress (user_id, series_id, chapter_id, page, page_count, completed, updated_at) \
                 VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(user_id, series_id) DO UPDATE SET chapter_id = excluded.chapter_id, \
                 page = excluded.page, page_count = excluded.page_count, completed = excluded.completed, \
                 updated_at = excluded.updated_at WHERE excluded.updated_at > progress.updated_at",
                args![u, series_id, item.chapter_id, item.page.max(0), item.page_count, item.completed, &t],
            )
            .await?;
        if changed == 0 {
            continue;
        }
        accepted += 1;
        let mut stmts = vec![app.db.stmt(
            "INSERT INTO history (user_id, series_id, chapter_id, page, read_at) VALUES (?1, ?2, ?3, ?4, ?5) \
             ON CONFLICT(user_id, series_id) DO UPDATE SET chapter_id = excluded.chapter_id, page = excluded.page, \
             read_at = excluded.read_at WHERE excluded.read_at > history.read_at",
            args![u, series_id, item.chapter_id, item.page.max(0), &t],
        )?];
        if item.completed {
            stmts.push(app.db.stmt(
                "INSERT INTO read_chapter (user_id, chapter_id, series_id, read, updated_at) VALUES (?1, ?2, ?3, 1, ?4) \
                 ON CONFLICT(user_id, chapter_id) DO UPDATE SET read = 1, updated_at = excluded.updated_at \
                 WHERE excluded.updated_at >= read_chapter.updated_at",
                args![u, item.chapter_id, series_id, &t],
            )?);
        }
        app.db.batch(stmts).await?;
    }
    json(&json!({ "accepted": accepted }))
}

// ── Read chapters ──────────────────────────────────────────────────────

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReadPut {
    chapter_ids: Vec<i64>,
    read: bool,
    updated_at: Option<String>,
}

async fn read(app: &App, u: i64, q: &Query) -> ApiResult {
    if let Some(id) = q.get("mangaId") {
        let id = catalog::resolve_id(&app.db, parse_id(id)?).await?;
        #[derive(Deserialize)]
        struct R {
            chapter_id: i64,
        }
        let rows: Vec<R> = app
            .db
            .all(
                "SELECT chapter_id FROM read_chapter WHERE user_id = ? AND series_id = ? AND read = 1",
                args![u, id],
            )
            .await?;
        return json(
            &json!({ "chapterIds": rows.into_iter().map(|r| r.chapter_id).collect::<Vec<_>>() }),
        );
    }
    #[derive(Deserialize)]
    struct R {
        chapter_id: i64,
        series_id: i64,
        read: i64,
        updated_at: String,
    }
    let rows: Vec<R> = app
        .db
        .all(
            "SELECT chapter_id, series_id, read, updated_at FROM read_chapter WHERE user_id = ? AND updated_at > ? \
             ORDER BY updated_at LIMIT 5000",
            args![u, since(q.get("since"))?],
        )
        .await?;
    let items: Vec<_> = rows
        .into_iter()
        .map(|r| json!({ "chapterId": r.chapter_id, "mangaId": r.series_id, "read": r.read != 0, "updatedAt": r.updated_at }))
        .collect();
    json(&json!({ "items": items, "serverTime": time::now_iso() }))
}

async fn read_put(app: &App, u: i64, body: ReadPut) -> ApiResult {
    if body.chapter_ids.len() > 5000 {
        return Err(ApiError::bad_request("at most 5000 chapters per request"));
    }
    let t = client_time(body.updated_at.as_deref())?;
    app.db
        .run(
            "INSERT INTO read_chapter (user_id, chapter_id, series_id, read, updated_at) \
             SELECT ?1, c.id, c.series_id, ?2, ?3 FROM chapter c WHERE c.id IN (SELECT value FROM json_each(?4)) \
             ON CONFLICT(user_id, chapter_id) DO UPDATE SET read = excluded.read, updated_at = excluded.updated_at \
             WHERE excluded.updated_at >= read_chapter.updated_at",
            args![u, body.read, &t, serde_json::to_string(&body.chapter_ids).unwrap()],
        )
        .await?;
    no_content()
}

// ── History, continue reading, updates ─────────────────────────────────

async fn history(app: &App, u: i64, q: &Query) -> ApiResult {
    #[derive(Deserialize)]
    struct R {
        series_id: i64,
        chapter_id: i64,
        number: Option<f64>,
        page: i64,
        read_at: String,
    }
    let limit = q.num("limit", 50, 200);
    let before = match q.get("before") {
        Some(b) => time::normalize(b).ok_or_else(|| ApiError::bad_request("bad before"))?,
        None => "9999".into(),
    };
    let rows: Vec<R> = app
        .db
        .all(
            "SELECT h.series_id, h.chapter_id, c.number, h.page, h.read_at FROM history h \
             LEFT JOIN chapter c ON c.id = h.chapter_id WHERE h.user_id = ? AND h.read_at < ? \
             ORDER BY h.read_at DESC LIMIT ?",
            args![u, before, limit],
        )
        .await?;
    let ids: Vec<i64> = rows.iter().map(|r| r.series_id).collect();
    let mut manga = summaries_by_id(app, &ids).await?;
    let items: Vec<_> = rows
        .into_iter()
        .filter_map(|r| {
            Some(json!({
                "manga": manga.remove(&r.series_id)?,
                "chapterId": r.chapter_id,
                "chapterNumber": r.number,
                "page": r.page,
                "readAt": r.read_at,
            }))
        })
        .collect();
    json(&json!({ "items": items }))
}

async fn continue_reading(app: &App, u: i64) -> ApiResult {
    // Skip finished series: completed on their last chapter.
    let rows: Vec<ProgressRow> = app
        .db
        .all(
            &format!(
                "{PROGRESS_SELECT} WHERE p.user_id = ? AND NOT (p.completed AND NOT EXISTS \
                 (SELECT 1 FROM chapter n WHERE n.series_id = p.series_id AND n.sort > c.sort)) \
                 ORDER BY p.updated_at DESC LIMIT 20"
            ),
            args![u],
        )
        .await?;
    let ids: Vec<i64> = rows.iter().map(|r| r.manga_id).collect();
    let mut manga = summaries_by_id(app, &ids).await?;
    let mut items = Vec::new();
    for p in rows {
        let Some(m) = manga.remove(&p.manga_id) else {
            continue;
        };
        let ch: Option<catalog::ChapterRow> = app
            .db
            .first(
                &format!("SELECT {} FROM chapter WHERE id = ?", catalog::CHAPTER_COLS),
                args![p.chapter_id],
            )
            .await?;
        let Some(ch) = ch else { continue };
        let index: Option<i64> = app
            .db
            .value(
                "SELECT COUNT(*) AS n FROM chapter WHERE series_id = ? AND sort < ?",
                args![ch.series_id, ch.sort],
                "n",
            )
            .await?;
        items.push(
            json!({ "manga": m, "progress": p, "chapter": ch.api(index.unwrap_or(0) as usize) }),
        );
    }
    json(&json!({ "items": items }))
}

async fn updates(app: &App, u: i64) -> ApiResult {
    #[derive(Deserialize)]
    struct R {
        series_id: i64,
        new_chapters: i64,
    }
    let rows: Vec<R> = app
        .db
        .all(
            "SELECT l.series_id, (SELECT COUNT(*) FROM chapter n WHERE n.series_id = l.series_id AND n.sort > pc.sort) AS new_chapters \
             FROM library l JOIN progress p ON p.user_id = l.user_id AND p.series_id = l.series_id \
             JOIN chapter pc ON pc.id = p.chapter_id JOIN series s ON s.id = l.series_id \
             WHERE l.user_id = ? AND l.deleted = 0 AND new_chapters > 0 ORDER BY s.updated_at DESC LIMIT 50",
            args![u],
        )
        .await?;
    let ids: Vec<i64> = rows.iter().map(|r| r.series_id).collect();
    let mut manga = summaries_by_id(app, &ids).await?;
    let items: Vec<_> = rows
        .into_iter()
        .filter_map(|r| {
            Some(json!({ "manga": manga.remove(&r.series_id)?, "newChapters": r.new_chapters }))
        })
        .collect();
    json(&json!({ "items": items }))
}
