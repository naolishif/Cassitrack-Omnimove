-- Runtime feature flags, editable from the admin dashboard.
-- Two switches govern where OmniMove is allowed to call Google Maps:
--   google.search   -> traffic-aware travel time in the journey planner
--   google.stop_eta -> real-time delay recalculation in the stop popup
-- Walk / bike / scooter always use Google and are NOT governed here.
CREATE TABLE IF NOT EXISTS app_settings (
    setting_key   VARCHAR(64)  PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO app_settings (setting_key, setting_value) VALUES
    ('google.search',   'true'),
    ('google.stop_eta', 'true')
ON CONFLICT (setting_key) DO NOTHING;
