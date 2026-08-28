-- =====================================================================
-- Anti-differencing safeguard for aggregate releases (DPIA §5.2)
--
-- Now that releases are produced automatically, two of them could end up
-- covering overlapping periods. That is precisely the differencing attack:
-- subtract a release covering Jan–Jun from one covering Jan–Sep and what is
-- left is Jul–Sep computed WITHOUT its own k-suppression — individual trips
-- reappear.
--
-- Disjoint periods make the attack impossible by construction, so the database
-- enforces it rather than trusting the caller. The scheduler publishes one
-- closed calendar quarter at a time, which satisfies this naturally.
--
-- To republish a period (e.g. after correcting the zoning), delete the existing
-- release first. That is a deliberate act and leaves a trace, which is the
-- intent — it must not be possible to do it by accident.
-- =====================================================================

ALTER TABLE research.release
    ADD CONSTRAINT chk_release_period_order CHECK (period_from <= period_to);

ALTER TABLE research.release
    ADD CONSTRAINT excl_release_no_overlap
    EXCLUDE USING gist (daterange(period_from, period_to, '[]') WITH &&);

COMMENT ON CONSTRAINT excl_release_no_overlap ON research.release IS
    'Releases must cover disjoint periods: overlapping aggregates can be subtracted from one another to recover suppressed cells.';
