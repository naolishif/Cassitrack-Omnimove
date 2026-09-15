package it.unicas.cassitrack.dto.siri;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Modello SIRI 2.1 (CEN EN 15531) — i tre servizi che CassiTrack espone:
 * <ul>
 *   <li><b>VehicleMonitoring</b> — dove sono i mezzi, su quale corsa, con quale
 *       ritardo, prossima fermata e fermate a seguire;</li>
 *   <li><b>StopMonitoring</b> — chi passa da una fermata, quando era previsto e
 *       quando è atteso;</li>
 *   <li><b>CheckStatus</b> — il servizio è vivo, e da quando.</li>
 * </ul>
 * <p>
 * L'ordine degli elementi segue rigorosamente la {@code xsd:sequence} dello schema
 * (SIRI è severo: un elemento fuori posto invalida il documento). L'ordine è
 * vincolato tramite {@link JsonPropertyOrder} su ogni struttura, così non dipende
 * dall'introspezione di Jackson. I campi {@code null} non vengono serializzati.
 * <p>
 * Tutto ciò che il profilo minimo emetteva prima è ancora qui, con lo stesso nome
 * e nella stessa posizione: OmniMove legge questo documento e ignora ciò che non
 * conosce, quindi gli elementi nuovi sono per gli altri consumer, non contro lui.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "Siri")
public class Siri {

    /**
     * 2.1 e non 2.0: i valori di Occupancy che emettiamo (manySeatsAvailable,
     * fewSeatsAvailable) esistono solo dalla 2.1. Dichiarare 2.0 e usarli era una
     * bugia che uno schema 2.0 stretto avrebbe smascherato.
     */
    public static final String VERSION = "2.1";

    @JacksonXmlProperty(isAttribute = true, localName = "xmlns")
    private String xmlns = "http://www.siri.org.uk/siri";

    @JacksonXmlProperty(isAttribute = true, localName = "version")
    private String version = VERSION;

    @JacksonXmlProperty(localName = "CheckStatusResponse")
    private CheckStatusResponse checkStatusResponse;

    @JacksonXmlProperty(localName = "ServiceDelivery")
    private ServiceDelivery serviceDelivery;

    public Siri() {}
    public Siri(ServiceDelivery serviceDelivery) { this.serviceDelivery = serviceDelivery; }
    public Siri(CheckStatusResponse checkStatusResponse) { this.checkStatusResponse = checkStatusResponse; }

