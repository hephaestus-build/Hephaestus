package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.webhook.WebhookInFlightBudget;
import de.tum.cit.aet.hephaestus.integration.core.webhook.WebhookPayloadSizeFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The HTTP boundary of the interactivity Request URL on the server role, which owns the controller. The webhook role's
 * ingress guards bind {@code /webhooks/*} there only, so this path gets the same payload bound here before its body is
 * buffered whole for the signature check.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
class SlackInteractivityHttpConfiguration {

    static final String PATH = "/webhooks/slack/interactivity";

    @Bean
    FilterRegistrationBean<WebhookPayloadSizeFilter> slackInteractivityPayloadSizeFilter(
            WebhookProperties properties, MeterRegistry meterRegistry, WebhookInFlightBudget budget) {
        FilterRegistrationBean<WebhookPayloadSizeFilter> registration =
                new FilterRegistrationBean<>(new WebhookPayloadSizeFilter(properties, meterRegistry, budget));
        // Named apart from the webhook role's registration, whose name the servlet container would otherwise
        // refuse a second time in a process that runs both roles.
        registration.setName("slackInteractivityPayloadSizeFilter");
        registration.addUrlPatterns(PATH);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    FilterRegistrationBean<SlackInteractivityRawBodyFilter> slackInteractivityRawBodyFilter() {
        FilterRegistrationBean<SlackInteractivityRawBodyFilter> registration =
                new FilterRegistrationBean<>(new SlackInteractivityRawBodyFilter());
        registration.addUrlPatterns(PATH);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
