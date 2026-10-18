//! comichubfree.com, a western comics aggregator (Marvel, DC, Image, Dark
//! Horse and more), the less crowded mirror of XOXO Comics. No bot check and
//! no cookies; images load from the site itself.
//!
//! Manga id: the series slug (`invincible`). Chapter id: the issue path
//! without its leading slash (`invincible/issue-120`).

use scraper::{ElementRef, Html};

use crate::http::{BROWSER_UA, check_interstitial, get_ok};
use crate::sources::{encode, sel, text_of};
use crate::{
    ChapterEntry, Error, Http, Kind, MangaDetails, MangaEntry, MangaList, PageImage, Result,
    SeriesData, Status,
};

const BASE: &str = "https://comichubfree.com";
const HEADERS: &[(&str, &str)] = &[
    ("User-Agent", BROWSER_UA),
    (
        "Accept",
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    ),
];
/// Issue lists come 50 to a page; long runs (The Amazing Spider-Man has
/// 900+ issues) stop here so one series can't eat a crawl's request budget.
const MAX_ISSUE_PAGES: u32 = 30;

async fn fetch<H: Http>(http: &H, url: &str) -> Result<Html> {
    let body = get_ok(http, url, HEADERS).await?;
    check_interstitial(&body, url)?;
    Ok(Html::parse_document(&body))
}

async fn listing<H: Http>(http: &H, url: &str) -> Result<MangaList> {
    let doc = fetch(http, url).await?;
    let items = parse_cards(&doc);
    let has_next = has_next(&doc);
    if items.is_empty() && has_next {
        return Err(Error::Parse(format!(
            "unexpected ComicHubFree listing: {url}"
        )));
    }
    Ok(MangaList { items, has_next })
}

pub async fn popular<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    listing(http, &format!("{BASE}/popular-comic?page={}", page.max(1))).await
}

pub async fn latest<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    listing(http, &format!("{BASE}/new-comic?page={}", page.max(1))).await
}

pub async fn search<H: Http>(http: &H, query: &str, page: u32) -> Result<MangaList> {
    listing(
        http,
        &format!(
            "{BASE}/search-comic?key={}&page={}",
            encode(query),
            page.max(1)
        ),
    )
    .await
}

