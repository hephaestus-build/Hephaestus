package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The HTTP boundary of the interactivity Request URL. The receiver's payload bound on {@code /webhooks/*} runs first,
 * so the body is bounded before this filter buffers it whole for the signature check.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
class SlackInteractivityHttpConfiguration {

    static final String PATH = "/webhooks/slack/interactivity";

    @Bean
    FilterRegistrationBean<SlackInteractivityRawBodyFilter> slackInteractivityRawBodyFilter() {
        FilterRegistrationBean<SlackInteractivityRawBodyFilter> registration =
                new FilterRegistrationBean<>(new SlackInteractivityRawBodyFilter());
        registration.addUrlPatterns(PATH);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
