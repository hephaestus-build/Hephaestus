package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

class PracticeReviewSummaryControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Workspace workspace;
    private Workspace otherWorkspace;
    private Practice practice;
    private User subject;
    private AgentJob job;

    @BeforeEach
    void setUpWorkspaces() {
        workspace = createWorkspace(
                "review-summary",
                "Review summary",
                "review-summary-org",
                AccountType.ORG,
                persistUser("review-summary-owner"));
        ensureAdminMembership(workspace);
        ensureWorkspaceMembership(workspace, persistUser("testuser"), WorkspaceMembership.WorkspaceRole.MEMBER);
        subject = persistUser("review-summary-subject");
        ensureWorkspaceMembership(workspace, subject, WorkspaceMembership.WorkspaceRole.MEMBER);
        practice = persistPractice(workspace);
        job = persistJob(workspace, AgentPurpose.PRACTICE_REVIEW);

        otherWorkspace = createWorkspace(
                "other-review-summary",
                "Other review summary",
                "other-review-summary-org",
                AccountType.ORG,
                persistUser("other-review-summary-owner"));
    }

    @Test
    @WithAdminUser
    void summarizesOnlyCompletedPracticeReviewsFromTheRequestedWorkspace() {
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setIntegrationKind(IntegrationKind.GITHUB);
        job.setMetadata(objectMapper.valueToTree(Map.of(
                "pull_request_id",
                7,
                "pr_number",
                42,
                "title",
                "Make review output visible",
                "repository_full_name",
                "review-summary-org/review-ui",
                "pr_url",
                "https://github.com/review-summary-org/review-ui/pull/42")));
        jobRepository.save(job);
        persistJob(workspace, AgentPurpose.PRACTICE_REVIEW);
        AgentJob mentorJob = persistJob(workspace, AgentPurpose.MENTOR);
        mentorJob.setStatus(AgentJobStatus.COMPLETED);
        jobRepository.save(mentorJob);
        AgentJob otherJob = persistJob(otherWorkspace, AgentPurpose.PRACTICE_REVIEW);
        otherJob.setStatus(AgentJobStatus.COMPLETED);
        jobRepository.save(otherJob);

        insertObservation("Problem", "ABSENT", "BAD", "MAJOR");
        insertObservation("Strength", "PRESENT", "GOOD", "INFO");
        insertObservation("Not applicable", "NOT_APPLICABLE", null, null);
        persistFeedback(0, FeedbackDeliveryState.DELIVERED, null, "Delivered");
        persistFeedback(1, FeedbackDeliveryState.SUPPRESSED, FeedbackSuppressionReason.VOLUME_CAPPED, "Withheld");
        persistFeedback(2, FeedbackDeliveryState.PREPARED, null, null);
        persistFeedback(3, FeedbackDeliveryState.SUPERSEDED, null, "Replaced");
        persistFeedback(4, FeedbackDeliveryState.FAILED, null, "Failed");

        webTestClient
                .get()
                .uri("/workspaces/{slug}/practices/reviews?status=COMPLETED&size=1", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.page.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].id")
                .isEqualTo(job.getId().toString())
                .jsonPath("$.content[0].target.type")
                .isEqualTo("scm.pull_request")
                .jsonPath("$.content[0].target.id")
                .isEqualTo(7)
                .jsonPath("$.content[0].target.provider")
                .isEqualTo("GITHUB")
                .jsonPath("$.content[0].target.number")
                .isEqualTo(42)
                .jsonPath("$.content[0].target.title")
                .isEqualTo("Make review output visible")
                .jsonPath("$.content[0].target.repositoryName")
                .isEqualTo("review-summary-org/review-ui")
                .jsonPath("$.content[0].target.url")
                .isEqualTo("https://github.com/review-summary-org/review-ui/pull/42")
                .jsonPath("$.content[0].observations.strengths")
                .isEqualTo(1)
                .jsonPath("$.content[0].observations.problems")
                .isEqualTo(1)
                .jsonPath("$.content[0].observations.notApplicable")
                .isEqualTo(1)
                .jsonPath("$.content[0].feedback.prepared")
                .isEqualTo(1)
                .jsonPath("$.content[0].feedback.delivered")
                .isEqualTo(1)
                .jsonPath("$.content[0].feedback.superseded")
                .isEqualTo(1)
                .jsonPath("$.content[0].feedback.suppressed")
                .isEqualTo(1)
                .jsonPath("$.content[0].feedback.failed")
                .isEqualTo(1);
    }

    @Test
    @WithAdminUser
    void reportsHowEachPracticesEvidenceRequirementsTurnedOut() {
        AgentJob skipped = persistJob(workspace, AgentPurpose.PRACTICE_REVIEW);
        skipped.setReviewReadiness(readinessSnapshot(false));
        jobRepository.save(skipped);
        AgentJob reviewed = persistJob(workspace, AgentPurpose.PRACTICE_REVIEW);
        reviewed.setReviewReadiness(readinessSnapshot(true));
        jobRepository.save(reviewed);
        // A review in another workspace must not be counted into this workspace's history.
        AgentJob elsewhere = persistJob(otherWorkspace, AgentPurpose.PRACTICE_REVIEW);
        elsewhere.setReviewReadiness(readinessSnapshot(false));
        jobRepository.save(elsewhere);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/practices/reviews/evidence-outcomes", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].practiceSlug")
                .isEqualTo(practice.getSlug())
                .jsonPath("$[0].consideredReviews")
                .isEqualTo(2)
                .jsonPath("$[0].reviewedCount")
                .isEqualTo(1)
                .jsonPath("$[0].blockersObserved[0].sourceKind")
                .isEqualTo("scm.pull-request.diff")
                .jsonPath("$[0].blockersObserved[0].reasonCode")
                .isEqualTo("SOURCE_EMPTY")
                .jsonPath("$[0].blockersObserved[0].reviewsAffected")
                .isEqualTo(1);
    }

    /** One readiness report as the executor records it, for one practice, ready or skipped. */
    private tools.jackson.databind.JsonNode readinessSnapshot(boolean ready) {
        Map<String, Object> check = Map.of(
                "sourceKind",
                "scm.pull-request.diff",
                "meetsRequirements",
                ready,
                "reasonCodes",
                ready ? List.of() : List.of("SOURCE_EMPTY"));
        return objectMapper.valueToTree(Map.of(
                "decisions",
                List.of(Map.of(
                        "practiceSlug",
                        practice.getSlug(),
                        "ready",
                        ready,
                        "reasonCodes",
                        List.of(),
                        "sourceChecks",
                        List.of(check)))));
    }

    /**
     * The window intersects with {@code status} rather than replacing it, and is half-open like the
     * sibling observation and feedback listings: a review created exactly at {@code from} is in, one
     * created exactly at {@code to} is out.
     */
    @Test
    @WithAdminUser
    void narrowsReviewsToTheRequestedWindowAndIntersectsWithStatus() {
        Instant from = Instant.parse("2026-03-10T00:00:00Z");
        Instant to = Instant.parse("2026-03-12T00:00:00Z");
        jobRepository.delete(job);
        AgentJob justBefore = persistReviewAt(workspace, from.minusSeconds(1), AgentJobStatus.COMPLETED);
        AgentJob onLowerBound = persistReviewAt(workspace, from, AgentJobStatus.COMPLETED);
        AgentJob insideButFailed = persistReviewAt(workspace, from.plusSeconds(3600), AgentJobStatus.FAILED);
        AgentJob onUpperBound = persistReviewAt(workspace, to, AgentJobStatus.COMPLETED);
        // Same window, another tenant: the workspace predicate must survive the new filters.
        persistReviewAt(otherWorkspace, from.plusSeconds(60), AgentJobStatus.COMPLETED);

        listReviews("").jsonPath("$.page.totalElements").isEqualTo(4);

        listReviews("?from=2026-03-10T00:00:00Z&to=2026-03-12T00:00:00Z")
                .jsonPath("$.page.totalElements")
                .isEqualTo(2)
                .jsonPath("$.content[0].id")
                .isEqualTo(insideButFailed.getId().toString())
                .jsonPath("$.content[1].id")
                .isEqualTo(onLowerBound.getId().toString());

        listReviews("?status=COMPLETED&from=2026-03-10T00:00:00Z&to=2026-03-12T00:00:00Z")
                .jsonPath("$.page.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].id")
                .isEqualTo(onLowerBound.getId().toString());

        listReviews("?from=2026-03-10T00:00:00Z")
                .jsonPath("$.page.totalElements")
                .isEqualTo(3)
                .jsonPath("$.content[0].id")
                .isEqualTo(onUpperBound.getId().toString())
                .jsonPath("$.content[2].id")
                .isEqualTo(onLowerBound.getId().toString());

        listReviews("?to=2026-03-12T00:00:00Z")
                .jsonPath("$.page.totalElements")
                .isEqualTo(3)
                .jsonPath("$.content[0].id")
                .isEqualTo(insideButFailed.getId().toString())
                .jsonPath("$.content[2].id")
                .isEqualTo(justBefore.getId().toString());
    }

    /** A backwards window is a mistake, not an empty page — the siblings answer it the same way. */
    @Test
    @WithAdminUser
    void rejectsAWindowThatEndsBeforeItStarts() {
        webTestClient
                .get()
                .uri(
                        "/workspaces/{slug}/practices/reviews?from=2026-03-12T00:00:00Z&to=2026-03-10T00:00:00Z",
                        workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(400)
                .jsonPath("$.detail")
                .isEqualTo("from must not be after to");
    }

    private WebTestClient.BodyContentSpec listReviews(String query) {
        return webTestClient
                .get()
                .uri("/workspaces/{slug}/practices/reviews" + query, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    /** {@code createdAt} is only defaulted when unset, so a review can be seeded at a chosen instant. */
    private AgentJob persistReviewAt(Workspace targetWorkspace, Instant createdAt, AgentJobStatus status) {
        AgentJob result = new AgentJob();
        result.setWorkspace(targetWorkspace);
        result.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        result.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        result.setConfigSnapshot(objectMapper.valueToTree(Map.of("model", "test")));
        result.setCreatedAt(createdAt);
        result.setStatus(status);
        return jobRepository.save(result);
    }

    @Test
    @WithUser
    void rejectsAWorkspaceMember() {
        webTestClient
                .get()
                .uri("/workspaces/{slug}/practices/reviews", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    private Practice persistPractice(Workspace targetWorkspace) {
        Practice result = new Practice();
        result.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        result.setWorkspace(targetWorkspace);
        result.setSlug("review-quality");
        result.setName("Review quality");
        result.setCriteria("Review the change");
        result.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        result.setAutonomy(PracticeAutonomy.AUTOMATIC);
        return practiceRepository.save(result);
    }

    private AgentJob persistJob(Workspace targetWorkspace, AgentPurpose purpose) {
        AgentJob result = new AgentJob();
        result.setWorkspace(targetWorkspace);
        result.setPurpose(purpose);
        result.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        result.setConfigSnapshot(objectMapper.valueToTree(Map.of("model", "test")));
        return jobRepository.save(result);
    }

    private void insertObservation(
            String title, String presence, @Nullable String assessment, @Nullable String severity) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                "summary-" + id,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                null,
                ArtifactKinds.PULL_REQUEST.value(),
                7L,
                subject.getId(),
                title,
                presence,
                assessment,
                severity,
                "{}",
                "Reasoning",
                "summary-" + title,
                Instant.now(),
                "LIVE");
    }

    private void persistFeedback(
            int position,
            FeedbackDeliveryState state,
            @Nullable FeedbackSuppressionReason reason,
            @Nullable String body) {
        feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(7L)
                .recipientUserId(subject.getId())
                .aboutUserId(subject.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(position)
                .deliveryState(state)
                .suppressionReason(reason)
                .body(body)
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .deliveredAt(
                        state == FeedbackDeliveryState.DELIVERED || state == FeedbackDeliveryState.SUPERSEDED
                                ? Instant.now()
                                : null)
                .build());
    }
}
