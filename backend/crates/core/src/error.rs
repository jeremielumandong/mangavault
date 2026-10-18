use std::fmt;

#[derive(Debug, Clone, PartialEq)]
pub enum Error {
    /// The request never completed (DNS, TLS, timeout, ...).
    Network(String),
    /// The site answered with a non-success status.
    Status { status: u16, url: String },
    /// The site answered, but not with the markup or JSON we expect
    /// (layout change, bot interstitial, ...).
    Parse(String),
    /// The caller asked for something that cannot exist (bad id, ...).
    Invalid(String),
}

pub type Result<T> = std::result::Result<T, Error>;

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Network(m) => write!(f, "network error: {m}"),
            Error::Status { status, url } => write!(f, "HTTP {status} from {url}"),
            Error::Parse(m) => write!(f, "unexpected response: {m}"),
            Error::Invalid(m) => write!(f, "invalid request: {m}"),
        }
    }
}

impl std::error::Error for Error {}

impl From<serde_json::Error> for Error {
    fn from(e: serde_json::Error) -> Self {
        Error::Parse(e.to_string())
    }
}
