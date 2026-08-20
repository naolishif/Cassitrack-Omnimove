-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- V17__magni_real_timetable.sql
--
-- Orario REALE di Autoservizi Luigi Magni e Figli S.r.l.,
-- da 'ORARIO IN VIGORE DAL 11 SETTEMBRE 2024' (www.magniturismo.it).
--
-- CONTENUTO
--   * 25 fermate nuove (le 20 gia' presenti sono riusate, non duplicate)
--   * 14 linee reali
--   * 125 corse con i loro orari
--   * 11 autobus nuovi (MAGNI-101..), quanti ne servono perche' nessun
--     mezzo debba fare due corse sovrapposte
--
-- SCELTE E LIMITI, dichiarati apertamente
--   1. Il PDF indica solo l'ORA DI PARTENZA. I tempi intermedi sono
--      calcolati sulla distanza reale fra le fermate a 24 km/h medi
--      piu' 45s di sosta: sono STIME, non orari ufficiali.
--   2. Il PDF distingue corse feriali / del sabato / prolungate
--      (*, **, °). La tabella trips non ha un calendario, quindi qui
--      TUTTE le corse valgono per ogni giorno.
--   3. Le linee preesistenti (LINEA_1/2/3) NON sono toccate: queste
--      corse usano bus nuovi e dedicati, cosi' nulla puo' collidere.
--      Se vuoi tenere solo l'orario reale, disattiva le vecchie linee
--      dal tab Data Management > Routes.
--   4. Nessuna geometria (route_shapes): le linee nuove appaiono
--      tratteggiate finche' non ne disegni il percorso sulla mappa.
--      Voluto: cosi' puoi correggere le fermate senza rompere nulla.
-- ────────────────────────────────────────────────────────────────

-- ============================================================
-- 1) FERMATE
--    'rilevata' = presa da mappa; 'INTERPOLATA' = calcolata fra due
--    fermate note del percorso e da rifinire se serve precisione.
-- ============================================================
INSERT INTO stops (id, name, lat, lon, description, active) VALUES
    ('KM135','Casilina Nord km 135',41.483315,13.797313,'rilevata',TRUE),
    ('SOLF','Solfegna',41.473532,13.797715,'rilevata',TRUE),
    ('SCES','San Cesareo',41.43022,13.898726,'rilevata',TRUE),
    ('ANDR','Andridonati',41.427128,13.853926,'rilevata',TRUE),
    ('ROCE','Incrocio Rocca d''Evandro',41.394053,13.898028,'rilevata',TRUE),
    ('SANG','Sant''Angelo in Theodice',41.4467,13.8314,'web: Mapcarta/Wikidata',TRUE),
    ('PANA','Panaccioni',41.432234,13.814132,'rilevata',TRUE),
    ('FILA','Filaro',41.44771,13.821406,'rilevata (anello)',TRUE),
    ('CERR','Cerro',41.472361,13.783508,'rilevata',TRUE),
    ('PCAV','Ponte a Cavallo',41.482498,13.784117,'rilevata',TRUE),
    ('CMOR','Cappella Morrone',41.490873,13.865842,'rilevata (da verificare: fonti danno ~5,1 km dal centro)',TRUE),
    ('CAPO','Capo d''Acqua',41.50756,13.862458,'rilevata',TRUE),
    ('CMON','Campo dei Monaci',41.475431,13.856854,'rilevata',TRUE),
    ('CCAN','Colle Canne',41.421427,13.857539,'rilevata',TRUE),
    ('CHIU','Chiusavecchia',41.506696,13.853539,'INTERPOLATA fra Ospedale e Capo d''Acqua',TRUE),
    ('VDIB','Via Di Biasio',41.48612,13.83036,'INTERPOLATA (EDN-COL)',TRUE),
    ('VLOM','Via Lombardia',41.494,13.830561,'INTERPOLATA (PSB-XXS)',TRUE),
    ('VAPP','Via Appia',41.4852,13.843773,'INTERPOLATA (CRS-CMON)',TRUE),
    ('VCAN','Via Casilina Nord',41.482372,13.812035,'INTERPOLATA (COL-KM135)',TRUE),
    ('VCAS','Via Casilina Sud',41.490667,13.851286,'INTERPOLATA (CRS-CMOR)',TRUE),
    ('FROS','Fontana Rosa',41.474627,13.841029,'INTERPOLATA (CRS-ANDR)',TRUE),
    ('VABR','Via Abruzzi',41.500326,13.836402,'INTERPOLATA (PSB-OSS)',TRUE),
    ('VDAN','Viale Dante',41.490065,13.830246,'INTERPOLATA (PSB-SFF)',TRUE),
    ('VSPA','Via San Pasquale',41.499027,13.841403,'INTERPOLATA (VLE-OSS)',TRUE),
    ('AGRA','Istituto Agrario',41.483673,13.806462,'INTERPOLATA (VBO-KM135)',TRUE)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 2) LINEE
-- ============================================================
INSERT INTO routes (id, short_name, long_name, description, color, text_color, active) VALUES
    ('LINEA_01','01','Solfegna - Casilina Nord','Autoservizi Magni - orario 11/09/2024','D32F2F','FFFFFF',TRUE),
    ('LINEA_02','02','San Cesareo - Rocca d''Evandro','Autoservizi Magni - orario 11/09/2024','7B1FA2','FFFFFF',TRUE),
    ('LINEA_03','03','Sant''Angelo - Panaccioni - Filaro','Autoservizi Magni - orario 11/09/2024','00796B','FFFFFF',TRUE),
    ('LINEA_04','04','Folcara','Autoservizi Magni - orario 11/09/2024','F57C00','FFFFFF',TRUE),
    ('LINEA_05','05','Cerro - Ponte a Cavallo','Autoservizi Magni - orario 11/09/2024','455A64','FFFFFF',TRUE),
    ('LINEA_07','07','Cappella Morrone','Autoservizi Magni - orario 11/09/2024','5D4037','FFFFFF',TRUE),
    ('LINEA_08','08','Campo dei Monaci','Autoservizi Magni - orario 11/09/2024','827717','FFFFFF',TRUE),
    ('LINEA_10','10','Ospedale - Capo d''Acqua','Autoservizi Magni - orario 11/09/2024','0288D1','FFFFFF',TRUE),
    ('LINEA_11L','11','Liceo Scientifico','Autoservizi Magni - orario 11/09/2024','C2185B','FFFFFF',TRUE),
    ('LINEA_11I','11','ITIS','Autoservizi Magni - orario 11/09/2024','AD1457','FFFFFF',TRUE),
    ('LINEA_14','14','Colle Canne','Autoservizi Magni - orario 11/09/2024','6A1B9A','FFFFFF',TRUE),
    ('LINEA_16','16','Universita Folcara','Autoservizi Magni - orario 11/09/2024','1976D2','FFFFFF',TRUE),
    ('LINEA_17','17','Ospedale','Autoservizi Magni - orario 11/09/2024','00838F','FFFFFF',TRUE),
    ('LINEA_AGR','AGR','Istituto Agrario','Autoservizi Magni - orario 11/09/2024','558B2F','FFFFFF',TRUE)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 3) AUTOBUS
