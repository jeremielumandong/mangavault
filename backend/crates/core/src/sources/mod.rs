//! Per-site adapters behind one interface. Add a site by adding a module,
//! a [`SourceId`] variant, and its arms in the dispatch methods below.

pub mod asurascans;
pub mod comichubfree;
pub mod mangadex;
pub mod mangapill;
pub mod mgeko;
pub mod tapas;
pub mod webtoons;

use crate::{Http, Kind, MangaList, PageImage, Result, SeriesData};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum SourceId {
    MangaDex,
    MangaPill,
    AsuraScans,
    MgEko,
    Webtoons,
    ComicHubFree,
    Tapas,
}

impl SourceId {
    pub const ALL: [SourceId; 7] = [
        SourceId::MangaDex,
        SourceId::MangaPill,
        SourceId::AsuraScans,
        SourceId::MgEko,
        SourceId::Webtoons,
        SourceId::ComicHubFree,
        SourceId::Tapas,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            SourceId::MangaDex => "mangadex",
            SourceId::MangaPill => "mangapill",
            SourceId::AsuraScans => "asurascans",
            SourceId::MgEko => "mgeko",
            SourceId::Webtoons => "webtoons",
            SourceId::ComicHubFree => "comichubfree",
            SourceId::Tapas => "tapas",
        }
    }

    pub fn parse(s: &str) -> Option<SourceId> {
        SourceId::ALL.into_iter().find(|id| id.as_str() == s)
    }

    pub fn name(self) -> &'static str {
        match self {
            SourceId::MangaDex => "MangaDex",
            SourceId::MangaPill => "MangaPill",
            SourceId::AsuraScans => "AsuraScans",
            SourceId::MgEko => "MgEko",
            SourceId::Webtoons => "Webtoons",
            SourceId::ComicHubFree => "ComicHubFree",
            SourceId::Tapas => "Tapas",
        }
    }

    /// Headers clients must send when loading this site's images.
    pub fn image_headers(self) -> &'static [(&'static str, &'static str)] {
        match self {
            SourceId::MangaPill => &[("Referer", "https://mangapill.com/")],
            // The image CDN (pstatic.net) rejects requests without it.
            SourceId::Webtoons => &[("Referer", "http://m.webtoons.com/")],
            SourceId::Tapas => &[("Referer", "https://m.tapas.io/")],
            // Without it, pages and covers come back as a "COMIC HUB FREE"
            // placeholder image.
            SourceId::ComicHubFree => &[("Referer", "https://comichubfree.com/")],
            SourceId::MangaDex | SourceId::AsuraScans | SourceId::MgEko => &[],
        }
    }

    /// Which site should serve a chapter when several have it. Scanlation
    /// sites first for Korean/Chinese series (faster releases, full-width
    /// strips); MangaDex first for Japanese manga (official-quality scans).
    /// ComicHubFree first for western comics, which the others rarely carry.
    /// Webtoons and Tapas come last: they number episodes their own way and
    /// paywall some, so they serve mainly the series only they carry.
    pub fn preference(kind: Option<Kind>) -> [SourceId; 7] {
        use SourceId::*;
        match kind {
            Some(Kind::Manhwa) | Some(Kind::Manhua) => [
                AsuraScans,
                MgEko,
                MangaDex,
                MangaPill,
                Webtoons,
                Tapas,
                ComicHubFree,
            ],
            Some(Kind::Comic) => [
                ComicHubFree,
                MangaDex,
                MangaPill,
                MgEko,
                AsuraScans,
                Tapas,
                Webtoons,
            ],
            _ => [
                MangaDex,
                MangaPill,
                MgEko,
                AsuraScans,
                ComicHubFree,
                Webtoons,
                Tapas,
            ],
        }
    }

    pub async fn popular<H: Http>(self, http: &H, page: u32) -> Result<MangaList> {
        match self {
            SourceId::MangaDex => mangadex::popular(http, page).await,
            SourceId::MangaPill => mangapill::popular(http, page).await,
            SourceId::AsuraScans => asurascans::popular(http, page).await,
            SourceId::MgEko => mgeko::popular(http, page).await,
            SourceId::Webtoons => webtoons::popular(http, page).await,
            SourceId::ComicHubFree => comichubfree::popular(http, page).await,
            SourceId::Tapas => tapas::popular(http, page).await,
        }
    }

    pub async fn latest<H: Http>(self, http: &H, page: u32) -> Result<MangaList> {
        match self {
            SourceId::MangaDex => mangadex::latest(http, page).await,
            SourceId::MangaPill => mangapill::latest(http, page).await,
            SourceId::AsuraScans => asurascans::latest(http, page).await,
            SourceId::MgEko => mgeko::latest(http, page).await,
            SourceId::Webtoons => webtoons::latest(http, page).await,
            SourceId::ComicHubFree => comichubfree::latest(http, page).await,
            SourceId::Tapas => tapas::latest(http, page).await,
        }
    }

    pub async fn search<H: Http>(self, http: &H, query: &str, page: u32) -> Result<MangaList> {
        match self {
            SourceId::MangaDex => mangadex::search(http, query, page).await,
            SourceId::MangaPill => mangapill::search(http, query, page).await,
            SourceId::AsuraScans => asurascans::search(http, query, page).await,
            SourceId::MgEko => mgeko::search(http, query, page).await,
            SourceId::Webtoons => webtoons::search(http, query, page).await,
            SourceId::ComicHubFree => comichubfree::search(http, query, page).await,
            SourceId::Tapas => tapas::search(http, query, page).await,
        }
    }

    /// Details plus the full chapter list.
    pub async fn series<H: Http>(self, http: &H, source_id: &str) -> Result<SeriesData> {
        match self {
            SourceId::MangaDex => mangadex::series(http, source_id).await,
            SourceId::MangaPill => mangapill::series(http, source_id).await,
            SourceId::AsuraScans => asurascans::series(http, source_id).await,
            SourceId::MgEko => mgeko::series(http, source_id).await,
            SourceId::Webtoons => webtoons::series(http, source_id).await,
            SourceId::ComicHubFree => comichubfree::series(http, source_id).await,
            SourceId::Tapas => tapas::series(http, source_id).await,
        }
    }

    pub async fn pages<H: Http>(self, http: &H, chapter_id: &str) -> Result<Vec<PageImage>> {
        match self {
            SourceId::MangaDex => mangadex::pages(http, chapter_id).await,
            SourceId::MangaPill => mangapill::pages(http, chapter_id).await,
            SourceId::AsuraScans => asurascans::pages(http, chapter_id).await,
            SourceId::MgEko => mgeko::pages(http, chapter_id).await,
            SourceId::Webtoons => webtoons::pages(http, chapter_id).await,
            SourceId::ComicHubFree => comichubfree::pages(http, chapter_id).await,
            SourceId::Tapas => tapas::pages(http, chapter_id).await,
        }
    }
}

