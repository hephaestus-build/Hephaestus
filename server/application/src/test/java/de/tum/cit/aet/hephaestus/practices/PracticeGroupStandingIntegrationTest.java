package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

class PracticeGroupStandingIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String STANDINGS_URI = "/workspaces/{workspaceSlug}/practice-groups/standings";

    private static final String DIFF_EVIDENCE_JSON =
            "{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\",\"artifactPath\":\"inputs/context/diff.patch\","
                    + "\"path\":\"src/Main.java\",\"side\":\"NEW\",\"startLine\":42,\"endLine\":42,\"quote\":\"example\","
                    + "\"quoteRedacted\":false}]}";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PracticeGroupRepository groupRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    private Workspace workspace;
    private PracticeGroup group;
    private Practice practice;
    private AgentJob agentJob;
    private User developer;

    @BeforeEach
    void setUpWorkspace() {
        User owner = persistUser("group-standing-owner");
        workspace =
                createWorkspace("group-standing-ws", "Group Standing WS", "group-standing-org", AccountType.ORG, owner);

        developer = persistUser("testuser");
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);

        group = persistGroup(workspace, "code-quality", "Code Quality");
        practice = persistPractice(workspace, group, "pr-description-quality", "PR Description Quality");
        agentJob = persistAgentJob(workspace);
    }

    private PracticeGroup persistGroup(Workspace ws, String slug, String name) {
        PracticeGroup a = new PracticeGroup();
        a.setWorkspace(ws);
        a.setSlug(slug);
        a.setName(name);
        return groupRepository.save(a);
    }

    private Practice persistPractice(Workspace ws, PracticeGroup boundGroup, String slug, String name) {
        Practice p = new Practice();
        p.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        p.setWorkspace(ws);
        p.setSlug(slug);
        p.setName(name);
        p.setCriteria("Description for " + slug);
        p.setGroup(boundGroup);
        p = practiceRepository.saveAndFlush(p);
        p.setCurrentRevision(practiceRevisionRepository.save(new PracticeRevision(p, 1)));
        return practiceRepository.saveAndFlush(p);
    }

    private void persistStrengthPractice(String slug, String name, long artifactId) {
        Practice target = persistPractice(workspace, group, slug, name);
        insertObservation(agentJob, target, developer, "Strength in " + name, "PRESENT", null, artifactId);
    }

    private void persistDevelopingPractice(String slug, String name, long artifactId) {
        Practice target = persistPractice(workspace, group, slug, name);
        insertObservation(agentJob, target, developer, "Gap in " + name, "ABSENT", "MAJOR", artifactId);
    }

    private void persistMixedPractice(String slug, String name, long artifactId) {
        Practice target = persistPractice(workspace, group, slug, name);
        insertObservation(agentJob, target, developer, "Strength in " + name, "PRESENT", null, artifactId);
        insertObservation(agentJob, target, developer, "Gap in " + name, "ABSENT", "MAJOR", artifactId);
    }

    private AgentJob persistAgentJob(Workspace ws) {
        AgentJob job = new AgentJob();
        job.setWorkspace(ws);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        job.setEvidenceSnapshot(OBJECT_MAPPER.valueToTree(Map.of("manifest", Map.of("contractVersion", "1.0.0"))));
        return agentJobRepository.save(job);
    }

    private UUID insertObservation(
            AgentJob job,
            Practice targetPractice,
            User user,
            String title,
            String presence,
            @Nullable String severity,
            Long artifactId) {
        return insertObservation(job, targetPractice, user, title, presence, severity, artifactId, Instant.now());
    }

    private UUID insertObservation(
            AgentJob job,
            Practice targetPractice,
            User user,
            String title,
            String presence,
            @Nullable String severity,
            Long artifactId,
            Instant observedAt) {
        return insertObservation(
                job,
                targetPractice,
                user,
                title,
                presence,
                severity,
                artifactId,
                observedAt,
                ArtifactKinds.PULL_REQUEST.value());
    }

    private UUID insertObservation(
            AgentJob job,
            Practice targetPractice,
            User user,
            String title,
            String presence,
            @Nullable String severity,
            Long artifactId,
            Instant observedAt,
            String artifactKind) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                "key-" + id,
                job.getId(),
                targetPractice.getWorkspace().getId(),
                targetPractice.getId(),
                targetPractice.getCurrentRevision().getId(),
                artifactKind,
                artifactId,
                user.getId(),
                title,
                presence,
                "PRESENT".equals(presence) ? "GOOD" : "BAD",
                severity,
                DIFF_EVIDENCE_JSON,
                "Test reasoning for " + title,
                null,
                observedAt,
                "LIVE");
        return id;
    }

    private void insertInapplicableObservation(Practice targetPractice, String presence, Long artifactId) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                "key-" + id,
                agentJob.getId(),
                targetPractice.getWorkspace().getId(),
                targetPractice.getId(),
                targetPractice.getCurrentRevision().getId(),
                ArtifactKinds.PULL_REQUEST.value(),
                artifactId,
                developer.getId(),
                "Nothing to judge here",
                presence,
                null,
                null,
                DIFF_EVIDENCE_JSON,
                "Test reasoning for an inapplicable run",
                null,
                Instant.now(),
                "LIVE");
    }

    private void deliverFeedbackFor(UUID observationId, String body) {
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(agentJob.getId())
                .workspaceId(workspace.getId())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(42L)
                .recipientUserId(developer.getId())
                .aboutUserId(developer.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(0)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .body(body)
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .build());
        feedbackObservationRepository.insertIfAbsent(feedback.getId(), observationId, "PRIMARY", 0);
    }

    @Nested
    @DisplayName("GET /practice-groups/standings")
    class DerivedStatus {

        @Test
        @WithUser
        @DisplayName("derives DEVELOPING from a confident problem and carries the delivered feedback")
        void shouldReturnDevelopingWithEvidence() {
            UUID observationId =
                    insertObservation(agentJob, practice, developer, "Missing rollout plan", "ABSENT", "MAJOR", 1L);
            deliverFeedbackFor(observationId, "Add a rollout section describing how the change ships.");

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].groupSlug")
                    .isEqualTo("code-quality")
                    .jsonPath("$[0].groupName")
                    .isEqualTo("Code Quality")
                    .jsonPath("$[0].standing")
                    .isEqualTo("DEVELOPING")
                    .jsonPath("$[0].guidance")
                    .isEqualTo(
                            "Your recent feedback points to “PR Description Quality” as the next practice to focus on.")
                    .jsonPath("$[0].guidanceSource")
                    .isEqualTo("RULE_BASED")
                    .jsonPath("$[0].direction")
                    .isEqualTo("INSUFFICIENT_EVIDENCE")
                    .jsonPath("$[0].feedbackSpanDays")
                    .isEqualTo(1)
                    .jsonPath("$[0].feedbackSince")
                    .exists()
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(1)
                    .jsonPath("$[0].observations[0].title")
                    .isEqualTo("Missing rollout plan")
                    .jsonPath("$[0].observations[0].deliveredFeedback")
                    .isEqualTo("Add a rollout section describing how the change ships.")
                    .jsonPath("$[0].observations[0].observationId")
                    .isEqualTo(observationId.toString())
                    .jsonPath("$[0].sources.length()")
                    .isEqualTo(1)
                    .jsonPath("$[0].sources[0].workKind")
                    .isEqualTo(ArtifactKinds.PULL_REQUEST.value())
                    .jsonPath("$[0].sources[0].count")
                    .isEqualTo(1);
        }

        @Test
        @WithUser
        @DisplayName("counts distinct contributing artifacts per kind for the provenance line")
        void shouldCountDistinctSourceArtifactsPerKind() {
            insertObservation(agentJob, practice, developer, "Gap on PR one", "ABSENT", "MAJOR", 1L);
            insertObservation(agentJob, practice, developer, "Second gap on PR one", "ABSENT", "MINOR", 1L);
            insertObservation(agentJob, practice, developer, "Gap on PR two", "ABSENT", "MAJOR", 2L);
            insertObservation(
                    agentJob,
                    practice,
                    developer,
                    "Vague issue description",
                    "ABSENT",
                    "MINOR",
                    7L,
                    Instant.now(),
                    ArtifactKinds.ISSUE.value());

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].sources.length()")
                    .isEqualTo(2)
                    .jsonPath("$[0].sources[0].workKind")
                    .isEqualTo(ArtifactKinds.ISSUE.value())
                    .jsonPath("$[0].sources[0].count")
                    .isEqualTo(1)
                    .jsonPath("$[0].sources[1].workKind")
                    .isEqualTo(ArtifactKinds.PULL_REQUEST.value())
                    .jsonPath("$[0].sources[1].count")
                    .isEqualTo(2);
        }

        @Test
        @WithUser
        @DisplayName("derives STRENGTH when the group only has strengths")
        void shouldReturnStrengthForGoodOnly() {
            insertObservation(agentJob, practice, developer, "Clear motivation section", "PRESENT", null, 1L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("STRENGTH")
                    .jsonPath("$[0].guidance")
                    .isEqualTo(
                            "Your recent feedback shows a strength in “PR Description Quality”. Keep building on it.")
                    .jsonPath("$[0].direction")
                    .isEqualTo("INSUFFICIENT_EVIDENCE")
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(1)
                    .jsonPath("$[0].observations[0].title")
                    .isEqualTo("Clear motivation section");
        }

        @Test
        @WithUser
        @DisplayName("returns NOT_OBSERVED when the group has no observations at all")
        void shouldReturnNotObservedWithoutObservations() {
            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].groupSlug")
                    .isEqualTo("code-quality")
                    .jsonPath("$[0].groupName")
                    .isEqualTo("Code Quality")
                    .jsonPath("$[0].standing")
                    .isEqualTo("NOT_OBSERVED")
                    .jsonPath("$[0].guidance")
                    .doesNotExist()
                    .jsonPath("$[0].guidanceSource")
                    .doesNotExist()
                    .jsonPath("$[0].direction")
                    .isEqualTo("INSUFFICIENT_EVIDENCE")
                    .jsonPath("$[0].feedbackSpanDays")
                    .doesNotExist()
                    .jsonPath("$[0].feedbackSince")
                    .doesNotExist()
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(0);
        }

        @Test
        @WithUser
        @DisplayName("returns NO_OPPORTUNITY when every practice ran but produced no verdict")
        void shouldReturnNoOpportunityWhenEveryRunWasInapplicable() {
            insertInapplicableObservation(practice, "NOT_APPLICABLE", 1L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].groupSlug")
                    .isEqualTo("code-quality")
                    .jsonPath("$[0].standing")
                    .isEqualTo("NO_OPPORTUNITY")
                    .jsonPath("$[0].guidance")
                    .doesNotExist()
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(0);
        }

        @Test
        @WithUser
        @DisplayName("an INCONCLUSIVE run counts as an opportunity that produced no verdict")
        void shouldReturnNoOpportunityForInconclusiveRun() {
            insertInapplicableObservation(practice, "INCONCLUSIVE", 2L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("NO_OPPORTUNITY");
        }

        @Test
        @WithUser
        @DisplayName("an inapplicable run never displaces the verdict a real observation supports")
        void shouldPreferVerdictOverInapplicableRuns() {
            insertObservation(agentJob, practice, developer, "Coin-flip hunch", "ABSENT", "MINOR", 1L);
            insertInapplicableObservation(practice, "NOT_APPLICABLE", 2L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("DEVELOPING")
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(1);
        }

        @Test
        @WithUser
        @DisplayName("a problem seen on a single piece of reviewed work still yields a verdict, not an empty state")
        void shouldReportDevelopingForSingleArtifactProblem() {
            insertObservation(agentJob, practice, developer, "Coin-flip hunch", "ABSENT", "MINOR", 1L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("DEVELOPING")
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(1);
        }

        @Test
        @WithUser
        @DisplayName("derives MIXED across two practices and names both sides in the guidance")
        void shouldComposeMixedGuidanceAcrossPractices() {
            Practice reviewPractice =
                    persistPractice(workspace, group, "review-comments", "Actionable Review Comments");
            insertObservation(agentJob, practice, developer, "Missing rollout plan", "ABSENT", "MAJOR", 1L);
            insertObservation(agentJob, reviewPractice, developer, "Concrete line references", "PRESENT", null, 1L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("MIXED")
                    .jsonPath("$[0].guidance")
                    .isEqualTo("Your recent feedback shows a strength in “Actionable Review Comments”. "
                            + "Next, focus on “PR Description Quality”.")
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(2)
                    .jsonPath("$[0].observations[0].title")
                    .isEqualTo("Missing rollout plan")
                    .jsonPath("$[0].observations[1].title")
                    .isEqualTo("Concrete line references");
        }

        @Test
        @WithUser
        @DisplayName("reads as STRENGTH when nearly every practice in the group stands as one")
        void shouldReadAsStrengthAboveTheStrengthShare() {
            persistStrengthPractice("commit-messages", "Commit Messages", 1L);
            persistStrengthPractice("review-comments", "Actionable Review Comments", 2L);
            persistStrengthPractice("issue-descriptions", "Issue Descriptions", 3L);
            persistStrengthPractice("test-coverage", "Test Coverage", 4L);
            persistMixedPractice("documentation", "Documentation", 5L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("STRENGTH");
        }

        @Test
        @WithUser
        @DisplayName("stays MIXED when the strength share only reaches the threshold")
        void shouldStayMixedAtTheStrengthShareThreshold() {
            persistStrengthPractice("commit-messages", "Commit Messages", 1L);
            persistStrengthPractice("review-comments", "Actionable Review Comments", 2L);
            persistStrengthPractice("issue-descriptions", "Issue Descriptions", 3L);
            persistStrengthPractice("test-coverage", "Test Coverage", 4L);
            persistDevelopingPractice("documentation", "Documentation", 5L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("MIXED");
        }

        @Test
        @WithUser
        @DisplayName("reads as MIXED when every practice in the group is itself mixed")
        void shouldReadAsMixedWhenEveryPracticeIsMixed() {
            persistMixedPractice("commit-messages", "Commit Messages", 1L);
            persistMixedPractice("review-comments", "Actionable Review Comments", 2L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("MIXED")
                    .jsonPath("$[0].guidance")
                    .value(
                            String.class,
                            value -> org.hamcrest.MatcherAssert.assertThat(
                                    value, org.hamcrest.Matchers.startsWith("Your recent feedback is mixed in ")))
                    .jsonPath("$[0].guidance")
                    .value(
                            String.class,
                            value -> org.hamcrest.MatcherAssert.assertThat(
                                    value,
                                    org.hamcrest.Matchers.containsString("with both strengths and room to grow.")));
        }

        @Test
        @WithUser
        @DisplayName("two of five standing is still a mixed group, not a developing one")
        void shouldStayMixedJustAboveTheLowerBoundary() {
            persistStrengthPractice("commit-messages", "Commit Messages", 1L);
            persistStrengthPractice("review-comments", "Actionable Review Comments", 2L);
            persistDevelopingPractice("issue-descriptions", "Issue Descriptions", 3L);
            persistDevelopingPractice("test-coverage", "Test Coverage", 4L);
            persistDevelopingPractice("documentation", "Documentation", 5L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("MIXED");
        }

        @Test
        @WithUser
        @DisplayName("reads as DEVELOPING when barely any practice in the group is standing")
        void shouldReadAsDevelopingBelowTheLowerBoundary() {
            persistStrengthPractice("commit-messages", "Commit Messages", 1L);
            persistDevelopingPractice("review-comments", "Actionable Review Comments", 2L);
            persistDevelopingPractice("issue-descriptions", "Issue Descriptions", 3L);
            persistDevelopingPractice("test-coverage", "Test Coverage", 4L);
            persistDevelopingPractice("documentation", "Documentation", 5L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("DEVELOPING");
        }

        @Test
        @WithUser
        @DisplayName("a practice no longer reviewed does not vote in the group trend either")
        void shouldKeepAnIneligiblePracticeOutOfTheGroupTrend() {
            persistStrengthPractice("commit-messages", "Commit Messages", 1L);
            Practice retired = persistPractice(workspace, group, "test-coverage", "Test Coverage");
            retired.setAutonomy(PracticeAutonomy.OFF);
            practiceRepository.saveAndFlush(retired);
            insertObservation(agentJob, retired, developer, "Gap in Test Coverage", "ABSENT", "MAJOR", 2L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].trendSupport.currentOpportunities")
                    .isEqualTo(1)
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(2);
        }

        @Test
        @WithUser
        @DisplayName("keeps evidence from both sides when a mixed group reaches the item cap")
        void shouldKeepStrengthEvidenceWhenMixedGroupReachesItemCap() {
            Practice reviewPractice =
                    persistPractice(workspace, group, "review-comments", "Actionable Review Comments");
            for (long artifactId = 1; artifactId <= 5; artifactId++) {
                insertObservation(agentJob, practice, developer, "Gap " + artifactId, "ABSENT", "MAJOR", artifactId);
            }
            insertObservation(agentJob, reviewPractice, developer, "Concrete line references", "PRESENT", null, 6L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("MIXED")
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(5)
                    .jsonPath("$[0].observations[4].title")
                    .isEqualTo("Concrete line references");
        }

        @Test
        @WithUser
        @DisplayName("a thin record still yields a verdict, but never a direction")
        void shouldNotDeriveADirectionFromTwoObservations() {
            Instant previousDay = Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS);
            insertObservation(agentJob, practice, developer, "Speculative gap", "ABSENT", "MINOR", 1L, previousDay);
            insertObservation(agentJob, practice, developer, "Clear motivation section", "PRESENT", null, 2L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("MIXED")
                    .jsonPath("$[0].direction")
                    .isEqualTo("INSUFFICIENT_EVIDENCE")
                    .jsonPath("$[0].trendSupport.opportunitiesUntilComparable")
                    .isEqualTo(4)
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(2);
        }

        @Test
        @WithUser
        @DisplayName("reports IMPROVING when the latest opportunity bundle is more positive")
        void shouldReportImprovingTrajectory() {
            Instant previousDay = Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS);
            for (long artifactId = 1; artifactId <= 4; artifactId++) {
                insertObservation(
                        agentJob,
                        practice,
                        developer,
                        "Previous gap " + artifactId,
                        "ABSENT",
                        "MAJOR",
                        artifactId,
                        previousDay);
            }
            for (long artifactId = 5; artifactId <= 8; artifactId++) {
                insertObservation(
                        agentJob, practice, developer, "Current strength " + artifactId, "PRESENT", null, artifactId);
            }

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("STRENGTH")
                    .jsonPath("$[0].direction")
                    .isEqualTo("IMPROVING")
                    .jsonPath("$[0].feedbackSpanDays")
                    .isEqualTo(2);
        }

        @Test
        @WithUser
        @DisplayName("reports DECLINING when the latest opportunity bundle is less positive")
        void shouldReportRegressingTrajectory() {
            Instant previousDay = Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS);
            for (long artifactId = 1; artifactId <= 4; artifactId++) {
                insertObservation(
                        agentJob,
                        practice,
                        developer,
                        "Previous strength " + artifactId,
                        "PRESENT",
                        null,
                        artifactId,
                        previousDay);
            }
            for (long artifactId = 5; artifactId <= 8; artifactId++) {
                insertObservation(
                        agentJob, practice, developer, "Current gap " + artifactId, "ABSENT", "MAJOR", artifactId);
            }

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("DEVELOPING")
                    .jsonPath("$[0].direction")
                    .isEqualTo("DECLINING");
        }

        @Test
        @WithUser
        @DisplayName("a later run that did not cover a practice does not erase what an earlier one found")
        void shouldKeepObservationsARunNeverRevisited() {
            Practice reviewPractice =
                    persistPractice(workspace, group, "review-comments", "Actionable Review Comments");
            Instant previousDay = Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS);
            insertObservation(
                    agentJob, practice, developer, "Missing rollout plan", "ABSENT", "MAJOR", 1L, previousDay);
            insertObservation(
                    agentJob, reviewPractice, developer, "Concrete line references", "PRESENT", null, 1L, previousDay);
            AgentJob laterJob = persistAgentJob(workspace);
            insertObservation(laterJob, reviewPractice, developer, "Still concrete", "PRESENT", null, 1L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("MIXED")
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(2)
                    .jsonPath("$[0].observations[0].title")
                    .isEqualTo("Missing rollout plan");
        }

        @Test
        @WithUser
        @DisplayName("a later run that did cover the practice still supersedes what it found before")
        void shouldSupersedeObservationsTheLaterRunRevisited() {
            Instant previousDay = Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS);
            insertObservation(
                    agentJob, practice, developer, "Missing rollout plan", "ABSENT", "MAJOR", 1L, previousDay);
            AgentJob laterJob = persistAgentJob(workspace);
            insertObservation(laterJob, practice, developer, "Rollout plan added", "PRESENT", null, 1L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("STRENGTH")
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(1)
                    .jsonPath("$[0].observations[0].title")
                    .isEqualTo("Rollout plan added");
        }

        @Test
        @WithUser
        @DisplayName("a later run about somebody else does not erase this developer's earlier observation")
        void shouldKeepObservationsWhenTheLaterRunWasAboutAnotherDeveloper() {
            Instant previousDay = Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS);
            insertObservation(
                    agentJob, practice, developer, "Missing rollout plan", "ABSENT", "MAJOR", 1L, previousDay);
            User otherContributor = persistUser("other-contributor");
            AgentJob laterJob = persistAgentJob(workspace);
            insertObservation(laterJob, practice, otherContributor, "Someone else's gap", "ABSENT", "MAJOR", 1L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("DEVELOPING")
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(1)
                    .jsonPath("$[0].observations[0].title")
                    .isEqualTo("Missing rollout plan");
        }

        @Test
        @WithUser
        @DisplayName("does not leak another contributor's or another workspace's observations")
        void shouldNotLeakOtherContributorOrWorkspace() {
            User otherUser = persistUser("other-user");
            insertObservation(agentJob, practice, otherUser, "Someone else's gap", "ABSENT", "MAJOR", 2L);

            User otherOwner = persistUser("other-ws-owner");
            Workspace otherWorkspace =
                    createWorkspace("other-standing-ws", "Other WS", "other-standing-org", AccountType.ORG, otherOwner);
            PracticeGroup otherGroup = persistGroup(otherWorkspace, "code-quality", "Code Quality");
            Practice otherPractice =
                    persistPractice(otherWorkspace, otherGroup, "pr-description-quality", "PR Quality");
            AgentJob otherJob = persistAgentJob(otherWorkspace);
            insertObservation(otherJob, otherPractice, developer, "Cross-workspace gap", "ABSENT", "MAJOR", 3L);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].standing")
                    .isEqualTo("NOT_OBSERVED")
                    .jsonPath("$[0].observations.length()")
                    .isEqualTo(0);
        }

        @Test
        @WithUser
        @DisplayName("returns statuses for active groups only")
        void shouldReturnStatusesForActiveGroupsOnly() {
            group.setVisibleInPracticeDashboards(false);
            groupRepository.saveAndFlush(group);

            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("returns 401 when not logged in")
        void shouldReturnUnauthorized() {
            webTestClient
                    .get()
                    .uri(STANDINGS_URI, workspace.getWorkspaceSlug())
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
    }
}
