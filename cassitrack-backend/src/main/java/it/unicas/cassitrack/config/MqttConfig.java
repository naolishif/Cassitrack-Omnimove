package it.unicas.cassitrack.config;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

import it.unicas.cassitrack.mqtt.MqttMessageHandler;

import javax.net.ssl.SSLSocketFactory;

import java.util.UUID;

/**
 * Configures the MQTT client that listens to messages from buses.
 *
 * How it works:
 *   Bus (ESP32) → publishes JSON to MQTT topic → Mosquitto broker
 *   → this adapter receives it → mqttInputChannel
 *   → MqttMessageHandler processes it (validate → store → broadcast)
 */
@Configuration
public class MqttConfig {

    @Value("${mqtt.broker.url}")
    private String brokerUrl;

    @Value("${mqtt.broker.client-id}")
    private String clientId;

    @Value("${mqtt.broker.username:}")
    private String mqttUsername;

    @Value("${mqtt.broker.password:}")
    private String mqttPassword;

    @Value("${mqtt.topics.position}")
    private String positionTopic;

    @Value("${mqtt.topics.ble}")
    private String bleTopic;

    // ── OBU broker (real ESP32 feed, e.g. ssl://devaidalab.unicas.it:8883) ──
    @Value("${mqtt.obu.url:}")
    private String obuBrokerUrl;

    @Value("${mqtt.obu.client-id:cassitrack-obu-bridge}")
    private String obuClientId;

    @Value("${mqtt.obu.username:}")
    private String obuUsername;

    @Value("${mqtt.obu.password:}")
    private String obuPassword;

    @Value("${mqtt.obu.topic:cassitrack/obu/+/pos}")
    private String obuTopic;

