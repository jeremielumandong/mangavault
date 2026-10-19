//! The merged catalog: storing what the crawler finds, merging one series
//! across sites, and serving chapter pages with fallback between sites.

use std::collections::{HashMap, HashSet};

use chrono::Duration;
use futures::future::join_all;
use mangavault_core::matching::{merge_chapters, title_key, title_keys};
use mangavault_core::{ChapterEntry, Http, Kind, MangaDetails, MangaEntry, PageImage, SourceId};
use serde::{Deserialize, Serialize};
use worker::Result;

use crate::args;
use crate::db::{Db, json_col};
use crate::time;

/// Details older than this are refetched when someone opens the series.
pub const DETAILS_TTL_HOURS: i64 = 6;
/// Cached page links older than this are refetched (sites rotate CDNs).
pub const PAGES_TTL_DAYS: i64 = 7;

#[derive(Debug, Clone, Deserialize)]
pub struct SeriesRow {
    pub id: i64,
    pub title: String,
    pub alt_titles: String,
    pub cover_url: Option<String>,
    pub cover_source: Option<String>,
    pub description: Option<String>,
    pub authors: String,
    pub artists: String,
    pub genres: String,
    pub status: Option<String>,
    pub kind: Option<String>,
    pub latest_chapter: Option<f64>,
    pub chapter_count: i64,
    pub updated_at: Option<String>,
    pub details_fetched_at: Option<String>,
}

pub const SERIES_COLS: &str = "s.id, s.title, s.alt_titles, s.cover_url, s.cover_source, s.description, s.authors, \
     s.artists, s.genres, s.status, s.kind, s.latest_chapter, s.chapter_count, s.updated_at, s.details_fetched_at";

#[derive(Debug, Clone, Deserialize)]
struct EntryRow {
    id: i64,
    source: String,
    source_id: String,
    title: String,
    cover_url: Option<String>,
    details: Option<String>,
    chapters: Option<String>,
    fetched_at: Option<String>,
}

const ENTRY_COLS: &str = "id, source, source_id, title, cover_url, details, chapters, fetched_at";

#[derive(Debug, Clone, Deserialize)]
pub struct ChapterRow {
    pub id: i64,
    pub series_id: i64,
    pub sort: f64,
    pub number: Option<f64>,
    pub title: Option<String>,
    pub volume: Option<String>,
    pub published_at: Option<String>,
    pub candidates: String,
    pub pages: Option<String>,
    pub pages_source: Option<String>,
    pub pages_fetched_at: Option<String>,
}

pub const CHAPTER_COLS: &str = "id, series_id, sort, number, title, volume, published_at, candidates, pages, pages_source, pages_fetched_at";

