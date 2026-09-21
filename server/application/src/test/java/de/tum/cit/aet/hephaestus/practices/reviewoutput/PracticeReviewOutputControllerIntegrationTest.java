package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluation;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementAnchorKind;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementAnchorSide;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

class PracticeReviewOutputControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String OBSERVATIONS = "/workspaces/{slug}/practices/reviews/observations";
    private static final String FEEDBACK = "/workspaces/{slug}/practices/reviews/feedback";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeGroupRepository practiceGroupRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private FeedbackPlacementRepository feedbackPlacementRepository;

    @Autowired
    private DeliveryPolicyEvaluationRepository deliveryPolicyEvaluationRepository;

    private Workspace workspace;
    private Workspace otherWorkspace;
    private Practice practiceA;
    private Practice practiceB;
    private Practice otherPractice;
    private AgentJob job;
    private AgentJob otherJob;
    private User alice;
    private User bob;
    private User workspaceMember;

    @BeforeEach
    void setUpWorkspaces() {
        User owner = persistUser("detection-owner");
        workspace = createWorkspace("detection-ws", "Detection WS", "detection-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        alice = persistUser("alice");
        bob = persistUser("bob");
        workspaceMember = persistUser("testuser");
        User plainWorkspaceAdmin = persistUser("mentor");
        ensureWorkspaceMembership(workspace, alice, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, bob, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, workspaceMember, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, plainWorkspaceAdmin, WorkspaceMembership.WorkspaceRole.ADMIN);

        practiceA = persistPractice(workspace, "pr-description-quality", "PR Description Quality");
        practiceB = persistPractice(workspace, "test-coverage", "Test Coverage");
        job = persistJob(workspace);

        User otherOwner = persistUser("other-owner");
        otherWorkspace = createWorkspace("other-ws", "Other WS", "other-org", AccountType.ORG, otherOwner);
        otherPractice = persistPractice(otherWorkspace, "pr-description-quality", "PR Description Quality");
        otherJob = persistJob(otherWorkspace);
    }

    private Practice persistPractice(Workspace ws, String slug, String name) {
        Practice practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(ws);
        practice.setSlug(slug);
        practice.setName(name);
        practice.setCriteria("Criteria for " + slug);
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        return practiceRepository.save(practice);
    }

    private PracticeGroup persistGroup(Workspace ws, String slug, String name) {
        PracticeGroup group = new PracticeGroup();
        group.setWorkspace(ws);
        group.setSlug(slug);
        group.setName(name);
        group.setIcon("MessageSquareText");
        group.setColor("blue");
        return practiceGroupRepository.save(group);
    }

    private AgentJob persistJob(Workspace ws) {
        AgentJob agentJob = new AgentJob();
        agentJob.setWorkspace(ws);
        agentJob.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        agentJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        agentJob.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        agentJob.setEvidenceSnapshot(OBJECT_MAPPER.valueToTree(Map.of("manifest", Map.of("contractVersion", "1.1.0"))));
        return agentJobRepository.save(agentJob);
    }

    private UUID insertProblem(Practice practice, AgentJob agentJob, User about, String title, String severity) {
        return insertObservation(practice, agentJob, about, title, "ABSENT", "GOOD", severity, 0.8f, 7L, Instant.now());
    }

    private UUID insertObservation(
            Practice practice,
            AgentJob agentJob,
            User about,
            String title,
            String presence,
            @Nullable String assessment,
            @Nullable String severity,
            float confidence,
            Long artifactId,
            Instant observedAt) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                "occurrence-" + id,
                agentJob.getId(),
                practice.getWorkspace().getId(),
                practice.getId(),
                null,
                ArtifactKinds.PULL_REQUEST.value(),
                artifactId,
                about.getId(),
                title,
                assessment != null ? "ASSESSED" : "INCONCLUSIVE".equals(presence) ? "UNDETERMINED" : "NOT_APPLICABLE",
                assessment == null ? null : presence,
                assessment,
                ("PRESENT".equals(presence) != "GOOD".equals(assessment)) ? severity : null,
                "{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\",\"artifactPath\":\"inputs/context/diff.patch\",\"path\":\"src/Main.java\",\"side\":\"NEW\",\"startLine\":42,\"endLine\":50,\"quote\":\"example\",\"quoteRedacted\":false}]}",
                "Reasoning for " + title,
                "recurrence-" + title,
                observedAt,
                "LIVE");
        return id;
    }

    private Feedback persistUnit(
            Workspace ws,
            AgentJob agentJob,
            User recipient,
            int position,
            FeedbackDeliveryState state,
            @Nullable FeedbackSuppressionReason reason,
            @Nullable String body) {
        return persistUnit(
                ws, agentJob, recipient, position, state, reason, body, Instant.now(), ArtifactKinds.PULL_REQUEST, 7L);
    }

    private Feedback persistUnit(
            Workspace ws,
            AgentJob agentJob,
            User recipient,
            int position,
            FeedbackDeliveryState state,
            @Nullable FeedbackSuppressionReason reason,
            @Nullable String body,
            Instant createdAt) {
        return persistUnit(
                ws, agentJob, recipient, position, state, reason, body, createdAt, ArtifactKinds.PULL_REQUEST, 7L);
    }

    private Feedback persistUnit(
            Workspace ws,
            AgentJob agentJob,
            User recipient,
            int position,
            FeedbackDeliveryState state,
            @Nullable FeedbackSuppressionReason reason,
            @Nullable String body,
            Instant createdAt,
            ArtifactKind artifactKind,
            Long artifactId) {
        return feedbackRepository.save(Feedback.builder()
                .agentJobId(agentJob.getId())
                .workspaceId(ws.getId())
                .artifactKind(artifactKind)
                .artifactId(artifactId)
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(position)
                .deliveryState(state)
                .suppressionReason(reason)
                .body(body)
                .source(FeedbackSource.AGENT)
                .createdAt(createdAt)
                .deliveredAt(
                        state == FeedbackDeliveryState.DELIVERED || state == FeedbackDeliveryState.SUPERSEDED
                                ? Instant.now()
                                : null)
                .build());
    }

    private WebTestClient.ResponseSpec get(String uri, Object... uriVariables) {
        return webTestClient
                .get()
                .uri(uri, uriVariables)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange();
    }

    private WebTestClient.BodyContentSpec getOk(String uri, Object... uriVariables) {
        return get(uri, uriVariables).expectStatus().isOk().expectBody();
    }

    private void expectResolvedPullRequestArtifact(String uri, String path, Object... uriVariables) {
        getOk(uri, uriVariables)
                .jsonPath(path + ".type")
                .isEqualTo("scm.pull_request")
                .jsonPath(path + ".provider")
                .isEqualTo("GITHUB")
                .jsonPath(path + ".number")
                .isEqualTo(42)
                .jsonPath(path + ".title")
                .isEqualTo("Make review output visible")
                .jsonPath(path + ".repositoryName")
                .isEqualTo("detection-org/review-ui")
                .jsonPath(path + ".url")
                .isEqualTo("https://github.com/detection-org/review-ui/pull/42");
    }

    private void bind(Feedback unit, UUID observationId) {
        feedbackObservationRepository.insertIfAbsent(unit.getId(), observationId, "PRIMARY", 0);
    }

    @Nested
    @DisplayName("Access control")
    class AccessControl {

        @Test
        void anonymousCallerCannotReadReviewOutput() {
            webTestClient
                    .get()
                    .uri(OBSERVATIONS, workspace.getWorkspaceSlug())
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }

        @Test
        @WithUser
        void workspaceMemberCannotReadReviewOutput() {
            get(OBSERVATIONS, workspace.getWorkspaceSlug())
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void workspaceAdminWithoutInstanceAuthorityIsAdmitted() {
            get(OBSERVATIONS, workspace.getWorkspaceSlug())
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("Observations")
    class Observations {

        @Test
        @WithAdminUser
        void spansEveryDeveloperNotJustTheCaller() {
            insertProblem(practiceA, job, alice, "Alice problem", "MAJOR");
            insertProblem(practiceB, job, bob, "Bob problem", "MINOR");

            getOk(OBSERVATIONS, workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(2)
                    .jsonPath("$.content[?(@.summary == 'Alice problem')].subject.login")
                    .isEqualTo("alice")
                    .jsonPath("$.content[?(@.summary == 'Alice problem')].claimCurrentness")
                    .isEqualTo("UNVERIFIABLE")
                    .jsonPath("$.content[?(@.summary == 'Bob problem')].subject.login")
                    .isEqualTo("bob");
        }

        @Test
        @WithAdminUser
        void excludesOtherWorkspaces() {
            insertProblem(practiceA, job, alice, "Mine", "MAJOR");
            insertProblem(otherPractice, otherJob, bob, "Theirs", "MAJOR");

            getOk(OBSERVATIONS, workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Mine");
        }

        @Test
        @WithAdminUser
        void detailOfAnotherWorkspaceIsNotFound() {
            UUID theirs = insertProblem(otherPractice, otherJob, bob, "Theirs", "MAJOR");

            get(OBSERVATIONS + "/{id}", workspace.getWorkspaceSlug(), theirs)
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        void shouldFilterAssessmentStatusIndependentlyFromPresenceAndAssessment() {
            insertObservation(practiceA, job, alice, "Risk avoided", "ABSENT", "BAD", null, 0.8f, 7L, Instant.now());
            insertObservation(
                    practiceA, job, alice, "No occasion", "NOT_APPLICABLE", null, null, 0.8f, 7L, Instant.now());
            insertObservation(
                    practiceA, job, alice, "Criterion unresolved", "INCONCLUSIVE", null, null, 0.8f, 7L, Instant.now());
            for (String status : List.of("NOT_APPLICABLE", "UNDETERMINED")) {
                getOk(
                                OBSERVATIONS + "?agentJobId={id}&assessmentStatus={status}",
                                workspace.getWorkspaceSlug(),
                                job.getId(),
                                status)
                        .jsonPath("$.page.totalElements")
                        .isEqualTo(1)
                        .jsonPath("$.content[0].assessmentStatus")
                        .isEqualTo(status)
                        .jsonPath("$.content[0].presence")
                        .doesNotExist()
                        .jsonPath("$.content[0].assessment")
                        .doesNotExist()
                        .jsonPath("$.content[0].severity")
                        .doesNotExist();
            }
            getOk(
                            OBSERVATIONS + "?agentJobId={id}&assessmentStatus=ASSESSED&presence=ABSENT&assessment=BAD",
                            workspace.getWorkspaceSlug(),
                            job.getId())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Risk avoided")
                    .jsonPath("$.content[0].outcome")
                    .isEqualTo("POSITIVE");
        }

        @Test
        @WithAdminUser
        void filtersByPracticeSeverityAndSubject() {
            insertProblem(practiceA, job, alice, "A major", "MAJOR");
            insertProblem(practiceA, job, alice, "A minor", "MINOR");
            insertProblem(practiceB, job, bob, "B major", "MAJOR");

            getOk(
                            OBSERVATIONS + "?practiceSlug={slug}&severity=MAJOR&subjectUserId={uid}",
                            workspace.getWorkspaceSlug(),
                            practiceA.getSlug(),
                            alice.getId())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("A major");
        }

        @Test
        @WithAdminUser
        void filtersByGroupAndReturnsItsMetadata() {
            PracticeGroup group = persistGroup(workspace, "communication", "Communication");
            practiceA.setGroup(group);
            practiceRepository.save(practiceA);
            UUID observationId = insertProblem(practiceA, job, alice, "In group", "MAJOR");
            insertProblem(practiceB, job, bob, "Ungrouped", "MAJOR");

            getOk(OBSERVATIONS + "?groupSlug=communication", workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("In group")
                    .jsonPath("$.content[0].group.slug")
                    .isEqualTo("communication")
                    .jsonPath("$.content[0].group.name")
                    .isEqualTo("Communication")
                    .jsonPath("$.content[0].group.icon")
                    .isEqualTo("MessageSquareText")
                    .jsonPath("$.content[0].group.color")
                    .isEqualTo("blue");

            getOk(OBSERVATIONS + "/{id}", workspace.getWorkspaceSlug(), observationId)
                    .jsonPath("$.group.slug")
                    .isEqualTo("communication");
        }

        @Test
        @WithAdminUser
        void filtersBySeveralSeverities() {
            insertProblem(practiceA, job, alice, "Critical", "CRITICAL");
            insertProblem(practiceA, job, alice, "Major", "MAJOR");
            insertProblem(practiceA, job, alice, "Info", "INFO");

            getOk(OBSERVATIONS + "?severity=CRITICAL&severity=MAJOR", workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(2);
        }

        @Test
        @WithAdminUser
        void filtersByRunAndArtifact() {
            insertProblem(practiceA, job, alice, "This run", "MAJOR");
            AgentJob second = persistJob(workspace);
            insertObservation(
                    practiceA, second, alice, "Other run", "ABSENT", "GOOD", "MAJOR", 0.8f, 9L, Instant.now());

            getOk(OBSERVATIONS + "?agentJobId={id}", workspace.getWorkspaceSlug(), job.getId())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("This run");

            getOk(OBSERVATIONS + "?artifactKind=scm.pull_request&artifactId=9", workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Other run");
        }

        @Test
        @WithAdminUser
        void sortsObservationsByActionabilityWithoutChangingTheDefault() {
            record ObservationInput(
                    String summary,
                    String presence,
                    @Nullable String assessment,
                    @Nullable String severity) {}

            Instant base = Instant.parse("2026-01-10T00:00:00Z");
            List<ObservationInput> observations = List.of(
                    new ObservationInput("Critical problem", "ABSENT", "GOOD", "CRITICAL"),
                    new ObservationInput("Major problem", "ABSENT", "GOOD", "MAJOR"),
                    new ObservationInput("Minor problem", "ABSENT", "GOOD", "MINOR"),
                    new ObservationInput("Info problem", "ABSENT", "GOOD", "INFO"),
                    new ObservationInput("Strength", "PRESENT", "GOOD", null),
                    new ObservationInput("Not applicable", "NOT_APPLICABLE", null, null));
            for (int i = 0; i < observations.size(); i++) {
                ObservationInput observation = observations.get(i);
                insertObservation(
                        practiceA,
                        job,
                        alice,
                        observation.summary(),
                        observation.presence(),
                        observation.assessment(),
                        observation.severity(),
                        0.8f,
                        7L,
                        base.plusSeconds(i));
            }

            getOk(
                            OBSERVATIONS + "?agentJobId={id}&sort=ACTIONABILITY&size=5",
                            workspace.getWorkspaceSlug(),
                            job.getId())
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Critical problem")
                    .jsonPath("$.content[1].summary")
                    .isEqualTo("Major problem")
                    .jsonPath("$.content[2].summary")
                    .isEqualTo("Minor problem")
                    .jsonPath("$.content[3].summary")
                    .isEqualTo("Info problem")
                    .jsonPath("$.content[4].summary")
                    .isEqualTo("Strength");

            getOk(OBSERVATIONS + "?agentJobId={id}&size=5", workspace.getWorkspaceSlug(), job.getId())
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Not applicable")
                    .jsonPath("$.content[4].summary")
                    .isEqualTo("Major problem");
        }

        @Test
        @WithAdminUser
        void filtersByObservedAtWindow() {
            Instant from = Instant.parse("2026-01-10T00:00:00Z");
            Instant to = Instant.parse("2026-01-20T00:00:00Z");
            insertObservation(
                    practiceA, job, alice, "Before", "ABSENT", "GOOD", "MAJOR", 0.8f, 7L, from.minusSeconds(1));
            insertObservation(practiceA, job, alice, "Inside", "ABSENT", "GOOD", "MAJOR", 0.8f, 8L, from);
            insertObservation(practiceA, job, alice, "At end", "ABSENT", "GOOD", "MAJOR", 0.8f, 9L, to);

            getOk(OBSERVATIONS + "?from={from}&to={to}", workspace.getWorkspaceSlug(), from, to)
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Inside");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "?artifactId=7",
                    "?subjectUserId=-1",
                    "?from=2026-01-20T00:00:00Z&to=2026-01-10T00:00:00Z",
                    "?sort=UNKNOWN",
                })
        @WithAdminUser
        void rejectsInvalidObservationQuery(String query) {
            get(OBSERVATIONS + query, workspace.getWorkspaceSlug())
                    .expectStatus()
                    .isBadRequest()
                    .expectHeader()
                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo(400);
        }

        @Test
        @WithAdminUser
        void reportsEveryLinkedFeedbackOutcome() {
            UUID observationId = insertProblem(practiceA, job, alice, "Every outcome", "MAJOR");
            bind(
                    persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.DELIVERED, null, "Posted body"),
                    observationId);
            bind(
                    persistUnit(workspace, job, alice, 1, FeedbackDeliveryState.SUPERSEDED, null, "Old body"),
                    observationId);
            bind(persistUnit(workspace, job, alice, 2, FeedbackDeliveryState.PREPARED, null, null), observationId);
            bind(
                    persistUnit(
                            workspace,
                            job,
                            alice,
                            3,
                            FeedbackDeliveryState.SUPPRESSED,
                            FeedbackSuppressionReason.VOLUME_CAPPED,
                            null),
                    observationId);
            bind(
                    persistUnit(workspace, job, alice, 4, FeedbackDeliveryState.FAILED, null, "Failed body"),
                    observationId);

            getOk(OBSERVATIONS, workspace.getWorkspaceSlug())
                    .jsonPath("$.content[0].feedbackDisposition.prepared")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].feedbackDisposition.delivered")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].feedbackDisposition.superseded")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].feedbackDisposition.suppressed")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].feedbackDisposition.failed")
                    .isEqualTo(1);
        }

        @Test
        @WithAdminUser
        void reportsZeroFeedbackCountsWhenNeverBound() {
            insertProblem(practiceA, job, alice, "Orphan", "MAJOR");

            getOk(OBSERVATIONS, workspace.getWorkspaceSlug())
                    .jsonPath("$.content[0].feedbackDisposition.prepared")
                    .isEqualTo(0)
                    .jsonPath("$.content[0].feedbackDisposition.delivered")
                    .isEqualTo(0)
                    .jsonPath("$.content[0].feedbackDisposition.superseded")
                    .isEqualTo(0)
                    .jsonPath("$.content[0].feedbackDisposition.suppressed")
                    .isEqualTo(0)
                    .jsonPath("$.content[0].feedbackDisposition.failed")
                    .isEqualTo(0);
        }

        @Test
        @WithAdminUser
        void excludesCrossWorkspaceFeedbackBindings() {
            UUID observationId = insertProblem(practiceA, job, alice, "Local observation", "MAJOR");
            Feedback foreignUnit = persistUnit(
                    otherWorkspace, otherJob, bob, 0, FeedbackDeliveryState.DELIVERED, null, "Foreign body");
            bind(foreignUnit, observationId);

            getOk(OBSERVATIONS, workspace.getWorkspaceSlug())
                    .jsonPath("$.content[0].feedbackDisposition.delivered")
                    .isEqualTo(0);

            getOk(OBSERVATIONS + "/{id}", workspace.getWorkspaceSlug(), observationId)
                    .jsonPath("$.feedback.length()")
                    .isEqualTo(0);
        }

        @Test
        @WithAdminUser
        void detailCarriesEvidenceReasoningAndTheUnitsItFed() {
            UUID id = insertProblem(practiceA, job, alice, "Detailed", "MAJOR");
            bind(
                    persistUnit(
                            workspace,
                            job,
                            alice,
                            5000,
                            FeedbackDeliveryState.SUPPRESSED,
                            FeedbackSuppressionReason.ARTIFACT_CLOSED,
                            "Would have said this"),
                    id);

            getOk(OBSERVATIONS + "/{id}", workspace.getWorkspaceSlug(), id)
                    .jsonPath("$.evidence.citations[0].path")
                    .isEqualTo("src/Main.java")
                    .jsonPath("$.evidenceRationale")
                    .isEqualTo("Reasoning for Detailed")
                    .jsonPath("$.subject.login")
                    .isEqualTo("alice")
                    .jsonPath("$.feedback.length()")
                    .isEqualTo(1)
                    .jsonPath("$.feedback[0].deliveryState")
                    .isEqualTo("SUPPRESSED")
                    .jsonPath("$.feedback[0].suppressionReason")
                    .isEqualTo("ARTIFACT_CLOSED")
                    .jsonPath("$.feedback[0].role")
                    .isEqualTo("PRIMARY");
        }
    }

    @Nested
    @DisplayName("Artifact context")
    class ArtifactContext {

        @Test
        @WithAdminUser
        void usesTheTargetRecordedWhenTheReviewWasSubmitted() {
            long artifactId = 7L;
            job.setIntegrationKind(IntegrationKind.GITHUB);
            job.setMetadata(OBJECT_MAPPER.valueToTree(Map.of(
                    "pull_request_id",
                    artifactId,
                    "pr_number",
                    42,
                    "title",
                    "Make review output visible",
                    "repository_full_name",
                    "detection-org/review-ui",
                    "pr_url",
                    "https://github.com/detection-org/review-ui/pull/42")));
            agentJobRepository.save(job);
            UUID observationId = insertObservation(
                    practiceA,
                    job,
                    alice,
                    "Resolved artifact",
                    "ABSENT",
                    "GOOD",
                    "MAJOR",
                    0.8f,
                    artifactId,
                    Instant.now());
            Feedback feedback = persistUnit(
                    workspace,
                    job,
                    alice,
                    0,
                    FeedbackDeliveryState.DELIVERED,
                    null,
                    "Body",
                    Instant.now(),
                    ArtifactKinds.PULL_REQUEST,
                    artifactId);

            expectResolvedPullRequestArtifact(
                    OBSERVATIONS + "?artifactKind=scm.pull_request&artifactId={id}",
                    "$.content[0].artifact",
                    workspace.getWorkspaceSlug(),
                    artifactId);
            expectResolvedPullRequestArtifact(
                    OBSERVATIONS + "/{id}", "$.artifact", workspace.getWorkspaceSlug(), observationId);
            expectResolvedPullRequestArtifact(
                    FEEDBACK + "?artifactKind=scm.pull_request&artifactId={id}",
                    "$.content[0].artifact",
                    workspace.getWorkspaceSlug(),
                    artifactId);
            expectResolvedPullRequestArtifact(
                    FEEDBACK + "/{id}", "$.artifact", workspace.getWorkspaceSlug(), feedback.getId());
        }

        @Test
        @WithAdminUser
        void doesNotExposeArtifactsFromAnotherWorkspace() {
            otherJob.setIntegrationKind(IntegrationKind.GITHUB);
            otherJob.setMetadata(OBJECT_MAPPER.valueToTree(Map.of(
                    "pull_request_id",
                    812,
                    "title",
                    "Private target from another workspace",
                    "pr_url",
                    "https://github.com/other-org/private/pull/1")));
            agentJobRepository.save(otherJob);
            UUID observationId = insertObservation(
                    practiceA,
                    otherJob,
                    alice,
                    "Foreign artifact reference",
                    "ABSENT",
                    "GOOD",
                    "MAJOR",
                    0.8f,
                    812L,
                    Instant.now());

            getOk(OBSERVATIONS + "/{id}", workspace.getWorkspaceSlug(), observationId)
                    .jsonPath("$.artifact.type")
                    .isEqualTo("scm.pull_request")
                    .jsonPath("$.artifact.id")
                    .isEqualTo(812)
                    .jsonPath("$.artifact.title")
                    .isEqualTo("Pull request")
                    .jsonPath("$.artifact.provider")
                    .doesNotExist()
                    .jsonPath("$.artifact.url")
                    .doesNotExist()
                    // The observation resolves, but nothing about the other workspace's evidence may come
                    // with it: the citations and their captured content are the payload a leak would
                    // actually expose, and the artifact fields alone never covered them.
                    .jsonPath("$.evidence")
                    .doesNotExist();
        }

        @Test
        @WithAdminUser
        void returnsFallbackForALegacyConversationWithoutTargetMetadata() {
            job.setJobType(AgentJobType.CONVERSATION_REVIEW);
            agentJobRepository.save(job);
            Feedback feedback = persistUnit(
                    workspace,
                    job,
                    alice,
                    0,
                    FeedbackDeliveryState.PREPARED,
                    null,
                    null,
                    Instant.now(),
                    ArtifactKinds.CONVERSATION_THREAD,
                    812L);

            getOk(FEEDBACK + "/{id}", workspace.getWorkspaceSlug(), feedback.getId())
                    .jsonPath("$.artifact.type")
                    .isEqualTo("chat.conversation_thread")
                    .jsonPath("$.artifact.id")
                    .isEqualTo(812)
                    .jsonPath("$.artifact.title")
                    .isEqualTo("Conversation")
                    .jsonPath("$.artifact.provider")
                    .doesNotExist()
                    .jsonPath("$.artifact.channelName")
                    .doesNotExist()
                    .jsonPath("$.artifact.url")
                    .doesNotExist();
        }
    }

    @Nested
    @DisplayName("Feedback ledger")
    class Ledger {

        @Test
        @WithAdminUser
        void listsEveryStateNotJustDelivered() {
            persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.DELIVERED, null, "Delivered body");
            persistUnit(workspace, job, alice, 1, FeedbackDeliveryState.SUPERSEDED, null, "Superseded body");
            persistUnit(workspace, job, alice, 3000, FeedbackDeliveryState.PREPARED, null, null);
            persistUnit(
                    workspace,
                    job,
                    bob,
                    5000,
                    FeedbackDeliveryState.SUPPRESSED,
                    FeedbackSuppressionReason.ARTIFACT_MERGED,
                    "Withheld body");
            persistUnit(workspace, job, bob, 4000, FeedbackDeliveryState.FAILED, null, "Failed body");

            getOk(FEEDBACK, workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(5)
                    .jsonPath("$.content[?(@.deliveryState == 'DELIVERED')]")
                    .isNotEmpty()
                    .jsonPath("$.content[?(@.deliveryState == 'SUPERSEDED')]")
                    .isNotEmpty()
                    .jsonPath("$.content[?(@.deliveryState == 'PREPARED')]")
                    .isNotEmpty()
                    .jsonPath("$.content[?(@.deliveryState == 'SUPPRESSED')]")
                    .isNotEmpty()
                    .jsonPath("$.content[?(@.deliveryState == 'FAILED')]")
                    .isNotEmpty();
        }

        @Test
        @WithAdminUser
        void excludesOtherWorkspaces() {
            persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.DELIVERED, null, "Mine");
            persistUnit(otherWorkspace, otherJob, bob, 0, FeedbackDeliveryState.DELIVERED, null, "Theirs");

            getOk(FEEDBACK, workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].bodyPreview")
                    .isEqualTo("Mine");
        }

        @Test
        @WithAdminUser
        void detailOfAnotherWorkspaceIsNotFound() {
            Feedback theirs =
                    persistUnit(otherWorkspace, otherJob, bob, 0, FeedbackDeliveryState.DELIVERED, null, "Theirs");

            get(FEEDBACK + "/{id}", workspace.getWorkspaceSlug(), theirs.getId())
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        void detailIncludesOnlyThePolicyTraceForThatFeedback() {
            Feedback unit = persistUnit(
                    workspace,
                    job,
                    alice,
                    0,
                    FeedbackDeliveryState.SUPPRESSED,
                    FeedbackSuppressionReason.RECIPIENT_OPTED_OUT,
                    "Withheld");
            deliveryPolicyEvaluationRepository.save(
                    policyEvaluation(job, unit.getId(), DeliveryPolicySurface.ARTIFACT));
            deliveryPolicyEvaluationRepository.save(policyEvaluation(job, null, DeliveryPolicySurface.CONVERSATION));

            getOk(FEEDBACK + "/{id}", workspace.getWorkspaceSlug(), unit.getId())
                    .jsonPath("$.deliveryPolicy.length()")
                    .isEqualTo(1)
                    .jsonPath("$.deliveryPolicy[0].reviewId")
                    .isEqualTo(job.getId().toString())
                    .jsonPath("$.deliveryPolicy[0].surface")
                    .isEqualTo("ARTIFACT")
                    .jsonPath("$.deliveryPolicy[0].decisiveReason")
                    .isEqualTo("RECIPIENT_OPTED_OUT")
                    .jsonPath("$.deliveryPolicy[0].checks[0].check")
                    .isEqualTo("RECIPIENT_CONSENT")
                    .jsonPath("$.deliveryPolicy[0].checks[0].status")
                    .isEqualTo("DENIED");
        }

        @Test
        @WithAdminUser
        void filtersByStateReasonAndRun() {
            persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.DELIVERED, null, "Delivered");
            persistUnit(
                    workspace,
                    job,
                    alice,
                    2000,
                    FeedbackDeliveryState.SUPPRESSED,
                    FeedbackSuppressionReason.VOLUME_CAPPED,
                    null);
            persistUnit(
                    workspace,
                    job,
                    alice,
                    2001,
                    FeedbackDeliveryState.SUPPRESSED,
                    FeedbackSuppressionReason.COMPOSER_DEDUPED,
                    null);
            AgentJob second = persistJob(workspace);
            persistUnit(workspace, second, bob, 0, FeedbackDeliveryState.DELIVERED, null, "Other run");

            getOk(FEEDBACK + "?deliveryState=SUPPRESSED", workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(2);

            getOk(FEEDBACK + "?suppressionReason=VOLUME_CAPPED", workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1);

            getOk(FEEDBACK + "?agentJobId={id}", workspace.getWorkspaceSlug(), second.getId())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].bodyPreview")
                    .isEqualTo("Other run");
        }

        @Test
        @WithAdminUser
        void filtersByChannel() {
            persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.DELIVERED, null, "In context");
            feedbackRepository.save(Feedback.builder()
                    .agentJobId(job.getId())
                    .workspaceId(workspace.getId())
                    .recipientUserId(alice.getId())
                    .aboutUserId(alice.getId())
                    .channel(FeedbackChannel.IN_CHAT)
                    .position(3000)
                    .deliveryState(FeedbackDeliveryState.PREPARED)
                    .source(FeedbackSource.AGENT)
                    .createdAt(Instant.now())
                    .build());

            getOk(FEEDBACK + "?channel=IN_CHAT", workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].deliveryState")
                    .isEqualTo("PREPARED")
                    .jsonPath("$.content[0].bodyPreview")
                    .doesNotExist();
        }

        @Test
        @WithAdminUser
        void filtersByArtifact() {
            persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.DELIVERED, null, "Pull request");
            feedbackRepository.save(Feedback.builder()
                    .agentJobId(job.getId())
                    .workspaceId(workspace.getId())
                    .artifactKind(ArtifactKinds.ISSUE)
                    .artifactId(99L)
                    .recipientUserId(alice.getId())
                    .aboutUserId(alice.getId())
                    .channel(FeedbackChannel.IN_CONTEXT)
                    .position(1)
                    .deliveryState(FeedbackDeliveryState.DELIVERED)
                    .body("Issue")
                    .source(FeedbackSource.AGENT)
                    .createdAt(Instant.now())
                    .deliveredAt(Instant.now())
                    .build());
            feedbackRepository.save(Feedback.builder()
                    .agentJobId(job.getId())
                    .workspaceId(workspace.getId())
                    .artifactKind(ArtifactKinds.ISSUE)
                    .artifactId(100L)
                    .recipientUserId(alice.getId())
                    .aboutUserId(alice.getId())
                    .channel(FeedbackChannel.IN_CONTEXT)
                    .position(2)
                    .deliveryState(FeedbackDeliveryState.DELIVERED)
                    .body("Other issue")
                    .source(FeedbackSource.AGENT)
                    .createdAt(Instant.now())
                    .deliveredAt(Instant.now())
                    .build());

            getOk(FEEDBACK + "?artifactKind=scm.issue", workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(2);

            getOk(FEEDBACK + "?artifactKind=scm.issue&artifactId=99", workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].bodyPreview")
                    .isEqualTo("Issue");

            get(FEEDBACK + "?artifactId=99", workspace.getWorkspaceSlug())
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        void filtersByRecipientAndCreatedAtWindow() {
            Instant from = Instant.parse("2026-01-10T00:00:00Z");
            Instant to = Instant.parse("2026-01-20T00:00:00Z");
            persistUnit(
                    workspace, job, alice, 0, FeedbackDeliveryState.DELIVERED, null, "Before", from.minusSeconds(1));
            persistUnit(workspace, job, alice, 1, FeedbackDeliveryState.DELIVERED, null, "Inside", from);
            persistUnit(workspace, job, bob, 2, FeedbackDeliveryState.DELIVERED, null, "Other recipient", from);
            persistUnit(workspace, job, alice, 3, FeedbackDeliveryState.DELIVERED, null, "At end", to);

            getOk(
                            FEEDBACK + "?recipientUserId={recipient}&from={from}&to={to}",
                            workspace.getWorkspaceSlug(),
                            alice.getId(),
                            from,
                            to)
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].bodyPreview")
                    .isEqualTo("Inside");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "?from=2026-01-20T00:00:00Z&to=2026-01-10T00:00:00Z",
                    "?recipientUserId=-1",
                    "?size=100000",
                    "?page=-1",
                })
        @WithAdminUser
        void rejectsInvalidFeedbackFiltersAndPagination(String query) {
            get(FEEDBACK + query, workspace.getWorkspaceSlug())
                    .expectStatus()
                    .isBadRequest()
                    .expectHeader()
                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo(400);
        }

        @Test
        @WithAdminUser
        void truncatesBodyOnTheListOnly() {
            @Nullable String body = "x".repeat(FeedbackRepository.BODY_PREVIEW_LENGTH + 200);
            Feedback unit = persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.DELIVERED, null, body);

            getOk(FEEDBACK, workspace.getWorkspaceSlug())
                    .jsonPath("$.content[0].bodyTruncated")
                    .isEqualTo(true)
                    .jsonPath("$.content[0].bodyPreview")
                    .isEqualTo(body.substring(0, FeedbackRepository.BODY_PREVIEW_LENGTH));

            getOk(FEEDBACK + "/{id}", workspace.getWorkspaceSlug(), unit.getId())
                    .jsonPath("$.body")
                    .isEqualTo(body);
        }

        /**
         * In a course deployment the workspace admin is the instructor, and an in-app body is the only
         * feedback text whose audience is the developer alone. Run against the real projection, because the
         * withholding lives in the SQL: a mapper-level assertion would pass on a query that selected the body.
         */
        @Test
        @WithAdminUser
        @DisplayName("an operator read cannot return an in-app body, on either route")
        void withholdsAnInAppBodyFromEveryOperatorRoute() {
            String inAppBody = "### You keep shipping untested changes\n\n"
                    + "y".repeat(FeedbackRepository.BODY_PREVIEW_LENGTH + 200);
            Feedback inApp = feedbackRepository.save(Feedback.builder()
                    .agentJobId(job.getId())
                    .workspaceId(workspace.getId())
                    .recipientUserId(alice.getId())
                    .aboutUserId(alice.getId())
                    .channel(FeedbackChannel.IN_APP)
                    .position(7000)
                    .deliveryState(FeedbackDeliveryState.DELIVERED)
                    .body(inAppBody)
                    .source(FeedbackSource.AGENT)
                    .createdAt(Instant.now())
                    .deliveredAt(Instant.now())
                    .build());
            String inContextBody = "z".repeat(FeedbackRepository.BODY_PREVIEW_LENGTH + 200);
            persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.DELIVERED, null, inContextBody);

            getOk(FEEDBACK + "?channel=IN_APP", workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(1)
                    // Everything needed to audit the pipeline still travels — only the words do not.
                    .jsonPath("$.content[0].deliveryState")
                    .isEqualTo("DELIVERED")
                    .jsonPath("$.content[0].recipient.login")
                    .isEqualTo("alice")
                    .jsonPath("$.content[0].bodyPreview")
                    .doesNotExist()
                    .jsonPath("$.content[0].bodyTruncated")
                    .isEqualTo(false);

            getOk(FEEDBACK + "/{id}", workspace.getWorkspaceSlug(), inApp.getId())
                    .jsonPath("$.channel")
                    .isEqualTo("IN_APP")
                    .jsonPath("$.body")
                    .doesNotExist();

            // The positive control: the same projection on a lane the developer can already be read on
            // still previews and still flags truncation, so the assertions above are about the channel.
            getOk(FEEDBACK + "?channel=IN_CONTEXT", workspace.getWorkspaceSlug())
                    .jsonPath("$.content[0].bodyPreview")
                    .isEqualTo(inContextBody.substring(0, FeedbackRepository.BODY_PREVIEW_LENGTH))
                    .jsonPath("$.content[0].bodyTruncated")
                    .isEqualTo(true);
        }

        @Test
        @WithAdminUser
        void detailOfAWithheldUnitCarriesTheComposedBody() {
            PracticeGroup group = persistGroup(workspace, "communication", "Communication");
            practiceA.setGroup(group);
            practiceRepository.save(practiceA);
            UUID observationId = insertProblem(practiceA, job, alice, "Would have flagged", "MAJOR");
            Feedback unit = persistUnit(
                    workspace,
                    job,
                    alice,
                    5000,
                    FeedbackDeliveryState.SUPPRESSED,
                    FeedbackSuppressionReason.ARTIFACT_CLOSED,
                    "## What I would have posted\n\nSomething useful.");
            bind(unit, observationId);

            getOk(FEEDBACK + "/{id}", workspace.getWorkspaceSlug(), unit.getId())
                    .jsonPath("$.deliveryState")
                    .isEqualTo("SUPPRESSED")
                    .jsonPath("$.suppressionReason")
                    .isEqualTo("ARTIFACT_CLOSED")
                    .jsonPath("$.body")
                    .isEqualTo("## What I would have posted\n\nSomething useful.")
                    .jsonPath("$.deliveredAt")
                    .doesNotExist()
                    .jsonPath("$.placements.length()")
                    .isEqualTo(0)
                    .jsonPath("$.observations.length()")
                    .isEqualTo(1)
                    .jsonPath("$.observations[0].summary")
                    .isEqualTo("Would have flagged")
                    .jsonPath("$.observations[0].practiceSlug")
                    .isEqualTo("pr-description-quality")
                    .jsonPath("$.observations[0].group.slug")
                    .isEqualTo("communication")
                    .jsonPath("$.recipient.login")
                    .isEqualTo("alice");
        }

        @Test
        @WithAdminUser
        void detailReturnsObservationsInRenderOrder() {
            Feedback unit = persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.DELIVERED, null, "Body");
            UUID second = insertProblem(practiceA, job, alice, "Second", "MINOR");
            UUID first = insertProblem(practiceA, job, alice, "First", "CRITICAL");
            feedbackObservationRepository.insertIfAbsent(unit.getId(), second, "PRIMARY", 1);
            feedbackObservationRepository.insertIfAbsent(unit.getId(), first, "PRIMARY", 0);

            getOk(FEEDBACK + "/{id}", workspace.getWorkspaceSlug(), unit.getId())
                    .jsonPath("$.observations[0].summary")
                    .isEqualTo("First")
                    .jsonPath("$.observations[1].summary")
                    .isEqualTo("Second");
        }

        @Test
        @WithAdminUser
        void excludesCrossWorkspaceObservationBindings() {
            Feedback unit = persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.DELIVERED, null, "Body");
            UUID foreignObservation = insertProblem(otherPractice, otherJob, bob, "Foreign", "MAJOR");
            bind(unit, foreignObservation);

            getOk(FEEDBACK, workspace.getWorkspaceSlug())
                    .jsonPath("$.content[0].observationCount")
                    .isEqualTo(0);

            getOk(FEEDBACK + "/{id}", workspace.getWorkspaceSlug(), unit.getId())
                    .jsonPath("$.observations.length()")
                    .isEqualTo(0);
        }

        @Test
        @WithAdminUser
        void returnsPlacementsInDisplayOrderWithTheirLocators() {
            Feedback unit = persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.DELIVERED, null, "Body");
            Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
            UUID chatMessageId = UUID.randomUUID();
            feedbackPlacementRepository.save(FeedbackPlacement.builder()
                    .feedback(unit)
                    .placementType(PlacementType.INLINE)
                    .anchorKind(PlacementAnchorKind.LINE)
                    .anchorPath("src/B.java")
                    .anchorStartLine(20)
                    .anchorEndLine(20)
                    .anchorSide(PlacementAnchorSide.NEW)
                    .postedCommentRef("note-b")
                    .createdAt(createdAt)
                    .build());
            feedbackPlacementRepository.save(FeedbackPlacement.builder()
                    .feedback(unit)
                    .placementType(PlacementType.INLINE)
                    .anchorKind(PlacementAnchorKind.LINE)
                    .anchorPath("src/A.java")
                    .anchorStartLine(10)
                    .anchorEndLine(10)
                    .anchorSide(PlacementAnchorSide.NEW)
                    .postedCommentRef("note-a")
                    .createdAt(createdAt)
                    .build());
            feedbackPlacementRepository.save(FeedbackPlacement.builder()
                    .feedback(unit)
                    .placementType(PlacementType.SUMMARY)
                    .postedCommentRef("comment-1")
                    .createdAt(createdAt.plusSeconds(2))
                    .build());
            feedbackPlacementRepository.save(FeedbackPlacement.builder()
                    .feedback(unit)
                    .placementType(PlacementType.CONVERSATION_TURN)
                    .chatMessageId(chatMessageId)
                    .createdAt(createdAt.plusSeconds(1))
                    .build());

            getOk(FEEDBACK + "/{id}", workspace.getWorkspaceSlug(), unit.getId())
                    .jsonPath("$.placements[0].placementType")
                    .isEqualTo("SUMMARY")
                    .jsonPath("$.placements[1].anchorPath")
                    .isEqualTo("src/A.java")
                    .jsonPath("$.placements[2].anchorPath")
                    .isEqualTo("src/B.java")
                    .jsonPath("$.placements[3].placementType")
                    .isEqualTo("CONVERSATION_TURN")
                    .jsonPath("$.placements[3].chatMessageId")
                    .isEqualTo(chatMessageId.toString());
        }

        @Test
        @WithAdminUser
        void pagesConsistentlyUnderAFilter() {
            for (int i = 0; i < 3; i++) {
                persistUnit(workspace, job, alice, i, FeedbackDeliveryState.DELIVERED, null, "Delivered " + i);
            }
            persistUnit(
                    workspace,
                    job,
                    bob,
                    2000,
                    FeedbackDeliveryState.SUPPRESSED,
                    FeedbackSuppressionReason.VOLUME_CAPPED,
                    null);

            getOk(FEEDBACK + "?deliveryState=DELIVERED&size=2&page=1", workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(3)
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.page.number")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].deliveryState")
                    .isEqualTo("DELIVERED");
        }
    }

    private DeliveryPolicyEvaluation policyEvaluation(
            AgentJob sourceJob, @Nullable UUID feedbackId, DeliveryPolicySurface surface) {
        return DeliveryPolicyEvaluation.builder()
                .workspaceId(sourceJob.getWorkspace().getId())
                .agentJobId(sourceJob.getId())
                .feedbackId(feedbackId)
                .admittedRevision(0L)
                .evaluatedRevision(1L)
                .resolverVersion("v1")
                .surface(surface)
                .stage(DeliveryPolicyStage.EGRESS)
                .allowed(false)
                .decisiveReason(FeedbackSuppressionReason.RECIPIENT_OPTED_OUT)
                .checks(OBJECT_MAPPER.readTree("[{\"check\":\"RECIPIENT_CONSENT\",\"status\":\"DENIED\"}]"))
                .facts(OBJECT_MAPPER.createObjectNode())
                .evaluatedAt(Instant.now())
                .build();
    }
}
