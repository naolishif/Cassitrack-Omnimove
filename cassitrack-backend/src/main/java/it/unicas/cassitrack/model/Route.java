package it.unicas.cassitrack.model;
import jakarta.persistence.*;
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
    private String id;

    private String longName;
    private String shortName;

    private boolean active;

    @Column(name = "color")
    private String color;

    /**
     * Label colour for this line's badge, 6-digit hex without '#'.
     *
     * The column has been in the schema since V1 but was never mapped, so the
     * value could only be set from a migration. It is mapped now because the
     * NeTEx feed publishes it: a line colour is only usable downstream if the
     * consumer also knows whether to write white or black on top of it.
     */
    @Column(name = "text_color")
    private String textColor;

    // Se nel database la tabella routes ha altre colonne (es. name, type),
    // puoi aggiungerle qui sotto come semplici variabili. Per ora lasciamo solo l'ID.
}