//! HTTP layer: routing, errors, and the public catalog endpoints.

use std::collections::HashMap;

use chrono::Duration;
use serde::Serialize;
use serde_json::json;
use worker::{Method, Request, Response};

use crate::args;
use crate::catalog::{self, CHAPTER_COLS, Chapter, MangaSummary, SERIES_COLS, SeriesRow};
use crate::db::Arg;
use crate::{App, auth, crawl, sync, time};

pub struct ApiError {
    pub status: u16,
    pub code: &'static str,
    pub message: String,
}

pub type ApiResult = std::result::Result<Response, ApiError>;

impl ApiError {
    pub fn bad_request(m: impl Into<String>) -> Self {
        ApiError {
            status: 400,
            code: "bad_request",
            message: m.into(),
        }
    }
    pub fn unauthorized(m: impl Into<String>) -> Self {
        ApiError {
            status: 401,
            code: "unauthorized",
            message: m.into(),
        }
    }
    pub fn not_found(m: impl Into<String>) -> Self {
        ApiError {
            status: 404,
            code: "not_found",
            message: m.into(),
        }
    }
    pub fn upstream(m: impl Into<String>) -> Self {
        ApiError {
            status: 502,
            code: "upstream",
            message: m.into(),
        }
    }

    pub fn into_response(self) -> worker::Result<Response> {
        Ok(Response::from_json(
            &json!({ "error": { "code": self.code, "message": self.message } }),
        )?
        .with_status(self.status))
    }
}

impl From<worker::Error> for ApiError {
    fn from(e: worker::Error) -> Self {
        worker::console_error!("internal error: {e}");
        ApiError {
            status: 500,
            code: "internal",
            message: "internal error".into(),
        }
    }
}

pub fn json<T: Serialize>(value: &T) -> ApiResult {
    Ok(Response::from_json(value)?)
}

/// Catalog reads are the same for everyone; let edges and clients reuse them briefly.
fn public_json<T: Serialize>(value: &T, max_age: u32) -> ApiResult {
    let mut res = Response::from_json(value)?;
    res.headers_mut()
        .set("Cache-Control", &format!("public, max-age={max_age}"))?;
    Ok(res)
}

pub fn no_content() -> ApiResult {
    Ok(Response::empty()?.with_status(204))
}

/// Query string helpers.
pub struct Query(Vec<(String, String)>);

impl Query {
    pub fn of(req: &Request) -> std::result::Result<Self, ApiError> {
        let url = req.url().map_err(|_| ApiError::bad_request("bad url"))?;
        Ok(Query(
            url.query_pairs()
                .map(|(k, v)| (k.into_owned(), v.into_owned()))
                .collect(),
        ))
    }
    pub fn get(&self, key: &str) -> Option<&str> {
        self.0
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
            .filter(|v| !v.is_empty())
    }
    pub fn all(&self, key: &str) -> Vec<&str> {
        self.0
            .iter()
            .filter(|(k, v)| k == key && !v.is_empty())
            .map(|(_, v)| v.as_str())
            .collect()
    }
    pub fn num(&self, key: &str, default: u32, max: u32) -> u32 {
        self.get(key)
            .and_then(|v| v.parse().ok())
            .unwrap_or(default)
            .clamp(1, max)
    }
}

pub fn parse_id(s: &str) -> std::result::Result<i64, ApiError> {
    s.parse()
        .map_err(|_| ApiError::bad_request(format!("bad id {s:?}")))
}

