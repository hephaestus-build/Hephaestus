package de.tum.cit.aet.hephaestus.agent.attempt;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWorkerRole
public class AttemptTokenConfiguration {

    @Bean
    AttemptSigningKey attemptSigningKey() {
        return AttemptSigningKey.generate();
    }

    @Bean
    AttemptTokenVerifier attemptTokenVerifier(AttemptSigningKey key, MeterRegistry meterRegistry) {
        return new AttemptTokenVerifier(key, meterRegistry);
    }

    @Bean
    AttemptTokenIssuer attemptTokenIssuer(AttemptSigningKey key) {
        return new AttemptTokenIssuer(key);
    }
}
