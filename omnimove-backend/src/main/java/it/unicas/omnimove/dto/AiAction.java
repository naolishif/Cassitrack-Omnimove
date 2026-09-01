package it.unicas.omnimove.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Something the assistant has been asked to do, rather than say.
 *
 * <p>The model writes prose for the traveller and, when the traveller has
 * actually agreed to something, one machine-readable line at the end of it. The
 * service lifts that line out, checks it and sends it here; the prose the
 * traveller reads never contains it.
 *
 * <p>Everything is advisory except {@link #start}. Filling the search fields and
 * running a search are undoable in one tap and cost the traveller nothing.
 * Starting a journey is different — it records the trip, and it is the one thing
 * here the assistant may only do when the traveller has said so in as many
 * words. {@link #isStartable()} is where that is enforced rather than trusted.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiAction {

    /** Stop name to put in the origin field, "GPS" for the traveller's own position, or null to leave it. */
    private String from;

    /** Stop name to put in the destination field, "GPS", or null to leave it. */
    private String to;

    /**
     * Which of the returned options to recommend: FAST, CHEAP, ECO or CUSTOM —
     * the four rankings the app already offers. Null when the traveller only
     * asked for the search itself.
     */
    private String pick;

    /**
     * The other end of the same criterion: the slowest rather than the fastest,
     * the least green rather than the greenest.
     *
     * <p>It reads like a joke until someone asks for it seriously — the scenic
     * way home, the cheapest-but-slowest when there is no hurry, the option a
     * profile ranks last to see what it is rejecting. One flag rather than four
     * more names for the criteria.
     */
    private boolean worst;

    /** Restrict the recommendation to BUS, BIKE, SCOOTER or WALK. Null means any. */
    private String mode;

    /** True only when the traveller has agreed the assistant may start it for them. */
    private boolean start;

    /**
     * Whether this action asks for anything at all.
     *
     * <p>A line with no endpoints and no criterion is a model that emitted the
     * marker out of habit, and running a search on it would replan whatever
     * happened to be in the fields.
     */
    public boolean isUseful() {
        return from != null || to != null || pick != null;
    }

    /**
     * Start Journey is only ever pressed on an option that was named.
     *
     * <p>"Start it for me" with nothing chosen is not a choice the assistant can
     * make on somebody's behalf: without a criterion there is no way to say
     * which of the returned itineraries was agreed to.
     */
    public boolean isStartable() {
        return start && pick != null;
    }
}
