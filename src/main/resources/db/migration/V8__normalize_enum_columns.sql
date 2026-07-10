-- users.role previously had no @Enumerated annotation, so Hibernate wrote
-- it using JPA's default ORDINAL mapping (Role: ADVERTISER=0, ADMIN=1,
-- PUBLISHER=2) for any row inserted through the app itself. The V2 seed
-- rows used text literals directly and are unaffected. Restore any
-- ordinal-encoded rows to the enum name now that the column is mapped
-- with @Enumerated(EnumType.STRING).
UPDATE users SET role = 'ADVERTISER' WHERE role = '0';
UPDATE users SET role = 'ADMIN' WHERE role = '1';
UPDATE users SET role = 'PUBLISHER' WHERE role = '2';

-- ads/campaigns/payments.status were plain lowercase strings before being
-- mapped to AdStatus/CampaignStatus/PaymentStatus via
-- @Enumerated(EnumType.STRING), which matches by exact-case Enum.valueOf().
-- Uppercase existing data so it still reads under the new mapping.
UPDATE ads SET status = UPPER(status);
UPDATE campaigns SET status = UPPER(status);
UPDATE payments SET status = UPPER(status);
