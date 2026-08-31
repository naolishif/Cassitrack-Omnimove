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
    }

    @Data
    public static class ChatTurn {
        private String role;     // "user" or "assistant"
        private String content;
    }
}
