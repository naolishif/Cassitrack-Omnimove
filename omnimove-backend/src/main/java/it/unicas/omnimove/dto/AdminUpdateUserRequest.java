package it.unicas.omnimove.dto;

import lombok.Data;

/** Identity fields an admin may correct on someone else's account. */
@Data
public class AdminUpdateUserRequest {
    private String name;
    private String email;
}
