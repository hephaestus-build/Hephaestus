package de.tum.cit.aet.hephaestus.testconfig;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.reactive.ClientHttpConnectorBuilderCustomizer;
import org.springframework.boot.http.client.reactive.ReactorClientHttpConnectorBuilder;
import org.springframework.context.annotation.Bean;

/**
 * Reactor Netty resolves names with its own DNS client, which the JDK resolver {@link OfflineNetwork} never sees;
 * the application's shared connector resolves through the JDK in tests instead. Registered as an auto-configuration
 * of the test classpath, so every test context applies it.
 */
@AutoConfiguration
public class OfflineConnectors {

    @Bean
    ClientHttpConnectorBuilderCustomizer<ReactorClientHttpConnectorBuilder> jdkResolvedConnector() {
        return builder ->
                builder.withHttpClientCustomizer(client -> client.resolver(DefaultAddressResolverGroup.INSTANCE));
    }
}