    public String getXmlns() { return xmlns; }
    public void setXmlns(String xmlns) { this.xmlns = xmlns; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public CheckStatusResponse getCheckStatusResponse() { return checkStatusResponse; }
    public void setCheckStatusResponse(CheckStatusResponse v) { this.checkStatusResponse = v; }
    public ServiceDelivery getServiceDelivery() { return serviceDelivery; }
    public void setServiceDelivery(ServiceDelivery sd) { this.serviceDelivery = sd; }

    // ── CheckStatusResponse ────────────────────────────────────────────────────
    // XSD: ResponseTimestamp, ProducerRef, ..., Status, ..., ServiceStartedTime
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({ "responseTimestamp", "producerRef", "status", "serviceStartedTime" })
    public static class CheckStatusResponse {
        @JacksonXmlProperty(localName = "ResponseTimestamp")
        private String responseTimestamp = now();
        @JacksonXmlProperty(localName = "ProducerRef")
        private String producerRef = "CASSITRACK";
        @JacksonXmlProperty(localName = "Status")
        private boolean status = true;
        @JacksonXmlProperty(localName = "ServiceStartedTime")
        private String serviceStartedTime;

        public String getResponseTimestamp() { return responseTimestamp; }
        public void setResponseTimestamp(String v) { this.responseTimestamp = v; }
        public String getProducerRef() { return producerRef; }
        public void setProducerRef(String v) { this.producerRef = v; }
        public boolean isStatus() { return status; }
        public void setStatus(boolean v) { this.status = v; }
        public String getServiceStartedTime() { return serviceStartedTime; }
        public void setServiceStartedTime(String v) { this.serviceStartedTime = v; }
    }

    // ── ServiceDelivery ────────────────────────────────────────────────────────
    // XSD: ResponseTimestamp, ProducerRef, ..., StopMonitoringDelivery*,
    //      VehicleMonitoringDelivery*  (SM viene PRIMA di VM nella sequence)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({ "responseTimestamp", "producerRef",
            "stopMonitoringDelivery", "vehicleMonitoringDelivery" })
    public static class ServiceDelivery {
        @JacksonXmlProperty(localName = "ResponseTimestamp")
        private String responseTimestamp = now();

        /** Identificativo del produttore del dato (obbligatorio nei profili nazionali). */
        @JacksonXmlProperty(localName = "ProducerRef")
        private String producerRef = "CASSITRACK";

        @JacksonXmlProperty(localName = "StopMonitoringDelivery")
        private StopMonitoringDelivery stopMonitoringDelivery;

        @JacksonXmlProperty(localName = "VehicleMonitoringDelivery")
        private VehicleMonitoringDelivery vehicleMonitoringDelivery;

        public String getResponseTimestamp() { return responseTimestamp; }
        public void setResponseTimestamp(String v) { this.responseTimestamp = v; }
        public String getProducerRef() { return producerRef; }
        public void setProducerRef(String v) { this.producerRef = v; }
        public StopMonitoringDelivery getStopMonitoringDelivery() { return stopMonitoringDelivery; }
        public void setStopMonitoringDelivery(StopMonitoringDelivery v) { this.stopMonitoringDelivery = v; }
        public VehicleMonitoringDelivery getVehicleMonitoringDelivery() { return vehicleMonitoringDelivery; }
        public void setVehicleMonitoringDelivery(VehicleMonitoringDelivery v) { this.vehicleMonitoringDelivery = v; }
    }

    // ── VehicleMonitoringDelivery ──────────────────────────────────────────────
    // XSD: ResponseTimestamp, ..., Status, ..., VehicleActivity*
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({ "version", "responseTimestamp", "status", "vehicleActivity" })
    public static class VehicleMonitoringDelivery {
        @JacksonXmlProperty(isAttribute = true, localName = "version")
        private String version = VERSION;
        @JacksonXmlProperty(localName = "ResponseTimestamp")
        private String responseTimestamp = now();
        @JacksonXmlProperty(localName = "Status")
        private boolean status = true;
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "VehicleActivity")
        private List<VehicleActivity> vehicleActivity = new ArrayList<>();

        public String getVersion() { return version; }
        public void setVersion(String v) { this.version = v; }
        public String getResponseTimestamp() { return responseTimestamp; }
        public void setResponseTimestamp(String v) { this.responseTimestamp = v; }
        public boolean isStatus() { return status; }
        public void setStatus(boolean v) { this.status = v; }
        public List<VehicleActivity> getVehicleActivity() { return vehicleActivity; }
        public void setVehicleActivity(List<VehicleActivity> v) { this.vehicleActivity = v; }
    }

    // ── StopMonitoringDelivery ─────────────────────────────────────────────────
    // XSD: ResponseTimestamp, ..., Status, ..., MonitoringRef?, MonitoredStopVisit*
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({ "version", "responseTimestamp", "status", "monitoringRef", "monitoredStopVisit" })
    public static class StopMonitoringDelivery {
        @JacksonXmlProperty(isAttribute = true, localName = "version")
        private String version = VERSION;
        @JacksonXmlProperty(localName = "ResponseTimestamp")
        private String responseTimestamp = now();
        @JacksonXmlProperty(localName = "Status")
        private boolean status = true;
        @JacksonXmlProperty(localName = "MonitoringRef")
        private String monitoringRef;
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "MonitoredStopVisit")
        private List<MonitoredStopVisit> monitoredStopVisit = new ArrayList<>();

        public String getVersion() { return version; }
        public void setVersion(String v) { this.version = v; }
        public String getResponseTimestamp() { return responseTimestamp; }
        public void setResponseTimestamp(String v) { this.responseTimestamp = v; }
        public boolean isStatus() { return status; }
        public void setStatus(boolean v) { this.status = v; }
        public String getMonitoringRef() { return monitoringRef; }
        public void setMonitoringRef(String v) { this.monitoringRef = v; }
        public List<MonitoredStopVisit> getMonitoredStopVisit() { return monitoredStopVisit; }
        public void setMonitoredStopVisit(List<MonitoredStopVisit> v) { this.monitoredStopVisit = v; }
    }

    // ── MonitoredStopVisit ─────────────────────────────────────────────────────
    // XSD: RecordedAtTime, ItemIdentifier?, MonitoringRef, ..., MonitoredVehicleJourney
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({ "recordedAtTime", "itemIdentifier", "monitoringRef", "monitoredVehicleJourney" })
    public static class MonitoredStopVisit {
        @JacksonXmlProperty(localName = "RecordedAtTime")
        private String recordedAtTime = now();
        @JacksonXmlProperty(localName = "ItemIdentifier")
        private String itemIdentifier;
        @JacksonXmlProperty(localName = "MonitoringRef")
        private String monitoringRef;
        @JacksonXmlProperty(localName = "MonitoredVehicleJourney")
        private MonitoredVehicleJourney monitoredVehicleJourney;

        public String getRecordedAtTime() { return recordedAtTime; }
        public void setRecordedAtTime(String v) { this.recordedAtTime = v; }
        public String getItemIdentifier() { return itemIdentifier; }
        public void setItemIdentifier(String v) { this.itemIdentifier = v; }
        public String getMonitoringRef() { return monitoringRef; }
        public void setMonitoringRef(String v) { this.monitoringRef = v; }
        public MonitoredVehicleJourney getMonitoredVehicleJourney() { return monitoredVehicleJourney; }
        public void setMonitoredVehicleJourney(MonitoredVehicleJourney v) { this.monitoredVehicleJourney = v; }
    }

    // ── VehicleActivity ────────────────────────────────────────────────────────
    // XSD: RecordedAtTime, ItemIdentifier?, ValidUntilTime, VehicleMonitoringRef?,
    //      ProgressBetweenStops?, MonitoredVehicleJourney, VehicleActivityNote*, Extensions?
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({ "recordedAtTime", "validUntilTime", "vehicleMonitoringRef",
            "monitoredVehicleJourney", "extensions" })
    public static class VehicleActivity {
        @JacksonXmlProperty(localName = "RecordedAtTime")
        private String recordedAtTime;

        /** Obbligatorio: fino a quando questa posizione va considerata attuale. */
        @JacksonXmlProperty(localName = "ValidUntilTime")
        private String validUntilTime;

        /** L'identificativo con cui il consumer può chiedere questo stesso mezzo. */
        @JacksonXmlProperty(localName = "VehicleMonitoringRef")
        private String vehicleMonitoringRef;

        @JacksonXmlProperty(localName = "MonitoredVehicleJourney")
        private MonitoredVehicleJourney monitoredVehicleJourney;

        /** Campi non standard: qui è la posizione ammessa dallo schema (non dentro MVJ). */
        @JacksonXmlProperty(localName = "Extensions")
        private Extensions extensions;

        public VehicleActivity() {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            this.recordedAtTime = now.toString();
            this.validUntilTime = now.plusSeconds(60).toString();
        }

        public String getRecordedAtTime() { return recordedAtTime; }
        public void setRecordedAtTime(String v) { this.recordedAtTime = v; }
        public String getValidUntilTime() { return validUntilTime; }
        public void setValidUntilTime(String v) { this.validUntilTime = v; }
        public String getVehicleMonitoringRef() { return vehicleMonitoringRef; }
        public void setVehicleMonitoringRef(String v) { this.vehicleMonitoringRef = v; }
        public MonitoredVehicleJourney getMonitoredVehicleJourney() { return monitoredVehicleJourney; }
        public void setMonitoredVehicleJourney(MonitoredVehicleJourney v) { this.monitoredVehicleJourney = v; }
        public Extensions getExtensions() { return extensions; }
        public void setExtensions(Extensions v) { this.extensions = v; }
    }

    // ── MonitoredVehicleJourney ────────────────────────────────────────────────
    // XSD MonitoredVehicleJourneyStructure, nell'ordine della sequence:
    //   LineRef, DirectionRef, FramedVehicleJourneyRef,
    //   [JourneyPatternInfo] VehicleMode, PublishedLineName, DirectionName,
    //   [VehicleJourneyInfo] OperatorRef,
    //   [JourneyEndNames] OriginRef, OriginName, DestinationRef, DestinationName,
    //   [JourneyInfo] OriginAimedDepartureTime, DestinationAimedArrivalTime,
    //   [MonitoredProgress] Monitored, VehicleLocation, Bearing, Velocity,
    //                       Occupancy, Delay, VehicleStatus,
    //   [MonitoredJourneyIdentity] VehicleRef,
    //   PreviousCalls, MonitoredCall, OnwardCalls
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({
            "lineRef", "directionRef", "framedVehicleJourneyRef",
            "vehicleMode", "publishedLineName", "directionName",
            "operatorRef",
            "originRef", "originName", "destinationRef", "destinationName",
            "originAimedDepartureTime", "destinationAimedArrivalTime",
            "monitored", "vehicleLocation", "bearing", "velocity",
            "occupancy", "delay", "vehicleStatus",
            "vehicleRef",
            "previousCalls", "monitoredCall", "onwardCalls"
    })
    public static class MonitoredVehicleJourney {

        /** La linea (routes.id, es. "LINEA_11I"). */
        @JacksonXmlProperty(localName = "LineRef")
        private String lineRef;

        /** outbound = andata, inbound = ritorno (le linee "_R"). */
        @JacksonXmlProperty(localName = "DirectionRef")
        private String directionRef;

        /** Riferimento strutturato alla corsa (data + ID corsa) */
        @JacksonXmlProperty(localName = "FramedVehicleJourneyRef")
        private FramedVehicleJourneyRef framedVehicleJourneyRef;

        @JacksonXmlProperty(localName = "VehicleMode")
        private String vehicleMode;

        /** Il numero di linea come lo legge il passeggero (routes.short_name). */
        @JacksonXmlProperty(localName = "PublishedLineName")
        private String publishedLineName;

        /** Il nome esteso della linea (routes.long_name). */
        @JacksonXmlProperty(localName = "DirectionName")
        private String directionName;

        @JacksonXmlProperty(localName = "OperatorRef")
        private String operatorRef;

        @JacksonXmlProperty(localName = "OriginRef")
        private String originRef;
        @JacksonXmlProperty(localName = "OriginName")
        private String originName;
        @JacksonXmlProperty(localName = "DestinationRef")
        private String destinationRef;
        @JacksonXmlProperty(localName = "DestinationName")
        private String destinationName;

        @JacksonXmlProperty(localName = "OriginAimedDepartureTime")
        private String originAimedDepartureTime;
        @JacksonXmlProperty(localName = "DestinationAimedArrivalTime")
        private String destinationAimedArrivalTime;

        /** true = i dati che seguono vengono dal mezzo, non dall'orario. */
        @JacksonXmlProperty(localName = "Monitored")
        private Boolean monitored;

        /** Posizione GPS */
        @JacksonXmlProperty(localName = "VehicleLocation")
        private VehicleLocation vehicleLocation;

        /** Direzione di marcia in gradi (0-359) */
        @JacksonXmlProperty(localName = "Bearing")
        private Double bearing;

        /** Velocità in metri al secondo, intero: così la vuole lo schema (Extensions/Velocity resta in km/h). */
        @JacksonXmlProperty(localName = "Velocity")
        private Integer velocity;

        /**
         * Stato di occupazione — enum SIRI 2.1:
         * empty | manySeatsAvailable | seatsAvailable | fewSeatsAvailable | standingAvailable | full
         */
        @JacksonXmlProperty(localName = "Occupancy")
        private String occupancy;

        /** Ritardo in formato durata ISO 8601 (PT0S, PT2M, -PT1M). */
        @JacksonXmlProperty(localName = "Delay")
        private String delay;

        /** inProgress | atOrigin | completed | offRoute | ... (VehicleStatusEnumeration). */
        @JacksonXmlProperty(localName = "VehicleStatus")
        private String vehicleStatus;

        /** Identificativo del veicolo */
        @JacksonXmlProperty(localName = "VehicleRef")
        private String vehicleRef;

        /** Fermate già percorse (ultima fermata registrata) */
        @JacksonXmlElementWrapper(localName = "PreviousCalls")
        @JacksonXmlProperty(localName = "PreviousCall")
        private List<PreviousCall> previousCalls;

        /** Fermata corrente/prossima */
        @JacksonXmlProperty(localName = "MonitoredCall")
        private MonitoredCall monitoredCall;

        /** Le fermate ancora da fare, con orario previsto e atteso. */
        @JacksonXmlElementWrapper(localName = "OnwardCalls")
        @JacksonXmlProperty(localName = "OnwardCall")
        private List<OnwardCall> onwardCalls;

        public String getLineRef() { return lineRef; }
        public void setLineRef(String v) { this.lineRef = v; }
        public String getDirectionRef() { return directionRef; }
        public void setDirectionRef(String v) { this.directionRef = v; }
        public FramedVehicleJourneyRef getFramedVehicleJourneyRef() { return framedVehicleJourneyRef; }
        public void setFramedVehicleJourneyRef(FramedVehicleJourneyRef v) { this.framedVehicleJourneyRef = v; }
        public String getVehicleMode() { return vehicleMode; }
        public void setVehicleMode(String v) { this.vehicleMode = v; }
        public String getPublishedLineName() { return publishedLineName; }
        public void setPublishedLineName(String v) { this.publishedLineName = v; }
        public String getDirectionName() { return directionName; }
        public void setDirectionName(String v) { this.directionName = v; }
        public String getOperatorRef() { return operatorRef; }
        public void setOperatorRef(String v) { this.operatorRef = v; }
        public String getOriginRef() { return originRef; }
        public void setOriginRef(String v) { this.originRef = v; }
        public String getOriginName() { return originName; }
        public void setOriginName(String v) { this.originName = v; }
        public String getDestinationRef() { return destinationRef; }
        public void setDestinationRef(String v) { this.destinationRef = v; }
        public String getDestinationName() { return destinationName; }
        public void setDestinationName(String v) { this.destinationName = v; }
        public String getOriginAimedDepartureTime() { return originAimedDepartureTime; }
        public void setOriginAimedDepartureTime(String v) { this.originAimedDepartureTime = v; }
        public String getDestinationAimedArrivalTime() { return destinationAimedArrivalTime; }
        public void setDestinationAimedArrivalTime(String v) { this.destinationAimedArrivalTime = v; }
        public Boolean getMonitored() { return monitored; }
        public void setMonitored(Boolean v) { this.monitored = v; }
        public VehicleLocation getVehicleLocation() { return vehicleLocation; }
        public void setVehicleLocation(VehicleLocation v) { this.vehicleLocation = v; }
        public Double getBearing() { return bearing; }
        public void setBearing(Double v) { this.bearing = v; }
        public Integer getVelocity() { return velocity; }
        public void setVelocity(Integer v) { this.velocity = v; }
        public String getOccupancy() { return occupancy; }
        public void setOccupancy(String v) { this.occupancy = v; }
        public String getDelay() { return delay; }
        public void setDelay(String v) { this.delay = v; }
        public String getVehicleStatus() { return vehicleStatus; }
        public void setVehicleStatus(String v) { this.vehicleStatus = v; }
        public String getVehicleRef() { return vehicleRef; }
        public void setVehicleRef(String v) { this.vehicleRef = v; }
        public List<PreviousCall> getPreviousCalls() { return previousCalls; }
        public void setPreviousCalls(List<PreviousCall> v) { this.previousCalls = v; }
        public MonitoredCall getMonitoredCall() { return monitoredCall; }
        public void setMonitoredCall(MonitoredCall v) { this.monitoredCall = v; }
        public List<OnwardCall> getOnwardCalls() { return onwardCalls; }
        public void setOnwardCalls(List<OnwardCall> v) { this.onwardCalls = v; }
    }

    // ── FramedVehicleJourneyRef ────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({ "dataFrameRef", "datedVehicleJourneyRef" })
    public static class FramedVehicleJourneyRef {
        /** Data di riferimento (es. "2026-06-26") */
        @JacksonXmlProperty(localName = "DataFrameRef")
        private String dataFrameRef;

        /** ID della corsa (tripId) */
        @JacksonXmlProperty(localName = "DatedVehicleJourneyRef")
        private String datedVehicleJourneyRef;

        public FramedVehicleJourneyRef() {}
        public FramedVehicleJourneyRef(String dataFrameRef, String datedVehicleJourneyRef) {
            this.dataFrameRef = dataFrameRef;
            this.datedVehicleJourneyRef = datedVehicleJourneyRef;
        }

        public String getDataFrameRef() { return dataFrameRef; }
        public void setDataFrameRef(String v) { this.dataFrameRef = v; }
        public String getDatedVehicleJourneyRef() { return datedVehicleJourneyRef; }
        public void setDatedVehicleJourneyRef(String v) { this.datedVehicleJourneyRef = v; }
    }

    // ── VehicleLocation ────────────────────────────────────────────────────────
    @JsonPropertyOrder({ "longitude", "latitude" })
    public static class VehicleLocation {
        @JacksonXmlProperty(localName = "Longitude")
        private double longitude;

        @JacksonXmlProperty(localName = "Latitude")
        private double latitude;

        public VehicleLocation() {}
        public VehicleLocation(double longitude, double latitude) {
            this.longitude = longitude;
            this.latitude = latitude;
        }

        public double getLongitude() { return longitude; }
        public void setLongitude(double v) { this.longitude = v; }
        public double getLatitude() { return latitude; }
        public void setLatitude(double v) { this.latitude = v; }
    }

    // ── MonitoredCall (fermata corrente/prossima) ──────────────────────────────
    // XSD MonitoredCallStructure: StopPointRef, VisitNumber?, Order?, StopPointName,
    //      VehicleAtStop, ..., AimedArrivalTime, ActualArrivalTime, ExpectedArrivalTime,
    //      ArrivalStatus, ..., AimedDepartureTime, ...
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({ "stopPointRef", "order", "stopPointName", "vehicleAtStop",
            "aimedArrivalTime", "expectedArrivalTime", "arrivalStatus", "aimedDepartureTime" })
    public static class MonitoredCall {
        @JacksonXmlProperty(localName = "StopPointRef")
        private String stopPointRef;

        /** Posizione della fermata nella corsa (stop_sequence). */
        @JacksonXmlProperty(localName = "Order")
        private Integer order;

        @JacksonXmlProperty(localName = "StopPointName")
        private String stopPointName;

        @JacksonXmlProperty(localName = "VehicleAtStop")
        private boolean vehicleAtStop = false;

        /** Orario di tabella. */
        @JacksonXmlProperty(localName = "AimedArrivalTime")
        private String aimedArrivalTime;

        /** Orario atteso, dalla posizione reale del mezzo. */
        @JacksonXmlProperty(localName = "ExpectedArrivalTime")
        private String expectedArrivalTime;

        /** onTime | early | delayed | noReport (CallStatusEnumeration). */
        @JacksonXmlProperty(localName = "ArrivalStatus")
        private String arrivalStatus;

        @JacksonXmlProperty(localName = "AimedDepartureTime")
        private String aimedDepartureTime;

        public MonitoredCall() {}
        public MonitoredCall(String stopPointRef, String stopPointName) {
            this.stopPointRef = stopPointRef;
            this.stopPointName = stopPointName;
        }

        public String getStopPointRef() { return stopPointRef; }
        public void setStopPointRef(String v) { this.stopPointRef = v; }
        public Integer getOrder() { return order; }
        public void setOrder(Integer v) { this.order = v; }
        public String getStopPointName() { return stopPointName; }
        public void setStopPointName(String v) { this.stopPointName = v; }
        public boolean isVehicleAtStop() { return vehicleAtStop; }
        public void setVehicleAtStop(boolean v) { this.vehicleAtStop = v; }
        public String getAimedArrivalTime() { return aimedArrivalTime; }
        public void setAimedArrivalTime(String v) { this.aimedArrivalTime = v; }
        public String getExpectedArrivalTime() { return expectedArrivalTime; }
        public void setExpectedArrivalTime(String v) { this.expectedArrivalTime = v; }
        public String getArrivalStatus() { return arrivalStatus; }
        public void setArrivalStatus(String v) { this.arrivalStatus = v; }
        public String getAimedDepartureTime() { return aimedDepartureTime; }
        public void setAimedDepartureTime(String v) { this.aimedDepartureTime = v; }
    }

    // ── OnwardCall (fermata a seguire) ─────────────────────────────────────────
    // XSD OnwardCallStructure: StopPointRef, VisitNumber?, Order?, StopPointName, ...,
    //      AimedArrivalTime, ExpectedArrivalTime, ArrivalStatus, ...
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({ "stopPointRef", "order", "stopPointName",
            "aimedArrivalTime", "expectedArrivalTime", "arrivalStatus" })
    public static class OnwardCall {
        @JacksonXmlProperty(localName = "StopPointRef")
        private String stopPointRef;
        @JacksonXmlProperty(localName = "Order")
        private Integer order;
        @JacksonXmlProperty(localName = "StopPointName")
        private String stopPointName;
        @JacksonXmlProperty(localName = "AimedArrivalTime")
        private String aimedArrivalTime;
        @JacksonXmlProperty(localName = "ExpectedArrivalTime")
        private String expectedArrivalTime;
        @JacksonXmlProperty(localName = "ArrivalStatus")
        private String arrivalStatus;

        public String getStopPointRef() { return stopPointRef; }
        public void setStopPointRef(String v) { this.stopPointRef = v; }
        public Integer getOrder() { return order; }
        public void setOrder(Integer v) { this.order = v; }
        public String getStopPointName() { return stopPointName; }
        public void setStopPointName(String v) { this.stopPointName = v; }
        public String getAimedArrivalTime() { return aimedArrivalTime; }
        public void setAimedArrivalTime(String v) { this.aimedArrivalTime = v; }
        public String getExpectedArrivalTime() { return expectedArrivalTime; }
        public void setExpectedArrivalTime(String v) { this.expectedArrivalTime = v; }
        public String getArrivalStatus() { return arrivalStatus; }
        public void setArrivalStatus(String v) { this.arrivalStatus = v; }
    }

    // ── PreviousCall (ultima fermata registrata) ───────────────────────────────
    // XSD: StopPointRef (obbligatorio), ..., StopPointName
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({ "stopPointRef", "order", "stopPointName" })
    public static class PreviousCall {
        @JacksonXmlProperty(localName = "StopPointRef")
        private String stopPointRef;

        @JacksonXmlProperty(localName = "Order")
        private Integer order;

        @JacksonXmlProperty(localName = "StopPointName")
        private String stopPointName;

        public PreviousCall() {}
        public PreviousCall(String stopPointRef, String stopPointName) {
            this.stopPointRef = stopPointRef;
            this.stopPointName = stopPointName;
        }

        public String getStopPointRef() { return stopPointRef; }
        public void setStopPointRef(String v) { this.stopPointRef = v; }
        public Integer getOrder() { return order; }
        public void setOrder(Integer v) { this.order = v; }
        public String getStopPointName() { return stopPointName; }
        public void setStopPointName(String v) { this.stopPointName = v; }
    }

    // ── Extensions (campi non standard) ───────────────────────────────────────
    // ExtensionsStructure ammette contenuto libero: qui trasportiamo dati fuori-standard.
    // Nomi e unità invariati: OmniMove li legge da qui.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({ "velocity", "numberOfSeats", "passengers", "wheelchairAccess" })
    public static class Extensions {
        /** Velocità in km/h */
        @JacksonXmlProperty(localName = "Velocity")
        private Double velocity;

        /** Numero totale di posti */
        @JacksonXmlProperty(localName = "NumberOfSeats")
        private Integer numberOfSeats;

        /** Numero di passeggeri a bordo */
        @JacksonXmlProperty(localName = "Passengers")
        private Integer passengers;

        /** Accessibilità sedia a rotelle (non è un elemento SIRI standard). */
        @JacksonXmlProperty(localName = "WheelchairAccess")
        private Boolean wheelchairAccess;

        public Extensions() {}

        public Double getVelocity() { return velocity; }
        public void setVelocity(Double v) { this.velocity = v; }
        public Integer getNumberOfSeats() { return numberOfSeats; }
        public void setNumberOfSeats(Integer v) { this.numberOfSeats = v; }
        public Integer getPassengers() { return passengers; }
        public void setPassengers(Integer v) { this.passengers = v; }
        public Boolean getWheelchairAccess() { return wheelchairAccess; }
        public void setWheelchairAccess(Boolean v) { this.wheelchairAccess = v; }
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
