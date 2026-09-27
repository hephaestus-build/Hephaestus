package de.tum.cit.aet.hephaestus.integration.slack.lifecycle;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Minimal lifecycle listener required by the integration framework bootstrap for every registered kind;
 * all methods fall through to the no-op defaults on {@link IntegrationLifecycleListener}.
 *
 * <p>Deactivation (disconnect/uninstall) does <b>not</b> hook in here: the Slack persistence layer
 * ({@code slack_message}, {@code slack_thread}, {@code slack_monitored_channel},
 * {@code slack_participant_consent}, {@code mentor_slack_thread}) is real and PII-bearing, and its GDPR
 * hard-erase runs synchronously inside the fenced disconnect transaction in
 * {@code SlackConnectionStrategy#eraseLocalData} (via {@code SlackWorkspaceContentEraser}) rather than on
 * an AFTER_COMMIT lifecycle event, as Outline's does.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true", matchIfMissing = false)
public class SlackLifecycleListener implements IntegrationLifecycleListener {

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.SLACK;
    }
}
