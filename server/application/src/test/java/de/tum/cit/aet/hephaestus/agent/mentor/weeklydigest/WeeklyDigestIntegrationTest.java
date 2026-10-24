package de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.WeeklyDigestContentSource;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorRefusal;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDraft;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorRedaction;
import de.tum.cit.aet.hephaestus.mentor.MentorTimeline;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.MentorReadinessQuery;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A developer's weekly digest, from the facts of their local week to the entry in their Heph conversation, its live
 * rendering and the document Heph reads in its thread. The evidence authorization boundary permits every stored
 * result of the workspace, as in the other review-evidence tests.
 */
class WeeklyDigestIntegrationTest extends BaseIntegrationTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @MockitoBean
    EvidenceAuthorization evidenceAuthorization;

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
    private MentorConversationService conversations;

    @Autowired
    private MentorEntryService entries;

    @Autowired
    private MentorEntryRepository entryRepository;

    @Autowired
    private MentorTimeline timeline;

    @Autowired
    private MentorRedaction redaction;

    @Autowired
    private WeeklyDigestContentSource contentSource;

    @Autowired
    private PersonDataWriteFence writeFence;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** What the composer published, recorded from any thread. */
    private final List<Object> published = new CopyOnWriteArrayList<>();

    private final ApplicationEventPublisher events = published::add;
    private final WeeklyDigestSubscriptions subscriptions = mock(WeeklyDigestSubscriptions.class);
    private final AccountMembershipQuery memberships = mock(AccountMembershipQuery.class);
    private final AccountDirectory accounts = mock(AccountDirectory.class);
    private final MentorReadinessQuery readiness = mock(MentorReadinessQuery.class);
    private final MentorAdmission admission = mock(MentorAdmission.class);

    private Scenario scenario;
    private long workspaceId;
    private long accountId;
    /** The full local week before the current one, so every fact in it is in the past. */
    private WeeklyDigestWindow week;

    /** One piece of feedback with the result and problem it rests on. */
    private record Piece(Feedback feedback, Result result, UUID problemId) {
        UUID id() {
            return feedback.getId();
        }
    }

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        when(evidenceAuthorization.permitsAll(anyLong(), anyCollection(), any()))
                .thenAnswer(invocation -> sameWorkspace(invocation.getArgument(0), invocation.getArgument(1)));
        when(evidenceAuthorization.permitsForNewDelivery(anyLong(), anyCollection(), any()))
                .thenAnswer(invocation -> sameWorkspace(invocation.getArgument(0), invocation.getArgument(1)));
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
        week = WeeklyDigestWindow.previousWeek(Instant.now(), BERLIN);

        when(subscriptions.subscribedAccountIds()).thenReturn(List.of(accountId));
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
        // Feedback that did not reach the developer in the week, which the digest leaves out.
        save(FeedbackDestination.PRACTICE_PAGE, FeedbackState.DELIVERED, inWeek(2));
        save(FeedbackDestination.ON_WORK, FeedbackState.AWAITING_APPROVAL, inWeek(3));
        save(FeedbackDestination.ON_WORK, FeedbackState.FAILED, inWeek(3));
        save(FeedbackDestination.ON_WORK, FeedbackState.UNCONFIRMED, inWeek(3), null);
        save(FeedbackDestination.CONVERSATION, FeedbackState.READY, inWeek(3));
        save(FeedbackDestination.CONVERSATION, FeedbackState.UNCONFIRMED, inWeek(3));
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, week.start().minusSeconds(60));

        assertThat(composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue()).isEqualTo(1);

        MentorEntry digest = digestEntry().orElseThrow();
        assertThat(digest.getKind()).isEqualTo(MentorEntry.Kind.DIGEST);
        assertThat(digest.getSurface()).isEqualTo(MentorEntry.Surface.SYSTEM);
        assertThat(digest.getText()).isEqualTo(WeeklyDigestComposer.text(week));
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
                        inConversation.id().toString());
        assertThat(conversations.findMain(workspaceId, accountId))
                .map(MentorConversation::getId)
                .contains(digest.getConversationId());

        assertThat(written()).singleElement().satisfies(note -> {
            assertThat(note.entryId()).isEqualTo(digest.getId());
            assertThat(note.accountId()).isEqualTo(accountId);
            assertThat(note.weekDays()).isEqualTo(week.days());
            assertThat(note.pointer()).isEqualTo(WeeklyDigestPointer.NONE);
        });
    }

    @Test
    void shouldPublishNoSecondNoteWhenAnOverlappingRunWroteTheDigestFirst() throws Exception {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        MentorConversation main = conversations.findOrCreateMain(workspaceId, accountId);
        HeldTransaction first = HeldTransaction.holdIn(
                transactionTemplate,
                jdbcTemplate,
                () -> entries.appendOnce(
                        workspaceId,
                        main.getId(),
                        MentorEntryDraft.digest(
                                WeeklyDigestComposer.text(week),
                                new WeeklyDigestPayload(week, List.of(), List.of()).toJson(objectMapper),
                                requestId())));
        CompletableFuture<Integer> overlapping = CompletableFuture.supplyAsync(
                () -> composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue());

        first.commitAfter(overlapping);

        assertThat(overlapping.get(20, TimeUnit.SECONDS)).isZero();
        assertThat(written()).isEmpty();
        // A second run in the same window finds the digest and writes nothing either.
        assertThat(composer(Clock.fixed(due().plus(Duration.ofHours(3)), ZoneOffset.UTC))
                        .composeDue())
                .isZero();
    }

    @Test
    void shouldWriteNothingWhenTheWeekWasQuiet() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, week.start().minusSeconds(3600));

        assertThat(composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue()).isZero();

        assertThat(digestEntry()).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void shouldWriteNothingWhenHephWouldNotAnswerTheDeveloper() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        when(admission.refusal(workspaceId, accountId)).thenReturn(Optional.of(MentorRefusal.NO_AI));

        assertThat(composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue()).isZero();

        assertThat(digestEntry()).isEmpty();
    }

    @Test
    void shouldWriteNothingWhenTheDeveloperIsNoLongerAnActiveMember() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        when(memberships.isActiveVisibleMember(workspaceId, accountId)).thenReturn(false);

        assertThat(composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue()).isZero();

        assertThat(digestEntry()).isEmpty();
    }

    @Test
    void shouldPointThroughSlackBeforeEmailWhenBothReachTheDeveloper() {
        save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        WeeklyDigestTransport email = transport(WeeklyDigestPointer.EMAIL, true);
        WeeklyDigestTransport slack = transport(WeeklyDigestPointer.SLACK, true);

        composer(Clock.fixed(due(), ZoneOffset.UTC), List.of(email, slack)).composeDue();

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
    void shouldLinkConversationFeedbackToTheHephMessageUntilTheMessageIsErased() {
        Piece inConversation = save(FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, inWeek(1));
        MentorConversation main = conversations.findOrCreateMain(workspaceId, accountId);
        UUID answer = entries.append(
                        workspaceId,
                        main.getId(),
                        new MentorEntryDraft(
                                MentorEntry.Kind.HEPH,
                                MentorEntry.Surface.APP,
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
                .mentorEntryId(answer)
                .createdAt(inWeek(1))
                .build());

        composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue();

        assertThat(digestOnTheTimeline().feedback()).singleElement().satisfies(row -> {
            assertThat(row.feedbackId()).isEqualTo(inConversation.id());
            assertThat(row.destination()).isEqualTo(FeedbackDestination.CONVERSATION);
            assertThat(row.mentorEntryId()).isEqualTo(answer);
            assertThat(row.item()).isNull();
        });

        redaction.redact(List.of(answer));

        assertThat(digestOnTheTimeline().feedback())
                .singleElement()
                .satisfies(row -> assertThat(row.mentorEntryId()).isNull());
    }

    @Test
    void shouldShowWithdrawnFeedbackByItsPracticeAndHideFeedbackWhenItsEvidenceCannotBeShown() {
        Piece hidden = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        Piece withdrawn = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(2));
        composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue();

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
        composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue();

        jdbcTemplate.update("UPDATE feedback SET state='REPLACED' WHERE id=?", replacedLater.id());

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
    void shouldGiveHephTheDigestWithItsResultsAndProblemsOnlyWhenItRunsInAThreadOnIt() throws Exception {
        Piece onTheWork = save(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, inWeek(1));
        composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue();
        MentorEntry digest = digestEntry().orElseThrow();
        MentorConversation thread = conversations.openThread(workspaceId, digest.getId());

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

    private static Set<UUID> sameWorkspace(long workspaceId, Collection<Result> results) {
        return results.stream()
                .filter(result -> result.getWorkspaceId() == workspaceId)
                .map(Result::getId)
                .collect(Collectors.toSet());
    }

    private WeeklyDigestDTO render(WeeklyDigestPayload payload) {
        return facts.render(workspaceId, accountId, List.of(payload)).getFirst().digest();
    }

    private WeeklyDigestComposer composer(Clock clock) {
        return composer(clock, List.of());
    }

    private WeeklyDigestComposer composer(Clock clock, List<WeeklyDigestTransport> transports) {
        return new WeeklyDigestComposer(
                subscriptions,
                new WeeklyDigestAvailability(memberships, readiness, admission),
                accounts,
                facts,
                conversations,
                entries,
                writeFence,
                transports,
                events,
                objectMapper,
                transactionManager,
                clock);
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
        return WeeklyDigestComposer.requestId(workspaceId, accountId, week);
    }

    private Optional<MentorEntry> digestEntry() {
        return entryRepository.findByWorkspaceIdAndRequestId(workspaceId, requestId());
    }

    private WeeklyDigestDTO digestOnTheTimeline() {
        return timeline.entries(workspaceId, accountId, null, 50).entries().stream()
                .filter(entry -> entry.kind() == MentorEntryDTO.Kind.DIGEST)
                .map(MentorEntryDTO::digest)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow();
    }

    private JsonNode document(UUID conversationId) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        contentSource.contribute(new MentorContextRequest(workspaceId, accountId, conversationId, null), files);
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
