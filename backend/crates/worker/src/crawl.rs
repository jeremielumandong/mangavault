//! Scheduled crawling. Every 15 minutes: read each site's latest-updates
//! page and refresh the series that changed (most popular first, within a
//! budget). Four times an hour, offset from those runs: walk the next pages
//! of each site's popular list so the catalog keeps growing and popularity
//! stays current. The two jobs run separately to stay within the per-run D1
//! query limit (saving a new title takes about four queries).

use mangavault_core::SourceId;

use crate::catalog::{self, ListUpsert};
use crate::{App, args, time};

pub const LATEST_CRON: &str = "*/15 * * * *";
pub const POPULAR_CRON: &str = "7,22,37,52 * * * *";

/// Popular-list scores count down from here by rank.
const POPULARITY_TOP: f64 = 100_000.0;

pub async fn run(app: &App, cron: &str) {
    match cron {
        POPULAR_CRON => popular_backfill(app).await,
        _ => {
            latest_updates(app).await;
            refresh_queue(app).await;
        }
    }
}

async fn latest_updates(app: &App) {
    for source in SourceId::ALL {
        match source.latest(&app.http, 1).await {
            Ok(list) => {
                let up = ListUpsert {
                    source,
                    items: &list.items,
                    popularity_start: None,
                    mark_refresh: true,
                };
                if let Err(e) = catalog::upsert_list(&app.db, up).await {
                    worker::console_error!("storing latest from {} failed: {e}", source.as_str());
                }
            }
            Err(e) => worker::console_warn!("latest from {} failed: {e}", source.as_str()),
        }
    }
}

async fn popular_backfill(app: &App) {
    for source in SourceId::ALL {
        let key = format!("popular_page:{}", source.as_str());
        let start: u32 = app
            .db
            .value::<String>(
                "SELECT value FROM crawl_state WHERE key = ?",
                args![&key],
                "value",
            )
            .await
            .ok()
            .flatten()
            .and_then(|v| v.parse().ok())
            .unwrap_or(1);
        let mut page = start;
        for _ in 0..app.config.popular_pages {
            match source.popular(&app.http, page).await {
                Ok(list) => {
                    // Rank continues across pages so page 3 scores below page 1.
                    let rank = (page as usize - 1) * list.items.len();
                    let up = ListUpsert {
                        source,
                        items: &list.items,
                        popularity_start: Some(POPULARITY_TOP - rank as f64),
                        mark_refresh: false,
                    };
                    if let Err(e) = catalog::upsert_list(&app.db, up).await {
                        worker::console_error!(
                            "storing popular from {} failed: {e}",
                            source.as_str()
                        );
                        break;
                    }
                    page = if list.has_next { page + 1 } else { 1 };
                    if page == 1 {
                        break;
                    }
                }
                Err(e) => {
                    worker::console_warn!(
                        "popular page {page} from {} failed: {e}",
                        source.as_str()
                    );
                    break;
                }
            }
        }
        let _ = app
            .db
            .run(
                "INSERT OR REPLACE INTO crawl_state (key, value) VALUES (?, ?)",
                args![&key, page.to_string()],
            )
            .await;
    }
    let _ = app
        .db
        .run(
            "INSERT OR REPLACE INTO crawl_state (key, value) VALUES ('popular_crawled_at', ?)",
            args![time::now_iso()],
        )
        .await;
}

/// Refresh queued series, most popular first.
async fn refresh_queue(app: &App) {
    #[derive(serde::Deserialize)]
    struct Q {
        series_id: i64,
    }
    let queued: Vec<Q> = match app
        .db
        .all(
            "SELECT series_id FROM entry WHERE needs_refresh = 1 GROUP BY series_id ORDER BY MAX(popularity) DESC LIMIT ?",
            args![app.config.refresh_budget],
        )
        .await
    {
        Ok(q) => q,
        Err(e) => {
            worker::console_error!("reading refresh queue failed: {e}");
            return;
        }
    };
    for q in queued {
        if let Err(e) = catalog::refresh_series(&app.db, &app.http, q.series_id).await {
            worker::console_error!("refresh of series {} failed: {e}", q.series_id);
        }
    }
}
