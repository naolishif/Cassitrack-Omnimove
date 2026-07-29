package it.unicas.omnimove;

import it.unicas.omnimove.service.NetexImportService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/**
 * OMNIMOVE — Multimodal Journey Planner
 *
 * Runs on port 8180. Talks to CASSITRACK (port 8280)
 * via REST API only. No shared database or code imports.
 *
 * Two-server architectures
 * CASSITRACK :8280 (MAGNI fleet monitoring)
 * OMNIMOVE   :8180 (passenger journey planning)
 */

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class OmnimoveApplication {

    // 1. 👈 DICHIARIAMO il servizio come variabile privata
    private final NetexImportService netexImportService;

    // 2. 👈 CREIAMO IL COSTRUTTORE per iniettarlo in OmnimoveApplication
    public OmnimoveApplication(NetexImportService netexImportService) {
        this.netexImportService = netexImportService;
    }

    public static void main(String[] args) {
        SpringApplication.run(OmnimoveApplication.class, args);
    }

    private static final int MAX_ATTEMPTS    = 10;
    private static final long INITIAL_DELAY_MS = 5_000;   // 5s
    private static final long MAX_DELAY_MS     = 60_000;  // 60s

    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        System.out.println("[OMNIMOVE] Applicazione avviata — avvio sincronizzazione NeTEx...");

        long delay = INITIAL_DELAY_MS;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                netexImportService.importDataFromCassitrack();
                System.out.println("[OMNIMOVE] Sincronizzazione NeTEx completata al tentativo " + attempt + ".");
                return; // successo, usciamo
            } catch (Exception e) {
                System.err.printf("[OMNIMOVE] Tentativo %d/%d fallito: %s%n",
                        attempt, MAX_ATTEMPTS, e.getMessage());

                if (attempt == MAX_ATTEMPTS) {
                    System.err.println("[OMNIMOVE] Tutti i tentativi esauriti. " +
                            "OmniMove partirà con tabelle vuote — riprovare manualmente o riavviare.");
                    return;
                }

                System.out.printf("[OMNIMOVE] Prossimo tentativo tra %ds...%n", delay / 1000);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }

                // Backoff esponenziale con limite massimo
                delay = Math.min(delay * 2, MAX_DELAY_MS);
            }
        }
    }
}