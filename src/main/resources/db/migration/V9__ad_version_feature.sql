-- Tags which premium AI feature (if any) produced this ad version, e.g.
-- 'translation' for the HuggingFace EN->FR dubbing provider. NULL means the
-- version was produced by a free/dev provider (mock, local) and is billed
-- at the plain locale rate. Used by BillingService to add a per-view surcharge.
ALTER TABLE ad_versions ADD COLUMN feature VARCHAR(64) NULL;
