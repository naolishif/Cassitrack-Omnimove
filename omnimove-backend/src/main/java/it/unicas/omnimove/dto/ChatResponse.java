package it.unicas.omnimove.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class ChatResponse {
    private String answer;
    private boolean success;
    private String error;

    /**
     * The language the AI actually detected and answered in
     * ("en" or "it"). Lets the frontend update its language toggle
     * to match what the user typed.
     */
    private String detectedLanguage;

    /**
     * Optional follow-up suggestions the user might tap next.
     * e.g. ["When is the next bus?", "Is it crowded?"]
     */
    private List<String> suggestions;

    /**
     * What the assistant should do on the traveller's behalf, or null — which is
     * the normal case. Present only when the traveller agreed to it in the
     * conversation. See {@link AiAction}.
     */
    private AiAction action;
}
