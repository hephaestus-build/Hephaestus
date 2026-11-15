package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerNotifications;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackMessages;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackReplies;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnBackgroundWork;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Wakes the one outbox executor for a completed answer; Slack never sees a partial model answer. */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SlackConversationProjector implements ConversationLedgerNotifications.Listener {
    private final ConversationSlackMessages messages;
    private final ConversationSlackReplies replies;
    private final ActionExecutor executor;

    /**
     * Subscribes the projector to the ledger once the application is ready. The subscription is background work: the
     * projector then posts every Slack turn on its own threads. Tests drive a projector of their own.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnServerRole
    @ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
    @ConditionalOnBackgroundWork
    static class SubscriptionConfiguration {
        @Bean
        ApplicationListener<ApplicationReadyEvent> slackConversationProjectorSubscription(
                SlackConversationProjector projector, ConversationLedgerNotifications notifications) {
            return event -> notifications.subscribe(projector);
        }
    }

    @Override
    @Async
    public void turnChanged(ConversationLedgerEvents.TurnChanged change) {
        if (change.surface() != ConversationTurn.Surface.SLACK
                || !change.status().isTerminal()) return;
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
