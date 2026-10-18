//! MangaDex v5 JSON API (api.mangadex.org). English chapters only.

use serde_json::Value;

use crate::http::get_ok;
use crate::{
    ChapterEntry, Error, Http, Kind, MangaDetails, MangaEntry, MangaList, PageImage, Result,
    SeriesData, Status,
};

const API: &str = "https://api.mangadex.org";
const COVERS: &str = "https://uploads.mangadex.org/covers";
/// Permanent image origin. The at-home server URLs MangaDex hands out
/// expire after minutes, so cached page links point here instead.
const UPLOADS: &str = "https://uploads.mangadex.org/data";
const PAGE_SIZE: u32 = 32;
const FEED_LIMIT: u32 = 500;
const HEADERS: &[(&str, &str)] = &[
    ("Accept", "application/json"),
    ("User-Agent", "Mangavault/1.0"),
];
const RATINGS: &str = "&contentRating[]=safe&contentRating[]=suggestive";

async fn get_json<H: Http>(http: &H, url: &str) -> Result<Value> {
    Ok(serde_json::from_str(&get_ok(http, url, HEADERS).await?)?)
}

async fn list<H: Http>(http: &H, page: u32, extra: &str) -> Result<MangaList> {
    let offset = (page.max(1) - 1) * PAGE_SIZE;
    let url = format!(
        "{API}/manga?limit={PAGE_SIZE}&offset={offset}&includes[]=cover_art&availableTranslatedLanguage[]=en{RATINGS}{extra}"
    );
    parse_list(&get_json(http, &url).await?)
}

pub async fn popular<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    list(http, page, "&order[followedCount]=desc").await
}

pub async fn latest<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    list(http, page, "&order[latestUploadedChapter]=desc").await
}

pub async fn search<H: Http>(http: &H, query: &str, page: u32) -> Result<MangaList> {
    list(
        http,
        page,
        &format!("&title={}&order[relevance]=desc", super::encode(query)),
    )
    .await
}

pub async fn series<H: Http>(http: &H, id: &str) -> Result<SeriesData> {
    if id.is_empty() || id.contains('/') {
        return Err(Error::Invalid(format!("bad MangaDex id {id:?}")));
    }
    let detail = get_json(
        http,
        &format!("{API}/manga/{id}?includes[]=cover_art&includes[]=author&includes[]=artist"),
    )
    .await?;
    let details = parse_details(&detail["data"])?;

    let mut chapters = Vec::new();
    let mut offset = 0;
    loop {
        let feed = get_json(
            http,
            &format!(
                "{API}/manga/{id}/feed?limit={FEED_LIMIT}&offset={offset}&translatedLanguage[]=en\
                 &order[chapter]=asc&includes[]=scanlation_group{RATINGS}&contentRating[]=erotica"
            ),
        )
        .await?;
        chapters.extend(parse_feed(&feed));
        let total = feed["total"].as_u64().unwrap_or(0) as u32;
        offset += FEED_LIMIT;
        if offset >= total {
            break;
        }
    }
    Ok(SeriesData { details, chapters })
}

pub async fn pages<H: Http>(http: &H, chapter_id: &str) -> Result<Vec<PageImage>> {
    let body = get_json(http, &format!("{API}/at-home/server/{chapter_id}")).await?;
    let hash = body["chapter"]["hash"]
        .as_str()
        .ok_or_else(|| Error::Parse("MangaDex at-home response without hash".into()))?;
    let files = body["chapter"]["data"]
        .as_array()
        .cloned()
        .unwrap_or_default();
    Ok(files
        .iter()
        .filter_map(|f| f.as_str())
        .map(|f| PageImage {
            url: format!("{UPLOADS}/{hash}/{f}"),
            width: None,
            height: None,
        })
        .collect())
}

fn localized(map: &Value) -> Option<String> {
    let obj = map.as_object()?;
    obj.get("en")
        .or_else(|| obj.get("ja-ro"))
        .or_else(|| obj.values().next())
        .and_then(|v| v.as_str())
        .map(str::to_string)
}

fn relationships<'a>(data: &'a Value, kind: &'a str) -> impl Iterator<Item = &'a Value> + 'a {
    data["relationships"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(move |r| r["type"] == kind)
}

fn cover_url(data: &Value, size: u32) -> Option<String> {
    let id = data["id"].as_str()?;
    let file = relationships(data, "cover_art").next()?["attributes"]["fileName"].as_str()?;
    Some(format!("{COVERS}/{id}/{file}.{size}.jpg"))
}

fn parse_list(body: &Value) -> Result<MangaList> {
    let data = body["data"]
        .as_array()
        .ok_or_else(|| Error::Parse("MangaDex list without data".into()))?;
    let items = data
        .iter()
        .filter_map(|m| {
            Some(MangaEntry {
                source_id: m["id"].as_str()?.to_string(),
                title: localized(&m["attributes"]["title"])?,
                cover_url: cover_url(m, 256),
            })
        })
        .collect();
    let offset = body["offset"].as_u64().unwrap_or(0);
    let limit = body["limit"].as_u64().unwrap_or(PAGE_SIZE as u64);
    let total = body["total"].as_u64().unwrap_or(0);
    Ok(MangaList {
        items,
        has_next: offset + limit < total,
    })
}

