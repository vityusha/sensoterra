package ru.sensoterra.server.health;

import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import ru.sensoterra.server.mqtt.MqttClientService;
import ru.sensoterra.server.mqtt.MqttConfiguration;

import java.net.URI;
import java.util.Map;

/**
 * MQTT broker connection in /health.
 * <p>
 * A lost broker is reported as {@link #DEGRADED}, not DOWN: the app still serves the UI and
 * history, and a DOWN here would turn /health into 503, mark the container unhealthy and make
 * deploy.sh roll back a release because of someone else's broker.
 */
@Singleton
public class MqttHealthIndicator implements HealthIndicator {

    /**
     * operational must stay null: HealthStatus.compareTo looks at severity only when operational
     * is unset, so with true it would rank equal to UP and the overall status would stay UP.
     * With null it sorts above UP and below DOWN. HTTP 200 comes from endpoints.health.status.http-mapping.
     */
    public static final HealthStatus DEGRADED =
            new HealthStatus("DEGRADED", "MQTT broker is not connected", null, 500);

    private final MqttClientService mqttClientService;
    private final MqttConfiguration config;

    public MqttHealthIndicator(MqttClientService mqttClientService, MqttConfiguration config) {
        this.mqttClientService = mqttClientService;
        this.config = config;
    }

    @Override
    public Publisher<HealthResult> getResult() {
        boolean connected = mqttClientService.isConnected();
        return Mono.just(HealthResult.builder("mqtt", connected ? HealthStatus.UP : DEGRADED)
                .details(Map.of("broker", brokerWithoutCredentials(), "connected", connected))
                .build());
    }

    /** tcp://user:pass@host:1883 -> tcp://host:1883 */
    private String brokerWithoutCredentials() {
        String broker = String.valueOf(config.getBroker());
        try {
            URI uri = URI.create(broker);
            if (uri.getUserInfo() == null) {
                return broker;
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