pub async fn route(app: &App, mut req: Request) -> ApiResult {
    let url = req.url().map_err(|_| ApiError::bad_request("bad url"))?;
    let path: Vec<String> = url
        .path_segments()
        .map(|s| s.filter(|p| !p.is_empty()).map(str::to_string).collect())
        .unwrap_or_default();
    let p: Vec<&str> = path.iter().map(String::as_str).collect();
    let method = req.method();

    use Method::*;
    match (method, p.as_slice()) {
        (Get, []) | (Get, ["v1"]) => {
            json(&json!({ "name": "mangavault", "version": env!("CARGO_PKG_VERSION") }))
        }
        (Get, ["v1", "genres"]) => genres(app).await,
        (Get, ["v1", "home"]) => home(app).await,
        (Get, ["v1", "stats"]) => stats(app).await,
        (Get, ["v1", "manga"]) => browse(app, &Query::of(&req)?).await,
        (Get, ["v1", "search"]) => search(app, &Query::of(&req)?).await,
        (Get, ["v1", "manga", id]) => manga(app, parse_id(id)?).await,
        (Get, ["v1", "manga", id, "chapters"]) => {
            manga_chapters(app, parse_id(id)?, &Query::of(&req)?).await
        }
        (Get, ["v1", "chapters", id, "pages"]) => pages(app, parse_id(id)?).await,

        (Post, ["v1", "admin", "crawl"]) => admin_crawl(app, &req, &Query::of(&req)?).await,

        (Post, ["v1", "auth", "logout"]) => auth::logout(app, &req).await,
        (Post, ["v1", "auth", provider]) => auth::sign_in(app, provider, &mut req).await,
        (Get, ["v1", "me"]) => auth::me(app, &req).await,
        (_, ["v1", "me", rest @ ..]) => {
            let user = auth::require_user(app, &req).await?;
            sync::route(app, user, req, rest).await
        }
        _ => Err(ApiError::not_found(format!("no route for {}", url.path()))),
    }
}

// ── Admin ──────────────────────────────────────────────────────────────

