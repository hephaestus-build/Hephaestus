package de.tum.cit.aet.hephaestus.integration.slack.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelConsentGate;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackIngestService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentGate;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.identity.SlackRosterIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.ProviderEvents;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipRoster;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Slack message asks Slack for its author's name with no transaction open, then stores the message. Slack's
 * {@code users.info} can wait out a rate limit, and a transaction open across it would hold a pooled connection.
 */
@DisplayName("Slack ingest outside transactions")
class SlackIngestOutsideTransactionsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SlackMessageRepository messages;

    @Autowired
    private SlackThreadRepository threads;

    @Autowired
    private SlackMonitoredChannelRepository channels;

    @Autowired
    private SlackParticipantConsentRepository participantConsent;

    @Autowired
    private ConversationFeedbackErasure conversationFeedbackErasure;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private IdentityProviderRepository identityProviders;

    @Autowired
    private GitProviderRegistry providers;

    @Autowired
    private PeopleRegistry people;

    @Autowired
    private MembershipRoster memberships;

    @Autowired
    private PersonProcessingSuppression suppression;

    @Autowired
    private JdbcTemplate jdbc;

    private final SlackMessageService slack = mock(SlackMessageService.class);

    /** Whether a transaction was open when the ingest asked Slack for a name. */
    private final List<Boolean> transactionOpenAtLookup = new CopyOnWriteArrayList<>();

    private final String team = "T" + UUID.randomUUID().toString().substring(0, 8);
    private final String channel = "C" + UUID.randomUUID().toString().substring(0, 8);
    private long workspaceId;

    @BeforeEach
    void channelAndHandler() {
        workspaceId = workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("slack-outside-" + UUID.randomUUID()))
                .getId();
        var monitored = new SlackMonitoredChannel();
        monitored.setWorkspaceId(workspaceId);
        monitored.setSlackTeamId(team);
        monitored.setSlackChannelId(channel);
        monitored.setConsentState(SlackMonitoredChannel.ConsentState.ACTIVE);
        monitored.setConsentAnnouncedAt(Instant.ofEpochSecond(1));
        channels.save(monitored);
        if (identityProviders
                .findByTypeAndServerUrl(IdentityProviderType.SLACK, "https://slack.com")
                .isEmpty()) {
            identityProviders.saveAndFlush(new IdentityProvider(IdentityProviderType.SLACK, "https://slack.com"));
        }

        when(slack.humanDisplayName(workspaceId, team, "U1")).thenAnswer(invocation -> {
            transactionOpenAtLookup.add(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of("Ada");
        });
    }

    /** The NATS handler over the real ingest, consent gates and identity resolver; only Slack itself is scripted. */
    private SlackChannelMessageHandler handler() {
        SlackWorkspaceResolver workspaceResolver = mock(SlackWorkspaceResolver.class);
        when(workspaceResolver.resolveWorkspaceId(team)).thenReturn(Optional.of(workspaceId));
        var ingest = new SlackIngestService(
                workspaceResolver,
                channels,
                new SlackChannelConsentGate(channels),
                new SlackParticipantConsentGate(participantConsent),
                messages,
                threads,
                new SlackRosterIdentityResolver(providers, people, memberships, slack, suppression),
                conversationFeedbackErasure,
                mock(ProviderEventStore.class),
                true,
                new TransactionTemplate(transactionManager));
        return new SlackChannelMessageHandler(
                ingest,
                new EventPayloadDeserializer(JsonMapper.builder().build()),
                new TransactionTemplate(transactionManager));
    }

    @Test
    void shouldAskSlackForTheAuthorWithNoTransactionOpenAndStoreTheMessage() {
        String payload = """
                {"team_id":"%s","event":{"type":"message","channel":"%s","ts":"500.1","user":"U1","text":"hi"}}
                """.formatted(team, channel);

        handler()
                .onMessage(ProviderEvents.event(
                        "slack." + team + "." + channel + ".message", payload.getBytes(StandardCharsets.UTF_8)));

        assertThat(transactionOpenAtLookup).containsExactly(false);
        assertThat(jdbc.queryForObject("""
                        SELECT author_account_id IS NOT NULL FROM slack_message
                        WHERE workspace_id = ? AND slack_channel_id = ? AND slack_ts = ?
                        """, Boolean.class, workspaceId, channel, "500.1"))
                .isTrue();
    }

    @Test
    void shouldStoreNothingWhenTheAuthorOptsOutWhileSlackAnswers() {
        when(slack.humanDisplayName(workspaceId, team, "U2")).thenAnswer(invocation -> {
            participantConsent.optOutOfIngestion(workspaceId, "U2");
            return Optional.of("Grace");
        });
        String payload = """
                {"team_id":"%s","event":{"type":"message","channel":"%s","ts":"600.1","user":"U2","text":"bye"}}
                """.formatted(team, channel);

        handler()
                .onMessage(ProviderEvents.event(
                        "slack." + team + "." + channel + ".message", payload.getBytes(StandardCharsets.UTF_8)));

        assertThat(messages.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, channel, "600.1"))
                .isFalse();
    }
}
