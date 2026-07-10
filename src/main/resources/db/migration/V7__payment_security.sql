-- Webhook replay/redelivery dedup: the unique constraint IS the guard,
-- not an exists-then-insert check (which would itself race).
CREATE TABLE stripe_events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  stripe_event_id VARCHAR(255) NOT NULL UNIQUE,
  event_type VARCHAR(100),
  processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Client-supplied (or server-generated) idempotency key so a double-submit
-- of POST /campaigns/{id}/fund reuses the same PaymentIntent instead of
-- creating a second one.
ALTER TABLE payments ADD COLUMN client_idempotency_key VARCHAR(255) UNIQUE NULL;