fn has_next(doc: &Html) -> bool {
    doc.select(&sel(r#"ul.pagination a[rel="next"]"#))
        .next()
        .is_some_and(|a| a.value().attr("href").is_some_and(|h| !h.is_empty()))
}

/// Cards with a `.detail` block are series; the rest are ads and banners.
fn parse_cards(doc: &Html) -> Vec<MangaEntry> {
    let mut items = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for card in doc.select(&sel(".movie-list-index > .cartoon-box")) {
        if card.select(&sel(".detail")).next().is_none() {
            continue;
        }
        let Some(slug) = card
            .select(&sel("a[href]"))
            .find_map(|a| a.value().attr("href").and_then(series_slug))
        else {
            continue;
        };
        let Some(title) = card
            .select(&sel("h3"))
            .next()
            .map(text_of)
            .filter(|t| !t.is_empty())
        else {
            continue;
        };
        if !seen.insert(slug.clone()) {
            continue;
        }
        items.push(MangaEntry {
            source_id: slug,
            title,
            cover_url: card.select(&sel("img")).next().and_then(image_of),
        });
    }
    items
}

/// `invincible` from `/comic/invincible` (absolute or not).
fn series_slug(href: &str) -> Option<String> {
    let rest = href.split("/comic/").nth(1)?;
    let slug = rest.split(['/', '?', '#']).next()?;
    valid_slug(slug).then(|| slug.to_string())
}

fn valid_slug(s: &str) -> bool {
    !s.is_empty()
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_' || b == b'.')
        && s != "."
        && s != ".."
}

/// Lazy-loaded images keep the real URL in `data-src`.
fn image_of(img: ElementRef<'_>) -> Option<String> {
    let raw = ["data-src", "src"]
        .iter()
        .filter_map(|k| img.value().attr(k))
        .map(str::trim)
        .find(|u| !u.is_empty() && !u.starts_with("data:"))?;
    if raw.starts_with("http") {
        Some(raw.to_string())
    } else if let Some(rest) = raw.strip_prefix("//") {
        Some(format!("https://{rest}"))
    } else if raw.starts_with('/') {
        Some(format!("{BASE}{raw}"))
    } else {
        None
    }
}

pub async fn series<H: Http>(http: &H, slug: &str) -> Result<SeriesData> {
    if !valid_slug(slug) {
        return Err(Error::Invalid(format!("bad ComicHubFree id {slug:?}")));
    }
    let url = format!("{BASE}/comic/{slug}");
    let mut doc = fetch(http, &url).await?;
    let details = parse_details(&doc, slug, url.clone())?;
    let mut chapters = parse_chapters(&doc, &details.title);
    let mut page = 1;
    while has_next(&doc) && page < MAX_ISSUE_PAGES {
        page += 1;
        doc = fetch(http, &format!("{url}?page={page}")).await?;
        let more = parse_chapters(&doc, &details.title);
        if more.is_empty() {
            break;
        }
        chapters.extend(more);
    }
    let mut seen = std::collections::HashSet::new();
    chapters.retain(|c| seen.insert(c.source_id.clone()));
    Ok(SeriesData { details, chapters })
}

fn parse_details(doc: &Html, slug: &str, url: String) -> Result<MangaDetails> {
    let info = doc
        .select(&sel("div.movie-info"))
        .next()
        .ok_or_else(|| Error::Parse(format!("unexpected ComicHubFree series page: {url}")))?;
    let title = info
        .select(&sel("h1, h2"))
        .next()
        .or_else(|| doc.select(&sel("h1")).next())
        .map(text_of)
        .filter(|t| !t.is_empty())
        .ok_or_else(|| Error::Parse(format!("ComicHubFree page without title: {url}")))?;
    // `<dt>Label:</dt><dd>value</dd>` pairs.
    let field = |label: &str| -> Option<ElementRef<'_>> {
        info.select(&sel(".series-info dt"))
            .find(|dt| {
                text_of(*dt)
                    .trim_end_matches(':')
                    .eq_ignore_ascii_case(label)
            })
            .and_then(|dt| {
                dt.next_siblings()
                    .filter_map(ElementRef::wrap)
                    .find(|e| e.value().name() == "dd")
            })
    };
    let list = |label: &str| -> Vec<String> {
        let Some(dd) = field(label) else {
            return Vec::new();
        };
        let links: Vec<String> = dd
            .select(&sel("a"))
            .map(text_of)
            .filter(|t| !t.is_empty())
            .collect();
        if !links.is_empty() {
            return links;
        }
        text_of(dd)
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("updating"))
            .collect()
    };
    let mut authors = list("Authors");
    if authors.is_empty() {
        authors = list("Author");
    }
    let status = field("Status").and_then(|dd| Status::parse(&text_of(dd)));
    Ok(MangaDetails {
        source_id: slug.to_string(),
        title,
        cover_url: info
            .select(&sel(".series-info img"))
            .next()
            .and_then(image_of),
        description: info
            .select(&sel("#film-content"))
            .next()
            .map(text_of)
            .filter(|d| !d.is_empty()),
        authors,
        genres: list("Genres"),
        status,
        // Western comics: paged, left to right.
        kind: Some(Kind::Comic),
        url,
        ..Default::default()
    })
}

