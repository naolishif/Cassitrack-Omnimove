package it.unicas.omnimove.client;

import it.unicas.omnimove.dto.BikeVehicleDTO;
import it.unicas.omnimove.dto.BikeZoneDTO;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to a shared-mobility provider (bike/scooter positions
 * and operating zones). Implementations:
 *
 *  - {@link MockElerentClient}  — simulated Elerent fleet (elerent.api.mock=true)
 *  - {@link RideAtomClient}     — real ATOM Mobility / RideAtom API, needs App-Public-Key
 *
 * A future GBFS implementation can plug in here without touching
 * service, controller or frontend.
 */
public interface BikeSharingClient {

    /**
     * Available vehicles around the given point. Never throws.
     *
     * An empty Optional means the provider could not be reached; an empty list
     * means it answered that nothing is available. The caller must keep the two
     * apart — one is a fault to report, the other is the truth to show.
     */
    Optional<List<BikeVehicleDTO>> getVehicles(double lat, double lon, int radiusKm);

    /** Operating/parking zones. Never throws — empty list on failure. */
    List<BikeZoneDTO> getZones();
}
