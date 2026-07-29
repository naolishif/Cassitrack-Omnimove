package it.unicas.cassitrack.dto.siri;

import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.service.CrowdingService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class SiriMapper {

    /**
     * Costruisce un pacchetto SIRI standard dalla cache Redis (VehicleStateCache).
     * Unico punto di ingresso per la produzione di SIRI in CassiTrack.
     */
    public static Siri toSiriFromCache(Collection<VehiclePosition> vehicles) {
        List<Siri.VehicleActivity> activities = new ArrayList<>();
        String today = LocalDate.now().toString(); // "2026-06-26"

        for (VehiclePosition v : vehicles) {

            // ── VehicleLocation ───────────────────────────────────────────────
            Siri.VehicleLocation location = new Siri.VehicleLocation(
                    v.getLon() != null ? v.getLon() : 0.0,
                    v.getLat() != null ? v.getLat() : 0.0);

            // ── FramedVehicleJourneyRef ───────────────────────────────────────
            Siri.FramedVehicleJourneyRef journeyRef = null;
            if (v.getTripId() != null) {
                journeyRef = new Siri.FramedVehicleJourneyRef(today, v.getTripId());
            }

            // ── Occupancy (calcolato da passeggeri/capacità) ──────────────────
            String occupancy = computeOccupancy(v.getPassengers(), v.getCapacity());

            // ── Delay in formato ISO 8601 ─────────────────────────────────────
            String delay = null;
            if (v.getDelayMinutes() != null) {
                int d = v.getDelayMinutes();
                delay = d == 0 ? "PT0S" : (d > 0 ? "PT" + d + "M" : "-PT" + Math.abs(d) + "M");
            }

            // ── MonitoredCall (prossima fermata) ──────────────────────────────
            // StopPointRef = ID reale della fermata (codice pulito, es. "SFF").
            // Fallback: codice derivato dal nome se l'ID non è disponibile.
            Siri.MonitoredCall monitoredCall = null;
            if (v.getNextStop() != null) {
                String nextRef = v.getNextStopId() != null
                        ? v.getNextStopId() : toStopCode(v.getNextStop());
                monitoredCall = new Siri.MonitoredCall(nextRef, v.getNextStop());
            }

            // ── PreviousCalls (ultima fermata registrata) ─────────────────────
            List<Siri.PreviousCall> previousCalls = null;
            if (v.getLastStopRegistered() != null) {
                String prevRef = v.getLastStopRegisteredId() != null
                        ? v.getLastStopRegisteredId() : toStopCode(v.getLastStopRegistered());
                previousCalls = List.of(new Siri.PreviousCall(prevRef, v.getLastStopRegistered()));
            }

            // ── Extensions (campi non standard, incl. accessibilità) ──────────
            Siri.Extensions extensions = new Siri.Extensions();
            extensions.setVelocity(v.getSpeedKmh());
            extensions.setNumberOfSeats(v.getNumeroPosti());
            extensions.setPassengers(v.getPassengers());
            extensions.setWheelchairAccess(v.getWheelchairAccessible());

            // ── MonitoredVehicleJourney ───────────────────────────────────────
            Siri.MonitoredVehicleJourney journey = new Siri.MonitoredVehicleJourney();
            journey.setVehicleRef(v.getVehicleId());
            journey.setFramedVehicleJourneyRef(journeyRef);
            journey.setVehicleLocation(location);
            journey.setBearing(v.getHeadingDeg());
            journey.setOccupancy(occupancy);
            journey.setDelay(delay);
            journey.setMonitoredCall(monitoredCall);
            journey.setPreviousCalls(previousCalls);

            // ── VehicleActivity ───────────────────────────────────────────────
            Siri.VehicleActivity activity = new Siri.VehicleActivity();
            if (v.getTimestamp() != null) {
                java.time.Instant rec = v.getTimestamp().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                activity.setRecordedAtTime(rec.toString());
                // ValidUntilTime obbligatorio: orizzonte di validità di 60s dal recorded.
                activity.setValidUntilTime(rec.plusSeconds(60).toString());
            }
            activity.setMonitoredVehicleJourney(journey);
            activity.setExtensions(extensions);   // Extensions vive a livello VehicleActivity (non MVJ)
            activities.add(activity);
        }

        Siri.VehicleMonitoringDelivery vmd = new Siri.VehicleMonitoringDelivery();
        vmd.setVehicleActivity(activities);

        Siri.ServiceDelivery sd = new Siri.ServiceDelivery();
        sd.setVehicleMonitoringDelivery(vmd);

        return new Siri(sd);
    }

    /**
     * Converte un nome/etichetta di fermata in un codice valido per StopPointCodeType.
     * StopPointCodeType non ammette spazi: teniamo solo caratteri alfanumerici.
     * Es. "Staz. FF.SS." → "StazFFSS", "Via Garigliano" → "ViaGarigliano".
     */
    private static String toStopCode(String s) {
        if (s == null) return null;
        String code = s.replaceAll("[^A-Za-z0-9]", "");
        return code.isEmpty() ? "UNKNOWN" : code;
    }

    /**
     * Calcola l'OccupancyStatus SIRI dal rapporto passeggeri/capacità.
     * Valori standard: empty | manySeatsAvailable | seatsAvailable |
     *                  fewSeatsAvailable | standingAvailable | full
     */
    private static String computeOccupancy(Integer passengers, Integer capacity) {
        return CrowdingService.siriOccupancy(passengers, capacity);
    }
}
