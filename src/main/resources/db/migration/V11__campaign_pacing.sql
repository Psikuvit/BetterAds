-- Optional flight window for a campaign, used by AdSelectionService to
-- compute an informational pacing signal (spent-vs-elapsed-time). NULL
-- means "unpaced" -- no pacing signal is computed for that campaign.
ALTER TABLE campaigns ADD COLUMN starts_at TIMESTAMP NULL;
ALTER TABLE campaigns ADD COLUMN ends_at TIMESTAMP NULL;
