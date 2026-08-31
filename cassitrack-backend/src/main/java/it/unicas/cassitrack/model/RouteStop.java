package it.unicas.cassitrack.model;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Una fermata nella sequenza di una linea — il JourneyPattern di NeTEx.
 *
 * Risponde a QUALI fermate e in CHE ORDINE. Il QUANDO sta in
 * {@link ScheduledStop}, una riga per corsa, così che la corsa del rientro da
 * scuola possa impiegare 22 minuti dove quella serale ne usa 18.
 *
 * Prima di V27 questa entità non esisteva e la sequenza viveva duplicata in
 * ogni corsa: 1884 copie per 142 posizioni reali, senza nulla che impedisse a
 * due corse della stessa linea di divergere.
 */
@Entity
@Table(name = "route_stops")
@IdClass(RouteStop.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteStop {

    /**
     * Chiave composta (linea, posizione): una posizione della sequenza ospita
     * una fermata sola. Non c'è una chiave surrogata perché non servirebbe a
     * nulla — non esiste modo di riferire una riga di pattern se non dicendo
     * di quale linea e in che posizione.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String  routeId;
        private Integer stopSequence;
    }

    @Id
    @Column(name = "route_id", nullable = false)
    private String routeId;

    /** 1-based, come in {@link ScheduledStop#getStopSequence()}. */
    @Id
    @Column(name = "stop_sequence", nullable = false)
    private Integer stopSequence;

    /**
     * Colonna semplice, non relazione JPA: è la stessa scelta già fatta in
     * ScheduledStop, e tenerle coerenti evita che due parti del codice
     * navighino la stessa cosa in due modi diversi.
     */
    @Column(name = "stop_id", nullable = false)
    private String stopId;

    /**
     * Scarto dalla partenza usato SOLO per proporre un orario: precompilare una
     * corsa nuova, o dare un tempo a una fermata appena inserita nel pattern.
     *
     * Non è autoritativo. Modificarlo non ri-tempifica le corse esistenti —
     * altrimenti si rientrerebbe dalla finestra in quell'uniformità fra corse
     * che avere gli orari per corsa serve proprio a evitare.
     */
    @Column(name = "default_offset_seconds", nullable = false)
    private Integer defaultOffsetSeconds;
}
