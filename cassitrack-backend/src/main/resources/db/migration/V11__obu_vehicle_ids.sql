-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- V11__obu_vehicle_ids.sql
--
-- Links our fleet to the on-board units that actually broadcast.
--
-- WHY
-- ---
-- MqttMessageHandler resolves an incoming position to a bus with
--     buses.current_vehicle_id = <the id in the payload>
-- The OBU units (and the simulator that stands in for them) publish
--     BUS1, BUS2, BUS2L, BUS3
-- while our registry carried the internal names MAGNI-001..004. Nothing
-- matched, so every position arrived with a null bus: no plate, no capacity,
-- no schedule adherence.
--
-- current_vehicle_id is the *radio identity* of the unit fitted to the bus —
-- it has to equal what the hardware transmits. The bus's own identity (plate,
-- seats, accessibility) is untouched, and the internal MAGNI-* naming survives
-- wherever it actually means something: the timetable migrations V3/V4/V5 look
-- buses up by current_vehicle_id, so this file deliberately runs AFTER them.
--
-- FIFTH BUS
-- ---------
-- On paper LINEA_2 and LINEA_2_LIC are worked by the same vehicle
-- (MAGNI-003 in V5). The OBU feed treats them as two independent units,
-- BUS2 and BUS2L, and current_vehicle_id is UNIQUE — one row cannot answer to
-- both. So the half-run gets its own bus. This mirrors reality: two units
-- transmitting means two vehicles on the road.
-- ────────────────────────────────────────────────────────────────

-- 1) A bus for the Liceo half-run. Mirrors MAGNI-003's characteristics
--    because it works the same line with the same rolling stock.
INSERT INTO buses (targa, numero_posti, wheelchair_accessible, disponibile,
                   current_vehicle_id, status, map_visible)
SELECT 'FR500LC',
       COALESCE((SELECT numero_posti FROM buses WHERE current_vehicle_id = 'MAGNI-003'), 52),
       COALESCE((SELECT wheelchair_accessible FROM buses WHERE current_vehicle_id = 'MAGNI-003'), FALSE),
       TRUE,
       'MAGNI-005',
       'ACTIVE',
       TRUE
WHERE NOT EXISTS (SELECT 1 FROM buses WHERE targa = 'FR500LC');

-- 2) Point each bus at the OBU id its unit transmits.
--    Written as one UPDATE ... FROM so the whole remap is atomic: with a
--    UNIQUE column, applying these one by one could collide mid-way.
UPDATE buses AS b
SET    current_vehicle_id = m.obu_id
FROM  (VALUES
          ('MAGNI-001', 'BUS1'),
          ('MAGNI-003', 'BUS2'),
          ('MAGNI-005', 'BUS2L'),
          ('MAGNI-004', 'BUS3')
      ) AS m(internal_id, obu_id)
WHERE b.current_vehicle_id = m.internal_id
  -- Re-runnable, and a no-op if someone already remapped by hand.
  AND NOT EXISTS (SELECT 1 FROM buses x WHERE x.current_vehicle_id = m.obu_id);

-- MAGNI-002 keeps its internal id on purpose: it is the second vehicle
-- alternating on LINEA_1 in V5, and the OBU feed has no unit for it. It stays
-- a perfectly valid bus in the registry, just one that never transmits.

-- 3) Assign each bus to the line it serves, so the Data Management table and
--    the map show a route instead of "Unassigned". route_id was added in V7.
UPDATE buses SET route_id = 'LINEA_1'     WHERE current_vehicle_id IN ('BUS1', 'MAGNI-002');
UPDATE buses SET route_id = 'LINEA_2'     WHERE current_vehicle_id = 'BUS2';
UPDATE buses SET route_id = 'LINEA_2_LIC' WHERE current_vehicle_id = 'BUS2L';
UPDATE buses SET route_id = 'LINEA_3'     WHERE current_vehicle_id = 'BUS3';