/// Text of an element with whitespace collapsed.
pub(crate) fn text_of(el: scraper::ElementRef<'_>) -> String {
    el.text()
        .collect::<Vec<_>>()
        .join(" ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

pub(crate) fn sel(s: &str) -> scraper::Selector {
    scraper::Selector::parse(s).expect("static selector")
}

pub(crate) fn encode(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

/// Strip tags and collapse blank lines in an HTML description.
pub(crate) fn strip_html(html: &str) -> String {
    use std::sync::OnceLock;
    static BR: OnceLock<regex::Regex> = OnceLock::new();
    static TAG: OnceLock<regex::Regex> = OnceLock::new();
    static BLANK: OnceLock<regex::Regex> = OnceLock::new();
    let br = BR.get_or_init(|| regex::Regex::new(r"(?i)<br\s*/?>|</p>").unwrap());
    let tag = TAG.get_or_init(|| regex::Regex::new(r"<[^>]*>").unwrap());
    let blank = BLANK.get_or_init(|| regex::Regex::new(r"\n{3,}").unwrap());
    let s = br.replace_all(html, "\n");
    let s = tag.replace_all(&s, "");
    let s = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ");
    blank.replace_all(&s, "\n\n").trim().to_string()
}

#[cfg(test)]
pub(crate) mod testing {
    use crate::{Error, Http, HttpResponse, Result};
    use std::cell::RefCell;

    /// Serves canned bodies by URL substring and records every request.
    pub struct Fixtures {
        routes: Vec<(String, String)>,
        pub requests: RefCell<Vec<String>>,
    }

    impl Fixtures {
        pub fn new(routes: &[(&str, &str)]) -> Self {
            Fixtures {
                routes: routes
                    .iter()
                    .map(|(k, v)| (k.to_string(), v.to_string()))
                    .collect(),
                requests: RefCell::new(Vec::new()),
            }
        }
    }

    impl Http for Fixtures {
        async fn get(&self, url: &str, _headers: &[(&str, &str)]) -> Result<HttpResponse> {
            self.requests.borrow_mut().push(url.to_string());
            match self.routes.iter().find(|(k, _)| url.contains(k.as_str())) {
                Some((_, body)) => Ok(HttpResponse {
                    status: 200,
                    body: body.clone(),
                }),
                None => Err(Error::Status {
                    status: 404,
                    url: url.to_string(),
                }),
            }
        }
    }

    pub fn block_on<F: std::future::Future>(f: F) -> F::Output {
        tokio::runtime::Builder::new_current_thread()
            .build()
            .unwrap()
            .block_on(f)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_round_trip() {
        for id in SourceId::ALL {
            assert_eq!(SourceId::parse(id.as_str()), Some(id));
        }
    }

    #[test]
    fn strips_html() {
        assert_eq!(
            strip_html(
                "<p>10 years ago. <br/>Sung is the <strong>Weakest</strong> &amp; more.</p>"
            ),
            "10 years ago. \nSung is the Weakest & more."
        );
    }
}
