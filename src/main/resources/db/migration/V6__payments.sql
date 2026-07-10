-- Stripe-backed campaign funding: one row per PaymentIntent
CREATE TABLE payments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  campaign_id BIGINT NOT NULL,
  advertiser_id BIGINT NOT NULL,
  stripe_payment_intent_id VARCHAR(255) NOT NULL UNIQUE,
  amount DECIMAL(18,4) NOT NULL,
  currency VARCHAR(10) NOT NULL DEFAULT 'usd',
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
  FOREIGN KEY (advertiser_id) REFERENCES users(id)
);

CREATE INDEX idx_payments_campaign ON payments(campaign_id);
CREATE INDEX idx_payments_stripe_intent ON payments(stripe_payment_intent_id);
