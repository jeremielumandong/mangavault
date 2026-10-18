-- ComicHubFree is no longer a source: it answers with a bot check and its
-- page links only return a placeholder image. Same cleanup as WeebCentral
-- (0002): series also on another site rebuild without it, the rest go.

UPDATE entry SET needs_refresh = 1
WHERE source != 'comichubfree'
  AND series_id IN (SELECT series_id FROM entry WHERE source = 'comichubfree');

DELETE FROM entry WHERE source = 'comichubfree';

UPDATE chapter SET pages = NULL, pages_source = NULL, pages_fetched_at = NULL
WHERE pages_source = 'comichubfree';

DELETE FROM chapter
WHERE NOT EXISTS (
  SELECT 1 FROM json_each(chapter.candidates)
  WHERE json_extract(value, '$[0]') != 'comichubfree'
);

DELETE FROM series_fts
WHERE rowid IN (SELECT id FROM series WHERE id NOT IN (SELECT series_id FROM entry));
DELETE FROM series WHERE id NOT IN (SELECT series_id FROM entry);

DELETE FROM crawl_state WHERE key = 'popular_page:comichubfree';
