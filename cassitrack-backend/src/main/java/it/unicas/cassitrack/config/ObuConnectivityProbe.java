package it.unicas.cassitrack.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

/**
 * Dice PERCHE' il broker OBU non risponde, invece di lasciarlo intuire.
 *
 * IL PROBLEMA CHE RISOLVE
 * Quando la connessione al broker esterno non riesce, Paho non la abbandona: con
 * automaticReconnect riprova in un thread suo, all'infinito, e l'eccezione vera —
 * host irraggiungibile, certificato non fidato, credenziali rifiutate — resta
 * dentro quel ciclo. All'avvio si vede solo un ERROR generico di Spring:
 *
 *     MQTT client failed to connect. Never happens.
 *     MqttException: Connessione gia' in corso
 *
 * che e' la CONSEGUENZA (l'adattatore ha smesso di aspettare) e non la causa.
 *
 * COSA FA
 * Una sola volta, ad applicazione avviata, ripercorre a mano i tre gradini che la
 * connessione deve salire — DNS, TCP, handshake TLS — e scrive quale non regge.
 * Ogni gradino ha un rimedio diverso, e distinguerli e' tutto:
 *
 *   DNS  fallito  -> il nome non si risolve: rete, o VPN d'ateneo assente
 *   TCP  fallito  -> il nome si risolve ma la porta non risponde: server spento
 *                    o firewall
 *   TLS  fallito  -> si arriva al broker ma la JVM non si fida del certificato.
 *                    E' il caso subdolo: mosquitto_sub e bridge.py in Python
 *                    funzionano lo stesso, perche' usano il magazzino di
 *                    certificati del sistema e non quello di Java.
 *
 * NON tenta l'autenticazione MQTT: le credenziali non si provano di nascosto, e
 * se i tre gradini reggono il CONNACK di Paho e' gia' informativo di suo.
 *
 * E' un accertamento, non una cura: non cambia il comportamento dell'adattatore
 * e non ritenta nulla. Esiste solo perche' la prossima volta la risposta stia nel
 * log invece che in mezz'ora di prove.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "mqtt.obu.enabled", havingValue = "true")
public class ObuConnectivityProbe {

    @Value("${mqtt.obu.url:}")
    private String obuUrl;

    /** Oltre questo non si sta diagnosticando, si sta aspettando. */
    private static final int TIMEOUT_MS = 8000;

    @EventListener(ApplicationReadyEvent.class)
    public void probe() {
        if (obuUrl == null || obuUrl.isBlank()) return;

        boolean tls = obuUrl.startsWith("ssl://");
        String hostPort = obuUrl.replace("ssl://", "").replace("tcp://", "");
        int colon = hostPort.lastIndexOf(':');
        String host = colon > 0 ? hostPort.substring(0, colon) : hostPort;
        int port;
        try {
            port = colon > 0 ? Integer.parseInt(hostPort.substring(colon + 1)) : (tls ? 8883 : 1883);
        } catch (NumberFormatException e) {
            log.warn("OBU: porta non leggibile in '{}' — controlla MQTT_OBU_URL", obuUrl);
            return;
        }

        // ── 1. DNS ────────────────────────────────────────────────
        InetAddress addr;
        try {
            addr = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            log.warn("OBU ✗ DNS: '{}' non si risolve. Il broker non e' raggiungibile da questa "
                   + "rete — manca la VPN, oppure il nome e' sbagliato in MQTT_OBU_URL.", host);
            return;
        }
        log.info("OBU ✓ DNS: {} → {}", host, addr.getHostAddress());

        // ── 2. TCP ────────────────────────────────────────────────
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(addr, port), TIMEOUT_MS);
        } catch (Exception e) {
            log.warn("OBU ✗ TCP {}:{} — {}. Il nome si risolve ma la porta non risponde: "
                   + "broker spento, o firewall fra qui e li'.", host, port, e.getMessage());
            return;
        }
        log.info("OBU ✓ TCP {}:{} raggiungibile", host, port);

        if (!tls) {
            log.info("OBU: connessione in chiaro, nessun handshake da verificare.");
            return;
        }

        // ── 3. TLS ────────────────────────────────────────────────
        try (SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket()) {
            s.connect(new InetSocketAddress(addr, port), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            // Senza questo il nome non viene verificato e un certificato per un
            // altro host passerebbe: la stessa verifica che fa l'adattatore con
            // setHttpsHostnameVerificationEnabled(true).
            javax.net.ssl.SSLParameters p = s.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(p);
            s.startHandshake();
            log.info("OBU ✓ TLS: certificato valido e fidato ({})",
                    s.getSession().getCipherSuite());
            log.info("OBU: i tre gradini reggono. Se l'adattatore continua a fallire, "
                   + "restano le credenziali: MQTT_OBU_USERNAME / MQTT_OBU_PASSWORD.");
        } catch (Exception e) {
            log.warn("OBU ✗ TLS con {}:{} — {}", host, port, e.getMessage());
            log.warn("OBU: si arriva al broker ma la JVM non accetta il suo certificato. "
                   + "Attenzione: mosquitto_sub e bridge.py possono funzionare lo stesso, "
                   + "perche' usano i certificati del sistema operativo e non quelli di Java. "
                   + "Il rimedio e' importare la CA del broker nel truststore della JVM "
                   + "(keytool -importcert -cacerts), non disattivare la verifica.");
        }
    }
}
