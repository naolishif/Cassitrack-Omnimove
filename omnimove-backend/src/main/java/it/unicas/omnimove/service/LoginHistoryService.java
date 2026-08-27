package it.unicas.omnimove.service;

import it.unicas.omnimove.model.LoginEvent;
import it.unicas.omnimove.model.User;
import it.unicas.omnimove.repository.LoginEventRepository;
import it.unicas.omnimove.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Access history shown in the admin dashboard.
 *
 * users.last_login_at is denormalised on purpose: the user list would
 * otherwise need an aggregate over login_events for every row. It is written
 * in exactly one place — recordLogin — right next to the event it summarises.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginHistoryService {

    /** Hard cap on the rows the history endpoint returns. */
    public static final int MAX_HISTORY = 200;

    private static final int USER_AGENT_MAX = 255;

    private final LoginEventRepository loginEventRepo;
    private final UserRepository       userRepo;

    /**
     * Records a successful access and refreshes the user's last-login stamp.
     * The caller has already applied its own changes to {@code user} (reset of
     * the failed-attempt counter); they are persisted by the same save.
     *
     * The two writes are deliberately NOT wrapped in one transaction: a failed
     * event insert must not roll back — or, worse, poison — the user update,
     * because history is a reporting feature and the login itself is valid
     * without it. Each repository call commits on its own.
     */
    public void recordLogin(User user, String ip, String userAgent) {
        LocalDateTime now = LocalDateTime.now();

        user.setLastLoginAt(now);
        userRepo.save(user);

        try {
            loginEventRepo.save(LoginEvent.builder()
                    .userId(user.getId())
                    .loggedInAt(now)
                    .ipAddress(ip)
                    .userAgent(truncate(userAgent))
                    .build());
        } catch (Exception ex) {
            // History is a reporting feature — never fail a valid login over it
            log.error("Could not record login event for user id={} : {}",
                    user.getId(), ex.getMessage());
        }
    }

    /** Full access history for one user, newest first, capped at {@link #MAX_HISTORY}. */
    public List<LoginEvent> history(Long userId) {
        return loginEventRepo.findByUserIdOrderByLoggedInAtDesc(
                userId, PageRequest.of(0, MAX_HISTORY));
    }

    public long count(Long userId) {
        return loginEventRepo.countByUserId(userId);
    }

    /** Access count per user id, for the whole registry, in one query. */
    public Map<Long, Long> countsByUser() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : loginEventRepo.countGroupedByUser()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    private String truncate(String userAgent) {
        if (userAgent == null) return null;
        return userAgent.length() <= USER_AGENT_MAX
                ? userAgent
                : userAgent.substring(0, USER_AGENT_MAX);
    }
}
