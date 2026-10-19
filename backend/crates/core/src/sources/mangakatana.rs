//! mangakatana.com HTML scraper. Images need no headers.
//!
//! Manga id: `{slug}.{numeric id}` (from `/manga/{id}`).
//! Chapter id: `{manga id}/c{n}` (the chapter page path under `/manga/`).

use std::sync::OnceLock;

use scraper::Html;

use crate::http::{BROWSER_UA, check_interstitial, get_ok};
use crate::sources::{encode, sel, strip_html, text_of};
use crate::{
    ChapterEntry, Error, Http, Kind, MangaDetails, MangaEntry, MangaList, PageImage, Result,
    SeriesData, Status, chapter_number,
};

const BASE: &str = "https://mangakatana.com";
const MANGA: &str = "https://mangakatana.com/manga/";
const HEADERS: &[(&str, &str)] = &[
    ("User-Agent", BROWSER_UA),
    ("Referer", "https://mangakatana.com/"),
];

async fn fetch<H: Http>(http: &H, url: &str) -> Result<Html> {
    let body = get_ok(http, url, HEADERS).await?;
    check_interstitial(&body, url)?;
    Ok(Html::parse_document(&body))
}

/// MangaKatana has no popularity sort. Page 1 is the home page: its "Hot
/// updates" carousel first, then the latest updates; later pages continue
/// the latest list.
pub async fn popular<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    if page > 1 {
        return latest(http, page).await;
    }
    let doc = fetch(http, BASE).await?;
    let items = parse_list(&doc, "#hot_update .item, #book_list .item");
    if items.is_empty() {
        return Err(Error::Parse("MangaKatana home page had no manga".into()));
    }
    Ok(MangaList {
        items,
        has_next: has_next_page(&doc),
    })
}

pub async fn latest<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    let url = match page.max(1) {
        1 => format!("{BASE}/latest"),
        n => format!("{BASE}/latest/page/{n}"),
    };
    let doc = fetch(http, &url).await?;
    let items = parse_list(&doc, "#book_list .item");
    if items.is_empty() {
        return Err(Error::Parse("MangaKatana latest page had no manga".into()));
    }
    Ok(MangaList {
        items,
        has_next: has_next_page(&doc),
    })
}

/// A search with no results answers an empty body.
pub async fn search<H: Http>(http: &H, query: &str, page: u32) -> Result<MangaList> {
    let path = match page.max(1) {
        1 => String::new(),
        n => format!("page/{n}"),
    };
    let doc = fetch(
        http,
        &format!("{BASE}/{path}?search={}&search_by=book_name", encode(query)),
    )
    .await?;
    Ok(MangaList {
        items: parse_list(&doc, "#book_list .item"),
        has_next: has_next_page(&doc),
    })
}

