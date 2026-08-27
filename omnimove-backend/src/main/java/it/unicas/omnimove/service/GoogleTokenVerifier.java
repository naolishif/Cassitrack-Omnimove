package it.unicas.omnimove.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Verifies the ID token that Google Identity Services hands to the browser.
 *
 * WHY NOT google-api-client
 * The whole job is: check an RS256 signature against Google's published keys,
 * then check four claims. jjwt already does the signature, and the JDK already
 * builds an RSA key from a modulus and an exponent. Pulling in the Google
 * client library — and its transitive HTTP stack — to wrap that is a larger
 * change than the feature.
 *
 * WHY NOT THE tokeninfo ENDPOINT
 * Asking Google to validate each token is a network round trip on the critical
 * path of every sign-in, and Google's own documentation reserves it for debug
 * use. Local validation is the documented production path.
 *
 * KEY ROTATION
 * Google rotates its signing keys without warning, so the cache is refreshed
 * both on a timer and, more importantly, whenever a token names a key id we
 * have not seen — a rotation must not lock users out until a TTL elapses.
 */
@Service
@Slf4j
public class GoogleTokenVerifier {

    /** Both spellings appear in Google ID tokens and both are legitimate. */
    private static final Set<String> ISSUERS =
            Set.of("accounts.google.com", "https://accounts.google.com");

    private static final Duration JWKS_TTL     = Duration.ofHours(1);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    /** Tolerance for a browser clock that runs slightly ahead of ours. */
    private static final long CLOCK_SKEW_SECONDS = 60;

    /** Blank when the deployment has no Google project — the feature then stays off. */
    @Value("${google.oauth.client-id:}")
    private String clientId;

    @Value("${google.oauth.certs-url:https://www.googleapis.com/oauth2/v3/certs}")
    private String certsUrl;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private volatile Map<String, PublicKey> keyCache    = Map.of();
    private volatile Instant                keyCacheAge = Instant.EPOCH;

    /** Raised for every rejection; the message is for the log, never for the caller. */
    public static class InvalidGoogleTokenException extends Exception {
        public InvalidGoogleTokenException(String message) { super(message); }
        public InvalidGoogleTokenException(String message, Throwable cause) { super(message, cause); }
    }

    /** What we are willing to trust about the person behind the token. */
    public record GoogleIdentity(String subject, String email, String name) {}

    public boolean isEnabled() {
        return clientId != null && !clientId.isBlank();
    }

    public String clientId() {
        return clientId;
    }

    public GoogleIdentity verify(String idToken) throws InvalidGoogleTokenException {

        if (!isEnabled())
            throw new InvalidGoogleTokenException("Google sign-in is not configured");
        if (idToken == null || idToken.isBlank())
            throw new InvalidGoogleTokenException("Empty credential");

        String kid = keyIdOf(idToken);
        PublicKey key = resolveKey(kid);

        Jws<Claims> jws;
        try {
            jws = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .setAllowedClockSkewSeconds(CLOCK_SKEW_SECONDS)
                    // Audience and expiry are checked by the parser itself, so a
                    // token minted for a different app never reaches our claims code
                    .requireAudience(clientId)
                    .build()
                    .parseClaimsJws(idToken);
        } catch (Exception e) {
            throw new InvalidGoogleTokenException("Signature or claims rejected: " + e.getMessage(), e);
        }

        Claims c = jws.getBody();

        if (!ISSUERS.contains(String.valueOf(c.getIssuer())))
            throw new InvalidGoogleTokenException("Unexpected issuer " + c.getIssuer());

        String subject = c.getSubject();
        if (subject == null || subject.isBlank())
            throw new InvalidGoogleTokenException("Token carries no subject");

        String email = c.get("email", String.class);
        if (email == null || email.isBlank())
            throw new InvalidGoogleTokenException("Token carries no email");

        // Without this the holder of any Google account could claim an address
        // they never proved they own — which is exactly how account takeover by
        // linking would happen.
        if (!Boolean.TRUE.equals(c.get("email_verified", Boolean.class)))
            throw new InvalidGoogleTokenException("Google has not verified this address");

        String name = c.get("name", String.class);
        if (name == null || name.isBlank()) name = email.substring(0, email.indexOf('@'));

        return new GoogleIdentity(subject, email.toLowerCase(), name);
    }

    // ── JWKS ──────────────────────────────────────────────────────────────

    /** Reads the unverified header purely to learn which key to verify with. */
    private String keyIdOf(String idToken) throws InvalidGoogleTokenException {
        try {
            int dot = idToken.indexOf('.');
            if (dot <= 0) throw new IllegalArgumentException("not a JWT");
            String headerJson = new String(
                    Base64.getUrlDecoder().decode(idToken.substring(0, dot)), StandardCharsets.UTF_8);
            JsonNode header = mapper.readTree(headerJson);

            if (!"RS256".equals(header.path("alg").asText()))
                throw new IllegalArgumentException("unexpected alg " + header.path("alg").asText());

            String kid = header.path("kid").asText(null);
            if (kid == null || kid.isBlank()) throw new IllegalArgumentException("no kid");
            return kid;

        } catch (Exception e) {
            throw new InvalidGoogleTokenException("Malformed credential: " + e.getMessage(), e);
        }
    }

    private PublicKey resolveKey(String kid) throws InvalidGoogleTokenException {
        PublicKey cached = keyCache.get(kid);
        boolean stale = Instant.now().isAfter(keyCacheAge.plus(JWKS_TTL));

        if (cached != null && !stale) return cached;

        if (refreshKeys()) {
            // The fresh set is authoritative — a key Google has retired should
            // stop verifying, not linger because we still remember it
            PublicKey fresh = keyCache.get(kid);
            if (fresh != null) return fresh;
            throw new InvalidGoogleTokenException("No Google signing key for kid " + kid);
        }

        // Refresh failed. A key we already hold is still better than refusing a
        // valid sign-in over a hiccup at Google; with nothing cached we must stop.
        if (cached != null) {
            log.warn("[GOOGLE-AUTH] Key refresh failed, falling back to the cached key");
            return cached;
        }
        throw new InvalidGoogleTokenException("Google signing keys unavailable");
    }

    /** @return true when the cache now holds a freshly fetched key set. */
    private synchronized boolean refreshKeys() {
        try {
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(certsUrl))
                            .timeout(HTTP_TIMEOUT)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200)
                throw new IllegalStateException("HTTP " + res.statusCode());

            Map<String, PublicKey> fresh = new HashMap<>();
            KeyFactory rsa = KeyFactory.getInstance("RSA");

            for (JsonNode k : mapper.readTree(res.body()).path("keys")) {
                if (!"RSA".equals(k.path("kty").asText())) continue;
                String kid = k.path("kid").asText(null);
                if (kid == null) continue;

                BigInteger modulus = unsigned(k.path("n").asText());
                BigInteger exponent = unsigned(k.path("e").asText());
                fresh.put(kid, rsa.generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            }

            if (fresh.isEmpty())
                throw new IllegalStateException("no usable keys in JWKS");

            keyCache    = Map.copyOf(fresh);
            keyCacheAge = Instant.now();
            log.info("[GOOGLE-AUTH] Loaded {} signing keys", fresh.size());
            return true;

        } catch (Exception e) {
            // The previous cache is deliberately left in place: an outage at
            // Google should not invalidate keys that are still good.
            log.error("[GOOGLE-AUTH] Could not refresh signing keys: {}", e.getMessage());
            return false;
        }
    }

    /** JWKS numbers are base64url big-endian and always positive. */
    private BigInteger unsigned(String base64Url) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(base64Url));
    }
}