fn parse_chapters(doc: &Html, series_title: &str) -> Vec<ChapterEntry> {
    let mut chapters = Vec::new();
    for row in doc.select(&sel("div.episode-list table tbody tr")) {
        let Some(a) = row.select(&sel("a[href]")).next() else {
            continue;
        };
        let Some(id) = a.value().attr("href").and_then(issue_id) else {
            continue;
        };
        let issue = id.rsplit('/').next().unwrap_or_default();
        let number = issue_number(issue);
        let label = text_of(a);
        // Issues are numbered; trade paperbacks, annuals and specials keep
        // their name and sit beside the numbered run.
        let title = match number {
            Some(_) => None,
            None => {
                let t = label
                    .strip_prefix(series_title)
                    .unwrap_or(&label)
                    .trim_start_matches([' ', '-', ':'])
                    .trim();
                Some(if t.is_empty() {
                    label.clone()
                } else {
                    t.to_string()
                })
                .filter(|t| !t.is_empty())
            }
        };
        chapters.push(ChapterEntry {
            source_id: id,
            number,
            title,
            volume: None,
            scanlator: None,
            published_at: row
                .select(&sel("td"))
                .last()
                .map(text_of)
                .and_then(|d| short_date(&d)),
        });
    }
    chapters
}

/// `invincible/issue-120` from `https://comichubfree.com/invincible/issue-120`.
fn issue_id(href: &str) -> Option<String> {
    let path = href
        .strip_prefix(BASE)
        .or_else(|| href.strip_prefix("https://www.comichubfree.com"))
        .unwrap_or(href);
    let path = path.split(['?', '#']).next()?.trim_matches('/');
    let mut parts = path.split('/');
    let (series, issue) = (parts.next()?, parts.next()?);
    (parts.next().is_none() && series != "comic" && valid_slug(series) && valid_slug(issue))
        .then(|| format!("{series}/{issue}"))
}

/// `issue-120` is 120. Anything else (`tpb-25-…`, `annual-1`, `full`) has
/// no place in the numbered run.
fn issue_number(issue: &str) -> Option<f64> {
    let n = issue.strip_prefix("issue-")?;
    (!n.is_empty() && n.bytes().all(|b| b.is_ascii_digit()))
        .then(|| n.parse().ok())
        .flatten()
}

/// "22-Dec-2020" as RFC 3339 UTC.
fn short_date(raw: &str) -> Option<String> {
    const MONTHS: [&str; 12] = [
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
    ];
    let mut parts = raw.trim().split('-');
    let day: u32 = parts.next()?.trim().parse().ok()?;
    let month = parts.next()?.trim().to_lowercase();
    let month = MONTHS.iter().position(|m| month.starts_with(m))? + 1;
    let year: u32 = parts.next()?.trim().parse().ok()?;
    if parts.next().is_some() || !(1..=31).contains(&day) || year < 1900 {
        return None;
    }
    Some(format!("{year:04}-{month:02}-{day:02}T00:00:00Z"))
}