pub async fn series<H: Http>(http: &H, id: &str) -> Result<SeriesData> {
    if id.is_empty() || id.contains('/') {
        return Err(Error::Invalid(format!("bad MangaKatana id {id:?}")));
    }
    let url = format!("{MANGA}{id}");
    let doc = fetch(http, &url).await?;
    let title = doc
        .select(&sel("h1.heading"))
        .next()
        .map(text_of)
        .filter(|t| !t.is_empty())
        .ok_or_else(|| Error::Parse(format!("MangaKatana page without title: {url}")))?;

    let alt_titles = doc
        .select(&sel(".alt_name"))
        .next()
        .map(text_of)
        .map(|alts| {
            alts.split(';')
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty() && *s != title)
                .collect()
        })
        .unwrap_or_default();
    // The site lists authors and artists together.
    let authors = doc
        .select(&sel(".authors a"))
        .map(text_of)
        .filter(|a| !a.is_empty() && !a.eq_ignore_ascii_case("unknown"))
        .collect();
    let genres: Vec<String> = doc
        .select(&sel(".info .genres a"))
        .map(text_of)
        .filter(|g| !g.is_empty())
        .collect();
    // Some series carry their kind ("Manhwa", "Manhua", "Webtoon") as a genre.
    let kind = genres.iter().find_map(|g| Kind::parse(g));

    let details = MangaDetails {
        source_id: id.to_string(),
        title,
        alt_titles,
        cover_url: doc
            .select(&sel("#single_book .cover img"))
            .next()
            .and_then(|i| i.value().attr("src"))
            .map(str::to_string),
        description: doc
            .select(&sel(".summary p"))
            .next()
            .map(|p| strip_html(&p.inner_html()))
            .filter(|d| !d.is_empty()),
        authors,
        genres,
        status: doc
            .select(&sel(".value.status"))
            .next()
            .and_then(|s| Status::parse(&text_of(s))),
        kind,
        url,
        ..Default::default()
    };

    let mut chapters = Vec::new();
    for row in doc.select(&sel(".chapters tr")) {
        let Some(a) = row.select(&sel(".chapter a")).next() else {
            continue;
        };
        let Some(path) = a.value().attr("href").and_then(|h| h.strip_prefix(MANGA)) else {
            continue;
        };
        let label = text_of(a);
        chapters.push(ChapterEntry {
            source_id: path.to_string(),
            number: chapter_number(&label),
            title: label
                .split_once(':')
                .map(|(_, t)| t.trim().to_string())
                .filter(|t| !t.is_empty()),
            volume: None,
            scanlator: None,
            published_at: row
                .select(&sel(".update_time"))
                .next()
                .and_then(|d| list_date(&text_of(d))),
        });
    }
    Ok(SeriesData { details, chapters })
}

