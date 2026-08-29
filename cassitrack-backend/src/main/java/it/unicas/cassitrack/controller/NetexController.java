package it.unicas.cassitrack.controller;

import it.unicas.cassitrack.dto.netex.*;
import it.unicas.cassitrack.model.*;
import it.unicas.cassitrack.model.Route;
import it.unicas.cassitrack.model.Stop;
import it.unicas.cassitrack.repository.*;
import it.unicas.cassitrack.service.RoutePatternService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/static")
public class NetexController {

    @Value("${sse.api-token}")
    private String expectedToken;

    private final StopRepository stopRepository;
    private final RouteRepository routeRepository;
    private final TripRepository tripRepository;
    private final ScheduledStopRepository scheduledStopRepository;
    private final BusRepository busRepository;
    private final RouteShapeRepository routeShapeRepository;
    private final DataVersionRepository dataVersionRepository;
    private final RoutePatternService routePatternService;

    /**
     * L'unico tipo di giorno del documento. Costante perche' vi puntano sia il
     * ServiceCalendarFrame che ogni ServiceJourney: scriverlo due volte a mano
     * significherebbe poter sbagliare da una parte sola, e un riferimento a un
     * DayType inesistente e' il genere di errore che nessun test coglie e ogni
     * validatore segnala.
     */
    private static final String DAY_TYPE_ID = "CASSITRACK:DayType:Everyday";

    public NetexController(StopRepository stopRepository,
                           RouteRepository routeRepository,
                           TripRepository tripRepository,
                           ScheduledStopRepository scheduledStopRepository,
                           BusRepository busRepository,
                           RouteShapeRepository routeShapeRepository,
                           DataVersionRepository dataVersionRepository,
                           RoutePatternService routePatternService) {
        this.stopRepository = stopRepository;
        this.routeRepository = routeRepository;
        this.tripRepository = tripRepository;
        this.scheduledStopRepository = scheduledStopRepository;
        this.busRepository = busRepository;
        this.routeShapeRepository = routeShapeRepository;
        this.dataVersionRepository = dataVersionRepository;
        this.routePatternService = routePatternService;
    }

