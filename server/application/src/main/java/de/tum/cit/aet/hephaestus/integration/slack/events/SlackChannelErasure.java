package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases a Slack channel from the workspace: revokes its consent so ingestion stops, and deletes its stored provider
 * events, its messages, its thread aggregates (which hold {@code participant_account_ids}) and the conversation feedback
 * derived from them. Idempotent: an unknown or already erased channel deletes nothing. It reads no Slack API, so
 * erasure recovery runs it while Slack is turned off.
 */
@Component
@ConditionalOnServerRole
public class SlackChannelErasure {
    private static final Logger log = LoggerFactory.getLogger(SlackChannelErasure.class);

    private final ProviderEventStore providerEvents;
    private final SlackThreadRepository threads;
    private final SlackMonitoredChannelRepository channels;
    private final ConversationFeedbackErasure conversationFeedback;
    private final SlackMessageRepository messages;

    public SlackChannelErasure(
            ProviderEventStore providerEvents,
            SlackThreadRepository threads,
            SlackMonitoredChannelRepository channels,
            ConversationFeedbackErasure conversationFeedback,
            SlackMessageRepository messages) {
        this.providerEvents = providerEvents;
        this.threads = threads;
        this.channels = channels;
        this.conversationFeedback = conversationFeedback;
        this.messages = messages;
    }

    @Transactional
    public void erase(long workspaceId, String channelId) {
        providerEvents.deleteResource(workspaceId, "slack", channelId);
        List<Long> threadIds = threads.findIdsByWorkspaceIdAndSlackChannelId(workspaceId, channelId);
        channels.revokeConsent(workspaceId, channelId);
        conversationFeedback.eraseForThreads(workspaceId, threadIds);
        messages.deleteByWorkspaceIdAndSlackChannelId(workspaceId, channelId);
        threads.deleteByWorkspaceIdAndSlackChannelId(workspaceId, channelId);
        // An erasure recovery manifest lists each channel erasure after its recovery point: a restore brings the
        // channel back. Logged only after commit, by ids alone.
        TransactionCallbacks.afterCommit(() -> log.atInfo()
                .addKeyValue("event.name", "slack.channel_erased")
                .addKeyValue("workspace.id", workspaceId)
                .addKeyValue("slack.channel.id", channelId)
                .log("A Slack channel was erased"));
    }
}
