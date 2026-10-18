-- WeebCentral is no longer a source: its chapter images don't load in the
-- apps. Drop its copies; series that also exist on another site keep going
-- and are queued to rebuild their chapter lists without it.

-- Rebuild every series that had a WeebCentral copy, from its other sites.
UPDATE entry SET needs_refresh = 1
WHERE source != 'weebcentral'
  AND series_id IN (SELECT series_id FROM entry WHERE source = 'weebcentral');

DELETE FROM entry WHERE source = 'weebcentral';

-- Page links cached from WeebCentral; the next read fetches another site.
UPDATE chapter SET pages = NULL, pages_source = NULL, pages_fetched_at = NULL
WHERE pages_source = 'weebcentral';

-- Chapters only WeebCentral had can't be read anywhere.
DELETE FROM chapter
WHERE NOT EXISTS (
  SELECT 1 FROM json_each(chapter.candidates)
  WHERE json_extract(value, '$[0]') != 'weebcentral'
);

-- Series that were WeebCentral-only. Their chapters, keys and genres go
-- with them (ON DELETE CASCADE), and so do library rows pointing at them.
DELETE FROM series_fts
WHERE rowid IN (SELECT id FROM series WHERE id NOT IN (SELECT series_id FROM entry));
DELETE FROM series WHERE id NOT IN (SELECT series_id FROM entry);

DELETE FROM crawl_state WHERE key = 'popular_page:weebcentral';
