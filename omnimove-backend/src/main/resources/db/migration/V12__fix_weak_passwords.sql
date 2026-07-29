-- ================================================================
-- V12__fix_weak_passwords.sql
-- OMNIMOVE — Remove/update accounts seeded with weak passwords
--
-- WHY THIS EXISTS:
--   V2__init_data.sql seeded admin/traveller with plaintext-style
--   weak passwords (adminpassword123, travellerpassword123).
--   V4__sim_users.sql seeded 30 simulation users all sharing
--   the password "test1234".
--
--   Flyway migration files are immutable once run (checksum-verified),
--   so the fix must come as a new migration, not an edit to V2/V4.
--
-- EFFECT:
--   1. Simulation users (@sim.omnimove.it) — passwords updated.
--      Kept for statistics simulation, but "test1234" replaced.
--   2. admin@omnimove.it / traveller@omnimove.it — passwords
--      updated to strong credentials (see below).
--
-- NEW CREDENTIALS:
--   admin@omnimove.it         → Admin_OmniMove2026!
--   traveller@omnimove.it     → Traveller_OmniMove2026*
--   *@sim.omnimove.it (x30)   → SimUser_OmniMove2026!
-- ================================================================

-- 1. Update sim users password (was: test1234 — shared across all 30 accounts)
--    New shared password: SimUser_OmniMove2026!
UPDATE users
SET password = '$2a$12$r/KOXkhqCpBgqGr2Dc//q.dzb8VH/nPTRHECWdb9LVc1QZuHClUhW'
WHERE email LIKE '%@sim.omnimove.it';

-- 2. Update admin password (was: adminpassword123)
--    New: Admin_OmniMove2026!
UPDATE users
SET password = '$2a$12$1wVVr2YRZ4GHBF3EPj5CrefNzRQNCw7NM/qKyCp82k0BNwS7wE1hi'
WHERE email = 'admin@omnimove.it';

-- 3. Update traveller password (was: travellerpassword123)
--    New: Traveller_OmniMove2026*
UPDATE users
SET password = '$2a$12$xqQRWFXbFT0hzzgUWiiszuw/.2w0U3GDRWB5eFYZnLJltel8VFsRy'
WHERE email = 'traveller@omnimove.it';
