//! weebcentral.com HTMX fragments. No bot check (verified by the Flutter
//! app, Jul 2026).
//!
//! Manga id: `{series ulid}/{slug}`. Chapter id: chapter ulid.

use scraper::Html;

use crate::http::{BROWSER_UA, check_interstitial, get_ok};
use crate::sources::{encode, sel, text_of};
use crate::{
    ChapterEntry, Error, Http, Kind, MangaDetails, MangaEntry, MangaList, PageImage, Result,
    SeriesData, Status, chapter_number,
};

const BASE: &str = "https://weebcentral.com";
const PAGE_SIZE: u32 = 32;
const HEADERS: &[(&str, &str)] = &[
    ("User-Agent", BROWSER_UA),
    (
        "Accept",
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    ),
];

async fn fetch<H: Http>(http: &H, url: &str) -> Result<Html> {
    let body = get_ok(http, url, HEADERS).await?;
    check_interstitial(&body, url)?;
    Ok(Html::parse_document(&body))
}

fn search_url(page: u32, text: &str, sort: &str) -> String {
    let order = if sort == "Best Match" {
        "Ascending"
    } else {
        "Descending"
    };
    format!(
        "{BASE}/search/data?limit={PAGE_SIZE}&offset={}&text={}&sort={}&order={order}&official=Any&display_mode=Full%20Display",
        (page.max(1) - 1) * PAGE_SIZE,
        encode(text),
        encode(sort),
    )
}

pub async fn popular<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    parse_search(&fetch(http, &search_url(page, "", "Popularity")).await?)
}

pub async fn latest<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    parse_search(&fetch(http, &search_url(page, "", "Latest Updates")).await?)
}

pub async fn search<H: Http>(http: &H, query: &str, page: u32) -> Result<MangaList> {
    parse_search(&fetch(http, &search_url(page, query, "Best Match")).await?)
}

