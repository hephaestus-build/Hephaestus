package de.tum.cit.aet.hephaestus.integration.slack.events;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.privacy.spi.NativeId;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEventRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases a Slack channel from the workspace: revokes its consent so ingestion stops, and deletes its stored provider
 * events, its messages, its thread aggregates (which hold {@code participant_account_ids}) and the conversation feedback
 * derived from them. Live erasure redacts tool results that could have copied the stored channel content.
 * Replay bounds that redaction by the original erasure time and reads no Slack API.
 */
@Component
@ConditionalOnServerRole
public class SlackChannelErasure {
    private static final Logger log = LoggerFactory.getLogger(SlackChannelErasure.class);

    private final ProviderEventStore providerEvents;
    private final SlackThreadRepository threads;
    private final SlackMonitoredChannelRepository channels;
    private final SlackChannelConsentEventRepository consentEvents;
    private final ConversationFeedbackErasure conversationFeedback;
    private final SlackMessageRepository messages;

    private final ConversationEntryService conversationEntries;
    private final PrivacyJournal journal;

    public SlackChannelErasure(
            ProviderEventStore providerEvents,
            SlackThreadRepository threads,
            SlackMonitoredChannelRepository channels,
            SlackChannelConsentEventRepository consentEvents,
            ConversationFeedbackErasure conversationFeedback,
            SlackMessageRepository messages,
            ConversationEntryService conversationEntries,
            PrivacyJournal journal) {
        this.journal = journal;
        this.providerEvents = providerEvents;
        this.threads = threads;
        this.channels = channels;
        this.consentEvents = consentEvents;
        this.conversationFeedback = conversationFeedback;
        this.messages = messages;
        this.conversationEntries = conversationEntries;
    }

    @Transactional
    public void erase(long workspaceId, String channelId, Instant erasedAt) {
        eraseChannelRows(workspaceId, channelId);
        // Slack may deliver a deletion after its event time, while the channel content is still available.
        conversationEntries.redactCopyingToolResults(workspaceId, null);
        journal.record(
                PrivacyJournalKind.SLACK_CHANNEL_ERASED,
                Map.of("workspaceId", workspaceId, "channelId", new NativeId(channelId), "erasedAt", erasedAt));
        // A recovery manifest lists each channel erasure after its recovery point, by ids and time.
        TransactionCallbacks.afterCommit(() -> log.atInfo()
                .addKeyValue("event.name", "slack.channel_erased")
                .addKeyValue("workspace.id", workspaceId)
                .addKeyValue("slack.channel.id", channelId)
                .addKeyValue("slack.channel.erased_at", erasedAt)
                .log("A Slack channel was erased"));
    }

    @Transactional
    public void replay(long workspaceId, String channelId, Instant erasedAt) {
        var channel = channels.findForUpdate(workspaceId, channelId);
        // A registration after the cutoff starts a new period. The prior revoke or disconnect erased the old rows.
        boolean registeredAgain = channel.isPresent()
                && (channel.get().getCreatedAt().isAfter(erasedAt)
                        || consentEvents.existsByWorkspaceIdAndSlackChannelIdAndToStateAndCreatedAtAfter(
                                workspaceId, channelId, ConsentState.PENDING, erasedAt));
        if (!registeredAgain) eraseChannelRows(workspaceId, channelId);
        conversationEntries.redactCopyingToolResults(workspaceId, erasedAt);
    }

    private void eraseChannelRows(long workspaceId, String channelId) {
        providerEvents.deleteResource(workspaceId, "slack", channelId);
        List<Long> threadIds = threads.findIdsByWorkspaceIdAndSlackChannelId(workspaceId, channelId);
        channels.revokeConsent(workspaceId, channelId);
        conversationFeedback.eraseForThreads(workspaceId, threadIds);
        messages.deleteByWorkspaceIdAndSlackChannelId(workspaceId, channelId);
        threads.deleteByWorkspaceIdAndSlackChannelId(workspaceId, channelId);
    }
}
