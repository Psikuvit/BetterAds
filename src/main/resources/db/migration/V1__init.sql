-- Flyway baseline migration: create core tables for BetterAds

CREATE TABLE users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(255) NOT NULL UNIQUE,
  role VARCHAR(50) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE campaigns (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  advertiser_id BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  budget DECIMAL(18,4) DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (advertiser_id) REFERENCES users(id)
);

CREATE TABLE ads (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  campaign_id BIGINT NOT NULL,
  title VARCHAR(512),
  storage_key VARCHAR(1024) NOT NULL,
  status VARCHAR(50) NOT NULL,
  target_locale VARCHAR(10),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (campaign_id) REFERENCES campaigns(id)
);

CREATE TABLE ad_versions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ad_id BIGINT NOT NULL,
  locale VARCHAR(10),
  storage_key VARCHAR(1024) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (ad_id) REFERENCES ads(id)
);

CREATE TABLE views (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ad_version_id BIGINT NOT NULL,
  viewed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  viewer_ip VARCHAR(100),
  device_info VARCHAR(255),
  FOREIGN KEY (ad_version_id) REFERENCES ad_versions(id)
);

CREATE INDEX idx_ads_status ON ads(status);
CREATE INDEX idx_ad_versions_ad ON ad_versions(ad_id);
CREATE INDEX idx_views_version ON views(ad_version_id);
