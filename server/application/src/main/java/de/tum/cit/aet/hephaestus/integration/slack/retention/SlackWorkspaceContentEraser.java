package de.tum.cit.aet.hephaestus.integration.slack.retention;

import de.tum.cit.aet.hephaestus.integration.slack.domain.MentorSlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single workspace-scoped choke point that erases every Slack-owned row for a workspace — the
 * counterpart at connection/workspace scope of {@code SlackIngestService#eraseChannel} at channel scope.
 * Erases the ingested channel content ({@code slack_message} PII, {@code slack_thread} aggregates), the
 * per-channel consent registrations ({@code slack_monitored_channel}), the per-person opt-out records
 * ({@code slack_participant_consent}), the mentor DM threads ({@code mentor_slack_thread}), and the
 * derived {@code chat.conversation_thread} observations/feedback (through the practices
 * {@link ConversationFeedbackErasure} port so no Spring Modulith cycle forms).
 *
 * <p>Shared by two callers so both erase the exact same rows: the workspace-purge contributor (full
 * tenant teardown) and the connection disconnect ({@code SlackConnectionStrategy#eraseLocalData} — a GDPR
 * hard-erase so nothing ingested outlives the connection, mirroring Outline's disconnect-erase). {@code
 * @Transactional} with the default {@code REQUIRED} propagation, so it joins the caller's transaction (the
 * purge chain's single tx, or the fenced {@code ConnectionService#disconnect} tx) rather than opening its own.
 */
@Component
@RequiredArgsConstructor
public class SlackWorkspaceContentEraser {

    private final SlackMessageRepository slackMessageRepository;
    private final SlackThreadRepository slackThreadRepository;
    private final SlackMonitoredChannelRepository slackMonitoredChannelRepository;
    private final MentorSlackThreadRepository mentorSlackThreadRepository;
    private final SlackParticipantConsentRepository slackParticipantConsentRepository;
    private final ConversationFeedbackErasure conversationFeedbackErasure;

    /** Idempotent: an already-erased (or never-populated) workspace deletes 0 rows. */
    @Transactional
    public void eraseWorkspace(long workspaceId) {
        conversationFeedbackErasure.eraseAllConversationForWorkspace(workspaceId);
        slackMessageRepository.deleteByWorkspaceId(workspaceId);
        slackThreadRepository.deleteByWorkspaceId(workspaceId);
        slackMonitoredChannelRepository.deleteByWorkspaceId(workspaceId);
        mentorSlackThreadRepository.deleteByWorkspaceId(workspaceId);
        slackParticipantConsentRepository.deleteByWorkspaceId(workspaceId);
    }
}
