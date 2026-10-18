//! www.webtoons.com, the official LINE Webtoon site (Originals and Canvas).
//! No bot check; the image CDN only wants a `Referer` (see
//! [`SourceId::image_headers`](crate::SourceId::image_headers)). Locked
//! (paid) episodes come back without images, so the chapter falls through to
//! another site.
//!
//! Manga id: the numeric `title_no`. Chapter id: the episode's viewer path
//! (`/en/fantasy/tower-of-god/season-1-ep-0/viewer?title_no=95&episode_no=1`).

use scraper::{ElementRef, Html};
use serde::Deserialize;

use crate::http::{BROWSER_UA, check_interstitial, get_ok};
use crate::sources::{encode, sel, strip_html, text_of};
use crate::{
    ChapterEntry, Error, Http, Kind, MangaDetails, MangaEntry, MangaList, PageImage, Result,
    SeriesData, Status,
};

const BASE: &str = "https://www.webtoons.com";
const MOBILE: &str = "https://m.webtoons.com";
const COOKIE: &str = "ageGatePass=true; locale=en; needGDPR=false";
const HEADERS: &[(&str, &str)] = &[
    ("User-Agent", BROWSER_UA),
    ("Referer", "https://www.webtoons.com/"),
    (
        "Accept",
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    ),
    ("Cookie", COOKIE),
];
const API_HEADERS: &[(&str, &str)] = &[
    (
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
    ),
    ("Referer", "https://m.webtoons.com/"),
    ("Accept", "application/json"),
    ("Cookie", COOKIE),
];

async fn fetch<H: Http>(http: &H, url: &str) -> Result<Html> {
    let body = get_ok(http, url, HEADERS).await?;
    check_interstitial(&body, url)?;
    Ok(Html::parse_document(&body))
}

/// Rankings are a single page each.
async fn ranking<H: Http>(http: &H, page: u32, which: &str) -> Result<MangaList> {
    if page > 1 {
        return Ok(MangaList {
            items: Vec::new(),
            has_next: false,
        });
    }
    let doc = fetch(http, &format!("{BASE}/en/ranking/{which}")).await?;
    let items = parse_cards(&doc);
    if items.is_empty() {
        return Err(Error::Parse("unexpected Webtoons ranking page".into()));
    }
    Ok(MangaList {
        items,
        has_next: false,
    })
}

pub async fn popular<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    ranking(http, page, "popular").await
}

pub async fn latest<H: Http>(http: &H, page: u32) -> Result<MangaList> {
    ranking(http, page, "trending").await
}

pub async fn search<H: Http>(http: &H, query: &str, page: u32) -> Result<MangaList> {
    if page > 1 {
        return Ok(MangaList {
            items: Vec::new(),
            has_next: false,
        });
    }
    let doc = fetch(http, &format!("{BASE}/en/search?keyword={}", encode(query))).await?;
    Ok(MangaList {
        items: parse_cards(&doc),
        has_next: false,
    })
}

