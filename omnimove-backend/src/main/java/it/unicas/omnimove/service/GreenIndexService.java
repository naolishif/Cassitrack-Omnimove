package it.unicas.omnimove.service;

import org.springframework.stereotype.Service;

/**
 * Computes the Green Index for each journey option.
 *
 * Green Index = 0 to 100
 *   100 = zero emissions (walking, cycling)
 *   0   = maximum emissions (private car solo)
 *
 * Based on CO₂ emission factors from the
 * European Environment Agency (EEA):
 *
 *   Walking:     0    gCO₂/km
 *   Cycling:     0    gCO₂/km
 *   Urban bus:   68   gCO₂/passenger-km
 *   E-scooter:   0    gCO₂/km (zero-emission, fully green)
 *   Private car: 170  gCO₂/km (average EU)
 *
 * Formula:
 *   CO₂ = emission_factor × distance_km
 *   Green Index = 100 - (CO₂ / MAX_CO2 × 100)
 *   where MAX_CO2 = private car over same distance
 *
 * Required by CINI challenge specification
 * FR-OM-002 and the competition evaluation criteria.r
 */
@Service
public class GreenIndexService {

     // EEA emission factors in gCO₂ per passenger-km
     private static final double CO2_WALK     = 0.0;
     private static final double CO2_BIKE     = 0.0;
     private static final double CO2_BUS      = 68.0;
     private static final double CO2_SCOOTER  = 0.0;
     private static final double CO2_CAR      = 170.0;

    /**
     * Compute Green Index for a journey.
     *
     * @param mode          Transport mode (BUS, WALK, etc.)
     * @param distanceKm    Journey distance in kilometres
     * @return              Green Index 0-100
     */
    public int computeGreenIndex(
            String mode, double distanceKm) {

        double co2 = computeCo2Grams(mode, distanceKm);
        double maxCo2 = CO2_CAR * distanceKm;

        if (maxCo2 == 0) return 100;

        // Invert: lower CO₂ = higher Green Index
        double index = 100.0 - (co2 / maxCo2 * 100.0);
        return (int) Math.max(0, Math.min(100, index));
    }

    /**
     * Compute actual CO₂ emissions in grams.
     *
     * @param mode          Transport mode
     * @param distanceKm    Distance in kilometres
     * @return              CO₂ in grams
     */
    /**
     * CO₂ of a journey broken down by mode: each mode's kilometres are charged at
     * its own factor. This is the only way a combined trip can be scored honestly
     * — by mode alone the whole distance has to be charged to the dirtiest leg,
     * which turned a bus-and-scooter journey into a bus journey.
     */
    public double computeCo2Grams(java.util.Map<String, Double> kilometresByMode) {
        double total = 0;
        for (var e : kilometresByMode.entrySet()) {
            total += computeCo2Grams(e.getKey(), e.getValue());
        }
        return total;
    }

    /**
     * The Green Index of a journey whose emissions are already known.
     *
     * <p>A combined trip cannot be scored by mode: its bus kilometres pollute and
     * its bike or scooter kilometres do not, and only the itinerary knows the
     * split. Scoring it by the bus leg alone left the clean kilometres out of the
     * denominator and made the journey look dirtier than it was.
     */
    public int greenIndexFor(double co2Grams, double distanceKm) {
        double maxCo2 = CO2_CAR * distanceKm;
        if (maxCo2 <= 0) return 100;
        double index = 100.0 - (co2Grams / maxCo2 * 100.0);
        return (int) Math.max(0, Math.min(100, index));
    }

    public double computeCo2Grams(
            String mode, double distanceKm) {

        // A combined journey arrives as BUS_BIKE or SCOOTER_BUS, and how its
        // kilometres split between the pieces does not travel with the request.
        // It is scored on its most polluting leg: crediting the greener half of a
        // chain we cannot measure would let a bus ride dressed as a combination
        // outscore the same bus ride taken plainly.
        double factor = 0;
        for (String part : mode.toUpperCase().split("_")) {
            factor = Math.max(factor, switch (part) {
                case "WALK"    -> CO2_WALK;
                case "BIKE"    -> CO2_BIKE;
                case "BUS"     -> CO2_BUS;
                case "SCOOTER" -> CO2_SCOOTER;
                case "CAR"     -> CO2_CAR;
                default        -> CO2_BUS;
            });
        }

        return factor * distanceKm;
    }

    /**
     * Get a human readable label for the Green Index.
     */
    public String getGreenLabel(int index) {
        if (index >= 90) return "Excellent ♻️";
        if (index >= 70) return "Good 🌿";
        if (index >= 50) return "Moderate 🌱";
        if (index >= 30) return "Poor ⚠️";
        return "Very Poor 🔴";
    }
}