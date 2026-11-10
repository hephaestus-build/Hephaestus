package de.tum.cit.aet.hephaestus.core;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application {@link Clock}. Deliberately not gated on the runtime role: beans that every context needs inject it.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
