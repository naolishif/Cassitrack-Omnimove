-- =================================================================
-- V25: two more Google switches for the admin dashboard
--
--   google.geocoding   -> turning a point tapped on the map into a
--                         street name (Reverse Geocoding API)
--   google.route_shape -> the drawn walking and riding paths
--                         (Directions API)
--
-- The second is not a new feature: the itineraries have been drawn
-- with Google since walking paths were added, and that call was the
-- only one on the Google bill with no way to turn it off. A dashboard
-- that offers to stop calling Google should mean it.
--
-- Both default ON, matching what the application does today. Turning
-- either off costs a feature, never correctness: without geocoding a
-- picked point keeps its coordinates as a label, and without shapes
-- the legs are drawn as straight lines between their stops.
-- =================================================================

INSERT INTO app_settings (setting_key, setting_value) VALUES
    ('google.geocoding',   'true'),
    ('google.route_shape', 'true')
ON CONFLICT (setting_key) DO NOTHING;
