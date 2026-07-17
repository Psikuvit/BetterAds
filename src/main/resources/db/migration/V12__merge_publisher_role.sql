-- The PUBLISHER role is merged into ADVERTISER: one role now covers both
-- buying ad campaigns and registering a site to display ads, instead of
-- splitting that across two roles (see storage/dto/Role.java). Convert any
-- existing PUBLISHER users so their stored role still matches a valid enum
-- constant now that PUBLISHER no longer exists.
UPDATE users SET role = 'ADVERTISER' WHERE role = 'PUBLISHER';