pub async fn pages<H: Http>(http: &H, chapter_id: &str) -> Result<Vec<PageImage>> {
    let id = issue_id(chapter_id)
        .filter(|id| id == chapter_id)
        .ok_or_else(|| Error::Invalid(format!("bad ComicHubFree chapter id {chapter_id:?}")))?;
    // `/all` puts every page of the issue on one page.
    let url = format!("{BASE}/{id}/all");
    let doc = fetch(http, &url).await?;
    let mut seen = std::collections::HashSet::new();
    Ok(doc
        .select(&sel("img.chapter_img"))
        .filter_map(image_of)
        .filter(|u| seen.insert(u.clone()))
        .map(|url| PageImage {
            url,
            width: None,
            height: None,
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sources::testing::{Fixtures, block_on};

    const POPULAR: &str = include_str!("../../tests/fixtures/comichubfree_popular.html");
    const SERIES: &str = include_str!("../../tests/fixtures/comichubfree_series.html");
    const SERIES_2: &str = include_str!("../../tests/fixtures/comichubfree_series_2.html");
    const READER: &str = include_str!("../../tests/fixtures/comichubfree_reader.html");

    #[test]
    fn parses_listing_cards() {
        let http = Fixtures::new(&[("/popular-comic?page=1", POPULAR)]);
        let list = block_on(popular(&http, 1)).unwrap();
        let ids: Vec<_> = list.items.iter().map(|m| m.source_id.as_str()).collect();
        assert_eq!(ids, ["invincible", "spawn", "the-amazing-spider-man-1963"]);
        assert_eq!(list.items[0].title, "Invincible (2003)");
        assert_eq!(
            list.items[0].cover_url.as_deref(),
            Some("https://comichubfree.com/images/thumbnail/invincible.jpeg")
        );
        // Lazy-loaded card: the real URL is in data-src.
        assert_eq!(
            list.items[1].cover_url.as_deref(),
            Some("https://comichubfree.com/images/thumbnail/spawn.jpeg")
        );
        assert!(list.has_next);
    }

    #[test]
    fn parses_series_across_issue_pages() {
        let http = Fixtures::new(&[
            ("/comic/invincible?page=2", SERIES_2),
            ("/comic/invincible", SERIES),
        ]);
        let s = block_on(series(&http, "invincible")).unwrap();
        let d = &s.details;
        assert_eq!(d.title, "Invincible (2003)");
        assert_eq!(d.authors, vec!["Robert Kirkman"]);
        assert_eq!(d.genres, vec!["Adventure", "Superhero"]);
        assert_eq!(d.status, Some(Status::Completed));
        assert_eq!(d.kind, Some(Kind::Comic));
        assert_eq!(
            d.description.as_deref(),
            Some("Girls, acne, homework, supervillains.")
        );
        assert_eq!(
            d.cover_url.as_deref(),
            Some("https://comichubfree.com/images/thumbnail/invincible.jpeg")
        );
        let c = &s.chapters;
        let ids: Vec<_> = c.iter().map(|c| c.source_id.as_str()).collect();
        assert_eq!(
            ids,
            [
                "invincible/tpb-25-the-end-of-all-things-part-two",
                "invincible/issue-144",
                "invincible/issue-143",
                "invincible/issue-2",
                "invincible/issue-1",
            ]
        );
        assert_eq!(c[0].number, None);
        assert_eq!(
            c[0].title.as_deref(),
            Some("TPB 25: The End of All Things Part Two")
        );
        assert_eq!(c[1].number, Some(144.0));
        assert_eq!(c[1].title, None);
        assert_eq!(c[1].published_at.as_deref(), Some("2018-02-07T00:00:00Z"));
        assert_eq!(c[4].published_at.as_deref(), Some("2003-01-22T00:00:00Z"));
        assert_eq!(http.requests.borrow().len(), 2);
    }

    #[test]
    fn parses_all_pages_once_each() {
        let http = Fixtures::new(&[("/invincible/issue-120/all", READER)]);
        let p = block_on(pages(&http, "invincible/issue-120")).unwrap();
        let urls: Vec<_> = p.iter().map(|p| p.url.as_str()).collect();
        assert_eq!(
            urls,
            [
                "https://2.bp.blogspot.com/inv120/01.jpg",
                "https://2.bp.blogspot.com/inv120/02.jpg",
                "https://comichubfree.com/storage/inv120/03.jpg",
            ]
        );
    }

    #[test]
    fn rejects_malformed_ids() {
        let http = Fixtures::new(&[]);
        assert!(block_on(series(&http, "a/b")).is_err());
        assert!(block_on(series(&http, "..")).is_err());
        assert!(block_on(pages(&http, "invincible")).is_err());
        assert!(block_on(pages(&http, "https://evil.example/a/b")).is_err());
        assert!(block_on(pages(&http, "a/b/c")).is_err());
        assert!(block_on(pages(&http, "comic/invincible")).is_err());
    }

    #[test]
    fn numbers_and_dates() {
        assert_eq!(issue_number("issue-7"), Some(7.0));
        assert_eq!(issue_number("tpb-3-ladies-man"), None);
        assert_eq!(issue_number("annual-1"), None);
        assert_eq!(issue_number("issue-"), None);
        assert_eq!(
            short_date("19-May-2016").as_deref(),
            Some("2016-05-19T00:00:00Z")
        );
        assert_eq!(
            short_date("1-sept-2024").as_deref(),
            Some("2024-09-01T00:00:00Z")
        );
        assert_eq!(short_date("yesterday"), None);
    }
}
