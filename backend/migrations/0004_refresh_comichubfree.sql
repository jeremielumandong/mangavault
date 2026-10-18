-- ComicHubFree titles were stored with the site's SEO heading ("Read X
-- Comics Online for Free"), and its cached page links may point at a lazy-load
-- placeholder. Re-fetch its series and drop its cached pages.
UPDATE entry SET needs_refresh = 1 WHERE source = 'comichubfree';

UPDATE chapter SET pages = NULL, pages_source = NULL, pages_fetched_at = NULL
WHERE pages_source = 'comichubfree';
