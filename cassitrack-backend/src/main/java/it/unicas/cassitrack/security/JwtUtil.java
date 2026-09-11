package it.unicas.cassitrack.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration-ms}")
    private long jwtExpirationMs;

    /** Quanta inattivita' si tollera: la soglia che l'utente percepisce. */
    @Value("${jwt.idle-ms}")
    private long idleMs;

    /** Quanto puo' durare in tutto una sessione, per quanto la si usi. */
    @Value("${jwt.session-max-ms}")
    private long sessionMaxMs;

    /**
     * Istante in cui e' cominciata la SESSIONE, non questo token.
     *
     * E' cio' che distingue una finestra scorrevole da una sessione eterna.
     * Il rinnovo ricopia questo valore invariato, quindi per quanti token si
     * susseguano il tetto resta ancorato al primo accesso.
     */
    public static final String SESSION_START_CLAIM = "sst";

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    // EXISTING METHOD: Used during Login
    public String generateToken(Authentication authentication) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();
        return generateTokenFromUsername(userPrincipal.getUsername());
    }

    // NEW METHOD: Used during Registration
    public String generateTokenFromUsername(String username) {
        return mint(username, System.currentTimeMillis());
    }

    /**
     * Un token nuovo per una sessione che continua.
     *
     * Stesso utente, stesso inizio di sessione, scadenza spostata avanti. Chi
     * chiama deve revocare il precedente: due token vivi per la stessa sessione
     * significherebbero che il logout ne spegne uno solo.
     */
    public String renew(String token) {
        return mint(getUsernameFromToken(token), sessionStartedAt(token));
    }

    private String mint(String username, long sessionStart) {
        return Jwts.builder()
                .setSubject(username)
                .claim(SESSION_START_CLAIM, sessionStart)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Quando e' cominciata la sessione a cui questo token appartiene.
     *
     * I token emessi PRIMA di questa modifica non hanno il claim: si ripiega
     * sulla data di emissione, che per loro coincide con l'inizio sessione.
     * Senza il ripiego, al primo riavvio dopo il rilascio tutti gli utenti
     * collegati si troverebbero fuori.
     *
     * Token illeggibile -> 0, cioe' una sessione infinitamente vecchia: il
     * tetto risulta superato e non si rinnova nulla. In caso di dubbio si
     * chiude, non si prolunga.
     */
    public long sessionStartedAt(String token) {
        try {
            Claims claims = Jwts.parser()
                    .setSigningKey(getSigningKey())
                    .parseClaimsJws(token).getBody();
            Object sst = claims.get(SESSION_START_CLAIM);
            if (sst instanceof Number n) return n.longValue();
            Date issuedAt = claims.getIssuedAt();
            return issuedAt != null ? issuedAt.getTime() : 0L;
        } catch (JwtException | IllegalArgumentException e) {
            return 0L;
        }
    }

    /**
     * Al token resta meno della tolleranza di inattivita' promessa.
     *
     * LA SOGLIA E' idle-ms, NON META' DELLA VITA DEL TOKEN. Con la meta' la
     * promessa non regge: una richiesta fatta poco prima di quel punto non
     * rinnova, e da li' all'utente resterebbe poco piu' di meta' vita prima di
     * essere disconnesso — la tolleranza reale oscillerebbe fra meta' e vita
     * intera invece di essere garantita. Rinnovando appena si scende sotto
     * idle-ms, dopo QUALSIASI richiesta il token ne ha almeno altrettanti
     * davanti, e la soglia scritta nella configurazione e' quella vera.
     *
     * Non si rinnova a ogni richiesta perche' vorrebbe dire firmare un token
     * per ognuna delle decine che una dashboard fa al minuto, e riscrivere il
     * cookie ogni volta.
     */
    public boolean shouldRenew(String token) {
        return getRemainingValidityMs(token) < idleMs;
    }

    /** La sessione e' ancora dentro il tetto: si puo' rinnovare. */
    public boolean withinSessionCap(String token) {
        return System.currentTimeMillis() - sessionStartedAt(token) < sessionMaxMs;
    }

    public long getSessionMaxMs() {
        return sessionMaxMs;
    }

    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parser()
                .setSigningKey(getSigningKey())
                .parseClaimsJws(token)
                .getBody();

        return claims.getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .setSigningKey(getSigningKey())
                    .parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public long getExpirationMs() {
        return jwtExpirationMs;
    }

    public long getRemainingValidityMs(String token) {
        try {
            java.util.Date expiry = Jwts.parser()
                    .setSigningKey(getSigningKey())
                    .parseClaimsJws(token).getBody().getExpiration();
            return Math.max(0, expiry.getTime() - System.currentTimeMillis());
        } catch (JwtException | IllegalArgumentException e) {
            return 0;
        }
    }
}