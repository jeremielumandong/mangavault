//! Source-agnostic manga crawling: the [`Http`] transport abstraction, the
//! normalized models every site maps into, the per-site adapters, and the
//! title matching used to merge one series across sites.

pub mod error;
pub mod http;
pub mod matching;
pub mod model;
pub mod sources;

pub use error::{Error, Result};
pub use http::{Http, HttpResponse};
pub use model::*;
pub use sources::SourceId;
