package it.unicas.omnimove.dto;

import lombok.Data;

/** The ID token Google Identity Services hands to the browser. */
@Data
public class GoogleAuthRequest {
    private String credential;
}