fn parse_details(data: &Value) -> Result<MangaDetails> {
    let id = data["id"]
        .as_str()
        .ok_or_else(|| Error::Parse("MangaDex manga without id".into()))?;
    let attrs = &data["attributes"];
    let title = localized(&attrs["title"]).unwrap_or_default();
    let alt_titles = attrs["altTitles"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|t| t.as_object()?.values().next()?.as_str().map(str::to_string))
        .filter(|t| t != &title)
        .collect();
    let names = |kind| {
        relationships(data, kind)
            .filter_map(|r| r["attributes"]["name"].as_str().map(str::to_string))
            .collect::<Vec<_>>()
    };
    let genres = attrs["tags"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|t| {
            matches!(
                t["attributes"]["group"].as_str(),
                Some("genre") | Some("theme")
            )
        })
        .filter_map(|t| localized(&t["attributes"]["name"]))
        .collect();
    Ok(MangaDetails {
        source_id: id.to_string(),
        title,
        alt_titles,
        cover_url: cover_url(data, 512),
        description: localized(&attrs["description"]).filter(|d| !d.is_empty()),
        authors: names("author"),
        artists: names("artist"),
        genres,
        status: attrs["status"].as_str().and_then(Status::parse),
        kind: attrs["originalLanguage"]
            .as_str()
            .and_then(Kind::from_language),
        url: format!("https://mangadex.org/title/{id}"),
    })
}

fn parse_feed(feed: &Value) -> Vec<ChapterEntry> {
    feed["data"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|c| {
            // Official-publisher links have no readable pages on MangaDex.
            let a = &c["attributes"];
            a["externalUrl"].is_null() && a["pages"].as_u64().unwrap_or(1) > 0
        })
        .filter_map(|c| {
            let a = &c["attributes"];
            Some(ChapterEntry {
                source_id: c["id"].as_str()?.to_string(),
                number: a["chapter"].as_str().and_then(|n| n.parse().ok()),
                title: a["title"]
                    .as_str()
                    .filter(|t| !t.is_empty())
                    .map(str::to_string),
                volume: a["volume"].as_str().map(str::to_string),
                scanlator: relationships(c, "scanlation_group")
                    .next()
                    .and_then(|g| g["attributes"]["name"].as_str())
                    .map(str::to_string),
                published_at: a["publishAt"].as_str().map(str::to_string),
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sources::testing::{Fixtures, block_on};

    const LIST: &str = include_str!("../../tests/fixtures/mangadex_list.json");
    const DETAIL: &str = include_str!("../../tests/fixtures/mangadex_manga.json");
    const FEED: &str = include_str!("../../tests/fixtures/mangadex_feed.json");
    const AT_HOME: &str = include_str!("../../tests/fixtures/mangadex_at_home.json");

    #[test]
    fn parses_list() {
        let http = Fixtures::new(&[("/manga?", LIST)]);
        let list = block_on(popular(&http, 2)).unwrap();
        assert_eq!(list.items.len(), 2);
        assert_eq!(list.items[0].title, "Kagurabachi");
        assert_eq!(
            list.items[0].cover_url.as_deref(),
            Some(
                "https://uploads.mangadex.org/covers/f98660a1-d2e2-461c-960d-7bd13df8b76d/cover.png.256.jpg"
            )
        );
        assert!(list.has_next);
        let url = &http.requests.borrow()[0];
        assert!(url.contains("offset=32") && url.contains("order[followedCount]=desc"));
    }

    #[test]
    fn parses_series_and_skips_external_chapters() {
        let http = Fixtures::new(&[("/feed", FEED), ("/manga/f98660a1", DETAIL)]);
        let s = block_on(series(&http, "f98660a1-d2e2-461c-960d-7bd13df8b76d")).unwrap();
        assert_eq!(s.details.title, "Kagurabachi");
        assert_eq!(s.details.alt_titles, vec!["カグラバチ"]);
        assert_eq!(s.details.authors, vec!["Hokazono Takeru"]);
        assert_eq!(s.details.kind, Some(Kind::Manga));
        assert_eq!(s.details.status, Some(Status::Ongoing));
        assert_eq!(s.details.genres, vec!["Action"]);
        assert_eq!(s.chapters.len(), 2);
        assert_eq!(s.chapters[0].number, Some(1.0));
        assert_eq!(s.chapters[1].title.as_deref(), Some("Second"));
        assert_eq!(s.chapters[1].scanlator.as_deref(), Some("TCB"));
    }

    #[test]
    fn page_links_use_permanent_origin() {
        let http = Fixtures::new(&[("/at-home/server/", AT_HOME)]);
        let p = block_on(pages(&http, "c1")).unwrap();
        assert_eq!(p.len(), 2);
        assert_eq!(p[0].url, "https://uploads.mangadex.org/data/abc123/1-x.png");
    }

    #[test]
    fn rejects_bad_ids() {
        let http = Fixtures::new(&[]);
        assert!(matches!(
            block_on(series(&http, "a/b")),
            Err(Error::Invalid(_))
        ));
    }
}
