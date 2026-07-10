-- Add campaign lifecycle status and running spend counter
ALTER TABLE campaigns ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'active';
ALTER TABLE campaigns ADD COLUMN spent DECIMAL(18,4) NOT NULL DEFAULT 0;