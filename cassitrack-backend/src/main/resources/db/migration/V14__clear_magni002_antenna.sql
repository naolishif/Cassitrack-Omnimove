-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- V14__clear_magni002_antenna.sql
--
-- Clears the antenna id of the one bus that has no on-board unit.
--
-- WHY
-- ---
-- current_vehicle_id is the RADIO identity of the unit fitted to a bus: it is
-- the key telemetry arrives under, and MqttMessageHandler resolves a position
-- to a bus with
--     buses.current_vehicle_id = <the id in the payload>
--
-- V11 renamed the four buses that have a transmitting unit to BUS1, BUS2,
-- BUS2L and BUS3. This one kept its internal name 'MAGNI-002', which reads in
-- the Data Management table as though an antenna were fitted when none is —
-- no unit ever publishes that id, so the bus can never appear on the map.
-- An empty column says exactly that, and says it honestly.
--
-- WHY THE BUS ITSELF STAYS
-- ------------------------
-- It is a real, scheduled vehicle, not a leftover. A LINEA_1 round trip takes
-- 44 minutes and the line runs every 30, so ONE bus cannot work it: V5
-- alternates two, and this one holds 11 of the 23 daily departures (the
-- half-hourly ones). Deleting it would force those trips onto BUS1 — a 44
-- minute loop on a 30 minute headway, i.e. overlapping runs and ambiguous
-- trip resolution — or drop LINEA_1 to an hourly service.
--
-- So the bus is kept and only the misleading antenna id is removed. It stays
-- in the registry and in the timetable; it simply has no radio, and so is
-- absent from the live map. That is a true statement about the fleet.
--
-- Nothing else has to change: current_vehicle_id is nullable and UNIQUE
-- (NULLs do not collide in Postgres), and the trips keep pointing at bus_id,
-- which is untouched.
-- ────────────────────────────────────────────────────────────────

UPDATE buses
SET    current_vehicle_id = NULL
WHERE  current_vehicle_id = 'MAGNI-002';