--    bus_id e' IDENTITY: si inseriscono senza id e si ritrovano
--    tramite current_vehicle_id (l'antenna di bordo).
-- ============================================================
INSERT INTO buses (targa, numero_posti, wheelchair_accessible, disponibile, current_vehicle_id) VALUES
    ('FR200MG',52,TRUE,TRUE,'MAGNI-101'),
    ('FR201MG',60,FALSE,TRUE,'MAGNI-102'),
    ('FR202MG',85,TRUE,TRUE,'MAGNI-103'),
    ('FR203MG',52,FALSE,TRUE,'MAGNI-104'),
    ('FR204MG',60,TRUE,TRUE,'MAGNI-105'),
    ('FR205MG',85,FALSE,TRUE,'MAGNI-106'),
    ('FR206MG',52,TRUE,TRUE,'MAGNI-107'),
    ('FR207MG',60,FALSE,TRUE,'MAGNI-108'),
    ('FR208MG',85,TRUE,TRUE,'MAGNI-109'),
    ('FR209MG',52,FALSE,TRUE,'MAGNI-110'),
    ('FR210MG',60,TRUE,TRUE,'MAGNI-111')
ON CONFLICT (targa) DO NOTHING;

-- ============================================================
-- 4) CORSE E ORARI
-- ============================================================
DO $$
DECLARE
    b INT;
