package it.unicas.omnimove.service;

import it.unicas.omnimove.model.AppSetting;
import it.unicas.omnimove.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Operator-tunable bits of the traveller interface.
 *
 * <p>Sits beside {@link GoogleApiSettingsService} on the same app_settings table
 * and follows the same shape — read once into memory, write through on change —
 * but holds a number rather than a flag, which is why it is not folded into that
 * class: its whole API is boolean.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UiSettingsService {

    /** How often the assistant introduces itself, in minutes. 0 disables it. */
    public static final String KEY_AI_NUDGE_MINUTES = "ui.ai_nudge_minutes";

    private static final int DEFAULT_AI_NUDGE_MINUTES = 2;
    /** Anything below this would be pestering rather than suggesting. */
    private static final int MIN_MINUTES = 1;
    private static final int MAX_MINUTES = 240;

    private final AppSettingRepository repo;

    private volatile int aiNudgeMinutes = DEFAULT_AI_NUDGE_MINUTES;

    @PostConstruct
    void load() {
        aiNudgeMinutes = repo.findById(KEY_AI_NUDGE_MINUTES)
                .map(s -> parseOrDefault(s.getValue()))
                .orElse(DEFAULT_AI_NUDGE_MINUTES);
        log.info("UI settings loaded: aiNudgeMinutes={}", aiNudgeMinutes);
    }

    private int parseOrDefault(String raw) {
        try {
            return clamp(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            log.warn("Unreadable {} value '{}' — falling back to {}",
                     KEY_AI_NUDGE_MINUTES, raw, DEFAULT_AI_NUDGE_MINUTES);
            return DEFAULT_AI_NUDGE_MINUTES;
        }
    }

    /** 0 stays 0 — that is "off", not a value to be pulled up into range. */
    private int clamp(int minutes) {
        if (minutes <= 0) return 0;
        return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
    }

    public int getAiNudgeMinutes() { return aiNudgeMinutes; }

    /** @return the value actually stored, which may have been clamped. */
    public int setAiNudgeMinutes(int minutes) {
        int value = clamp(minutes);
        AppSetting row = repo.findById(KEY_AI_NUDGE_MINUTES).orElseGet(() -> {
            AppSetting fresh = new AppSetting();
            fresh.setKey(KEY_AI_NUDGE_MINUTES);
            return fresh;
        });
        row.setValue(String.valueOf(value));
        row.setUpdatedAt(Instant.now());
        repo.save(row);
        aiNudgeMinutes = value;
        log.info("aiNudgeMinutes set to {}", value);
        return value;
    }
}
