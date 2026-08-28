-- =====================================================================
-- Three-tier data lifecycle for research reuse
--
-- Titolare: Università degli Studi di Cassino e del Lazio Meridionale.
-- Design rationale and risk analysis: docs/privacy/DPIA-omnimove-cassitrack.md
--
--   Tier 1  operational   journey_log with user_id          12 months
--   Tier 2  research      schema "research", pseudonymised  project lifetime
--   Tier 3  aggregate     O/D matrix, k-suppressed          indefinite
--
-- Only tier 3 is anonymous within the meaning of recital 26 and may therefore
-- be kept forever. Tier 2 is PSEUDONYMOUS — still personal data, still GDPR.
-- Dropping user_id is NOT anonymisation: recurring origin/destination pairs on
-- weekday mornings identify a person's home and workplace. See DPIA §5.1.
--
-- LEGAL BASIS is art. 6(1)(e) public interest, NOT consent, with the art. 89(1)
-- safeguards. Consequence for this schema: absence of a consent row means the
-- subject IS included; only an explicit objection excludes them. Do not invert
-- this without re-reading DPIA §3.2.
--
-- Numbering: develop ends at V17, massi_sprint_10 holds V18–V21, V22 is the
-- consent ledger. This is V23.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- =====================================================================
-- TIER 1 — operational: make journeys locatable
-- =====================================================================
-- journey_log stores origin/destination as FREE TEXT typed by the user. That
-- text may be a home address, and it cannot be generalised to a zone, which
-- makes anonymisation impossible. New columns let the write path record
-- coordinates; the research pipeline uses those and never the free text.
-- Nullable on purpose: rows written before this migration have no coordinates
-- and are simply not promoted (fail closed).

ALTER TABLE journey_log ADD COLUMN IF NOT EXISTS origin_lat DOUBLE PRECISION;
ALTER TABLE journey_log ADD COLUMN IF NOT EXISTS origin_lon DOUBLE PRECISION;
ALTER TABLE journey_log ADD COLUMN IF NOT EXISTS dest_lat   DOUBLE PRECISION;
ALTER TABLE journey_log ADD COLUMN IF NOT EXISTS dest_lon   DOUBLE PRECISION;

COMMENT ON COLUMN journey_log.origin_name IS
    'Free text typed by the user. May contain a home address — never copy into the research schema, and purge with the row at 12 months.';

CREATE SCHEMA IF NOT EXISTS research;

COMMENT ON SCHEMA research IS
    'Tier 2 (pseudonymous) and tier 3 (anonymous aggregate) mobility data. Personal data lives here: access is restricted and logged.';

-- =====================================================================
-- Zoning — spatial generalisation
-- =====================================================================
-- Stop-level or address-level origins single people out, so everything is
-- generalised to a zone before it reaches tier 2.
--
-- The default zoning is a ~500 m grid computed from coordinates: deterministic,
-- needs no external dataset, and is available immediately. It is a PLACEHOLDER.
-- Replace it with real zones (ISTAT sezioni di censimento, or the city's
-- quartieri) by populating research.zone and remapping — the rest of the
-- pipeline is agnostic to how zones are defined.
--
-- At Cassino's latitude (~41.49° N): 0.0045° lat ≈ 500 m, 0.0060° lon ≈ 500 m.

