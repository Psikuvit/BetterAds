-- Stores the unique embed token generated for each live ad
CREATE TABLE ad_links (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ad_id BIGINT NOT NULL UNIQUE,
  token VARCHAR(64) NOT NULL UNIQUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (ad_id) REFERENCES ads(id)
);

CREATE INDEX idx_ad_links_token ON ad_links(token);
