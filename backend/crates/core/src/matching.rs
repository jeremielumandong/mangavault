//! Merging one series across sites: title keys decide which site entries are
//! the same series, and [`merge_chapters`] folds their chapter lists into
//! one list with a preferred site per chapter.

use crate::{ChapterEntry, SourceId};

/// Normalize a title into a match key: lowercase, `&` → `and`, punctuation
/// and a leading "the" dropped, whitespace removed. Non-Latin letters are
/// kept so Korean/Japanese alt titles still match each other.
///
/// Returns `None` for keys too short to identify a series on their own.
pub fn title_key(title: &str) -> Option<String> {
    let lower = title.to_lowercase().replace('&', " and ");
    // Drop bracketed notes such as "(Official)" or "[Remastered]".
    let mut cleaned = String::with_capacity(lower.len());
    let mut depth = 0i32;
    for c in lower.chars() {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = (depth - 1).max(0),
            _ if depth == 0 => cleaned.push(c),
            _ => {}
        }
    }
    let words: Vec<&str> = cleaned
        .split(|c: char| !c.is_alphanumeric())
        .filter(|w| !w.is_empty())
        .collect();
    let words = match words.first() {
        Some(&"the") if words.len() > 1 => &words[1..],
        _ => &words[..],
    };
    let key: String = words.concat();
    // Short Latin keys ("one", "solo") collide across unrelated series; CJK
    // titles carry far more per character, so allow them shorter.
    let min = if key.is_ascii() { 5 } else { 2 };
    (key.chars().count() >= min).then_some(key)
}

/// Every distinct key for a title and its alternatives.
pub fn title_keys<'a>(titles: impl IntoIterator<Item = &'a str>) -> Vec<String> {
    let mut keys: Vec<String> = titles.into_iter().filter_map(title_key).collect();
    keys.sort();
    keys.dedup();
    keys
}

/// One chapter of the merged list.
#[derive(Debug, Clone, PartialEq)]
pub struct MergedChapter {
    /// Stable identity within the series: the chapter number, or the
    /// serving site's chapter id for unnumbered extras.
    pub key: String,
    /// Reading-order position; numbers for numbered chapters, with
    /// unnumbered extras slotted just after the chapter they follow.
    pub sort: f64,
    pub number: Option<f64>,
    pub title: Option<String>,
    pub volume: Option<String>,
    pub published_at: Option<String>,
    /// Sites that have this chapter, best first: (site, site chapter id).
    pub candidates: Vec<(SourceId, String)>,
}

pub fn number_key(n: f64) -> String {
    // 12, 12.5 — never "12.0", so keys are stable across sites.
    let s = format!("{n:.3}");
    s.trim_end_matches('0').trim_end_matches('.').to_string()
}

