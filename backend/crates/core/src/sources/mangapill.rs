//! mangapill.com HTML scraper. Images need `Referer: https://mangapill.com/`.
//!
//! Manga id: `{numeric id}/{slug}` (from `/manga/{id}/{slug}`).
//! Chapter id: `chapters/{id}-{n}/{slug}` (the chapter page path).

use scraper::{ElementRef, Html};

use crate::http::{BROWSER_UA, check_interstitial, get_ok};
use crate::sources::{encode, sel, text_of};
use crate::{
    ChapterEntry, Error, Http, Kind, MangaDetails, MangaEntry, MangaList, PageImage, Result,
    SeriesData, Status, chapter_number,
};

const BASE: &str = "https://mangapill.com";
const HEADERS: &[(&str, &str)] = &[
    ("User-Agent", BROWSER_UA),
    ("Referer", "https://mangapill.com/"),
];

async fn fetch<H: Http>(http: &H, url: &str) -> Result<Html> {
    let body = get_ok(http, url, HEADERS).await?;
    check_interstitial(&body, url)?;
    Ok(Html::parse_document(&body))
}

/// MangaPill has no popularity listing; its recent-chapters feed is the
/// best signal of what is being read, so popular and latest share it.
pub async fn popular<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    latest(http, page).await
}

pub async fn latest<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    let doc = fetch(http, &format!("{BASE}/chapters?page={}", page.max(1))).await?;
    let items = parse_grid(&doc);
    if items.is_empty() {
        return Err(Error::Parse("MangaPill chapters page had no manga".into()));
    }
    let has_next = has_next_page(&doc, page);
    Ok(MangaList { items, has_next })
}

/// An empty query lists the whole catalog, which the crawler uses for backfill.
pub async fn search<H: Http>(http: &H, query: &str, page: u32) -> Result<MangaList> {
    let doc = fetch(
        http,
        &format!("{BASE}/search?q={}&page={}", encode(query), page.max(1)),
    )
    .await?;
    let items = parse_grid(&doc);
    let has_next = has_next_page(&doc, page);
    Ok(MangaList { items, has_next })
}