/// Every link to a series (`…/list?title_no=N`) is a card. The markup varies
/// between ranking, search and genre pages, so the title is looked up in
/// several places, most specific first.
fn parse_cards(doc: &Html) -> Vec<MangaEntry> {
    let mut items = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for a in doc.select(&sel(r#"a[href*="title_no="]"#)) {
        let href = a.value().attr("href").unwrap_or_default();
        // Episode links also carry title_no; only series links count.
        if href.contains("episode_no=") {
            continue;
        }
        let Some(id) = title_no(href) else {
            continue;
        };
        let Some(title) = card_title(a) else {
            continue;
        };
        if !seen.insert(id.clone()) {
            continue;
        }
        let cover_url = a.select(&sel("img")).next().and_then(|img| {
            ["src", "data-src", "data-url"]
                .iter()
                .filter_map(|k| img.value().attr(k))
                .find(|u| u.starts_with("http"))
                .map(str::to_string)
        });
        items.push(MangaEntry {
            source_id: id,
            title,
            cover_url,
        });
    }
    items
}

fn card_title(a: ElementRef<'_>) -> Option<String> {
    for s in [".title", ".subj", ".info_text .title", "strong", "p"] {
        if let Some(t) = a
            .select(&sel(s))
            .next()
            .map(text_of)
            .filter(|t| !t.is_empty())
        {
            return Some(t);
        }
    }
    a.select(&sel("img[alt]"))
        .next()
        .and_then(|i| i.value().attr("alt"))
        .map(|t| t.trim().to_string())
        .filter(|t| !t.is_empty())
}

fn title_no(href: &str) -> Option<String> {
    let rest = href.split("title_no=").nth(1)?;
    let n: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
    (!n.is_empty()).then_some(n)
}

#[derive(Deserialize)]
struct EpisodesBody {
    result: Option<EpisodesResult>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct EpisodesResult {
    #[serde(default)]
    episode_list: Vec<Episode>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Episode {
    episode_title: Option<String>,
    viewer_link: Option<String>,
    exposure_date_millis: Option<i64>,
}

pub async fn series<H: Http>(http: &H, id: &str) -> Result<SeriesData> {
    if id.is_empty() || !id.bytes().all(|b| b.is_ascii_digit()) {
        return Err(Error::Invalid(format!("bad Webtoons id {id:?}")));
    }
    // Originals live under "webtoon", Canvas under "challenge".
    let mut episodes = Vec::new();
    let mut last_err = None;
    for kind in ["webtoon", "challenge"] {
        let url = format!("{MOBILE}/api/v1/{kind}/{id}/episodes?pageSize=99999");
        match get_ok(http, &url, API_HEADERS).await {
            Ok(body) => {
                let parsed: EpisodesBody = serde_json::from_str(&body)
                    .map_err(|e| Error::Parse(format!("Webtoons episodes {url}: {e}")))?;
                episodes = parsed.result.map(|r| r.episode_list).unwrap_or_default();
                if !episodes.is_empty() {
                    break;
                }
            }
            Err(e) => last_err = Some(e),
        }
    }
    if episodes.is_empty() {
        return Err(last_err
            .unwrap_or_else(|| Error::Parse(format!("Webtoons series without episodes: {id}"))));
    }
    let chapters = parse_episodes(&episodes);

    // `/en/{genre}/{slug}` comes from any viewer link; the list page there
    // has the metadata.
    let path = episodes
        .iter()
        .filter_map(|e| e.viewer_link.as_deref())
        .find_map(series_path);
    let url = match &path {
        Some(p) => format!("{BASE}{p}/list?title_no={id}"),
        None => format!("{BASE}/episodeList?titleNo={id}"),
    };
    let doc = fetch(http, &url).await?;
    let details = parse_details(&doc, id, url)?;
    Ok(SeriesData { details, chapters })
}

/// Numbered by `episode_no`, which is unique and in order. The titles'
/// "Ep. N" restarts every season, so it would collide.
fn parse_episodes(episodes: &[Episode]) -> Vec<ChapterEntry> {
    episodes
        .iter()
        .filter_map(|e| {
            let link = e.viewer_link.as_deref().and_then(viewer_path)?;
            let number = link
                .split("episode_no=")
                .nth(1)
                .map(|r| {
                    r.chars()
                        .take_while(|c| c.is_ascii_digit())
                        .collect::<String>()
                })
                .and_then(|n| n.parse().ok());
            Some(ChapterEntry {
                source_id: link,
                number,
                title: e
                    .episode_title
                    .as_deref()
                    .map(str::trim)
                    .filter(|t| !t.is_empty())
                    .map(str::to_string),
                volume: None,
                scanlator: None,
                published_at: e
                    .exposure_date_millis
                    .filter(|m| *m > 0)
                    .map(iso_from_millis),
            })
        })
        .collect()
}

/// The path of a viewer link, absolute or not, if it is a webtoons.com one.
fn viewer_path(link: &str) -> Option<String> {
    let path = ["https://www.webtoons.com", "https://m.webtoons.com", ""]
        .iter()
        .find_map(|h| link.strip_prefix(h).filter(|p| p.starts_with('/')))?;
    (path.contains("/viewer") && path.contains("episode_no=")).then(|| path.to_string())
}

/// `/en/fantasy/tower-of-god` from `/en/fantasy/tower-of-god/season-1-ep-0/viewer?…`.
fn series_path(link: &str) -> Option<String> {
    let path = viewer_path(link)?;
    let parts: Vec<&str> = path.split('/').collect();
    // ["", "en", genre, slug, episode, "viewer?…"]
    (parts.len() >= 6 && !parts[2].is_empty() && !parts[3].is_empty())
        .then(|| format!("/{}/{}/{}", parts[1], parts[2], parts[3]))
}

fn parse_details(doc: &Html, id: &str, url: String) -> Result<MangaDetails> {
    let meta = |p: &str| {
        doc.select(&sel(&format!(r#"meta[property="{p}"]"#)))
            .next()
            .and_then(|m| m.value().attr("content"))
            .map(|c| c.trim().to_string())
            .filter(|c| !c.is_empty())
    };
    let title = doc
        .select(&sel("h1.subj, h3.subj, .detail_header .subj"))
        .next()
        .map(text_of)
        .filter(|t| !t.is_empty())
        .or_else(|| meta("og:title"))
        .ok_or_else(|| Error::Parse(format!("Webtoons page without title: {url}")))?;
    let description = doc
        .select(&sel("p.summary"))
        .next()
        .map(|p| strip_html(&p.inner_html()))
        .filter(|d| !d.is_empty())
        .or_else(|| meta("og:description"));
    let authors = doc
        .select(&sel(
            ".detail_header .author_area a, .detail_header .author_area, .author_area .author",
        ))
        .next()
        .map(text_of)
        .map(|a| {
            a.trim_end_matches("author info")
                .split(',')
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect()
        })
        .unwrap_or_default();
    let genres = doc
        .select(&sel(".detail_header .genre, .info .genre"))
        .map(text_of)
        .filter(|g| !g.is_empty())
        .collect::<Vec<_>>();
    let day = doc
        .select(&sel("p.day_info, .day_info"))
        .next()
        .map(text_of)
        .unwrap_or_default()
        .to_lowercase();
    let status = if day.contains("completed") || day.contains("end") {
        Some(Status::Completed)
    } else if day.contains("up") || day.contains("every") {
        Some(Status::Ongoing)
    } else if day.contains("hiatus") {
        Some(Status::Hiatus)
    } else {
        None
    };
    Ok(MangaDetails {
        source_id: id.to_string(),
        title,
        cover_url: meta("og:image"),
        description,
        authors,
        genres: dedupe(genres),
        status,
        // Webtoons is Korean-style vertical comics, whatever the creator's country.
        kind: Some(Kind::Manhwa),
        url,
        ..Default::default()
    })
}

fn dedupe(v: Vec<String>) -> Vec<String> {
    let mut seen = std::collections::HashSet::new();
    v.into_iter()
        .filter(|g| seen.insert(g.to_lowercase()))
        .collect()
}

pub async fn pages<H: Http>(http: &H, chapter_id: &str) -> Result<Vec<PageImage>> {
    let path = viewer_path(chapter_id)
        .ok_or_else(|| Error::Invalid(format!("bad Webtoons chapter id {chapter_id:?}")))?;
    let doc = fetch(http, &format!("{BASE}{path}")).await?;
    let pages: Vec<PageImage> = doc
        .select(&sel("#_imageList img"))
        .filter_map(|img| {
            let src = img
                .value()
                .attr("data-url")
                .or_else(|| img.value().attr("src"))
                .filter(|u| u.starts_with("http"))?;
            Some(PageImage {
                url: src.to_string(),
                width: img
                    .value()
                    .attr("width")
                    .and_then(|w| w.split('.').next()?.parse().ok()),
                height: img
                    .value()
                    .attr("height")
                    .and_then(|h| h.split('.').next()?.parse().ok()),
            })
        })
        .collect();
    // Locked episodes render the viewer without an image list; an empty
    // result lets the backend try the next site for this chapter.
    Ok(pages)
}

/// Unix milliseconds as RFC 3339 UTC, without pulling in a date crate.
fn iso_from_millis(ms: i64) -> String {
    let secs = ms.div_euclid(1000);
    let (days, rem) = (secs.div_euclid(86_400), secs.rem_euclid(86_400));
    // Howard Hinnant's civil_from_days.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + if month <= 2 { 1 } else { 0 };
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sources::testing::{Fixtures, block_on};

    const RANKING: &str = include_str!("../../tests/fixtures/webtoons_ranking.html");
    const EPISODES: &str = include_str!("../../tests/fixtures/webtoons_episodes.json");
    const LIST: &str = include_str!("../../tests/fixtures/webtoons_list.html");
    const VIEWER: &str = include_str!("../../tests/fixtures/webtoons_viewer.html");

    #[test]
    fn parses_ranking_cards_once_each() {
        let http = Fixtures::new(&[("/en/ranking/popular", RANKING)]);
        let list = block_on(popular(&http, 1)).unwrap();
        let titles: Vec<_> = list.items.iter().map(|m| m.title.as_str()).collect();
        assert_eq!(titles, ["Osora", "Superfish", "unOrdinary"]);
        assert_eq!(list.items[0].source_id, "6202");
        assert_eq!(
            list.items[0].cover_url.as_deref(),
            Some("https://swebtoon-phinf.pstatic.net/20260218_141/osora.jpg?type=q90")
        );
        assert!(!list.has_next);
        assert!(block_on(popular(&http, 2)).unwrap().items.is_empty());
    }

    #[test]
    fn parses_series_from_api_and_list_page() {
        let http = Fixtures::new(&[
            ("/api/v1/webtoon/95/episodes", EPISODES),
            ("/en/fantasy/tower-of-god/list?title_no=95", LIST),
        ]);
        let s = block_on(series(&http, "95")).unwrap();
        let d = &s.details;
        assert_eq!(d.title, "Tower of God");
        assert_eq!(d.authors, vec!["SIU"]);
        assert_eq!(d.genres, vec!["Fantasy"]);
        assert_eq!(d.status, Some(Status::Ongoing));
        assert_eq!(d.kind, Some(Kind::Manhwa));
        assert_eq!(
            d.description.as_deref(),
            Some("What do you desire? Money and wealth? Honor and pride?")
        );
        assert_eq!(
            d.cover_url.as_deref(),
            Some("https://swebtoon-phinf.pstatic.net/tower.jpg?type=crop540_540")
        );
        let c = &s.chapters;
        assert_eq!(c.len(), 3);
        assert_eq!(
            c[0].source_id,
            "/en/fantasy/tower-of-god/season-1-ep-0/viewer?title_no=95&episode_no=1"
        );
        assert_eq!(c[0].number, Some(1.0));
        assert_eq!(c[0].title.as_deref(), Some("[Season 1] Ep. 0"));
        assert_eq!(c[0].published_at.as_deref(), Some("2014-07-01T12:00:00Z"));
        // An absolute link is reduced to its path.
        assert_eq!(
            c[2].source_id,
            "/en/fantasy/tower-of-god/season-2-ep-1/viewer?title_no=95&episode_no=81"
        );
        assert_eq!(c[2].number, Some(81.0));
    }

    #[test]
    fn falls_back_to_canvas_episodes() {
        let http = Fixtures::new(&[
            ("/api/v1/challenge/95/episodes", EPISODES),
            ("/list?title_no=95", LIST),
        ]);
        let s = block_on(series(&http, "95")).unwrap();
        assert_eq!(s.chapters.len(), 3);
        assert!(http.requests.borrow()[0].contains("/api/v1/webtoon/95/"));
    }

    #[test]
    fn parses_viewer_images() {
        let http = Fixtures::new(&[("/season-1-ep-0/viewer?title_no=95&episode_no=1", VIEWER)]);
        let p = block_on(pages(
            &http,
            "/en/fantasy/tower-of-god/season-1-ep-0/viewer?title_no=95&episode_no=1",
        ))
        .unwrap();
        assert_eq!(p.len(), 2);
        assert_eq!(
            p[0].url,
            "https://webtoon-phinf.pstatic.net/20140701_1/tog_001.jpg?type=q90"
        );
        assert_eq!(p[0].width, Some(800));
        assert_eq!(p[1].height, Some(1279));
    }

    #[test]
    fn locked_episode_has_no_pages() {
        let http = Fixtures::new(&[(
            "/viewer",
            "<html><body><div class=\"paywall\"></div></body></html>",
        )]);
        let p = block_on(pages(&http, "/en/a/b/c/viewer?title_no=1&episode_no=2")).unwrap();
        assert!(p.is_empty());
    }

    #[test]
    fn rejects_foreign_or_malformed_ids() {
        let http = Fixtures::new(&[]);
        assert!(block_on(series(&http, "95/x")).is_err());
        assert!(block_on(pages(&http, "https://evil.example/viewer?episode_no=1")).is_err());
        assert!(block_on(pages(&http, "/en/a/b/c/list?title_no=1")).is_err());
    }

    #[test]
    fn millis_to_iso() {
        assert_eq!(iso_from_millis(0), "1970-01-01T00:00:00Z");
        assert_eq!(iso_from_millis(1_404_216_000_000), "2014-07-01T12:00:00Z");
        assert_eq!(iso_from_millis(1_709_164_800_000), "2024-02-29T00:00:00Z");
    }
}
