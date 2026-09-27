package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import static de.tum.cit.aet.hephaestus.practices.model.ObservationKind.OMISSION_GAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.DeliveredFeedbackContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.AdmittedObservationFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackLedgerRecorder;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackSupersession;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeFeedbackDeliveryPolicy;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.handler.inapp.InAppCompositionListener;
import de.tum.cit.aet.hephaestus.agent.handler.inapp.InAppFeedbackPreparer;
import de.tum.cit.aet.hephaestus.agent.handler.inapp.InAppFeedbackRouter;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawalRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.feedback.PreviousInAppFeedback;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What becomes of a card on the developer's page once a newer card about its practice is written: the in-app
 * lane replaces the card while it is open, a closed card stays as the record beside the newer one, and a
 * closed card leaves the page thirty days after it closed. Every history here is written by the test that
 * reads it.
 */
class InAppFeedbackLifecycleIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Instant FIRST_PREPARED_AT = NOW.minus(Duration.ofDays(20));

    @Autowired
    private ObservationVisibilityPolicy visibilityPolicy;

    @Autowired
    private WorkspaceReviewDefaultsProvider workspaceDefaults;

    @Autowired
    private FeedbackCompositionResultParser resultParser;

    @Autowired
    private InAppFeedbackPreparer preparer;

    @Autowired
    private PreviousInAppFeedback previousInAppFeedback;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private FeedbackWithdrawalService withdrawalService;

    @Autowired
    private DeliveredFeedbackContentSource deliveredFeedback;

    @Autowired
    private FeedbackSupersession supersession;

    @Autowired
    private FeedbackWithdrawalRepository withdrawalRepository;

    @Autowired
    private Clock clock;

    private InAppCompositionListener inAppLane;
    private Workspace workspace;
    private Practice practice;
    private User developer;

    @BeforeEach
    void seedWorkspace() {
        User owner = persistUser("lifecycle-owner");
        workspace = createWorkspace("lifecycle-ws", "Lifecycle WS", "lifecycle-org", AccountType.ORG, owner);
        developer = persistUser("testuser"); // matches @WithUser
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);
        practice = persistPractice(workspace, null, "reviewable-diff-size", "Keep the diff reviewable", null);

        // The pull requests here are review metadata only, never synced, so the composition gate would refuse
        // them as gone. The gate has its own tests (PracticeFeedbackDeliveryPolicyTest); everything after it
        // is the production lane.
        PracticeFeedbackDeliveryPolicy openGate = mock(PracticeFeedbackDeliveryPolicy.class);
        when(openGate.allowsComposition(any(), any())).thenReturn(true);
        inAppLane = new InAppCompositionListener(
                agentJobRepository,
                observationRepository,
                feedbackRepository,
                visibilityPolicy,
                workspaceDefaults,
                resultParser,
                preparer,
                openGate,
                previousInAppFeedback,
                clock);
    }

    /**
     * The second review, after the cooldown, finds the same lapse again on two newer pieces of work. The card the
     * developer already read is retired for the newer one, which cites only the work since it: the page is a
     * list of practices to work on, and one practice gets one open card.
     */
    @Test
    @WithUser
    @DisplayName("a newer card about the practice supersedes the open one, and the page shows one open card")
    void shouldSupersedeTheOpenCardWhenANewCardIsPrepared() {
        Feedback first = firstCard(FIRST_PREPARED_AT);

        Feedback second = secondReview();

        assertThat(state(first)).isEqualTo(FeedbackDeliveryState.SUPERSEDED);
        assertThat(second.getReplacesId()).isEqualTo(first.getId());
        readInAppPage(workspace)
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].id")
                .isEqualTo(second.getId().toString())
                .jsonPath("$[0].evidence[*].reviewedWork.label")
                .isEqualTo(List.of("#12", "#11"))
                .jsonPath("$[0].closedAt")
                .doesNotExist()
                .jsonPath("$[0].closedBy")
                .doesNotExist();
    }

    /**
     * A resolved card is the record of what was said; the newer card cites only the work after the answer, so a
     * slip between the card and the answer is not cited again.
     */
    @Test
    @WithUser
    @DisplayName("a card resolved by the developer is not superseded; it stays as the record beside the new one")
    void shouldKeepAResolvedCardBesideTheNewOneWhenANewCardIsPrepared() {
        Feedback first = firstCard(FIRST_PREPARED_AT);
        UUID beforeTheAnswer = slip(9, FIRST_PREPARED_AT.plus(Duration.ofDays(1)));
        markAddressed(first, developer, FIRST_PREPARED_AT.plus(Duration.ofDays(2)));

        Feedback second = secondReview();

        assertThat(state(first)).isEqualTo(FeedbackDeliveryState.DELIVERED);
        assertThat(second.getReplacesId()).isNull();
        assertThat(cited(second)).isNotEmpty().doesNotContain(beforeTheAnswer);
        readInAppPage(workspace)
                .jsonPath("$.length()")
                .isEqualTo(2)
                .jsonPath("$[0].id")
                .isEqualTo(second.getId().toString())
                .jsonPath("$[0].evidence[*].reviewedWork.label")
                .isEqualTo(List.of("#12", "#11"))
                .jsonPath("$[1].id")
                .isEqualTo(first.getId().toString())
                .jsonPath("$[1].response.resolution")
                .isEqualTo("ADDRESSED");
    }

    /**
     * An open card older than the in-app lane's window is still the card a newer one replaces, but it cannot
     * pull the newer card's evidence back past the window.
     */
    @Test
    @WithUser
    @DisplayName("an open card older than the window is superseded, and the new card cites only work inside it")
    void shouldCiteOnlyWorkInsideTheWindowWhenTheOpenCardIsOlderThanIt() {
        Instant preparedAt = NOW.minus(Duration.ofDays(InAppFeedbackRouter.PATTERN_WINDOW_DAYS + 10));
        Feedback first = firstCard(preparedAt);
        UUID beforeTheWindow = slip(9, preparedAt.plus(Duration.ofDays(5)));

        Feedback second = secondReview();

        assertThat(state(first)).isEqualTo(FeedbackDeliveryState.SUPERSEDED);
        assertThat(second.getReplacesId()).isEqualTo(first.getId());
        assertThat(cited(second)).isNotEmpty().doesNotContain(beforeTheWindow);
    }

    @Test
    @WithUser
    @DisplayName("a card resolved by the developer leaves the page thirty days after the response")
    void shouldDropACardWhenTheDeveloperResolvedItOverThirtyDaysAgo() {
        Instant preparedAt = NOW.minus(Duration.ofDays(40));
        Feedback gone = firstCard(preparedAt);
        markAddressed(gone, developer, NOW.minus(Duration.ofDays(31)));
        Feedback stillThere = card(preparedAt.plus(Duration.ofHours(1)), 20);
        respond(stillThere, developer, FeedbackResolution.NOT_APPLICABLE, NOW.minus(Duration.ofDays(29)));

        readInAppPage(workspace)
                .jsonPath("$[*].id")
                .isEqualTo(List.of(stillThere.getId().toString()))
                .jsonPath("$[0].response.resolution")
                .isEqualTo("NOT_APPLICABLE")
                .jsonPath("$[0].closedBy")
                .isEqualTo("DEVELOPER")
                .jsonPath("$[0].closedAt")
                .isEqualTo(NOW.minus(Duration.ofDays(29)).toString());
    }

    @Test
    @WithUser
    @DisplayName("a card resolved by the work leaves the page thirty days after the third clean piece of work")
    void shouldDropACardWhenTheWorkResolvedItOverThirtyDaysAgo() {
        Instant preparedAt = NOW.minus(Duration.ofDays(40));
        Feedback gone = firstCard(preparedAt);
        cleanReview(practice, developer, 11, NOW.minus(Duration.ofDays(38)));
        cleanReview(practice, developer, 12, NOW.minus(Duration.ofDays(36)));
        cleanReview(practice, developer, 13, NOW.minus(Duration.ofDays(31)));

        readInAppPage(workspace).jsonPath("$.length()").isEqualTo(0);

        assertThat(state(gone)).isEqualTo(FeedbackDeliveryState.DELIVERED);
    }

    @Test
    @WithUser
    @DisplayName("a card the work resolved less than thirty days ago is still on the page, resolved")
    void shouldKeepACardWhenTheWorkResolvedItRecently() {
        Instant preparedAt = NOW.minus(Duration.ofDays(40));
        Feedback resolved = firstCard(preparedAt);
        cleanReview(practice, developer, 11, NOW.minus(Duration.ofDays(38)));
        cleanReview(practice, developer, 12, NOW.minus(Duration.ofDays(36)));
        cleanReview(practice, developer, 13, NOW.minus(Duration.ofDays(29)));

        readInAppPage(workspace)
                .jsonPath("$[*].id")
                .isEqualTo(List.of(resolved.getId().toString()))
                .jsonPath("$[0].closedBy")
                .isEqualTo("WORK")
                .jsonPath("$[0].closedAt")
                .isEqualTo(NOW.minus(Duration.ofDays(29)).toString());
    }

    /**
     * Whether a card is still on the page is known only once it is read, so a full page of newer cards that
     * closed long ago must not push an older open card off it. The page still holds at most
     * {@link InAppFeedbackService#MAX_CARDS}, and only the cards on it are delivered: an open card pushed past
     * the limit was not read.
     */
    @Test
    @WithUser
    @DisplayName("an older open card is listed when a full page of newer cards closed over thirty days ago")
    void shouldListAnOlderOpenCardWhenAFullPageOfNewerCardsClosedOverThirtyDaysAgo() {
        Feedback pushedPast = card(NOW.minus(Duration.ofDays(100)), 31, FeedbackDeliveryState.PREPARED);
        Feedback open = card(NOW.minus(Duration.ofDays(90)), 30, FeedbackDeliveryState.PREPARED);
        for (int i = 0; i < InAppFeedbackService.MAX_CARDS; i++) {
            Feedback closed = card(NOW.minus(Duration.ofDays(60)).plusSeconds(i), 100 + i);
            markAddressed(closed, developer, NOW.minus(InAppFeedbackService.CLOSED_CARD_STAYS.plusDays(1)));
        }
        for (int i = 0; i < InAppFeedbackService.MAX_CARDS - 1; i++) {
            card(NOW.minus(Duration.ofDays(50)).plusSeconds(i), 200 + i);
        }

        readInAppPage(workspace)
                .jsonPath("$.length()")
                .isEqualTo(InAppFeedbackService.MAX_CARDS)
                .jsonPath("$[%d].id".formatted(InAppFeedbackService.MAX_CARDS - 1))
                .isEqualTo(open.getId().toString())
                .jsonPath("$[?(@.id == '" + pushedPast.getId() + "')]")
                .isEmpty();

        assertThat(state(open)).isEqualTo(FeedbackDeliveryState.DELIVERED);
        assertThat(state(pushedPast)).isEqualTo(FeedbackDeliveryState.PREPARED);
    }

    /**
     * The words were wrong while the observation behind them is valid. The card becomes a notice, the mentor keeps
     * the record without the words, the observation still counts, and the newer card neither replaces the
     * withdrawn one nor cites the work before it.
     */
    @Test
    @WithUser
    @DisplayName("a withdrawn delivered card is a notice without its words, and the next card still starts after it")
    void shouldShowAWithdrawnDeliveredCardAsANoticeAndKeepItsContinuity() {
        Feedback first = firstCard(FIRST_PREPARED_AT);
        UUID beforeTheCard = slip(9, FIRST_PREPARED_AT.minus(Duration.ofDays(1)));
        long admin = withdrawingAdmin();

        withdrawalService.setWithdrawn(workspace.getId(), first.getId(), admin, true, "About older issues");

        readInAppPage(workspace)
                .jsonPath("$[*].id")
                .isEqualTo(List.of(first.getId().toString()))
                .jsonPath("$[0].withdrawnAt")
                .exists()
                .jsonPath("$[0].body")
                .doesNotExist()
                .jsonPath("$[0].nextStep")
                .doesNotExist()
                .jsonPath("$[0].evidence.length()")
                .isEqualTo(0)
                .jsonPath("$[0].closedBy")
                .doesNotExist();
        JsonNode mentor = OBJECT_MAPPER.readTree(mentorContext());
        // The developer has no other feedback, so no words at all reach the mentor.
        assertThat(mentor.path("deliveredFeedback").size()).isZero();
        JsonNode state = mentor.path("feedbackStates").get(0);
        assertThat(state.path("feedbackId").asString()).isEqualTo(first.getId().toString());
        assertThat(state.path("withdrawn").asBoolean()).isTrue();
        assertThat(state.path("evidenceCurrentness").asString()).isEqualTo("CURRENT");
        assertThat(cited(first)).isNotEmpty();
        assertThat(state(first)).isEqualTo(FeedbackDeliveryState.DELIVERED);

        Feedback second = secondReview();

        assertThat(state(first)).isEqualTo(FeedbackDeliveryState.DELIVERED);
        assertThat(second.getReplacesId()).isNull();
        assertThat(cited(second)).isNotEmpty().doesNotContain(beforeTheCard);
    }

    /**
     * A card nobody saw is withdrawn before it is read: it never reaches the page and is never marked delivered.
     * Restoring it puts it through the ordinary read, which shows and delivers it then.
     */
    @Test
    @WithUser
    @DisplayName("a withdrawn prepared card is neither shown nor delivered until it is restored")
    void shouldNeitherShowNorDeliverAWithdrawnPreparedCardUntilRestored() {
        Feedback waiting = card(NOW.minus(Duration.ofDays(2)), 10, FeedbackDeliveryState.PREPARED);
        Feedback other = card(NOW.minus(Duration.ofDays(1)), 11, FeedbackDeliveryState.PREPARED);
        long admin = withdrawingAdmin();

        withdrawalService.setWithdrawn(workspace.getId(), waiting.getId(), admin, true, "Wrong words");

        readInAppPage(workspace)
                .jsonPath("$[*].id")
                .isEqualTo(List.of(other.getId().toString()));
        assertThat(state(waiting)).isEqualTo(FeedbackDeliveryState.PREPARED);
        assertThat(state(other)).isEqualTo(FeedbackDeliveryState.DELIVERED);

        withdrawalService.setWithdrawn(workspace.getId(), waiting.getId(), admin, false, "It was right");

        readInAppPage(workspace)
                .jsonPath("$[?(@.id == '%s')].body".formatted(waiting.getId()))
                .isNotEmpty()
                .jsonPath("$[?(@.id == '%s')].withdrawnAt".formatted(waiting.getId()))
                .isEmpty();
        assertThat(state(waiting)).isEqualTo(FeedbackDeliveryState.DELIVERED);
    }

    /**
     * The boundary a composing run crosses: it reads the previous card as open, and only later, in its own
     * transaction, retires it. A withdrawal committed in between must win, or the card would end superseded, off
     * the page, and past any restore.
     */
    @Test
    @WithUser
    @DisplayName("a card withdrawn after a run read it as open is not retired, and a restore brings it back")
    void shouldNotRetireACardWithdrawnAfterARunReadItAsOpen() {
        Feedback first = firstCard(FIRST_PREPARED_AT);
        PreviousInAppFeedback.Previous stale = previousInAppFeedback
                .find(workspace.getId(), developer.getId(), practice.getSlug(), NOW)
                .orElseThrow();
        assertThat(stale.isOpen()).isTrue();
        long admin = withdrawingAdmin();
        withdrawalService.setWithdrawn(workspace.getId(), first.getId(), admin, true, "About older issues");

        FeedbackSupersession.Outcome outcome =
                transactionTemplate.execute(status -> supersession.replaceOpen(workspace.getId(), stale.id()));

        assertThat(outcome.retiredSomething()).isFalse();
        assertThat(outcome.replacesId()).isNull();
        assertThat(state(first)).isEqualTo(FeedbackDeliveryState.DELIVERED);
        assertThat(withdrawalRepository.findActive(workspace.getId(), first.getId()))
                .isPresent();

        withdrawalService.setWithdrawn(workspace.getId(), first.getId(), admin, false, "It was right");

        readInAppPage(workspace)
                .jsonPath("$[?(@.id == '%s')].body".formatted(first.getId()))
                .isNotEmpty()
                .jsonPath("$[?(@.id == '%s')].withdrawnAt".formatted(first.getId()))
                .isEmpty();
    }

    /** The other order: a card a newer one already retired is no longer on the page, so there is nothing to take off. */
    @Test
    @WithUser
    @DisplayName("a card a newer run already retired cannot be withdrawn")
    void shouldRefuseToWithdrawACardANewerRunAlreadyRetired() {
        Feedback first = firstCard(FIRST_PREPARED_AT);
        assertThat(transactionTemplate
                        .execute(status -> supersession.replaceOpen(workspace.getId(), first.getId()))
                        .retiredSomething())
                .isTrue();
        long admin = withdrawingAdmin();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> withdrawalService.setWithdrawn(workspace.getId(), first.getId(), admin, true, "Too late"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(state(first)).isEqualTo(FeedbackDeliveryState.SUPERSEDED);
        assertThat(withdrawalRepository.findHistory(workspace.getId(), first.getId()))
                .isEmpty();
    }

    /**
     * The notice is news as of the withdrawal: an older card withdrawn today ranks by today, not behind every card
     * written since, while the other cards keep their newest-first order and the page keeps its limit.
     */
    @Test
    @WithUser
    @DisplayName("an older card withdrawn today is on a full page, first, as a notice")
    void shouldListAnOlderCardWithdrawnTodayAheadOfAFullPageOfNewerCards() {
        Feedback older = firstCard(NOW.minus(Duration.ofDays(25)));
        List<String> newer = new java.util.ArrayList<>();
        for (int i = 0; i < InAppFeedbackService.MAX_CARDS; i++) {
            newer.add(card(NOW.minus(Duration.ofDays(10)).plusSeconds(i), 100 + i)
                    .getId()
                    .toString());
        }
        long admin = withdrawingAdmin();
        withdrawalService.setWithdrawn(workspace.getId(), older.getId(), admin, true, "About older issues");

        readInAppPage(workspace)
                .jsonPath("$.length()")
                .isEqualTo(InAppFeedbackService.MAX_CARDS)
                .jsonPath("$[0].id")
                .isEqualTo(older.getId().toString())
                .jsonPath("$[0].withdrawnAt")
                .exists()
                .jsonPath("$[0].body")
                .doesNotExist()
                .jsonPath("$[0].nextStep")
                .doesNotExist()
                // The rest newest first; the oldest of the newer cards is the one the limit leaves out.
                .jsonPath("$[1:].id")
                .isEqualTo(new java.util.ArrayList<>(newer.reversed().subList(0, InAppFeedbackService.MAX_CARDS - 1)));
    }

    private long withdrawingAdmin() {
        return Objects.requireNonNull(persistInstanceAdmin("Withdrawing admin").getId());
    }

    /** The mentor's delivered-feedback context for the developer, as a conversation turn stages it. */
    private byte[] mentorContext() {
        Map<String, byte[]> files = new HashMap<>();
        deliveredFeedback.contribute(
                new ContextRequest.MentorChatRequest(workspace.getId(), developer.getId(), UUID.randomUUID()), files);
        return Objects.requireNonNull(files.get(DeliveredFeedbackContentSource.OUTPUT_KEY));
    }

    /** The first card about the practice, delivered, written from a slip on #10 an hour before it was prepared. */
    private Feedback firstCard(Instant preparedAt) {
        return card(preparedAt, 10);
    }

    private Feedback card(Instant preparedAt, int number) {
        return card(preparedAt, number, FeedbackDeliveryState.DELIVERED);
    }

    private Feedback card(Instant preparedAt, int number, FeedbackDeliveryState state) {
        Instant reviewedAt = preparedAt.minus(Duration.ofHours(1));
        AgentJob run = persistPullRequestReview(workspace, number, reviewedAt);
        UUID problem = observe(practice, run, number, developer, OMISSION_GAP, Severity.MAJOR, reviewedAt);
        Feedback feedback = persistInAppFeedback(
                run,
                developer,
                FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE,
                state,
                InAppFeedbackBody.render(
                        "Your diffs keep growing",
                        "Two pull requests in a row went past the size that reviews well.",
                        "Split the next one."),
                preparedAt);
        bind(feedback, problem);
        return feedback;
    }

    /**
     * The second review, after the cooldown: the lapse on #11 and #12, and the review of #12 composed a card
     * about it, which the in-app lane prepares. Returns the newest readable card about the practice.
     */
    private Feedback secondReview() {
        slip(11, NOW.minus(Duration.ofDays(3)));
        AgentJob run = persistPullRequestReview(workspace, 12, NOW);
        UUID slip = observe(
                practice,
                run,
                12,
                developer,
                OMISSION_GAP,
                Severity.MAJOR,
                NOW.minus(Duration.ofHours(1)),
                admittedEvidence(run));
        run.setOutput(OBJECT_MAPPER.readTree("""
                {"feedback":{
                  "observations":[{"id":"%s","practiceSlug":"%s"}],
                  "units":[{"channel":"IN_APP","action":"NEW","practiceSlug":"%s","basedOn":["%s"],
                   "title":"Your diffs are still growing",
                   "body":"Two more pull requests went past the size that reviews well.",
                   "nextStep":"Split the next one."}]}}
                """.formatted(slip, practice.getSlug(), practice.getSlug(), slip)));
        agentJobRepository.save(run);

        // The listener's own transaction is a proxy's; the lane built here runs in one the test opens.
        transactionTemplate.executeWithoutResult(status -> inAppLane.prepare(run.getId(), workspace.getId()));

        return feedbackRepository
                .findReadableInAppForPractice(
                        workspace.getId(), developer.getId(), practice.getSlug(), PageRequest.of(0, 1))
                .getFirst();
    }

    /** The lapse again on {@code #number}, reviewed at {@code at}, as evidence a new card may cite. */
    private UUID slip(int number, Instant at) {
        AgentJob run = persistPullRequestReview(workspace, number, at);
        return observe(practice, run, number, developer, OMISSION_GAP, Severity.MAJOR, at, admittedEvidence(run));
    }

    /** The observations the card was written from, as its rows bind them. */
    private List<UUID> cited(Feedback feedback) {
        return feedbackObservationRepository.findBoundObservations(workspace.getId(), feedback.getId()).stream()
                .map(FeedbackObservationRepository.BoundObservation::getObservationId)
                .toList();
    }

    /** Evidence a new card may cite: the citation carries the verdict admission records for the run. */
    private static String admittedEvidence(AgentJob run) {
        return AdmittedObservationFixtures.evidence(
                        run.getId(), "scm.pull-request.diff", "inputs/context/diff.patch", "src/Main.java", "example")
                .toString();
    }

    private FeedbackDeliveryState state(Feedback feedback) {
        return feedbackRepository
                .findByIdAndWorkspaceId(feedback.getId(), workspace.getId())
                .orElseThrow()
                .getDeliveryState();
    }
}
