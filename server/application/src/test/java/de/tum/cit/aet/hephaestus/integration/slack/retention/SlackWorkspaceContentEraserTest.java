package de.tum.cit.aet.hephaestus.integration.slack.retention;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

@Tag("unit")
class SlackWorkspaceContentEraserTest extends BaseUnitTest {

    @Mock
    private SlackMessageRepository slackMessageRepository;

    @Mock
    private SlackThreadRepository slackThreadRepository;

    @Mock
    private SlackMonitoredChannelRepository slackMonitoredChannelRepository;

    @Mock
    private ConversationEntryService conversationEntries;

    @Mock
    private ConversationService conversations;

    @Mock
    private SlackParticipantConsentRepository slackParticipantConsentRepository;

    @Mock
    private ConversationFeedbackErasure conversationFeedbackErasure;

    @InjectMocks
    private SlackWorkspaceContentEraser eraser;

    @Test
    void eraseWorkspace_dropsAllIngestedContentConsentAndDerivedFeedback() {
        eraser.eraseWorkspace(42L);

        // Derived CONVERSATION feedback first (before slack_thread, the artifact it points at).
        verify(conversationFeedbackErasure).eraseAllConversationForWorkspace(42L);
        // Ingested content.
        verify(slackMessageRepository).deleteByWorkspaceId(42L);
        verify(slackThreadRepository).deleteByWorkspaceId(42L);
        // Consent registrations (per-channel) + per-person opt-out records — the consent state is cleared.
        verify(slackMonitoredChannelRepository).deleteByWorkspaceId(42L);
        verify(slackParticipantConsentRepository).deleteByWorkspaceId(42L);
        // Every Hephaestus entry written in Slack, and the Slack DM identities of Hephaestus conversations.
        verify(conversationEntries).redactSlackEntries(42L);
        verify(conversationEntries).redactCopyingToolResultsWrittenBefore(eq(42L), any(), eq(Integer.MAX_VALUE));
        verify(conversations).clearSlackIdentities(42L);
    }
}
