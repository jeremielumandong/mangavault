//! AsuraScans public JSON API (api.asurascans.com). No auth, no bot check.
//!
//! Manga id: series slug. Chapter id: `{series slug}/chapters/{chapter slug}`.

use serde_json::Value;

use crate::http::get_ok;
use crate::sources::{encode, strip_html};
use crate::{
    ChapterEntry, Error, Http, Kind, MangaDetails, MangaEntry, MangaList, PageImage, Result,
    SeriesData, Status,
};

const API: &str = "https://api.asurascans.com/api";
const SITE: &str = "https://asurascans.com";
const PAGE_SIZE: u32 = 20;
const HEADERS: &[(&str, &str)] = &[
    ("User-Agent", "Mozilla/5.0"),
    ("Accept", "application/json"),
];

async fn get_json<H: Http>(http: &H, url: &str) -> Result<Value> {
    Ok(serde_json::from_str(&get_ok(http, url, HEADERS).await?)?)
}

pub async fn popular<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    parse_list(
        &get_json(
            http,
            &format!(
                "{API}/series?page={}&order=rating&count={PAGE_SIZE}",
                page.max(1)
            ),
        )
        .await?,
    )
}

pub async fn latest<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    parse_list(
        &get_json(
            http,
            &format!(
                "{API}/series?page={}&order=update&count={PAGE_SIZE}",
                page.max(1)
            ),
        )
        .await?,
    )
}

/// The search endpoint is not paginated.
pub async fn search<H: Http>(http: &H, query: &str, page: u32) -> Result<MangaList> {
    if page > 1 {
        return Ok(MangaList {
            items: vec![],
            has_next: false,
        });
    }
    let mut list =
        parse_list(&get_json(http, &format!("{API}/search?q={}", encode(query))).await?)?;
    list.has_next = false;
    Ok(list)
}

pub async fn series<H: Http>(http: &H, slug: &str) -> Result<SeriesData> {
    if slug.is_empty() || slug.contains('/') {
        return Err(Error::Invalid(format!("bad AsuraScans slug {slug:?}")));
    }
    let detail = get_json(http, &format!("{API}/series/{slug}")).await?;
    let chapters = get_json(http, &format!("{API}/series/{slug}/chapters")).await?;

    let s = if detail["series"].is_object() {
        &detail["series"]
    } else {
        &detail
    };
    let title = s["title"]
        .as_str()
        .filter(|t| !t.is_empty())
        .ok_or_else(|| Error::Parse(format!("AsuraScans series without title: {slug}")))?
        .to_string();
    let list = |key: &str| -> Vec<String> {
        match &s[key] {
            Value::String(v) if !v.is_empty() => vec![v.clone()],
            Value::Array(a) => a
                .iter()
                .filter_map(|v| v.as_str().map(str::to_string))
                .collect(),
            _ => vec![],
        }
    };
    let alt_titles = s["alternative_titles"]
        .as_str()
        .map(|t| {
            t.split(['|', ',', ';'])
                .map(|x| x.trim().to_string())
                .filter(|x| !x.is_empty())
                .collect()
        })
        .unwrap_or_else(|| list("alternative_titles"));
    let description = s["description"]
        .as_str()
        .map(strip_html)
        .filter(|d| !d.is_empty());
    let url = s["public_url"]
        .as_str()
        .map(|p| format!("{SITE}{p}"))
        .unwrap_or_else(|| format!("{SITE}/series/{slug}"));

    let details = MangaDetails {
        source_id: slug.to_string(),
        title,
        alt_titles,
        cover_url: thumbnail_cover(s["cover"].as_str(), 400),
        description,
        authors: list("author"),
        artists: list("artist"),
        genres: s["genres"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|g| g["name"].as_str().map(str::to_string))
            .collect(),
        status: s["status"].as_str().and_then(Status::parse),
        kind: s["type"]
            .as_str()
            .and_then(Kind::parse)
            .or(Some(Kind::Manhwa)),
        url,
    };

    let chapters = chapters["data"]
        .as_array()
        .into_iter()
        .flatten()
        // Premium chapters are paywalled on Asura; their pages don't load.
        .filter(|c| {
            !c["is_locked"].as_bool().unwrap_or(false)
                && !c["is_premium"].as_bool().unwrap_or(false)
        })
        .filter_map(|c| {
            let ch_slug = c["slug"].as_str()?;
            Some(ChapterEntry {
                source_id: format!("{slug}/chapters/{ch_slug}"),
                number: c["number"]
                    .as_f64()
                    .or_else(|| c["number"].as_str()?.parse().ok()),
                title: c["title"]
                    .as_str()
                    .map(str::trim)
                    .filter(|t| !t.is_empty())
                    .map(str::to_string),
                volume: None,
                scanlator: None,
                published_at: c["published_at"].as_str().map(str::to_string),
            })
        })
        .collect();
    Ok(SeriesData { details, chapters })
}

pub async fn pages<H: Http>(http: &H, chapter_id: &str) -> Result<Vec<PageImage>> {
    if chapter_id.split('/').count() != 3 {
        return Err(Error::Invalid(format!(
            "bad AsuraScans chapter id {chapter_id:?}"
        )));
    }
    let body = get_json(http, &format!("{API}/series/{chapter_id}")).await?;
    let pages: Vec<PageImage> = body["data"]["chapter"]["pages"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|p| {
            let url = p["url"].as_str().filter(|u| u.starts_with("http"))?;
            Some(PageImage {
                url: url.to_string(),
                width: p["width"].as_u64().map(|w| w as u32),
                height: p["height"].as_u64().map(|h| h as u32),
            })
        })
        .collect();
    if pages.is_empty() {
        return Err(Error::Parse(format!(
            "AsuraScans chapter without pages: {chapter_id}"
        )));
    }
    Ok(pages)
}

