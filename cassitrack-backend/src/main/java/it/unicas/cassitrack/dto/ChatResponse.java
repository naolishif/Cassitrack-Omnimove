package it.unicas.cassitrack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * What the chat endpoint returns to the frontend.
 *
 * Example:
 * {
 *   "answer": "Yes — BUS-101 is near Cassino Centro
 *              and will reach Campus in about 8 minutes.",
 *   "success": true
 * }
 */
@Data
@Builder
public class ChatResponse {

    /** The AI's natural language answer */
    private String answer;

    /** True if the AI responded successfully */
    private boolean success;

    /**
     * Error message if something went wrong.
     * Null when success is true.
     */
    private String error;
}