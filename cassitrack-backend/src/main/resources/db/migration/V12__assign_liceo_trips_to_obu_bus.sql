-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- V12__assign_liceo_trips_to_obu_bus.sql
--
-- Hands the Liceo half-runs to the bus that actually drives them.
--
-- WHY
-- ---
-- V5 planned LINEA_2 and LINEA_2_LIC as one vehicle's duty: both sets of trips
-- were assigned to MAGNI-003. V11 then split that duty in two, because the OBU
-- feed broadcasts BUS2 and BUS2L as separate units, and gave the half-run its
-- own bus (MAGNI-005 → BUS2L).
--
-- The trips were left behind by that split. TripResolutionService finds a
-- vehicle's current trip with
--     trips.bus_id = <this bus>  AND  now BETWEEN first and last arrival
-- so BUS2L — owning no trips — would never resolve one: no trip_id, no next
-- stop, no adherence, and a permanent NO_TRIP badge on the map, while BUS2
-- would hold two overlapping duties at once.
--
-- Moving the LINEA_2_LIC trips to the new bus fixes both ends of that.
-- ────────────────────────────────────────────────────────────────

UPDATE trips
SET    bus_id = (SELECT bus_id FROM buses WHERE current_vehicle_id = 'BUS2L')
WHERE  route_id = 'LINEA_2_LIC'
  -- No-op unless V11 actually created the bus (e.g. a database where the OBU
  -- remap was skipped), so this migration is safe to apply anywhere.
  AND  EXISTS (SELECT 1 FROM buses WHERE current_vehicle_id = 'BUS2L');
