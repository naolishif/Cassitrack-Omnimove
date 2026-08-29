package it.unicas.omnimove.service;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.unicas.omnimove.dto.netex.*;
import it.unicas.omnimove.model.*;
import it.unicas.omnimove.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import javax.xml.stream.XMLInputFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class NetexImportService {

    @Value("${cassitrack.netex.url}")
    private String cassitrackNetexUrl;

    @Value("${cassitrack.api.token}")
    private String cassitrackApiToken;

    private final StopRepository stopRepository;
    private final RouteRepository routeRepository;
    private final TripRepository tripRepository;
    private final ScheduledStopRepository scheduledStopRepository;
    private final BusRepository busRepository; // ← AGGIUNTO
    private final RouteShapeRepository routeShapeRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private final RestClient restClient;

    public NetexImportService(StopRepository stopRepository,
                              RouteRepository routeRepository,
                              TripRepository tripRepository,
                              ScheduledStopRepository scheduledStopRepository,
                              BusRepository busRepository, // ← AGGIUNTO
                              RouteShapeRepository routeShapeRepository) {
        this.stopRepository = stopRepository;
        this.routeRepository = routeRepository;
        this.tripRepository = tripRepository;
        this.scheduledStopRepository = scheduledStopRepository;
        this.busRepository = busRepository; // ← AGGIUNTO
        this.routeShapeRepository = routeShapeRepository;

        XMLInputFactory xmlFactory = XMLInputFactory.newInstance();
        xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        XmlMapper xmlMapper = new XmlMapper(xmlFactory);
        xmlMapper.registerModule(new JavaTimeModule());
        xmlMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Tollera elementi/attributi NeTEx che non modelliamo (es. ValidityConditions,
        // TypeOfFrameRef aggiunti per conformità): non far fallire l'import per questi.
        xmlMapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        this.restClient = RestClient.builder()
                .messageConverters(converters -> {
                    converters.add(new org.springframework.http.converter.xml.MappingJackson2XmlHttpMessageConverter(xmlMapper));
                })
                .build();
    }

    /**
     * Estrae la parte locale da un ID NeTEx con namespace.
     * Es: "CASSITRACK:ScheduledStopPoint:PSB" → "PSB"
     *     "CASSITRACK:Line:LINEA_1"           → "LINEA_1"
     *     "PSB" (già locale)                  → "PSB"
     */
    private static String localId(String netexId) {
        if (netexId == null) return null;
        int colon = netexId.lastIndexOf(':');
        return colon >= 0 ? netexId.substring(colon + 1) : netexId;
    }

    // ── helper: converti HH:mm:ss → secondi ────────────────────────────────
    private static Integer timeToSeconds(String time) {
        if (time == null || time.isBlank()) return null;
        String[] parts = time.split(":");
        if (parts.length != 3) return null;
        try {
            return Integer.parseInt(parts[0]) * 3600
                 + Integer.parseInt(parts[1]) * 60
                 + Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * "" and "   " mean "absent" in an XML document; NULL means it in a
     * database. Anything unique-constrained must make that translation, or two
     * genuinely empty values collide with each other.
     */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Feed colour → the 6-hex form the column holds, or null.
     *
     * The column is VARCHAR(6), so anything that is not a clean 6-digit hex has
     * to become NULL rather than reach the insert: a stray '#RRGGBB' from a
     * hand-edited route would be truncated to '#RRGGB' and paint the wrong
     * colour on every map that reads it. Null is honest — the traveller map
     * falls back to a generated colour.
     */
    private static String normalizeHex(String c) {
        if (c == null) return null;
        String v = c.trim();
        if (v.startsWith("#")) v = v.substring(1);
        return v.matches("(?i)[0-9a-f]{6}") ? v.toUpperCase() : null;
    }

    @Transactional
    public void importDataFromCassitrack() {
        System.out.println("Inizio scaricamento dati NeTEx da Cassitrack...");

        System.out.println("Pulizia dei vecchi dati prima dell'importazione...");
        scheduledStopRepository.deleteAll();
        tripRepository.deleteAll();
        routeRepository.deleteAll();
        stopRepository.deleteAll();
        busRepository.deleteAll();
        // Forza il flush delle DELETE prima di qualsiasi INSERT successivo.
        // Senza questo, Hibernate può riordinare INSERT prima delle DELETE
        // causando violazioni di constraint UNIQUE (es. buses_license_plate_key).
        entityManager.flush();
        entityManager.clear();

        PublicationDeliveryDTO netexData = restClient.get()
                .uri(cassitrackNetexUrl)
                .accept(org.springframework.http.MediaType.APPLICATION_XML)
                .header("X-Api-Key", cassitrackApiToken)
                .retrieve()
                .body(PublicationDeliveryDTO.class);

        if (netexData == null || netexData.getDataObjects() == null) {
            throw new RuntimeException("NeTEx import aborted: no data received from CassiTrack. Previous data preserved.");
        }

        CompositeFrameDTO compositeFrame = netexData.getDataObjects().getCompositeFrame();
        if (compositeFrame == null || compositeFrame.getFrames() == null) {
            throw new RuntimeException("NeTEx import aborted: CompositeFrame/frames assenti.");
        }

        FramesDTO frames = compositeFrame.getFrames();

        // ── 1. SITE FRAME → StopPlace (coordinate geografiche) ─────────────
        SiteFrameDTO siteFrame = frames.getSiteFrame();
        // Mappa: localId StopPlace → DTO (per recuperare coords dopo)
        Map<String, StopPlaceDTO> stopPlaceMap = new HashMap<>();
        if (siteFrame != null && siteFrame.getStopPlaces() != null) {
            for (StopPlaceDTO sp : siteFrame.getStopPlaces()) {
                stopPlaceMap.put(localId(sp.getId()), sp);
            }
        }

        // ── 2. RESOURCE FRAME → Veicoli (Bus) ──────────────────────────────
        ResourceFrameDTO resourceFrame = frames.getResourceFrame();
        if (resourceFrame != null && resourceFrame.getVehicles() != null) {
            List<Bus> buses = resourceFrame.getVehicles().stream().map(dto -> {
                Bus bus = new Bus();
                // A bus with no on-board unit has current_vehicle_id NULL in
                // CassiTrack, but a null PrivateCode crosses the wire as an
                // empty XML element and arrives here as "". Postgres tolerates
                // many NULLs in a unique index and exactly one '', so leaving
                // it blank made the SECOND antenna-less bus abort the whole
                // import with buses_current_vehicle_id_key.
                bus.setCurrentVehicleId(blankToNull(dto.getPrivateCode()));
                if (dto.getExtensions() != null) {
                    bus.setLicensePlate(dto.getExtensions().getTarga());
                    bus.setNumberSeats(dto.getExtensions().getNumeroPosti());
                    bus.setPlaceDisablePeople(dto.getExtensions().getWheelchairAccessible());
                    bus.setAvailable(dto.getExtensions().getDisponibile());
                }
                return bus;
            }).collect(java.util.stream.Collectors.toList());
            busRepository.saveAll(buses);
        }

        // ── 3. SERVICE FRAME → SSP + PSA + Linee + Corse ───────────────────
        ServiceFrameDTO serviceFrame = frames.getServiceFrame();
        if (serviceFrame != null) {

            // 3a. PassengerStopAssignment → mappa sspLocalId → stopPlaceLocalId
            Map<String, String> sspToSpMap = new HashMap<>();
            if (serviceFrame.getStopAssignments() != null) {
                for (PassengerStopAssignmentDTO psa : serviceFrame.getStopAssignments()) {
                    String sspId = localId(psa.getScheduledStopPointRef() != null
                            ? psa.getScheduledStopPointRef().getRef() : null);
                    String spId  = localId(psa.getStopPlaceRef() != null
                            ? psa.getStopPlaceRef().getRef() : null);
                    if (sspId != null && spId != null) sspToSpMap.put(sspId, spId);
                }
            }

            // 3b. ScheduledStopPoint → Stop (id = localId SSP; coords da StopPlace via PSA)
            if (serviceFrame.getScheduledStopPoints() != null) {
                List<Stop> stops = serviceFrame.getScheduledStopPoints().stream().map(sspDto -> {
                    Stop stop = new Stop();
                    String sspLocalId = localId(sspDto.getId());
                    stop.setId(sspLocalId);
                    stop.setName(sspDto.getName());
                    // Trova il StopPlace corrispondente tramite la mappa PSA
                    String spLocalId = sspToSpMap.getOrDefault(sspLocalId, sspLocalId);
                    StopPlaceDTO sp = stopPlaceMap.get(spLocalId);
                    if (sp != null && sp.getCentroid() != null && sp.getCentroid().getLocation() != null) {
                        stop.setLat(sp.getCentroid().getLocation().getLatitude());
                        stop.setLon(sp.getCentroid().getLocation().getLongitude());
                    }
                    stop.setActive(true);
                    return stop;
                }).collect(java.util.stream.Collectors.toList());
                stopRepository.saveAll(stops);
            }

            // 3c. Linee
            if (serviceFrame.getLines() != null) {
                List<Route> routes = serviceFrame.getLines().stream().map(dto -> {
                    Route route = new Route();
                    route.setId(localId(dto.getId())); // "CASSITRACK:Line:LINEA_1" → "LINEA_1"
                    route.setLongName(dto.getName());
                    route.setShortName(dto.getShortName());
                    route.setActive(true);
                    // Colour, when the feed carries it. Normalised here rather
                    // than at every read site: CassiTrack stores bare hex, but a
                    // hand-edited route could carry a leading '#'.
                    if (dto.getPresentation() != null) {
                        route.setColor(normalizeHex(dto.getPresentation().getColour()));
                        route.setTextColor(normalizeHex(dto.getPresentation().getTextColour()));
                    }
                    return route;
                }).collect(java.util.stream.Collectors.toList());
                routeRepository.saveAll(routes);

                // 3d. Geometria stradale delle linee (opzionale).
                // Saved after the routes so the FK on route_shapes.route_id is
                // satisfied. A line without <LineString> leaves any existing
                // shape untouched rather than wiping it — older CassiTrack
                // builds simply do not publish the element.
                for (LineDTO dto : serviceFrame.getLines()) {
                    // resolveLineString(): la geometria e' passata da figlio
                    // diretto di <Line> (non conforme) a <Extensions>. Il DTO
                    // legge entrambe le posizioni, quindi questo import
                    // funziona sia col CassiTrack aggiornato che con quello
                    // precedente, e i due sistemi non devono essere rilasciati
                    // nello stesso momento.
                    importRouteShape(localId(dto.getId()), dto.resolveLineString());
                }
            }

            // 3e. Corse — ora nel TimetableFrame (standard NeTEx);
            //     fallback al ServiceFrame per retro-compatibilità con pacchetti vecchi.
            List<ServiceJourneyDTO> journeys = frames.getTimetableFrame() != null
                    ? frames.getTimetableFrame().getServiceJourneys()
                    : serviceFrame.getServiceJourneys();
            if (journeys != null) {
                for (ServiceJourneyDTO journeyDto : journeys) {
                    Trip trip = new Trip();
                    trip.setId(localId(journeyDto.getId())); // "CASSITRACK:ServiceJourney:LINEA_1_28800" → "LINEA_1_28800"

                    String routeId = journeyDto.getLineRef() != null
                            ? localId(journeyDto.getLineRef().getRef()) : null; // "CASSITRACK:Line:LINEA_1" → "LINEA_1"
                    Route associatedRoute = routeId != null ? routeRepository.findById(routeId).orElse(null) : null;
                    trip.setRoute(associatedRoute);

                    // VehicleRef nelle extensions → collegamento al Bus
                    if (journeyDto.getExtensions() != null && journeyDto.getExtensions().getVehicleRef() != null) {
                        try {
                            // "CASSITRACK:Vehicle:1" → "1" → 1
                            Integer busId = Integer.parseInt(localId(journeyDto.getExtensions().getVehicleRef()));
                            Bus associatedBus = busRepository.findById(busId).orElse(null);
                            trip.setBus(associatedBus);
                        } catch (NumberFormatException ignored) {}
                    }

                    tripRepository.save(trip);

                    // Fermate della corsa
                    if (journeyDto.getCalls() != null) {
                        List<ScheduledStop> stops = journeyDto.getCalls().stream().map(callDto -> {
                            ScheduledStop sStop = new ScheduledStop();
                            sStop.setTrip(trip);
                            sStop.setStopId(callDto.getScheduledStopPointRef() != null
                                    ? localId(callDto.getScheduledStopPointRef().getRef()) : null); // "CASSITRACK:ScheduledStopPoint:PSB" → "PSB"
                            sStop.setStopSequence(callDto.getOrder());
                            // Conversione HH:mm:ss → secondi
                            // Prima fermata ha solo Departure, ultima solo Arrival, mezzo entrambi
                            String time = null;
                            if (callDto.getArrival() != null) time = callDto.getArrival().getTime();
                            else if (callDto.getDeparture() != null) time = callDto.getDeparture().getTime();
                            sStop.setArrivalSeconds(timeToSeconds(time));
                            return sStop;
                        }).collect(java.util.stream.Collectors.toList());
                        scheduledStopRepository.saveAll(stops);
                    }
                }
            }
        }

        System.out.println("Importazione NeTEx completata con successo nel database di Omnimove!");
    }

    /**
     * Persist one line's road geometry, replacing whatever was stored before.
     *
     * <p>The payload is a GML-style posList: "lat lon lat lon …" in path order.
     * Replacement is wholesale rather than a diff — a path is reshaped in the
     * route editor as a unit, so merging point by point would add complexity
     * without buying anything.
     *
     * <p>A missing or malformed element is not an error: the shape is optional,
     * and losing it must never fail an otherwise good import. In that case the
     * previously stored geometry is left alone and the traveller map simply
     * keeps using it.
     *
     * @param routeId local route id, e.g. "LINEA_1"
     * @param ls      the LineString element, or null when the feed has none
     */
    private void importRouteShape(String routeId, LineStringDTO ls) {
        if (routeId == null || ls == null || ls.getPosList() == null) return;

        String[] tok = ls.getPosList().trim().split("\\s+");
        // Coordinates come in pairs; an odd count means a truncated document.
        if (tok.length < 4 || tok.length % 2 != 0) {
            System.out.println("NeTEx: geometria ignorata per " + routeId
                    + " (posList non valida, " + tok.length + " valori)");
            return;
        }

        List<RouteShape> pts = new java.util.ArrayList<>(tok.length / 2);
        try {
            for (int i = 0, seq = 0; i < tok.length; i += 2, seq++) {
                RouteShape p = new RouteShape();
                p.setRouteId(routeId);
                p.setSeq(seq);
                p.setLat(Double.parseDouble(tok[i]));
                p.setLon(Double.parseDouble(tok[i + 1]));
                // is_stop is not carried on the feed: the traveller map cuts
                // legs by matching stop coordinates, so the flag is redundant
                // here. Kept false to satisfy the NOT NULL column.
                p.setIsStop(false);
                pts.add(p);
            }
        } catch (NumberFormatException e) {
            System.out.println("NeTEx: geometria ignorata per " + routeId
                    + " (coordinate non numeriche)");
            return;
        }

        routeShapeRepository.deleteByRouteId(routeId);
        routeShapeRepository.flush();      // delete before insert: same PKs
        routeShapeRepository.saveAll(pts);
    }
}