pub async fn series<H: Http>(http: &H, id: &str) -> Result<SeriesData> {
    let url = format!("{BASE}/manga/{id}");
    let doc = fetch(http, &url).await?;
    let title = doc
        .select(&sel("h1"))
        .next()
        .map(text_of)
        .filter(|t| !t.is_empty())
        .ok_or_else(|| Error::Parse(format!("MangaPill page without title: {url}")))?;

    // Alternate titles sit in the small line right under the <h1>.
    let alt_titles = doc
        .select(&sel("h1 + div"))
        .next()
        .map(text_of)
        .map(|alts| {
            alts.split(&[';', ','][..])
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty() && *s != title)
                .collect()
        })
        .unwrap_or_default();

    // Metadata rows: <div><label>Type</label><div>manhwa</div></div>
    let mut kind = None;
    let mut status = None;
    for label in doc.select(&sel("label")) {
        let value = label
            .next_siblings()
            .filter_map(ElementRef::wrap)
            .next()
            .map(text_of)
            .unwrap_or_default();
        match text_of(label).to_lowercase().as_str() {
            "type" => kind = Kind::parse(&value),
            "status" => status = Status::parse(&value),
            _ => {}
        }
    }

    let genres = doc
        .select(&sel(r#"a[href*="genre="]"#))
        .map(text_of)
        .filter(|g| !g.is_empty())
        .collect();

    let details = MangaDetails {
        source_id: id.to_string(),
        title,
        alt_titles,
        cover_url: doc
            .select(&sel("img[data-src]"))
            .next()
            .and_then(|i| i.value().attr("data-src"))
            .map(str::to_string),
        description: doc
            .select(&sel("p.text--secondary"))
            .next()
            .map(|p| p.text().collect::<String>().trim().to_string())
            .filter(|d| !d.is_empty()),
        genres,
        status,
        kind,
        url,
        ..Default::default()
    };

    let mut chapters = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for a in doc.select(&sel(
        r#"#chapters a[href^="/chapters/"], a[href^="/chapters/"]"#,
    )) {
        let Some(href) = a.value().attr("href") else {
            continue;
        };
        let path = href.trim_start_matches('/');
        // Real chapter links look like chapters/{id}-{n}/{slug}.
        if path.split('/').count() != 3 || !seen.insert(path.to_string()) {
            continue;
        }
        let label = text_of(a);
        chapters.push(ChapterEntry {
            source_id: path.to_string(),
            number: chapter_number(&label),
            title: None,
            volume: None,
            scanlator: None,
            published_at: None,
        });
    }
    Ok(SeriesData { details, chapters })
}

pub async fn pages<H: Http>(http: &H, chapter_id: &str) -> Result<Vec<PageImage>> {
    if !chapter_id.starts_with("chapters/") {
        return Err(Error::Invalid(format!(
            "bad MangaPill chapter id {chapter_id:?}"
        )));
    }
    let doc = fetch(http, &format!("{BASE}/{chapter_id}")).await?;
    let pages: Vec<PageImage> = doc
        .select(&sel("chapter-page img, picture img"))
        .filter_map(|img| {
            img.value()
                .attr("data-src")
                .or_else(|| img.value().attr("src"))
        })
        .filter(|src| src.starts_with("http"))
        .map(|src| PageImage {
            url: src.to_string(),
            width: None,
            height: None,
        })
        .collect();
    if pages.is_empty() {
        return Err(Error::Parse(format!(
            "MangaPill chapter without images: {chapter_id}"
        )));
    }
    Ok(pages)
}

fn parse_grid(doc: &Html) -> Vec<MangaEntry> {
    let mut items = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for card in doc.select(&sel(".grid > div")) {
        let Some(link) = card.select(&sel(r#"a[href^="/manga/"]"#)).next() else {
            continue;
        };
        let Some(href) = link.value().attr("href") else {
            continue;
        };
        let source_id = href.trim_start_matches("/manga/").to_string();
        if source_id.is_empty() || !seen.insert(source_id.clone()) {
            continue;
        }
        let title = card
            .select(&sel(".line-clamp-2"))
            .next()
            .map(text_of)
            .filter(|t| !t.is_empty())
            .unwrap_or_else(|| text_of(link));
        if title.is_empty() {
            continue;
        }
        let cover_url = card
            .select(&sel("img"))
            .next()
            .and_then(|i| i.value().attr("data-src").or_else(|| i.value().attr("src")))
            .map(str::to_string);
        items.push(MangaEntry {
            source_id,
            title,
            cover_url,
        });
    }
    items
}

fn has_next_page(doc: &Html, page: u32) -> bool {
    let next = format!("page={}", page.max(1) + 1);
    doc.select(&sel("a[href]"))
        .any(|a| a.value().attr("href").is_some_and(|h| h.contains(&next)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sources::testing::{Fixtures, block_on};

    const CHAPTERS: &str = include_str!("../../tests/fixtures/mangapill_chapters.html");
    const MANGA: &str = include_str!("../../tests/fixtures/mangapill_manga.html");
    const READER: &str = include_str!("../../tests/fixtures/mangapill_chapter.html");

    #[test]
    fn parses_recent_chapters_grid_once_per_manga() {
        let http = Fixtures::new(&[("/chapters?page=1", CHAPTERS)]);
        let list = block_on(latest(&http, 1)).unwrap();
        let ids: Vec<_> = list.items.iter().map(|m| m.source_id.as_str()).collect();
        assert_eq!(ids, ["7529/kagurabachi", "2/one-piece"]);
        assert_eq!(list.items[0].title, "Kagurabachi");
        assert_eq!(
            list.items[0].cover_url.as_deref(),
            Some("https://cdn.readdetectiveconan.com/file/mangapill/i/7529.jpeg")
        );
        assert!(list.has_next);
    }

    #[test]
    fn parses_series_page() {
        let http = Fixtures::new(&[("/manga/7529/kagurabachi", MANGA)]);
        let s = block_on(series(&http, "7529/kagurabachi")).unwrap();
        let d = &s.details;
        assert_eq!(d.title, "Kagurabachi");
        assert_eq!(d.alt_titles, vec!["カグラバチ"]);
        assert_eq!(d.kind, Some(Kind::Manga));
        assert_eq!(d.status, Some(Status::Ongoing));
        assert_eq!(d.genres, vec!["Action", "Supernatural"]);
        assert_eq!(
            d.description.as_deref(),
            Some("Chihiro seeks revenge with his father's swords.")
        );
        let ids: Vec<_> = s.chapters.iter().map(|c| c.source_id.as_str()).collect();
        assert_eq!(
            ids,
            [
                "chapters/7529-10111000/kagurabachi-chapter-111",
                "chapters/7529-10110500/kagurabachi-chapter-110.5"
            ]
        );
        assert_eq!(s.chapters[1].number, Some(110.5));
    }

    #[test]
    fn parses_reader_images() {
        let http = Fixtures::new(&[("/chapters/7529-10111000", READER)]);
        let p = block_on(pages(
            &http,
            "chapters/7529-10111000/kagurabachi-chapter-111",
        ))
        .unwrap();
        assert_eq!(p.len(), 2);
        assert_eq!(
            p[1].url,
            "https://cdn.readdetectiveconan.com/file/mangap/7529/10111000/2.jpeg"
        );
    }

    #[test]
    fn rejects_interstitial() {
        let http = Fixtures::new(&[(
            "/chapters?",
            "<html><head><title>Just a moment...</title></head></html>",
        )]);
        assert!(matches!(block_on(latest(&http, 1)), Err(Error::Parse(_))));
    }
}