/// Merge per-site chapter lists. `lists` must be ordered by site preference
/// (best first); each list may be in any order.
///
/// Numbered chapters are unioned by number. Unnumbered extras are taken only
/// from the most complete site, so the same "Side story" isn't listed once
/// per site.
pub fn merge_chapters(lists: &[(SourceId, Vec<ChapterEntry>)]) -> Vec<MergedChapter> {
    let mut merged: Vec<MergedChapter> = Vec::new();
    let mut by_key: std::collections::HashMap<String, usize> = std::collections::HashMap::new();

    let extras_from = lists
        .iter()
        .max_by_key(|(_, chs)| chs.len())
        .map(|(s, _)| *s);

    for (source, chapters) in lists {
        // Sites list newest-first or oldest-first; walk oldest-first so an
        // unnumbered extra lands after the chapter it follows.
        let mut ordered: Vec<&ChapterEntry> = chapters.iter().collect();
        let nums: Vec<f64> = ordered.iter().filter_map(|c| c.number).collect();
        if nums.len() >= 2 && nums[0] > nums[nums.len() - 1] {
            ordered.reverse();
        }
        let mut last_number = -1.0f64;
        let mut extra_offset = 0.0f64;
        for ch in ordered {
            match ch.number {
                Some(n) => {
                    last_number = n;
                    extra_offset = 0.0;
                    let key = number_key(n);
                    if let Some(&i) = by_key.get(&key) {
                        let m = &mut merged[i];
                        if !m.candidates.iter().any(|(s, _)| s == source) {
                            m.candidates.push((*source, ch.source_id.clone()));
                        }
                        if m.title.is_none() {
                            m.title = ch.title.clone();
                        }
                        if m.published_at.is_none() {
                            m.published_at = ch.published_at.clone();
                        }
                        if m.volume.is_none() {
                            m.volume = ch.volume.clone();
                        }
                    } else {
                        by_key.insert(key.clone(), merged.len());
                        merged.push(MergedChapter {
                            key,
                            sort: n,
                            number: Some(n),
                            title: ch.title.clone(),
                            volume: ch.volume.clone(),
                            published_at: ch.published_at.clone(),
                            candidates: vec![(*source, ch.source_id.clone())],
                        });
                    }
                }
                None if Some(*source) == extras_from => {
                    extra_offset += 0.001;
                    let key = format!("x:{}:{}", source.as_str(), ch.source_id);
                    by_key.insert(key.clone(), merged.len());
                    merged.push(MergedChapter {
                        key,
                        sort: last_number + extra_offset,
                        number: None,
                        title: ch.title.clone(),
                        volume: ch.volume.clone(),
                        published_at: ch.published_at.clone(),
                        candidates: vec![(*source, ch.source_id.clone())],
                    });
                }
                None => {}
            }
        }
    }
    merged.sort_by(|a, b| {
        a.sort
            .partial_cmp(&b.sort)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    merged
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ch(id: &str, n: Option<f64>) -> ChapterEntry {
        ChapterEntry {
            source_id: id.into(),
            number: n,
            title: None,
            volume: None,
            scanlator: None,
            published_at: None,
        }
    }

    #[test]
    fn keys_ignore_case_punctuation_and_notes() {
        assert_eq!(title_key("Solo Leveling"), title_key("SOLO LEVELING!"));
        assert_eq!(
            title_key("The Beginning After the End"),
            title_key("Beginning After The End")
        );
        assert_eq!(
            title_key("Omniscient Reader's Viewpoint"),
            title_key("Omniscient Readers Viewpoint")
        );
        assert_eq!(
            title_key("Kagurabachi (Official)"),
            title_key("Kagurabachi")
        );
        assert_eq!(title_key("Spy x Family"), Some("spyxfamily".into()));
        assert_eq!(title_key("Me & You"), title_key("Me and You"));
        assert_eq!(title_key("나 혼자만 레벨업"), Some("나혼자만레벨업".into()));
    }

    #[test]
    fn short_latin_keys_are_rejected() {
        assert_eq!(title_key("One"), None);
        assert_eq!(title_key("!!"), None);
        assert!(title_key("海贼").is_some());
    }

    #[test]
    fn merge_unions_numbers_and_orders_candidates() {
        let merged = merge_chapters(&[
            (
                SourceId::AsuraScans,
                vec![ch("a2", Some(2.0)), ch("a3", Some(3.0))],
            ),
            (
                SourceId::MangaDex,
                vec![ch("d1", Some(1.0)), ch("d2", Some(2.0))],
            ),
        ]);
        let keys: Vec<_> = merged.iter().map(|m| m.key.as_str()).collect();
        assert_eq!(keys, ["1", "2", "3"]);
        assert_eq!(
            merged[1].candidates,
            vec![
                (SourceId::AsuraScans, "a2".into()),
                (SourceId::MangaDex, "d2".into())
            ]
        );
        assert_eq!(
            merged[0].candidates,
            vec![(SourceId::MangaDex, "d1".into())]
        );
    }

    #[test]
    fn merge_keeps_extras_from_most_complete_site_only() {
        let merged = merge_chapters(&[
            (
                SourceId::AsuraScans,
                vec![ch("a1", Some(1.0)), ch("ax", None)],
            ),
            (
                SourceId::MgEko,
                vec![ch("w1", Some(1.0)), ch("wx", None), ch("w2", Some(2.0))],
            ),
        ]);
        let keys: Vec<_> = merged.iter().map(|m| m.key.as_str()).collect();
        assert_eq!(keys, ["1", "x:mgeko:wx", "2"]);
    }

    #[test]
    fn number_keys_are_canonical() {
        assert_eq!(number_key(12.0), "12");
        assert_eq!(number_key(12.5), "12.5");
        assert_eq!(number_key(0.0), "0");
    }
}
