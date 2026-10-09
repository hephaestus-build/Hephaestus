package de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.WeeklyDigestContentSource;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorRefusal;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery.WorkspaceMembershipView;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDraft;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorRedaction;
import de.tum.cit.aet.hephaestus.mentor.MentorTimeline;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawal;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawalRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.Reaction;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileChangeDTO;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestChangeKey;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestDTO;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFacts;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestPayload;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestWindow;
import de.tum.cit.aet.hephaestus.testconfig.AdmittedReviewJobFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A developer's weekly digest, from the facts of their local week to the entry in their Heph conversation, its live
 * rendering and the document Heph reads in its thread.
 */
class WeeklyDigestIntegrationTest extends BaseIntegrationTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final long ACCOUNT_ID = 4242L;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMembershipRepository membershipRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private LlmConnectionRepository llmConnectionRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    @Autowired
    private WorkspaceAgentBindingRepository workspaceAgentBindingRepository;

    @Autowired
    private LlmModelResolver llmModelResolver;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private FeedbackWithdrawalRepository withdrawalRepository;

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
    private WeeklyDigestContentSource contentSource;

    @Autowired
    private ReactionRepository reactionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FeedbackPlacementRepository placementRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private SqlReadMeasurement reads;

    @Autowired
    private MentorRedaction redaction;

    /** What the composer published, recorded from any thread. */
    private final List<Object> published = new CopyOnWriteArrayList<>();

    private final ApplicationEventPublisher events = published::add;
    private final WeeklyDigestSubscriptions subscriptions = mock(WeeklyDigestSubscriptions.class);
    private final AccountWorkspaceMembershipQuery memberships = mock(AccountWorkspaceMembershipQuery.class);
    private final AccountPreferencesQuery preferences = mock(AccountPreferencesQuery.class);
    private final MentorReadinessQuery readiness = mock(MentorReadinessQuery.class);
    private final MentorAdmission admission = mock(MentorAdmission.class);

    private Workspace workspace;
    private User developer;
    private Practice practice;
    private AgentJob job;
    /** The full local week before the current one, so every fact in it is in the past. */
    private WeeklyDigestWindow week;

    private int position;

    @BeforeEach
    void setUp() {
        // The admitted-review fixture writes a model catalog with fixed slugs.
        databaseTestUtils.cleanDatabase();
        String slug = "weekly-digest-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace active = WorkspaceTestFixtures.activeWorkspace(slug);
        active.getFeatures().setPracticesEnabled(true);
        workspace = workspaceRepository.save(active);
        IdentityProvider github = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        developer = userRepository.save(TestUserFactory.createUser(
                ThreadLocalRandom.current().nextLong(1_000_000, 9_000_000), slug + "-dev", github));
        WorkspaceMembership membership = new WorkspaceMembership();
        membership.setWorkspace(workspace);
        membership.setUser(developer);
        membershipRepository.save(membership);

        practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setSlug("issue-linking");
        practice.setName("Issue linking");
        practice.setCriteria("Link the issue the change resolves.");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        PracticeTestEvidence.configure(practice, ScmSignals.PULL_REQUEST_OPENED);
        practice = practiceRepository.saveAndFlush(practice);
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(practice, 1));
        practice.setCurrentRevision(revision);
        practice = practiceRepository.saveAndFlush(practice);
        job = newJob();
        week = WeeklyDigestWindow.previousWeek(Instant.now(), BERLIN);
        position = 0;

        when(subscriptions.subscribedAccountIds()).thenReturn(List.of(ACCOUNT_ID));
        when(preferences.timeZoneForAccount(ACCOUNT_ID)).thenReturn(Optional.of(BERLIN));
        when(memberships.membershipsForAccount(ACCOUNT_ID))
                .thenReturn(List.of(new WorkspaceMembershipView(
                        workspace.getId(),
                        workspace.getWorkspaceSlug(),
                        "Delivery team",
                        "MEMBER",
                        developer.getId(),
                        List.of(developer.getId()))));
        when(readiness.isReady(workspace.getId())).thenReturn(true);
        when(admission.refusal(workspace.getId(), developer.getId())).thenReturn(Optional.empty());
    }

    @Test
    void shouldListOnlyFeedbackThatReachedTheDeveloperInTheWeekWhenTheDigestIsWritten() {
        Feedback onTheWork = save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED, "note", inWeek(1));
        Feedback onTheProfile = save(
                FeedbackChannel.IN_APP,
                FeedbackDeliveryState.DELIVERED,
                InAppFeedbackBody.render("Link the issue", "message", "next step"),
                inWeek(2));
        // Feedback that did not reach the developer in the week, which the digest leaves out.
        save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.AWAITING_APPROVAL, "proposal", inWeek(3));
        save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.SUPPRESSED, "held", inWeek(3));
        save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.FAILED, "failed", inWeek(3));
        save(FeedbackChannel.IN_CHAT, FeedbackDeliveryState.PREPARED, "notes", inWeek(3));
        save(
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.DELIVERED,
                "older",
                week.start().minusSeconds(60));

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
                        onTheWork.getId().toString(), onTheProfile.getId().toString());

        assertThat(written()).singleElement().satisfies(note -> {
            assertThat(note.entryId()).isEqualTo(digest.getId());
            assertThat(note.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(note.weekDays()).isEqualTo(week.days());
            assertThat(note.pointer()).isEqualTo(WeeklyDigestPointer.NONE);
        });
    }

    @Test
    void shouldPublishNoSecondNoteWhenAnOverlappingRunWroteTheDigestFirst() throws Exception {
        save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED, "note", inWeek(1));
        MentorConversation main =
                conversations.findOrCreateMain(workspace.getId(), developer.getId(), Set.of(developer.getId()));
        HeldTransaction first = HeldTransaction.holdIn(
                transactionTemplate,
                jdbcTemplate,
                () -> entries.appendOnce(
                        workspace.getId(),
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
    void shouldWriteTheDigestIntoTheAccountsConversationWhenAnotherOfItsActorsStartedIt() {
        save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED, "note", inWeek(1));
        User otherActor = userRepository.save(TestUserFactory.createUser(
                ThreadLocalRandom.current().nextLong(1_000_000, 9_000_000),
                developer.getLogin() + "-gitlab",
                developer.getProvider()));
        MentorConversation started =
                conversations.findOrCreateMain(workspace.getId(), otherActor.getId(), Set.of(otherActor.getId()));
        when(memberships.membershipsForAccount(ACCOUNT_ID))
                .thenReturn(List.of(new WorkspaceMembershipView(
                        workspace.getId(),
                        workspace.getWorkspaceSlug(),
                        "Delivery team",
                        "MEMBER",
                        developer.getId(),
                        List.of(developer.getId(), otherActor.getId()))));

        assertThat(composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue()).isEqualTo(1);

        assertThat(digestEntry().orElseThrow().getConversationId()).isEqualTo(started.getId());
    }

    @Test
    void shouldWriteNothingWhenTheWeekWasQuiet() {
        save(
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.DELIVERED,
                "older",
                week.start().minusSeconds(3600));

        assertThat(composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue()).isZero();

        assertThat(digestEntry()).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void shouldWriteNothingWhenHephWouldNotAnswerTheDeveloper() {
        save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED, "note", inWeek(1));
        when(admission.refusal(workspace.getId(), developer.getId())).thenReturn(Optional.of(MentorRefusal.NO_AI));

        assertThat(composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue()).isZero();

        assertThat(digestEntry()).isEmpty();
    }

    @Test
    void shouldPointThroughSlackBeforeEmailWhenBothReachTheDeveloper() {
        save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED, "note", inWeek(1));
        WeeklyDigestTransport email = transport(WeeklyDigestPointer.EMAIL, true);
        WeeklyDigestTransport slack = transport(WeeklyDigestPointer.SLACK, true);

        composer(Clock.fixed(due(), ZoneOffset.UTC), List.of(email, slack)).composeDue();

        assertThat(written())
                .singleElement()
                .extracting(WeeklyDigestWritten::pointer)
                .isEqualTo(WeeklyDigestPointer.SLACK);
    }

    @Test
    void shouldWriteNoDigestWhenAnOldUnreadCardIsAllThereIs() {
        save(
                FeedbackChannel.IN_APP,
                FeedbackDeliveryState.PREPARED,
                InAppFeedbackBody.render("Link the issue", "message", "next step"),
                week.start().minus(Duration.ofDays(3)));

        assertThat(facts.compose(workspace.getId(), developer.getId(), week)).isEmpty();
    }

    @Test
    void shouldCountACardPreparedInTheWeekAsWaitingWhenItIsStillUnread() {
        // Its observation is just before the week, so the card is the only thing the week holds.
        save(
                FeedbackChannel.IN_APP,
                FeedbackDeliveryState.PREPARED,
                InAppFeedbackBody.render("Link the issue", "message", "next step"),
                week.start().plusSeconds(45));

        WeeklyDigestPayload payload =
                facts.compose(workspace.getId(), developer.getId(), week).orElseThrow();

        assertThat(payload.feedbackIds()).isEmpty();
        assertThat(payload.changeKeys()).isEmpty();
        WeeklyDigestDTO digest = render(payload);
        assertThat(digest.waiting()).isEqualTo(1);
        assertThat(digest.weekStart()).isEqualTo(week.weekStart());
    }

    @Test
    void shouldListAChangeOnlyWhenTheDigestHeldIt() {
        save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED, "note", inWeek(1));
        WeeklyDigestPayload payload =
                facts.compose(workspace.getId(), developer.getId(), week).orElseThrow();
        assertThat(payload.changeKeys()).isNotEmpty();

        WeeklyDigestDTO held = render(payload);
        WeeklyDigestDTO heldNothing = render(new WeeklyDigestPayload(week, payload.feedbackIds(), List.of()));

        assertThat(held.changes()).isNotEmpty();
        assertThat(heldNothing.changes()).isEmpty();
    }

    @Test
    void shouldWriteADigestWhenTheOnlyThingInTheWeekIsACardTheDeveloperResolved() {
        Feedback card = save(
                FeedbackChannel.IN_APP,
                FeedbackDeliveryState.PREPARED,
                InAppFeedbackBody.render("Link the issue", "message", "next step"),
                week.start().minus(Duration.ofDays(3)));
        reactionRepository.save(Reaction.builder()
                .feedback(card)
                .reactorUserId(developer.getId())
                .resolution(FeedbackResolution.ADDRESSED)
                .createdAt(inWeek(2))
                .build());

        WeeklyDigestPayload payload =
                facts.compose(workspace.getId(), developer.getId(), week).orElseThrow();

        assertThat(payload.feedbackIds()).isEmpty();
        assertThat(payload.changeKeys())
                .extracting(WeeklyDigestChangeKey::type)
                .containsExactly(ProfileChangeDTO.Type.FEEDBACK_RESOLVED);
    }

    @Test
    void shouldLinkFeedbackToTheHephMessageUntilTheMessageIsErasedWhenItWasDeliveredInConversation() {
        Feedback inChat = save(FeedbackChannel.IN_CHAT, FeedbackDeliveryState.DELIVERED, "brief", inWeek(1));
        MentorConversation main =
                conversations.findOrCreateMain(workspace.getId(), developer.getId(), Set.of(developer.getId()));
        UUID answer = entries.append(
                        workspace.getId(),
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
        placementRepository.save(FeedbackPlacement.builder()
                .feedback(inChat)
                .placementType(PlacementType.CONVERSATION_TURN)
                .mentorEntryId(answer)
                .createdAt(inWeek(1))
                .build());

        composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue();

        assertThat(digestOnTheTimeline().feedback()).singleElement().satisfies(row -> {
            assertThat(row.feedbackId()).isEqualTo(inChat.getId());
            assertThat(row.channel()).isEqualTo(FeedbackChannel.IN_CHAT);
            assertThat(row.mentorEntryId()).isEqualTo(answer);
        });

        redaction.redact(List.of(answer));

        assertThat(digestOnTheTimeline().feedback())
                .singleElement()
                .satisfies(row -> assertThat(row.mentorEntryId()).isNull());
    }

    @Test
    void shouldShowWithdrawnFeedbackByItsPracticeAndHideFeedbackWhenItsEvidenceCannotBeShown() {
        Feedback onTheWork = save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED, "note", inWeek(1));
        Feedback onTheProfile = save(
                FeedbackChannel.IN_APP,
                FeedbackDeliveryState.DELIVERED,
                InAppFeedbackBody.render("Link the issue", "message", "next step"),
                inWeek(2));
        composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue();

        assertThat(digestOnTheTimeline().feedback())
                .extracting(WeeklyDigestFeedbackDTO::feedbackId, WeeklyDigestFeedbackDTO::title)
                .containsExactly(tuple(onTheWork.getId(), null), tuple(onTheProfile.getId(), "Link the issue"));

        withdrawalRepository.save(new FeedbackWithdrawal(onTheProfile, 7L, "Wrong about the work", Instant.now()));
        jdbcTemplate.update(
                "UPDATE observation SET evidence = CAST(? AS jsonb) WHERE id IN"
                        + " (SELECT observation_id FROM feedback_observation WHERE feedback_id = ?)",
                citing("unapproved.source"),
                onTheWork.getId());

        WeeklyDigestDTO now = digestOnTheTimeline();
        assertThat(now.feedback()).singleElement().satisfies(row -> {
            assertThat(row.feedbackId()).isEqualTo(onTheProfile.getId());
            assertThat(row.withdrawn()).isTrue();
            assertThat(row.title()).isNull();
            assertThat(row.practiceName()).isEqualTo("Issue linking");
        });
    }

    @Test
    void shouldListReplacedFeedbackWhenItReachedTheDeveloperInTheWeek() {
        Feedback replacedInTheWeek =
                save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED, "note", inWeek(1));
        Feedback replacedLater = save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED, "later", inWeek(2));
        save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.SUPERSEDED, "never sent", inWeek(3));
        feedbackRepository.supersedeDelivered(workspace.getId(), replacedInTheWeek.getId());
        composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue();

        feedbackRepository.supersedeDelivered(workspace.getId(), replacedLater.getId());

        assertThat(digestOnTheTimeline().feedback())
                .extracting(WeeklyDigestFeedbackDTO::feedbackId, WeeklyDigestFeedbackDTO::replaced)
                .containsExactly(tuple(replacedInTheWeek.getId(), true), tuple(replacedLater.getId(), true));
        assertThat(digestOnTheTimeline().feedback().getFirst().work()).isNotNull();
    }

    @Test
    void shouldRenderTwoDigestsWithTheReadsOfOneWhenAPageHoldsBoth() {
        WeeklyDigestWindow earlier = WeeklyDigestWindow.previousWeek(week.start(), BERLIN);
        Feedback lastWeek = save(
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.DELIVERED,
                "older",
                earlier.start().plus(Duration.ofDays(1)).plus(Duration.ofHours(10)));
        Feedback thisWeek = save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED, "note", inWeek(1));
        WeeklyDigestPayload first =
                facts.compose(workspace.getId(), developer.getId(), earlier).orElseThrow();
        WeeklyDigestPayload second =
                facts.compose(workspace.getId(), developer.getId(), week).orElseThrow();

        SqlReadMeasurement.Read<List<WeeklyDigestFacts.Rendered>> both =
                reads.measure(() -> facts.render(workspace.getId(), developer.getId(), List.of(first, second)));
        SqlReadMeasurement.Read<List<WeeklyDigestFacts.Rendered>> one =
                reads.measure(() -> facts.render(workspace.getId(), developer.getId(), List.of(second)));

        assertThat(both.value())
                .extracting(rendered -> rendered.digest().weekStart())
                .containsExactly(earlier.weekStart(), week.weekStart());
        assertThat(both.value().getFirst().digest().feedback())
                .extracting(WeeklyDigestFeedbackDTO::feedbackId)
                .containsExactly(lastWeek.getId());
        assertThat(both.value().getLast().digest().feedback())
                .extracting(WeeklyDigestFeedbackDTO::feedbackId)
                .containsExactly(thisWeek.getId());
        assertThat(both.cost().statements()).isEqualTo(one.cost().statements());
    }

    @Test
    void shouldGiveHephTheDigestOnlyWhenItRunsInAThreadOnIt() throws Exception {
        Feedback onTheWork = save(FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED, "note", inWeek(1));
        composer(Clock.fixed(due(), ZoneOffset.UTC)).composeDue();
        MentorEntry digest = digestEntry().orElseThrow();
        MentorConversation thread = conversations.openThread(workspace.getId(), digest.getId());

        JsonNode inThread = document(thread.getId());
        JsonNode inMain = document(digest.getConversationId());

        assertThat(inThread.path("weeklyDigest").path("feedback"))
                .singleElement()
                .satisfies(row -> assertThat(row.path("feedbackId").asString())
                        .isEqualTo(onTheWork.getId().toString()));
        assertThat(inThread.path("weeklyDigest")
                        .path("observationIds")
                        .path(onTheWork.getId().toString()))
                .hasSize(1);
        assertThat(inThread.path("weeklyDigest").path("timeZone").asString()).isEqualTo("Europe/Berlin");
        assertThat(inMain.isEmpty()).isTrue();
    }

    private WeeklyDigestDTO render(WeeklyDigestPayload payload) {
        return facts.render(workspace.getId(), developer.getId(), List.of(payload))
                .getFirst()
                .digest();
    }

    private WeeklyDigestComposer composer(Clock clock) {
        return composer(clock, List.of());
    }

    private WeeklyDigestComposer composer(Clock clock, List<WeeklyDigestTransport> transports) {
        return new WeeklyDigestComposer(
                subscriptions,
                new WeeklyDigestAvailability(memberships, readiness, admission),
                preferences,
                facts,
                conversations,
                entries,
                transports,
                events,
                objectMapper,
                transactionManager,
                clock);
    }

    private static WeeklyDigestTransport transport(WeeklyDigestPointer pointer, boolean reaches) {
        WeeklyDigestTransport transport = mock(WeeklyDigestTransport.class);
        when(transport.pointer()).thenReturn(pointer);
        when(transport.reaches(anyLong(), anyLong(), anyLong())).thenReturn(reaches);
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
        return WeeklyDigestComposer.requestId(workspace.getId(), developer.getId(), week);
    }

    private Optional<MentorEntry> digestEntry() {
        return entryRepository.findByWorkspaceIdAndRequestId(workspace.getId(), requestId());
    }

    private WeeklyDigestDTO digestOnTheTimeline() {
        return timeline.entries(workspace.getId(), Set.of(developer.getId()), null, 50).entries().stream()
                .filter(entry -> entry.kind() == MentorEntryDTO.Kind.DIGEST)
                .map(MentorEntryDTO::digest)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow();
    }

    private JsonNode document(UUID conversationId) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        contentSource.contribute(
                new ContextRequest.HephTurnRequest(workspace.getId(), developer.getId(), conversationId), files);
        return objectMapper.readTree(Objects.requireNonNull(files.get(WeeklyDigestContentSource.OUTPUT_KEY)));
    }

    private AgentJob newJob() {
        AgentJob review = new AgentJob();
        review.setWorkspace(workspace);
        review.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        review.setMetadata(objectMapper.valueToTree(Map.of("about_user_id", developer.getId())));
        review.setPracticeRolloutRevision(workspace.getReviewSettings().getRolloutRevision());
        review.setConfigSnapshot(AdmittedReviewJobFixtures.snapshot(
                workspace,
                llmConnectionRepository,
                llmModelRepository,
                workspaceAgentBindingRepository,
                llmModelResolver,
                objectMapper));
        review.setEvidenceSnapshot(objectMapper.readTree("{\"manifest\":{\"contractVersion\":\"1.2.0\"}}"));
        return agentJobRepository.save(review);
    }

    /** One piece of feedback on its own pull request, bound to one observation made a minute before {@code at}. */
    private Feedback save(FeedbackChannel channel, FeedbackDeliveryState state, String body, Instant at) {
        long pullRequest = 7_000_000L + position;
        UUID observationId = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                observationId,
                "observation-" + observationId,
                job.getId(),
                workspace.getId(),
                practice.getId(),
                practice.getCurrentRevision().getId(),
                ArtifactKinds.PULL_REQUEST.value(),
                pullRequest,
                developer.getId(),
                "Links the issue it closes",
                "MET",
                null,
                citing("scm.pull-request.core"),
                null,
                null,
                at.minusSeconds(60),
                "LIVE");
        boolean delivered = state == FeedbackDeliveryState.DELIVERED;
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(pullRequest)
                .recipientUserId(developer.getId())
                .aboutUserId(developer.getId())
                .channel(channel)
                .position(position++)
                .deliveryState(state)
                .source(FeedbackSource.AGENT)
                .body(body)
                .createdAt(at.minusSeconds(30))
                .deliveredAt(delivered ? at : null)
                .build());
        feedbackObservationRepository.insertIfAbsent(feedback.getId(), observationId, EvidenceRole.PRIMARY.name(), 0);
        return feedback;
    }

    private static String citing(String sourceKind) {
        return """
        {"citations":[{"sourceKind":"%s","artifactPath":"inputs/context/source.json",\
        "path":"source.json","startLine":1,"endLine":1,"quote":"evidence",\
        "quoteRedacted":false}]}
        """.formatted(sourceKind);
    }
}
