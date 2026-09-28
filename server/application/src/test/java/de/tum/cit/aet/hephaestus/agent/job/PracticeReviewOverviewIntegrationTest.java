package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.core.time.TimeBucketSize;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewPracticeGroupDTO;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

class PracticeReviewOverviewIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String FROM = "2026-03-10T00:00:00Z";
    private static final String TO = "2026-03-13T00:00:00Z";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeGroupRepository groupRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private ObservationInvalidationRepository invalidationRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Workspace workspace;
    private Workspace otherWorkspace;
    private User subject;
    private int nextPosition;

    @BeforeEach
    void setUpWorkspaces() {
        workspace = createWorkspace(
                "review-overview",
                "Review overview",
                "review-overview-org",
                AccountType.ORG,
                persistUser("review-overview-owner"));
        ensureAdminMembership(workspace);
        ensureWorkspaceMembership(workspace, persistUser("testuser"), WorkspaceMembership.WorkspaceRole.MEMBER);
        subject = persistUser("review-overview-subject");
        ensureWorkspaceMembership(workspace, subject, WorkspaceMembership.WorkspaceRole.MEMBER);

        otherWorkspace = createWorkspace(
                "other-review-overview",
                "Other review overview",
                "other-review-overview-org",
                AccountType.ORG,
                persistUser("other-review-overview-owner"));
    }

    @Test
    @WithAdminUser
    void shouldCountReviewsObservationsAndFeedbackInTheRangeWhenSeededAroundIt() {
        PracticeGroup group = persistGroup(workspace, "collaboration", "Collaboration");
        Practice described = persistPractice(workspace, group, "pr-description", "PR description");
        Practice tested = persistPractice(workspace, null, "tests", "Tests");
        Practice older = persistPractice(workspace, null, "older", "Older");
        persistPractice(workspace, null, "idle", "Idle");

        AgentJob review = persistReviewAt(workspace, "2026-03-10T01:00:00Z", AgentJobStatus.COMPLETED);
        persistReviewAt(workspace, "2026-03-10T05:00:00Z", AgentJobStatus.FAILED);
        persistReviewAt(workspace, "2026-03-11T00:00:00Z", AgentJobStatus.RUNNING);
        persistReviewAt(workspace, "2026-03-12T08:00:00Z", AgentJobStatus.QUEUED);
        // Outside the half-open range, another purpose, another workspace: none of them count.
        persistReviewAt(workspace, "2026-03-09T23:59:59Z", AgentJobStatus.COMPLETED);
        persistReviewAt(workspace, TO, AgentJobStatus.COMPLETED);
        AgentJob mentorTurn = persistReviewAt(workspace, "2026-03-11T00:00:00Z", AgentJobStatus.COMPLETED);
        mentorTurn.setPurpose(AgentPurpose.MENTOR);
        jobRepository.save(mentorTurn);
        AgentJob elsewhere = persistReviewAt(otherWorkspace, "2026-03-11T00:00:00Z", AgentJobStatus.COMPLETED);

        UUID problem = observe(review, described, "2026-03-10T02:00:00Z", "ABSENT", "GOOD");
        UUID strength = observe(review, described, "2026-03-11T02:00:00Z", "PRESENT", "GOOD");
        observe(review, described, "2026-03-11T03:00:00Z", "NOT_APPLICABLE", null);
        UUID undetermined = observe(review, tested, "2026-03-12T02:00:00Z", "UNDETERMINED", null);
        UUID beforeRange = observe(review, older, "2026-03-09T20:00:00Z", "ABSENT", "GOOD");
        observe(review, described, TO, "PRESENT", "GOOD");
        observe(
                elsewhere,
                persistPractice(otherWorkspace, null, "elsewhere", "Elsewhere"),
                "2026-03-11T02:00:00Z",
                "ABSENT",
                "GOOD");

        invalidate(problem);
        ObservationInvalidation restored = invalidate(strength);
        restored.restore(1L, "It was right after all", Instant.parse("2026-03-12T00:00:00Z"));
        invalidationRepository.save(restored);

        // One piece of feedback fusing two observations of one practice counts once for it; one bound to two
        // practices counts once for each.
        UUID proposed =
                persistFeedback(workspace, review, "2026-03-10T03:00:00Z", FeedbackDeliveryState.AWAITING_APPROVAL);
        bind(proposed, problem);
        bind(proposed, strength);
        UUID delivered = persistFeedback(workspace, review, "2026-03-12T03:00:00Z", FeedbackDeliveryState.DELIVERED);
        bind(delivered, strength);
        bind(delivered, undetermined);
        bind(
                persistFeedback(workspace, review, "2026-03-11T05:00:00Z", FeedbackDeliveryState.PARTIALLY_DELIVERED),
                strength);
        persistFeedback(workspace, review, "2026-03-12T04:00:00Z", FeedbackDeliveryState.PARTIALLY_FAILED);
        persistFeedback(workspace, review, "2026-03-11T04:00:00Z", FeedbackDeliveryState.DISCARDED);
        bind(persistFeedback(workspace, review, "2026-03-11T06:00:00Z", FeedbackDeliveryState.PREPARED), beforeRange);
        bind(persistFeedback(workspace, review, "2026-03-09T12:00:00Z", FeedbackDeliveryState.PREPARED), problem);
        persistFeedback(otherWorkspace, elsewhere, "2026-03-11T04:00:00Z", FeedbackDeliveryState.DELIVERED);

        PracticeReviewOverviewDTO overview = overview(FROM, TO, "UTC");

        assertThat(overview.from()).isEqualTo(Instant.parse(FROM));
        assertThat(overview.to()).isEqualTo(Instant.parse(TO));
        assertThat(overview.bucket()).isEqualTo(TimeBucketSize.DAY);
        assertThat(nonZero(overview.reviews()))
                .isEqualTo(Map.of("queued", 1L, "running", 1L, "completed", 1L, "failed", 1L));
        assertThat(nonZero(overview.observations()))
                .isEqualTo(Map.of("strengths", 1L, "problems", 1L, "notApplicable", 1L, "undetermined", 1L));
        assertThat(overview.observationsInvalidated()).isEqualTo(1L);
        assertThat(nonZero(overview.feedback()))
                .isEqualTo(Map.of(
                        "awaitingApproval",
                        1L,
                        "prepared",
                        1L,
                        "partiallyDelivered",
                        1L,
                        "partiallyFailed",
                        1L,
                        "delivered",
                        1L,
                        "discarded",
                        1L));
        assertThat(overview.buckets())
                .extracting(
                        PracticeReviewBucketDTO::start,
                        PracticeReviewBucketDTO::reviews,
                        PracticeReviewBucketDTO::observations,
                        PracticeReviewBucketDTO::feedback)
                .containsExactly(
                        tuple(Instant.parse("2026-03-10T00:00:00Z"), 2L, 1L, 1L),
                        tuple(Instant.parse("2026-03-11T00:00:00Z"), 1L, 2L, 3L),
                        tuple(Instant.parse("2026-03-12T00:00:00Z"), 1L, 1L, 2L));

        assertThat(overview.practices())
                .as("the most observed first; a practice with feedback on an older observation still has a row")
                .extracting(PracticeReviewCountsDTO::practiceSlug)
                .containsExactly("pr-description", "tests", "older");
        PracticeReviewCountsDTO describedCounts = overview.practices().get(0);
        assertThat(describedCounts.group())
                .isEqualTo(new ReviewPracticeGroupDTO("collaboration", "Collaboration", null, null));
        assertThat(nonZero(describedCounts.observations()))
                .isEqualTo(Map.of("strengths", 1L, "problems", 1L, "notApplicable", 1L));
        assertThat(describedCounts.observationsInvalidated()).isEqualTo(1L);
        assertThat(nonZero(describedCounts.feedback()))
                .isEqualTo(Map.of("awaitingApproval", 1L, "partiallyDelivered", 1L, "delivered", 1L));
        PracticeReviewCountsDTO testedCounts = overview.practices().get(1);
        assertThat(testedCounts.group()).isNull();
        assertThat(nonZero(testedCounts.observations())).isEqualTo(Map.of("undetermined", 1L));
        assertThat(testedCounts.observationsInvalidated()).isZero();
        assertThat(nonZero(testedCounts.feedback())).isEqualTo(Map.of("delivered", 1L));
        PracticeReviewCountsDTO olderCounts = overview.practices().get(2);
        assertThat(nonZero(olderCounts.observations())).isEmpty();
        assertThat(nonZero(olderCounts.feedback())).isEqualTo(Map.of("prepared", 1L));
    }

    /** Each feedback count opens the feedback list filtered to one delivery state, so no state may go uncounted. */
    @Test
    @WithAdminUser
    void shouldCountEveryDeliveryStateOnceWhenEachHasOnePieceOfFeedback() {
        AgentJob review = persistReviewAt(workspace, "2026-03-10T01:00:00Z", AgentJobStatus.COMPLETED);
        Map<String, Long> expected = new HashMap<>();
        for (FeedbackDeliveryState state : FeedbackDeliveryState.values()) {
            persistFeedback(workspace, review, "2026-03-11T00:00:00Z", state);
            expected.put(camelCase(state.name()), 1L);
        }

        assertThat(nonZero(overview(FROM, TO, "UTC").feedback())).isEqualTo(expected);
    }

    private static String camelCase(String constant) {
        String[] words = constant.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            result.append(words[i].substring(0, 1).toUpperCase(Locale.ROOT)).append(words[i].substring(1));
        }
        return result.toString();
    }

    @Test
    @WithAdminUser
    void shouldStartBucketsAtMidnightInTheZoneWhenAZoneIsGiven() {
        persistReviewAt(workspace, "2026-03-10T23:30:00Z", AgentJobStatus.COMPLETED);

        PracticeReviewOverviewDTO overview = overview(FROM, TO, "Europe/Berlin");

        assertThat(overview.buckets())
                .extracting(PracticeReviewBucketDTO::start, PracticeReviewBucketDTO::reviews)
                .containsExactly(
                        tuple(Instant.parse("2026-03-09T23:00:00Z"), 0L),
                        tuple(Instant.parse("2026-03-10T23:00:00Z"), 1L),
                        tuple(Instant.parse("2026-03-11T23:00:00Z"), 0L),
                        tuple(Instant.parse("2026-03-12T23:00:00Z"), 0L));
        assertThat(overview.practices()).isEmpty();
    }

    @Test
    @WithUser
    void shouldRejectTheOverviewWhenTheCallerIsAMember() {
        webTestClient
                .get()
                .uri("/workspaces/{slug}/practices/reviews/overview", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void shouldRejectTheOverviewWhenTheZoneIsUnknown() {
        webTestClient
                .get()
                .uri(
                        "/workspaces/{slug}/practices/reviews/overview?zone={zone}",
                        workspace.getWorkspaceSlug(),
                        "Mars/Olympus_Mons")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("Unknown time zone: Mars/Olympus_Mons");
    }

    /** The counts of a counts record that are not zero, by name, so an assertion names every count it expects. */
    private static Map<String, Long> nonZero(Record counts) {
        Map<String, Long> result = new HashMap<>();
        for (RecordComponent component : counts.getClass().getRecordComponents()) {
            try {
                if (component.getAccessor().invoke(counts) instanceof Long count && count != 0) {
                    result.put(component.getName(), count);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return result;
    }

    private PracticeReviewOverviewDTO overview(String from, String to, String zone) {
        return Objects.requireNonNull(webTestClient
                .get()
                .uri(
                        "/workspaces/{slug}/practices/reviews/overview?from={from}&to={to}&zone={zone}",
                        workspace.getWorkspaceSlug(),
                        from,
                        to,
                        zone)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PracticeReviewOverviewDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private PracticeGroup persistGroup(Workspace targetWorkspace, String slug, String name) {
        PracticeGroup result = new PracticeGroup();
        result.setWorkspace(targetWorkspace);
        result.setSlug(slug);
        result.setName(name);
        return groupRepository.save(result);
    }

    private Practice persistPractice(
            Workspace targetWorkspace, @Nullable PracticeGroup group, String slug, String name) {
        Practice result = new Practice();
        result.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        result.setWorkspace(targetWorkspace);
        result.setSlug(slug);
        result.setName(name);
        result.setGroup(group);
        result.setCriteria("Review the change");
        result.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        result.setAutonomy(PracticeAutonomy.AUTOMATIC);
        return practiceRepository.save(result);
    }

    /** {@code createdAt} is only defaulted when unset, so a review can be seeded at a chosen instant. */
    private AgentJob persistReviewAt(Workspace targetWorkspace, String createdAt, AgentJobStatus status) {
        AgentJob result = new AgentJob();
        result.setWorkspace(targetWorkspace);
        result.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        result.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        result.setConfigSnapshot(objectMapper.valueToTree(Map.of("model", "test")));
        result.setCreatedAt(Instant.parse(createdAt));
        result.setStatus(status);
        return jobRepository.save(result);
    }

    /** An assessed observation when {@code assessment} is given, otherwise one with that assessment status. */
    private UUID observe(
            AgentJob job, Practice practice, String observedAt, String presence, @Nullable String assessment) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                "overview-" + id,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                null,
                ArtifactKinds.PULL_REQUEST.value(),
                7L,
                subject.getId(),
                "Observed " + id,
                assessment == null ? presence : "ASSESSED",
                assessment == null ? null : presence,
                assessment,
                assessment == null ? null : "MINOR",
                "{}",
                "Reasoning",
                "overview-" + id,
                Instant.parse(observedAt),
                "LIVE");
        return id;
    }

    private ObservationInvalidation invalidate(UUID observationId) {
        return invalidationRepository.save(new ObservationInvalidation(
                observationRepository
                        .findByIdAndWorkspaceId(observationId, workspace.getId())
                        .orElseThrow(),
                1L,
                "Wrong when made",
                Instant.parse("2026-03-11T12:00:00Z")));
    }

    private UUID persistFeedback(
            Workspace targetWorkspace, AgentJob job, String createdAt, FeedbackDeliveryState state) {
        return feedbackRepository
                .save(Feedback.builder()
                        .agentJobId(job.getId())
                        .workspaceId(targetWorkspace.getId())
                        .artifactKind(ArtifactKinds.PULL_REQUEST)
                        .artifactId(7L)
                        .recipientUserId(subject.getId())
                        .aboutUserId(subject.getId())
                        .channel(FeedbackChannel.IN_CONTEXT)
                        .position(nextPosition++)
                        .deliveryState(state)
                        .body("Feedback")
                        .source(FeedbackSource.AGENT)
                        .createdAt(Instant.parse(createdAt))
                        .deliveredAt(state == FeedbackDeliveryState.DELIVERED ? Instant.parse(createdAt) : null)
                        .build())
                .getId();
    }

    private void bind(UUID feedbackId, UUID observationId) {
        feedbackObservationRepository.insertIfAbsent(feedbackId, observationId, "PRIMARY", 0);
    }
}
