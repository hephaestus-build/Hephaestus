package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.api.model.block.LayoutBlock;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestNotes;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestPointer;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestSubscriptions;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestWritten;
import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.SlackHephaestusUiLinks;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDraft;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackDelivery;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestPayload;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestWindow;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/** The Slack note that a weekly digest is ready: one top-level DM message, sent once, with no contents. */
class SlackWeeklyDigestPointerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String TEAM = "T1";
    private static final String SLACK_USER = "U1";
    private static final String DM = "D77";
    private static final long ACCOUNT_ID = 5150L;
    private static final WeeklyDigestWindow WEEK =
            new WeeklyDigestWindow(LocalDate.parse("2026-10-05"), ZoneId.of("Europe/Berlin"));

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorEntryService entries;

    @Autowired
    private MentorEntryRepository entryRepository;

    @Autowired
    private MentorSlackDelivery delivery;

    @Autowired
    private WeeklyDigestNotes notes;

    @Autowired
    private WorkspaceSummaryQuery workspaces;

    @Autowired
    private ObjectMapper objectMapper;

    private final SlackMessageService slack = mock(SlackMessageService.class);
    private final ConnectionService connections = mock(ConnectionService.class);
    private final SlackMentorIdentityResolver identities = mock(SlackMentorIdentityResolver.class);
    private final WeeklyDigestSubscriptions subscriptions = mock(WeeklyDigestSubscriptions.class);

    private Workspace workspace;
    private User developer;
    private MentorConversation main;
    private SlackWeeklyDigestPointer pointer;

    @BeforeEach
    void setUpPointer() {
        developer = persistUser("weekly-digest-slack-developer");
        workspace = createWorkspace("digest-slack", "Delivery team", "digest-slack", AccountType.ORG, developer);
        main = conversations.findOrCreateMain(workspace.getId(), developer.getId(), Set.of(developer.getId()));
        when(slack.isConnected(workspace.getId())).thenReturn(true);
        when(connections.findActive(workspace.getId(), IntegrationKind.SLACK))
                .thenReturn(Optional.of(new Connection(
                        workspace,
                        IntegrationKind.SLACK,
                        TEAM,
                        new ConnectionConfig.SlackConfig(TEAM, "not-a-key", null, Set.of()))));
        when(identities.resolveSlackUserId(developer.getId(), TEAM)).thenReturn(Optional.of(SLACK_USER));
        when(identities.resolveAccountId(TEAM, SLACK_USER)).thenReturn(Optional.of(ACCOUNT_ID));
        when(subscriptions.enabledSince(ACCOUNT_ID)).thenReturn(Optional.of(Instant.parse("2026-01-01T00:00:00Z")));
        pointer = new SlackWeeklyDigestPointer(
                slack,
                connections,
                identities,
                new SlackMentorPoster(slack, delivery),
                conversations,
                notes,
                subscriptions,
                workspaces,
                new SlackHephaestusUiLinks("https://hephaestus.example", new WorkspaceSubdomainProperties(false, "")));
    }

    @Test
    void shouldPostOneNoteWithoutContentsAndLearnTheDmWhenADigestIsWritten() {
        MentorEntry digest = digest();
        when(slack.postMessage(eq(workspace.getId()), eq(SLACK_USER), isNull(), anyList(), anyString(), any()))
                .thenReturn(new SlackMessageService.Posted(DM, "300.1"));

        pointer.on(written(digest, WeeklyDigestPointer.SLACK));
        pointer.on(written(digest, WeeklyDigestPointer.SLACK));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LayoutBlock>> blocks = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(slack, times(1))
                .postMessage(eq(workspace.getId()), eq(SLACK_USER), isNull(), blocks.capture(), text.capture(), any());
        assertThat(text.getValue()).isEqualTo("Your weekly digest for 5–11 October is ready in Hephaestus.");
        String rendered = blocks.getValue().toString();
        assertThat(rendered)
                .contains(
                        "*Your weekly digest is ready*\n5–11 October · Delivery team",
                        SlackWeeklyDigestPointer.OPEN_ACTION,
                        "https://hephaestus.example/w/digest-slack/mentor?entry=" + digest.getId(),
                        SlackWeeklyDigestPointer.TURN_OFF_ACTION,
                        "Reply in this thread to go through it with Heph.")
                .doesNotContainPattern("\\d+ (pieces|piece) of feedback");

        MentorEntry posted = entryRepository.findById(digest.getId()).orElseThrow();
        assertThat(posted.getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(posted.getSlackTs()).isEqualTo("300.1");
        MentorConversation bound = conversations.require(workspace.getId(), main.getId());
        assertThat(bound.getSlackChannelId()).isEqualTo(DM);
        assertThat(conversations.findMainBySlack(workspace.getId(), TEAM, SLACK_USER, DM))
                .map(MentorConversation::getId)
                .contains(main.getId());
    }

    @Test
    void shouldPostNothingWhenTheAccountTurnedTheDigestOffOrOnOnlyAfterItWasWritten() {
        MentorEntry digest = digest();
        when(subscriptions.enabledSince(ACCOUNT_ID)).thenReturn(Optional.empty());
        pointer.on(written(digest, WeeklyDigestPointer.SLACK));

        when(subscriptions.enabledSince(ACCOUNT_ID))
                .thenReturn(Optional.of(Instant.now().plusSeconds(60)));
        pointer.on(written(digest, WeeklyDigestPointer.SLACK));

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(entryRepository.findById(digest.getId()).orElseThrow().getSlackDelivery())
                .isNull();
    }

    @Test
    void shouldPostNothingWhenTheNoteGoesByEmail() {
        pointer.on(written(digest(), WeeklyDigestPointer.EMAIL));

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    void shouldFindTheNoteInTheDmInsteadOfPostingItAgainWhenTheReconcilerSettlesIt() {
        conversations.bindSlackIdentity(workspace.getId(), main.getId(), TEAM, SLACK_USER, DM);
        MentorEntry digest = digest();
        delivery.claim(workspace.getId(), digest.getId());
        when(slack.findWorkspaceMessages(eq(workspace.getId()), eq(DM), isNull(), anyString(), any()))
                .thenReturn(List.of("300.5"));
        SlackMentorReconciler reconciler = new SlackMentorReconciler(
                delivery,
                mock(SlackMentorProjector.class),
                pointer,
                Clock.fixed(Instant.now().plus(Duration.ofMinutes(5)), ZoneOffset.UTC));

        reconciler.reconcile();

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        MentorEntry settled = entryRepository.findById(digest.getId()).orElseThrow();
        assertThat(settled.getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(settled.getSlackTs()).isEqualTo("300.5");
    }

    @Test
    void shouldPostNothingWhenTheReconcilerSettlesANoteTheAccountTurnedOffSince() {
        MentorEntry digest = digest();
        delivery.claim(workspace.getId(), digest.getId());
        when(subscriptions.enabledSince(ACCOUNT_ID)).thenReturn(Optional.empty());
        SlackMentorReconciler reconciler = new SlackMentorReconciler(
                delivery,
                mock(SlackMentorProjector.class),
                pointer,
                Clock.fixed(Instant.now().plus(Duration.ofMinutes(5)), ZoneOffset.UTC));

        reconciler.reconcile();

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(entryRepository.findById(digest.getId()).orElseThrow().getSlackTs())
                .isNull();
    }

    @Test
    void shouldPostAgainWhenTheReconcilerCannotReadAnUnknownDm() {
        MentorEntry digest = digest();
        delivery.claim(workspace.getId(), digest.getId());
        when(slack.postMessage(eq(workspace.getId()), eq(SLACK_USER), isNull(), anyList(), anyString(), any()))
                .thenReturn(new SlackMessageService.Posted(DM, "300.9"));

        pointer.reconcile(workspace.getId(), digest.getId());

        verify(slack, never()).findWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any());
        assertThat(entryRepository.findById(digest.getId()).orElseThrow().getSlackTs())
                .isEqualTo("300.9");
    }

    @Test
    void shouldReachTheDeveloperOnlyWhenTheyHaveASlackIdentityInTheConnectedTeam() {
        assertThat(pointer.reaches(workspace.getId(), ACCOUNT_ID, developer.getId()))
                .isTrue();

        when(identities.resolveSlackUserId(developer.getId(), TEAM)).thenReturn(Optional.empty());

        assertThat(pointer.reaches(workspace.getId(), ACCOUNT_ID, developer.getId()))
                .isFalse();
    }

    private MentorEntry digest() {
        return entries.appendOnce(
                        workspace.getId(),
                        main.getId(),
                        MentorEntryDraft.digest(
                                "Weekly digest for 5–11 October 2026.",
                                new WeeklyDigestPayload(WEEK, List.of(), List.of()).toJson(objectMapper),
                                "weekly-digest:" + workspace.getId() + ":" + developer.getId() + ":"
                                        + UUID.randomUUID()))
                .entry();
    }

    private WeeklyDigestWritten written(MentorEntry digest, WeeklyDigestPointer to) {
        return new WeeklyDigestWritten(digest.getId(), workspace.getId(), ACCOUNT_ID, WEEK.days(), to, Instant.now());
    }
}
