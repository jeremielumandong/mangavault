-- "Remove from Continue reading": the progress row stays (so the reader still
-- resumes where it was), it just stops showing on Home until read again.
ALTER TABLE progress ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0;
