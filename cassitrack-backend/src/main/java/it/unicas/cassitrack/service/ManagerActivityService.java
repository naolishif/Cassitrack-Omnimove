package it.unicas.cassitrack.service;

import it.unicas.cassitrack.model.LoginEvent;
import it.unicas.cassitrack.model.ManagerExport;
import it.unicas.cassitrack.model.User;
import it.unicas.cassitrack.repository.LoginEventRepository;
import it.unicas.cassitrack.repository.ManagerExportRepository;
import it.unicas.cassitrack.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What each operator has done: when they came in, and what they took out.
 *
 * <p>Both are recorded best-effort. Neither is allowed to fail the thing it
 * describes: a bookkeeping error must not refuse a sign-in, and it must not
 * withhold a file the manager asked for and is entitled to. The forensic copy
 * of the sign-in is written separately by {@code SecurityAuditService} and is
 * the record that must not be lost.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerActivityService {

    private final LoginEventRepository loginEvents;
    private final ManagerExportRepository exports;
    private final UserRepository users;

    /** How much history a card shows before it stops being a card. */
    public static final int MAX_HISTORY = 200;

    // ── Writing ─────────────────────────────────────────────────────

    /**
     * One successful access.
     *
     * <p>Writes the event and moves the account's own {@code last_login_at}, so
     * the list view can show "last seen" without reading the history of every
     * row it draws.
     */
    public void recordLogin(User user, String ip, String userAgent) {
        if (user == null || user.getId() == null) return;
        try {
            loginEvents.save(LoginEvent.builder()
                    .userId(user.getId())
                    .loggedInAt(LocalDateTime.now())
                    .ipAddress(trim(ip, 50))
                    .userAgent(trim(userAgent, 255))
                    .build());

            user.setLastLoginAt(LocalDateTime.now());
            users.save(user);

        } catch (Exception e) {
            log.error("Could not record the login of user id={} : {}", user.getId(), e.getMessage());
        }
    }

    /** One file downloaded. Called from the single endpoint every export goes through. */
    public void recordExport(Long userId, String dataset, String format, int rows, String detail) {
        if (userId == null) return;
        try {
            exports.save(ManagerExport.builder()
                    .userId(userId)
                    .dataset(trim(dataset, 60))
                    .format(trim(format, 10))
                    .rowCount(Math.max(0, rows))
                    .detail(trim(detail, 255))
                    .exportedAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("Could not record the export by user id={} : {}", userId, e.getMessage());
        }
    }

    // ── Reading ─────────────────────────────────────────────────────

    public List<LoginEvent> logins(Long userId) {
        return loginEvents.findByUserIdOrderByLoggedInAtDesc(userId, PageRequest.of(0, MAX_HISTORY));
    }

    public List<ManagerExport> downloads(Long userId) {
        return exports.findByUserIdOrderByExportedAtDesc(userId, PageRequest.of(0, MAX_HISTORY));
    }

    public long loginCount(Long userId)    { return loginEvents.countByUserId(userId); }
    public long downloadCount(Long userId) { return exports.countByUserId(userId); }

    /**
     * Totals for the whole list in two queries rather than two per row.
     *
     * <p>The list draws every account; asking the database once per account for
     * its counts is the N+1 that makes a user panel feel slow the moment there
     * is more than a handful of them.
     */
    public Map<Long, long[]> totalsByUser() {
        Map<Long, long[]> out = new HashMap<>();
        for (Object[] row : loginEvents.countGroupedByUser())
            out.computeIfAbsent(id(row[0]), k -> new long[2])[0] = ((Number) row[1]).longValue();
        for (Object[] row : exports.countGroupedByUser())
            out.computeIfAbsent(id(row[0]), k -> new long[2])[1] = ((Number) row[1]).longValue();
        return out;
    }

    private static Long id(Object v) { return ((Number) v).longValue(); }

    /** The column has a width; a long User-Agent must not fail the insert. */
    private static String trim(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