/// The page links are a JavaScript array with a random name, next to a
/// one-item decoy; the real one has as many links as the reader has page
/// slots. `cds` holds each page's "WxH".
pub async fn pages<H: Http>(http: &H, chapter_id: &str) -> Result<Vec<PageImage>> {
    if chapter_id.split('/').count() != 2 {
        return Err(Error::Invalid(format!(
            "bad MangaKatana chapter id {chapter_id:?}"
        )));
    }
    let body = get_ok(http, &format!("{MANGA}{chapter_id}"), HEADERS).await?;
    check_interstitial(&body, chapter_id)?;
    static ARRAY: OnceLock<regex::Regex> = OnceLock::new();
    static LINK: OnceLock<regex::Regex> = OnceLock::new();
    static SLOTS: OnceLock<regex::Regex> = OnceLock::new();
    static SIZES: OnceLock<regex::Regex> = OnceLock::new();
    let array = ARRAY.get_or_init(|| regex::Regex::new(r"var\s+(\w+)\s*=\s*\[([^\]]*)\]").unwrap());
    let link = LINK.get_or_init(|| regex::Regex::new(r"'(https?://[^']+)'").unwrap());
    let slots = SLOTS.get_or_init(|| regex::Regex::new(r#"data-pages="(\d+)""#).unwrap());
    let sizes = SIZES.get_or_init(|| regex::Regex::new(r#""(\d+)x(\d+)""#).unwrap());

    let arrays: Vec<Vec<&str>> = array
        .captures_iter(&body)
        .map(|c| {
            link.captures_iter(c.get(2).unwrap().as_str())
                .map(|l| l.get(1).unwrap().as_str())
                .collect()
        })
        .collect();
    let expected: Option<usize> = slots.captures(&body).and_then(|c| c[1].parse().ok());
    let links = arrays
        .iter()
        .find(|l| !l.is_empty() && Some(l.len()) == expected)
        .or_else(|| arrays.iter().max_by_key(|l| l.len()))
        .filter(|l| !l.is_empty())
        .ok_or_else(|| Error::Parse(format!("MangaKatana chapter without images: {chapter_id}")))?;
    let dims: Vec<(u32, u32)> = array
        .captures_iter(&body)
        .find(|c| &c[1] == "cds")
        .map(|c| {
            sizes
                .captures_iter(c.get(2).unwrap().as_str())
                .filter_map(|s| Some((s[1].parse().ok()?, s[2].parse().ok()?)))
                .collect()
        })
        .unwrap_or_default();
    let dims = (dims.len() == links.len()).then_some(dims);
    Ok(links
        .iter()
        .enumerate()
        .map(|(i, url)| PageImage {
            url: url.to_string(),
            width: dims.as_ref().map(|d| d[i].0),
            height: dims.as_ref().map(|d| d[i].1),
        })
        .collect())
}

fn parse_list(doc: &Html, items: &str) -> Vec<MangaEntry> {
    let mut out = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for item in doc.select(&sel(items)) {
        let Some(link) = item.select(&sel("h3.title a")).next() else {
            continue;
        };
        let Some(source_id) = link
            .value()
            .attr("href")
            .and_then(|h| h.strip_prefix(MANGA))
            .filter(|id| !id.is_empty() && !id.contains('/'))
        else {
            continue;
        };
        let title = text_of(link);
        if title.is_empty() || !seen.insert(source_id.to_string()) {
            continue;
        }
        out.push(MangaEntry {
            source_id: source_id.to_string(),
            title,
            cover_url: item
                .select(&sel(".wrap_img img"))
                .next()
                .and_then(|i| i.value().attr("src"))
                .map(str::to_string),
        });
    }
    out
}

fn has_next_page(doc: &Html) -> bool {
    doc.select(&sel("a.next.page-numbers")).next().is_some()
}

/// "Jun-16-2018" as a date. Relative times ("2 hours ago") give `None`.
fn list_date(raw: &str) -> Option<String> {
    const MONTHS: [&str; 12] = [
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
    ];
    let mut parts = raw.trim().split('-');
    let word = parts.next()?.to_lowercase();
    let month = MONTHS.iter().position(|m| *m == word)? + 1;
    let day: u32 = parts.next()?.parse().ok()?;
    let year: u32 = parts.next()?.parse().ok()?;
    if parts.next().is_some() || !(1..=31).contains(&day) {
        return None;
    }
    Some(format!("{year:04}-{month:02}-{day:02}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sources::testing::{Fixtures, block_on};

    const HOME: &str = include_str!("../../tests/fixtures/mangakatana_home.html");
    const LIST: &str = include_str!("../../tests/fixtures/mangakatana_list.html");
    const SERIES: &str = include_str!("../../tests/fixtures/mangakatana_series.html");
    const CHAPTER: &str = include_str!("../../tests/fixtures/mangakatana_chapter.html");

    #[test]
    fn popular_puts_hot_updates_first_once_per_manga() {
        let http = Fixtures::new(&[("mangakatana.com", HOME)]);
        let list = block_on(popular(&http, 1)).unwrap();
        let ids: Vec<_> = list.items.iter().map(|m| m.source_id.as_str()).collect();
        assert_eq!(ids, ["dandadan.25818", "gantze.24255", "mad.27586"]);
        assert_eq!(list.items[1].title, "Gantz:E");
        assert_eq!(
            list.items[0].cover_url.as_deref(),
            Some("https://mangakatana.com/imgs/cover/09c/1e/5ab79.webp")
        );
        assert!(list.has_next);
        assert_eq!(http.requests.borrow()[0], "https://mangakatana.com");
    }

    #[test]
    fn later_popular_pages_follow_latest() {
        let http = Fixtures::new(&[("/latest/page/3", HOME)]);
        assert!(block_on(popular(&http, 3)).is_ok());
    }

    #[test]
    fn parses_search() {
        let http = Fixtures::new(&[("search=death+note", LIST)]);
        let list = block_on(search(&http, "death note", 1)).unwrap();
        let ids: Vec<_> = list.items.iter().map(|m| m.source_id.as_str()).collect();
        assert_eq!(ids, ["death-note.6685", "death-note-short-stories.6705"]);
        assert_eq!(
            list.items[0].cover_url.as_deref(),
            Some("https://mangakatana.com/imgs/cover/04e/22/21396.jpg")
        );
        assert!(list.has_next);
        let http = Fixtures::new(&[("/page/2?search=x", LIST)]);
        assert!(block_on(search(&http, "x", 2)).is_ok());
    }

    #[test]
    fn empty_search_is_not_an_error() {
        let http = Fixtures::new(&[("search=", "")]);
        let list = block_on(search(&http, "zzz", 1)).unwrap();
        assert!(list.items.is_empty() && !list.has_next);
    }

    #[test]
    fn parses_series_page() {
        let http = Fixtures::new(&[("/manga/death-note.6685", SERIES)]);
        let s = block_on(series(&http, "death-note.6685")).unwrap();
        let d = &s.details;
        assert_eq!(d.title, "Death Note");
        assert_eq!(d.alt_titles, vec!["デスノート", "Desu Nôto"]);
        assert_eq!(d.authors, vec!["Ohba Tsugumi", "Obata Takeshi"]);
        assert_eq!(d.genres, vec!["Mystery", "Shounen"]);
        assert_eq!(d.kind, None);
        assert_eq!(d.status, Some(Status::Completed));
        assert_eq!(
            d.cover_url.as_deref(),
            Some("https://mangakatana.com/imgs/cover/04e/22/21396.jpg")
        );
        assert_eq!(
            d.description.as_deref(),
            Some("Light Yagami is an ace student.\n\nWon the Eagle Award & more.")
        );
        let ids: Vec<_> = s.chapters.iter().map(|c| c.source_id.as_str()).collect();
        assert_eq!(
            ids,
            [
                "death-note.6685/c110",
                "death-note.6685/c1.5",
                "death-note.6685/c1"
            ]
        );
        assert_eq!(s.chapters[0].number, Some(110.0));
        assert_eq!(s.chapters[0].title.as_deref(), Some("How To Read"));
        assert_eq!(s.chapters[0].published_at.as_deref(), Some("2019-06-04"));
        assert_eq!(s.chapters[1].number, Some(1.5));
        assert_eq!(s.chapters[1].title, None);
        assert_eq!(s.chapters[2].published_at, None);
        assert!(block_on(series(&http, "a/b")).is_err());
    }

    #[test]
    fn manhwa_kind_comes_from_genres() {
        let page = SERIES.replace(">Mystery<", ">Manhwa<");
        let http = Fixtures::new(&[("/manga/x.1", page.as_str())]);
        let s = block_on(series(&http, "x.1")).unwrap();
        assert_eq!(s.details.kind, Some(Kind::Manhwa));
    }

    #[test]
    fn picks_the_real_page_array_with_sizes() {
        let http = Fixtures::new(&[("/manga/death-note.6685/c1", CHAPTER)]);
        let p = block_on(pages(&http, "death-note.6685/c1")).unwrap();
        assert_eq!(p.len(), 3);
        assert_eq!(
            p[2].url,
            "https://i1.mangakatana.com/token/906a171c3010284729w2r6/2.jpg"
        );
        assert_eq!((p[0].width, p[0].height), (Some(1365), Some(2048)));
        assert_eq!((p[2].width, p[2].height), (Some(720), Some(4000)));
        assert!(block_on(pages(&http, "c1")).is_err());
    }

    #[test]
    fn rejects_interstitial() {
        let http = Fixtures::new(&[(
            "/latest",
            "<html><head><title>Just a moment...</title></head></html>",
        )]);
        assert!(matches!(block_on(latest(&http, 1)), Err(Error::Parse(_))));
    }

    #[test]
    fn list_dates() {
        assert_eq!(list_date("Jun-16-2018").as_deref(), Some("2018-06-16"));
        assert_eq!(list_date("Oct-20-2026").as_deref(), Some("2026-10-20"));
        assert_eq!(list_date("19 minutes ago"), None);
        assert_eq!(list_date("Foo-1-2020"), None);
    }
}
