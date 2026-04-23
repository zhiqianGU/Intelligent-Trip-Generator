ALTER TABLE place
    ADD COLUMN website_uri VARCHAR(512) NULL AFTER external_ref,
    ADD COLUMN google_maps_uri VARCHAR(512) NULL AFTER website_uri,
    ADD COLUMN business_status VARCHAR(64) NULL AFTER google_maps_uri;


TRUNCATE TABLE place;
DELETE FROM place;

DELETE FROM place;
ALTER TABLE place AUTO_INCREMENT = 1;

UPDATE place
SET latitude = NULL,
    longitude = NULL,
    source = NULL
WHERE source IN ('GEOAPIFY', 'GOOGLE_GEOCODING', 'GOOGLE_GEOCODING_ANCHOR')
   OR latitude IS NOT NULL
   OR longitude IS NOT NULL;

UPDATE place
SET latitude = NULL,
    longitude = NULL
WHERE latitude IS NOT NULL
   OR longitude IS NOT NULL;

UPDATE place
SET latitude = NULL, longitude = NULL
WHERE name LIKE '%Gallery of Modern Art%'
   OR name LIKE '%GOMA%'
   OR name LIKE '%Park%';

UPDATE place
SET latitude = NULL, longitude = NULL
WHERE name LIKE '%The Rocks Discovery Museum%'
   OR address LIKE '%The Rocks Discovery Museum%';

UPDATE place
SET latitude = NULL,
    longitude = NULL
WHERE LOWER(name) = 'nielsen park'
   OR LOWER(address) LIKE '%nielsen park%';
