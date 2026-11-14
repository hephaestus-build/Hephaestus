package de.tum.cit.aet.hephaestus.integration.slack.retention;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erase raw and derived data in the caller's transaction, using {@link ConversationFeedbackErasure} across the module
 * boundary. The Hephaestus messages written in Slack are erased and the Slack DM identities of Hephaestus conversations are
 * forgotten; the conversations stay.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class SlackWorkspaceContentEraser {

    private final SlackMessageRepository slackMessageRepository;
    private final SlackThreadRepository slackThreadRepository;
    private final SlackMonitoredChannelRepository slackMonitoredChannelRepository;
    private final ConversationEntryService conversationEntries;
    private final ConversationService conversations;
    private final SlackParticipantConsentRepository slackParticipantConsentRepository;
    private final ConversationFeedbackErasure conversationFeedbackErasure;

    /** Idempotent: an already-erased (or never-populated) workspace deletes 0 rows. */
    @Transactional
    public void eraseWorkspace(long workspaceId) {
        conversationFeedbackErasure.eraseAllConversationForWorkspace(workspaceId);
        slackMessageRepository.deleteByWorkspaceId(workspaceId);
        slackThreadRepository.deleteByWorkspaceId(workspaceId);
        slackMonitoredChannelRepository.deleteByWorkspaceId(workspaceId);
        conversationEntries.redactSlackEntries(workspaceId);
        // The tool results that could have copied a channel message go with the messages they copied.
        conversationEntries.redactCopyingToolResultsWrittenBefore(workspaceId, Instant.now(), Integer.MAX_VALUE);
        conversations.clearSlackIdentities(workspaceId);
        slackParticipantConsentRepository.deleteByWorkspaceId(workspaceId);
    }
}
