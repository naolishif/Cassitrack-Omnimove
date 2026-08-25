package it.unicas.omnimove.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "routes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Route {

    @Id
    @Column(name = "id", length = 255)
    private String id; // Es: "LINEA-16"

    private String longName;
    private String shortName;

    private boolean active;

    /**
     * Line colour and label colour as published by CassiTrack, 6-digit hex
     * without '#'. Null when the feed carries none — the traveller map then
     * falls back to a colour generated from the line's short name.
     */
    @Column(name = "color", length = 6)
    private String color;

    @Column(name = "text_color", length = 6)
    private String textColor;


    // Se nel database la tabella routes ha altre colonne (es. name, type),
    // puoi aggiungerle qui sotto come semplici variabili. Per ora lasciamo solo l'ID.
}