    // ── helper: converti secondi in formato NeTEx HH:mm:ss ──────────────────
    private static String secondsToTime(Integer seconds) {
        if (seconds == null) return null;
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    /**
     * Change counters for the static-data tables — the cheap companion to
     * /netex.
     *
     * A consumer that mirrors this data (OmniMove) polls this endpoint instead
     * of re-downloading the whole NeTEx document to find out whether anything
     * moved. Rebuilding that document reads every stop, route, trip and
     * scheduled stop; this reads five rows from data_version, whose counters
     * are maintained by database triggers (see V15__data_version.sql).
     *
     * Response:
     *   { "routes": 3, "stops": 1, "trips": 7,
     *     "scheduled_stops": 7, "route_shapes": 2 }
     *
     * A number changing means "re-import"; the numbers themselves carry no
     * meaning beyond being different from last time. `buses` is deliberately
     * absent — it changes on every map-visibility toggle, which OmniMove does
     * not consume.
     *
     * Same X-Api-Key as /netex (NFR-11): this exposes no data, but there is no
     * reason to leave a polling target open either.
     */
    @GetMapping(value = "/version", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Long> getStaticDataVersion(
            @RequestHeader(value = "X-Api-Key", required = false) String receivedToken,
            HttpServletResponse response) {

        if (!MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                (receivedToken != null ? receivedToken : "").getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }

        Map<String, Long> out = new HashMap<>();
        for (DataVersion v : dataVersionRepository.findAll()) {
            out.put(v.getTableName(), v.getVersion());
        }
        return out;
    }

    @GetMapping(value = "/netex", produces = MediaType.APPLICATION_XML_VALUE)
    public PublicationDeliveryDTO getNetexData(
            @RequestHeader(value = "X-Api-Key", required = false) String receivedToken,
            HttpServletResponse response) {

        if (!MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                (receivedToken != null ? receivedToken : "").getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }

        // ── 1. SITE FRAME (StopPlace — luogo fisico con coordinate) ────────────
        List<Stop> dbStops = stopRepository.findAll();
        List<StopPlaceDTO> netexStopPlaces = dbStops.stream().map(stop -> {
            StopPlaceDTO dto = new StopPlaceDTO();
            dto.setId("CASSITRACK:StopPlace:" + stop.getId());
            dto.setName(stop.getName());
            dto.setCentroid(new CentroidDTO(new LocationDTO(stop.getLon(), stop.getLat())));
            return dto;
        }).collect(Collectors.toList());

        SiteFrameDTO siteFrame = new SiteFrameDTO();
        siteFrame.setStopPlaces(netexStopPlaces);

        // ── 2. SERVICE FRAME (Linee e Corse) ────────────────────────────────
        // 2a. ScheduledStopPoint — punto logico, senza coordinate
        List<ScheduledStopPointDTO> netexSSPs = dbStops.stream().map(stop -> {
            ScheduledStopPointDTO dto = new ScheduledStopPointDTO();
            dto.setId("CASSITRACK:ScheduledStopPoint:" + stop.getId());
            dto.setName(stop.getName());
            return dto;
        }).collect(Collectors.toList());

        // 2b. PassengerStopAssignment — collega ogni SSP al suo StopPlace fisico
        final int[] psa_order = {1};
        List<PassengerStopAssignmentDTO> netexAssignments = dbStops.stream().map(stop -> {
            PassengerStopAssignmentDTO psa = new PassengerStopAssignmentDTO();
            psa.setId("CASSITRACK:PassengerStopAssignment:" + stop.getId());
            psa.setOrder(psa_order[0]++);
            psa.setScheduledStopPointRef(new RefDTO("CASSITRACK:ScheduledStopPoint:" + stop.getId()));
            psa.setStopPlaceRef(new RefDTO("CASSITRACK:StopPlace:" + stop.getId()));
            return psa;
        }).collect(Collectors.toList());

        // Road geometry, fetched once and grouped, so the loop below does not
        // issue a query per line. Empty when no route has a shape yet.
        Map<String, List<RouteShape>> shapesByRoute = new HashMap<>();
        for (RouteShape sh : routeShapeRepository.findAllByOrderByRouteIdAscSeqAsc()) {
            shapesByRoute.computeIfAbsent(sh.getRouteId(), k -> new ArrayList<>()).add(sh);
        }

        List<Route> dbRoutes = routeRepository.findAll();
        List<LineDTO> netexLines = dbRoutes.stream().map(route -> {
            LineDTO dto = new LineDTO();
            dto.setId("CASSITRACK:Line:" + route.getId());
            dto.setName(route.getLongName());
            dto.setShortName(route.getShortName());
            // transportMode è già "bus" per default

            // Publish the line colour. Omitted when the route has none, so a
            // consumer can distinguish "not set" from a real colour.
            if (route.getColor() != null && !route.getColor().isBlank()) {
                dto.setPresentation(new PresentationDTO(route.getColor(), route.getTextColor()));
            }

            // La geometria stradale, dentro <Extensions>.
            //
            // Era un figlio diretto di <Line>, dove NeTEx non la prevede: una
            // Line non ha un LineString, la geometria appartiene ai RouteLink
            // via LinkSequenceProjection. Extensions è il punto che lo standard
            // riserva al contenuto non previsto, quindi un validatore lo
            // attraversa invece di fallire.
            //
            // Omessa del tutto per le linee senza tracciato: assente e vuota
            // sono due affermazioni diverse.
            List<RouteShape> shape = shapesByRoute.get(route.getId());
            if (shape != null && shape.size() >= 2) {
                StringBuilder pos = new StringBuilder(shape.size() * 22);
                for (RouteShape p : shape) {
                    if (pos.length() > 0) pos.append(' ');
                    pos.append(p.getLat()).append(' ').append(p.getLon());
                }
                LineStringDTO ls = new LineStringDTO();
                // gml:id obbligatorio, e deve essere un NCName: non può
                // iniziare con una cifra né contenere ':', quindi l'id della
                // linea da solo non basterebbe.
                ls.setGmlId("CASSITRACK-shape-" + route.getId().replaceAll("[^A-Za-z0-9_-]", "_"));
                ls.setPosList(pos.toString());
                dto.setExtensions(new LineExtensionsDTO(ls));
            }
            return dto;
        }).collect(Collectors.toList());

        List<Trip> dbTrips = tripRepository.findAll();
        List<ServiceJourneyDTO> netexJourneys = dbTrips.stream().map(trip -> {
            ServiceJourneyDTO journeyDto = new ServiceJourneyDTO();
            journeyDto.setId("CASSITRACK:ServiceJourney:" + trip.getId());
            journeyDto.setLineRef(new RefDTO("CASSITRACK:Line:" + trip.getRoute().getId()));
            // Ogni corsa dichiara i giorni in cui vale. Uno solo, perche' il
            // database non distingue: vedi ServiceCalendarFrameDTO.
            journeyDto.setDayTypes(List.of(new RefDTO(DAY_TYPE_ID)));

            // VehicleRef in extensions (associazione non standard nel core NeTEx)
            if (trip.getBus() != null) {
                journeyDto.setExtensions(
                        new ServiceJourneyExtensionsDTO("CASSITRACK:Vehicle:" + trip.getBus().getBusId()));
            }

            // Ordinata: le Call di una ServiceJourney descrivono un percorso, e
            // pubblicarle nell'ordine in cui il database le restituisce non è
            // una garanzia che il consumatore possa dare per buona.
            List<ScheduledStop> stopsForThisTrip =
                    scheduledStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId());
            // Da V24 la fermata arriva dal pattern della linea. Il formato sul
            // filo NON cambia: si continuano a emettere Call che portano sia la
            // fermata sia l'orario. Emettere un vero ServiceJourneyPattern
            // sarebbe ora possibile e più aderente allo standard, ma è un
            // cambio di contratto verso OmniMove e va concordato, non subito.
            String patternRouteId = trip.getRoute() != null ? trip.getRoute().getId() : null;
            if (!stopsForThisTrip.isEmpty()) {
                int totalStops = stopsForThisTrip.size();
                List<CallDTO> netexCalls = stopsForThisTrip.stream().map(sStop -> {
                    CallDTO callDto = new CallDTO();
                    // id obbligatorio. Corsa + posizione lo rende univoco in
                    // tutto il documento anche sugli anelli, dove la stessa
                    // fermata compare due volte nella stessa corsa.
                    callDto.setId("CASSITRACK:Call:" + trip.getId()
                                  + ":" + sStop.getStopSequence());
                    callDto.setOrder(sStop.getStopSequence());
                    String patternStopId = routePatternService.stopIdAt(
                            patternRouteId, sStop.getStopSequence());
                    callDto.setScheduledStopPointRef(new RefDTO(
                            "CASSITRACK:ScheduledStopPoint:"
                            + (patternStopId != null ? patternStopId : "UNKNOWN")));
                    String time = secondsToTime(sStop.getArrivalSeconds());
                    if (time != null) {
                        int pos = sStop.getStopSequence();
                        boolean isLast = (pos == totalStops);
                        // Arrival: sempre presente (richiesto dall'import; prima fermata ha stesso
                        //          orario di partenza dato che il DB ha un solo campo)
                        // Departure: tutte le fermate tranne l'ultima (il bus riparte)
                        callDto.setArrival(new ArrivalDTO(time));
                        if (!isLast) callDto.setDeparture(new DepartureDTO(time));
                    }
                    return callDto;
                }).collect(Collectors.toList());
                journeyDto.setCalls(netexCalls);
            }

            return journeyDto;
        }).collect(Collectors.toList());

        // ── SERVICE CALENDAR FRAME ──────────────────────────────────────
        // Un solo DayType: il database ha un orario unico ripetuto ogni
        // giorno, e inventare distinzioni che i dati non fanno sarebbe
        // peggio che dichiarare la semplicita' vera.
        ServiceCalendarFrameDTO calendarFrame = new ServiceCalendarFrameDTO();
        calendarFrame.setDayTypes(List.of(new DayTypeDTO(
                DAY_TYPE_ID, "Every day",
                new PropertiesOfDayDTO(new PropertyOfDayDTO()))));

        ServiceFrameDTO serviceFrame = new ServiceFrameDTO();
        serviceFrame.setScheduledStopPoints(netexSSPs);
        serviceFrame.setStopAssignments(netexAssignments);
        serviceFrame.setLines(netexLines);

        // ── 2c. TIMETABLE FRAME (Corse) ─────────────────────────────────────
        // In NeTEx le ServiceJourney vivono nel TimetableFrame, non nel ServiceFrame.
        TimetableFrameDTO timetableFrame = new TimetableFrameDTO();
        timetableFrame.setServiceJourneys(netexJourneys);

        // ── 3. RESOURCE FRAME (Veicoli) ─────────────────────────────────────
        List<Bus> dbBuses = busRepository.findAll();
        List<VehicleDTO> netexVehicles = dbBuses.stream().map(bus -> {
            VehicleDTO dto = new VehicleDTO();
            dto.setId("CASSITRACK:Vehicle:" + bus.getBusId());
            dto.setPrivateCode(bus.getCurrentVehicleId()); // ID MQTT (es. BUS-101)
            VehicleExtensionsDTO ext = new VehicleExtensionsDTO();
            ext.setTarga(bus.getTarga());
            ext.setNumeroPosti(bus.getNumeroPosti());
            ext.setWheelchairAccessible(bus.getWheelchairAccessible());
            ext.setDisponibile(bus.getDisponibile());
            dto.setExtensions(ext);
            return dto;
        }).collect(Collectors.toList());

        ResourceFrameDTO resourceFrame = new ResourceFrameDTO();
        resourceFrame.setVehicles(netexVehicles);

        // ── 4. ASSEMBLAGGIO FINALE ───────────────────────────────────────────
        FramesDTO frames = new FramesDTO();
        frames.setResourceFrame(resourceFrame);
        frames.setSiteFrame(siteFrame);
        frames.setServiceFrame(serviceFrame);
        frames.setServiceCalendarFrame(calendarFrame);
        frames.setTimetableFrame(timetableFrame);

        CompositeFrameDTO compositeFrame = new CompositeFrameDTO();
        compositeFrame.setFrames(frames);

        DataObjects dataObjects = new DataObjects();
        dataObjects.setCompositeFrame(compositeFrame);

        PublicationDeliveryDTO publicationDelivery = new PublicationDeliveryDTO();
        publicationDelivery.setDataObjects(dataObjects);

        return publicationDelivery;
    }
}