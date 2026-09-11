package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Functional coverage for {@code GET /practice-groups/{groupSlug}/review-runs} — the developer-facing record of
 * what each review run saw.
 *
 * <p>The grain is the point: a run is returned whole or not at all, so the two queries behind it (which runs,
 * then their observations) must agree. And an undecided observation has to survive to the payload — this surface is
 * the inspectable record, so a practice that ran and hedged must not read like one that never ran.
 */
class PracticeGroupReviewRunIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String REVIEW_RUNS_URI = "/workspaces/{workspaceSlug}/practice-groups/{groupSlug}/review-runs";

    /** Delivery authorization reads the run's evidence contract, so every fixture observation cites a source. */
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

    private Workspace workspace;
    private PracticeGroup group;
    private Practice practice;
    private AgentJob agentJob;
    private User developer; // login = "testuser" to match @WithUser

    @BeforeEach
    void setUpWorkspace() {
        User owner = persistUser("review-runs-owner");
        workspace = createWorkspace("review-runs-ws", "Review History WS", "review-runs-org", AccountType.ORG, owner);

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

    private AgentJob persistAgentJob(Workspace ws) {
        AgentJob job = new AgentJob();
        job.setWorkspace(ws);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        job.setEvidenceSnapshot(OBJECT_MAPPER.valueToTree(Map.of("manifest", Map.of("contractVersion", "1.0.0"))));
        return agentJobRepository.save(job);
    }

    private void insertObservation(
            String title,
            String presence,
            @org.jspecify.annotations.Nullable String assessment,
            @org.jspecify.annotations.Nullable String severity,
            String artifactKind,
            Long artifactId) {
        insertObservation(
                practice, agentJob, title, presence, assessment, severity, artifactKind, artifactId, Instant.now());
    }

    private void insertObservation(
            Practice observedPractice,
            AgentJob reviewJob,
            String title,
            String presence,
            @org.jspecify.annotations.Nullable String assessment,
            @org.jspecify.annotations.Nullable String severity,
            String artifactKind,
            Long artifactId,
            Instant observedAt) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                "key-" + id,
                reviewJob.getId(),
                observedPractice.getWorkspace().getId(),
                observedPractice.getId(),
                observedPractice.getCurrentRevision().getId(),
                artifactKind,
                artifactId,
                developer.getId(),
                title,
                assessment == null ? presence : "ASSESSED",
                assessment == null ? null : presence,
                assessment,
                severity,
                DIFF_EVIDENCE_JSON,
                "Test reasoning for " + title,
                null,
                observedAt,
                "LIVE");
    }

    private WebTestClient.BodyContentSpec getHistory() {
        return webTestClient
                .get()
                .uri(REVIEW_RUNS_URI, workspace.getWorkspaceSlug(), group.getSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    @Test
    @WithUser
    @DisplayName("returns a review run whole, with every observation that explains it")
    void shouldReturnCompleteRun() {
        insertObservation("Motivation is clear", "PRESENT", "GOOD", null, ArtifactKinds.PULL_REQUEST.value(), 1L);
        insertObservation("No testing notes", "ABSENT", "BAD", "MAJOR", ArtifactKinds.PULL_REQUEST.value(), 1L);

        getHistory()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].reviewId")
                .isEqualTo(agentJob.getId().toString())
                .jsonPath("$.content[0].observations.length()")
                .isEqualTo(2);
    }

    @Test
    @WithUser
    @DisplayName("carries an undecided observation with a null assessment rather than dropping it")
    void shouldCarryInconclusiveObservationWithoutAnAssessment() {
        insertObservation("Could not tell from the diff", "UNDETERMINED", null, null, "scm.pull_request", 1L);

        getHistory()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].observations.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].observations[0].assessmentStatus")
                .isEqualTo("UNDETERMINED")
                .jsonPath("$.content[0].observations[0].presence")
                .doesNotExist()
                .jsonPath("$.content[0].observations[0].assessment")
                .doesNotExist();
    }

    @Test
    @WithUser
    @DisplayName("an unfiltered request is not silently narrowed to pull requests")
    void shouldNotDefaultToPullRequestsWhenNoKindFilterIsGiven() {
        insertObservation("Issue lacks acceptance criteria", "ABSENT", "BAD", "MINOR", ArtifactKinds.ISSUE.value(), 7L);

        getHistory()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].observations.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].observations[0].title")
                .isEqualTo("Issue lacks acceptance criteria");
    }

    @Test
    @WithUser
    @DisplayName("a run that produced nothing to judge is absent from the history")
    void shouldOmitRunsWithoutAnythingToJudge() {
        insertObservation(
                "Nothing to judge here", "NOT_APPLICABLE", null, null, ArtifactKinds.PULL_REQUEST.value(), 1L);

        getHistory().jsonPath("$.content.length()").isEqualTo(0);
    }

    @Test
    @WithUser
    void shouldSelectRunsByMatchingSeverityWithoutTreatingStrengthsAsMatches() {
        insertObservation("Motivation is clear", "PRESENT", "GOOD", null, ArtifactKinds.PULL_REQUEST.value(), 1L);

        webTestClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path(REVIEW_RUNS_URI)
                        .queryParam("severities", "MAJOR")
                        .build(workspace.getWorkspaceSlug(), group.getSlug()))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(0);
    }

    @Test
    @WithUser
    void shouldFillAPageAfterWithholdingANewerRun() {
        AgentJob olderJob = persistAgentJob(workspace);
        insertObservation(
                practice,
                olderJob,
                "Visible observation",
                "PRESENT",
                "GOOD",
                null,
                ArtifactKinds.PULL_REQUEST.value(),
                1L,
                Instant.parse("2025-01-01T00:00:00Z"));

        Practice superseded = persistPractice(workspace, group, "superseded", "Superseded");
        AgentJob newerJob = persistAgentJob(workspace);
        insertObservation(
                superseded,
                newerJob,
                "Withheld observation",
                "PRESENT",
                "GOOD",
                null,
                ArtifactKinds.PULL_REQUEST.value(),
                2L,
                Instant.parse("2025-01-02T00:00:00Z"));
        superseded.setCriteria("New criteria");
        superseded.setGroup(group);
        superseded.setCurrentRevision(practiceRevisionRepository.save(new PracticeRevision(superseded, 2)));
        practiceRepository.saveAndFlush(superseded);

        webTestClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path(REVIEW_RUNS_URI)
                        .queryParam("size", 1)
                        .build(workspace.getWorkspaceSlug(), group.getSlug()))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].observations[0].title")
                .isEqualTo("Visible observation")
                .jsonPath("$.hasNext")
                .isEqualTo(false);
    }

    @Test
    @WithUser
    @DisplayName("a run whose observations the visibility gate withholds leaves the page entirely")
    void shouldWithholdARunMeasuredAgainstSupersededReviewRules() {
        insertObservation("Motivation is clear", "PRESENT", "GOOD", null, ArtifactKinds.PULL_REQUEST.value(), 1L);
        practice.setCriteria("Rewritten criteria, which is what makes the fingerprint differ");
        practice.setGroup(group);
        practice.setCurrentRevision(practiceRevisionRepository.save(new PracticeRevision(practice, 2)));
        practiceRepository.saveAndFlush(practice);

        getHistory().jsonPath("$.content.length()").isEqualTo(0);
    }
}
