package it.unicas.cassitrack.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * L'unico punto che scrive il cookie di sessione.
 *
 * PERCHE' ESISTE
 * Il formato era battuto a mano dentro AuthController, in due punti gia'
 * diversi fra loro (emissione e scadenza). Da quando anche il filtro rinnova il
 * token servirebbe una terza copia, e tre stringhe da tenere allineate a mano
 * non restano allineate: basta che una dimentichi SameSite o il flag Secure
 * perche' il cookie di sessione diventi leggibile dove non deve.
 *
 * E' il gemello di SessionService in OmniMove, che nasce dalla stessa esigenza.
 *
 * GLI ATTRIBUTI, E PERCHE'
 *   HttpOnly          JavaScript non lo legge: un XSS non porta via la sessione
 *   SameSite=Strict   non viaggia su richieste partite da altri siti (CSRF)
 *   Secure            solo su HTTPS. Spento in sviluppo e sul server senza TLS,
 *                     da accendere con COOKIE_SECURE=true appena c'e' Nginx
 *   Max-Age           uguale alla vita del token: le due scadenze non devono
 *                     poter divergere
 */
@Component
public class SessionCookie {

    public static final String NAME = "cassitrack_jwt";

    @Value("${cassitrack.cookie.secure:false}")
    private boolean secure;

    /** Consegna al browser un cookie per un token appena emesso. */
    public void issue(HttpServletResponse response, String token, long lifetimeMs) {
        response.setHeader("Set-Cookie", String.format(
                "%s=%s; Path=/; Max-Age=%d; HttpOnly%s; SameSite=Strict",
                NAME, token, (int) (lifetimeMs / 1000), secure ? "; Secure" : ""));
    }

    /**
     * Fa scadere subito il cookie.
     *
     * Gli attributi vanno ripetuti identici: un browser considera due cookie
     * con Path diverso come cookie diversi, e quello vecchio sopravviverebbe
     * alla cancellazione.
     */
    public void expire(HttpServletResponse response) {
        response.setHeader("Set-Cookie", String.format(
                "%s=; Path=/; Max-Age=0; HttpOnly%s; SameSite=Strict",
                NAME, secure ? "; Secure" : ""));
    }
}
