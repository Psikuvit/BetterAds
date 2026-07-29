-- Short-lived numeric codes for the forgot-password flow (emailed via Resend),
-- separate from password_reset_tokens which now stores the short session token
-- issued after a code is successfully verified. Codes store only a hash.
CREATE TABLE password_reset_codes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  code_hash VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  used_at TIMESTAMP NULL,
  invalidated_at TIMESTAMP NULL,
  attempts INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_password_reset_codes_user ON password_reset_codes(user_id);
CREATE INDEX idx_password_reset_codes_code_hash ON password_reset_codes(code_hash);
