//! `core::Http` over the Workers `fetch` API, with a per-request timeout.

use std::time::Duration;

use futures::future::{Either, select};
use mangavault_core::{Error, Http, HttpResponse, Result};
use worker::{Delay, Fetch, Headers, Method, Request, RequestInit};

pub struct WorkerHttp {
    pub timeout: Duration,
}

impl Default for WorkerHttp {
    fn default() -> Self {
        WorkerHttp {
            timeout: Duration::from_secs(12),
        }
    }
}

impl Http for WorkerHttp {
    async fn get(&self, url: &str, headers: &[(&str, &str)]) -> Result<HttpResponse> {
        let h = Headers::new();
        for (k, v) in headers {
            h.set(k, v).map_err(|e| Error::Network(e.to_string()))?;
        }
        let mut init = RequestInit::new();
        init.with_method(Method::Get).with_headers(h);
        let req = Request::new_with_init(url, &init).map_err(|e| Error::Network(e.to_string()))?;

        let fetch = Box::pin(async move {
            let mut res = Fetch::Request(req)
                .send()
                .await
                .map_err(|e| Error::Network(e.to_string()))?;
            let status = res.status_code();
            let body = res
                .text()
                .await
                .map_err(|e| Error::Network(e.to_string()))?;
            Ok::<_, Error>(HttpResponse { status, body })
        });
        let timer = Box::pin(Delay::from(self.timeout));
        match select(fetch, timer).await {
            Either::Left((res, _)) => res,
            Either::Right(_) => Err(Error::Network(format!(
                "timed out after {:?}: {url}",
                self.timeout
            ))),
        }
    }
}