    /**
     * Factory that creates MQTT client connections.
     * Sets connection options: timeout, keep-alive, clean session.
     */
    @Bean
    public MqttPahoClientFactory mqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{brokerUrl});
        options.setConnectionTimeout(30);
        options.setKeepAliveInterval(60);
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        if (mqttUsername != null && !mqttUsername.isBlank()) {
            options.setUserName(mqttUsername);
            options.setPassword(mqttPassword.toCharArray());
        }
        factory.setConnectionOptions(options);
        return factory;
    }

    /**
     * The Spring Integration channel that carries incoming MQTT messages.
     * Think of it as a pipe: messages arrive here from the broker.
     */
    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    /**
     * Inbound channel adapter — subscribes to MQTT topics and
     * pushes messages into mqttInputChannel.
     *
     * Topics subscribed:
     *   cassitrack/+/position  (+ matches any vehicle_id)
     *   cassitrack/+/ble
     *   cassitrack/obu/+/pos   (compact OBU schema, see below)
     *
     * The OBU topic is subscribed HERE as well as on the external broker so a
     * simulator running against the local Mosquitto can exercise the same
     * ingestion path without needing the real TLS broker. MqttMessageHandler
     * decides how to parse a message from its topic, not from which connection
     * delivered it, so the translation works identically either way.
     */
    @Bean
    public MqttPahoMessageDrivenChannelAdapter mqttInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter =
            new MqttPahoMessageDrivenChannelAdapter(
                clientId + "-inbound",
                mqttClientFactory(),
                positionTopic,
                bleTopic,
                obuTopic
            );
        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);                 // QoS 1 = at least once delivery
        adapter.setOutputChannel(mqttInputChannel());
        return adapter;
    }

    /**
     * The Spring Integration channel for OUTBOUND messages.
     * Controllers send messages here to be published to the Mosquitto broker.
     */
    @Bean
    public MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

    /**
     * Outbound channel adapter — takes messages from mqttOutboundChannel
     * and publishes them using the existing MqttPahoClientFactory connection.
     */
    @Bean
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    public MessageHandler mqttOutbound() {
        MqttPahoMessageHandler messageHandler =
                new MqttPahoMessageHandler(clientId + "-outbound-publisher", mqttClientFactory());
        messageHandler.setAsync(true);
        messageHandler.setDefaultQos(1);
        return messageHandler;
    }

    // ─────────────────────────────────────────────────────────────────
    //  OBU broker path (second inbound connection)
    //
    //  The real ESP32 units — and the professor's simulate_bus.py — publish
    //  to an EXTERNAL, TLS-secured broker using a different topic and a more
    //  compact payload schema. Rather than run a separate bridge process,
    //  cassitrack opens a second inbound connection and feeds the SAME
    //  mqttInputChannel; MqttMessageHandler recognises the cassitrack/obu/...
    //  topic and translates the payload via ObuPositionPayload.
    //
    //  Dormant unless mqtt.obu.enabled=true, so local dev is unaffected.
    // ─────────────────────────────────────────────────────────────────

    /** The value of mqtt.obu.client-id when nobody has configured one. */
    private static final String DEFAULT_OBU_CLIENT_ID = "cassitrack-obu-bridge";

    /**
     * The client id for the OBU connection.
     *
     * WHY THIS ISN'T JUST A CONSTANT
     * ------------------------------
     * MQTT requires client ids to be unique per broker: connecting with an id
     * already in use makes the broker disconnect the existing holder. The OBU
     * broker is shared — the deployed server and every developer's laptop run
     * this same code — so a single hardcoded id makes two instances kick each
     * other off in a loop, seen as "Lost connection: Connection lost" every few
     * seconds with only the odd message arriving in between.
     *
     * CONFIG FIRST
     * ------------
     * A client id is an IDENTITY, so it belongs in configuration. Set
     * MQTT_OBU_CLIENT_ID per environment (e.g. cassitrack-obu-server in
     * .env_server, cassitrack-obu-ferran in a developer's .env) and that value
     * is used verbatim — which keeps the broker's connection log readable and
     * leaves the door open to per-client ACLs or cleanSession=false later.
     *
     * The random suffix applies ONLY when the setting is still the untouched
     * default, so a fresh clone cannot collide with a running server before
     * anyone has configured anything. Deliberate values are never rewritten.
     *
     * The id is not truncated to the old MQTT 3.1 23-character limit: the
     * previous 29-character id connected to this broker successfully, so it
     * does not enforce that limit.
     */
    private String resolveObuClientId() {
        if (!DEFAULT_OBU_CLIENT_ID.equals(obuClientId)) {
            return obuClientId;                       // configured deliberately — respect it
        }
        return obuClientId + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Dedicated client factory for the OBU broker. Attaches a TLS socket
     * factory automatically when the URL uses the ssl:// scheme, validating the
     * broker certificate against the JVM default truststore — the same trust
     * anchors as {@code mosquitto_sub --capath /etc/ssl/certs}.
     */
    @Bean
    @ConditionalOnProperty(name = "mqtt.obu.enabled", havingValue = "true")
    public MqttPahoClientFactory obuMqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{obuBrokerUrl});
        options.setConnectionTimeout(30);
        options.setKeepAliveInterval(60);
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        if (obuUsername != null && !obuUsername.isBlank()) {
            options.setUserName(obuUsername);
            options.setPassword(obuPassword.toCharArray());
        }
        if (obuBrokerUrl != null && obuBrokerUrl.startsWith("ssl://")) {
            options.setSocketFactory(SSLSocketFactory.getDefault());
            options.setHttpsHostnameVerificationEnabled(true);
        }
        factory.setConnectionOptions(options);
        return factory;
    }

    /**
     * Inbound adapter subscribing to the OBU topic on the external broker.
     * Pushes into the shared mqttInputChannel so the existing handler pipeline
     * is reused unchanged.
     */
    @Bean
    @ConditionalOnProperty(name = "mqtt.obu.enabled", havingValue = "true")
    public MqttPahoMessageDrivenChannelAdapter obuMqttInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(
                        resolveObuClientId(),
                        obuMqttClientFactory(),
                        obuTopic
                );
        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(mqttInputChannel());
        return adapter;
    }

}
