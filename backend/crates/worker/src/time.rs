//! All timestamps are stored and returned as RFC 3339 UTC with millisecond
//! precision ("2026-10-17T22:00:00.000Z"), so string order is time order.

use chrono::{DateTime, Duration, SecondsFormat, Utc};

pub fn now() -> DateTime<Utc> {
    Utc::now()
}

pub fn iso(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::Millis, true)
}

pub fn now_iso() -> String {
    iso(now())
}

pub fn ago_iso(d: Duration) -> String {
    iso(now() - d)
}

/// Normalize any RFC 3339 / ISO 8601 timestamp (with offset or `Z`, or a
/// naive one taken as UTC) to our storage format.
pub fn normalize(s: &str) -> Option<String> {
    if let Ok(t) = DateTime::parse_from_rfc3339(s) {
        return Some(iso(t.with_timezone(&Utc)));
    }
    for fmt in ["%Y-%m-%dT%H:%M:%S%.f", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d"] {
        if let Ok(t) = chrono::NaiveDateTime::parse_from_str(s, fmt) {
            return Some(iso(t.and_utc()));
        }
        if let Ok(d) = chrono::NaiveDate::parse_from_str(s, fmt) {
            return Some(iso(d.and_hms_opt(0, 0, 0)?.and_utc()));
        }
    }
    None
}

pub fn parse(s: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(s)
        .ok()
        .map(|t| t.with_timezone(&Utc))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_offsets_and_dates() {
        assert_eq!(
            normalize("2023-09-17T15:00:00+00:00").unwrap(),
            "2023-09-17T15:00:00.000Z"
        );
        assert_eq!(
            normalize("2023-09-17T17:00:00+02:00").unwrap(),
            "2023-09-17T15:00:00.000Z"
        );
        assert_eq!(
            normalize("2024-09-07T17:04:15.717343Z").unwrap(),
            "2024-09-07T17:04:15.717Z"
        );
        assert_eq!(normalize("2024-01-02").unwrap(), "2024-01-02T00:00:00.000Z");
        assert_eq!(normalize("yesterday"), None);
    }
}
