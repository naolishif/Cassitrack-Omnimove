-- =================================================================
-- V30: COOKIE_NOTICE joins the consent ledger
--
-- The banner used to decide whether it had already been shown by
-- reading localStorage, which binds the answer to a browser rather
-- than to a person: the same user saw it again on a second device,
-- and never saw it again after clearing site data.
--
-- It is now recorded per user, like every other entry here. It could
-- not reuse PRIVACY_NOTICE: registration records that one the moment
-- the account is created, so a banner keyed off it would never
-- appear. The two also cover different ground — art. 13 information
-- about the processing, against art. 122 acknowledgement of what is
-- written to the device — and they are two separate documents.
--
-- No data change: consent_type is free text and carries no CHECK.
-- Only the column comment, which enumerates the accepted values and
-- would otherwise go stale.
-- =================================================================

COMMENT ON COLUMN user_consents.consent_type IS
    'PRIVACY_NOTICE | COOKIE_NOTICE | PROFILING | THIRD_PARTY_CONTENT | RESEARCH_USE';
