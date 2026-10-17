use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Status {
    Ongoing,
    Completed,
    Hiatus,
    Cancelled,
}

impl Status {
    pub fn as_str(self) -> &'static str {
        match self {
            Status::Ongoing => "ongoing",
            Status::Completed => "completed",
            Status::Hiatus => "hiatus",
            Status::Cancelled => "cancelled",
        }
    }

    /// Map the many spellings sites use onto our four states.
    pub fn parse(raw: &str) -> Option<Status> {
        match raw.trim().to_lowercase().as_str() {
            "ongoing" | "publishing" | "season end" | "releasing" => Some(Status::Ongoing),
            "completed" | "complete" | "finished" => Some(Status::Completed),
            "hiatus" | "on hiatus" => Some(Status::Hiatus),
            "cancelled" | "canceled" | "dropped" | "discontinued" => Some(Status::Cancelled),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    Manga,
    Manhwa,
    Manhua,
    Comic,
}

impl Kind {
    pub fn as_str(self) -> &'static str {
        match self {
            Kind::Manga => "manga",
            Kind::Manhwa => "manhwa",
            Kind::Manhua => "manhua",
            Kind::Comic => "comic",
        }
    }

    pub fn parse(raw: &str) -> Option<Kind> {
        match raw.trim().to_lowercase().as_str() {
            "manga" | "one-shot" | "oneshot" | "doujinshi" => Some(Kind::Manga),
            "manhwa" | "webtoon" => Some(Kind::Manhwa),
            "manhua" => Some(Kind::Manhua),
            "comic" | "comics" | "oel" => Some(Kind::Comic),
            _ => None,
        }
    }

    /// MangaDex `originalLanguage` → kind.
    pub fn from_language(lang: &str) -> Option<Kind> {
        match lang {
            "ja" => Some(Kind::Manga),
            "ko" => Some(Kind::Manhwa),
            "zh" | "zh-hk" => Some(Kind::Manhua),
            "en" => Some(Kind::Comic),
            _ => None,
        }
    }
}

/// One series as it appears in a site's list (popular, latest, search).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MangaEntry {
    pub source_id: String,
    pub title: String,
    pub cover_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct MangaList {
    pub items: Vec<MangaEntry>,
    pub has_next: bool,
}

/// Full metadata for one series on one site.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
pub struct MangaDetails {
    pub source_id: String,
    pub title: String,
    pub alt_titles: Vec<String>,
    pub cover_url: Option<String>,
    pub description: Option<String>,
    pub authors: Vec<String>,
    pub artists: Vec<String>,
    pub genres: Vec<String>,
    pub status: Option<Status>,
    pub kind: Option<Kind>,
    pub url: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ChapterEntry {
    pub source_id: String,
    pub number: Option<f64>,
    pub title: Option<String>,
    pub volume: Option<String>,
    pub scanlator: Option<String>,
    /// ISO 8601 UTC.
    pub published_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PageImage {
    pub url: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub width: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub height: Option<u32>,
}

/// Details plus the chapter list, which most sites return together.
#[derive(Debug, Clone, PartialEq)]
pub struct SeriesData {
    pub details: MangaDetails,
    pub chapters: Vec<ChapterEntry>,
}

/// Pull the first decimal number that follows "chapter"/"ch." (or failing
/// that, the first number at all) out of a chapter label.
pub fn chapter_number(label: &str) -> Option<f64> {
    use std::sync::OnceLock;
    static CH: OnceLock<regex::Regex> = OnceLock::new();
    static ANY: OnceLock<regex::Regex> = OnceLock::new();
    let ch = CH.get_or_init(|| {
        regex::Regex::new(r"(?i)\b(?:chapter|ch\.?|episode|ep\.?)\s*(\d+(?:\.\d+)?)").unwrap()
    });
    let any = ANY.get_or_init(|| regex::Regex::new(r"(\d+(?:\.\d+)?)").unwrap());
    ch.captures(label)
        .or_else(|| any.captures(label))
        .and_then(|c| c[1].parse().ok())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chapter_numbers() {
        assert_eq!(chapter_number("Chapter 112"), Some(112.0));
        assert_eq!(chapter_number("Vol. 3 Ch. 20.5 - Ending"), Some(20.5));
        assert_eq!(chapter_number("Episode 7"), Some(7.0));
        assert_eq!(chapter_number("Side Story"), None);
        assert_eq!(chapter_number("199.5"), Some(199.5));
    }

    #[test]
    fn statuses() {
        assert_eq!(Status::parse("Publishing"), Some(Status::Ongoing));
        assert_eq!(Status::parse("Complete"), Some(Status::Completed));
        assert_eq!(Status::parse("Dropped"), Some(Status::Cancelled));
        assert_eq!(Status::parse("???"), None);
    }
}
