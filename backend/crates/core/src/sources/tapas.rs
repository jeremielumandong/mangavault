//! tapas.io, a webcomic platform with many western indie comics alongside
//! licensed manhwa. Lists come from its JSON API, details and pages from the
//! site. It only wants a birth-date cookie (the age gate) and a `Referer`.
//! Paid and scheduled episodes are left out of the chapter list, so every
//! listed episode is readable.
//!
//! Manga id: the numeric series id. Chapter id: the numeric episode id.

use scraper::Html;
use serde::Deserialize;

use crate::http::{check_interstitial, get_ok};
use crate::sources::{encode, sel, strip_html, text_of};
use crate::{
    ChapterEntry, Error, Http, MangaDetails, MangaEntry, MangaList, PageImage, Result, SeriesData,
    Status,
};

const BASE: &str = "https://tapas.io";
const API: &str = "https://story-api.tapas.io";
const UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
const COOKIE: &str = "birthDate=1990-01-01; adjustedBirthDate=1990-01-01";
const HEADERS: &[(&str, &str)] = &[
    ("User-Agent", UA),
    ("Referer", "https://m.tapas.io"),
    ("Cookie", COOKIE),
];
const PAGE_SIZE: u32 = 25;
/// Episode lists come 20 to a page; very long series stop here.
const MAX_EPISODE_PAGES: u32 = 50;
const FAR_FUTURE_MS: u64 = 9_999_999_999_999;

async fn fetch_html<H: Http>(http: &H, url: &str) -> Result<Html> {
    let body = get_ok(http, url, HEADERS).await?;
    check_interstitial(&body, url)?;
    Ok(Html::parse_document(&body))
}

async fn fetch_json<H: Http, T: serde::de::DeserializeOwned>(http: &H, url: &str) -> Result<T> {
    let body = get_ok(http, url, HEADERS).await?;
    serde_json::from_str(&body).map_err(|e| Error::Parse(format!("Tapas {url}: {e}")))
}

#[derive(Deserialize)]
struct Landing {
    data: LandingData,
    #[serde(default)]
    meta: Option<Meta>,
}

#[derive(Deserialize)]
struct LandingData {
    #[serde(default)]
    items: Vec<Item>,
}

#[derive(Deserialize)]
struct Meta {
    #[serde(default)]
    pagination: Option<Pagination>,
}

#[derive(Deserialize)]
struct Pagination {
    #[serde(default = "yes")]
    last: bool,
    #[serde(default)]
    has_next: bool,
}

fn yes() -> bool {
    true
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Item {
    series_id: u64,
    title: String,
    #[serde(default)]
    asset_property: Option<AssetProperty>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AssetProperty {
    #[serde(default)]
    book_cover_image: Option<std::collections::BTreeMap<String, String>>,
}

/// The cover map's URLs come without an extension.
fn cover_of(item: &Item) -> Option<String> {
    let url = item
        .asset_property
        .as_ref()?
        .book_cover_image
        .as_ref()?
        .values()
        .find(|u| u.starts_with("http"))?;
    Some(if url.ends_with(".png") || url.ends_with(".jpg") {
        url.clone()
    } else {
        format!("{url}.png")
    })
}

async fn landing<H: Http>(http: &H, url: String) -> Result<MangaList> {
    let body: Landing = fetch_json(http, &url).await?;
    let mut seen = std::collections::HashSet::new();
    let items = body
        .data
        .items
        .iter()
        .filter(|i| !i.title.trim().is_empty() && seen.insert(i.series_id))
        .map(|i| MangaEntry {
            source_id: i.series_id.to_string(),
            title: i.title.trim().to_string(),
            cover_url: cover_of(i),
        })
        .collect();
    let has_next = body
        .meta
        .and_then(|m| m.pagination)
        .is_some_and(|p| !p.last);
    Ok(MangaList { items, has_next })
}

/// Comics ranking (subtab 17 is the comics tab).
pub async fn popular<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    landing(
        http,
        format!(
            "{API}/cosmos/api/v1/landing/ranking?category_type=COMIC&subtab_id=17&size={PAGE_SIZE}&page={}",
            page.max(1) - 1
        ),
    )
    .await
}

pub async fn latest<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    landing(
        http,
        format!(
            "{API}/cosmos/api/v1/landing/genre?category_type=COMIC&sort_option=NEWEST_EPISODE&subtab_id=17&pageSize={PAGE_SIZE}&page={}",
            page.max(1) - 1
        ),
    )
    .await
}

