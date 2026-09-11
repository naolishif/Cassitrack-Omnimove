package it.unicas.omnimove.service;

/**
 * Quanto e' carico un mezzo, dedotto da passeggeri e capienza.
 *
 * PERCHE' ESISTE UNA COPIA
 * Le stesse soglie stanno in CassiTrack (CrowdingService). Non e' una svista:
 * i due servizi non condividono codice ne' database, comunicano solo via REST e
 * SIRI, ed e' quella separazione a permettere di distribuirli e aggiornarli
 * separatamente. Importare la classe dell'altro significherebbe rinunciarci.
 *
 * Il feed SIRI porta gia' un OccupancyStatus calcolato da CassiTrack, ma lo
 * porta solo per il mezzo nel suo insieme: il flusso che alimenta la mappa
 * passa da Redis (bus:latest:*), dove restano il conteggio e i posti e non il
 * livello. Ricavarlo qui evita di allargare quel formato e di doverne
 * ripopolare la cache a ogni cambio.
 *
 * LE SOGLIE DEVONO RESTARE UGUALI a quelle di CassiTrack: lo stesso mezzo che
 * sulla dashboard dell'azienda risulta "HIGH" non puo' risultare "MEDIUM" al
 * viaggiatore. Se una delle due cambia, va cambiata anche l'altra.
 */
public final class CrowdingLevels {

    private CrowdingLevels() {}

    // Identiche a it.unicas.cassitrack.service.CrowdingService
    private static final double MANY_SEATS = 0.50;
    private static final double SEATS      = 0.65;
    private static final double STANDING   = 0.95;

    /** Frazione di riempimento, o null se una delle due grandezze manca. */
    private static Double ratio(Integer passengers, Integer capacity) {
        if (passengers == null || capacity == null || capacity == 0) return null;
        return (double) passengers / capacity;
    }

    /** LOW | MEDIUM | HIGH | VERY_HIGH, oppure null se sconosciuto. */
    public static String level(Integer passengers, Integer capacity) {
        Double r = ratio(passengers, capacity);
        if (r == null)      return null;
        if (r < MANY_SEATS) return "LOW";
        if (r < SEATS)      return "MEDIUM";
        if (r < STANDING)   return "HIGH";
        return "VERY_HIGH";
    }

    /**
     * Percentuale di riempimento arrotondata, o null se sconosciuta.
     *
     * Limitata a 100 perche' e' il riempimento di una barra: un autobus puo'
     * davvero portare piu' persone dei posti dichiarati — i posti in piedi non
     * sono contati in numero_posti — ma una barra al 130% non si disegna, e
     * oltre il pieno la risposta utile e' comunque "pieno".
     */
    public static Integer pct(Integer passengers, Integer capacity) {
        Double r = ratio(passengers, capacity);
        return r == null ? null : Math.min(100, (int) Math.round(r * 100));
    }
}
