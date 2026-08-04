package it.unicas.cassitrack.config;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Safety net for database constraint violations.
 *
 * The CRUD services check for duplicates/references *before* writing, so in
 * normal use this never fires. It matters for the cases those checks cannot
 * cover:
 *
 *  · Race conditions — two fleet managers submitting the same plate at the
 *    same instant both pass the "is it free?" check, then one INSERT loses to
 *    the UNIQUE index.
 *  · Any constraint added in the schema but not mirrored by an application
 *    check.
 *
 * Without this, Spring turns those into an opaque HTTP 500 ("Internal Server
 * Error") that tells the user nothing and looks like a crash. Here we map them
 * to 409 CONFLICT with a readable explanation — the data is still safely
 * rejected by PostgreSQL either way; this only fixes what the user is told.
 *
 * Scope note: controller-local @ExceptionHandler methods (e.g. BusController's)
 * take precedence over this advice, so existing behaviour is unchanged.
 * The body carries BOTH "error" and "message" keys because the Stops/Routes UI
 * reads `error` while the Buses UI reads `message`.
 */
@RestControllerAdvice
public class DataIntegrityExceptionHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(DataIntegrityViolationException ex) {
        String detail = rootMessage(ex).toLowerCase();
        String friendly;

        if (detail.contains("targa")) {
            friendly = "That plate is already registered to another bus.";
        } else if (detail.contains("current_vehicle_id")) {
            friendly = "That vehicle id is already linked to another bus.";
        } else if (detail.contains("foreign key") || detail.contains("still referenced")
                || detail.contains("violates foreign key constraint")) {
            friendly = "This record is still referenced by other data and cannot be changed or removed.";
        } else if (detail.contains("duplicate key") || detail.contains("unique")) {
            friendly = "A record with the same identifier already exists.";
        } else if (detail.contains("not null")) {
            friendly = "A required field is missing.";
        } else {
            friendly = "The database rejected this change because it would break data integrity.";
        }

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("status", 409, "error", friendly, "message", friendly));
    }

    /** Deepest cause carries the actual PostgreSQL message (constraint name). */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage() == null ? "" : cur.getMessage();
    }
}
