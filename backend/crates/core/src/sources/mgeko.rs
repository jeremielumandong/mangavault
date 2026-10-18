//! www.mgeko.cc, a manhwa/webtoon-heavy aggregator. Cloudflare in
//! pass-through mode: no challenge, no cookies, and page images load with no
//! headers at all (verified by the Flutter app, Aug 2026).
//!
//! Manga id: the series slug (`murim-login`). Chapter id: the reader slug
//! (`murim-login-chapter-250-eng-li`).

use scraper::{ElementRef, Html};
use serde::Deserialize;

use crate::http::{check_interstitial, get_ok};
use crate::sources::{encode, sel, text_of};
use crate::{
    ChapterEntry, Error, Http, MangaDetails, MangaEntry, MangaList, PageImage, Result, SeriesData,
    Status,
};

const BASE: &str = "https://www.mgeko.cc";
const HEADERS: &[(&str, &str)] = &[
    ("User-Agent", "Mozilla/5.0"),
    (
        "Accept",
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    ),
];
/// The 288x412 cover variant both the cards and the detail page use.
const THUMB: &str = "https://imgsrv5.com/avatar/288x412";
/// Browse is scoped to the site's own manhwa and webtoon listings (its
/// reason for being here). `type` is single-valued, so one request each.
const BROWSE_TYPES: [&str; 2] = ["manhwa", "webtoon"];

async fn fetch<H: Http>(http: &H, url: &str) -> Result<String> {
    let body = get_ok(http, url, HEADERS).await?;
    check_interstitial(&body, url)?;
    Ok(body)
}

#[derive(Deserialize)]
struct Envelope {
    results_html: String,
    #[serde(default)]
    total_results: Option<i64>,
    #[serde(default)]
    page: Option<u32>,
    #[serde(default)]
    num_pages: Option<u32>,
}

/// One `/browse-comics/data/` page: a JSON envelope around an HTML fragment.
async fn browse<H: Http>(http: &H, page: u32, params: &[(&str, &str)]) -> Result<MangaList> {
    let page = page.max(1);
    let mut url = format!("{BASE}/browse-comics/data/?page={page}");
    for (k, v) in params {
        url.push_str(&format!("&{k}={}", encode(v)));
    }
    url.push_str("&safe_mode=true");
    let body = fetch(http, &url).await?;
    parse_browse(&body, page, &url)
}

fn parse_browse(body: &str, page: u32, url: &str) -> Result<MangaList> {
    let env: Envelope = serde_json::from_str(body)
        .map_err(|_| Error::Parse(format!("unexpected MgEko browse response: {url}")))?;
    // Out-of-range pages are clamped to the last one and served again.
    if env.page.is_some_and(|p| p != page) {
        return Ok(MangaList {
            items: Vec::new(),
            has_next: false,
        });
    }
    let items = parse_cards(&Html::parse_fragment(&env.results_html));
    if items.is_empty() && env.total_results != Some(0) {
        return Err(Error::Parse(format!(
            "unexpected MgEko browse response: {url}"
        )));
    }
    Ok(MangaList {
        items,
        has_next: page < env.num_pages.unwrap_or(1),
    })
}

/// Both type listings, round-robin so neither hides behind the other, and
/// deduped (some titles are typed as both).
async fn browse_types<H: Http>(http: &H, page: u32, sort: &str) -> Result<MangaList> {
    let mut lists = Vec::new();
    for t in BROWSE_TYPES {
        lists.push(browse(http, page, &[("type", t), ("sort", sort)]).await?);
    }
    let longest = lists.iter().map(|l| l.items.len()).max().unwrap_or(0);
    let mut seen = std::collections::HashSet::new();
    let mut items = Vec::new();
    for i in 0..longest {
        for l in &lists {
            if let Some(m) = l.items.get(i).filter(|m| seen.insert(m.source_id.clone())) {
                items.push(m.clone());
            }
        }
    }
    Ok(MangaList {
        items,
        has_next: lists.iter().any(|l| l.has_next),
    })
}

