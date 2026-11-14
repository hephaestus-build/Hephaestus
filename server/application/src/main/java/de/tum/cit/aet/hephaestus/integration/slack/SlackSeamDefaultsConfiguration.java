package de.tum.cit.aet.hephaestus.integration.slack;

import de.tum.cit.aet.hephaestus.integration.slack.events.KeywordSlackConversationInputGuard;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackConversationInputGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the overridable default bean for the Slack conversation input guard.
 *
 * <p>The default lives on a {@code @Bean} factory method rather than a component-scanned {@code @Component}:
 * {@code @ConditionalOnMissingBean} is only evaluated deterministically for {@code @Bean} methods, so on a
 * scanned component it left the default unregistered and broke context startup.
 */
@Configuration
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
class SlackSeamDefaultsConfiguration {

    @Bean
    @ConditionalOnMissingBean(SlackConversationInputGuard.class)
    SlackConversationInputGuard slackConversationInputGuard() {
        return new KeywordSlackConversationInputGuard();
    }
}
