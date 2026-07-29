package it.unicas.cassitrack.service;

/**
 * Unica fonte di verità per l'affollamento a bordo.
 *
 * Il livello (LOW…VERY_HIGH) e l'OccupancyStatus SIRI sono due vocabolari
 * diversi per lo stesso rapporto passeggeri/capienza. Derivano entrambi da
 * ratio(), così non possono divergere.
 *
 * Metodi statici: SiriMapper è una classe di utilità e non riceve iniezioni.
 */
public final class CrowdingService {

    private CrowdingService() {}

    // Confini SIRI standard
    private static final double EMPTY               = 0.20;
    private static final double MANY_SEATS          = 0.50;
    private static final double SEATS               = 0.65;
    private static final double FEW_SEATS           = 0.80;
    private static final double STANDING            = 0.95;

    private static final double BLE_CALIBRATION = 0.6;

    /**
     * Passeggeri effettivi: conteggio diretto se l'unità ne ha uno,
     * altrimenti stima dal numero di dispositivi Bluetooth visti.
     * null = nessuna delle due fonti disponibile.
     */
    public static Integer effectivePassengers(Integer passengers, Integer bleDeviceCount) {
        if (passengers != null)     return passengers;
        if (bleDeviceCount != null) return (int) (bleDeviceCount * BLE_CALIBRATION);
        return null;
    }

    /** Frazione di riempimento, o null se non calcolabile. */
    private static Double ratio(Integer passengers, Integer capacity) {
        if (passengers == null || capacity == null || capacity == 0) return null;
        return (double) passengers / capacity;
    }

    /** LOW | MEDIUM | HIGH | VERY_HIGH, oppure null se sconosciuto. */
    public static String levelFromRatio(Integer passengers, Integer capacity) {
        Double r = ratio(passengers, capacity);
        if (r == null)          return null;
        if (r < MANY_SEATS)     return "LOW";
        if (r < SEATS)          return "MEDIUM";
        if (r < STANDING)       return "HIGH";
        return "VERY_HIGH";
    }

    /**
     * OccupancyStatus SIRI:
     * empty | manySeatsAvailable | seatsAvailable | fewSeatsAvailable |
     * standingAvailable | full
     */
    public static String siriOccupancy(Integer passengers, Integer capacity) {
        Double r = ratio(passengers, capacity);
        if (r == null)      return null;
        if (r < EMPTY)      return "empty";
        if (r < MANY_SEATS) return "manySeatsAvailable";
        if (r < SEATS)      return "seatsAvailable";
        if (r < FEW_SEATS)  return "fewSeatsAvailable";
        if (r < STANDING)   return "standingAvailable";
        return "full";
    }

    /** Percentuale di riempimento arrotondata, per la barra della dashboard. */
    public static Integer occupancyPct(Integer passengers, Integer capacity) {
        Double r = ratio(passengers, capacity);
        return r == null ? null : (int) Math.round(r * 100);
    }
}