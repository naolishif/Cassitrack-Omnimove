package it.unicas.omnimove.controller;

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
 * is the single place errors are rendered — a 404 from a typo'd URL, a 403 from a role
 * check, a 500 from a bug. Browsers get the branded page, API clients get JSON, decided by
 * the Accept header rather than by the caller having to know two URLs.
 */
@Controller
public class ErrorPageController implements ErrorController {

    private static final String PAGE_RESOURCE = "error-page.html";
    private static final String STATUS_PLACEHOLDER = "__STATUS__";

    /** Read once from the classpath, then kept in memory — the page never changes at runtime. */
    private volatile String cachedPage;

    @RequestMapping(value = "/error", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> errorHtml(HttpServletRequest request) throws IOException {
        int status = resolveStatus(request);
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_HTML)
                .body(page().replace(STATUS_PLACEHOLDER, String.valueOf(status)));
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