pub async fn series<H: Http>(http: &H, id: &str) -> Result<SeriesData> {
    let ulid = id
        .split('/')
        .next()
        .filter(|u| !u.is_empty() && id.contains('/'));
    let Some(ulid) = ulid else {
        return Err(Error::Invalid(format!("bad WeebCentral id {id:?}")));
    };
    let url = format!("{BASE}/series/{id}");
    let doc = fetch(http, &url).await?;
    let list = fetch(http, &format!("{BASE}/series/{ulid}/full-chapter-list")).await?;

    let title = doc
        .select(&sel("h1"))
        .next()
        .map(text_of)
        .filter(|t| !t.is_empty())
        .ok_or_else(|| Error::Parse(format!("WeebCentral page without title: {url}")))?;

    let mut d = MangaDetails {
        source_id: id.to_string(),
        title,
        cover_url: Some(cover_url(ulid)),
        url,
        ..Default::default()
    };
    // Metadata rows: <li><strong>Label: </strong> links… </li>
    for li in doc.select(&sel("li")) {
        let Some(label) = li.select(&sel("strong")).next().map(text_of) else {
            continue;
        };
        let a = sel("a");
        let links: Vec<String> = li
            .select(&a)
            .map(text_of)
            .filter(|t| !t.is_empty())
            .collect();
        if label.starts_with("Author") {
            d.authors = links;
        } else if label.starts_with("Tag") {
            d.genres = links;
        } else if label.starts_with("Type") {
            d.kind = links.first().and_then(|t| Kind::parse(t));
        } else if label.starts_with("Status") {
            d.status = links.first().and_then(|t| Status::parse(t));
        } else if label.starts_with("Associated Name") {
            d.alt_titles = li
                .select(&sel("li"))
                .map(text_of)
                .filter(|t| !t.is_empty())
                .collect();
        } else if label == "Description" {
            d.description = li
                .select(&sel("p"))
                .next()
                .map(|p| p.text().collect::<String>().trim().to_string())
                .filter(|t| !t.is_empty());
        }
    }

    let mut chapters = Vec::new();
    for a in list.select(&sel(r#"a[href*="/chapters/"]"#)) {
        let Some(href) = a.value().attr("href") else {
            continue;
        };
        let Some(chapter_ulid) = href
            .split("/chapters/")
            .nth(1)
            .and_then(|r| r.split(['/', '?']).next())
        else {
            continue;
        };
        let label = a
            .select(&sel("span.grow > span"))
            .next()
            .map(text_of)
            .unwrap_or_else(|| text_of(a));
        if label.is_empty() {
            continue;
        }
        chapters.push(ChapterEntry {
            source_id: chapter_ulid.to_string(),
            number: chapter_number(&label),
            title: Some(label),
            volume: None,
            scanlator: None,
            published_at: a
                .select(&sel("time[datetime]"))
                .next()
                .and_then(|t| t.value().attr("datetime"))
                .map(str::to_string),
        });
    }
    if chapters.is_empty() && !is_explicitly_empty(&list) {
        return Err(Error::Parse(format!(
            "WeebCentral chapter list had no chapters: {id}"
        )));
    }
    // Labels are just "Chapter 12"; the number already says that.
    for c in &mut chapters {
        if c.number.is_some()
            && c.title
                .as_deref()
                .is_some_and(|t| t.to_lowercase().starts_with("chapter"))
        {
            c.title = None;
        }
    }
    Ok(SeriesData {
        details: d,
        chapters,
    })
}

pub async fn pages<H: Http>(http: &H, chapter_id: &str) -> Result<Vec<PageImage>> {
    if chapter_id.is_empty() || chapter_id.contains('/') {
        return Err(Error::Invalid(format!(
            "bad WeebCentral chapter id {chapter_id:?}"
        )));
    }
    let doc = fetch(
        http,
        &format!("{BASE}/chapters/{chapter_id}/images?is_prev=False&reading_style=long_strip"),
    )
    .await?;
    let pages: Vec<PageImage> = doc
        .select(&sel("img[src]"))
        .filter_map(|img| {
            let src = img.value().attr("src").filter(|s| s.starts_with("http"))?;
            Some(PageImage {
                url: src.to_string(),
                width: img.value().attr("width").and_then(|w| w.parse().ok()),
                height: img.value().attr("height").and_then(|h| h.parse().ok()),
            })
        })
        .collect();
    if pages.is_empty() {
        return Err(Error::Parse(format!(
            "WeebCentral chapter without images: {chapter_id}"
        )));
    }
    Ok(pages)
}

fn parse_search(doc: &Html) -> Result<MangaList> {
    let mut items = Vec::new();
    let mut seen = std::collections::HashSet::new();
    // Cards nest <article> cover elements inside the outer <article>.
    for article in doc.select(&sel("article")) {
        let Some(link) = article.select(&sel(r#"a[href*="/series/"]"#)).next() else {
            continue;
        };
        let Some(id) = link.value().attr("href").and_then(series_id) else {
            continue;
        };
        if !seen.insert(id.clone()) {
            continue;
        }
        let mut title = article
            .select(&sel(r#"section a[href*="/series/"].link"#))
            .next()
            .map(text_of)
            .unwrap_or_default();
        if title.is_empty() {
            title = article
                .select(&sel("img[alt]"))
                .next()
                .and_then(|i| i.value().attr("alt"))
                .map(|a| a.trim_end_matches(" cover").trim().to_string())
                .unwrap_or_default();
        }
        if title.is_empty() {
            continue;
        }
        let ulid = id.split('/').next().unwrap_or_default().to_string();
        items.push(MangaEntry {
            source_id: id,
            title,
            cover_url: Some(cover_url(&ulid)),
        });
    }
    if items.is_empty() && !is_explicitly_empty(doc) {
        return Err(Error::Parse(
            "unexpected WeebCentral search response".into(),
        ));
    }
    let has_next = items.len() as u32 >= PAGE_SIZE;
    Ok(MangaList { items, has_next })
}

/// `{ulid}/{slug}` from a series URL.
fn series_id(href: &str) -> Option<String> {
    let rest = href.split("/series/").nth(1)?;
    let mut parts = rest.split(['/', '?']);
    let (ulid, slug) = (parts.next()?, parts.next()?);
    (!ulid.is_empty() && !slug.is_empty()).then(|| format!("{ulid}/{slug}"))
}

fn cover_url(ulid: &str) -> String {
    format!("https://temp.compsci88.com/cover/normal/{ulid}.webp")
}

fn is_explicitly_empty(doc: &Html) -> bool {
    let text = doc.root_element().text().collect::<String>().to_lowercase();
    text.contains("no results") || text.contains("no chapters") || text.trim().is_empty()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sources::testing::{Fixtures, block_on};

    const SEARCH: &str = include_str!("../../tests/fixtures/weebcentral_search.html");
    const SERIES: &str = include_str!("../../tests/fixtures/weebcentral_series.html");
    const CHAPTERS: &str = include_str!("../../tests/fixtures/weebcentral_chapters.html");
    const IMAGES: &str = include_str!("../../tests/fixtures/weebcentral_images.html");

    #[test]
    fn parses_cards_without_duplicating_nested_articles() {
        let http = Fixtures::new(&[("/search/data", SEARCH)]);
        let list = block_on(popular(&http, 1)).unwrap();
        assert_eq!(list.items.len(), 2);
        assert_eq!(
            list.items[0].source_id,
            "01J76XYCPSY3C4BNPBRY8JMCBE/Solo-Leveling"
        );
        assert_eq!(list.items[0].title, "Solo Leveling");
        assert_eq!(
            list.items[0].cover_url.as_deref(),
            Some("https://temp.compsci88.com/cover/normal/01J76XYCPSY3C4BNPBRY8JMCBE.webp")
        );
        assert_eq!(list.items[1].title, "Solo Leveling: Ragnarok");
        assert!(!list.has_next);
        let url = &http.requests.borrow()[0];
        assert!(url.contains("sort=Popularity") && url.contains("offset=0"));
    }

    #[test]
    fn parses_series_and_chapters() {
        let http = Fixtures::new(&[
            ("/full-chapter-list", CHAPTERS),
            ("/series/01J76XYCPSY3C4BNPBRY8JMCBE/Solo-Leveling", SERIES),
        ]);
        let s = block_on(series(&http, "01J76XYCPSY3C4BNPBRY8JMCBE/Solo-Leveling")).unwrap();
        let d = &s.details;
        assert_eq!(d.title, "Solo Leveling");
        assert_eq!(d.authors, vec!["GEE So-Lyung", "JANG Sung-Lak"]);
        assert_eq!(d.genres, vec!["Action", "Adventure", "Fantasy"]);
        assert_eq!(d.kind, Some(Kind::Manhwa));
        assert_eq!(d.status, Some(Status::Completed));
        assert_eq!(
            d.alt_titles,
            vec!["Na Honjaman Level Up", "나 혼자만 레벨업"]
        );
        assert_eq!(
            d.description.as_deref(),
            Some("E-class hunter Jinwoo Sung is the weakest of them all.")
        );
        assert_eq!(s.chapters.len(), 3);
        assert_eq!(s.chapters[0].source_id, "01J76XZ666GREP4DQDKEP1YDZG");
        assert_eq!(s.chapters[0].number, Some(200.0));
        assert_eq!(s.chapters[0].title, None);
        assert_eq!(
            s.chapters[0].published_at.as_deref(),
            Some("2024-09-07T17:04:15.717Z")
        );
        assert_eq!(s.chapters[1].number, Some(199.5));
        assert_eq!(s.chapters[2].number, None);
        assert_eq!(s.chapters[2].title.as_deref(), Some("Side Story"));
    }

    #[test]
    fn parses_images() {
        let http = Fixtures::new(&[("/chapters/01J76XZ666GREP4DQDKEP1YDZG/images", IMAGES)]);
        let p = block_on(pages(&http, "01J76XZ666GREP4DQDKEP1YDZG")).unwrap();
        assert_eq!(p.len(), 3);
        assert_eq!(
            p[0].url,
            "https://hot.planeptune.us/manga/Solo-Leveling/0200-001.png"
        );
        assert_eq!(p[2].height, Some(1899));
    }

    #[test]
    fn empty_search_is_not_an_error() {
        let http = Fixtures::new(&[("/search/data", "<div>No results found</div>")]);
        assert!(block_on(search(&http, "zzz", 1)).unwrap().items.is_empty());
    }
}
