-- =====================================================================
-- V43 — Tier 2 keeps the exact origin and destination
--
-- V28 generalised both endpoints to a ~500 m grid cell before promoting a
-- journey, on the principle of carrying as little as the analysis needs. The
-- analysis turned out to need more: the research question is which places in
-- Cassino people travel between, and a 500 m cell merges the station with the
-- street behind it. The decision (2026-10-05) is to keep the endpoints exact
-- and detach the subject instead — the pseudonym was, and remains, the part
-- that must not lead back to a person.
--
-- CONSEQUENCE, to be carried into the DPIA and the privacy notice: exact
-- origin-destination pairs with a stable pseudonym are a quasi-identifier. A
-- commute repeated daily singles someone out even with no user id attached, so
-- tier 2 is now MORE re-identifiable than it was, not less. It was already
-- pseudonymous data under the GDPR and it still is; what changes is that
-- calling it "anonymised" in any published text would be wrong, and tier 2 must
-- never leave the server. Tier 3 is unaffected: it still aggregates over zones
-- with k >= 10 suppression, and that is the only layer meant to be published.
--
-- The zone columns stay: tier 3 aggregates over them, and build_od_matrix,
-- purge_research and forget_subject are untouched by this migration.
-- =====================================================================

ALTER TABLE research.journey
    ADD COLUMN IF NOT EXISTS origin_lat DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS origin_lon DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS dest_lat   DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS dest_lon   DOUBLE PRECISION;

-- Left nullable on purpose. Rows promoted before this migration were generalised
-- to a zone and their exact endpoints no longer exist anywhere: a NULL here means
-- "promoted under the old rule", not a defect. Every row written from now on
-- carries all four, because promote_journeys always supplies them.
COMMENT ON COLUMN research.journey.origin_lat IS
    'Exact origin latitude. NULL only for rows promoted before V43, when endpoints were generalised to a zone.';

COMMENT ON TABLE research.journey IS
    'Tier 2 — PSEUDONYMOUS and, since V43, carrying exact origin/destination. That combination is a quasi-identifier: treat as personal data, never publish these rows, not even as supplementary material to a paper. Tier 3 (research.od_matrix) is the publishable layer.';

-- Promotion, with the endpoints carried over unchanged.
CREATE OR REPLACE FUNCTION research.promote_journeys(p_salt        TEXT,
                                                     p_older_than  TIMESTAMPTZ)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    v_watermark TIMESTAMPTZ;
    v_in        BIGINT := 0;
    v_out       BIGINT := 0;
BEGIN
    IF p_salt IS NULL OR length(p_salt) < 32 THEN
        RAISE EXCEPTION 'A pseudonymisation salt of at least 32 characters is required';
    END IF;

    -- Resume from where the last successful promotion stopped.
    SELECT COALESCE(MAX(watermark_to), '-infinity'::TIMESTAMPTZ)
      INTO v_watermark
      FROM research.pipeline_run
     WHERE step = 'PROMOTE';

    SELECT count(*) INTO v_in
      FROM journey_log
     WHERE created_at > v_watermark AND created_at <= p_older_than;

    -- Zones referenced by the rows about to be inserted must exist first.
    INSERT INTO research.zone (zone_id, centre_lat, centre_lon, scheme)
    SELECT DISTINCT z.zone_id,
           (floor(z.lat / 0.0045) + 0.5) * 0.0045,
           (floor(z.lon / 0.0060) + 0.5) * 0.0060,
           'GRID500'
      FROM (
            SELECT research.zone_of(origin_lat, origin_lon) AS zone_id,
                   origin_lat AS lat, origin_lon AS lon
              FROM journey_log
             WHERE created_at > v_watermark AND created_at <= p_older_than
            UNION
            SELECT research.zone_of(dest_lat, dest_lon), dest_lat, dest_lon
              FROM journey_log
             WHERE created_at > v_watermark AND created_at <= p_older_than
           ) z
     WHERE z.zone_id IS NOT NULL
    ON CONFLICT (zone_id) DO NOTHING;

    INSERT INTO research.journey (
        subject_pseudonym, origin_zone_id, dest_zone_id,
        origin_lat, origin_lon, dest_lat, dest_lon, mode,
        travelled_on, hour_bucket, day_type,
        distance_km, co2_grams, green_index)
    SELECT encode(hmac(j.user_id::TEXT, p_salt, 'sha256'), 'hex'),
           research.zone_of(j.origin_lat, j.origin_lon),
           research.zone_of(j.dest_lat,   j.dest_lon),
           -- Exact endpoints: the research question is about where people go,
           -- and a 500 m cell answers it too coarsely. The subject is what gets
           -- detached, not the geography.
           j.origin_lat, j.origin_lon, j.dest_lat, j.dest_lon,
           j.mode,
           (j.created_at AT TIME ZONE 'Europe/Rome')::DATE,
           EXTRACT(HOUR FROM j.created_at AT TIME ZONE 'Europe/Rome')::SMALLINT,
           CASE EXTRACT(ISODOW FROM j.created_at AT TIME ZONE 'Europe/Rome')
                WHEN 6 THEN 'SATURDAY' WHEN 7 THEN 'SUNDAY' ELSE 'WEEKDAY' END,
           round(j.distance_km::NUMERIC, 1),
           round(j.co2_grams::NUMERIC, 0),
           j.green_index
      FROM journey_log j
     WHERE j.created_at > v_watermark
       AND j.created_at <= p_older_than
       -- A journey outside the zone grid is still dropped, but the reason has
       -- changed: the exact endpoints are kept now, so this is no longer about
       -- withholding precision. The zone is what tier 3 aggregates over, and
       -- research.journey declares both zone columns NOT NULL.
       AND research.zone_of(j.origin_lat, j.origin_lon) IS NOT NULL
       AND research.zone_of(j.dest_lat,   j.dest_lon)   IS NOT NULL
       -- Right to object, art. 21(6). The ledger is an OBJECTION register, not a
       -- consent register: no row means included. The policy-version check that
       -- applies to consents is deliberately NOT applied here — an objection
       -- must not expire when the notice is reworded.
       AND NOT EXISTS (
             SELECT 1
               FROM user_consents c
              WHERE c.user_id = j.user_id
                AND c.consent_type = 'RESEARCH_USE'
                AND c.granted = false
                AND c.recorded_at = (
                      SELECT MAX(c2.recorded_at) FROM user_consents c2
                       WHERE c2.user_id = c.user_id AND c2.consent_type = 'RESEARCH_USE'));

    GET DIAGNOSTICS v_out = ROW_COUNT;

    INSERT INTO research.pipeline_run (step, watermark_to, rows_in, rows_out, rows_dropped)
    VALUES ('PROMOTE', p_older_than, v_in, v_out, v_in - v_out);

    RETURN v_out;
END;
$$;