CREATE TABLE IF NOT EXISTS research.zone (
    zone_id     TEXT PRIMARY KEY,
    label       TEXT,
    -- Representative point, for mapping output only. Never a precise location.
    centre_lat  DOUBLE PRECISION,
    centre_lon  DOUBLE PRECISION,
    scheme      TEXT NOT NULL DEFAULT 'GRID500',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION research.zone_of(p_lat DOUBLE PRECISION,
                                            p_lon DOUBLE PRECISION)
RETURNS TEXT
LANGUAGE sql IMMUTABLE
AS $$
    SELECT CASE
        WHEN p_lat IS NULL OR p_lon IS NULL THEN NULL
        -- Reject coordinates far outside the service area: a stray value would
        -- create a zone of its own, which is the opposite of generalisation.
        WHEN p_lat < 41.0 OR p_lat > 42.0 OR p_lon < 13.0 OR p_lon > 14.5 THEN NULL
        ELSE 'G' || floor(p_lat / 0.0045)::BIGINT
                 || '_' || floor(p_lon / 0.0060)::BIGINT
    END
$$;

COMMENT ON FUNCTION research.zone_of IS
    'Maps a coordinate to a ~500 m grid cell. Returns NULL outside the service area so unlocatable journeys are dropped rather than singled out.';

-- =====================================================================
-- TIER 2 — pseudonymous research data
-- =====================================================================
-- Deliberately absent: name, e-mail, IP, user agent, user_id, the free-text
-- origin/destination, and cost_euros (derivable from mode and distance —
-- redundant, so minimisation excludes it). See DPIA §3.3.

CREATE TABLE IF NOT EXISTS research.journey (
    id                 BIGSERIAL PRIMARY KEY,

    -- HMAC-SHA256(user_id, salt). The salt is supplied by the caller at run time
    -- and MUST NOT be stored in this database: that separation is what keeps a
    -- dump of this schema from being re-linkable to identities (DPIA §5.8).
    subject_pseudonym  CHAR(64)    NOT NULL,

    origin_zone_id     TEXT        NOT NULL REFERENCES research.zone(zone_id),
    dest_zone_id       TEXT        NOT NULL REFERENCES research.zone(zone_id),

    mode               VARCHAR(20) NOT NULL,

    -- Date kept (longitudinal analysis needs it); clock time reduced to the hour.
    travelled_on       DATE        NOT NULL,
    hour_bucket        SMALLINT    NOT NULL CHECK (hour_bucket BETWEEN 0 AND 23),
    day_type           VARCHAR(16) NOT NULL,   -- WEEKDAY | SATURDAY | SUNDAY

    distance_km        DOUBLE PRECISION,
    co2_grams          DOUBLE PRECISION,
    green_index        INTEGER,

    promoted_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_research_journey_subject
    ON research.journey (subject_pseudonym);
CREATE INDEX IF NOT EXISTS idx_research_journey_od
    ON research.journey (origin_zone_id, dest_zone_id, day_type, hour_bucket);
CREATE INDEX IF NOT EXISTS idx_research_journey_date
    ON research.journey (travelled_on);

COMMENT ON TABLE research.journey IS
    'Tier 2 — PSEUDONYMOUS, therefore still personal data under GDPR. Never publish these rows, not even as supplementary material to a paper.';

-- =====================================================================
-- TIER 3 — anonymous aggregate
-- =====================================================================
-- Frozen, versioned releases rather than live queries. Recomputing aggregates
-- on demand over an evolving dataset enables a differencing attack: subtracting
-- two releases isolates one person's trips (DPIA §5.2).

CREATE TABLE IF NOT EXISTS research.release (
    id             BIGSERIAL PRIMARY KEY,
    label          TEXT        NOT NULL UNIQUE,
    k_threshold    INTEGER     NOT NULL CHECK (k_threshold >= 5),
    period_from    DATE        NOT NULL,
    period_to      DATE        NOT NULL,
    source_rows    BIGINT      NOT NULL,
    published_rows BIGINT      NOT NULL,
    suppressed_rows BIGINT     NOT NULL,
    notes          TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN research.release.suppressed_rows IS
    'How many O/D combinations fell below k and were withheld. Recorded so a release never silently understates what it omits.';

CREATE TABLE IF NOT EXISTS research.od_matrix (
    id                BIGSERIAL PRIMARY KEY,
    release_id        BIGINT      NOT NULL REFERENCES research.release(id) ON DELETE CASCADE,

    origin_zone_id    TEXT        NOT NULL,
    dest_zone_id      TEXT        NOT NULL,
    mode              VARCHAR(20) NOT NULL,
    day_type          VARCHAR(16) NOT NULL,
    hour_bucket       SMALLINT    NOT NULL,

    trips             BIGINT      NOT NULL,
    distinct_subjects BIGINT      NOT NULL,
    avg_distance_km   DOUBLE PRECISION,
    avg_co2_grams     DOUBLE PRECISION,
    avg_green_index   DOUBLE PRECISION,

    UNIQUE (release_id, origin_zone_id, dest_zone_id, mode, day_type, hour_bucket)
);

COMMENT ON TABLE research.od_matrix IS
    'Tier 3 — anonymous (recital 26): no subject identifier, small cells suppressed. This is the only tier that may be kept indefinitely or published.';

-- =====================================================================
-- Governance tables
-- =====================================================================

CREATE TABLE IF NOT EXISTS research.pipeline_run (
    id             BIGSERIAL PRIMARY KEY,
    step           VARCHAR(40) NOT NULL,   -- PROMOTE | AGGREGATE | PURGE_OPERATIONAL | PURGE_RESEARCH
    watermark_to   TIMESTAMPTZ,            -- promotion processed rows up to here
    rows_in        BIGINT,
    rows_out       BIGINT,
    rows_dropped   BIGINT,
    detail         TEXT,
    ran_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN research.pipeline_run.rows_dropped IS
    'Rows excluded: no coordinates, outside the service area, or subject objected. Never let this be silent — it is how we prove minimisation worked.';

-- Art. 89 requires organisational safeguards, not just technical ones: who
-- looked at tier 2, when, and under which approved project.
CREATE TABLE IF NOT EXISTS research.access_log (
    id           BIGSERIAL PRIMARY KEY,
    db_user      TEXT        NOT NULL DEFAULT current_user,
    project_ref  TEXT,
    purpose      TEXT,
    accessed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =====================================================================
-- Pipeline
-- =====================================================================

-- Tier 1 → Tier 2.
-- p_salt        pseudonymisation secret, from the application environment.
--               NEVER persist it in this database.
-- p_older_than  only journeys older than this are promoted.
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
        subject_pseudonym, origin_zone_id, dest_zone_id, mode,
        travelled_on, hour_bucket, day_type,
        distance_km, co2_grams, green_index)
    SELECT encode(hmac(j.user_id::TEXT, p_salt, 'sha256'), 'hex'),
           research.zone_of(j.origin_lat, j.origin_lon),
           research.zone_of(j.dest_lat,   j.dest_lon),
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
       -- Fail closed: a journey that cannot be generalised to a zone is dropped,
       -- never promoted with a precise or free-text location.
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

-- Tier 2 → Tier 3. Emits one frozen release.
-- Suppression is applied on BOTH trip count and distinct subjects: 20 trips made
-- by one person is not a crowd, and would single that person out.
CREATE OR REPLACE FUNCTION research.build_od_matrix(p_label TEXT,
                                                    p_from  DATE,
                                                    p_to    DATE,
                                                    p_k     INTEGER DEFAULT 10)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    v_release_id  BIGINT;
    v_source      BIGINT;
    v_published   BIGINT;
    v_total_cells BIGINT;
BEGIN
    IF p_k < 5 THEN
        RAISE EXCEPTION 'k must be at least 5; 10 is recommended (DPIA §5.1)';
    END IF;

    SELECT count(*) INTO v_source
      FROM research.journey WHERE travelled_on BETWEEN p_from AND p_to;

    INSERT INTO research.release (label, k_threshold, period_from, period_to,
                                  source_rows, published_rows, suppressed_rows)
    VALUES (p_label, p_k, p_from, p_to, v_source, 0, 0)
    RETURNING id INTO v_release_id;

    -- Two straightforward passes rather than a data-modifying CTE: the batch runs
    -- rarely, and the suppression arithmetic has to be obvious to anyone auditing
    -- this — that is the point of the whole function.
    SELECT count(*) INTO v_total_cells
      FROM (SELECT 1
              FROM research.journey
             WHERE travelled_on BETWEEN p_from AND p_to
             GROUP BY origin_zone_id, dest_zone_id, mode, day_type, hour_bucket) c;

    INSERT INTO research.od_matrix (
        release_id, origin_zone_id, dest_zone_id, mode, day_type, hour_bucket,
        trips, distinct_subjects, avg_distance_km, avg_co2_grams, avg_green_index)
    SELECT v_release_id, origin_zone_id, dest_zone_id, mode, day_type, hour_bucket,
           count(*),
           count(DISTINCT subject_pseudonym),
           round(avg(distance_km)::NUMERIC, 2),
           round(avg(co2_grams)::NUMERIC, 1),
           round(avg(green_index)::NUMERIC, 2)
      FROM research.journey
     WHERE travelled_on BETWEEN p_from AND p_to
     GROUP BY origin_zone_id, dest_zone_id, mode, day_type, hour_bucket
    HAVING count(*) >= p_k AND count(DISTINCT subject_pseudonym) >= p_k;

    GET DIAGNOSTICS v_published = ROW_COUNT;

    UPDATE research.release
       SET published_rows  = v_published,
           suppressed_rows = v_total_cells - v_published
     WHERE id = v_release_id;

    INSERT INTO research.pipeline_run (step, rows_in, rows_out, rows_dropped, detail)
    VALUES ('AGGREGATE', v_total_cells, v_published, v_total_cells - v_published,
            format('release=%s k=%s', p_label, p_k));

    RETURN v_published;
END;
$$;

-- Honours an objection received after promotion: the pseudonym is recomputable
-- from user_id and the salt, so tier 2 rows can still be removed. Tier 3 is
-- anonymous and is intentionally not touched.
CREATE OR REPLACE FUNCTION research.forget_subject(p_salt TEXT, p_user_id BIGINT)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE v_deleted BIGINT;
BEGIN
    DELETE FROM research.journey
     WHERE subject_pseudonym = encode(hmac(p_user_id::TEXT, p_salt, 'sha256'), 'hex');
    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    INSERT INTO research.pipeline_run (step, rows_out, detail)
    VALUES ('FORGET', v_deleted, 'objection or erasure honoured');

    RETURN v_deleted;
END;
$$;

-- Tier 1 retention. This is the job privacy.html § 7 already promises: until it
-- is scheduled, the notice overstates what the system does (DPIA §5.7).
CREATE OR REPLACE FUNCTION research.purge_operational(p_older_than TIMESTAMPTZ)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    v_promoted_to TIMESTAMPTZ;
    v_deleted     BIGINT;
BEGIN
    SELECT COALESCE(MAX(watermark_to), '-infinity'::TIMESTAMPTZ)
      INTO v_promoted_to
      FROM research.pipeline_run WHERE step = 'PROMOTE';

    -- Never delete operational rows that have not been promoted yet: that would
    -- lose the data outright instead of moving it down a tier.
    IF p_older_than > v_promoted_to THEN
        RAISE EXCEPTION
            'Refusing to purge up to % — journeys are only promoted up to %. Run promote_journeys first.',
            p_older_than, v_promoted_to;
    END IF;

    DELETE FROM journey_log WHERE created_at <= p_older_than;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    INSERT INTO research.pipeline_run (step, watermark_to, rows_out)
    VALUES ('PURGE_OPERATIONAL', p_older_than, v_deleted);

    RETURN v_deleted;
END;
$$;

-- Tier 2 retention. Pseudonymous data may be kept longer under art. 89(1), but
-- not forever: "indefinite" on personal data is not defensible.
CREATE OR REPLACE FUNCTION research.purge_research(p_older_than DATE)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE v_deleted BIGINT;
BEGIN
    DELETE FROM research.journey WHERE travelled_on <= p_older_than;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    INSERT INTO research.pipeline_run (step, rows_out, detail)
    VALUES ('PURGE_RESEARCH', v_deleted, format('older than %s', p_older_than));

    RETURN v_deleted;
END;
$$;

-- =====================================================================
-- Access control
-- =====================================================================
-- Least privilege: a researcher gets tier 3 only. Tier 2 access is granted
-- per approved project, by an explicit statement recorded in the DPIA review —
-- never by default, and never to a shared login.

-- Roles are cluster-level objects: creating one needs CREATEROLE, which the
-- application user may not have on a managed database. A missing role must not
-- abort the migration — the schema is still correct, the grants are simply
-- applied by a DBA afterwards. The REVOKE below runs either way, so the default
-- stays closed.
REVOKE ALL ON SCHEMA research FROM PUBLIC;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'omnimove_research_ro') THEN
        CREATE ROLE omnimove_research_ro NOLOGIN;
    END IF;

    GRANT USAGE ON SCHEMA research TO omnimove_research_ro;
    -- Tier 3 plus the zoning needed to interpret it. Nothing else.
    GRANT SELECT ON research.od_matrix, research.release, research.zone
        TO omnimove_research_ro;
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE WARNING
            'Could not create or grant to omnimove_research_ro (insufficient privilege). '
            'Schema "research" is created and closed to PUBLIC. A DBA must create the '
            'role and grant SELECT on research.od_matrix, research.release, research.zone.';
END
$$;

-- Deliberately NOT granted: research.journey (tier 2, pseudonymous).
-- To open it for one approved project:
--   CREATE ROLE proj_<name> LOGIN PASSWORD '…';
--   GRANT omnimove_research_ro TO proj_<name>;
--   GRANT SELECT ON research.journey TO proj_<name>;
-- and record project reference and purpose in research.access_log.

COMMENT ON TABLE research.access_log IS
    'Who read tier 2, when and why. Required as an organisational safeguard under art. 89(1).';
