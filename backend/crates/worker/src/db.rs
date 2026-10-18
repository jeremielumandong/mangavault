//! Thin helpers over D1: typed arguments, row decoding, and batches.

use serde::de::DeserializeOwned;
use worker::wasm_bindgen::JsValue;
use worker::{D1Database, D1PreparedStatement, Result};

pub enum Arg {
    Null,
    Num(f64),
    Text(String),
}

impl From<&str> for Arg {
    fn from(v: &str) -> Self {
        Arg::Text(v.to_string())
    }
}
impl From<String> for Arg {
    fn from(v: String) -> Self {
        Arg::Text(v)
    }
}
impl From<&String> for Arg {
    fn from(v: &String) -> Self {
        Arg::Text(v.clone())
    }
}
impl From<i64> for Arg {
    fn from(v: i64) -> Self {
        Arg::Num(v as f64)
    }
}
impl From<u32> for Arg {
    fn from(v: u32) -> Self {
        Arg::Num(v as f64)
    }
}
impl From<usize> for Arg {
    fn from(v: usize) -> Self {
        Arg::Num(v as f64)
    }
}
impl From<f64> for Arg {
    fn from(v: f64) -> Self {
        Arg::Num(v)
    }
}
impl From<bool> for Arg {
    fn from(v: bool) -> Self {
        Arg::Num(if v { 1.0 } else { 0.0 })
    }
}
impl<T: Into<Arg>> From<Option<T>> for Arg {
    fn from(v: Option<T>) -> Self {
        v.map(Into::into).unwrap_or(Arg::Null)
    }
}

impl Arg {
    fn js(&self) -> JsValue {
        match self {
            Arg::Null => JsValue::NULL,
            Arg::Num(n) => JsValue::from_f64(*n),
            Arg::Text(s) => JsValue::from_str(s),
        }
    }
}

/// `args![a, b, c]` → `Vec<Arg>`.
#[macro_export]
macro_rules! args {
    ($($x:expr),* $(,)?) => { vec![$($crate::db::Arg::from($x)),*] };
}

#[derive(Clone)]
pub struct Db(pub std::rc::Rc<D1Database>);

impl Db {
    pub fn new(d1: D1Database) -> Self {
        Db(std::rc::Rc::new(d1))
    }

    pub fn stmt(&self, sql: &str, args: Vec<Arg>) -> Result<D1PreparedStatement> {
        let values: Vec<JsValue> = args.iter().map(Arg::js).collect();
        self.0.prepare(sql).bind(&values)
    }

    pub async fn all<T: DeserializeOwned>(&self, sql: &str, args: Vec<Arg>) -> Result<Vec<T>> {
        self.stmt(sql, args)?.all().await?.results()
    }

    pub async fn first<T: DeserializeOwned>(&self, sql: &str, args: Vec<Arg>) -> Result<Option<T>> {
        self.stmt(sql, args)?.first(None).await
    }

    /// A single column of the first row.
    pub async fn value<T: DeserializeOwned>(
        &self,
        sql: &str,
        args: Vec<Arg>,
        col: &str,
    ) -> Result<Option<T>> {
        self.stmt(sql, args)?.first(Some(col)).await
    }

    /// Run a write; returns the number of changed rows.
    pub async fn run(&self, sql: &str, args: Vec<Arg>) -> Result<usize> {
        let res = self.stmt(sql, args)?.run().await?;
        Ok(res.meta()?.and_then(|m| m.changes).unwrap_or(0))
    }

    /// Run a write and return the inserted row id.
    pub async fn insert(&self, sql: &str, args: Vec<Arg>) -> Result<i64> {
        let res = self.stmt(sql, args)?.run().await?;
        res.meta()?
            .and_then(|m| m.last_row_id)
            .ok_or_else(|| worker::Error::RustError("insert returned no row id".into()))
    }

    /// Run statements in one implicit transaction.
    pub async fn batch(&self, stmts: Vec<D1PreparedStatement>) -> Result<()> {
        if !stmts.is_empty() {
            self.0.batch(stmts).await?;
        }
        Ok(())
    }
}

/// Decode a JSON text column, treating NULL/garbage as the default.
pub fn json_col<T: DeserializeOwned + Default>(s: &Option<String>) -> T {
    s.as_deref()
        .and_then(|s| serde_json::from_str(s).ok())
        .unwrap_or_default()
}
