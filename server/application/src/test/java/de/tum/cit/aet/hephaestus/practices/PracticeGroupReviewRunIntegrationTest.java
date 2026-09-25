package de.tum.cit.aet.hephaestus.practices;

import static de.tum.cit.aet.hephaestus.practices.model.ObservationKind.DEMONSTRATED_STRENGTH;
import static de.tum.cit.aet.hephaestus.practices.model.ObservationKind.NOT_APPLICABLE;
import static de.tum.cit.aet.hephaestus.practices.model.ObservationKind.OMISSION_GAP;
import static de.tum.cit.aet.hephaestus.practices.model.ObservationKind.UNDETERMINED;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
class PracticeGroupReviewRunIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String REVIEW_RUNS_URI = "/workspaces/{workspaceSlug}/practice-groups/{groupSlug}/review-runs";

    /**
     * A citation of a source the shipped contract does not know. Delivery authorization refuses it, which is
     * the only way a developer's own observation is withheld from them for its evidence rather than its age.
     */
    private static final String UNKNOWN_SOURCE_EVIDENCE_JSON =
            "{\"citations\":[{\"sourceKind\":\"scm.repository.secrets\",\"artifactPath\":\"inputs/context/secrets.txt\","
                    + "\"path\":\"secrets.txt\",\"startLine\":1,\"endLine\":1,\"quote\":\"example\","
                    + "\"quoteRedacted\":false}]}";

    @Autowired
    private PracticeGroupRepository groupRepository;

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
        practice = persistPractice(workspace, group, "pr-description-quality", "PR Description Quality", null);
        agentJob = persistAgentJob(workspace);
    }

    private PracticeGroup persistGroup(Workspace ws, String slug, String name) {
        PracticeGroup a = new PracticeGroup();
        a.setWorkspace(ws);
        a.setSlug(slug);
        a.setName(name);
        return groupRepository.save(a);
    }

    private AgentJob persistAgentJob(Workspace ws) {
        AgentJob job = new AgentJob();
        job.setWorkspace(ws);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        job.setEvidenceSnapshot(OBJECT_MAPPER.valueToTree(Map.of("manifest", Map.of("contractVersion", "1.2.0"))));
        return agentJobRepository.save(job);
    }

    /** One observation of this group's practice, recorded now by this test's run. */
    private UUID observe(
            String title, ObservationKind kind, @Nullable Severity severity, String artifactKind, long artifactId) {
        return observe(
                practice,
                agentJob,
                artifactKind,
                artifactId,
                developer,
                title,
                kind,
                severity,
                Instant.now(),
                DIFF_EVIDENCE_JSON,
                null);
    }

    /** Advice lives on the delivered {@link Feedback}, not the observation (ADR 0021); the feed reads it from here. */
    private Feedback deliverFeedbackFor(UUID observationId, String body) {
        Feedback feedback = persistFeedback(
                agentJob,
                developer,
                FeedbackChannel.IN_CONTEXT,
                0,
                FeedbackDeliveryState.DELIVERED,
                body,
                Instant.now());
        bind(feedback, observationId);
        return feedback;
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
        observe("Motivation is clear", DEMONSTRATED_STRENGTH, null, ArtifactKinds.PULL_REQUEST.value(), 1L);
        observe("No testing notes", OMISSION_GAP, Severity.MAJOR, ArtifactKinds.PULL_REQUEST.value(), 1L);

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
        observe("Could not tell from the diff", UNDETERMINED, null, "scm.pull_request", 1L);

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
        observe("Issue lacks acceptance criteria", OMISSION_GAP, Severity.MINOR, ArtifactKinds.ISSUE.value(), 7L);

        getHistory()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].observations.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].observations[0].summary")
                .isEqualTo("Issue lacks acceptance criteria");
    }

    @Test
    @WithUser
    @DisplayName("a run that produced nothing to judge is absent from the history")
    void shouldOmitRunsWithoutAnythingToJudge() {
        observe("Nothing to judge here", NOT_APPLICABLE, null, ArtifactKinds.PULL_REQUEST.value(), 1L);

        getHistory().jsonPath("$.content.length()").isEqualTo(0);
    }

    @Test
    @WithUser
    void shouldSelectRunsByMatchingSeverityWithoutTreatingStrengthsAsMatches() {
        observe("Motivation is clear", DEMONSTRATED_STRENGTH, null, ArtifactKinds.PULL_REQUEST.value(), 1L);

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
    void shouldPageHistoricalAndCurrentRunsWithoutLosingEither() {
        AgentJob olderJob = persistAgentJob(workspace);
        observe(
                practice,
                olderJob,
                ArtifactKinds.PULL_REQUEST.value(),
                1L,
                developer,
                "Visible observation",
                DEMONSTRATED_STRENGTH,
                null,
                Instant.parse("2025-01-01T00:00:00Z"),
                DIFF_EVIDENCE_JSON,
                null);

        Practice superseded = persistPractice(workspace, group, "superseded", "Superseded", null);
        AgentJob newerJob = persistAgentJob(workspace);
        observe(
                superseded,
                newerJob,
                ArtifactKinds.PULL_REQUEST.value(),
                2L,
                developer,
                "Historical observation",
                DEMONSTRATED_STRENGTH,
                null,
                Instant.parse("2025-01-02T00:00:00Z"),
                DIFF_EVIDENCE_JSON,
                null);
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
                .jsonPath("$.content[0].observations[0].summary")
                .isEqualTo("Historical observation")
                .jsonPath("$.content[0].observations[0].claimCurrentness")
                .isEqualTo("STALE")
                .jsonPath("$.hasNext")
                .isEqualTo(true);

        webTestClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path(REVIEW_RUNS_URI)
                        .queryParam("size", 1)
                        .queryParam("page", 1)
                        .build(workspace.getWorkspaceSlug(), group.getSlug()))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].observations[0].summary")
                .isEqualTo("Visible observation")
                .jsonPath("$.content[0].observations[0].claimCurrentness")
                .isEqualTo("CURRENT")
                .jsonPath("$.hasNext")
                .isEqualTo(false);
    }

    @Test
    @WithUser
    @DisplayName("a run measured against older review rules stays in history")
    void shouldKeepARunMeasuredAgainstSupersededReviewRulesAsHistorical() {
        observe("Motivation is clear", DEMONSTRATED_STRENGTH, null, ArtifactKinds.PULL_REQUEST.value(), 1L);
        practice.setCriteria("Rewritten criteria, which is what makes the fingerprint differ");
        practice.setGroup(group);
        practice.setCurrentRevision(practiceRevisionRepository.save(new PracticeRevision(practice, 2)));
        practiceRepository.saveAndFlush(practice);

        getHistory()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].observations[0].claimCurrentness")
                .isEqualTo("STALE");
    }

    @Test
    @WithUser
    @DisplayName("carries each observation complete enough to open in place, so a row needs no detail request")
    void shouldCarryEachObservationCompleteEnoughToOpenWhenListingARun() {
        UUID observationId = observe(
                practice,
                agentJob,
                ArtifactKinds.PULL_REQUEST.value(),
                1L,
                developer,
                "No testing notes",
                OMISSION_GAP,
                Severity.MAJOR,
                Instant.parse("2025-03-04T05:06:07Z"),
                DIFF_EVIDENCE_JSON,
                "locus-testing-notes");
        Feedback feedback = deliverFeedbackFor(observationId, "Add a Testing section that names what you ran.");

        getHistory()
                .jsonPath("$.content[0].observations[0].id")
                .isEqualTo(observationId.toString())
                .jsonPath("$.content[0].observations[0].summary")
                .isEqualTo("No testing notes")
                .jsonPath("$.content[0].observations[0].evidenceRationale")
                .isEqualTo("Reasoning for No testing notes")
                .jsonPath("$.content[0].observations[0].deliveredFeedback")
                .isEqualTo("Add a Testing section that names what you ran.")
                .jsonPath("$.content[0].observations[0].feedbackResponse.feedbackId")
                .isEqualTo(feedback.getId().toString())
                .jsonPath("$.content[0].observations[0].evidence.citations.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].observations[0].evidence.citations[0].path")
                .isEqualTo("src/Main.java")
                .jsonPath("$.content[0].observations[0].evidence.citations[0].startLine")
                .isEqualTo(42)
                .jsonPath("$.content[0].observations[0].evidence.citations[0].quote")
                .isEqualTo("example")
                .jsonPath("$.content[0].observations[0].observedAt")
                .isEqualTo("2025-03-04T05:06:07Z")
                .jsonPath("$.content[0].observations[0].origin")
                .isEqualTo("LIVE")
                .jsonPath("$.content[0].observations[0].claimCurrentness")
                .isEqualTo("CURRENT")
                .jsonPath("$.content[0].observations[0].recurrenceKey")
                .isEqualTo("locus-testing-notes");
    }

    /** The next step the run composed from an observation travels with it, even though no feedback was delivered. */
    @Test
    @WithUser
    @DisplayName("an observation carries the next step its run wrote about it")
    void shouldCarryTheNextStepWhenTheRunComposedFeedback() {
        UUID observationId =
                observe("No testing notes", OMISSION_GAP, Severity.MAJOR, ArtifactKinds.PULL_REQUEST.value(), 1L);
        agentJob.setOutput(OBJECT_MAPPER.readTree("""
                {"feedback":{"observations":[{"id":"%s","practiceSlug":"pr-description-quality","anchorable":false,
                   "citations":[]}],
                  "units":[{"channel":"IN_CONTEXT","action":"NEW","practiceSlug":"pr-description-quality",
                   "basedOn":["%s"],"title":"No testing notes",
                   "nextStep":"Add a Testing section that names what you ran.",
                   "placement":{"kind":"ARTIFACT"}}]}}
                """.formatted(observationId, observationId)));
        agentJobRepository.saveAndFlush(agentJob);

        getHistory()
                .jsonPath("$.content[0].observations[0].nextStep")
                .isEqualTo("Add a Testing section that names what you ran.")
                .jsonPath("$.content[0].observations[0].deliveredFeedback")
                .doesNotExist();
    }

    @Test
    @WithUser
    @DisplayName("an observation whose run composed nothing carries no next step")
    void shouldCarryNoNextStepWhenTheRunComposedNothing() {
        observe("No testing notes", OMISSION_GAP, Severity.MAJOR, ArtifactKinds.PULL_REQUEST.value(), 1L);

        getHistory().jsonPath("$.content[0].observations[0].nextStep").doesNotExist();
    }

    @Test
    @WithUser
    @DisplayName("an observation whose evidence is not authorised for delivery is withheld, never shown bare")
    void shouldWithholdAnObservationWhenItsEvidenceIsNotAuthorisedForDelivery() {
        observe("Motivation is clear", DEMONSTRATED_STRENGTH, null, ArtifactKinds.PULL_REQUEST.value(), 1L);
        observe(
                practice,
                agentJob,
                ArtifactKinds.PULL_REQUEST.value(),
                1L,
                developer,
                "Cites a source nobody may show",
                OMISSION_GAP,
                Severity.MAJOR,
                Instant.now(),
                UNKNOWN_SOURCE_EVIDENCE_JSON,
                null);

        getHistory()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].observations.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].observations[0].summary")
                .isEqualTo("Motivation is clear")
                .jsonPath("$.content[0].observations[0].evidence.citations[0].quote")
                .isEqualTo("example");
    }
}