/// Runs a crawl now instead of waiting for the cron: `?job=latest` (default)
/// or `?job=popular`. Needs `Authorization: Bearer <ADMIN_TOKEN>`.
async fn admin_crawl(app: &App, req: &Request, q: &Query) -> ApiResult {
    let Some(expected) = app.config.admin_token.as_deref() else {
        return Err(ApiError::not_found("admin endpoints are disabled"));
    };
    let given = auth::bearer(req).unwrap_or_default();
    if !constant_time_eq(given.as_bytes(), expected.as_bytes()) {
        return Err(ApiError::unauthorized("bad admin token"));
    }
    let (job, cron) = match q.get("job").unwrap_or("latest") {
        "latest" => ("latest", crawl::LATEST_CRON),
        "popular" => ("popular", crawl::POPULAR_CRON),
        other => return Err(ApiError::bad_request(format!("unknown job {other}"))),
    };
    crawl::run(app, cron).await;
    let series: Option<i64> = app
        .db
        .value("SELECT COUNT(*) AS n FROM series", args![], "n")
        .await?;
    json(&json!({ "job": job, "series": series.unwrap_or(0) }))
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

// ── Catalog ────────────────────────────────────────────────────────────

async fn genres(app: &App) -> ApiResult {
    #[derive(serde::Deserialize)]
    struct G {
        genre: String,
    }
    let rows: Vec<G> = app
        .db
        .all("SELECT genre FROM series_genre GROUP BY genre HAVING COUNT(*) >= 3 ORDER BY genre COLLATE NOCASE", args![])
        .await?;
    public_json(
        &json!({ "items": rows.into_iter().map(|g| g.genre).collect::<Vec<_>>() }),
        3600,
    )
}

pub async fn summaries(
    app: &App,
    sql: &str,
    args: Vec<Arg>,
) -> std::result::Result<Vec<MangaSummary>, ApiError> {
    let rows: Vec<SeriesRow> = app.db.all(sql, args).await?;
    Ok(rows.iter().map(SeriesRow::summary).collect())
}

/// Series seen in a site's list but not fetched yet are listed too: opening
/// one fetches it on demand. Fetched series without chapters stay out.
const LISTABLE: &str = "(s.chapter_count > 0 OR s.details_fetched_at IS NULL)";

async fn home(app: &App) -> ApiResult {
    let row = |order: &str| {
        format!("SELECT {SERIES_COLS} FROM series s WHERE {LISTABLE} ORDER BY {order} LIMIT 20")
    };
    let popular = summaries(app, &row("s.popularity DESC"), args![]).await?;
    let latest = summaries(app, &row("s.updated_at DESC NULLS LAST"), args![]).await?;
    let new = summaries(app, &row("s.first_seen_at DESC"), args![]).await?;
    public_json(
        &json!({ "popular": popular, "latest": latest, "new": new }),
        120,
    )
}

/// How big the catalog is, counted like the lists count it.
async fn stats(app: &App) -> ApiResult {
    #[derive(serde::Deserialize)]
    struct S {
        series: i64,
        chapters: Option<i64>,
        latest: Option<String>,
    }
    let s: Option<S> = app
        .db
        .first(
            &format!(
                "SELECT COUNT(*) AS series, SUM(s.chapter_count) AS chapters, MAX(s.updated_at) AS latest \
                 FROM series s WHERE {LISTABLE}"
            ),
            args![],
        )
        .await?;
    let (series, chapters, latest) = s.map_or((0, 0, None), |s| {
        (s.series, s.chapters.unwrap_or(0), s.latest)
    });
    public_json(
        &json!({ "series": series, "chapters": chapters, "latestUpdateAt": latest }),
        300,
    )
}

/// WHERE clauses and args shared by browse and search.
fn filters(q: &Query) -> (Vec<String>, Vec<Arg>) {
    let mut wh = Vec::new();
    let mut a = Vec::new();
    for g in q.all("genre") {
        wh.push(
            "s.id IN (SELECT series_id FROM series_genre WHERE genre = ? COLLATE NOCASE)"
                .to_string(),
        );
        a.push(Arg::from(g));
    }
    for g in q.all("excludeGenre") {
        wh.push(
            "s.id NOT IN (SELECT series_id FROM series_genre WHERE genre = ? COLLATE NOCASE)"
                .to_string(),
        );
        a.push(Arg::from(g));
    }
    if let Some(st) = q.get("status") {
        wh.push("s.status = ?".into());
        a.push(Arg::from(st));
    }
    if let Some(t) = q.get("type") {
        wh.push("s.kind = ?".into());
        a.push(Arg::from(t));
    }
    (wh, a)
}

fn order_by(sort: Option<&str>) -> std::result::Result<&'static str, ApiError> {
    Ok(match sort {
        None | Some("popular") => "s.popularity DESC",
        Some("latest") => "s.updated_at DESC NULLS LAST",
        Some("new") => "s.first_seen_at DESC",
        Some("title") => "s.title COLLATE NOCASE",
        Some(other) => return Err(ApiError::bad_request(format!("unknown sort {other:?}"))),
    })
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ListPage {
    items: Vec<MangaSummary>,
    page: u32,
    has_more: bool,
}

async fn browse(app: &App, q: &Query) -> ApiResult {
    let page = q.num("page", 1, 10_000);
    let limit = q.num("limit", 30, 100);
    let (mut wh, mut a) = filters(q);
    wh.push(LISTABLE.into());
    let sql = format!(
        "SELECT {SERIES_COLS} FROM series s WHERE {} ORDER BY {} LIMIT ? OFFSET ?",
        wh.join(" AND "),
        order_by(q.get("sort"))?
    );
    a.push(Arg::from(limit + 1));
    a.push(Arg::from((page - 1) * limit));
    let mut items = summaries(app, &sql, a).await?;
    let has_more = items.len() > limit as usize;
    items.truncate(limit as usize);
    public_json(
        &ListPage {
            items,
            page,
            has_more,
        },
        120,
    )
}

/// Turn free text into an FTS5 prefix query: `solo lev` → `"solo"* "lev"*`.
pub fn fts_query(q: &str) -> Option<String> {
    let terms: Vec<String> = q
        .split(|c: char| !c.is_alphanumeric())
        .filter(|t| !t.is_empty())
        .take(8)
        .map(|t| format!("\"{}\"*", t.to_lowercase()))
        .collect();
    (!terms.is_empty()).then(|| terms.join(" "))
}

async fn search(app: &App, q: &Query) -> ApiResult {
    let text = q
        .get("q")
        .ok_or_else(|| ApiError::bad_request("missing q"))?;
    let Some(fts) = fts_query(text) else {
        return json(&ListPage {
            items: vec![],
            page: 1,
            has_more: false,
        });
    };
    let page = q.num("page", 1, 1000);
    let limit = q.num("limit", 30, 100);
    let order = match q.get("sort") {
        None | Some("relevance") => "bm25(series_fts, 10.0, 3.0)",
        other => order_by(other)?,
    };
    let run = |fts: String| {
        let (mut wh, mut a) = filters(q);
        wh.insert(0, "series_fts MATCH ?".into());
        a.insert(0, Arg::from(fts));
        a.push(Arg::from(limit + 1));
        a.push(Arg::from((page - 1) * limit));
        let sql = format!(
            "SELECT {SERIES_COLS} FROM series s JOIN series_fts ON series_fts.rowid = s.id WHERE {} ORDER BY {order} LIMIT ? OFFSET ?",
            wh.join(" AND ")
        );
        (sql, a)
    };

    let (sql, a) = run(fts.clone());
    let mut items = summaries(app, &sql, a).await?;
    if page == 1 && items.len() < limit as usize && should_live_search(app, text).await? {
        if catalog::live_search(&app.db, &app.http, text).await? > 0 {
            let (sql, a) = run(fts);
            items = summaries(app, &sql, a).await?;
        }
    }
    let has_more = items.len() > limit as usize;
    items.truncate(limit as usize);
    json(&ListPage {
        items,
        page,
        has_more,
    })
}

/// Ask the sites about a given query at most once a day.
async fn should_live_search(app: &App, text: &str) -> std::result::Result<bool, ApiError> {
    let key = format!("search:{}", text.trim().to_lowercase());
    let last: Option<String> = app
        .db
        .value(
            "SELECT value FROM crawl_state WHERE key = ?",
            args![&key],
            "value",
        )
        .await?;
    if last.is_some_and(|t| t > time::ago_iso(Duration::hours(24))) {
        return Ok(false);
    }
    app.db
        .run(
            "INSERT OR REPLACE INTO crawl_state (key, value) VALUES (?, ?)",
            args![&key, time::now_iso()],
        )
        .await?;
    Ok(true)
}

/// Load a series, refreshing it from the sites first when stale.
async fn fresh_series(app: &App, id: i64) -> std::result::Result<SeriesRow, ApiError> {
    let s = catalog::series(&app.db, id)
        .await?
        .ok_or_else(|| ApiError::not_found(format!("manga {id} not found")))?;
    if !catalog::is_stale(s.details_fetched_at.as_deref()) {
        return Ok(s);
    }
    match catalog::refresh_series(&app.db, &app.http, s.id).await {
        Ok(_) => Ok(catalog::series(&app.db, s.id).await?.unwrap_or(s)),
        Err(e) => {
            worker::console_warn!("on-demand refresh of {} failed: {e}", s.id);
            Ok(s)
        }
    }
}

async fn manga(app: &App, id: i64) -> ApiResult {
    let s = fresh_series(app, id).await?;
    public_json(&s.detail(), 60)
}

async fn manga_chapters(app: &App, id: i64, q: &Query) -> ApiResult {
    let s = fresh_series(app, id).await?;
    let rows = catalog::chapters(&app.db, s.id).await?;
    let mut items: Vec<Chapter> = rows.iter().enumerate().map(|(i, c)| c.api(i)).collect();
    if q.get("order") == Some("desc") {
        items.reverse();
    }
    public_json(&json!({ "items": items }), 60)
}

async fn pages(app: &App, chapter_id: i64) -> ApiResult {
    let ch: catalog::ChapterRow = app
        .db
        .first(
            &format!("SELECT {CHAPTER_COLS} FROM chapter WHERE id = ?"),
            args![chapter_id],
        )
        .await?
        .ok_or_else(|| ApiError::not_found(format!("chapter {chapter_id} not found")))?;

    let neighbor = |cmp: &str, dir: &str| {
        format!(
            "SELECT id FROM chapter WHERE series_id = ? AND sort {cmp} ? ORDER BY sort {dir} LIMIT 1"
        )
    };
    let prev: Option<i64> = app
        .db
        .value(&neighbor("<", "DESC"), args![ch.series_id, ch.sort], "id")
        .await?;
    let next: Option<i64> = app
        .db
        .value(&neighbor(">", "ASC"), args![ch.series_id, ch.sort], "id")
        .await?;
    let index: Option<i64> = app
        .db
        .value(
            "SELECT COUNT(*) AS n FROM chapter WHERE series_id = ? AND sort < ?",
            args![ch.series_id, ch.sort],
            "n",
        )
        .await?;

    let got = catalog::chapter_pages(&app.db, &app.http, &ch)
        .await
        .map_err(ApiError::upstream)?;
    let headers: HashMap<&str, &str> = got.source.image_headers().iter().copied().collect();
    json(&json!({
        "chapter": ch.api(index.unwrap_or(0) as usize),
        "pages": got.pages,
        "imageHeaders": headers,
        "prevChapterId": prev,
        "nextChapterId": next,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fts_queries_are_quoted_prefixes() {
        assert_eq!(fts_query("Solo  Lev").unwrap(), "\"solo\"* \"lev\"*");
        assert_eq!(fts_query("re:zero").unwrap(), "\"re\"* \"zero\"*");
        assert_eq!(fts_query("\"* OR"), Some("\"or\"*".into()));
        assert_eq!(fts_query("  -- "), None);
    }
}
