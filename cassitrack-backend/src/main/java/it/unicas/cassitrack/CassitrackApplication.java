package it.unicas.cassitrack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CASSITRACK — Real-time bus fleet monitoring system
 * University of Cassino and Southern Lazio — 2025/2026
 *
 * This system tracks the MAGNI Autoservizi bus fleet in Cassino,
 * receiving GPS telemetry via MQTT from ESP32 on-board units
 * and exposing real-time positions and ETAs through a REST API.
 */
@SpringBootApplication
@EnableScheduling   // needed for scheduled tasks (ETA recomputation, alerts)
@EnableAsync        // needed for async security audit DB writes
public class CassitrackApplication {

    public static void main(String[] args) {
        SpringApplication.run(CassitrackApplication.class, args);
    }
}
