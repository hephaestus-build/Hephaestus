package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerNotifications;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackMessages;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackReplies;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnBackgroundWork;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wakes the one outbox executor for a completed answer; Slack never sees a partial model answer. It finds the turn's
 * question as the account that the change names, in a read-only transaction, reads no content, and wakes the executor
 * after that transaction. A
 * change that names no account, or a turn that belongs to another account, wakes nothing: a wake-up sends nothing
 * itself, and the executor's poll claims the post.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
@OwnerScope.StoredOwnerOperation
public class SlackConversationProjector implements ConversationLedgerNotifications.Listener {
    private static final Logger log = LoggerFactory.getLogger(SlackConversationProjector.class);

    private final ConversationSlackMessages messages;
    private final ConversationSlackReplies replies;
    private final ActionExecutor executor;
    private final OwnerScope owners;
    private final TransactionTemplate reads;

    public SlackConversationProjector(
            ConversationSlackMessages messages,
            ConversationSlackReplies replies,
            ActionExecutor executor,
            OwnerScope owners,
            PlatformTransactionManager transactions) {
        this.messages = messages;
        this.replies = replies;
        this.executor = executor;
        this.owners = owners;
        this.reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
    }

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
        long accountId = change.accountId();
        if (accountId <= 0) {
            log.debug(
                    "Slack post not woken, the change names no account: workspaceId={}, turnId={}",
                    change.workspaceId(),
                    change.turnId());
            return;
        }
        Optional<UUID> post = Objects.requireNonNull(reads.execute(status -> {
            owners.ofJob(accountId);
            return messages.endedSlackQuestion(change.workspaceId(), accountId, change.turnId())
                    .flatMap(question -> replies.forQuestion(change.workspaceId(), question));
        }));
        if (post.isEmpty()) {
            log.debug(
                    "Slack post not woken, no post of an ended Slack turn of the account: workspaceId={}, turnId={}",
                    change.workspaceId(),
                    change.turnId());
        }
        post.ifPresent(executor::runNow);
    }

    @Override
    public void resync() {
        /* The executor claims committed actions after a lost notification. */
    }
}
