-- One merged catalog: a `series` is what the app calls a manga. Each site's
-- copy of it is an `entry`; `series_key` maps normalized titles to series so
-- new entries from any site land on the same series.

CREATE TABLE series (
  id INTEGER PRIMARY KEY,
  title TEXT NOT NULL,
  alt_titles TEXT NOT NULL DEFAULT '[]',
  cover_url TEXT,
  cover_source TEXT,
  description TEXT,
  authors TEXT NOT NULL DEFAULT '[]',
  artists TEXT NOT NULL DEFAULT '[]',
  genres TEXT NOT NULL DEFAULT '[]',
  status TEXT,
  kind TEXT,
  latest_chapter REAL,
  chapter_count INTEGER NOT NULL DEFAULT 0,
  popularity REAL NOT NULL DEFAULT 0,
  first_seen_at TEXT NOT NULL,
  updated_at TEXT,
  details_fetched_at TEXT
);
CREATE INDEX series_popularity ON series(popularity DESC);
CREATE INDEX series_updated ON series(updated_at DESC);
CREATE INDEX series_first_seen ON series(first_seen_at DESC);
CREATE INDEX series_title ON series(title COLLATE NOCASE);

CREATE TABLE series_key (
  key TEXT PRIMARY KEY,
  series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE
);
CREATE INDEX series_key_series ON series_key(series_id);

-- Ids of series merged into another; clients may still hold them.
CREATE TABLE series_alias (
  old_id INTEGER PRIMARY KEY,
  series_id INTEGER NOT NULL
);

CREATE TABLE series_genre (
  series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE,
  genre TEXT NOT NULL,
  PRIMARY KEY (series_id, genre)
);
CREATE INDEX series_genre_genre ON series_genre(genre);

-- Search index; rowid = series.id, maintained by the worker.
CREATE VIRTUAL TABLE series_fts USING fts5(title, alt_titles, tokenize = 'unicode61 remove_diacritics 2');

CREATE TABLE entry (
  id INTEGER PRIMARY KEY,
  series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE,
  source TEXT NOT NULL,
  source_id TEXT NOT NULL,
  title TEXT NOT NULL,
  cover_url TEXT,
  popularity REAL NOT NULL DEFAULT 0,
  -- core::MangaDetails and Vec<core::ChapterEntry> as JSON, from the last fetch.
  details TEXT,
  chapters TEXT,
  fetched_at TEXT,
  needs_refresh INTEGER NOT NULL DEFAULT 1,
  last_error TEXT,
  UNIQUE (source, source_id)
);
CREATE INDEX entry_series ON entry(series_id);
CREATE INDEX entry_refresh ON entry(needs_refresh DESC, popularity DESC);

-- The merged chapter list. `key` is the chapter number ("12.5") or an
-- extras key; ids stay stable across re-merges.
CREATE TABLE chapter (
  id INTEGER PRIMARY KEY,
  series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE,
  key TEXT NOT NULL,
  sort REAL NOT NULL,
  number REAL,
  title TEXT,
  volume TEXT,
  published_at TEXT,
  -- [[source, sourceChapterId], ...] best first.
  candidates TEXT NOT NULL,
  pages TEXT,
  pages_source TEXT,
  pages_fetched_at TEXT,
  UNIQUE (series_id, key)
);
CREATE INDEX chapter_series_sort ON chapter(series_id, sort);

CREATE TABLE crawl_state (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE user (
  id INTEGER PRIMARY KEY,
  provider TEXT NOT NULL,
  subject TEXT NOT NULL,
  email TEXT,
  name TEXT,
  created_at TEXT NOT NULL,
  UNIQUE (provider, subject)
);

CREATE TABLE session (
  token_hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);
CREATE INDEX session_user ON session(user_id);

CREATE TABLE library (
  user_id INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE,
  series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE,
  category TEXT,
  added_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, series_id)
);
CREATE INDEX library_user_updated ON library(user_id, updated_at);

CREATE TABLE progress (
  user_id INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE,
  series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE,
  chapter_id INTEGER NOT NULL,
  page INTEGER NOT NULL,
  page_count INTEGER,
  completed INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (user_id, series_id)
);
CREATE INDEX progress_user_updated ON progress(user_id, updated_at);

CREATE TABLE read_chapter (
  user_id INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE,
  chapter_id INTEGER NOT NULL,
  series_id INTEGER NOT NULL,
  read INTEGER NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (user_id, chapter_id)
);
CREATE INDEX read_chapter_series ON read_chapter(user_id, series_id);

CREATE TABLE history (
  user_id INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE,
  series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE,
  chapter_id INTEGER NOT NULL,
  page INTEGER NOT NULL,
  read_at TEXT NOT NULL,
  PRIMARY KEY (user_id, series_id)
);
CREATE INDEX history_user_read ON history(user_id, read_at DESC);
