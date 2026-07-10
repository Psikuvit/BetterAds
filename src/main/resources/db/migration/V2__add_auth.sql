-- Add password hash column to users table for JWT auth

ALTER TABLE users ADD COLUMN password_hash VARCHAR(255) NOT NULL DEFAULT '';

-- Seed test users (bcrypt hashes for password: "password123")
-- These hashes are pre-computed and can be used for local testing
-- In production, use the AuthController /auth/register endpoint

INSERT INTO users (email, password_hash, role, created_at) VALUES
  ('advertiser@betterads.io', '$2a$10$R9h7cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jKMm2', 'ADVERTISER', CURRENT_TIMESTAMP),
  ('publisher@betterads.io', '$2a$10$R9h7cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jKMm2', 'PUBLISHER', CURRENT_TIMESTAMP),
  ('admin@betterads.io', '$2a$10$R9h7cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jKMm2', 'ADMIN', CURRENT_TIMESTAMP);