fn parse_list(body: &Value) -> Result<MangaList> {
    // A search with no results answers `"data": null`.
    let empty = Vec::new();
    let data = match &body["data"] {
        Value::Null if body.get("data").is_some() => &empty,
        d => d
            .as_array()
            .ok_or_else(|| Error::Parse("AsuraScans list without data".into()))?,
    };
    let items = data
        .iter()
        .filter_map(|m| {
            Some(MangaEntry {
                source_id: m["slug"].as_str()?.to_string(),
                title: m["title"].as_str().filter(|t| !t.is_empty())?.to_string(),
                cover_url: thumbnail_cover(m["cover"].as_str(), 400),
            })
        })
        .collect();
    Ok(MangaList {
        items,
        has_next: body["meta"]["has_more"].as_bool().unwrap_or(false),
    })
}

/// The API returns multi-MB cover masters; the CDN also serves a `-400`
/// width of every `.webp` cover (`-800` only when the master is large
/// enough). Other URLs pass through unchanged.
fn thumbnail_cover(url: Option<&str>, width: u32) -> Option<String> {
    let url = url.filter(|u| !u.is_empty())?;
    let Some(stem) = url
        .strip_suffix(".webp")
        .filter(|_| url.starts_with("https://cdn.asurascans.com/"))
    else {
        return Some(url.to_string());
    };
    let sized = stem
        .rsplit_once('-')
        .is_some_and(|(_, w)| w == "400" || w == "800");
    Some(if sized {
        url.to_string()
    } else {
        format!("{stem}-{width}.webp")
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sources::testing::{Fixtures, block_on};

    const LIST: &str = include_str!("../../tests/fixtures/asura_list.json");
    const DETAIL: &str = include_str!("../../tests/fixtures/asura_series.json");
    const CHAPTERS: &str = include_str!("../../tests/fixtures/asura_chapters.json");
    const PAGES: &str = include_str!("../../tests/fixtures/asura_pages.json");

    #[test]
    fn empty_search_is_not_an_error() {
        let http = Fixtures::new(&[("/search", r#"{"data":null,"meta":{"per_page":20}}"#)]);
        let list = block_on(search(&http, "zzz", 1)).unwrap();
        assert!(list.items.is_empty());
        assert!(parse_list(&serde_json::json!({"meta": {}})).is_err());
    }

    #[test]
    fn parses_list_with_thumbnail_covers() {
        let http = Fixtures::new(&[("/api/series?page=1&order=rating", LIST)]);
        let list = block_on(popular(&http, 1)).unwrap();
        assert_eq!(list.items.len(), 2);
        assert_eq!(list.items[0].source_id, "solo-leveling");
        assert_eq!(
            list.items[0].cover_url.as_deref(),
            Some("https://cdn.asurascans.com/covers/solo-leveling-400.webp")
        );
        assert!(list.has_next);
    }

    #[test]
    fn parses_series_and_drops_locked_chapters() {
        let http = Fixtures::new(&[
            ("/series/solo-leveling/chapters", CHAPTERS),
            ("/series/solo-leveling", DETAIL),
        ]);
        let s = block_on(series(&http, "solo-leveling")).unwrap();
        assert_eq!(s.details.title, "Solo Leveling");
        assert_eq!(s.details.authors, vec!["Chugong"]);
        assert_eq!(s.details.artists, vec!["REDICE STUDIO"]);
        assert_eq!(s.details.status, Some(Status::Completed));
        assert_eq!(s.details.kind, Some(Kind::Manhwa));
        assert_eq!(s.details.genres, vec!["Action", "Adventure", "Fantasy"]);
        assert_eq!(
            s.details.description.as_deref(),
            Some("10 years ago, the Gate opened. \nSung Jin-Woo is the World's Weakest.")
        );
        assert_eq!(s.chapters.len(), 3);
        assert_eq!(s.chapters[0].source_id, "solo-leveling/chapters/e85021f4");
        assert_eq!(s.chapters[0].number, Some(200.0));
        assert_eq!(s.chapters[2].title, None);
    }

    #[test]
    fn parses_pages_with_sizes() {
        let http = Fixtures::new(&[("/series/solo-leveling/chapters/e85021f4", PAGES)]);
        let p = block_on(pages(&http, "solo-leveling/chapters/e85021f4")).unwrap();
        assert_eq!(p.len(), 3);
        assert_eq!(p[1].height, Some(13333));
    }

    #[test]
    fn cover_thumbnails() {
        assert_eq!(
            thumbnail_cover(Some("https://cdn.asurascans.com/c/a-800.webp"), 400).unwrap(),
            "https://cdn.asurascans.com/c/a-800.webp"
        );
        assert_eq!(
            thumbnail_cover(Some("https://other.cdn/a.webp"), 400).unwrap(),
            "https://other.cdn/a.webp"
        );
        assert_eq!(thumbnail_cover(Some(""), 400), None);
    }
}