pub async fn search<H: Http>(http: &H, query: &str, page: u32) -> Result<MangaList> {
    let url = format!(
        "{BASE}/search?pageNumber={}&q={}&t=COMICS",
        page.max(1),
        encode(query)
    );
    let doc = fetch_html(http, &url).await?;
    let mut seen = std::collections::HashSet::new();
    let mut items = Vec::new();
    for card in doc.select(&sel(".search-item-wrap")) {
        let Some(id) = card
            .select(&sel("[data-series-id]"))
            .filter_map(|e| e.value().attr("data-series-id"))
            .find(|id| valid_id(id))
        else {
            continue;
        };
        let title = card
            .select(&sel(".title-section .title a"))
            .next()
            .map(text_of)
            .filter(|t| !t.is_empty())
            .or_else(|| {
                card.select(&sel(".item__thumb img[alt], .thumb-wrap img[alt]"))
                    .next()
                    .and_then(|i| i.value().attr("alt"))
                    .map(|t| t.trim().to_string())
                    .filter(|t| !t.is_empty())
            });
        let Some(title) = title else { continue };
        if !seen.insert(id.to_string()) {
            continue;
        }
        items.push(MangaEntry {
            source_id: id.to_string(),
            title,
            cover_url: card
                .select(&sel(".item__thumb img, .thumb-wrap img"))
                .next()
                .and_then(|i| i.value().attr("src"))
                .filter(|u| u.starts_with("http"))
                .map(str::to_string),
        });
    }
    let has_next = doc
        .select(&sel(r#"a[class*="paging__button--next"]"#))
        .next()
        .is_some();
    Ok(MangaList { items, has_next })
}

fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 20 && id.bytes().all(|b| b.is_ascii_digit())
}

#[derive(Deserialize)]
struct EpisodesBody {
    data: EpisodesData,
}

#[derive(Deserialize)]
struct EpisodesData {
    #[serde(default)]
    pagination: Option<Pagination>,
    #[serde(default)]
    episodes: Vec<Episode>,
}

#[derive(Deserialize)]
struct Episode {
    id: u64,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    publish_date: Option<String>,
    #[serde(default)]
    unlocked: bool,
    #[serde(default)]
    free: bool,
    #[serde(default)]
    scene: Option<f64>,
    #[serde(default)]
    scheduled: bool,
}

pub async fn series<H: Http>(http: &H, id: &str) -> Result<SeriesData> {
    if !valid_id(id) {
        return Err(Error::Invalid(format!("bad Tapas id {id:?}")));
    }
    let url = format!("{BASE}/series/{id}/info");
    let doc = fetch_html(http, &url).await?;
    let details = parse_details(&doc, id, url)?;

    let mut episodes = Vec::new();
    for page in 1..=MAX_EPISODE_PAGES {
        let body: EpisodesBody = fetch_json(
            http,
            // The site's own query; `since` caps the list at a publish time.
            &format!(
                "{BASE}/series/{id}/episodes?page={page}&sort=NEWEST&since={FAR_FUTURE_MS}&large=true&last_access=0"
            ),
        )
        .await?;
        let more = !body.data.episodes.is_empty()
            && body.data.pagination.as_ref().is_some_and(|p| p.has_next);
        episodes.extend(body.data.episodes);
        if !more {
            break;
        }
    }
    Ok(SeriesData {
        details,
        chapters: parse_episodes(episodes),
    })
}

/// Only episodes anyone can read today: no paywall, no future release.
fn parse_episodes(episodes: Vec<Episode>) -> Vec<ChapterEntry> {
    let mut seen = std::collections::HashSet::new();
    episodes
        .into_iter()
        .filter(|e| (e.free || e.unlocked) && !e.scheduled && seen.insert(e.id))
        .map(|e| ChapterEntry {
            source_id: e.id.to_string(),
            number: e.scene.filter(|n| n.is_finite() && *n >= 0.0),
            title: e
                .title
                .map(|t| t.trim().to_string())
                .filter(|t| !t.is_empty()),
            volume: None,
            scanlator: None,
            published_at: e.publish_date.as_deref().and_then(utc_date),
        })
        .collect()
}

/// Tapas publish dates ("2019-01-15T17:00:00Z") are UTC; keep the date and
/// time, drop anything after the seconds.
fn utc_date(raw: &str) -> Option<String> {
    let raw = raw.trim();
    let date = raw.get(..10)?;
    let time = raw.get(11..19).unwrap_or("00:00:00");
    let ok = date.len() == 10
        && date.as_bytes()[4] == b'-'
        && date.as_bytes()[7] == b'-'
        && date
            .bytes()
            .enumerate()
            .all(|(i, b)| i == 4 || i == 7 || b.is_ascii_digit())
        && time.len() == 8
        && time.bytes().enumerate().all(|(i, b)| {
            if i == 2 || i == 5 {
                b == b':'
            } else {
                b.is_ascii_digit()
            }
        });
    ok.then(|| format!("{date}T{time}Z"))
}

fn parse_details(doc: &Html, id: &str, url: String) -> Result<MangaDetails> {
    let title = doc
        .select(&sel(".info__right .title, .info__right h1"))
        .next()
        .map(text_of)
        .filter(|t| !t.is_empty())
        .ok_or_else(|| Error::Parse(format!("unexpected Tapas series page: {url}")))?;
    let mut genres: Vec<String> = Vec::new();
    for g in doc.select(&sel(".genre-btn")).map(text_of) {
        if !g.is_empty() && !genres.contains(&g) {
            genres.push(g);
        }
    }
    let mut authors: Vec<String> = Vec::new();
    for a in doc.select(&sel(".creator-section .name")).map(text_of) {
        if !a.is_empty() && !authors.contains(&a) {
            authors.push(a);
        }
    }
    let schedule = doc
        .select(&sel(".schedule-label"))
        .map(text_of)
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase();
    let status = if schedule.contains("completed") {
        Some(Status::Completed)
    } else if schedule.contains("hiatus") {
        Some(Status::Hiatus)
    } else if schedule.contains("updates") {
        Some(Status::Ongoing)
    } else {
        None
    };
    Ok(MangaDetails {
        source_id: id.to_string(),
        title,
        cover_url: doc
            .select(&sel(".thumb.js-thumbnail img"))
            .next()
            .and_then(|i| i.value().attr("src"))
            .filter(|u| u.starts_with("http"))
            .map(str::to_string),
        description: doc
            .select(&sel(".description__body"))
            .next()
            .map(|d| strip_html(&d.inner_html()))
            .filter(|d| !d.is_empty()),
        authors,
        genres,
        status,
        // Western indie comics and licensed manhwa side by side; leave the
        // kind to the other sites (and the reader to its default).
        kind: None,
        url,
        ..Default::default()
    })
}

pub async fn pages<H: Http>(http: &H, chapter_id: &str) -> Result<Vec<PageImage>> {
    if !valid_id(chapter_id) {
        return Err(Error::Invalid(format!(
            "bad Tapas chapter id {chapter_id:?}"
        )));
    }
    let doc = fetch_html(http, &format!("{BASE}/episode/{chapter_id}")).await?;
    // Novels have a text-style button; they aren't comics.
    if doc
        .select(&sel(r#".toolbar a[data-type="style"]"#))
        .next()
        .is_some()
    {
        return Ok(Vec::new());
    }
    let mut seen = std::collections::HashSet::new();
    // A locked episode has no images, so the backend moves to the next site.
    Ok(doc
        .select(&sel("img.content__img"))
        .filter_map(|img| {
            let src = img
                .value()
                .attr("data-src")
                .or_else(|| img.value().attr("src"))?
                .trim();
            (src.starts_with("http") && seen.insert(src.to_string())).then(|| PageImage {
                url: src.to_string(),
                width: img.value().attr("width").and_then(|w| w.parse().ok()),
                height: img.value().attr("height").and_then(|h| h.parse().ok()),
            })
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sources::testing::{Fixtures, block_on};

    const RANKING: &str = include_str!("../../tests/fixtures/tapas_ranking.json");
    const SEARCH: &str = include_str!("../../tests/fixtures/tapas_search.html");
    const INFO: &str = include_str!("../../tests/fixtures/tapas_info.html");
    const EPISODES_1: &str = include_str!("../../tests/fixtures/tapas_episodes_1.json");
    const EPISODES_2: &str = include_str!("../../tests/fixtures/tapas_episodes_2.json");
    const EPISODE: &str = include_str!("../../tests/fixtures/tapas_episode.html");

    #[test]
    fn parses_ranking() {
        let http = Fixtures::new(&[("/landing/ranking", RANKING)]);
        let list = block_on(popular(&http, 1)).unwrap();
        assert!(http.requests.borrow()[0].ends_with("&page=0"));
        let titles: Vec<_> = list.items.iter().map(|m| m.title.as_str()).collect();
        assert_eq!(titles, ["Mage & Demon Queen", "Kinda Okay"]);
        assert_eq!(list.items[0].source_id, "52914");
        assert_eq!(
            list.items[0].cover_url.as_deref(),
            Some("https://us-a.tapas.io/sa/5f/mage-cover.png")
        );
        assert!(list.has_next);
    }

    #[test]
    fn parses_search_cards() {
        let http = Fixtures::new(&[("/search?pageNumber=1&q=mage", SEARCH)]);
        let list = block_on(search(&http, "mage", 1)).unwrap();
        assert_eq!(list.items.len(), 2);
        assert_eq!(list.items[0].source_id, "52914");
        assert_eq!(list.items[0].title, "Mage & Demon Queen");
        assert_eq!(list.items[1].title, "Mage Tales");
        assert_eq!(
            list.items[1].cover_url.as_deref(),
            Some("https://us-a.tapas.io/sa/aa/tales.jpg")
        );
        assert!(!list.has_next);
    }

    #[test]
    fn parses_series_and_readable_episodes() {
        let http = Fixtures::new(&[
            ("/series/52914/info", INFO),
            ("/series/52914/episodes?page=1&", EPISODES_1),
            ("/series/52914/episodes?page=2&", EPISODES_2),
        ]);
        let s = block_on(series(&http, "52914")).unwrap();
        let d = &s.details;
        assert_eq!(d.title, "Mage & Demon Queen");
        assert_eq!(d.authors, vec!["Pikat"]);
        assert_eq!(d.genres, vec!["Comedy", "Fantasy"]);
        assert_eq!(d.status, Some(Status::Ongoing));
        assert_eq!(d.kind, None);
        assert_eq!(
            d.description.as_deref(),
            Some("A mage climbs a tower.\nShe wants to date the boss.")
        );
        assert_eq!(
            d.cover_url.as_deref(),
            Some("https://us-a.tapas.io/sa/5f/mage-thumb.jpg")
        );
        let ids: Vec<_> = s.chapters.iter().map(|c| c.source_id.as_str()).collect();
        // 3003 is paid and 3005 not out yet.
        assert_eq!(ids, ["3001", "3002", "3004"]);
        assert_eq!(s.chapters[0].number, Some(1.0));
        assert_eq!(s.chapters[0].title.as_deref(), Some("Episode 1"));
        assert_eq!(
            s.chapters[0].published_at.as_deref(),
            Some("2019-01-15T17:00:00Z")
        );
        assert_eq!(s.chapters[2].number, Some(4.0));
        assert_eq!(http.requests.borrow().len(), 3);
    }

    #[test]
    fn parses_episode_images() {
        let http = Fixtures::new(&[("/episode/3001", EPISODE)]);
        let p = block_on(pages(&http, "3001")).unwrap();
        let urls: Vec<_> = p.iter().map(|p| p.url.as_str()).collect();
        assert_eq!(
            urls,
            [
                "https://us-a.tapas.io/pc/01/page-1.jpg",
                "https://us-a.tapas.io/pc/01/page-2.jpg"
            ]
        );
        assert_eq!(p[0].width, Some(940));
        assert_eq!(p[0].height, Some(1330));
    }

    #[test]
    fn locked_or_novel_episode_has_no_pages() {
        let locked = Fixtures::new(&[(
            "/episode/9",
            "<html><body><div class=\"js-episode-locked\"></div></body></html>",
        )]);
        assert!(block_on(pages(&locked, "9")).unwrap().is_empty());
        let novel = Fixtures::new(&[(
            "/episode/9",
            "<html><body><div class=\"toolbar\"><a data-type=\"style\">Aa</a></div><img class=\"content__img\" src=\"https://x.example/a.jpg\"></body></html>",
        )]);
        assert!(block_on(pages(&novel, "9")).unwrap().is_empty());
    }

    #[test]
    fn rejects_malformed_ids() {
        let http = Fixtures::new(&[]);
        assert!(block_on(series(&http, "12/x")).is_err());
        assert!(block_on(series(&http, "")).is_err());
        assert!(block_on(pages(&http, "https://evil.example/1")).is_err());
    }

    #[test]
    fn dates() {
        assert_eq!(
            utc_date("2019-01-15T17:00:00Z").as_deref(),
            Some("2019-01-15T17:00:00Z")
        );
        assert_eq!(
            utc_date("2019-01-15").as_deref(),
            Some("2019-01-15T00:00:00Z")
        );
        assert_eq!(utc_date("Jan 15, 2019"), None);
    }
}
