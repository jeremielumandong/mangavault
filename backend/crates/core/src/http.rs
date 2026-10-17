use crate::{Error, Result};

#[derive(Debug, Clone)]
pub struct HttpResponse {
    pub status: u16,
    pub body: String,
}

/// Minimal GET transport. The Worker implements it with `fetch`; tests
/// implement it with canned fixtures.
#[allow(async_fn_in_trait)]
pub trait Http {
    async fn get(&self, url: &str, headers: &[(&str, &str)]) -> Result<HttpResponse>;
}

/// GET and fail on any non-2xx status.
pub async fn get_ok<H: Http>(http: &H, url: &str, headers: &[(&str, &str)]) -> Result<String> {
    let res = http.get(url, headers).await?;
    if !(200..300).contains(&res.status) {
        return Err(Error::Status {
            status: res.status,
            url: url.to_string(),
        });
    }
    Ok(res.body)
}

pub const BROWSER_UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

/// Reject Cloudflare / bot-check interstitials that come back as HTTP 200.
pub fn check_interstitial(body: &str, url: &str) -> Result<()> {
    let head: String = body.chars().take(4000).collect::<String>().to_lowercase();
    if head.contains("<title>just a moment")
        || head.contains("<title>attention required")
        || head.contains("challenge-platform")
        || head.contains("checking your browser")
    {
        return Err(Error::Parse(format!("bot-check interstitial at {url}")));
    }
    Ok(())
}