BEGIN

    -- ── LINEA_01: 15 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_23100','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_23100','PSB',1,23100), ('LINEA_01_23100','EDN',2,23256), ('LINEA_01_23100','VDIB',3,23409), ('LINEA_01_23100','COL',4,23562), ('LINEA_01_23100','SOLF',5,23962), ('LINEA_01_23100','VCAN',6,24238), ('LINEA_01_23100','KM135',7,24467) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_23400','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_23400','PSB',1,23400), ('LINEA_01_23400','EDN',2,23556), ('LINEA_01_23400','VDIB',3,23709), ('LINEA_01_23400','COL',4,23862), ('LINEA_01_23400','SOLF',5,24262), ('LINEA_01_23400','VCAN',6,24538), ('LINEA_01_23400','KM135',7,24767) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-105';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_24600','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_24600','PSB',1,24600), ('LINEA_01_24600','EDN',2,24756), ('LINEA_01_24600','VDIB',3,24909), ('LINEA_01_24600','COL',4,25062), ('LINEA_01_24600','SOLF',5,25462), ('LINEA_01_24600','VCAN',6,25738), ('LINEA_01_24600','KM135',7,25967) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_28800','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_28800','PSB',1,28800), ('LINEA_01_28800','EDN',2,28956), ('LINEA_01_28800','VDIB',3,29109), ('LINEA_01_28800','COL',4,29262), ('LINEA_01_28800','SOLF',5,29662), ('LINEA_01_28800','VCAN',6,29938), ('LINEA_01_28800','KM135',7,30167) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_33000','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_33000','PSB',1,33000), ('LINEA_01_33000','EDN',2,33156), ('LINEA_01_33000','VDIB',3,33309), ('LINEA_01_33000','COL',4,33462), ('LINEA_01_33000','SOLF',5,33862), ('LINEA_01_33000','VCAN',6,34138), ('LINEA_01_33000','KM135',7,34367) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_41400','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_41400','PSB',1,41400), ('LINEA_01_41400','EDN',2,41556), ('LINEA_01_41400','VDIB',3,41709), ('LINEA_01_41400','COL',4,41862), ('LINEA_01_41400','SOLF',5,42262), ('LINEA_01_41400','VCAN',6,42538), ('LINEA_01_41400','KM135',7,42767) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-105';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_49200','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_49200','PSB',1,49200), ('LINEA_01_49200','EDN',2,49356), ('LINEA_01_49200','VDIB',3,49509), ('LINEA_01_49200','COL',4,49662), ('LINEA_01_49200','SOLF',5,50062), ('LINEA_01_49200','VCAN',6,50338), ('LINEA_01_49200','KM135',7,50567) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_54300','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_54300','PSB',1,54300), ('LINEA_01_54300','EDN',2,54456), ('LINEA_01_54300','VDIB',3,54609), ('LINEA_01_54300','COL',4,54762), ('LINEA_01_54300','SOLF',5,55162), ('LINEA_01_54300','VCAN',6,55438), ('LINEA_01_54300','KM135',7,55667) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_55200','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_55200','PSB',1,55200), ('LINEA_01_55200','EDN',2,55356), ('LINEA_01_55200','VDIB',3,55509), ('LINEA_01_55200','COL',4,55662), ('LINEA_01_55200','SOLF',5,56062), ('LINEA_01_55200','VCAN',6,56338), ('LINEA_01_55200','KM135',7,56567) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_57600','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_57600','PSB',1,57600), ('LINEA_01_57600','EDN',2,57756), ('LINEA_01_57600','VDIB',3,57909), ('LINEA_01_57600','COL',4,58062), ('LINEA_01_57600','SOLF',5,58462), ('LINEA_01_57600','VCAN',6,58738), ('LINEA_01_57600','KM135',7,58967) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_61200','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_61200','PSB',1,61200), ('LINEA_01_61200','EDN',2,61356), ('LINEA_01_61200','VDIB',3,61509), ('LINEA_01_61200','COL',4,61662), ('LINEA_01_61200','SOLF',5,62062), ('LINEA_01_61200','VCAN',6,62338), ('LINEA_01_61200','KM135',7,62567) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_64800','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_64800','PSB',1,64800), ('LINEA_01_64800','EDN',2,64956), ('LINEA_01_64800','VDIB',3,65109), ('LINEA_01_64800','COL',4,65262), ('LINEA_01_64800','SOLF',5,65662), ('LINEA_01_64800','VCAN',6,65938), ('LINEA_01_64800','KM135',7,66167) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_68400','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_68400','PSB',1,68400), ('LINEA_01_68400','EDN',2,68556), ('LINEA_01_68400','VDIB',3,68709), ('LINEA_01_68400','COL',4,68862), ('LINEA_01_68400','SOLF',5,69262), ('LINEA_01_68400','VCAN',6,69538), ('LINEA_01_68400','KM135',7,69767) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_69600','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_69600','PSB',1,69600), ('LINEA_01_69600','EDN',2,69756), ('LINEA_01_69600','VDIB',3,69909), ('LINEA_01_69600','COL',4,70062), ('LINEA_01_69600','SOLF',5,70462), ('LINEA_01_69600','VCAN',6,70738), ('LINEA_01_69600','KM135',7,70967) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_01_72300','LINEA_01',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_01_72300','PSB',1,72300), ('LINEA_01_72300','EDN',2,72456), ('LINEA_01_72300','VDIB',3,72609), ('LINEA_01_72300','COL',4,72762), ('LINEA_01_72300','SOLF',5,73162), ('LINEA_01_72300','VCAN',6,73438), ('LINEA_01_72300','KM135',7,73667) ON CONFLICT DO NOTHING;

    -- ── LINEA_02: 5 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_02_24000','LINEA_02',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_02_24000','PSB',1,24000), ('LINEA_02_24000','CRS',2,24159), ('LINEA_02_24000','VAPP',3,24328), ('LINEA_02_24000','FROS',4,24552), ('LINEA_02_24000','ANDR',5,25405), ('LINEA_02_24000','SCES',6,26012), ('LINEA_02_24000','ROCE',7,26660) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_02_32400','LINEA_02',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_02_32400','PSB',1,32400), ('LINEA_02_32400','CRS',2,32559), ('LINEA_02_32400','VAPP',3,32728), ('LINEA_02_32400','FROS',4,32952), ('LINEA_02_32400','ANDR',5,33805), ('LINEA_02_32400','SCES',6,34412), ('LINEA_02_32400','ROCE',7,35060) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_02_48600','LINEA_02',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_02_48600','PSB',1,48600), ('LINEA_02_48600','CRS',2,48759), ('LINEA_02_48600','VAPP',3,48928), ('LINEA_02_48600','FROS',4,49152), ('LINEA_02_48600','ANDR',5,50005), ('LINEA_02_48600','SCES',6,50612), ('LINEA_02_48600','ROCE',7,51260) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_02_53100','LINEA_02',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_02_53100','PSB',1,53100), ('LINEA_02_53100','CRS',2,53259), ('LINEA_02_53100','VAPP',3,53428), ('LINEA_02_53100','FROS',4,53652), ('LINEA_02_53100','ANDR',5,54505), ('LINEA_02_53100','SCES',6,55112), ('LINEA_02_53100','ROCE',7,55760) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_02_67200','LINEA_02',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_02_67200','PSB',1,67200), ('LINEA_02_67200','CRS',2,67359), ('LINEA_02_67200','VAPP',3,67528), ('LINEA_02_67200','FROS',4,67752), ('LINEA_02_67200','ANDR',5,68605), ('LINEA_02_67200','SCES',6,69212), ('LINEA_02_67200','ROCE',7,69860) ON CONFLICT DO NOTHING;

    -- ── LINEA_03: 6 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_03_25200','LINEA_03',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_03_25200','PSB',1,25200), ('LINEA_03_25200','VLOM',2,25267), ('LINEA_03_25200','XXS',3,25326), ('LINEA_03_25200','EDN',4,25455), ('LINEA_03_25200','VDIB',5,25608), ('LINEA_03_25200','SANG',6,26310), ('LINEA_03_25200','PANA',7,26678), ('LINEA_03_25200','FILA',8,26996), ('LINEA_03_25200','PSB',9,27815) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_03_39000','LINEA_03',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_03_39000','PSB',1,39000), ('LINEA_03_39000','VLOM',2,39067), ('LINEA_03_39000','XXS',3,39126), ('LINEA_03_39000','EDN',4,39255), ('LINEA_03_39000','VDIB',5,39408), ('LINEA_03_39000','SANG',6,40110), ('LINEA_03_39000','PANA',7,40478), ('LINEA_03_39000','FILA',8,40796), ('LINEA_03_39000','PSB',9,41615) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_03_45000','LINEA_03',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_03_45000','PSB',1,45000), ('LINEA_03_45000','VLOM',2,45067), ('LINEA_03_45000','XXS',3,45126), ('LINEA_03_45000','EDN',4,45255), ('LINEA_03_45000','VDIB',5,45408), ('LINEA_03_45000','SANG',6,46110), ('LINEA_03_45000','PANA',7,46478), ('LINEA_03_45000','FILA',8,46796), ('LINEA_03_45000','PSB',9,47615) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_03_50400','LINEA_03',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_03_50400','PSB',1,50400), ('LINEA_03_50400','VLOM',2,50467), ('LINEA_03_50400','XXS',3,50526), ('LINEA_03_50400','EDN',4,50655), ('LINEA_03_50400','VDIB',5,50808), ('LINEA_03_50400','SANG',6,51510), ('LINEA_03_50400','PANA',7,51878), ('LINEA_03_50400','FILA',8,52196), ('LINEA_03_50400','PSB',9,53015) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-105';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_03_53100','LINEA_03',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_03_53100','PSB',1,53100), ('LINEA_03_53100','VLOM',2,53167), ('LINEA_03_53100','XXS',3,53226), ('LINEA_03_53100','EDN',4,53355), ('LINEA_03_53100','VDIB',5,53508), ('LINEA_03_53100','SANG',6,54210), ('LINEA_03_53100','PANA',7,54578), ('LINEA_03_53100','FILA',8,54896), ('LINEA_03_53100','PSB',9,55715) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_03_63000','LINEA_03',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_03_63000','PSB',1,63000), ('LINEA_03_63000','VLOM',2,63067), ('LINEA_03_63000','XXS',3,63126), ('LINEA_03_63000','EDN',4,63255), ('LINEA_03_63000','VDIB',5,63408), ('LINEA_03_63000','SANG',6,64110), ('LINEA_03_63000','PANA',7,64478), ('LINEA_03_63000','FILA',8,64796), ('LINEA_03_63000','PSB',9,65615) ON CONFLICT DO NOTHING;

    -- ── LINEA_04: 6 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_04_25200','LINEA_04',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_04_25200','PSB',1,25200), ('LINEA_04_25200','VLOM',2,25267), ('LINEA_04_25200','XXS',3,25326), ('LINEA_04_25200','EDN',4,25455), ('LINEA_04_25200','VDIB',5,25608), ('LINEA_04_25200','COL',6,25761), ('LINEA_04_25200','UNI',7,25919) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_04_27600','LINEA_04',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_04_27600','PSB',1,27600), ('LINEA_04_27600','VLOM',2,27667), ('LINEA_04_27600','XXS',3,27726), ('LINEA_04_27600','EDN',4,27855), ('LINEA_04_27600','VDIB',5,28008), ('LINEA_04_27600','COL',6,28161), ('LINEA_04_27600','UNI',7,28319) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_04_36900','LINEA_04',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_04_36900','PSB',1,36900), ('LINEA_04_36900','VLOM',2,36967), ('LINEA_04_36900','XXS',3,37026), ('LINEA_04_36900','EDN',4,37155), ('LINEA_04_36900','VDIB',5,37308), ('LINEA_04_36900','COL',6,37461), ('LINEA_04_36900','UNI',7,37619) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_04_48600','LINEA_04',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_04_48600','PSB',1,48600), ('LINEA_04_48600','VLOM',2,48667), ('LINEA_04_48600','XXS',3,48726), ('LINEA_04_48600','EDN',4,48855), ('LINEA_04_48600','VDIB',5,49008), ('LINEA_04_48600','COL',6,49161), ('LINEA_04_48600','UNI',7,49319) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-106';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_04_53100','LINEA_04',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_04_53100','PSB',1,53100), ('LINEA_04_53100','VLOM',2,53167), ('LINEA_04_53100','XXS',3,53226), ('LINEA_04_53100','EDN',4,53355), ('LINEA_04_53100','VDIB',5,53508), ('LINEA_04_53100','COL',6,53661), ('LINEA_04_53100','UNI',7,53819) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_04_60600','LINEA_04',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_04_60600','PSB',1,60600), ('LINEA_04_60600','VLOM',2,60667), ('LINEA_04_60600','XXS',3,60726), ('LINEA_04_60600','EDN',4,60855), ('LINEA_04_60600','VDIB',5,61008), ('LINEA_04_60600','COL',6,61161), ('LINEA_04_60600','UNI',7,61319) ON CONFLICT DO NOTHING;

    -- ── LINEA_05: 6 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-106';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_05_25200','LINEA_05',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_05_25200','PSB',1,25200), ('LINEA_05_25200','VLOM',2,25267), ('LINEA_05_25200','XXS',3,25326), ('LINEA_05_25200','EDN',4,25455), ('LINEA_05_25200','VDIB',5,25608), ('LINEA_05_25200','COL',6,25761), ('LINEA_05_25200','CERR',7,26335), ('LINEA_05_25200','PCAV',8,26549) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_05_36000','LINEA_05',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_05_36000','PSB',1,36000), ('LINEA_05_36000','VLOM',2,36067), ('LINEA_05_36000','XXS',3,36126), ('LINEA_05_36000','EDN',4,36255), ('LINEA_05_36000','VDIB',5,36408), ('LINEA_05_36000','COL',6,36561), ('LINEA_05_36000','CERR',7,37135), ('LINEA_05_36000','PCAV',8,37349) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_05_43200','LINEA_05',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_05_43200','PSB',1,43200), ('LINEA_05_43200','VLOM',2,43267), ('LINEA_05_43200','XXS',3,43326), ('LINEA_05_43200','EDN',4,43455), ('LINEA_05_43200','VDIB',5,43608), ('LINEA_05_43200','COL',6,43761), ('LINEA_05_43200','CERR',7,44335), ('LINEA_05_43200','PCAV',8,44549) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-105';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_05_47400','LINEA_05',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_05_47400','PSB',1,47400), ('LINEA_05_47400','VLOM',2,47467), ('LINEA_05_47400','XXS',3,47526), ('LINEA_05_47400','EDN',4,47655), ('LINEA_05_47400','VDIB',5,47808), ('LINEA_05_47400','COL',6,47961), ('LINEA_05_47400','CERR',7,48535), ('LINEA_05_47400','PCAV',8,48749) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_05_48300','LINEA_05',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_05_48300','PSB',1,48300), ('LINEA_05_48300','VLOM',2,48367), ('LINEA_05_48300','XXS',3,48426), ('LINEA_05_48300','EDN',4,48555), ('LINEA_05_48300','VDIB',5,48708), ('LINEA_05_48300','COL',6,48861), ('LINEA_05_48300','CERR',7,49435), ('LINEA_05_48300','PCAV',8,49649) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-107';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_05_53100','LINEA_05',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_05_53100','PSB',1,53100), ('LINEA_05_53100','VLOM',2,53167), ('LINEA_05_53100','XXS',3,53226), ('LINEA_05_53100','EDN',4,53355), ('LINEA_05_53100','VDIB',5,53508), ('LINEA_05_53100','COL',6,53661), ('LINEA_05_53100','CERR',7,54235), ('LINEA_05_53100','PCAV',8,54449) ON CONFLICT DO NOTHING;

    -- ── LINEA_07: 8 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_07_26400','LINEA_07',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_07_26400','PSB',1,26400), ('LINEA_07_26400','CRS',2,26559), ('LINEA_07_26400','VCAS',3,26785), ('LINEA_07_26400','CMOR',4,27011) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_07_27600','LINEA_07',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_07_27600','PSB',1,27600), ('LINEA_07_27600','CRS',2,27759), ('LINEA_07_27600','VCAS',3,27985), ('LINEA_07_27600','CMOR',4,28211) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_07_30600','LINEA_07',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_07_30600','PSB',1,30600), ('LINEA_07_30600','CRS',2,30759), ('LINEA_07_30600','VCAS',3,30985), ('LINEA_07_30600','CMOR',4,31211) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_07_34800','LINEA_07',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_07_34800','PSB',1,34800), ('LINEA_07_34800','CRS',2,34959), ('LINEA_07_34800','VCAS',3,35185), ('LINEA_07_34800','CMOR',4,35411) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_07_41400','LINEA_07',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_07_41400','PSB',1,41400), ('LINEA_07_41400','CRS',2,41559), ('LINEA_07_41400','VCAS',3,41785), ('LINEA_07_41400','CMOR',4,42011) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-106';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_07_49200','LINEA_07',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_07_49200','PSB',1,49200), ('LINEA_07_49200','CRS',2,49359), ('LINEA_07_49200','VCAS',3,49585), ('LINEA_07_49200','CMOR',4,49811) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-108';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_07_53100','LINEA_07',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_07_53100','PSB',1,53100), ('LINEA_07_53100','CRS',2,53259), ('LINEA_07_53100','VCAS',3,53485), ('LINEA_07_53100','CMOR',4,53711) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_07_66000','LINEA_07',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_07_66000','PSB',1,66000), ('LINEA_07_66000','CRS',2,66159), ('LINEA_07_66000','VCAS',3,66385), ('LINEA_07_66000','CMOR',4,66611) ON CONFLICT DO NOTHING;

    -- ── LINEA_08: 2 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-105';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_08_26400','LINEA_08',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_08_26400','PSB',1,26400), ('LINEA_08_26400','CRS',2,26559), ('LINEA_08_26400','VAPP',3,26728), ('LINEA_08_26400','CMON',4,27003) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-109';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_08_53100','LINEA_08',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_08_53100','PSB',1,53100), ('LINEA_08_53100','CRS',2,53259), ('LINEA_08_53100','VAPP',3,53428), ('LINEA_08_53100','CMON',4,53703) ON CONFLICT DO NOTHING;

    -- ── LINEA_10: 8 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-107';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_10_25500','LINEA_10',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_10_25500','PSB',1,25500), ('LINEA_10_25500','VLOM',2,25567), ('LINEA_10_25500','VABR',3,25740), ('LINEA_10_25500','OSS',4,25902), ('LINEA_10_25500','CHIU',5,26084), ('LINEA_10_25500','CAPO',6,26241) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_10_40200','LINEA_10',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_10_40200','PSB',1,40200), ('LINEA_10_40200','VLOM',2,40267), ('LINEA_10_40200','VABR',3,40440), ('LINEA_10_40200','OSS',4,40602), ('LINEA_10_40200','CHIU',5,40784), ('LINEA_10_40200','CAPO',6,40941) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-106';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_10_47700','LINEA_10',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_10_47700','PSB',1,47700), ('LINEA_10_47700','VLOM',2,47767), ('LINEA_10_47700','VABR',3,47940), ('LINEA_10_47700','OSS',4,48102), ('LINEA_10_47700','CHIU',5,48284), ('LINEA_10_47700','CAPO',6,48441) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_10_50700','LINEA_10',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_10_50700','PSB',1,50700), ('LINEA_10_50700','VLOM',2,50767), ('LINEA_10_50700','VABR',3,50940), ('LINEA_10_50700','OSS',4,51102), ('LINEA_10_50700','CHIU',5,51284), ('LINEA_10_50700','CAPO',6,51441) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-110';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_10_53100','LINEA_10',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_10_53100','PSB',1,53100), ('LINEA_10_53100','VLOM',2,53167), ('LINEA_10_53100','VABR',3,53340), ('LINEA_10_53100','OSS',4,53502), ('LINEA_10_53100','CHIU',5,53684), ('LINEA_10_53100','CAPO',6,53841) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_10_55800','LINEA_10',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_10_55800','PSB',1,55800), ('LINEA_10_55800','VLOM',2,55867), ('LINEA_10_55800','VABR',3,56040), ('LINEA_10_55800','OSS',4,56202), ('LINEA_10_55800','CHIU',5,56384), ('LINEA_10_55800','CAPO',6,56541) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_10_63000','LINEA_10',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_10_63000','PSB',1,63000), ('LINEA_10_63000','VLOM',2,63067), ('LINEA_10_63000','VABR',3,63240), ('LINEA_10_63000','OSS',4,63402), ('LINEA_10_63000','CHIU',5,63584), ('LINEA_10_63000','CAPO',6,63741) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_10_70200','LINEA_10',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_10_70200','PSB',1,70200), ('LINEA_10_70200','VLOM',2,70267), ('LINEA_10_70200','VABR',3,70440), ('LINEA_10_70200','OSS',4,70602), ('LINEA_10_70200','CHIU',5,70784), ('LINEA_10_70200','CAPO',6,70941) ON CONFLICT DO NOTHING;

    -- ── LINEA_11I: 10 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-105';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11I_27600','LINEA_11I',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11I_27600','PSB',1,27600), ('LINEA_11I_27600','VLE',2,27800), ('LINEA_11I_27600','VGA',3,27922), ('LINEA_11I_27600','SFF',4,28005), ('LINEA_11I_27600','UNI',5,28215), ('LINEA_11I_27600','VSA',6,28373) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-107';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11I_28200','LINEA_11I',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11I_28200','PSB',1,28200), ('LINEA_11I_28200','VLE',2,28400), ('LINEA_11I_28200','VGA',3,28522), ('LINEA_11I_28200','SFF',4,28605), ('LINEA_11I_28200','UNI',5,28815), ('LINEA_11I_28200','VSA',6,28973) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11I_29100','LINEA_11I',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11I_29100','PSB',1,29100), ('LINEA_11I_29100','VLE',2,29300), ('LINEA_11I_29100','VGA',3,29422), ('LINEA_11I_29100','SFF',4,29505), ('LINEA_11I_29100','UNI',5,29715), ('LINEA_11I_29100','VSA',6,29873) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-108';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11I_29400','LINEA_11I',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11I_29400','PSB',1,29400), ('LINEA_11I_29400','VLE',2,29600), ('LINEA_11I_29400','VGA',3,29722), ('LINEA_11I_29400','SFF',4,29805), ('LINEA_11I_29400','UNI',5,30015), ('LINEA_11I_29400','VSA',6,30173) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11I_30900','LINEA_11I',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11I_30900','PSB',1,30900), ('LINEA_11I_30900','VLE',2,31100), ('LINEA_11I_30900','VGA',3,31222), ('LINEA_11I_30900','SFF',4,31305), ('LINEA_11I_30900','UNI',5,31515), ('LINEA_11I_30900','VSA',6,31673) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-105';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11I_33000','LINEA_11I',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11I_33000','PSB',1,33000), ('LINEA_11I_33000','VLE',2,33200), ('LINEA_11I_33000','VGA',3,33322), ('LINEA_11I_33000','SFF',4,33405), ('LINEA_11I_33000','UNI',5,33615), ('LINEA_11I_33000','VSA',6,33773) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-105';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11I_34500','LINEA_11I',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11I_34500','PSB',1,34500), ('LINEA_11I_34500','VLE',2,34700), ('LINEA_11I_34500','VGA',3,34822), ('LINEA_11I_34500','SFF',4,34905), ('LINEA_11I_34500','UNI',5,35115), ('LINEA_11I_34500','VSA',6,35273) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11I_36600','LINEA_11I',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11I_36600','PSB',1,36600), ('LINEA_11I_36600','VLE',2,36800), ('LINEA_11I_36600','VGA',3,36922), ('LINEA_11I_36600','SFF',4,37005), ('LINEA_11I_36600','UNI',5,37215), ('LINEA_11I_36600','VSA',6,37373) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11I_38100','LINEA_11I',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11I_38100','PSB',1,38100), ('LINEA_11I_38100','VLE',2,38300), ('LINEA_11I_38100','VGA',3,38422), ('LINEA_11I_38100','SFF',4,38505), ('LINEA_11I_38100','UNI',5,38715), ('LINEA_11I_38100','VSA',6,38873) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-105';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11I_51300','LINEA_11I',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11I_51300','PSB',1,51300), ('LINEA_11I_51300','VLE',2,51500), ('LINEA_11I_51300','VGA',3,51622), ('LINEA_11I_51300','SFF',4,51705), ('LINEA_11I_51300','UNI',5,51915), ('LINEA_11I_51300','VSA',6,52073) ON CONFLICT DO NOTHING;

    -- ── LINEA_11L: 12 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11L_27600','LINEA_11L',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11L_27600','PSB',1,27600), ('LINEA_11L_27600','VLE',2,27800), ('LINEA_11L_27600','VGA',3,27922), ('LINEA_11L_27600','SFF',4,28005), ('LINEA_11L_27600','UNI',5,28215), ('LINEA_11L_27600','VSA',6,28373), ('LINEA_11L_27600','LIC',7,28667) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11L_28200','LINEA_11L',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11L_28200','PSB',1,28200), ('LINEA_11L_28200','VLE',2,28400), ('LINEA_11L_28200','VGA',3,28522), ('LINEA_11L_28200','SFF',4,28605), ('LINEA_11L_28200','UNI',5,28815), ('LINEA_11L_28200','VSA',6,28973), ('LINEA_11L_28200','LIC',7,29267) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11L_28800','LINEA_11L',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11L_28800','PSB',1,28800), ('LINEA_11L_28800','VLE',2,29000), ('LINEA_11L_28800','VGA',3,29122), ('LINEA_11L_28800','SFF',4,29205), ('LINEA_11L_28800','UNI',5,29415), ('LINEA_11L_28800','VSA',6,29573), ('LINEA_11L_28800','LIC',7,29867) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-107';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11L_29400','LINEA_11L',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11L_29400','PSB',1,29400), ('LINEA_11L_29400','VLE',2,29600), ('LINEA_11L_29400','VGA',3,29722), ('LINEA_11L_29400','SFF',4,29805), ('LINEA_11L_29400','UNI',5,30015), ('LINEA_11L_29400','VSA',6,30173), ('LINEA_11L_29400','LIC',7,30467) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-105';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11L_30000','LINEA_11L',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11L_30000','PSB',1,30000), ('LINEA_11L_30000','VLE',2,30200), ('LINEA_11L_30000','VGA',3,30322), ('LINEA_11L_30000','SFF',4,30405), ('LINEA_11L_30000','UNI',5,30615), ('LINEA_11L_30000','VSA',6,30773), ('LINEA_11L_30000','LIC',7,31067) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11L_30900','LINEA_11L',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11L_30900','PSB',1,30900), ('LINEA_11L_30900','VLE',2,31100), ('LINEA_11L_30900','VGA',3,31222), ('LINEA_11L_30900','SFF',4,31305), ('LINEA_11L_30900','UNI',5,31515), ('LINEA_11L_30900','VSA',6,31673), ('LINEA_11L_30900','LIC',7,31967) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11L_33000','LINEA_11L',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11L_33000','PSB',1,33000), ('LINEA_11L_33000','VLE',2,33200), ('LINEA_11L_33000','VGA',3,33322), ('LINEA_11L_33000','SFF',4,33405), ('LINEA_11L_33000','UNI',5,33615), ('LINEA_11L_33000','VSA',6,33773), ('LINEA_11L_33000','LIC',7,34067) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11L_34500','LINEA_11L',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11L_34500','PSB',1,34500), ('LINEA_11L_34500','VLE',2,34700), ('LINEA_11L_34500','VGA',3,34822), ('LINEA_11L_34500','SFF',4,34905), ('LINEA_11L_34500','UNI',5,35115), ('LINEA_11L_34500','VSA',6,35273), ('LINEA_11L_34500','LIC',7,35567) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11L_36600','LINEA_11L',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11L_36600','PSB',1,36600), ('LINEA_11L_36600','VLE',2,36800), ('LINEA_11L_36600','VGA',3,36922), ('LINEA_11L_36600','SFF',4,37005), ('LINEA_11L_36600','UNI',5,37215), ('LINEA_11L_36600','VSA',6,37373), ('LINEA_11L_36600','LIC',7,37667) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11L_38100','LINEA_11L',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11L_38100','PSB',1,38100), ('LINEA_11L_38100','VLE',2,38300), ('LINEA_11L_38100','VGA',3,38422), ('LINEA_11L_38100','SFF',4,38505), ('LINEA_11L_38100','UNI',5,38715), ('LINEA_11L_38100','VSA',6,38873), ('LINEA_11L_38100','LIC',7,39167) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11L_46800','LINEA_11L',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11L_46800','PSB',1,46800), ('LINEA_11L_46800','VLE',2,47000), ('LINEA_11L_46800','VGA',3,47122), ('LINEA_11L_46800','SFF',4,47205), ('LINEA_11L_46800','UNI',5,47415), ('LINEA_11L_46800','VSA',6,47573), ('LINEA_11L_46800','LIC',7,47867) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-108';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_11L_49500','LINEA_11L',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_11L_49500','PSB',1,49500), ('LINEA_11L_49500','VLE',2,49700), ('LINEA_11L_49500','VGA',3,49822), ('LINEA_11L_49500','SFF',4,49905), ('LINEA_11L_49500','UNI',5,50115), ('LINEA_11L_49500','VSA',6,50273), ('LINEA_11L_49500','LIC',7,50567) ON CONFLICT DO NOTHING;

    -- ── LINEA_14: 2 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_14_23700','LINEA_14',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_14_23700','PSB',1,23700), ('LINEA_14_23700','FROS',2,24100), ('LINEA_14_23700','CCAN',3,25056), ('LINEA_14_23700','SANG',4,25634), ('LINEA_14_23700','PSB',5,26465) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-111';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_14_53100','LINEA_14',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_14_53100','PSB',1,53100), ('LINEA_14_53100','FROS',2,53500), ('LINEA_14_53100','CCAN',3,54456), ('LINEA_14_53100','SANG',4,55034), ('LINEA_14_53100','PSB',5,55865) ON CONFLICT DO NOTHING;

    -- ── LINEA_16: 26 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-106';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_27600','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_27600','PSB',1,27600), ('LINEA_16_27600','CRS',2,27759), ('LINEA_16_27600','VLE',3,27845), ('LINEA_16_27600','VGA',4,27967), ('LINEA_16_27600','SFF',5,28050), ('LINEA_16_27600','VBO',6,28150), ('LINEA_16_27600','UNI',7,28340), ('LINEA_16_27600','RET',8,28454) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-108';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_28200','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_28200','PSB',1,28200), ('LINEA_16_28200','CRS',2,28359), ('LINEA_16_28200','VLE',3,28445), ('LINEA_16_28200','VGA',4,28567), ('LINEA_16_28200','SFF',5,28650), ('LINEA_16_28200','VBO',6,28750), ('LINEA_16_28200','UNI',7,28940), ('LINEA_16_28200','RET',8,29054) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-105';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_28800','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_28800','PSB',1,28800), ('LINEA_16_28800','CRS',2,28959), ('LINEA_16_28800','VLE',3,29045), ('LINEA_16_28800','VGA',4,29167), ('LINEA_16_28800','SFF',5,29250), ('LINEA_16_28800','VBO',6,29350), ('LINEA_16_28800','UNI',7,29540), ('LINEA_16_28800','RET',8,29654) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-110';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_29400','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_29400','PSB',1,29400), ('LINEA_16_29400','CRS',2,29559), ('LINEA_16_29400','VLE',3,29645), ('LINEA_16_29400','VGA',4,29767), ('LINEA_16_29400','SFF',5,29850), ('LINEA_16_29400','VBO',6,29950), ('LINEA_16_29400','UNI',7,30140), ('LINEA_16_29400','RET',8,30254) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-106';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_30900','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_30900','PSB',1,30900), ('LINEA_16_30900','CRS',2,31059), ('LINEA_16_30900','VLE',3,31145), ('LINEA_16_30900','VGA',4,31267), ('LINEA_16_30900','SFF',5,31350), ('LINEA_16_30900','VBO',6,31450), ('LINEA_16_30900','UNI',7,31640), ('LINEA_16_30900','RET',8,31754) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-106';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_33000','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_33000','PSB',1,33000), ('LINEA_16_33000','CRS',2,33159), ('LINEA_16_33000','VLE',3,33245), ('LINEA_16_33000','VGA',4,33367), ('LINEA_16_33000','SFF',5,33450), ('LINEA_16_33000','VBO',6,33550), ('LINEA_16_33000','UNI',7,33740), ('LINEA_16_33000','RET',8,33854) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-106';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_34800','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_34800','PSB',1,34800), ('LINEA_16_34800','CRS',2,34959), ('LINEA_16_34800','VLE',3,35045), ('LINEA_16_34800','VGA',4,35167), ('LINEA_16_34800','SFF',5,35250), ('LINEA_16_34800','VBO',6,35350), ('LINEA_16_34800','UNI',7,35540), ('LINEA_16_34800','RET',8,35654) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-105';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_36600','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_36600','PSB',1,36600), ('LINEA_16_36600','CRS',2,36759), ('LINEA_16_36600','VLE',3,36845), ('LINEA_16_36600','VGA',4,36967), ('LINEA_16_36600','SFF',5,37050), ('LINEA_16_36600','VBO',6,37150), ('LINEA_16_36600','UNI',7,37340), ('LINEA_16_36600','RET',8,37454) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_38100','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_38100','PSB',1,38100), ('LINEA_16_38100','CRS',2,38259), ('LINEA_16_38100','VLE',3,38345), ('LINEA_16_38100','VGA',4,38467), ('LINEA_16_38100','SFF',5,38550), ('LINEA_16_38100','VBO',6,38650), ('LINEA_16_38100','UNI',7,38840), ('LINEA_16_38100','RET',8,38954) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_39600','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_39600','PSB',1,39600), ('LINEA_16_39600','CRS',2,39759), ('LINEA_16_39600','VLE',3,39845), ('LINEA_16_39600','VGA',4,39967), ('LINEA_16_39600','SFF',5,40050), ('LINEA_16_39600','VBO',6,40150), ('LINEA_16_39600','UNI',7,40340), ('LINEA_16_39600','RET',8,40454) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_41400','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_41400','PSB',1,41400), ('LINEA_16_41400','CRS',2,41559), ('LINEA_16_41400','VLE',3,41645), ('LINEA_16_41400','VGA',4,41767), ('LINEA_16_41400','SFF',5,41850), ('LINEA_16_41400','VBO',6,41950), ('LINEA_16_41400','UNI',7,42140), ('LINEA_16_41400','RET',8,42254) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_43200','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_43200','PSB',1,43200), ('LINEA_16_43200','CRS',2,43359), ('LINEA_16_43200','VLE',3,43445), ('LINEA_16_43200','VGA',4,43567), ('LINEA_16_43200','SFF',5,43650), ('LINEA_16_43200','VBO',6,43750), ('LINEA_16_43200','UNI',7,43940), ('LINEA_16_43200','RET',8,44054) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_45000','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_45000','PSB',1,45000), ('LINEA_16_45000','CRS',2,45159), ('LINEA_16_45000','VLE',3,45245), ('LINEA_16_45000','VGA',4,45367), ('LINEA_16_45000','SFF',5,45450), ('LINEA_16_45000','VBO',6,45550), ('LINEA_16_45000','UNI',7,45740), ('LINEA_16_45000','RET',8,45854) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_46800','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_46800','PSB',1,46800), ('LINEA_16_46800','CRS',2,46959), ('LINEA_16_46800','VLE',3,47045), ('LINEA_16_46800','VGA',4,47167), ('LINEA_16_46800','SFF',5,47250), ('LINEA_16_46800','VBO',6,47350), ('LINEA_16_46800','UNI',7,47540), ('LINEA_16_46800','RET',8,47654) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-107';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_48600','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_48600','PSB',1,48600), ('LINEA_16_48600','CRS',2,48759), ('LINEA_16_48600','VLE',3,48845), ('LINEA_16_48600','VGA',4,48967), ('LINEA_16_48600','SFF',5,49050), ('LINEA_16_48600','VBO',6,49150), ('LINEA_16_48600','UNI',7,49340), ('LINEA_16_48600','RET',8,49454) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_50400','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_50400','PSB',1,50400), ('LINEA_16_50400','CRS',2,50559), ('LINEA_16_50400','VLE',3,50645), ('LINEA_16_50400','VGA',4,50767), ('LINEA_16_50400','SFF',5,50850), ('LINEA_16_50400','VBO',6,50950), ('LINEA_16_50400','UNI',7,51140), ('LINEA_16_50400','RET',8,51254) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_52200','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_52200','PSB',1,52200), ('LINEA_16_52200','CRS',2,52359), ('LINEA_16_52200','VLE',3,52445), ('LINEA_16_52200','VGA',4,52567), ('LINEA_16_52200','SFF',5,52650), ('LINEA_16_52200','VBO',6,52750), ('LINEA_16_52200','UNI',7,52940), ('LINEA_16_52200','RET',8,53054) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_54000','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_54000','PSB',1,54000), ('LINEA_16_54000','CRS',2,54159), ('LINEA_16_54000','VLE',3,54245), ('LINEA_16_54000','VGA',4,54367), ('LINEA_16_54000','SFF',5,54450), ('LINEA_16_54000','VBO',6,54550), ('LINEA_16_54000','UNI',7,54740), ('LINEA_16_54000','RET',8,54854) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-106';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_55800','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_55800','PSB',1,55800), ('LINEA_16_55800','CRS',2,55959), ('LINEA_16_55800','VLE',3,56045), ('LINEA_16_55800','VGA',4,56167), ('LINEA_16_55800','SFF',5,56250), ('LINEA_16_55800','VBO',6,56350), ('LINEA_16_55800','UNI',7,56540), ('LINEA_16_55800','RET',8,56654) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_57600','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_57600','PSB',1,57600), ('LINEA_16_57600','CRS',2,57759), ('LINEA_16_57600','VLE',3,57845), ('LINEA_16_57600','VGA',4,57967), ('LINEA_16_57600','SFF',5,58050), ('LINEA_16_57600','VBO',6,58150), ('LINEA_16_57600','UNI',7,58340), ('LINEA_16_57600','RET',8,58454) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_59400','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_59400','PSB',1,59400), ('LINEA_16_59400','CRS',2,59559), ('LINEA_16_59400','VLE',3,59645), ('LINEA_16_59400','VGA',4,59767), ('LINEA_16_59400','SFF',5,59850), ('LINEA_16_59400','VBO',6,59950), ('LINEA_16_59400','UNI',7,60140), ('LINEA_16_59400','RET',8,60254) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_61200','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_61200','PSB',1,61200), ('LINEA_16_61200','CRS',2,61359), ('LINEA_16_61200','VLE',3,61445), ('LINEA_16_61200','VGA',4,61567), ('LINEA_16_61200','SFF',5,61650), ('LINEA_16_61200','VBO',6,61750), ('LINEA_16_61200','UNI',7,61940), ('LINEA_16_61200','RET',8,62054) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_63000','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_63000','PSB',1,63000), ('LINEA_16_63000','CRS',2,63159), ('LINEA_16_63000','VLE',3,63245), ('LINEA_16_63000','VGA',4,63367), ('LINEA_16_63000','SFF',5,63450), ('LINEA_16_63000','VBO',6,63550), ('LINEA_16_63000','UNI',7,63740), ('LINEA_16_63000','RET',8,63854) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_64800','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_64800','PSB',1,64800), ('LINEA_16_64800','CRS',2,64959), ('LINEA_16_64800','VLE',3,65045), ('LINEA_16_64800','VGA',4,65167), ('LINEA_16_64800','SFF',5,65250), ('LINEA_16_64800','VBO',6,65350), ('LINEA_16_64800','UNI',7,65540), ('LINEA_16_64800','RET',8,65654) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_66600','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_66600','PSB',1,66600), ('LINEA_16_66600','CRS',2,66759), ('LINEA_16_66600','VLE',3,66845), ('LINEA_16_66600','VGA',4,66967), ('LINEA_16_66600','SFF',5,67050), ('LINEA_16_66600','VBO',6,67150), ('LINEA_16_66600','UNI',7,67340), ('LINEA_16_66600','RET',8,67454) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_16_68400','LINEA_16',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_16_68400','PSB',1,68400), ('LINEA_16_68400','CRS',2,68559), ('LINEA_16_68400','VLE',3,68645), ('LINEA_16_68400','VGA',4,68767), ('LINEA_16_68400','SFF',5,68850), ('LINEA_16_68400','VBO',6,68950), ('LINEA_16_68400','UNI',7,69140), ('LINEA_16_68400','RET',8,69254) ON CONFLICT DO NOTHING;

    -- ── LINEA_17: 15 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-108';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_25500','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_25500','PSB',1,25500), ('LINEA_17_25500','VDAN',2,25610), ('LINEA_17_25500','SFF',3,25735), ('LINEA_17_25500','VGA',4,25818), ('LINEA_17_25500','VLE',5,25940), ('LINEA_17_25500','EDN',6,26029), ('LINEA_17_25500','VSPA',7,26226), ('LINEA_17_25500','OSS',8,26382), ('LINEA_17_25500','VSPA',9,26538), ('LINEA_17_25500','CRS',10,26737), ('LINEA_17_25500','PSB',11,26896) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-109';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_28200','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_28200','PSB',1,28200), ('LINEA_17_28200','VDAN',2,28310), ('LINEA_17_28200','SFF',3,28435), ('LINEA_17_28200','VGA',4,28518), ('LINEA_17_28200','VLE',5,28640), ('LINEA_17_28200','EDN',6,28729), ('LINEA_17_28200','VSPA',7,28926), ('LINEA_17_28200','OSS',8,29082), ('LINEA_17_28200','VSPA',9,29238), ('LINEA_17_28200','CRS',10,29437), ('LINEA_17_28200','PSB',11,29596) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_29700','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_29700','PSB',1,29700), ('LINEA_17_29700','VDAN',2,29810), ('LINEA_17_29700','SFF',3,29935), ('LINEA_17_29700','VGA',4,30018), ('LINEA_17_29700','VLE',5,30140), ('LINEA_17_29700','EDN',6,30229), ('LINEA_17_29700','VSPA',7,30426), ('LINEA_17_29700','OSS',8,30582), ('LINEA_17_29700','VSPA',9,30738), ('LINEA_17_29700','CRS',10,30937), ('LINEA_17_29700','PSB',11,31096) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_31500','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_31500','PSB',1,31500), ('LINEA_17_31500','VDAN',2,31610), ('LINEA_17_31500','SFF',3,31735), ('LINEA_17_31500','VGA',4,31818), ('LINEA_17_31500','VLE',5,31940), ('LINEA_17_31500','EDN',6,32029), ('LINEA_17_31500','VSPA',7,32226), ('LINEA_17_31500','OSS',8,32382), ('LINEA_17_31500','VSPA',9,32538), ('LINEA_17_31500','CRS',10,32737), ('LINEA_17_31500','PSB',11,32896) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_33300','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_33300','PSB',1,33300), ('LINEA_17_33300','VDAN',2,33410), ('LINEA_17_33300','SFF',3,33535), ('LINEA_17_33300','VGA',4,33618), ('LINEA_17_33300','VLE',5,33740), ('LINEA_17_33300','EDN',6,33829), ('LINEA_17_33300','VSPA',7,34026), ('LINEA_17_33300','OSS',8,34182), ('LINEA_17_33300','VSPA',9,34338), ('LINEA_17_33300','CRS',10,34537), ('LINEA_17_33300','PSB',11,34696) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-101';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_35100','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_35100','PSB',1,35100), ('LINEA_17_35100','VDAN',2,35210), ('LINEA_17_35100','SFF',3,35335), ('LINEA_17_35100','VGA',4,35418), ('LINEA_17_35100','VLE',5,35540), ('LINEA_17_35100','EDN',6,35629), ('LINEA_17_35100','VSPA',7,35826), ('LINEA_17_35100','OSS',8,35982), ('LINEA_17_35100','VSPA',9,36138), ('LINEA_17_35100','CRS',10,36337), ('LINEA_17_35100','PSB',11,36496) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-106';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_37200','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_37200','PSB',1,37200), ('LINEA_17_37200','VDAN',2,37310), ('LINEA_17_37200','SFF',3,37435), ('LINEA_17_37200','VGA',4,37518), ('LINEA_17_37200','VLE',5,37640), ('LINEA_17_37200','EDN',6,37729), ('LINEA_17_37200','VSPA',7,37926), ('LINEA_17_37200','OSS',8,38082), ('LINEA_17_37200','VSPA',9,38238), ('LINEA_17_37200','CRS',10,38437), ('LINEA_17_37200','PSB',11,38596) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_39300','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_39300','PSB',1,39300), ('LINEA_17_39300','VDAN',2,39410), ('LINEA_17_39300','SFF',3,39535), ('LINEA_17_39300','VGA',4,39618), ('LINEA_17_39300','VLE',5,39740), ('LINEA_17_39300','EDN',6,39829), ('LINEA_17_39300','VSPA',7,40026), ('LINEA_17_39300','OSS',8,40182), ('LINEA_17_39300','VSPA',9,40338), ('LINEA_17_39300','CRS',10,40537), ('LINEA_17_39300','PSB',11,40696) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_43200','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_43200','PSB',1,43200), ('LINEA_17_43200','VDAN',2,43310), ('LINEA_17_43200','SFF',3,43435), ('LINEA_17_43200','VGA',4,43518), ('LINEA_17_43200','VLE',5,43640), ('LINEA_17_43200','EDN',6,43729), ('LINEA_17_43200','VSPA',7,43926), ('LINEA_17_43200','OSS',8,44082), ('LINEA_17_43200','VSPA',9,44238), ('LINEA_17_43200','CRS',10,44437), ('LINEA_17_43200','PSB',11,44596) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_47100','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_47100','PSB',1,47100), ('LINEA_17_47100','VDAN',2,47210), ('LINEA_17_47100','SFF',3,47335), ('LINEA_17_47100','VGA',4,47418), ('LINEA_17_47100','VLE',5,47540), ('LINEA_17_47100','EDN',6,47629), ('LINEA_17_47100','VSPA',7,47826), ('LINEA_17_47100','OSS',8,47982), ('LINEA_17_47100','VSPA',9,48138), ('LINEA_17_47100','CRS',10,48337), ('LINEA_17_47100','PSB',11,48496) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-106';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_50700','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_50700','PSB',1,50700), ('LINEA_17_50700','VDAN',2,50810), ('LINEA_17_50700','SFF',3,50935), ('LINEA_17_50700','VGA',4,51018), ('LINEA_17_50700','VLE',5,51140), ('LINEA_17_50700','EDN',6,51229), ('LINEA_17_50700','VSPA',7,51426), ('LINEA_17_50700','OSS',8,51582), ('LINEA_17_50700','VSPA',9,51738), ('LINEA_17_50700','CRS',10,51937), ('LINEA_17_50700','PSB',11,52096) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-107';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_55800','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_55800','PSB',1,55800), ('LINEA_17_55800','VDAN',2,55910), ('LINEA_17_55800','SFF',3,56035), ('LINEA_17_55800','VGA',4,56118), ('LINEA_17_55800','VLE',5,56240), ('LINEA_17_55800','EDN',6,56329), ('LINEA_17_55800','VSPA',7,56526), ('LINEA_17_55800','OSS',8,56682), ('LINEA_17_55800','VSPA',9,56838), ('LINEA_17_55800','CRS',10,57037), ('LINEA_17_55800','PSB',11,57196) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_63000','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_63000','PSB',1,63000), ('LINEA_17_63000','VDAN',2,63110), ('LINEA_17_63000','SFF',3,63235), ('LINEA_17_63000','VGA',4,63318), ('LINEA_17_63000','VLE',5,63440), ('LINEA_17_63000','EDN',6,63529), ('LINEA_17_63000','VSPA',7,63726), ('LINEA_17_63000','OSS',8,63882), ('LINEA_17_63000','VSPA',9,64038), ('LINEA_17_63000','CRS',10,64237), ('LINEA_17_63000','PSB',11,64396) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_67500','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_67500','PSB',1,67500), ('LINEA_17_67500','VDAN',2,67610), ('LINEA_17_67500','SFF',3,67735), ('LINEA_17_67500','VGA',4,67818), ('LINEA_17_67500','VLE',5,67940), ('LINEA_17_67500','EDN',6,68029), ('LINEA_17_67500','VSPA',7,68226), ('LINEA_17_67500','OSS',8,68382), ('LINEA_17_67500','VSPA',9,68538), ('LINEA_17_67500','CRS',10,68737), ('LINEA_17_67500','PSB',11,68896) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-102';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_17_70200','LINEA_17',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_17_70200','PSB',1,70200), ('LINEA_17_70200','VDAN',2,70310), ('LINEA_17_70200','SFF',3,70435), ('LINEA_17_70200','VGA',4,70518), ('LINEA_17_70200','VLE',5,70640), ('LINEA_17_70200','EDN',6,70729), ('LINEA_17_70200','VSPA',7,70926), ('LINEA_17_70200','OSS',8,71082), ('LINEA_17_70200','VSPA',9,71238), ('LINEA_17_70200','CRS',10,71437), ('LINEA_17_70200','PSB',11,71596) ON CONFLICT DO NOTHING;

    -- ── LINEA_AGR: 4 corse ──
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-106';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_AGR_28800','LINEA_AGR',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_AGR_28800','PSB',1,28800), ('LINEA_AGR_28800','CRS',2,28959), ('LINEA_AGR_28800','SFF',3,29105), ('LINEA_AGR_28800','VGA',4,29188), ('LINEA_AGR_28800','VBO',5,29326), ('LINEA_AGR_28800','AGRA',6,29638) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-104';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_AGR_48900','LINEA_AGR',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_AGR_48900','PSB',1,48900), ('LINEA_AGR_48900','CRS',2,49059), ('LINEA_AGR_48900','SFF',3,49205), ('LINEA_AGR_48900','VGA',4,49288), ('LINEA_AGR_48900','VBO',5,49426), ('LINEA_AGR_48900','AGRA',6,49738) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-109';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_AGR_49500','LINEA_AGR',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_AGR_49500','PSB',1,49500), ('LINEA_AGR_49500','CRS',2,49659), ('LINEA_AGR_49500','SFF',3,49805), ('LINEA_AGR_49500','VGA',4,49888), ('LINEA_AGR_49500','VBO',5,50026), ('LINEA_AGR_49500','AGRA',6,50338) ON CONFLICT DO NOTHING;
    SELECT bus_id INTO b FROM buses WHERE current_vehicle_id='MAGNI-103';
    INSERT INTO trips(id, route_id, bus_id) VALUES ('LINEA_AGR_52500','LINEA_AGR',b) ON CONFLICT (id) DO NOTHING;
    INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds) VALUES
        ('LINEA_AGR_52500','PSB',1,52500), ('LINEA_AGR_52500','CRS',2,52659), ('LINEA_AGR_52500','SFF',3,52805), ('LINEA_AGR_52500','VGA',4,52888), ('LINEA_AGR_52500','VBO',5,53026), ('LINEA_AGR_52500','AGRA',6,53338) ON CONFLICT DO NOTHING;
END $$;

