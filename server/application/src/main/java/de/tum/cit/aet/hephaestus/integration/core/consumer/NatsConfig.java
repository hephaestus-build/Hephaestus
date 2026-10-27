package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ReceiverComponent;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@ConditionalOnProperty(name = "hephaestus.sync.nats.enabled", havingValue = "true")
@ReceiverComponent
public class NatsConfig {

    private final NatsConnectionProperties natsProperties;

    private final Environment environment;

    public NatsConfig(NatsConnectionProperties natsProperties, Environment environment) {
        this.natsProperties = natsProperties;
        this.environment = environment;
    }

    @Bean
    public @Nullable Connection natsConnection() throws Exception {
        if (environment.matchesProfiles("specs")) {
            return null;
        }

        if (natsProperties.server() == null || natsProperties.server().isBlank()) {
            throw new IllegalStateException("NATS server configuration is missing.");
        }

        Options options = NatsOptions.builder(natsProperties).maxReconnects(-1).build();
        return Nats.connect(options);
    }
}
