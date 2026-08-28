package it.unicas.omnimove.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unicas.omnimove.model.AppSetting;
import it.unicas.omnimove.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Google reCAPTCHA v2 on the login form.
 *
 * TWO CONDITIONS, NOT ONE
 * The check runs only when the administrator has switched it on AND the
 * deployment actually has a key pair. Either alone is not enough: a flag
 * without keys would show a widget nobody could solve and lock every account
 * out of the app, which is a worse outcome than the bots the flag is there to
 * stop. {@link #isActive()} is the only question callers should ask.
 *
 * WHY v2 AND NOT v3
 * v3 returns a score and asks the site to decide a threshold, which means
 * guessing how bot-like a real student on campus Wi-Fi looks. The checkbox is
 * a plain gate: solved or not, with nothing to tune.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecaptchaService {

    public static final String KEY_ENABLED = "security.recaptcha";

    private static final String VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify";
    private static final Duration TIMEOUT  = Duration.ofSeconds(5);

    /** Public — it is embedded in the widget on the login page. */
    @Value("${recaptcha.site-key:}")
    private String siteKey;

    /** Secret — never leaves the server. */
    @Value("${recaptcha.secret-key:}")
    private String secretKey;

    private final AppSettingRepository repo;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient   http   = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** Mirrors the app_settings row so the login path never queries the DB. */
    private final AtomicBoolean flag = new AtomicBoolean(true);

    @PostConstruct
    void load() {
        flag.set(repo.findById(KEY_ENABLED)
                .map(s -> Boolean.parseBoolean(s.getValue()))
                .orElse(true));
        log.info("reCAPTCHA flag={} configured={} → active={}", flag.get(), isConfigured(), isActive());
    }

    public boolean isConfigured() {
        return siteKey != null && !siteKey.isBlank()
            && secretKey != null && !secretKey.isBlank();
    }

    /** Whether a login must carry a solved challenge. */
    public boolean isActive() {
        return flag.get() && isConfigured();
    }

    /** What the administrator has chosen, regardless of whether keys exist. */
    public boolean isFlagEnabled() {
        return flag.get();
    }

    public String siteKey() {
        return isActive() ? siteKey : "";
    }

    /** Write-through: the row is the record, the flag is the cache. */
    public void setEnabled(boolean enabled) {
        AppSetting s = repo.findById(KEY_ENABLED).orElseGet(() -> {
            AppSetting n = new AppSetting();
            n.setKey(KEY_ENABLED);
            return n;
        });
        s.setValue(Boolean.toString(enabled));
        s.setUpdatedAt(Instant.now());
        repo.save(s);
        flag.set(enabled);
        log.info("reCAPTCHA switched {} by an administrator", enabled ? "ON" : "OFF");
    }

    /**
     * Asks Google whether this token is a solved challenge.
     *
     * Fails CLOSED — unlike the rate limiter, which lets requests through when
     * Redis is down. There the fallback is a slower attacker; here it would be
     * no captcha at all, which is exactly what an attacker would engineer by
     * making the verification call fail.
     */
    public boolean verify(String token, String remoteIp) {
        if (token == null || token.isBlank()) return false;

        try {
            String form = "secret="   + URLEncoder.encode(secretKey, StandardCharsets.UTF_8)
                        + "&response=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                        + (remoteIp == null ? ""
                           : "&remoteip=" + URLEncoder.encode(remoteIp, StandardCharsets.UTF_8));

            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(VERIFY_URL))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                log.warn("[RECAPTCHA] Verification returned HTTP {}", res.statusCode());
                return false;
            }

            JsonNode body = mapper.readTree(res.body());
            boolean ok = body.path("success").asBoolean(false);
            if (!ok) log.warn("[RECAPTCHA] Rejected: {}", body.path("error-codes"));
            return ok;

        } catch (Exception e) {
            log.error("[RECAPTCHA] Could not reach Google: {}", e.getMessage());
            return false;
        }
    }
}
