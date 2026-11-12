package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.nats.client.Connection;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wires the NATS-backed webhook publisher. Gated on the server role
 * AND requires the {@link Connection} bean from {@code integration.core.consumer.NatsConfig} — the publisher
 * reuses that connection rather than opening a second one.
 *
 * <p>HTTP ingress guards live in {@link WebhookHttpConfiguration} so payload-size protection does
 * not depend on NATS availability.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
// Gate on the specifically-named webhook/sync connection, NOT any Connection: matching an arbitrary
// Connection bean would activate these producers while `WebhookProducerBeans` still requires
// @Qualifier("natsConnection"), failing the whole context.
@ConditionalOnBean(name = "natsConnection")
@Import(WebhookProducerBeans.class)
public class WebhookConfiguration {

    @Bean
    WebhookHealthIndicator webhookHealthIndicator(@Qualifier("natsConnection") Connection connection) {
        return new WebhookHealthIndicator(connection);
    }
}
