package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wires the NATS-backed webhook publisher. Gated by {@code hephaestus.runtime.webhook.enabled} (default on)
 * AND requires the {@link Connection} bean from {@code config.NatsConfig} — the publisher
 * reuses that connection rather than opening a second one. See ADR 0008.
 *
 * <p>HTTP ingress guards live in {@link WebhookHttpConfiguration} so payload-size protection does
 * not depend on NATS availability.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebhookRole
// Gate on the specifically-named webhook/sync connection, NOT any Connection: matching an arbitrary
// Connection bean would activate these producers while `WebhookProducerBeans` still requires
// @Qualifier("natsConnection"), failing the whole context.
@ConditionalOnBean(name = "natsConnection")
@Import(WebhookProducerBeans.class)
public class WebhookConfiguration {

    @Bean
    WebhookHealthIndicator webhookHealthIndicator(
            @Qualifier("natsConnection") Connection connection, JetStreamManagement jsm) {
        return new WebhookHealthIndicator(connection, jsm);
    }
}
