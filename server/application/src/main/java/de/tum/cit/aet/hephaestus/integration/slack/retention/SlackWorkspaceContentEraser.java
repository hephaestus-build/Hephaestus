package de.tum.cit.aet.hephaestus.integration.slack.retention;

import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erase raw and derived data in the caller's transaction, using {@link ConversationFeedbackErasure} across the module
 * boundary. The Heph messages written in Slack are erased and the Slack DM identities of Heph conversations are
 * forgotten; the conversations stay.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class SlackWorkspaceContentEraser {

    private final SlackMessageRepository slackMessageRepository;
    private final SlackThreadRepository slackThreadRepository;
    private final SlackMonitoredChannelRepository slackMonitoredChannelRepository;
    private final MentorEntryService mentorEntries;
    private final MentorConversationService mentorConversations;
    private final SlackParticipantConsentRepository slackParticipantConsentRepository;
    private final ConversationFeedbackErasure conversationFeedbackErasure;
    private final AgentActions actions;

    /** Idempotent: an already-erased (or never-populated) workspace deletes 0 rows. */
    @Transactional
    public void eraseWorkspace(long workspaceId) {
        actions.eraseKinds(
                workspaceId, List.of(ActionKind.SLACK_NOTICE, ActionKind.SLACK_JOIN, ActionKind.SLACK_CONSENT));
        conversationFeedbackErasure.eraseAllConversationForWorkspace(workspaceId);
        slackMessageRepository.deleteByWorkspaceId(workspaceId);
        slackThreadRepository.deleteByWorkspaceId(workspaceId);
        slackMonitoredChannelRepository.deleteByWorkspaceId(workspaceId);
        mentorEntries.redactSlackEntries(workspaceId);
        // The tool results that could have copied a channel message go with the messages they copied.
        mentorEntries.redactCopyingToolResultsWrittenBefore(workspaceId, Instant.now(), Integer.MAX_VALUE);
        mentorConversations.clearSlackIdentities(workspaceId);
        slackParticipantConsentRepository.deleteByWorkspaceId(workspaceId);
    }
}