// ── API shapes ─────────────────────────────────────────────────────────

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MangaSummary {
    pub id: i64,
    pub title: String,
    pub cover_url: Option<String>,
    pub cover_headers: HashMap<&'static str, &'static str>,
    #[serde(rename = "type")]
    pub kind: Option<String>,
    pub status: Option<String>,
    pub latest_chapter: Option<f64>,
    pub updated_at: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MangaDetail {
    #[serde(flatten)]
    pub summary: MangaSummary,
    pub alt_titles: Vec<String>,
    pub authors: Vec<String>,
    pub artists: Vec<String>,
    pub description: Option<String>,
    pub genres: Vec<String>,
    pub chapter_count: i64,
    pub details_fetched_at: Option<String>,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Chapter {
    pub id: i64,
    pub manga_id: i64,
    pub number: Option<f64>,
    pub title: Option<String>,
    pub volume: Option<String>,
    pub published_at: Option<String>,
    pub index: usize,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct Page {
    pub index: usize,
    pub url: String,
    pub width: Option<u32>,
    pub height: Option<u32>,
}

pub fn headers_for(source: Option<&str>) -> HashMap<&'static str, &'static str> {
    source
        .and_then(SourceId::parse)
        .map(|s| s.image_headers().iter().copied().collect())
        .unwrap_or_default()
}

impl SeriesRow {
    pub fn summary(&self) -> MangaSummary {
        MangaSummary {
            id: self.id,
            title: self.title.clone(),
            cover_url: self.cover_url.clone(),
            cover_headers: headers_for(self.cover_source.as_deref()),
            kind: self.kind.clone(),
            status: self.status.clone(),
            latest_chapter: self.latest_chapter,
            updated_at: self.updated_at.clone(),
        }
    }

    pub fn detail(&self) -> MangaDetail {
        MangaDetail {
            summary: self.summary(),
            alt_titles: json_col(&Some(self.alt_titles.clone())),
            authors: json_col(&Some(self.authors.clone())),
            artists: json_col(&Some(self.artists.clone())),
            description: self.description.clone(),
            genres: json_col(&Some(self.genres.clone())),
            chapter_count: self.chapter_count,
            details_fetched_at: self.details_fetched_at.clone(),
        }
    }
}

impl ChapterRow {
    pub fn api(&self, index: usize) -> Chapter {
        Chapter {
            id: self.id,
            manga_id: self.series_id,
            number: self.number,
            title: self.title.clone(),
            volume: self.volume.clone(),
            published_at: self.published_at.clone(),
            index,
        }
    }
}

// ── Reads ──────────────────────────────────────────────────────────────

/// A series by id, following ids of series that were merged into another.
pub async fn series(db: &Db, id: i64) -> Result<Option<SeriesRow>> {
    db.first(
        &format!(
            "SELECT {SERIES_COLS} FROM series s WHERE s.id = COALESCE((SELECT series_id FROM series_alias WHERE old_id = ?1), ?1)"
        ),
        args![id],
    )
    .await
}

/// Resolve a possibly merged-away id to the live series id.
pub async fn resolve_id(db: &Db, id: i64) -> Result<i64> {
    Ok(db
        .value::<i64>(
            "SELECT series_id FROM series_alias WHERE old_id = ?",
            args![id],
            "series_id",
        )
        .await?
        .unwrap_or(id))
}

pub async fn chapters(db: &Db, series_id: i64) -> Result<Vec<ChapterRow>> {
    db.all(
        &format!("SELECT {CHAPTER_COLS} FROM chapter WHERE series_id = ? ORDER BY sort"),
        args![series_id],
    )
    .await
}

async fn entries(db: &Db, series_id: i64) -> Result<Vec<EntryRow>> {
    db.all(
        &format!("SELECT {ENTRY_COLS} FROM entry WHERE series_id = ?"),
        args![series_id],
    )
    .await
}

// ── Writes from list crawls ─────────────────────────────────────────────

pub struct ListUpsert<'a> {
    pub source: SourceId,
    pub items: &'a [MangaEntry],
    /// Popularity score of the first item; each later item scores one less.
    pub popularity_start: Option<f64>,
    /// Queue every item for a details refresh (they just had an update).
    pub mark_refresh: bool,
}

/// Store a page of list results: known entries get their title, cover and
/// popularity updated; new ones join the series whose title key matches,
/// or start a new series.
pub async fn upsert_list(db: &Db, up: ListUpsert<'_>) -> Result<Vec<i64>> {
    if up.items.is_empty() {
        return Ok(vec![]);
    }
    let source = up.source.as_str();
    let ids: Vec<&str> = up.items.iter().map(|m| m.source_id.as_str()).collect();
    #[derive(Deserialize)]
    struct Known {
        id: i64,
        series_id: i64,
        source_id: String,
    }
    let known: Vec<Known> = db
        .all(
            "SELECT id, series_id, source_id FROM entry WHERE source = ? AND source_id IN (SELECT value FROM json_each(?))",
            args![source, serde_json::to_string(&ids).unwrap()],
        )
        .await?;
    let known: HashMap<String, (i64, i64)> = known
        .into_iter()
        .map(|k| (k.source_id, (k.id, k.series_id)))
        .collect();

    let mut series_ids = Vec::with_capacity(up.items.len());
    let mut stmts = Vec::new();
    for (rank, item) in up.items.iter().enumerate() {
        let pop = up.popularity_start.map(|p| p - rank as f64);
        let series_id = match known.get(&item.source_id) {
            Some(&(entry_id, series_id)) => {
                stmts.push(db.stmt(
                    "UPDATE entry SET title = ?, cover_url = COALESCE(?, cover_url), popularity = MAX(popularity, ?), \
                     needs_refresh = MAX(needs_refresh, ?) WHERE id = ?",
                    args![&item.title, item.cover_url.clone(), pop.unwrap_or(0.0), up.mark_refresh, entry_id],
                )?);
                series_id
            }
            None => {
                let series_id = match find_series_by_keys(db, &title_keys([item.title.as_str()]))
                    .await?
                {
                    Some(id) => id,
                    None => {
                        create_series(db, &item.title, item.cover_url.as_deref(), up.source).await?
                    }
                };
                db.run(
                    "INSERT INTO entry (series_id, source, source_id, title, cover_url, popularity, needs_refresh) \
                     VALUES (?, ?, ?, ?, ?, ?, 1) ON CONFLICT(source, source_id) DO NOTHING",
                    args![series_id, source, &item.source_id, &item.title, item.cover_url.clone(), pop.unwrap_or(0.0)],
                )
                .await?;
                series_id
            }
        };
        if let Some(p) = pop {
            stmts.push(db.stmt(
                "UPDATE series SET popularity = MAX(popularity, ?) WHERE id = ?",
                args![p, series_id],
            )?);
        }
        series_ids.push(series_id);
    }
    db.batch(stmts).await?;
    series_ids.dedup();
    Ok(series_ids)
}

async fn find_series_by_keys(db: &Db, keys: &[String]) -> Result<Option<i64>> {
    if keys.is_empty() {
        return Ok(None);
    }
    db.value(
        "SELECT series_id FROM series_key WHERE key IN (SELECT value FROM json_each(?)) LIMIT 1",
        args![serde_json::to_string(keys).unwrap()],
        "series_id",
    )
    .await
}

async fn create_series(db: &Db, title: &str, cover: Option<&str>, source: SourceId) -> Result<i64> {
    let now = time::now_iso();
    let id = db
        .insert(
            "INSERT INTO series (title, cover_url, cover_source, first_seen_at) VALUES (?, ?, ?, ?)",
            args![title, cover, source.as_str(), &now],
        )
        .await?;
    let mut stmts = vec![db.stmt(
        "INSERT INTO series_fts (rowid, title, alt_titles) VALUES (?, ?, '')",
        args![id, title],
    )?];
    if let Some(key) = title_key(title) {
        stmts.push(db.stmt(
            "INSERT OR IGNORE INTO series_key (key, series_id) VALUES (?, ?)",
            args![key, id],
        )?);
    }
    db.batch(stmts).await?;
    Ok(id)
}

// ── Refreshing and merging one series ───────────────────────────────────

pub fn is_stale(fetched_at: Option<&str>) -> bool {
    match fetched_at {
        None => true,
        Some(t) => t < time::ago_iso(Duration::hours(DETAILS_TTL_HOURS)).as_str(),
    }
}

/// Fetch every site's copy of a series, absorb any other series that turn
/// out to be the same (by alt titles), and rebuild the merged record.
/// Sites that fail keep their previous data. Returns the surviving id.
pub async fn refresh_series<H: Http>(db: &Db, http: &H, series_id: i64) -> Result<i64> {
    let list = entries(db, series_id).await?;
    fetch_entries(db, http, &list).await?;

    // New alt titles may reveal that another series is this one.
    let mut absorbed = Vec::new();
    for e in entries(db, series_id).await? {
        let Some(details) = e
            .details
            .as_deref()
            .and_then(|d| serde_json::from_str::<MangaDetails>(d).ok())
        else {
            continue;
        };
        let titles = std::iter::once(details.title.as_str())
            .chain(details.alt_titles.iter().map(String::as_str));
        for key in title_keys(titles) {
            let owner: Option<i64> = db
                .value(
                    "SELECT series_id FROM series_key WHERE key = ?",
                    args![&key],
                    "series_id",
                )
                .await?;
            match owner {
                None => {
                    db.run(
                        "INSERT OR IGNORE INTO series_key (key, series_id) VALUES (?, ?)",
                        args![&key, series_id],
                    )
                    .await?;
                }
                Some(other) if other != series_id && !absorbed.contains(&other) => {
                    if compatible(db, series_id, other).await? {
                        merge_into(db, series_id, other).await?;
                        absorbed.push(other);
                    }
                }
                _ => {}
            }
        }
    }
    if !absorbed.is_empty() {
        // Fetch the copies we just picked up, once; no further absorbing.
        let fresh: Vec<EntryRow> = entries(db, series_id)
            .await?
            .into_iter()
            .filter(|e| e.fetched_at.is_none())
            .collect();
        fetch_entries(db, http, &fresh).await?;
    }
    rebuild_series(db, series_id).await?;
    Ok(series_id)
}

async fn fetch_entries<H: Http>(db: &Db, http: &H, list: &[EntryRow]) -> Result<()> {
    let results = join_all(list.iter().map(|e| async move {
        match SourceId::parse(&e.source) {
            Some(s) => s.series(http, &e.source_id).await,
            None => Err(mangavault_core::Error::Invalid(format!(
                "unknown source {}",
                e.source
            ))),
        }
    }))
    .await;
    let now = time::now_iso();
    let mut stmts = Vec::new();
    for (e, res) in list.iter().zip(results) {
        match res {
            Ok(data) => {
                let chapters: Vec<ChapterEntry> = data
                    .chapters
                    .into_iter()
                    .map(|mut c| {
                        c.published_at = c.published_at.as_deref().and_then(time::normalize);
                        c
                    })
                    .collect();
                stmts.push(db.stmt(
                    "UPDATE entry SET details = ?, chapters = ?, title = ?, cover_url = COALESCE(?, cover_url), \
                     fetched_at = ?, needs_refresh = 0, last_error = NULL WHERE id = ?",
                    args![
                        serde_json::to_string(&data.details).unwrap(),
                        serde_json::to_string(&chapters).unwrap(),
                        &data.details.title,
                        data.details.cover_url.clone(),
                        &now,
                        e.id
                    ],
                )?);
            }
            Err(err) => {
                worker::console_warn!("refresh {}:{} failed: {err}", e.source, e.source_id);
                stmts.push(db.stmt(
                    "UPDATE entry SET needs_refresh = 0, last_error = ?, fetched_at = COALESCE(fetched_at, ?) WHERE id = ?",
                    args![err.to_string(), &now, e.id],
                )?);
            }
        }
    }
    db.batch(stmts).await
}

/// Two series with known, different kinds (manga vs manhwa) are not merged
/// even if a title key collides.
async fn compatible(db: &Db, a: i64, b: i64) -> Result<bool> {
    #[derive(Deserialize)]
    struct K {
        kind: Option<String>,
    }
    let ka: Option<K> = db
        .first("SELECT kind FROM series WHERE id = ?", args![a])
        .await?;
    let kb: Option<K> = db
        .first("SELECT kind FROM series WHERE id = ?", args![b])
        .await?;
    Ok(match (ka.and_then(|k| k.kind), kb.and_then(|k| k.kind)) {
        (Some(x), Some(y)) => x == y,
        _ => true,
    })
}

/// Move everything of series `drop` into `keep`, remapping user data so
/// nobody loses a favorite or their place, then delete `drop`.
async fn merge_into(db: &Db, keep: i64, drop: i64) -> Result<()> {
    worker::console_log!("merging series {drop} into {keep}");
    let stmts = vec![
        db.stmt("UPDATE entry SET series_id = ?1 WHERE series_id = ?2", args![keep, drop])?,
        db.stmt("UPDATE OR REPLACE series_key SET series_id = ?1 WHERE series_id = ?2", args![keep, drop])?,
        // Point user data at keep's chapter with the same key, if it has one.
        db.stmt(
            "UPDATE progress SET chapter_id = COALESCE((SELECT k.id FROM chapter k JOIN chapter d ON d.key = k.key \
             WHERE d.id = progress.chapter_id AND k.series_id = ?1), chapter_id) WHERE series_id = ?2",
            args![keep, drop],
        )?,
        db.stmt(
            "UPDATE OR IGNORE read_chapter SET chapter_id = COALESCE((SELECT k.id FROM chapter k JOIN chapter d ON d.key = k.key \
             WHERE d.id = read_chapter.chapter_id AND k.series_id = ?1), chapter_id) WHERE series_id = ?2",
            args![keep, drop],
        )?,
        db.stmt(
            "UPDATE history SET chapter_id = COALESCE((SELECT k.id FROM chapter k JOIN chapter d ON d.key = k.key \
             WHERE d.id = history.chapter_id AND k.series_id = ?1), chapter_id) WHERE series_id = ?2",
            args![keep, drop],
        )?,
        // Chapters keep has no copy of move over with their ids intact.
        db.stmt("UPDATE OR IGNORE chapter SET series_id = ?1 WHERE series_id = ?2", args![keep, drop])?,
        db.stmt("DELETE FROM chapter WHERE series_id = ?", args![drop])?,
        db.stmt("UPDATE OR IGNORE read_chapter SET series_id = ?1 WHERE series_id = ?2", args![keep, drop])?,
        db.stmt("DELETE FROM read_chapter WHERE series_id = ?", args![drop])?,
        // Two rows per user can't both survive: keep the newer one.
        db.stmt(
            "UPDATE OR REPLACE library SET series_id = ?1 WHERE series_id = ?2 AND NOT EXISTS (SELECT 1 FROM library l \
             WHERE l.user_id = library.user_id AND l.series_id = ?1 AND l.updated_at >= library.updated_at)",
            args![keep, drop],
        )?,
        db.stmt(
            "UPDATE OR REPLACE progress SET series_id = ?1 WHERE series_id = ?2 AND NOT EXISTS (SELECT 1 FROM progress p \
             WHERE p.user_id = progress.user_id AND p.series_id = ?1 AND p.updated_at >= progress.updated_at)",
            args![keep, drop],
        )?,
        db.stmt(
            "UPDATE OR REPLACE history SET series_id = ?1 WHERE series_id = ?2 AND NOT EXISTS (SELECT 1 FROM history h \
             WHERE h.user_id = history.user_id AND h.series_id = ?1 AND h.read_at >= history.read_at)",
            args![keep, drop],
        )?,
        db.stmt("DELETE FROM library WHERE series_id = ?", args![drop])?,
        db.stmt("DELETE FROM progress WHERE series_id = ?", args![drop])?,
        db.stmt("DELETE FROM history WHERE series_id = ?", args![drop])?,
        db.stmt("UPDATE series SET popularity = MAX(popularity, (SELECT popularity FROM series WHERE id = ?2)) WHERE id = ?1", args![keep, drop])?,
        // Clients may still hold the old id; resolve it to the survivor.
        db.stmt("UPDATE series_alias SET series_id = ?1 WHERE series_id = ?2", args![keep, drop])?,
        db.stmt("INSERT OR REPLACE INTO series_alias (old_id, series_id) VALUES (?2, ?1)", args![keep, drop])?,
        db.stmt("DELETE FROM series_fts WHERE rowid = ?", args![drop])?,
        db.stmt("DELETE FROM series WHERE id = ?", args![drop])?,
    ];
    db.batch(stmts).await
}

/// Recompute a series' metadata and merged chapter list from its entries.
pub async fn rebuild_series(db: &Db, series_id: i64) -> Result<()> {
    let list = entries(db, series_id).await?;
    let mut sites: Vec<(SourceId, EntryRow, Option<MangaDetails>, Vec<ChapterEntry>)> = list
        .into_iter()
        .filter_map(|e| {
            let source = SourceId::parse(&e.source)?;
            let details = e
                .details
                .as_deref()
                .and_then(|d| serde_json::from_str(d).ok());
            let chapters = json_col(&e.chapters);
            Some((source, e, details, chapters))
        })
        .collect();
    if sites.is_empty() {
        return Ok(());
    }

    // MangaDex's original language is the most reliable kind signal.
    let kind = sites
        .iter()
        .filter(|s| s.0 == SourceId::MangaDex)
        .chain(sites.iter())
        .find_map(|s| s.2.as_ref().and_then(|d| d.kind));
    let order = SourceId::preference(kind);
    sites.sort_by_key(|s| order.iter().position(|o| *o == s.0).unwrap_or(order.len()));

    let details: Vec<(SourceId, &MangaDetails)> = sites
        .iter()
        .filter_map(|s| s.2.as_ref().map(|d| (s.0, d)))
        .collect();
    let first =
        |f: &dyn Fn(&MangaDetails) -> Option<String>| details.iter().find_map(|(_, d)| f(d));
    let title = first(&|d| Some(d.title.clone()).filter(|t| !t.is_empty()))
        .unwrap_or_else(|| sites[0].1.title.clone());

    let mut alt_titles: Vec<String> = Vec::new();
    let mut seen_keys: HashSet<String> = title_key(&title).into_iter().collect();
    for (_, d) in &details {
        for t in std::iter::once(&d.title).chain(&d.alt_titles) {
            let key = title_key(t).unwrap_or_else(|| t.to_lowercase());
            if seen_keys.insert(key) {
                alt_titles.push(t.clone());
            }
        }
    }

    let (cover_url, cover_source) = details
        .iter()
        .find_map(|(s, d)| d.cover_url.clone().map(|c| (Some(c), Some(*s))))
        .or_else(|| {
            sites
                .iter()
                .find_map(|s| s.1.cover_url.clone().map(|c| (Some(c), Some(s.0))))
        })
        .unwrap_or((None, None));
    let description = first(&|d| d.description.clone().filter(|x| !x.is_empty()));
    let authors = details
        .iter()
        .map(|(_, d)| d.authors.clone())
        .find(|a| !a.is_empty())
        .unwrap_or_default();
    let artists = details
        .iter()
        .map(|(_, d)| d.artists.clone())
        .find(|a| !a.is_empty())
        .unwrap_or_default();
    let mut genres: Vec<String> = Vec::new();
    let mut seen_genres = HashSet::new();
    for (_, d) in &details {
        for g in &d.genres {
            if genres.len() < 12 && seen_genres.insert(g.to_lowercase()) {
                genres.push(g.clone());
            }
        }
    }
    let status = details
        .iter()
        .find_map(|(_, d)| d.status)
        .map(|s| s.as_str());

    let lists: Vec<(SourceId, Vec<ChapterEntry>)> =
        sites.iter().map(|s| (s.0, s.3.clone())).collect();
    let merged = merge_chapters(&lists);
    let latest = merged
        .iter()
        .filter_map(|c| c.number)
        .fold(None, |m: Option<f64>, n| Some(m.map_or(n, |m| m.max(n))));
    let newest_date = merged.iter().filter_map(|c| c.published_at.clone()).max();

    #[derive(Serialize)]
    struct Row<'a> {
        key: &'a str,
        sort: f64,
        number: Option<f64>,
        title: Option<&'a str>,
        volume: Option<&'a str>,
        published_at: Option<&'a str>,
        candidates: Vec<(&'static str, &'a str)>,
    }
    let rows: Vec<Row> = merged
        .iter()
        .map(|c| Row {
            key: &c.key,
            sort: c.sort,
            number: c.number,
            title: c.title.as_deref(),
            volume: c.volume.as_deref(),
            published_at: c.published_at.as_deref(),
            candidates: c
                .candidates
                .iter()
                .map(|(s, id)| (s.as_str(), id.as_str()))
                .collect(),
        })
        .collect();
    let keys: Vec<&str> = merged.iter().map(|c| c.key.as_str()).collect();
    let prev_count: Option<i64> = db
        .value(
            "SELECT chapter_count FROM series WHERE id = ?",
            args![series_id],
            "chapter_count",
        )
        .await?;
    let grew = prev_count.is_some_and(|p| (merged.len() as i64) > p);
    let now = time::now_iso();
    // Sites without dates (MangaPill) still bump the series when it grows.
    let updated_at = match (&newest_date, grew) {
        (Some(d), _) => Some(d.clone()),
        (None, true) => Some(now.clone()),
        (None, false) => None,
    };

    let mut stmts = vec![
        db.stmt(
            "UPDATE series SET title = ?, alt_titles = ?, cover_url = ?, cover_source = ?, description = ?, authors = ?, \
             artists = ?, genres = ?, status = ?, kind = ?, latest_chapter = ?, chapter_count = ?, \
             updated_at = COALESCE(?, updated_at), details_fetched_at = ? WHERE id = ?",
            args![
                &title,
                serde_json::to_string(&alt_titles).unwrap(),
                cover_url,
                cover_source.map(|s| s.as_str()),
                description,
                serde_json::to_string(&authors).unwrap(),
                serde_json::to_string(&artists).unwrap(),
                serde_json::to_string(&genres).unwrap(),
                status,
                kind.map(Kind::as_str),
                latest,
                merged.len(),
                updated_at,
                &now,
                series_id
            ],
        )?,
        db.stmt("DELETE FROM series_genre WHERE series_id = ?", args![series_id])?,
        db.stmt(
            "INSERT OR IGNORE INTO series_genre (series_id, genre) SELECT ?, value FROM json_each(?)",
            args![series_id, serde_json::to_string(&genres).unwrap()],
        )?,
        db.stmt("DELETE FROM series_fts WHERE rowid = ?", args![series_id])?,
        db.stmt(
            "INSERT INTO series_fts (rowid, title, alt_titles) VALUES (?, ?, ?)",
            args![series_id, &title, alt_titles.join(" ")],
        )?,
    ];
    // An empty merge (every site failed on first fetch) must not wipe chapters.
    if !merged.is_empty() {
        stmts.extend([
    db.stmt(
                "INSERT INTO chapter (series_id, key, sort, number, title, volume, published_at, candidates) \
                 SELECT ?1, json_extract(value, '$.key'), json_extract(value, '$.sort'), json_extract(value, '$.number'), \
                 json_extract(value, '$.title'), json_extract(value, '$.volume'), json_extract(value, '$.published_at'), \
                 json_extract(value, '$.candidates') FROM json_each(?2) WHERE true \
                 ON CONFLICT(series_id, key) DO UPDATE SET sort = excluded.sort, number = excluded.number, \
                 title = excluded.title, volume = excluded.volume, published_at = excluded.published_at, \
                 candidates = excluded.candidates",
                args![series_id, serde_json::to_string(&rows).unwrap()],
            )?,
            db.stmt(
                "DELETE FROM chapter WHERE series_id = ? AND key NOT IN (SELECT value FROM json_each(?))",
                args![series_id, serde_json::to_string(&keys).unwrap()],
            )?,
        ]);
    }
    db.batch(stmts).await
}

// ── Pages ──────────────────────────────────────────────────────────────

pub struct ChapterPages {
    pub pages: Vec<Page>,
    pub source: SourceId,
}

/// Page links for a merged chapter: the cache if fresh, else each site
/// that has the chapter in preference order until one answers.
pub async fn chapter_pages<H: Http>(
    db: &Db,
    http: &H,
    ch: &ChapterRow,
) -> std::result::Result<ChapterPages, String> {
    let fresh = ch
        .pages_fetched_at
        .as_deref()
        .is_some_and(|t| t > time::ago_iso(Duration::days(PAGES_TTL_DAYS)).as_str());
    if let (true, Some(pages), Some(source)) = (
        fresh,
        ch.pages.as_deref(),
        ch.pages_source.as_deref().and_then(SourceId::parse),
    ) {
        if let Ok(pages) = serde_json::from_str::<Vec<Page>>(pages) {
            if !pages.is_empty() && !links_expired(&pages) {
                return Ok(ChapterPages { pages, source });
            }
        }
    }

    let candidates: Vec<(String, String)> =
        serde_json::from_str(&ch.candidates).unwrap_or_default();
    let mut errors = Vec::new();
    for (source, chapter_id) in candidates {
        let Some(source) = SourceId::parse(&source) else {
            continue;
        };
        match source.pages(http, &chapter_id).await {
            Ok(images) if !images.is_empty() => {
                let pages = to_pages(images);
                let _ = db
                    .run(
                        "UPDATE chapter SET pages = ?, pages_source = ?, pages_fetched_at = ? WHERE id = ?",
                        args![serde_json::to_string(&pages).unwrap(), source.as_str(), time::now_iso(), ch.id],
                    )
                    .await;
                return Ok(ChapterPages { pages, source });
            }
            Ok(_) => errors.push(format!("{}: no pages", source.as_str())),
            Err(e) => errors.push(format!("{}: {e}", source.as_str())),
        }
    }
    Err(if errors.is_empty() {
        "no site has this chapter".into()
    } else {
        errors.join("; ")
    })
}

/// Signed page links (Tapas' `__token__=exp=<unix>~acl=...`) stop working
/// at `exp`, well before the page cache would; refetch a few minutes early.
fn links_expired(pages: &[Page]) -> bool {
    pages
        .first()
        .and_then(|p| link_expiry(&p.url))
        .is_some_and(|exp| exp - 300 < time::now().timestamp())
}

fn link_expiry(url: &str) -> Option<i64> {
    let at = url
        .match_indices("exp=")
        .find(|(i, _)| matches!(url[..*i].chars().last(), Some('?' | '&' | '=' | '~')))?
        .0;
    let digits: String = url[at + 4..].chars().take_while(char::is_ascii_digit).collect();
    digits.parse().ok()
}

fn to_pages(images: Vec<PageImage>) -> Vec<Page> {
    images
        .into_iter()
        .enumerate()
        .map(|(index, p)| Page {
            index,
            url: p.url,
            width: p.width,
            height: p.height,
        })
        .collect()
}

// ── Live search ────────────────────────────────────────────────────────

/// Search every site at once and store what they find. Failing sites are
/// skipped. Returns how many results were stored.
pub async fn live_search<H: Http>(db: &Db, http: &H, query: &str) -> Result<usize> {
    let results = join_all(
        SourceId::ALL
            .iter()
            .map(|s| async move { (*s, s.search(http, query, 1).await) }),
    )
    .await;
    let mut stored = 0;
    for (source, res) in results {
        match res {
            Ok(list) => {
                let items: Vec<MangaEntry> = list.items.into_iter().take(10).collect();
                stored += items.len();
                upsert_list(
                    db,
                    ListUpsert {
                        source,
                        items: &items,
                        popularity_start: None,
                        mark_refresh: false,
                    },
                )
                .await?;
            }
            Err(e) => worker::console_warn!("live search on {} failed: {e}", source.as_str()),
        }
    }
    Ok(stored)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_signed_link_expiry() {
        let tapas = "https://us-a.tapas.io/pc/ee/371b.jpg?__token__=exp=1792353832~acl=/pc/*~hmac=ab12";
        assert_eq!(link_expiry(tapas), Some(1792353832));
        assert_eq!(link_expiry("https://x.test/a.jpg?exp=17&b=1"), Some(17));
        assert_eq!(link_expiry("https://x.test/exp=1/a.jpg"), None);
        assert_eq!(link_expiry("https://uploads.mangadex.org/data/h/1.png"), None);
        let page = |url: &str| Page { index: 0, url: url.into(), width: None, height: None };
        assert!(links_expired(&[page(tapas)]));
        let later = time::now().timestamp() + 86_400;
        assert!(!links_expired(&[page(&format!("https://us-a.tapas.io/a.jpg?__token__=exp={later}~acl=x"))]));
        assert!(!links_expired(&[page("https://uploads.mangadex.org/data/h/1.png")]));
    }
}
