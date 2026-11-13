package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackMessages;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackReplies;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Wakes the one outbox executor for a completed answer; Slack never sees a partial model answer. */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SlackMentorProjector implements MentorLedgerNotifications.Listener {
    private final MentorSlackMessages messages;
    private final MentorSlackReplies replies;
    private final ActionExecutor executor;

    /**
     * Subscribes the projector to the ledger once the application is ready. The test profile drives a projector of its
     * own and subscribes none, since a subscribed projector reads every later test's Slack turns on its own threads. The
     * build profiles serve no turn, and their database cannot {@code LISTEN}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnServerRole
    @ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
    @Profile("!test & !specs & !cds-training")
    static class SubscriptionConfiguration {
        @Bean
        ApplicationListener<ApplicationReadyEvent> slackMentorProjectorSubscription(
                SlackMentorProjector projector, MentorLedgerNotifications notifications) {
            return event -> notifications.subscribe(projector);
        }
    }

    @Override
    @Async
    public void turnChanged(MentorLedgerEvents.TurnChanged change) {
        if (change.surface() != MentorTurn.Surface.SLACK || !change.status().isTerminal()) return;
        messages.slackTurn(change.workspaceId(), change.turnId())
                .flatMap(turn -> replies.forQuestion(
                        change.workspaceId(), turn.question().getId()))
                .ifPresent(executor::runNow);
    }

    @Override
    public void resync() {
        /* The executor claims committed actions after a lost notification. */
    }
}
