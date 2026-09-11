package it.unicas.omnimove.dto;

import lombok.Data;
import java.util.List;

@Data
public class ChatRequest {
    private String message;
    private String language = "en";

    /**
     * Previous messages in this conversation (oldest first).
     * Each item is {role: "user"|"assistant", content: "..."}.
     * The frontend sends the running history so the AI has memory
     * across turns. Optional — if null, treated as a fresh conversation.
     */
    private List<ChatTurn> history;

    /**
     * What the traveller has on screen when they ask.
     *
     * "When is the next bus?" has no answer without a stop, and "how do I get
     * to the campus?" none without a starting point. The page knows both; the
     * assistant did not, so it either guessed or answered in general terms.
     * Optional: absent means the questions have to be asked back.
     */
    private ChatContext context;

    @Data
    public static class ChatContext {
        /** Origin and destination currently in the search fields, if any. */
        private String originName;
        private String destName;
        /**
         * Where the origin actually is. The name alone cannot rank the shared
         * fleet: "Map point - 41.4901, 13.8305" is not a place the assistant
         * can look up, and neither is "My location". Sent whenever the field
         * resolves to a coordinate, whatever the traveller picked it from.
         */
        private Double originLat;
        private Double originLon;
        /** The stop whose arrivals panel is open, if any. */
        private String stopId;
        private String stopName;

        /**
         * The itinerary the traveller has started, while one is running.
         *
         * Asked "where is the bus?" from the stop they are waiting at, the
         * assistant had the whole network in front of it — every line, every
         * stop, every bus on the road — and nothing saying which of it was this
         * traveller's, so it answered with whichever line came to hand. The
         * journey they pressed Start on is the subject of every question they
         * ask while it runs, and this is how it gets told.
         */
        private ActiveJourney journey;
    }

    /**
     * One started itinerary, as the page has it.
     *
     * The ids matter as much as the names: an arrival at a stop carries the trip
     * it belongs to, so the run named here is the very bus this traveller is
     * waiting for rather than the next one wearing the same number.
     */
    @Data
    public static class ActiveJourney {
        /** BUS, BIKE, SCOOTER or WALK — the mode of the itinerary as a whole. */
        private String mode;
        /** The label on the card they chose, e.g. "16 -> Ospedale". */
        private String label;
        private String originName;
        private String destName;
        /** How long it was planned to take, and what the counter on screen reads now. */
        private Integer durationMinutes;
        private Integer minutesLeft;
        /** Where they board and which run they ride, for the first bus of the trip. */
        private String boardingStopId;
        private String boardingTripId;
        private String alightStopId;
        /** The interchange and the run boarded there, on a journey with a change. */
        private String transferStopId;
        private String transferTripId;
        /** The bus legs in travel order. Empty on a journey with no bus. */
        private List<BusLeg> busLegs;
    }

    /** A bus leg of the started journey: which line, between which stops. */
    @Data
    public static class BusLeg {
        private String routeId;
        private String fromStopName;
        private String toStopName;
    }

    @Data
    public static class ChatTurn {
        private String role;     // "user" or "assistant"
        private String content;
    }
}
