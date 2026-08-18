package it.unicas.cassitrack.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * US-13: one error page for every status, replacing Spring's Whitelabel.
 *
 * Declaring an ErrorController bean makes Boot's own BasicErrorController back off, so this
 * is the single place errors are rendered. Wording is substituted server-side rather than
 * picked by a script: the CASSITRACK CSP deliberately has no 'unsafe-inline', so the page
 * ships with no inline script and no inline style.
 */
@Controller
public class ErrorPageController implements ErrorController {

    private static final String PAGE_RESOURCE = "error-page.html";

    /** Read once from the classpath, then kept in memory — the page never changes at runtime. */
    private volatile String cachedPage;

    private record Wording(String title, String message) {}

    @RequestMapping(value = "/error", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> errorHtml(HttpServletRequest request) throws IOException {
        int status = resolveStatus(request);
        Wording wording = wordingFor(status);

        String html = page()
                .replace("__CTX__", request.getContextPath())
                .replace("__STATUS__", String.valueOf(status))
                .replace("__SERIES__", String.valueOf(status / 100))
                .replace("__TITLE__", wording.title())
                .replace("__MESSAGE__", wording.message());

        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(html);
    }

    @RequestMapping("/error")
    public ResponseEntity<Map<String, Object>> errorJson(HttpServletRequest request) {
        int status = resolveStatus(request);
        HttpStatus resolved = HttpStatus.resolve(status);

        // LinkedHashMap, not Map.of: the fields should read in a stable order
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", resolved != null ? resolved.getReasonPhrase() : "Error");
        return ResponseEntity.status(status).body(body);
    }

    private Wording wordingFor(int status) {
        return switch (status) {
            case 400 -> new Wording("Bad request",
                    "The request could not be understood. Try again from the app.");
            case 401 -> new Wording("Session expired",
                    "You need to sign in again to continue.");
            case 403 -> new Wording("Access denied",
                    "Your account is not allowed to open this page.");
            case 404 -> new Wording("Page not found",
                    "The address you opened does not exist. Check the link, or go back to the app.");
            case 500 -> new Wording("Something went wrong",
                    "An error occurred on our side. Please try again in a moment.");
            default  -> new Wording("Something went wrong",
                    "An unexpected error occurred. Please try again.");
        };
    }

    private int resolveStatus(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code == null) return HttpStatus.INTERNAL_SERVER_ERROR.value();
        try {
            int status = Integer.parseInt(code.toString());
            // Never echo back a nonsense status the container may have set
            return (status >= 400 && status < 600) ? status : HttpStatus.INTERNAL_SERVER_ERROR.value();
        } catch (NumberFormatException e) {
            return HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
    }

    private String page() throws IOException {
        String page = cachedPage;
        if (page == null) {
            try (InputStream in = new ClassPathResource(PAGE_RESOURCE).getInputStream()) {
                page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            cachedPage = page;
        }
        return page;
    }
}
