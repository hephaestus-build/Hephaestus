package de.tum.cit.aet.hephaestus.integration.slack.retention;

import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackDmProducerPinRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
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

    @Mock
    private AgentActions actions;

    @Mock
    private SlackDmProducerPinRepository pins;

    @Mock
    private ProviderEventStore events;

    @InjectMocks
    private SlackWorkspaceContentEraser eraser;

    @Test
    void eraseWorkspace_dropsAllIngestedContentConsentAndDerivedFeedback() {
        eraser.eraseWorkspace(42L);

        verify(pins).deleteByWorkspaceId(42L);
        verify(events).deleteHandledByHeader(42L, "slack", ProviderEvent.PRODUCER, "MENTOR");

        // The Slack notices, joins and consent announcements of the workspace.
        verify(actions)
                .eraseKinds(
                        42L,
                        List.of(
                                ActionKind.SLACK_NOTICE,
                                ActionKind.SLACK_JOIN,
                                ActionKind.SLACK_CONSENT,
                                ActionKind.MENTOR_EFFECT));
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
        verify(conversationEntries).redactCopyingToolResults(42L, null);
        verify(conversations).clearSlackIdentities(42L);
    }
}
