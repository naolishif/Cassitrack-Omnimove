-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- Drop the operator's name from the data: MAGNI-1xx vehicles become BUS-1xx,
-- and the line descriptions stop advertising a company that will never run
-- this system.
--
-- WHY A SEPARATE MIGRATION FROM V21
-- V21 renumbers lines, this renames vehicles. Two unrelated concerns, so two
-- files: if one of them ever has to be reverted or re-run, the other should not
-- come along for the ride.
--
-- WHY BUS-1xx
-- Four vehicles already carry generic ids — BUS1, BUS2, BUS2L, BUS3 — assigned
-- by V11 when they were bound to the real OBU antennas. This finishes the job
-- for the eleven simulated vehicles V17 introduced, keeping the shape of the
-- old id (prefix, dash, number) so nothing but the prefix moves.
--
-- BUS1 / BUS2 / BUS2L / BUS3 ARE DELIBERATELY UNTOUCHED
-- Those are the identifiers the physical OBU hardware publishes on MQTT. They
-- are not ours to rename: changing them here would silently detach every real
-- antenna from its bus. They are already free of the operator's name anyway.
--
-- WHAT DOES NOT FOLLOW THE RENAME
-- current_vehicle_id is the MQTT identity, so historical telemetry keeps the
-- old value: InfluxDB points are tagged vehicle_id=MAGNI-1xx and stay that way,
-- and Redis holds live positions under vehicle_positions:MAGNI-1xx until those
-- keys expire. Past analytics for these eleven simulated buses therefore will
-- not join to them any more. Accepted: they are simulated, the real fleet
-- (BUS*) is unaffected, and the alternative is keeping a brand in the data
-- forever. The simulator itself needs no change — it reads current_vehicle_id
-- from this table and publishes to cassitrack/<vehicle_id>/position.
--
-- Plates (targa) are left alone: a registration is a registration, and
-- 'FR200MG' names no one.
-- ────────────────────────────────────────────────────────────────

-- Pattern-based rather than eleven literals, so any MAGNI-xxx left over from an
-- earlier seed is caught too. 'MAGNI-' is six characters; the rest is the
-- number, which is preserved.
UPDATE buses
SET    current_vehicle_id = 'BUS-' || substring(current_vehicle_id FROM 7)
WHERE  current_vehicle_id LIKE 'MAGNI-%'
  -- current_vehicle_id is UNIQUE: never overwrite an id already in use.
  AND  NOT EXISTS (
      SELECT 1 FROM buses b2
      WHERE b2.current_vehicle_id = 'BUS-' || substring(buses.current_vehicle_id FROM 7)
  );

-- The timetable source belongs in the description; the operator's name does not.
UPDATE routes
SET    description = 'Orario in vigore dal 11/09/2024'
WHERE  description = 'Autoservizi Magni - orario 11/09/2024';
