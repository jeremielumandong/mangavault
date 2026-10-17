//! Mangavault API Worker: the HTTP API and the scheduled crawler, both over
//! one D1 database. See docs/api.md for the contract.

pub mod api;
pub mod auth;
pub mod catalog;
pub mod crawl;
pub mod db;
pub mod fetch;
pub mod sync;
pub mod time;

use worker::{Context, Env, Request, Response, Result, ScheduleContext, ScheduledEvent, event};

use crate::db::Db;
use crate::fetch::WorkerHttp;

pub struct Config {
    /// Accepted `aud` values for Apple identity tokens (bundle / service ids).
    pub apple_audiences: Vec<String>,
    /// Accepted `aud` values for Google ID tokens (OAuth client ids).
    pub google_audiences: Vec<String>,
    /// Enables POST /v1/auth/dev for local development.
    pub dev_auth: bool,
    /// Series refreshed per cron run.
    pub refresh_budget: u32,
    /// Popular-list pages per site per backfill run.
    pub popular_pages: u32,
}

impl Config {
    fn from_env(env: &Env) -> Config {
        let var = |k: &str| env.var(k).map(|v| v.to_string()).unwrap_or_default();
        let list = |k: &str| {
            var(k)
                .split(',')
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(String::from)
                .collect()
        };
        Config {
            apple_audiences: list("APPLE_AUDIENCES"),
            google_audiences: list("GOOGLE_AUDIENCES"),
            dev_auth: var("DEV_AUTH") == "true",
            refresh_budget: var("CRAWL_REFRESH_BUDGET").parse().unwrap_or(15),
            popular_pages: var("CRAWL_POPULAR_PAGES").parse().unwrap_or(3),
        }
    }
}

pub struct App {
    pub db: Db,
    pub http: WorkerHttp,
    pub config: Config,
}

impl App {
    fn new(env: &Env) -> Result<App> {
        Ok(App {
            db: Db::new(env.d1("DB")?),
            http: WorkerHttp::default(),
            config: Config::from_env(env),
        })
    }
}

#[event(fetch)]
async fn fetch(req: Request, env: Env, _ctx: Context) -> Result<Response> {
    console_error_panic_hook::set_once();
    let app = App::new(&env)?;
    match api::route(&app, req).await {
        Ok(res) => Ok(res),
        Err(e) => e.into_response(),
    }
}

#[event(scheduled)]
async fn scheduled(event: ScheduledEvent, env: Env, _ctx: ScheduleContext) {
    console_error_panic_hook::set_once();
    match App::new(&env) {
        Ok(app) => crawl::run(&app, &event.cron()).await,
        Err(e) => worker::console_error!("cron setup failed: {e}"),
    }
}
