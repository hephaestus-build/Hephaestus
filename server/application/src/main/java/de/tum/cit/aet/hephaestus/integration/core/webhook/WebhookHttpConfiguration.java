package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** HTTP guards for the public webhook ingress. Independent of provider inbox publisher availability. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
public class WebhookHttpConfiguration {

    @Bean
    FilterRegistrationBean<WebhookPayloadSizeFilter> webhookPayloadSizeFilter(
            WebhookProperties properties, MeterRegistry meterRegistry, WebhookInFlightBudget budget) {
        FilterRegistrationBean<WebhookPayloadSizeFilter> registration =
                new FilterRegistrationBean<>(new WebhookPayloadSizeFilter(properties, meterRegistry, budget));
        registration.addUrlPatterns("/webhooks/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
