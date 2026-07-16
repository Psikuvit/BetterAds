-- Phase 1 of the iframe -> SDK migration: session + playback-event API.
-- Replaces the implicit "widget render = trust" model with an explicit
-- session (one ad impression) plus a server-validated event sequence.

-- Non-secret, publisher-facing key (same trust model as a Stripe publishable
-- key) identifying which registered site/app is calling the placement API.
CREATE TABLE sites (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  publisher_id BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  site_key VARCHAR(64) NOT NULL UNIQUE,
  allowed_origin VARCHAR(255) NULL,
  bundle_id VARCHAR(255) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sites_site_key ON sites(site_key);
CREATE INDEX idx_sites_publisher_id ON sites(publisher_id);

-- One row per ad impression session, from placement request through to
-- session expiry. ad_version_id/campaign_id are snapshotted at issuance so
-- later billing/events are unambiguous even if the underlying ad changes.
CREATE TABLE ad_sessions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  site_id BIGINT NOT NULL,
  ad_id BIGINT NOT NULL,
  ad_version_id BIGINT NOT NULL,
  campaign_id BIGINT NOT NULL,
  session_token VARCHAR(255) NOT NULL UNIQUE,
  viewer_ip VARCHAR(64) NULL,
  device_info VARCHAR(512) NULL,
  issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  FOREIGN KEY (site_id) REFERENCES sites(id),
  FOREIGN KEY (ad_id) REFERENCES ads(id),
  FOREIGN KEY (ad_version_id) REFERENCES ad_versions(id),
  FOREIGN KEY (campaign_id) REFERENCES campaigns(id)
);

CREATE INDEX idx_ad_sessions_session_token ON ad_sessions(session_token);

-- Durable audit trail of every accepted playback event, replacing the old
-- Redis-only, ephemeral single-use enforcement. UNIQUE(session_id,
-- event_type) is the DB-level backstop for "each event type is single-use
-- per session" (the Redis SETNX check is only a fast-path in front of this).
CREATE TABLE session_events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (session_id) REFERENCES ad_sessions(id),
  UNIQUE (session_id, event_type)
);

-- Optional manifest duration, in seconds. Left NULL unless already populated
-- by the processing pipeline; the SDK falls back to reading actual video
-- duration client-side when absent.
ALTER TABLE ad_versions ADD COLUMN duration_seconds BIGINT NULL;