pub async fn popular<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    browse_types(http, page, "popular_all_time").await
}

pub async fn latest<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    browse_types(http, page, "latest").await
}

/// No `type` here, so search reaches manga and manhua too.
pub async fn search<H: Http>(http: &H, query: &str, page: u32) -> Result<MangaList> {
    browse(http, page, &[("q", query)]).await
}

fn parse_cards(doc: &Html) -> Vec<MangaEntry> {
    let mut items = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for card in doc.select(&sel("article.comic-card")) {
        let Some(slug) = card
            .select(&sel(r#"a[href*="/manga/"]"#))
            .next()
            .and_then(|a| a.value().attr("href"))
            .and_then(slug_of)
        else {
            continue;
        };
        if !seen.insert(slug.clone()) {
            continue;
        }
        let title = card
            .select(&sel(".comic-card__title a"))
            .next()
            .map(text_of)
            .filter(|t| !t.is_empty())
            .or_else(|| {
                card.select(&sel("img[alt]"))
                    .next()
                    .and_then(|i| i.value().attr("alt"))
                    .map(|a| a.trim().to_string())
            })
            .unwrap_or_default();
        if title.is_empty() {
            continue;
        }
        items.push(MangaEntry {
            source_id: slug,
            title,
            cover_url: card
                .select(&sel(".comic-card__cover img"))
                .next()
                .and_then(cover_of),
        });
    }
    items
}

/// `murim-login` from `/manga/murim-login/`.
fn slug_of(href: &str) -> Option<String> {
    let rest = href.split("/manga/").nth(1)?;
    let slug = rest.split(['/', '?', '#']).next()?;
    (!slug.is_empty()).then(|| slug.to_string())
}

/// The 288x412 thumbnail for a cover `<img>`. The detail page lazy-loads
/// (real URL in `data-src`, a placeholder in `src`), and origin `/media/`
/// URLs are 28x larger than the thumbnail, so both map to the same form.
fn cover_of(img: ElementRef<'_>) -> Option<String> {
    let raw = img
        .value()
        .attr("data-src")
        .or_else(|| img.value().attr("src"))?
        .trim();
    if raw.is_empty() || raw.contains("default-placeholder") {
        return None;
    }
    if raw.starts_with(THUMB) {
        return Some(raw.to_string());
    }
    for origin in ["https://www.mgeko.cc", "https://mgeko.cc", ""] {
        if let Some(path) = raw
            .strip_prefix(origin)
            .filter(|p| p.starts_with("/media/"))
        {
            return Some(format!("{THUMB}{path}"));
        }
    }
    raw.starts_with("http").then(|| raw.to_string())
}

pub async fn series<H: Http>(http: &H, slug: &str) -> Result<SeriesData> {
    if slug.is_empty() || slug.contains(['/', '?', '#']) {
        return Err(Error::Invalid(format!("bad MgEko id {slug:?}")));
    }
    let url = format!("{BASE}/manga/{slug}/");
    let doc = Html::parse_document(&fetch(http, &url).await?);
    let list =
        Html::parse_document(&fetch(http, &format!("{BASE}/manga/{slug}/all-chapters/")).await?);
    let details = parse_details(&doc, slug, url)?;
    Ok(SeriesData {
        details,
        chapters: parse_chapters(&list),
    })
}

fn parse_details(doc: &Html, slug: &str, url: String) -> Result<MangaDetails> {
    let title = doc
        .select(&sel("h1.novel-title"))
        .next()
        .map(text_of)
        .filter(|t| !t.is_empty())
        .ok_or_else(|| Error::Parse(format!("MgEko page without title: {url}")))?;
    let authors = doc
        .select(&sel(r#"span[itemprop="author"]"#))
        .map(text_of)
        // "Updating" is the site's word for unknown.
        .filter(|a| !a.is_empty() && a != "Updating")
        .collect();
    let genres = doc
        .select(&sel(".categories li a.property-item"))
        .map(text_of)
        .filter(|g| !g.is_empty())
        .collect();
    let status = doc
        .select(&sel(".header-stats > span"))
        .find(|s| s.select(&sel("small")).next().map(text_of).as_deref() == Some("Status"))
        .and_then(|s| s.select(&sel("strong")).next().map(text_of))
        .and_then(|s| Status::parse(&s));
    Ok(MangaDetails {
        source_id: slug.to_string(),
        title,
        // `figure.cover` only: `figure.novel-cover` is an unrendered template.
        cover_url: doc
            .select(&sel("figure.cover img"))
            .next()
            .and_then(cover_of),
        description: description(doc),
        authors,
        genres,
        status,
        url,
        ..Default::default()
    })
}

/// The description opens with SEO boilerplate ending in "The Summary is".
fn description(doc: &Html) -> Option<String> {
    let raw = text_of(doc.select(&sel(".description")).next()?);
    let body = match raw.find("The Summary is") {
        Some(i) => &raw[i + "The Summary is".len()..],
        None => &raw,
    };
    Some(body.trim().to_string()).filter(|d| !d.is_empty())
}

fn parse_chapters(doc: &Html) -> Vec<ChapterEntry> {
    let mut chapters = Vec::new();
    for li in doc.select(&sel("li[data-chapterno]")) {
        let Some(slug) = li
            .select(&sel(r#"a[href*="/reader/"]"#))
            .next()
            .and_then(|a| a.value().attr("href"))
            .and_then(reader_slug)
        else {
            continue;
        };
        let raw = li
            .select(&sel("strong.chapter-title"))
            .next()
            .map(text_of)
            .unwrap_or_default();
        // `data-chapterno` is "1" on every row; the slug carries the number.
        let number = slug_number(&slug).or_else(|| leading_number(&raw));
        let title = match number {
            Some(_) => None,
            None => {
                Some(raw.trim_end_matches("-eng-li").trim().to_string()).filter(|t| !t.is_empty())
            }
        };
        chapters.push(ChapterEntry {
            source_id: slug,
            number,
            title,
            volume: None,
            scanlator: None,
            published_at: li
                .select(&sel("time[datetime]"))
                .next()
                .and_then(|t| t.value().attr("datetime"))
                .and_then(django_date),
        });
    }
    chapters
}

/// `murim-login-chapter-250-eng-li` from `/reader/en/murim-login-chapter-250-eng-li/`.
fn reader_slug(href: &str) -> Option<String> {
    let rest = href.split("/reader/").nth(1)?;
    let slug = rest.split('/').nth(1)?;
    let slug = slug.split(['?', '#']).next()?;
    (!slug.is_empty()).then(|| slug.to_string())
}

/// `…-chapter-250-eng-li` is 250; decimals use a hyphen, so
/// `…-chapter-206-5-eng-li` is 206.5. The last match wins, in case the series
/// slug itself contains "chapter".
fn slug_number(slug: &str) -> Option<f64> {
    use std::sync::OnceLock;
    static RE: OnceLock<regex::Regex> = OnceLock::new();
    let re =
        RE.get_or_init(|| regex::Regex::new(r"-chapter-(\d+)(?:[.-](\d+))?(?:[^\d]|$)").unwrap());
    let c = re.captures_iter(slug).last()?;
    match c.get(2) {
        Some(f) => format!("{}.{}", &c[1], f.as_str()).parse().ok(),
        None => c[1].parse().ok(),
    }
}

fn leading_number(text: &str) -> Option<f64> {
    let digits: String = text
        .trim()
        .chars()
        .take_while(|c| c.is_ascii_digit() || *c == '.')
        .collect();
    digits.trim_end_matches('.').parse().ok()
}

/// Django's "Feb. 7, 2026, 2:35 p.m." (also "midnight" / "noon", and
/// month names of any length) as RFC 3339 UTC. Unknown shapes give `None`.
fn django_date(raw: &str) -> Option<String> {
    use std::sync::OnceLock;
    static DATE: OnceLock<regex::Regex> = OnceLock::new();
    static TIME: OnceLock<regex::Regex> = OnceLock::new();
    let date = DATE.get_or_init(|| {
        regex::Regex::new(r"^([A-Za-z]+)\.?\s+(\d{1,2}),\s*(\d{4})(?:,\s*(.+?))?\.?$").unwrap()
    });
    let time = TIME.get_or_init(|| {
        regex::Regex::new(r"(?i)^(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m\.?$").unwrap()
    });
    let c = date.captures(raw.trim())?;
    const MONTHS: [&str; 12] = [
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
    ];
    let word = c[1].to_lowercase();
    let month = MONTHS.iter().position(|m| word.starts_with(m))? + 1;
    let day: u32 = c[2].parse().ok()?;
    let year: u32 = c[3].parse().ok()?;
    let (mut hour, mut minute) = (0u32, 0u32);
    if let Some(t) = c.get(4).map(|t| t.as_str().trim().to_lowercase()) {
        match t.as_str() {
            "midnight" => {}
            "noon" => hour = 12,
            _ => {
                let tc = time.captures(&t)?;
                let h: u32 = tc[1].parse().ok()?;
                if !(1..=12).contains(&h) {
                    return None;
                }
                minute = tc.get(2).map_or(Some(0), |m| m.as_str().parse().ok())?;
                hour = h % 12 + if &tc[3] == "p" { 12 } else { 0 };
            }
        }
    }
    if !(1..=31).contains(&day) || minute > 59 {
        return None;
    }
    Some(format!(
        "{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:00Z"
    ))
}

pub async fn pages<H: Http>(http: &H, chapter_id: &str) -> Result<Vec<PageImage>> {
    if chapter_id.is_empty() || chapter_id.contains(['/', '?', '#']) {
        return Err(Error::Invalid(format!(
            "bad MgEko chapter id {chapter_id:?}"
        )));
    }
    let url = format!("{BASE}/reader/en/{chapter_id}/");
    let doc = Html::parse_document(&fetch(http, &url).await?);
    let reader = doc
        .select(&sel("#chapter-reader"))
        .next()
        .ok_or_else(|| Error::Parse(format!("unexpected MgEko reader response: {url}")))?;
    let pages: Vec<PageImage> = reader
        .select(&sel("img"))
        .filter_map(|img| {
            let src = img
                .value()
                .attr("src")
                .or_else(|| img.value().attr("data-src"))?
                .trim();
            (src.starts_with("http") && !is_site_chrome(src)).then(|| PageImage {
                url: src.to_string(),
                width: None,
                height: None,
            })
        })
        .collect();
    if pages.is_empty() {
        return Err(Error::Parse(format!("MgEko chapter has no pages: {url}")));
    }
    Ok(pages)
}

/// A site asset rather than a page: the reader ends with a credits banner at
/// the CDN root, while real pages sit several directories deep.
fn is_site_chrome(src: &str) -> bool {
    let path = url::Url::parse(src)
        .map(|u| u.path().to_string())
        .unwrap_or_default();
    path.ends_with(".svg") || path.split('/').filter(|s| !s.is_empty()).count() < 2
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sources::testing::{Fixtures, block_on};

    const BROWSE: &str = include_str!("../../tests/fixtures/mgeko_browse.json");
    const SERIES: &str = include_str!("../../tests/fixtures/mgeko_series.html");
    const CHAPTERS: &str = include_str!("../../tests/fixtures/mgeko_chapters.html");
    const READER: &str = include_str!("../../tests/fixtures/mgeko_reader.html");

    #[test]
    fn popular_interleaves_both_types_with_safe_mode() {
        let http = Fixtures::new(&[("/browse-comics/data/", BROWSE)]);
        let list = block_on(popular(&http, 1)).unwrap();
        // Both requests get the same fixture, so dedupe leaves one copy.
        assert_eq!(list.items.len(), 2);
        assert_eq!(list.items[0].source_id, "murim-login");
        assert_eq!(list.items[0].title, "Murim Login");
        assert_eq!(
            list.items[0].cover_url.as_deref(),
            Some("https://imgsrv5.com/avatar/288x412/media/manga_covers/murim-login.webp")
        );
        assert_eq!(
            list.items[1].title,
            "The Regressed Mercenary's Machinations"
        );
        assert!(list.has_next);
        let reqs = http.requests.borrow();
        assert_eq!(reqs.len(), 2);
        assert!(reqs[0].contains("type=manhwa") && reqs[0].contains("sort=popular_all_time"));
        assert!(reqs[1].contains("type=webtoon"));
        assert!(
            reqs.iter()
                .all(|r| r.contains("safe_mode=true") && r.contains("page=1"))
        );
    }

    #[test]
    fn clamped_page_ends_the_stream() {
        // The fixture says page 1; asking for page 5 got the clamped last page.
        let list = parse_browse(BROWSE, 5, "x").unwrap();
        assert!(list.items.is_empty());
        assert!(!list.has_next);
    }

    #[test]
    fn empty_search_is_not_an_error() {
        let empty = r#"{"results_html":"<p>No series found with these filters.</p>","total_results":0,"page":1,"num_pages":1}"#;
        let http = Fixtures::new(&[("/browse-comics/data/", empty)]);
        let list = block_on(search(&http, "zzz", 1)).unwrap();
        assert!(list.items.is_empty());
        let url = &http.requests.borrow()[0];
        assert!(url.contains("q=zzz") && !url.contains("type="));
    }

    #[test]
    fn parses_series_and_chapters() {
        let http = Fixtures::new(&[
            ("/manga/murim-login/all-chapters/", CHAPTERS),
            ("/manga/murim-login/", SERIES),
        ]);
        let s = block_on(series(&http, "murim-login")).unwrap();
        let d = &s.details;
        assert_eq!(d.title, "Murim Login");
        assert_eq!(d.authors, vec!["Buldalgi"]);
        assert_eq!(d.genres, vec!["Action", "Adventure", "Fantasy"]);
        assert_eq!(d.status, Some(Status::Ongoing));
        assert_eq!(
            d.cover_url.as_deref(),
            Some("https://imgsrv5.com/avatar/288x412/media/manga_covers/sasasa.webp")
        );
        assert_eq!(
            d.description.as_deref(),
            Some("An era where hunters thrive on hunting monsters that come from Gates.")
        );
        let c = &s.chapters;
        assert_eq!(c.len(), 4);
        assert_eq!(c[0].source_id, "murim-login-chapter-250-eng-li");
        assert_eq!(c[0].number, Some(250.0));
        assert_eq!(c[0].title, None);
        assert_eq!(c[0].published_at.as_deref(), Some("2026-02-07T14:35:00Z"));
        assert_eq!(c[1].number, Some(206.5));
        assert_eq!(c[1].published_at.as_deref(), Some("2025-09-30T00:00:00Z"));
        assert_eq!(c[2].number, Some(117.5));
        assert_eq!(c[2].published_at.as_deref(), Some("2025-03-01T12:00:00Z"));
        assert_eq!(c[3].number, None);
        assert_eq!(c[3].title.as_deref(), Some("prologue"));
        assert_eq!(c[3].published_at, None);
    }

    #[test]
    fn reader_drops_banner_and_page_chrome() {
        let http = Fixtures::new(&[("/reader/en/murim-login-chapter-250-eng-li/", READER)]);
        let p = block_on(pages(&http, "murim-login-chapter-250-eng-li")).unwrap();
        assert_eq!(p.len(), 3);
        assert_eq!(
            p[0].url,
            "https://imgsrv5.com/mg1/fastcdn/cdn_mangaraw/murim-login/chapter-250/0.jpg"
        );
        assert!(p.iter().all(|x| !x.url.contains("credits")));
    }

    #[test]
    fn rejects_ids_with_paths() {
        let http = Fixtures::new(&[]);
        assert!(block_on(series(&http, "../x")).is_err());
        assert!(block_on(pages(&http, "a/b")).is_err());
    }
}
