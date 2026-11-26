package de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.providers.conversation.WeeklyDigestContentSource;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationAdmission;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationReceiptAnswers;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationRefusal;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationContextPathBuilder;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDraft;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationRedaction;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackReplies;
import de.tum.cit.aet.hephaestus.conversation.ConversationTimeline;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnProgress;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.conversation.InboxItems;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationAccess;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationTurnAccess;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.ConversationContextRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmSourceFingerprints;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationReadinessQuery;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ProfileChangeType;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestChangeKey;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestDTO;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFacts;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestPayload;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestWindow;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.SharedEventRecorder;
import de.tum.cit.aet.hephaestus.testconfig.WeeklyDigestTestFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A developer's weekly digest, from the facts of their local week to the entry in their Hephaestus conversation, its live
 * rendering and the document Hephaestus reads in its thread. The evidence authorization boundary permits every stored
 * result of the workspace, as in the other review-evidence tests.
 */
class WeeklyDigestIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private SharedEventRecorder observedEvents;

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @MockitoBean
    EvidenceAuthorization evidenceAuthorization;

    @MockitoBean
    DigestAdmission liveDigestAdmission;

    @MockitoSpyBean
    Clock jvmClock;

    @MockitoBean(name = "digestTransactionTransport")
    WeeklyDigestTransport transactionTransport;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnRepository turnRows;

    @Autowired
    private InboxItems inbox;

    @Autowired
    private ConversationTurnAccess turnAccess;

    @Autowired
    private ConversationAccess conversationAccess;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private LlmUsageEventRepository usage;

    @Autowired
    private ConversationClaims claims;

    @Autowired
    private ConversationReceiptAnswers answers;

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackBasisRepository basisRepository;

    @Autowired
    private FeedbackCopyRepository copyRepository;

    @Autowired
    private WeeklyDigestFacts facts;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ConversationEntryRepository entryRepository;

    @Autowired
    private ConversationTimeline timeline;

    @Autowired
    private ConversationContextPathBuilder paths;

    @Autowired
    private ConversationRedaction redaction;

    @Autowired
    private WeeklyDigestContentSource contentSource;

    @Autowired
    private PersonDataWriteFence writeFence;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** What the composer published, recorded from any thread. */
    private final List<Object> published = new CopyOnWriteArrayList<>();

    private final ApplicationEventPublisher events = published::add;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final WeeklyDigestSubscriptions subscriptions = mock(WeeklyDigestSubscriptions.class);
    private final AccountMembershipQuery memberships = mock(AccountMembershipQuery.class);
    private final AccountDirectory accounts = mock(AccountDirectory.class);
    private final ConversationReadinessQuery readiness = mock(ConversationReadinessQuery.class);
    private final ConversationAdmission admission = mock(ConversationAdmission.class);

    private Scenario scenario;
    private long workspaceId;
    private long accountId;
    /** The week before the simulated scheduler pass. Its cutoff is ahead of the database clock. */
    private WeeklyDigestWindow week;

    /** One piece of feedback with the result and problem it rests on. */
    private record Piece(Feedback feedback, Result result, UUID problemId) {
        UUID id() {
            return feedback.getId();
        }
    }

    @MockitoBean
    ScmSourceFingerprints sourceFingerprints;

    @BeforeEach
    void setUp() {
        when(transactionTransport.pointer()).thenReturn(WeeklyDigestPointer.SLACK);
        when(liveDigestAdmission.refuses(anyLong(), any())).thenReturn(false);
        databaseTestUtils.cleanDatabase();
        when(evidenceAuthorization.permitsAll(anyLong(), anyCollection(), any()))
                .thenAnswer(invocation -> sameWorkspace(invocation.getArgument(0), invocation.getArgument(1)));
        when(evidenceAuthorization.permitsForNewDelivery(anyLong(), anyCollection(), any()))
                .thenAnswer(invocation -> sameWorkspace(
                        invocation.getArgument(0),
                        invocation.<Collection<Result>>getArgument(1).stream()
                                .map(Result::facts)
                                .toList()));
        when(sourceFingerprints.supports(any())).thenReturn(true);
        when(sourceFingerprints.fingerprints(any(), any(), any(), any()))
                .thenReturn(Map.of(new SourceKind("scm.pull-request.diff"), "a".repeat(64)));
        scenario = data.scenario();
        workspaceId = scenario.workspace().getId();
        accountId = Objects.requireNonNull(scenario.account().getId());
        // The person-data write fence admits an account with an identity only.
        IdentityProvider github = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        Identity identity = new Identity();
        identity.setAccount(scenario.account());
        identity.setProviderId(Objects.requireNonNull(github.getId()));
        identity.setProviderUserKey("weekly-digest-" + accountId);
        identityRepository.saveAndFlush(identity);
        week = WeeklyDigestWindow.previousWeek(Instant.now().plus(Duration.ofDays(7)), BERLIN);

        when(subscriptions.subscribedAccountIds()).thenReturn(List.of(accountId));
        when(subscriptions.enabledSince(accountId)).thenReturn(Optional.of(week.start()));
        when(accounts.timeZone(accountId)).thenReturn(Optional.of(BERLIN));
        when(memberships.membershipsForAccount(accountId))
                .thenReturn(List.of(new AccountMembershipQuery.MembershipView(
                        workspaceId,
                        scenario.workspace().getWorkspaceSlug(),
                        "Delivery team",
                        AccountMembershipQuery.Role.MEMBER)));
        when(memberships.isActiveVisibleMember(workspaceId, accountId)).thenReturn(true);
        when(readiness.isReady(workspaceId)).thenReturn(true);
        when(admission.refusal(workspaceId, accountId)).thenReturn(Optional.empty());
    }

    @Test
    void shouldListOnlyFeedbackThatReachedTheDeveloperOnTheWorkOrInConversationWhenTheDigestIsWritten() {
        Piece onTheWork = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        Piece unconfirmed = save(FeedbackDestination.ON_WORK, FeedbackState.UNCONFIRMED, inWeek(2));
        Piece inConversation = save(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, inWeek(3));
        // A copy appeared on the work before another one failed.
        Piece partlyDelivered = save(FeedbackDestination.ON_WORK, FeedbackState.FAILED, inWeek(4), inWeek(4));
        // Feedback that did not reach the developer in the week, which the digest leaves out.
        save(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, inWeek(2));
        save(FeedbackDestination.ON_WORK, FeedbackState.AWAITING_APPROVAL, inWeek(3));
        save(FeedbackDestination.ON_WORK, FeedbackState.FAILED, inWeek(3));
        save(FeedbackDestination.ON_WORK, FeedbackState.UNCONFIRMED, inWeek(3), null);
        save(FeedbackDestination.CONVERSATION, FeedbackState.READY, inWeek(3));
        save(FeedbackDestination.CONVERSATION, FeedbackState.UNCONFIRMED, inWeek(3));
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, week.start().minusSeconds(60));

        assertThat(schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue()).isEqualTo(1);

        ConversationEntry digest = digestEntry().orElseThrow();
        assertThat(digest.getKind()).isEqualTo(ConversationEntry.Kind.DIGEST);
        assertThat(digest.getSurface()).isEqualTo(ConversationEntry.Surface.SYSTEM);
        assertThat(digest.getText()).contains(week.daysWithYear(), "inputs/context/weekly_digest.json");
        JsonNode payload = Objects.requireNonNull(digest.getPayload());
        assertThat(payload.path("window").path("weekStart").asString())
                .isEqualTo(week.weekStart().toString());
        assertThat(payload.path("window").path("zone").asString()).isEqualTo("Europe/Berlin");
        List<String> listed = new ArrayList<>();
        payload.path("feedbackIds").forEach(id -> listed.add(id.asString()));
        assertThat(listed)
                .containsExactly(
                        onTheWork.id().toString(),
                        unconfirmed.id().toString(),
                        inConversation.id().toString(),
                        partlyDelivered.id().toString());
        assertThat(conversations.findMain(workspaceId, accountId))
                .map(Conversation::getId)
                .contains(digest.getConversationId());

        var turn = turnRows.findByIdAndWorkspaceId(Objects.requireNonNull(digest.getTurnId()), workspaceId)
                .orElseThrow();
        assertThat(turn.getSurface()).isEqualTo(ConversationTurn.Surface.SYSTEM);
        assertThat(turn.getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
        var run = runs.find(new AgentRunRef(turn.getRunId(), workspaceId)).orElseThrow();
        assertThat(run.getKind()).isEqualTo(AgentRunKind.DIGEST);
        assertThat(run.getBand()).isEqualTo(AgentRunBand.BACKGROUND);
        assertThat(run.getConcurrencyKey()).isEqualTo("conversation:" + digest.getConversationId());
        assertThat(written()).isEmpty();
        assertThat(timeline.liveEntry(workspaceId, accountId, digest.getId())).isEmpty();
        assertThat(timeline.entries(workspaceId, accountId, null, 50).content()).isEmpty();
    }

    @Test
    void shouldPublishNoSecondNoteWhenAnOverlappingRunWroteTheDigestFirst() throws Exception {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        Conversation main = conversations.findOrCreateMain(workspaceId, accountId);
        HeldTransaction first = HeldTransaction.holdIn(
                transactionTemplate,
                jdbcTemplate,
                () -> entries.appendOnce(
                        workspaceId,
                        main.getId(),
                        WeeklyDigestTestFixtures.historical(
                                "Weekly digest for " + week.daysWithYear() + ".",
                                new WeeklyDigestPayload(week, List.of(), List.of()).toJson(objectMapper),
                                requestId())));
        CompletableFuture<Integer> overlapping = CompletableFuture.supplyAsync(
                () -> schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue());

        first.commitAfter(overlapping);

        assertThat(overlapping.get(20, TimeUnit.SECONDS)).isZero();
        assertThat(written()).isEmpty();
        // A second run in the same window finds the digest and writes nothing either.
        assertThat(schedule(Clock.fixed(due().plus(Duration.ofHours(3)), ZoneOffset.UTC))
                        .enqueueDue())
                .isZero();
    }

    @Test
    void shouldWriteNothingWhenTheWeekWasQuiet() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, week.start().minusSeconds(3600));

        assertThat(schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue()).isZero();

        assertThat(digestEntry()).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void shouldFindRecordedObservationsWithoutFeedbackInTheDigestWindow() {
        data.result(scenario, ResultOutcome.NOT_MET, week.start());
        assertThat(facts.anythingIn(workspaceId, accountId, week)).isFalse();

        var previous = WeeklyDigestWindow.previousWeek(week.start(), BERLIN);
        assertThat(facts.anythingIn(workspaceId, accountId, previous)).isTrue();
    }

    @Test
    void shouldWriteNothingWhenHephaestusWouldNotAnswerTheDeveloper() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        when(admission.refusal(workspaceId, accountId)).thenReturn(Optional.of(ConversationRefusal.NO_AI));

        assertThat(schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue()).isZero();

        assertThat(digestEntry()).isEmpty();
    }

    @Test
    void shouldWriteNothingWhenTheDeveloperIsNoLongerAnActiveMember() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        when(memberships.isActiveVisibleMember(workspaceId, accountId)).thenReturn(false);

        assertThat(schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue()).isZero();

        assertThat(digestEntry()).isEmpty();
    }

    @Test
    void shouldPointThroughSlackBeforeEmailWhenBothReachTheDeveloper() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        WeeklyDigestTransport email = transport(WeeklyDigestPointer.EMAIL, true);
        WeeklyDigestTransport slack = transport(WeeklyDigestPointer.SLACK, true);

        schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue();
        assertThat(written()).isEmpty();
        finishDigest(List.of(email, slack));

        assertThat(written())
                .singleElement()
                .extracting(WeeklyDigestWritten::pointer)
                .isEqualTo(WeeklyDigestPointer.SLACK);
    }

    @Test
    void shouldWriteNoDigestWhenOldUnreadPracticePageFeedbackIsAllThereIs() {
        save(
                FeedbackDestination.PRACTICE_PAGE,
                FeedbackState.READY,
                week.start().minus(Duration.ofDays(3)));

        assertThat(facts.compose(workspaceId, accountId, week)).isEmpty();
    }

    @Test
    void shouldCountPracticePageFeedbackPreparedInTheWeekAsWaitingAndListNoChangeOfItWhenItIsUnread() {
        // Its result is just before the week, so the unread feedback is the only thing the week holds.
        save(
                FeedbackDestination.PRACTICE_PAGE,
                FeedbackState.READY,
                week.start().plusSeconds(45));

        WeeklyDigestPayload payload =
                facts.compose(workspaceId, accountId, week).orElseThrow();
        // Unread from an earlier week: it waits too, but the count is of this week's.
        save(
                FeedbackDestination.PRACTICE_PAGE,
                FeedbackState.READY,
                week.start().minus(Duration.ofDays(3)));

        assertThat(payload.feedbackIds()).isEmpty();
        assertThat(payload.changeKeys()).isEmpty();
        WeeklyDigestDTO digest = render(payload);
        assertThat(digest.waiting()).isEqualTo(1);
        assertThat(digest.weekStart()).isEqualTo(week.weekStart());
    }

    @Test
    void shouldListAChangeOnlyWhenTheDigestHeldIt() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        WeeklyDigestPayload payload =
                facts.compose(workspaceId, accountId, week).orElseThrow();
        assertThat(payload.changeKeys()).isNotEmpty();
        assertThat(payload.changeKeys())
                .extracting(WeeklyDigestChangeKey::type)
                .as("the practice's first result is a change of the week")
                .contains(ProfileChangeType.FIRST_RECORDED)
                .doesNotContain(
                        ProfileChangeType.FEEDBACK_NEW,
                        ProfileChangeType.FEEDBACK_RESOLVED,
                        ProfileChangeType.FEEDBACK_RESET);

        WeeklyDigestDTO held = render(payload);
        WeeklyDigestDTO heldNothing = render(new WeeklyDigestPayload(week, payload.feedbackIds(), List.of()));

        assertThat(held.changes()).isNotEmpty();
        assertThat(heldNothing.changes()).isEmpty();
    }

    @Test
    void shouldLinkConversationFeedbackToTheHephaestusMessageUntilTheMessageIsErased() {
        Piece inConversation = save(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, inWeek(1));
        Conversation main = conversations.findOrCreateMain(workspaceId, accountId);
        UUID answer = entries.append(
                        workspaceId,
                        main.getId(),
                        new ConversationEntryDraft(
                                ConversationEntry.Kind.ANSWER,
                                ConversationEntry.Surface.APP,
                                "That is what I saw.",
                                null,
                                null,
                                null,
                                null))
                .getId();
        UUID copyId = UUID.randomUUID();
        copyRepository.saveAndFlush(FeedbackCopy.builder()
                .id(copyId)
                .workspaceId(workspaceId)
                .feedbackId(inConversation.id())
                .kind(FeedbackCopyKind.CHAT_TURN)
                .position(0)
                .state(FeedbackCopyState.POSTED)
                .writeKey(copyId.toString())
                .writeStartedAt(inWeek(1))
                .postedAt(inWeek(1))
                .conversationEntryId(answer)
                .createdAt(inWeek(1))
                .build());

        schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue();
        finishDigest(List.of());

        assertThat(digestOnTheTimeline().feedback()).singleElement().satisfies(row -> {
            assertThat(row.feedbackId()).isEqualTo(inConversation.id());
            assertThat(row.destination()).isEqualTo(FeedbackDestination.CONVERSATION);
            assertThat(row.conversationEntryId()).isEqualTo(answer);
            assertThat(row.item()).isNull();
        });

        redaction.redact(List.of(answer));

        assertThat(digestOnTheTimeline().feedback())
                .singleElement()
                .satisfies(row -> assertThat(row.conversationEntryId()).isNull());
    }

    @Test
    void shouldShowWithdrawnFeedbackByItsPracticeAndHideFeedbackWhenItsEvidenceCannotBeShown() {
        Piece hidden = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        Piece withdrawn = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(2));
        schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue();
        finishDigest(List.of());

        assertThat(digestOnTheTimeline().feedback())
                .extracting(WeeklyDigestFeedbackDTO::feedbackId)
                .containsExactly(hidden.id(), withdrawn.id());
        assertThat(digestOnTheTimeline().feedback())
                .allSatisfy(row -> assertThat(row.item()).isNotNull());

        jdbcTemplate.update(
                "INSERT INTO feedback_decision(id,workspace_id,feedback_id,action,note,decided_at)"
                        + " VALUES (?,?,?,'WITHDRAW','Wrong about the work',?)",
                UUID.randomUUID(),
                workspaceId,
                withdrawn.id(),
                Timestamp.from(Instant.now()));
        data.mark(hidden.result(), ResultMarkKind.INCORRECT, accountId);

        WeeklyDigestDTO now = digestOnTheTimeline();
        assertThat(now.feedback()).singleElement().satisfies(row -> {
            assertThat(row.feedbackId()).isEqualTo(withdrawn.id());
            assertThat(row.withdrawn()).isTrue();
            assertThat(row.item()).isNull();
            assertThat(row.practiceSlug()).isEqualTo(scenario.practice().getSlug());
        });
    }

    @Test
    void shouldListReplacedFeedbackWhenItReachedTheDeveloperInTheWeek() {
        Piece replacedInTheWeek = save(FeedbackDestination.ON_WORK, FeedbackState.REPLACED, inWeek(1));
        Piece replacedLater = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(2));
        schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue();

        jdbcTemplate.update("UPDATE feedback SET state='REPLACED' WHERE id=?", replacedLater.id());
        finishDigest(List.of());

        assertThat(digestOnTheTimeline().feedback())
                .extracting(WeeklyDigestFeedbackDTO::feedbackId, WeeklyDigestFeedbackDTO::replaced)
                .containsExactly(tuple(replacedInTheWeek.id(), true), tuple(replacedLater.id(), true));
        assertThat(digestOnTheTimeline().feedback().getFirst().item())
                .isNotNull()
                .extracting(item -> item.id())
                .isEqualTo(Long.toString(replacedInTheWeek.result().getItemId()));
    }

    @Test
    void shouldRenderTwoDigestsInTheirOrderWhenAPageHoldsBoth() {
        WeeklyDigestWindow earlier = WeeklyDigestWindow.previousWeek(week.start(), BERLIN);
        Piece lastWeek = save(
                FeedbackDestination.ON_WORK,
                FeedbackState.DELIVERED,
                earlier.start().plus(Duration.ofDays(1)).plus(Duration.ofHours(10)));
        Piece thisWeek = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        WeeklyDigestPayload first =
                facts.compose(workspaceId, accountId, earlier).orElseThrow();
        WeeklyDigestPayload second = facts.compose(workspaceId, accountId, week).orElseThrow();

        List<WeeklyDigestFacts.Rendered> both = facts.render(workspaceId, accountId, List.of(first, second));

        assertThat(both)
                .extracting(rendered -> rendered.digest().weekStart())
                .containsExactly(earlier.weekStart(), week.weekStart());
        assertThat(both.getFirst().digest().feedback())
                .extracting(WeeklyDigestFeedbackDTO::feedbackId)
                .containsExactly(lastWeek.id());
        assertThat(both.getLast().digest().feedback())
                .extracting(WeeklyDigestFeedbackDTO::feedbackId)
                .containsExactly(thisWeek.id());
    }

    @Test
    void shouldGiveHephaestusTheDigestWithItsResultsAndProblemsOnlyWhenItRunsInAThreadOnIt() throws Exception {
        Piece onTheWork = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue();
        ConversationEntry digest = digestEntry().orElseThrow();
        Conversation thread = conversations.openThread(workspaceId, digest.getId());

        JsonNode inThread = document(thread.getId());
        JsonNode inMain = document(digest.getConversationId());

        JsonNode rendered = inThread.path("weeklyDigest");
        assertThat(rendered.path("feedback"))
                .singleElement()
                .satisfies(row -> assertThat(row.path("feedbackId").asString())
                        .isEqualTo(onTheWork.id().toString()));
        assertThat(rendered.path("resultIds").path(onTheWork.id().toString()))
                .singleElement()
                .satisfies(id -> assertThat(id.asString())
                        .isEqualTo(onTheWork.result().getId().toString()));
        assertThat(rendered.path("problemIds").path(onTheWork.id().toString()))
                .singleElement()
                .satisfies(id -> assertThat(id.asString())
                        .isEqualTo(onTheWork.problemId().toString()));
        assertThat(rendered.path("timeZone").asString()).isEqualTo("Europe/Berlin");
        assertThat(inMain.isEmpty()).isTrue();
    }

    @Test
    void shouldGiveAReplyOnTheCompletedRecapItsWeeklyFacts() throws Exception {
        Piece feedback = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue();
        var stimulus = digestEntry().orElseThrow();
        finishDigest(List.of());
        var answer = entryRepository
                .findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
                        workspaceId, Objects.requireNonNull(stimulus.getTurnId()), ConversationEntry.Kind.ANSWER)
                .getFirst();
        var thread = conversations.openThread(workspaceId, answer.getId());
        assertThat(document(thread.getId()).path("weeklyDigest").path("feedback"))
                .singleElement()
                .satisfies(row -> assertThat(row.path("feedbackId").asString())
                        .isEqualTo(feedback.id().toString()));
    }

    @Test
    void shouldStartTheDigestAtOnceOnAnIdleThreadAndKeepOneItemForTheWeek() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        var schedule = schedule(Clock.fixed(due(), ZoneOffset.UTC));
        assertThat(schedule.enqueueDue()).isEqualTo(1);
        var stimulus = digestEntry().orElseThrow();
        assertThat(item(stimulus.getConversationId()))
                .containsEntry("state", "CLAIMED")
                .containsEntry("reason", "STARTED")
                .containsEntry("turn_id", stimulus.getTurnId())
                .containsEntry("starts", 1)
                .containsEntry("attempts", 0);

        assertThat(schedule.enqueueDue()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM inbox_item WHERE conversation_id=?",
                        Integer.class,
                        stimulus.getConversationId()))
                .isOne();
    }

    @Test
    void shouldStopAQueuedDigestForTheQuestionAndStartItAgainOnceTheThreadIsIdle() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        var schedule = schedule(Clock.fixed(due(), ZoneOffset.UTC));
        assertThat(schedule.enqueueDue()).isEqualTo(1);
        var first = digestEntry().orElseThrow();
        UUID conversationId = first.getConversationId();
        UUID digestTurn = Objects.requireNonNull(first.getTurnId());
        UUID digestRun = turnRows.findById(digestTurn).orElseThrow().getRunId();

        var asked = turns.submit(workspaceId, conversationId, question("Why was my pull request flagged?"));

        assertThat(asked.created()).isTrue();
        assertThat(turnRows.findById(digestTurn).orElseThrow().getStatus()).isEqualTo(ConversationTurn.Status.STOPPED);
        assertThat(runState(digestRun)).isEqualTo("ENDED");

        // While the question waits, the pass settles the stopped digest and leaves the item waiting, uncharged.
        starts(Clock.fixed(due(), ZoneOffset.UTC)).startDue();
        assertThat(item(conversationId))
                .containsEntry("state", "PENDING")
                .containsEntry("reason", "DEFERRED_ACTIVE")
                .containsEntry("attempts", 0);

        turns.stop(workspaceId, asked.turn().getId());
        starts(Clock.fixed(due(), ZoneOffset.UTC)).startDue();
        var again = entryRepository
                .findByWorkspaceIdAndRequestId(workspaceId, requestId() + "#1")
                .orElseThrow();
        assertThat(again.getKind()).isEqualTo(ConversationEntry.Kind.DIGEST);
        assertThat(item(conversationId))
                .containsEntry("state", "CLAIMED")
                .containsEntry("turn_id", again.getTurnId())
                .containsEntry("starts", 2);
    }

    @Test
    void shouldStopALeasedDigestForTheQuestionWithTheStopGraceSoTheQuestionIsTheNextClaim() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        assertThat(schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue()).isEqualTo(1);
        var stimulus = digestEntry().orElseThrow();
        UUID conversationId = stimulus.getConversationId();
        UUID digestTurn = Objects.requireNonNull(stimulus.getTurnId());
        var claim = Objects.requireNonNull(transactionTemplate.execute(tx -> {
            assertThat(writeFence.holdForAccountWrite(accountId)).isTrue();
            var leased = runs.claimSubject(workspaceId, "conversation:" + conversationId, Set.of(AgentRunKind.DIGEST))
                    .orElseThrow();
            assertThat(turns.start(workspaceId, leased.runId(), leased.epoch())).isPresent();
            return leased;
        }));

        var asked = turns.submit(workspaceId, conversationId, question("Can you look at my latest change?"));

        assertThat(turnRows.findById(digestTurn).orElseThrow().getStatus()).isEqualTo(ConversationTurn.Status.STOPPED);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT deadline <= now() + interval '31 seconds' FROM agent_run WHERE id=? AND workspace_id=?",
                        Boolean.class,
                        claim.runId(),
                        workspaceId))
                .as("the digest's holder gets the stop grace, not the rest of its work")
                .isTrue();
        // The holder settles within the grace and stores nothing; then the question is the next claim.
        transactionTemplate.executeWithoutResult(tx -> runs.end(claim.fence(), AgentRunStopReason.CANCELLED));
        var next = transactionTemplate.execute(tx -> runs.claimSubject(
                workspaceId, "conversation:" + conversationId, Set.of(AgentRunKind.TURN, AgentRunKind.DIGEST)));
        assertThat(next)
                .as("the question is the next claim of its thread")
                .hasValueSatisfying(lease -> assertThat(lease.runId()).isEqualTo(asked.turn().getRunId()));
    }

    @Test
    void shouldLeaveTheDigestWaitingWhileTheQuestionRunsAndExpireItAtTheCutoff() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        var main = conversations.findOrCreateMain(workspaceId, accountId);
        var asked = turns.submit(workspaceId, main.getId(), question("What should I fix first?"));

        assertThat(schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue()).isEqualTo(1);
        assertThat(digestEntry()).as("no digest turn while the question waits").isEmpty();
        assertThat(item(main.getId())).containsEntry("state", "PENDING").containsEntry("reason", "DEFERRED_ACTIVE");

        turns.stop(workspaceId, asked.turn().getId());
        starts(Clock.fixed(week.contactCutoff().plusSeconds(1), ZoneOffset.UTC)).startDue();
        assertThat(item(main.getId())).containsEntry("state", "EXPIRED").containsEntry("reason", "DEADLINE_PASSED");
        assertThat(digestEntry()).as("a late digest is never written").isEmpty();
    }

    @Test
    void shouldNotStartTheDigestAgainAfterTheDeveloperTurnedItOffAndOn() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        var main = conversations.findOrCreateMain(workspaceId, accountId);
        var asked = turns.submit(workspaceId, main.getId(), question("What changed this week?"));
        var schedule = schedule(Clock.fixed(due(), ZoneOffset.UTC));
        assertThat(schedule.enqueueDue()).isEqualTo(1);

        // Opted out and in again: a new consent, which the item was not scheduled under.
        when(subscriptions.enabledSince(accountId))
                .thenReturn(Optional.of(week.start().plusSeconds(60)));
        turns.stop(workspaceId, asked.turn().getId());
        starts(Clock.fixed(due(), ZoneOffset.UTC)).startDue();
        assertThat(item(main.getId())).containsEntry("state", "DROPPED").containsEntry("reason", "NO_LONGER_ELIGIBLE");
        assertThat(digestEntry()).isEmpty();
    }

    private ConversationTurnService.Question question(String text) {
        return new ConversationTurnService.Question(
                ConversationTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null);
    }

    private Map<String, Object> item(UUID conversationId) {
        return jdbcTemplate.queryForMap(
                "SELECT state, reason, starts, attempts, turn_id FROM inbox_item WHERE workspace_id=?"
                        + " AND conversation_id=? AND source_key=?",
                workspaceId,
                conversationId,
                DigestSchedule.sourceKey(week));
    }

    private String runState(UUID runId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT state FROM agent_run WHERE id=? AND workspace_id=?", String.class, runId, workspaceId));
    }

    private static Set<UUID> sameWorkspace(long workspaceId, Collection<ResultFacts> results) {
        return results.stream()
                .filter(result -> result.workspaceId() == workspaceId)
                .map(ResultFacts::id)
                .collect(Collectors.toSet());
    }

    private WeeklyDigestDTO render(WeeklyDigestPayload payload) {
        return facts.render(workspaceId, accountId, List.of(payload)).getFirst().digest();
    }

    private DigestSchedule schedule(Clock clock) {
        return new DigestSchedule(
                subscriptions,
                new WeeklyDigestAvailability(memberships, readiness, admission),
                accounts,
                facts,
                conversations,
                entries,
                inbox,
                starts(clock),
                writeFence,
                objectMapper,
                transactionTemplate,
                clock);
    }

    private DigestStarts starts(Clock clock) {
        return new DigestStarts(
                inbox,
                subscriptions,
                new WeeklyDigestAvailability(memberships, readiness, admission),
                facts,
                turns,
                eligibility(),
                conversationAccess,
                objectMapper,
                clock);
    }

    private DigestAdmission eligibility() {
        AgentRuns replay = mock(AgentRuns.class);
        when(replay.conversationDeadlineClock()).thenReturn(due());
        return eligibility(replay);
    }

    private DigestAdmission eligibility(AgentRuns runs) {
        return new DigestAdmission(
                turnAccess,
                conversationAccess,
                subscriptions,
                new WeeklyDigestAvailability(memberships, readiness, admission),
                facts,
                objectMapper,
                runs);
    }

    private DigestDelivery delivery(List<WeeklyDigestTransport> transports) {
        return new DigestDelivery(
                turnAccess, conversationAccess, eligibility(), transports, objectMapper, events, Clock.systemUTC());
    }

    private void finishDigest(List<WeeklyDigestTransport> transports) {
        finishDigest(transports, ConversationTurn.Status.DONE);
    }

    private void finishDigest(List<WeeklyDigestTransport> transports, ConversationTurn.Status outcome) {
        var stimulus = digestEntry().orElseThrow();
        UUID turnId = Objects.requireNonNull(stimulus.getTurnId());
        transactionTemplate.executeWithoutResult(tx -> {
            assertThat(writeFence.holdForAccountWrite(accountId)).isTrue();
            var claim = runs.claimSubject(
                            workspaceId, "conversation:" + stimulus.getConversationId(), Set.of(AgentRunKind.DIGEST))
                    .orElseThrow();
            assertThat(turns.start(workspaceId, claim.runId(), claim.epoch()))
                    .get()
                    .extracting(ConversationTurn::getId)
                    .isEqualTo(turnId);
            assertThat(turns.updatePartial(
                            workspaceId,
                            turnId,
                            claim.epoch(),
                            claim.runId(),
                            new ConversationTurnProgress.Update(1, 0, 0, 0, "Unadmitted draft")))
                    .isFalse();
            var answer = objectMapper
                    .createObjectNode()
                    .put("type", "message")
                    .put("id", UUID.randomUUID().toString())
                    .put("timestamp", Instant.now().toString());
            answer.putObject("message")
                    .put("role", "assistant")
                    .putArray("content")
                    .addObject()
                    .put("type", "text")
                    .put("text", "Your weekly recap is ready.");
            var prompt = objectMapper
                    .createObjectNode()
                    .put("type", "message")
                    .put("id", UUID.randomUUID().toString());
            prompt.putObject("message").put("role", "user").put("content", stimulus.getText());
            new ConversationTurnCompletion(
                            turns,
                            eligibility(),
                            claims,
                            answers,
                            usage,
                            meters,
                            Clock.fixed(due(), ZoneOffset.UTC),
                            events)
                    .end(
                            workspaceId,
                            turnId,
                            claim.epoch(),
                            claim.runId(),
                            new ConversationTurnCompletion.Ending(
                                    outcome, List.of(prompt, answer), null, null, null, null));
            delivery(transports)
                    .on(new ConversationLedgerEvents.TurnChanged(
                            workspaceId,
                            stimulus.getConversationId(),
                            stimulus.getAccountId(),
                            turnId,
                            outcome,
                            ConversationTurn.Surface.SYSTEM,
                            claim.epoch()));
        });
    }

    @Test
    void shouldCommitTheModelAnswerAndTheActualSlackActionTogether() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        assertThat(schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue()).isEqualTo(1);
        var stimulus = digestEntry().orElseThrow();
        when(transactionTransport.reaches(workspaceId, accountId)).thenReturn(true);
        finishDigest(List.of());
        assertThat(entryRepository.findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
                        workspaceId, Objects.requireNonNull(stimulus.getTurnId()), ConversationEntry.Kind.ANSWER))
                .isNotEmpty();
        assertThat(digestActions(stimulus)).containsExactly("SLACK_POST");
    }

    @Test
    void shouldRollBackTheModelAnswerAndLeaveNoNotificationAction() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        assertThat(schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue()).isEqualTo(1);
        var stimulus = digestEntry().orElseThrow();
        when(transactionTransport.reaches(workspaceId, accountId)).thenReturn(true);
        transactionTemplate.executeWithoutResult(status -> {
            finishDigest(List.of());
            status.setRollbackOnly();
        });
        assertThat(entryRepository.findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
                        workspaceId, Objects.requireNonNull(stimulus.getTurnId()), ConversationEntry.Kind.ANSWER))
                .isEmpty();
        assertThat(digestActions(stimulus)).isEmpty();
    }

    private List<String> digestActions(ConversationEntry stimulus) {
        return jdbcTemplate
                .queryForList(
                        "SELECT kind FROM agent_action WHERE workspace_id=? AND idempotency_key=?",
                        String.class,
                        workspaceId,
                        ConversationSlackReplies.digestKey(stimulus.getId()))
                .stream()
                .map(Objects::requireNonNull)
                .toList();
    }

    @ParameterizedTest
    @EnumSource(
            value = ConversationTurn.Status.class,
            names = {"STOPPED", "FAILED"})
    void shouldPublishNoPartialOrMessageForAnUnfinishedDigest(ConversationTurn.Status outcome) {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        assertThat(schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue()).isEqualTo(1);
        var stimulus = digestEntry().orElseThrow();
        UUID turnId = Objects.requireNonNull(stimulus.getTurnId());
        finishDigest(List.of(), outcome);
        assertThat(entryRepository.findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
                        workspaceId, turnId, ConversationEntry.Kind.ANSWER))
                .isEmpty();
        assertThat(timeline.liveEntry(workspaceId, accountId, stimulus.getId())).isEmpty();
        assertThat(entryRepository.searchByWorkspaceId(
                        workspaceId, List.of(stimulus.getConversationId()), null, null, null, 50))
                .noneMatch(hit -> hit.getId().equals(stimulus.getId()));
        assertThat(observedEvents.of(
                        ConversationLedgerEvents.ProgressChanged.class,
                        ConversationLedgerEvents.ProgressChanged::turnId,
                        turnId))
                .isEmpty();
        assertThat(written()).isEmpty();
    }

    @Test
    void shouldDiscardTheAnswerAndSendNoNoteWhenTheDeveloperOptsOutBeforeCompletion() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        assertThat(schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue()).isEqualTo(1);
        var stimulus = digestEntry().orElseThrow();
        UUID turnId = Objects.requireNonNull(stimulus.getTurnId());
        when(subscriptions.enabledSince(accountId)).thenReturn(Optional.empty());

        finishDigest(List.of());

        assertThat(turnRows.findByIdAndWorkspaceId(turnId, workspaceId))
                .get()
                .extracting(ConversationTurn::getStatus)
                .isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(entryRepository.findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
                        workspaceId, turnId, ConversationEntry.Kind.ANSWER))
                .isEmpty();
        assertThat(timeline.liveEntry(workspaceId, accountId, stimulus.getId())).isEmpty();
        assertThat(written()).isEmpty();
        assertThat(meters.find(AgentMetrics.CONVERSATION_TURN_COMPLETED).counters())
                .isEmpty();
        var next = turns.submit(
                workspaceId,
                stimulus.getConversationId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        "What should I read next?",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        assertThat(paths.forTurn(workspaceId, next.turn().getId(), BERLIN).entries())
                .isEmpty();
    }

    @Test
    void shouldWriteTheEligibleRecapWhenOlderFeedbackWasAlreadyWithdrawn() {
        Piece removed = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        Piece kept = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(2));
        jdbcTemplate.update(
                "INSERT INTO feedback_decision(id,workspace_id,feedback_id,action,note,decided_at)"
                        + " VALUES (?,?,?,'WITHDRAW','Wrong about the work',?)",
                UUID.randomUUID(),
                workspaceId,
                removed.id(),
                Timestamp.from(Instant.now()));
        assertThat(schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue()).isEqualTo(1);
        var stimulus = digestEntry().orElseThrow();
        UUID turnId = Objects.requireNonNull(stimulus.getTurnId());
        assertThat(eligibility().refuses(workspaceId, turnId)).isFalse();
        finishDigest(List.of());
        assertThat(turnRows.findByIdAndWorkspaceId(turnId, workspaceId))
                .get()
                .extracting(ConversationTurn::getStatus)
                .isEqualTo(ConversationTurn.Status.DONE);
        assertThat(digestOnTheTimeline().feedback())
                .extracting(WeeklyDigestFeedbackDTO::feedbackId)
                .containsExactly(kept.id());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRejectCapturedFactsThatLoseEligibilityEvenWhenOtherFactsRemain(boolean withdrawn) {
        Piece removed = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        Piece kept = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(2));
        assertThat(schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue()).isEqualTo(1);
        var stimulus = digestEntry().orElseThrow();
        UUID turnId = Objects.requireNonNull(stimulus.getTurnId());
        assertThat(eligibility().refuses(workspaceId, turnId)).isFalse();
        if (withdrawn) {
            jdbcTemplate.update(
                    "INSERT INTO feedback_decision(id,workspace_id,feedback_id,action,note,decided_at)"
                            + " VALUES (?,?,?,'WITHDRAW','Wrong about the work',?)",
                    UUID.randomUUID(),
                    workspaceId,
                    removed.id(),
                    Timestamp.from(Instant.now()));
        } else {
            data.mark(removed.result(), ResultMarkKind.INCORRECT, accountId);
        }
        assertThat(facts.compose(workspaceId, accountId, week))
                .get()
                .extracting(WeeklyDigestPayload::feedbackIds)
                .isEqualTo(List.of(kept.id()));
        assertThat(eligibility().refuses(workspaceId, turnId)).isTrue();
        finishDigest(List.of());
        assertThat(turnRows.findByIdAndWorkspaceId(turnId, workspaceId))
                .get()
                .extracting(ConversationTurn::getStatus)
                .isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(entryRepository.findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
                        workspaceId, turnId, ConversationEntry.Kind.ANSWER))
                .isEmpty();
        assertThat(digestActions(stimulus)).isEmpty();
        assertThat(written()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldUseTheScheduledCutoffWhenAClaimFollowsTheSchedulerSample(boolean expired) {
        if (expired) {
            week = WeeklyDigestWindow.previousWeek(Instant.now().minus(Duration.ofDays(7)), BERLIN);
            when(subscriptions.enabledSince(accountId)).thenReturn(Optional.of(week.start()));
        }
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        var cutoff = week.contactCutoff();

        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            // The scheduler sampled time before closing. The database clock may already be past closing.
            assertThat(schedule(Clock.fixed(cutoff.minusSeconds(30), BERLIN)).enqueueDue())
                    .isOne();
            var stimulus = digestEntry().orElseThrow();
            assertThat(Objects.requireNonNull(stimulus.getPayload())
                            .path("startBy")
                            .asString())
                    .isEqualTo(cutoff.toString());
            var turn = turnRows.findById(Objects.requireNonNull(stimulus.getTurnId()))
                    .orElseThrow();
            var run = runs.find(new AgentRunRef(turn.getRunId(), workspaceId)).orElseThrow();
            var lease = runs.claimSubject(
                    workspaceId, "conversation:" + stimulus.getConversationId(), Set.of(AgentRunKind.DIGEST));
            if (expired) {
                assertThat(run.getCreatedAt()).isAfter(cutoff);
                assertThat(run.getStartWithinSeconds()).isOne();
                assertThat(lease).as("the cutoff comes from the scheduled week").isEmpty();
            } else {
                assertThat(run.getCreatedAt()).isBefore(cutoff);
                assertThat(run.getStartWithinSeconds())
                        .as("the rollback reader has an expiry")
                        .isNotNull();
                assertThat(run.getAvailableAt().plusSeconds(Objects.requireNonNull(run.getStartWithinSeconds())))
                        .isBetween(cutoff.minusSeconds(1), cutoff);
                assertThat(lease)
                        .hasValueSatisfying(claim -> assertThat(claim.runId()).isEqualTo(run.getId()));
            }
        });
    }

    @Test
    void shouldRefuseDigestAdmissionWhenTheJvmClockStillShowsAnOpenWindow() {
        Instant databaseNow = runs.conversationDeadlineClock();
        week = WeeklyDigestWindow.previousWeek(databaseNow.minus(Duration.ofDays(7)), BERLIN);
        when(subscriptions.enabledSince(accountId)).thenReturn(Optional.of(week.start()));
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));

        assertThat(week.contactCutoff()).isBefore(databaseNow);
        assertThat(schedule(Clock.fixed(due(), ZoneOffset.UTC)).enqueueDue()).isOne();
        UUID turnId = Objects.requireNonNull(digestEntry().orElseThrow().getTurnId());

        doReturn(due()).when(jvmClock).instant();
        assertThat(eligibility().refuses(workspaceId, turnId)).isFalse();
        assertThat(eligibility(runs).refuses(workspaceId, turnId)).isTrue();
    }

    @Test
    void shouldRefuseToLeaseADigestWhenItsStimulusWasRedacted() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            assertThat(schedule(Clock.fixed(due(), BERLIN)).enqueueDue()).isOne();
            var stimulus = digestEntry().orElseThrow();
            redaction.redact(List.of(stimulus.getId()));
            assertThat(runs.claimSubject(
                            workspaceId, "conversation:" + stimulus.getConversationId(), Set.of(AgentRunKind.DIGEST)))
                    .isEmpty();
        });
    }

    @Test
    void shouldLeaseTheNextDigestAfterPersonErasureEndsTheQueuedDigest() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            UUID conversationId =
                    conversations.findOrCreateMain(workspaceId, accountId).getId();
            var copy = entries.append(
                    workspaceId,
                    conversationId,
                    new ConversationEntryDraft(
                            ConversationEntry.Kind.TOOL_RESULT,
                            ConversationEntry.Surface.SYSTEM,
                            "",
                            null,
                            null,
                            null,
                            null));
            assertThat(schedule(Clock.fixed(due(), BERLIN)).enqueueDue()).isOne();
            var stimulus = digestEntry().orElseThrow();
            var payload = Objects.requireNonNull(stimulus.getPayload()).deepCopy();
            UUID erasedTurnId = Objects.requireNonNull(stimulus.getTurnId());
            var erasedRun = new AgentRunRef(
                    turnRows.findById(erasedTurnId).orElseThrow().getRunId(), workspaceId);

            redaction.redactPersonCopies(List.of(copy.getId()));
            assertThat(turnRows.findById(erasedTurnId).orElseThrow().getStatus())
                    .as("the erasure ends the digest turn in its own transaction")
                    .isEqualTo(ConversationTurn.Status.STOPPED);
            assertThat(runs.find(erasedRun).orElseThrow().getStopReason()).isEqualTo(AgentRunStopReason.CANCELLED);

            String requestId = requestId() + "-next";
            assertThat(turns.submitDigest(
                            workspaceId,
                            conversationId,
                            new ConversationTurnService.DigestRequest(
                                    requestId, "Next digest", objectMapper.valueToTree(payload), week.contactCutoff())))
                    .isPresent();
            var next = turnRows.findByWorkspaceIdAndRequestId(workspaceId, requestId)
                    .orElseThrow();
            assertThat(runs.claimSubject(workspaceId, "conversation:" + conversationId, Set.of(AgentRunKind.DIGEST)))
                    .hasValueSatisfying(lease -> assertThat(lease.runId()).isEqualTo(next.getRunId()));
        });
    }

    @Test
    void shouldSkipADigestWhenLessThanOneSecondRemainsInItsDeliveryWindow() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        var nearClosing = week.end().plus(Duration.ofHours(21)).minusMillis(500);
        assertThat(schedule(Clock.fixed(nearClosing, BERLIN)).enqueueDue()).isZero();
        assertThat(digestEntry()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldLeaseTheNextDigestAfterAnEarlierReplicaErasedTheQueuedDigest(boolean subjectClaim) {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            assertThat(schedule(Clock.fixed(due(), BERLIN)).enqueueDue()).isOne();
            var stimulus = digestEntry().orElseThrow();
            var payload = Objects.requireNonNull(stimulus.getPayload()).deepCopy();
            UUID erasedTurnId = Objects.requireNonNull(stimulus.getTurnId());
            var erasedRun = new AgentRunRef(
                    turnRows.findById(erasedTurnId).orElseThrow().getRunId(), workspaceId);
            // rc.16 erased the stimulus without changing the queued turn or run.
            assertThat(jdbcTemplate.update("""
                            UPDATE conversation_entry
                               SET redacted_at = clock_timestamp(), payload = NULL, text = '', search_text = ''
                             WHERE workspace_id = ? AND id = ?
                            """, workspaceId, stimulus.getId())).isOne();

            UUID conversationId = stimulus.getConversationId();
            String requestId = requestId() + "-next";
            assertThat(turns.submitDigest(
                            workspaceId,
                            conversationId,
                            new ConversationTurnService.DigestRequest(
                                    requestId, "Next digest", objectMapper.valueToTree(payload), week.contactCutoff())))
                    .isPresent();
            var next = turnRows.findByWorkspaceIdAndRequestId(workspaceId, requestId)
                    .orElseThrow();
            assertThat(runs.find(erasedRun).orElseThrow().getStartWithinSeconds())
                    .as("the older writer set the relative window")
                    .isPositive();

            var kinds = Set.of(AgentRunKind.DIGEST);
            var lease = subjectClaim
                    ? runs.claimSubject(workspaceId, "conversation:" + conversationId, kinds)
                    : runs.claim(kinds, List.of());
            assertThat(lease)
                    .as("the erased digest releases its queue position during the claim")
                    .hasValueSatisfying(claim -> assertThat(claim.runId()).isEqualTo(next.getRunId()));
            assertThat(turnRows.findById(erasedTurnId).orElseThrow().getStatus())
                    .isEqualTo(ConversationTurn.Status.STOPPED);
            assertThat(runs.find(erasedRun).orElseThrow().getStopReason()).isEqualTo(AgentRunStopReason.CANCELLED);
        });
    }

    private static WeeklyDigestTransport transport(WeeklyDigestPointer pointer, boolean reaches) {
        WeeklyDigestTransport transport = mock(WeeklyDigestTransport.class);
        when(transport.pointer()).thenReturn(pointer);
        when(transport.reaches(anyLong(), anyLong())).thenReturn(reaches);
        return transport;
    }

    /** Monday 10:00 in Berlin, right after the week. */
    private Instant due() {
        return week.end().plus(Duration.ofHours(10));
    }

    private Instant inWeek(int day) {
        return week.start().plus(Duration.ofDays(day)).plus(Duration.ofHours(10));
    }

    private List<WeeklyDigestWritten> written() {
        return published.stream()
                .filter(WeeklyDigestWritten.class::isInstance)
                .map(WeeklyDigestWritten.class::cast)
                .toList();
    }

    private String requestId() {
        return DigestSchedule.requestId(workspaceId, accountId, week);
    }

    private Optional<ConversationEntry> digestEntry() {
        return entryRepository.findByWorkspaceIdAndRequestId(workspaceId, requestId());
    }

    private WeeklyDigestDTO digestOnTheTimeline() {
        return timeline.entries(workspaceId, accountId, null, 50).content().stream()
                .filter(entry -> entry.kind() == ConversationEntryDTO.Kind.DIGEST)
                .map(ConversationEntryDTO::digest)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow();
    }

    private JsonNode document(UUID conversationId) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        contentSource.contribute(new ConversationContextRequest(workspaceId, accountId, conversationId, null), files);
        return objectMapper.readTree(Objects.requireNonNull(files.get(WeeklyDigestContentSource.OUTPUT_KEY)));
    }

    /** A piece delivered at {@code at} when its state says it was delivered. */
    private Piece save(FeedbackDestination destination, FeedbackState state, Instant at) {
        boolean delivered = state == FeedbackState.DELIVERED
                || state == FeedbackState.UNCONFIRMED
                || state == FeedbackState.REPLACED;
        return save(destination, state, at, delivered ? at : null);
    }

    /**
     * One piece of feedback on its own item, resting on one result with one problem that was recorded a minute
     * before {@code at}. The piece was prepared half a minute before {@code at}.
     */
    private Piece save(
            FeedbackDestination destination, FeedbackState state, Instant at, @Nullable Instant deliveredAt) {
        Item item = new Item();
        item.setWorkspaceId(workspaceId);
        item.setKind(scenario.item().getKind());
        item.setSourceId(ThreadLocalRandom.current().nextLong(1_000_000_000_000L, Long.MAX_VALUE));
        item.setCreatedAt(week.start().minus(Duration.ofDays(30)));
        itemRepository.saveAndFlush(item);
        Result result = data.result(
                new Scenario(scenario.workspace(), scenario.account(), scenario.practice(), item),
                ResultOutcome.NOT_MET,
                at.minusSeconds(60));
        UUID problemId = Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT id FROM problem WHERE workspace_id=? AND result_id=?",
                UUID.class,
                workspaceId,
                result.getId()));
        Feedback feedback = feedbackRepository.saveAndFlush(Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .reviewId(result.getReviewId())
                .itemId(destination == FeedbackDestination.PRACTICE_PAGE ? null : result.getItemId())
                .developerId(accountId)
                .destination(destination)
                .state(state)
                .body("Link the issue the change resolves.")
                .createdAt(at.minusSeconds(30))
                .deliveredAt(deliveredAt)
                .build());
        basisRepository.saveAndFlush(FeedbackBasis.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .feedbackId(feedback.getId())
                .resultId(result.getId())
                .problemId(problemId)
                .developerId(accountId)
                .ordinal(0)
                .build());
        return new Piece(feedback, result, problemId);
    }
}
