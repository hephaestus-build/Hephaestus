package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationAdmission;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationRefusal;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationRejectedException;
import de.tum.cit.aet.hephaestus.conversation.ConversationRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackMessages;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackReplies;
import de.tum.cit.aet.hephaestus.conversation.ConversationTimeline;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnClaims;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveConnectionGuard;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.consumer.DeliveryInbox;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationPoisonHandler;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventConsumerProperties;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.SlackProperties;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackNoticeActions;
import de.tum.cit.aet.hephaestus.integration.slack.credentials.SlackCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackInteractiveReplyAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMembershipUnavailableException;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.slack.metrics.SlackMetrics;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.integration.slack.webhook.SlackConversationDmMessageHandler;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationReadinessQuery;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.UnchangedAuthority;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** What the developer does in their Slack DM with Hephaestus, and the ledger rows it writes. */
class SlackConversationServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ConversationTurnClaims claims;

    private static final String TEAM = "T1";
    private static final String OTHER_TEAM = "T2";
    private static final String CHANNEL = "D9";
    private static final String SLACK_USER = "U1";
    private static final String FULL_MEMBER = """
            {"ok":true,"user":{"id":"U1","team_id":"T1","deleted":false,"is_restricted":false,
            "is_ultra_restricted":false,"is_bot":false,"is_app_user":false}}
            """;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ActiveConnectionGuard activeConnections;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ConversationSlackMessages messages;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTimeline timeline;

    @Autowired
    private ConversationEntryRepository entryRepository;

    @Autowired
    private ConversationTurnRepository turnRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private SlackWorkspaceContentEraser eraser;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProviderEventStore providerEvents;

    @Autowired
    private DeliveryInbox inbox;

    @Autowired
    private IntegrationPoisonHandler inboxFailures;

    @Autowired
    private ProviderEventConsumerProperties consumerProperties;

    @Autowired
    private EventPayloadDeserializer eventPayloads;

    @Autowired
    private AgentActions actions;

    @Autowired
    private GitProviderRegistry registry;

    @Autowired
    private ObjectMapper mapper;

    private final MockWebServer slackApi = new MockWebServer();
    private final AtomicReference<MockResponse> membership = new AtomicReference<>(slackJson(FULL_MEMBER));
    private final ConcurrentLinkedQueue<MockResponse> lookupResponses = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> ephemeralReplies = new ConcurrentLinkedQueue<>();
    private final AtomicReference<MockResponse> ephemeralResponse =
            new AtomicReference<>(slackJson("{\"ok\":true,\"message_ts\":\"101.1\"}"));
    private final SimpleMeterRegistry slackMeters = new SimpleMeterRegistry();
    private final SlackConversationIdentityResolver identities = mock(SlackConversationIdentityResolver.class);
    private final ConversationReadinessQuery readiness = mock(ConversationReadinessQuery.class);
    private final ConversationAdmission admission = mock(ConversationAdmission.class);

    private Workspace workspace;
    private Workspace otherWorkspace;
    private long developerId;
    private SlackMessageService slack;
    private SlackConversationService service;

    @BeforeEach
    void setUpSlack() throws IOException {
        slackApi.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getUrl().encodedPath();
                String auth = request.getHeaders().get("Authorization");
                if ("/api/auth.test".equals(path)) {
                    return slackJson("{\"ok\":true,\"team_id\":\"T1\",\"user_id\":\"UBOT\"}");
                }
                if ("/api/chat.postEphemeral".equals(path) && ("Bearer xoxb-test-" + TEAM).equals(auth)) {
                    var body = Objects.requireNonNull(request.getBody()).utf8();
                    ephemeralReplies.add(URLDecoder.decode(body, StandardCharsets.UTF_8));
                    return ephemeralResponse.get();
                }
                if (!"/api/users.info".equals(path)) {
                    return new MockResponse.Builder().code(404).build();
                }
                if (("Bearer xoxb-test-" + TEAM).equals(auth)) {
                    var next = lookupResponses.poll();
                    return next == null ? membership.get() : next;
                }
                if (("Bearer xoxb-test-" + OTHER_TEAM).equals(auth))
                    return slackJson(FULL_MEMBER.replace(TEAM, OTHER_TEAM));
                return slackJson("{\"ok\":false,\"error\":\"invalid_auth\"}");
            }
        });
        slackApi.start();
        var credentials = mock(SlackCredentialProvider.class);
        slack = new SlackMessageService(
                credentials,
                new SlackRateLimitTracker(slackMeters),
                new OutboundEgressGuard(() -> false),
                new SlackMetrics(slackMeters),
                new SlackProperties(slackApi.url("/").toString()));

        User developer = persistUser("slack-ask-developer");
        developerId = accountId(developer);
        workspace = createWorkspace("slack-ask", "Slack Hephaestus", "slack-ask", AccountType.ORG, developer);
        otherWorkspace =
                createWorkspace("slack-ask-2", "Slack Hephaestus 2", "slack-ask-2", AccountType.ORG, developer);
        for (Workspace each : List.of(workspace, otherWorkspace)) {
            String team = each.getId().equals(workspace.getId()) ? TEAM : OTHER_TEAM;
            Connection slackConnection = new Connection(
                    each, IntegrationKind.SLACK, team, new ConnectionConfig.SlackConfig(team, "Slack", null, Set.of()));
            ConnectionFixtures.reachState(slackConnection, IntegrationState.ACTIVE);
            connectionRepository.save(slackConnection);
            when(credentials.resolve(new IntegrationRef(IntegrationKind.SLACK, each.getId(), null)))
                    .thenReturn(Optional.of(new BearerToken("xoxb-test-" + team, null)));
            when(identities.resolveActiveAccountId(each.getId(), team, SLACK_USER))
                    .thenReturn(Optional.of(developerId));
            when(readiness.isReady(each.getId())).thenReturn(true);
            when(admission.refusal(each.getId(), developerId)).thenReturn(Optional.empty());
        }
        service = new SlackConversationService(
                new SlackWorkspaceResolver(connectionService, activeConnections),
                slack,
                new SlackInteractiveReplyAdapter(slack),
                new SlackNoticeActions(actions, new UnchangedAuthority(), mapper, registry),
                identities,
                new KeywordSlackConversationInputGuard(),
                readiness,
                admission,
                conversations,
                entries,
                turns,
                timeline);
    }

    @AfterEach
    void closeSlack() throws IOException {
        slack.close();
        slackApi.close();
        slackMeters.close();
    }

    private static MockResponse slackJson(String body) {
        return new MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private void assertNotice(String messageTs, @Nullable String threadTs, String text, boolean linkAccount) {
        String key = "slack_notice:" + workspace.getId() + ":" + TEAM + ":" + CHANNEL + ":" + messageTs;
        var rows = jdbc.queryForList(
                "SELECT kind,state,payload->>'channelId' AS channel,native_recipient->>'subject' AS recipient,payload->>'threadTs' AS thread,payload->>'text' AS text,payload->>'linkAccount' AS link FROM agent_action WHERE workspace_id=? AND idempotency_key=?",
                workspace.getId(),
                key);
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row)
                .containsEntry("kind", "SLACK_NOTICE")
                .containsEntry("state", "ADMITTED")
                .containsEntry("channel", CHANNEL)
                .containsEntry("recipient", SLACK_USER)
                .containsEntry("thread", threadTs)
                .containsEntry("text", text)
                .containsEntry("link", Boolean.toString(linkAccount));
    }

    private Conversation main() {
        return conversationRepository.findMain(workspace.getId(), developerId).orElseThrow();
    }

    private ConversationEntry question(String ts) {
        return entryRepository
                .findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D9:" + ts)
                .orElseThrow();
    }

    private Optional<ConversationEntry> maybeQuestion(String ts) {
        return entryRepository.findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D9:" + ts);
    }

    /** Nothing of the DM reached the ledger: no conversation, no question. */
    private void assertNothingStored(String ts) {
        assertThat(conversationRepository.findMain(workspace.getId(), developerId))
                .isEmpty();
        assertThat(maybeQuestion(ts)).isEmpty();
        assertThat(turnRepository.findByWorkspaceIdAndRequestId(
                        workspace.getId(), SlackConversationService.requestId(TEAM, CHANNEL, ts)))
                .isEmpty();
    }

    private ConversationTurn turnOf(ConversationEntry question) {
        return turnRepository
                .findById(Objects.requireNonNull(question.getTurnId()))
                .orElseThrow();
    }

    /** Runs the main conversation's turn to an answer that Slack shows at {@code ts}. */
    private ConversationEntry answerPostedAt(String ts) {
        ConversationTurn running = claims.claimNext().orElseThrow();
        ObjectNode pi = ConversationLedgerFixtures.answer("a1b2c3d4", "Your reviews are quick.");
        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(ConversationTurn.Status.DONE, List.of(pi), null, null));
        UUID answerId = Objects.requireNonNull(
                turns.answer(workspace.getId(), running.getId()).answerEntryId());
        messages.link(workspace.getId(), answerId, ts);
        return entryRepository.findById(answerId).orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldStoreAQuestionWhenTheFullMemberWritesATopLevelDm(boolean includesDeleted) {
        if (!includesDeleted) membership.set(slackJson(FULL_MEMBER.replace("\"deleted\":false,", "")));
        var claimed = claimedDm();
        dispatchDm(claimed);
        assertThat(providerEvents.complete(claimed.id(), claimed.leaseEpoch())).isTrue();

        Conversation main = main();
        assertThat(main.getSlackTeamId()).isEqualTo(TEAM);
        assertThat(main.getSlackUserId()).isEqualTo(SLACK_USER);
        assertThat(main.getSlackChannelId()).isEqualTo(CHANNEL);
        ConversationEntry question = question("100.1");
        assertThat(question.getConversationId()).isEqualTo(main.getId());
        assertThat(question.getKind()).isEqualTo(ConversationEntry.Kind.USER);
        assertThat(question.getSurface()).isEqualTo(ConversationEntry.Surface.SLACK);
        assertThat(question.getSlackTs()).isEqualTo("100.1");
        assertThat(question.getText()).isEqualTo("How is my review practice?");
        ConversationTurn turn = turnOf(question);
        assertThat(turn.getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
        assertThat(turn.getSurface()).isEqualTo(ConversationTurn.Surface.SLACK);
        assertThat(ephemeralReplies).isEmpty();
        assertLookup("confirmed", "none", 1);
    }

    @Test
    void shouldStoreOneQuestionWhenSlackDeliversTheSameDmTwice() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");

        ConversationEntry question = question("100.1");
        assertThat(turnRepository.findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D9:100.1"))
                .hasValueSatisfying(turn -> assertThat(turn.getId()).isEqualTo(question.getTurnId()));
        assertThat(entryRepository.findMaxSeqByWorkspaceIdAndConversationId(workspace.getId(), main().getId()))
                .isEqualTo(question.getSeq());
    }

    private ProviderEventStore.Claimed claimedDm() {
        byte[] payload = """
                {"team_id":"T1","event":{"type":"message","channel_type":"im","channel":"D9",
                 "user":"U1","text":"How is my review practice?","ts":"100.1"}}
                """.getBytes(StandardCharsets.UTF_8);
        var arrival = new ProviderEventStore.Arrival(
                workspace.getId(),
                "slack",
                "slack.T1.message_im",
                "slack-notice-" + UUID.randomUUID(),
                Map.of(),
                payload);
        assertThat(providerEvents.store(List.of(arrival))).isOne();
        return providerEvents.claim(Duration.ofMinutes(2)).orElseThrow();
    }

    private void dispatchDm(ProviderEventStore.Claimed claimed) {
        var handler = new SlackConversationDmMessageHandler(service, eventPayloads);
        assertThat(inbox.dispatch(claimed, () -> handler.onMessage(claimed.event())))
                .isFalse();
    }

    private void failMembershipLookup(ProviderEventStore.Claimed claimed) {
        assertThatThrownBy(() -> dispatchDm(claimed))
                .isInstanceOfSatisfying(
                        SlackMembershipUnavailableException.class,
                        failure -> inboxFailures.handleFailure(claimed, failure));
    }

    private void assertQueuedForRetry(ProviderEventStore.Claimed claimed, int failures) {
        assertThat(jdbc.queryForMap("SELECT state, failures FROM provider_event WHERE id = ?", claimed.id()))
                .containsEntry("state", "QUEUED")
                .containsEntry("failures", failures);
        assertNothingStored("100.1");
        assertThat(ephemeralReplies).isEmpty();
    }

    private ProviderEventStore.Claimed claimRetry(long eventId) {
        assertThat(jdbc.update("UPDATE provider_event SET available_at = clock_timestamp() WHERE id = ?", eventId))
                .isOne();
        var retry = providerEvents.claim(Duration.ofMinutes(2)).orElseThrow();
        assertThat(retry.id()).isEqualTo(eventId);
        return retry;
    }

    @ParameterizedTest
    @CsvSource({
        "is_restricted, true",
        "is_ultra_restricted, true",
        "team_id, T2",
        "deleted, true",
        "is_stranger, true",
        "is_external, true",
        "is_bot, true",
        "is_app_user, true",
        "is_invited_user, true",
        "is_profile_only_user, true",
        "is_forgotten, true",
        "is_connector_bot, true",
        "is_workflow_bot, true"
    })
    void shouldAdmitOneEphemeralRefusalWhenTheSlackUserIsNotAFullMember(String field, String value) {
        var member = mapper.readValue(FULL_MEMBER, ObjectNode.class);
        var user = (ObjectNode) member.get("user");
        if ("true".equals(value)) user.put(field, true);
        else user.put(field, value);
        membership.set(slackJson(mapper.writeValueAsString(member)));
        var claimed = claimedDm();

        dispatchDm(claimed);
        dispatchDm(claimed);

        assertNothingStored("100.1");
        assertMembershipRefusal();
        assertLookup("ineligible", "none", 1);
        assertThat(providerEvents.complete(claimed.id(), claimed.leaseEpoch())).isTrue();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "provider | {\"ok\":false,\"error\":\"missing_scope\"}",
                "payload | {\"ok\":true,\"user\":{\"id\":\"U1\",\"team_id\":\"T1\"}}",
                "payload | {\"ok\":true,\"user\":{\"id\":\"U2\",\"team_id\":\"T1\"}}",
                "payload | malformed response"
            })
    void shouldRetryWhenSlackCannotConfirmMembership(String reason, String response) {
        membership.set(slackJson(response));
        var claimed = claimedDm();

        failMembershipLookup(claimed);

        assertQueuedForRetry(claimed, 1);
        assertLookup("unconfirmed", reason, 1);
    }

    @Test
    void shouldRetryWhenSlackReturnsANonBooleanDeletionFlag() {
        membership.set(slackJson(FULL_MEMBER.replace("\"deleted\":false", "\"deleted\":\"false\"")));
        var claimed = claimedDm();

        failMembershipLookup(claimed);

        assertQueuedForRetry(claimed, 1);
        assertLookup("unconfirmed", "payload", 1);
    }

    @ParameterizedTest
    @CsvSource({"503, provider", "429, rate_limited"})
    void shouldRetryWithoutSpendingAnAttemptWhenSlackIsTemporarilyUnavailable(int httpStatus, String reason) {
        membership.set(new MockResponse.Builder()
                .code(httpStatus)
                .addHeader("Retry-After", "60")
                .build());
        var claimed = claimedDm();

        failMembershipLookup(claimed);

        assertQueuedForRetry(claimed, 0);
        assertLookup("unconfirmed", reason, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"internal_error", "fatal_error", "service_unavailable", "request_timeout"})
    void shouldRetryWithoutSpendingAnAttemptWhenTheSlackBodyReportsAnOutage(String error) {
        membership.set(slackJson("""
                {"ok":false,"error":"%s"}
                """.formatted(error)));
        var claimed = claimedDm();

        failMembershipLookup(claimed);

        assertQueuedForRetry(claimed, 0);
        assertLookup("unconfirmed", "provider", 1);
    }

    @Test
    void shouldAnswerTheOriginalQuestionWhenSlackRecovers() {
        lookupResponses.add(new MockResponse.Builder().code(503).build());
        var claimed = claimedDm();

        failMembershipLookup(claimed);
        assertQueuedForRetry(claimed, 0);
        var retry = claimRetry(claimed.id());
        dispatchDm(retry);
        assertThat(providerEvents.complete(retry.id(), retry.leaseEpoch())).isTrue();

        ConversationEntry question = question("100.1");
        assertThat(question.getText()).isEqualTo("How is my review practice?");
        var answer = answerPostedAt("100.2");
        assertThat(answer.getTurnId()).isEqualTo(question.getTurnId());
        assertThat(turnOf(question).getStatus()).isEqualTo(ConversationTurn.Status.DONE);
        assertThat(jdbc.queryForObject(
                        "SELECT payload->>'entryId' FROM agent_action WHERE workspace_id=? AND kind='SLACK_POST' AND idempotency_key=?",
                        String.class,
                        workspace.getId(),
                        ConversationSlackReplies.replyKey(question.getId())))
                .isEqualTo(question.getId().toString());
        assertThat(ephemeralReplies).isEmpty();
    }

    @Test
    void shouldTellTheSenderOnceWhenTheRetryBudgetEndsAndKeepTheirQuestionForReplay() {
        membership.set(slackJson("{\"ok\":false,\"error\":\"missing_scope\"}"));
        var claimed = claimedDm();
        failMembershipLookup(claimed);
        assertQueuedForRetry(claimed, 1);
        jdbc.update(
                "UPDATE provider_event SET failures = ? WHERE id = ?",
                consumerProperties.poison().maxAttempts() - 1,
                claimed.id());

        var last = claimRetry(claimed.id());
        failMembershipLookup(last);

        assertNothingStored("100.1");
        assertThat(ephemeralReplies)
                .singleElement()
                .asString()
                .contains(
                        "channel=" + CHANNEL,
                        "user=" + SLACK_USER,
                        "text=" + SlackConversationService.SLACK_MEMBERSHIP_UNAVAILABLE);
        assertThat(providerEvents.deadLetters(workspace.getId(), 0, 10))
                .singleElement()
                .satisfies(dead -> assertThat(dead.id()).isEqualTo(claimed.id()));

        assertThat(providerEvents.replay(workspace.getId(), claimed.id(), developerId))
                .isPresent();
        jdbc.update(
                "UPDATE provider_event SET failures = ? WHERE id = ?",
                consumerProperties.poison().maxAttempts() - 1,
                claimed.id());
        failMembershipLookup(claimRetry(claimed.id()));
        assertThat(ephemeralReplies).hasSize(1);

        membership.set(slackJson(FULL_MEMBER));
        assertThat(providerEvents.replay(workspace.getId(), claimed.id(), developerId))
                .isPresent();
        var replayed = claimRetry(claimed.id());
        dispatchDm(replayed);
        assertThat(providerEvents.complete(replayed.id(), replayed.leaseEpoch()))
                .isTrue();
        assertThat(turnOf(question("100.1")).getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
        assertThat(ephemeralReplies).hasSize(1);
    }

    @Test
    void shouldAdmitTheMemberAfterSlackAcceptsARateLimitRetry() {
        lookupResponses.add(new MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "1")
                .build());
        var claimed = claimedDm();

        dispatchDm(claimed);

        assertThat(turnOf(question("100.1")).getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
        assertThat(ephemeralReplies).isEmpty();
        assertLookup("confirmed", "none", 1);
        assertThat(providerEvents.complete(claimed.id(), claimed.leaseEpoch())).isTrue();
    }

    @Test
    void shouldKeepOneRefusalAttemptWhenSlackDoesNotConfirmTheEphemeralWrite() {
        membership.set(slackJson(FULL_MEMBER.replace("\"deleted\":false", "\"deleted\":true")));
        ephemeralResponse.set(new MockResponse.Builder().code(503).build());
        var claimed = claimedDm();

        dispatchDm(claimed);
        dispatchDm(claimed);

        assertNothingStored("100.1");
        assertMembershipRefusal();
        assertThat(providerEvents.complete(claimed.id(), claimed.leaseEpoch())).isTrue();
    }

    @Test
    void shouldCheckMembershipAgainWhenSlackRedeliversAnUncommittedDmAfterTheMemberLeaves() {
        when(admission.refusal(workspace.getId(), developerId))
                .thenThrow(new IllegalStateException("admission interrupted"))
                .thenReturn(Optional.empty());
        var claimed = claimedDm();
        assertThatThrownBy(() -> dispatchDm(claimed)).hasMessage("admission interrupted");
        assertNothingStored("100.1");
        membership.set(slackJson(FULL_MEMBER.replace("\"deleted\":false", "\"deleted\":true")));

        dispatchDm(claimed);
        dispatchDm(claimed);

        assertNothingStored("100.1");
        assertMembershipRefusal();
        assertLookup("confirmed", "none", 1);
        assertLookup("ineligible", "none", 1);
        assertThat(providerEvents.complete(claimed.id(), claimed.leaseEpoch())).isTrue();
    }

    private void assertMembershipRefusal() {
        assertThat(ephemeralReplies)
                .singleElement()
                .asString()
                .contains(
                        "channel=" + CHANNEL,
                        "user=" + SLACK_USER,
                        "text=" + SlackConversationService.SLACK_MEMBERSHIP_REQUIRED);
    }

    private void assertLookup(String outcome, String reason, int count) {
        assertThat(slackMeters
                        .get(SlackMetrics.SLACK_DM_MEMBERSHIP_LOOKUPS)
                        .tags("outcome", outcome, "reason", reason)
                        .counter()
                        .count())
                .isEqualTo(count);
    }

    @Test
    void shouldKeepOneAdmissionOutcomeWhenARefusedDmIsRedeliveredAfterReadinessChanges() {
        when(readiness.isReady(workspace.getId())).thenReturn(false);
        var claimed = claimedDm();
        dispatchDm(claimed);
        assertNotice("100.1", null, ConversationRefusal.UNAVAILABLE.userMessage(), false);

        when(readiness.isReady(workspace.getId())).thenReturn(true);
        dispatchDm(claimed);

        assertNothingStored("100.1");
        assertNotice("100.1", null, ConversationRefusal.UNAVAILABLE.userMessage(), false);
        assertThat(providerEvents.complete(claimed.id(), claimed.leaseEpoch())).isTrue();
    }

    @Test
    void shouldNotRecreateAnErasedNoticeWhenTheSameInboxEventIsRedelivered() {
        when(readiness.isReady(workspace.getId())).thenReturn(false);
        var claimed = claimedDm();
        dispatchDm(claimed);
        var recipient = new PersonIdentity(registry.resolveProviderId("SLACK", "https://slack.com"), SLACK_USER, TEAM);
        transactionTemplate.executeWithoutResult(status ->
                assertThat(actions.eraseRecipient(workspace.getId(), recipient)).isEqualTo(1));

        dispatchDm(claimed);

        String key = "slack_notice:" + workspace.getId() + ":T1:D9:100.1";
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_action WHERE workspace_id=? AND idempotency_key=?",
                        Integer.class,
                        workspace.getId(),
                        key))
                .isZero();
        assertThat(providerEvents.complete(claimed.id(), claimed.leaseEpoch())).isTrue();
    }

    @Test
    void shouldStoreOneQuestionAndQueueOneCompleteReplyWhenTheInboxReceivesTheSameDmTwice() {
        String delivery = "slack-" + UUID.randomUUID();
        byte[] payload = """
                {"team_id":"T1","event":{"type":"message","channel_type":"im","channel":"D9",
                 "user":"U1","text":"How is my review practice?","ts":"100.1"}}
                """.getBytes(StandardCharsets.UTF_8);
        var arrival = new ProviderEventStore.Arrival(
                workspace.getId(), "slack", "slack.T1.message_im", delivery, Map.of(), payload);
        assertThat(providerEvents.store(List.of(arrival))).isOne();
        assertThat(providerEvents.store(List.of(arrival))).isZero();

        var claimed = providerEvents.claim(Duration.ofMinutes(2)).orElseThrow();
        assertThat(claimed.workspaceId()).isEqualTo(workspace.getId());
        assertThat(claimed.event().payload()).isEqualTo(payload);
        assertThat(jdbc.queryForObject("SELECT state FROM provider_event WHERE id=?", String.class, claimed.id()))
                .isEqualTo("LEASED");
        for (int attempt = 0; attempt < 2; attempt++) {
            dispatchDm(claimed);
        }
        assertThat(providerEvents.complete(claimed.id(), claimed.leaseEpoch())).isTrue();

        ConversationEntry question = question("100.1");
        assertThat(question.getText()).isEqualTo("How is my review practice?");
        assertThat(entryRepository.findMaxSeqByWorkspaceIdAndConversationId(workspace.getId(), main().getId()))
                .isEqualTo(question.getSeq());
        assertThat(jdbc.queryForObject("SELECT state FROM provider_event WHERE id=?", String.class, claimed.id()))
                .isEqualTo("DONE");
        String replyKey = ConversationSlackReplies.replyKey(question.getId());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_action WHERE workspace_id=? AND idempotency_key=?",
                        Integer.class,
                        workspace.getId(),
                        replyKey))
                .as("Slack receives no partial answer while the turn is queued")
                .isZero();

        answerPostedAt("100.2");

        assertThat(jdbc.queryForList(
                        "SELECT state FROM agent_action WHERE workspace_id=? AND kind='SLACK_POST' AND idempotency_key=?",
                        String.class,
                        workspace.getId(),
                        replyKey))
                .containsExactly("ADMITTED");
        assertThat(jdbc.queryForObject(
                        "SELECT payload->>'entryId' FROM agent_action WHERE workspace_id=? AND idempotency_key=?",
                        String.class,
                        workspace.getId(),
                        replyKey))
                .isEqualTo(question.getId().toString());
        assertThat(providerEvents.store(List.of(arrival)))
                .as("a late redelivery also stays deduplicated")
                .isZero();
    }

    @Test
    void shouldStoreTheReplyInTheThreadOnItsParentWhenTheDeveloperRepliesUnderTheirOwnMessage() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        ConversationEntry parent = question("100.1");

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Tell me more", "100.5", "100.1");

        ConversationEntry reply = question("100.5");
        Conversation thread = conversations.require(workspace.getId(), reply.getConversationId());
        assertThat(thread.getParentEntryId()).isEqualTo(parent.getId());
        assertThat(thread.getParentConversationId()).isEqualTo(main().getId());
        assertThat(thread.getSlackTeamId()).isNull();
    }

    @Test
    void shouldStoreTheReplyInTheThreadOnHephaestusAnswerWhenTheDeveloperRepliesUnderIt() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        ConversationEntry answer = answerPostedAt("100.2");

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Why quick?", "100.6", "100.2");

        Conversation thread =
                conversations.require(workspace.getId(), question("100.6").getConversationId());
        assertThat(thread.getParentEntryId()).isEqualTo(answer.getId());
    }

    @Test
    void shouldRefuseTheReplyAndStoreNothingWhenItsParentBelongsToAnotherWorkspace() {
        service.handleDm(OTHER_TEAM, CHANNEL, SLACK_USER, "Asked in the other workspace", "500.1", "500.1");
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Asked here", "100.1", "100.1");

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Reply under the other one", "500.5", "500.1");

        assertThat(maybeQuestion("500.5")).isEmpty();
        assertNotice("500.5", "500.1", ConversationRejectedException.Reason.NOT_A_THREAD_PARENT.userMessage(), false);
    }

    @Test
    void shouldRefuseTheReplyWhenTheDmIsNotTheOneTheConversationKnows() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");

        service.handleDm(TEAM, "D8", SLACK_USER, "From another DM", "100.7", "100.1");

        assertThat(entryRepository.findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D8:100.7"))
                .isEmpty();
    }

    @Test
    void shouldKeepTheNewTextMarkedEditedAndAskNothingNewWhenTheDeveloperEditsADm() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practise?", "100.1", "100.1");

        service.handleEdit(TEAM, CHANNEL, SLACK_USER, "100.1", "How is my review practice?");

        ConversationEntry edited = question("100.1");
        assertThat(edited.getText()).isEqualTo("How is my review practice?");
        assertThat(edited.getEditedAt()).isNotNull();
        assertThat(turnRepository.countQueuedByWorkspaceIdAndConversationId(workspace.getId(), main().getId()))
                .isEqualTo(1);
    }

    @Test
    void shouldEraseTheQuestionAndStopItsTurnWhenTheDeveloperDeletesADm() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");

        service.handleDelete(TEAM, CHANNEL, SLACK_USER, "100.1");

        ConversationEntry deleted = question("100.1");
        assertThat(deleted.isRedacted()).isTrue();
        assertThat(deleted.getText()).isEmpty();
        assertThat(turnOf(deleted).getStatus()).isEqualTo(ConversationTurn.Status.STOPPED);
    }

    @Test
    void shouldStoreNothingWhenSlackIsDisconnectedWhileTheDmWaits() throws Exception {
        HeldTransaction disconnecting = HeldTransaction.holdIn(transactionTemplate, jdbc, this::disconnect);
        CompletableFuture<Void> dm = CompletableFuture.runAsync(
                () -> service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1"));

        disconnecting.commitAfter(dm);
        dm.get(20, TimeUnit.SECONDS);

        assertThat(maybeQuestion("100.1")).isEmpty();
        assertThat(conversationRepository
                        .findMain(workspace.getId(), developerId)
                        .map(Conversation::getSlackChannelId))
                .isEmpty();
    }

    @Test
    void shouldEraseTheQuestionWhenSlackIsDisconnectedWhileTheDmIsStored() throws Exception {
        HeldTransaction storing = HeldTransaction.holdIn(
                transactionTemplate,
                jdbc,
                () -> service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1"));
        CompletableFuture<Void> disconnecting =
                CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> disconnect()));

        storing.commitAfter(disconnecting);
        disconnecting.get(20, TimeUnit.SECONDS);

        assertThat(question("100.1").isRedacted()).isTrue();
        assertThat(main().getSlackChannelId()).isNull();
    }

    /** What a Slack disconnect does in its transaction: the lifecycle lock, the state, then the erasure. */
    private void disconnect() {
        Connection connection = connectionRepository
                .findActive(workspace.getId(), IntegrationKind.SLACK)
                .orElseThrow();
        connectionRepository.acquireLifecycleLock(connection.getId(), workspace.getId());
        ConnectionFixtures.reachState(connection, IntegrationState.UNINSTALLED);
        connectionRepository.saveAndFlush(connection);
        eraser.eraseWorkspace(workspace.getId());
    }

    @Test
    void shouldVoteOnHephaestusAnswerWhenTheDeveloperPressesAFeedbackButton() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        ConversationEntry answer = answerPostedAt("100.2");

        service.handleFeedback(TEAM, CHANNEL, SLACK_USER, "NOT_HELPFUL:" + answer.getId());

        assertThat(entryRepository.findById(answer.getId()).orElseThrow().getVote())
                .isEqualTo(ConversationEntry.Vote.NOT_HELPFUL);
    }

    @Test
    void shouldIgnoreTheVoteWhenAnotherSlackMemberPressesTheButton() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        ConversationEntry answer = answerPostedAt("100.2");

        service.handleFeedback(TEAM, CHANNEL, "U2", "HELPFUL:" + answer.getId());

        assertThat(entryRepository.findById(answer.getId()).orElseThrow().getVote())
                .isNull();
    }

    @ParameterizedTest
    @EnumSource(ConversationRefusal.class)
    void shouldReplyWithTheReasonAndStoreNothingWhenAdmissionRefusesTheMember(ConversationRefusal refusal) {
        when(admission.refusal(workspace.getId(), developerId)).thenReturn(Optional.of(refusal));

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Can you review my work?", "100.1", "100.1");

        assertNotice("100.1", null, refusal.userMessage(), false);
        assertNothingStored("100.1");
    }

    @Test
    void shouldReplyInTheSlackThreadAndStoreNothingWhenAdmissionRefusesAReplyInAThread() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        when(admission.refusal(workspace.getId(), developerId)).thenReturn(Optional.of(ConversationRefusal.NO_AI));

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "And then?", "100.5", "100.1");

        assertNotice("100.5", "100.1", ConversationRefusal.NO_AI.userMessage(), false);
        assertThat(maybeQuestion("100.5")).isEmpty();
        assertThat(conversations.findThread(workspace.getId(), question("100.1").getId()))
                .isEmpty();
    }

    @Test
    void shouldOfferTheAccountLinkAndStoreNothingWhenTheSlackMemberIsNotLinked() {
        when(identities.resolveActiveAccountId(workspace.getId(), TEAM, SLACK_USER))
                .thenReturn(Optional.empty());

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Can you review my PR practice?", "100.1", "100.1");

        assertNotice("100.1", null, SlackConversationService.NOT_LINKED, true);
        assertNothingStored("100.1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldExplainUnavailabilityInTheSameDmOrThreadWhenHephaestusIsNotReady(boolean inThread) {
        when(readiness.isReady(workspace.getId())).thenReturn(false);
        String threadTs = inThread ? "100.0" : "100.1";

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Can you review my work?", "100.1", threadTs);

        assertNotice("100.1", inThread ? threadTs : null, ConversationRefusal.UNAVAILABLE.userMessage(), false);
        verifyNoInteractions(identities, admission);
        assertNothingStored("100.1");
        assertThat(turnRepository.findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D9:100.1"))
                .isEmpty();
    }

    @Test
    void shouldPointToHelpAndStoreNothingWhenTheDmSpeaksOfSelfHarmAndHephaestusIsNotReady() {
        when(readiness.isReady(workspace.getId())).thenReturn(false);
        membership.set(new MockResponse.Builder().code(503).build());

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "I want to kill myself", "100.1", "100.1");

        assertNotice("100.1", null, KeywordSlackConversationInputGuard.SELF_HARM_RESPONSE, false);
        assertLookup("unconfirmed", "provider", 0);
        verifyNoInteractions(readiness, identities, admission);
        assertNothingStored("100.1");
    }

    @Test
    void shouldStayQuietAndStoreNothingWhenTheDmIsAbusiveAndHephaestusIsNotReady() {
        when(readiness.isReady(workspace.getId())).thenReturn(false);

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "stupid bot", "100.1", "100.1");

        verifyNoInteractions(readiness, identities, admission);
        assertNothingStored("100.1");
    }

    @Test
    void shouldPointToHelpAndStoreNothingWhenTheDmSpeaksOfSelfHarm() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "I want to kill myself", "100.1", "100.1");

        assertNotice("100.1", null, KeywordSlackConversationInputGuard.SELF_HARM_RESPONSE, false);
        assertNothingStored("100.1");
    }

    @Test
    void shouldStayQuietAndStoreNothingWhenTheDmIsAbusive() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "stupid bot", "100.1", "100.1");

        assertNothingStored("100.1");
    }
}
