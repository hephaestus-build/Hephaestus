package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import static org.assertj.core.api.Assertions.assertThat;

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
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationInvalidationRepository;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
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
import tools.jackson.databind.JsonNode;
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

    @Autowired
    private ObservationInvalidationRepository invalidationRepository;

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
        agentJob.setEvidenceSnapshot(OBJECT_MAPPER.valueToTree(Map.of("manifest", Map.of("contractVersion", "1.2.0"))));
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
                .jsonPath(path + ".kind")
                .isEqualTo("scm.pull_request")
                .jsonPath(path + ".id")
                .isEqualTo("7")
                .jsonPath(path + ".label")
                .isEqualTo("#42")
                .jsonPath(path + ".provider")
                .isEqualTo("GITHUB")
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

        /** Feedback waiting on an admin, or rejected by one, was still composed from the observation. */
        @Test
        @WithAdminUser
        void shouldCountFeedbackAwaitingApprovalAndDiscardedWhenBoundToTheObservation() {
            UUID observationId = insertProblem(practiceA, job, alice, "Needs approval", "MAJOR");
            bind(
                    persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.AWAITING_APPROVAL, null, "Proposed"),
                    observationId);
            bind(
                    persistUnit(workspace, job, alice, 1, FeedbackDeliveryState.DISCARDED, null, "Rejected"),
                    observationId);

            getOk(OBSERVATIONS, workspace.getWorkspaceSlug())
                    .jsonPath("$.content[0].id")
                    .isEqualTo(observationId.toString())
                    .jsonPath("$.content[0].feedback.awaitingApproval")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].feedback.discarded")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].feedback.prepared")
                    .isEqualTo(0)
                    .jsonPath("$.content[0].feedback.suppressed")
                    .isEqualTo(0);
        }

        @Test
        @WithAdminUser
        void shouldListOnlyObservationsWithThatOutcomeWhenFilteredByOutcome() {
            Instant now = Instant.now();
            UUID presentAndGood =
                    insertObservation(practiceA, job, alice, "Good present", "PRESENT", "GOOD", null, 0.8f, 7L, now);
            UUID absentAndBad =
                    insertObservation(practiceA, job, alice, "Bad absent", "ABSENT", "BAD", null, 0.8f, 7L, now);
            UUID absentAndGood = insertProblem(practiceA, job, alice, "Good absent", "MAJOR");
            UUID presentAndBad =
                    insertObservation(practiceB, job, bob, "Bad present", "PRESENT", "BAD", "MINOR", 0.8f, 7L, now);
            insertObservation(practiceA, job, alice, "Not applicable", "NOT_APPLICABLE", null, null, 0.8f, 7L, now);

            expectListed("?outcome=POSITIVE", presentAndGood, absentAndBad);
            expectListed("?outcome=NEGATIVE", absentAndGood, presentAndBad);
            expectListed(
                    "?outcome=POSITIVE&outcome=NEGATIVE", presentAndGood, absentAndBad, absentAndGood, presentAndBad);
        }

        @Test
        @WithAdminUser
        void shouldListOnlyObservationsMarkedIncorrectWhenFilteredByInvalidated() {
            UUID markedIncorrect = insertProblem(practiceA, job, alice, "Marked incorrect", "MAJOR");
            UUID restored = insertProblem(practiceA, job, alice, "Restored", "MAJOR");
            UUID standing = insertProblem(practiceB, job, bob, "Standing", "MINOR");
            invalidate(markedIncorrect);
            ObservationInvalidation correction = invalidate(restored);
            correction.restore(1L, "It was right", Instant.now());
            invalidationRepository.save(correction);

            expectListed("?invalidated=true", markedIncorrect);
            expectListed("?invalidated=false", restored, standing);
            expectListed("", markedIncorrect, restored, standing);
        }

        /** Each overview count opens the observation list on exactly the rows it counted. */
        @Test
        @WithAdminUser
        void shouldListAsManyObservationsAsTheOverviewCountsWhenFilteredToTheSameRange() {
            Instant inside = Instant.parse("2026-02-03T12:00:00Z");
            insertObservation(practiceA, job, alice, "Strength", "PRESENT", "GOOD", null, 0.8f, 7L, inside);
            insertObservation(practiceB, job, bob, "Another strength", "ABSENT", "BAD", null, 0.8f, 7L, inside);
            invalidate(
                    insertObservation(practiceA, job, alice, "Problem", "ABSENT", "GOOD", "MAJOR", 0.8f, 7L, inside));
            insertObservation(practiceB, job, bob, "Not applicable", "NOT_APPLICABLE", null, null, 0.8f, 7L, inside);
            // On the exclusive upper bound and just before the lower one: counted by neither.
            invalidate(insertObservation(
                    practiceA,
                    job,
                    alice,
                    "Late",
                    "PRESENT",
                    "GOOD",
                    null,
                    0.8f,
                    7L,
                    Instant.parse("2026-02-08T00:00:00Z")));
            insertObservation(
                    practiceA,
                    job,
                    alice,
                    "Early",
                    "ABSENT",
                    "GOOD",
                    "MAJOR",
                    0.8f,
                    7L,
                    Instant.parse("2026-01-31T23:59:59Z"));
            String range = "from=2026-02-01T00:00:00Z&to=2026-02-08T00:00:00Z";

            WebTestClient.BodyContentSpec overview =
                    getOk("/workspaces/{slug}/practices/reviews/overview?" + range, workspace.getWorkspaceSlug());
            overview.jsonPath("$.observations.strengths")
                    .isEqualTo(2)
                    .jsonPath("$.observations.problems")
                    .isEqualTo(1)
                    .jsonPath("$.observationsInvalidated")
                    .isEqualTo(1)
                    .jsonPath("$.practices[0].practiceSlug")
                    .isEqualTo(practiceA.getSlug())
                    .jsonPath("$.practices[0].observations.strengths")
                    .isEqualTo(1)
                    .jsonPath("$.practices[0].observations.problems")
                    .isEqualTo(1)
                    .jsonPath("$.practices[0].observationsInvalidated")
                    .isEqualTo(1)
                    .jsonPath("$.practices[1].practiceSlug")
                    .isEqualTo(practiceB.getSlug())
                    .jsonPath("$.practices[1].observations.strengths")
                    .isEqualTo(1);
            expectTotal("?outcome=POSITIVE&" + range, 2);
            expectTotal("?outcome=NEGATIVE&" + range, 1);
            expectTotal("?invalidated=true&" + range, 1);
            String practiceARange = "practiceSlug=" + practiceA.getSlug() + "&" + range;
            expectTotal("?outcome=POSITIVE&" + practiceARange, 1);
            expectTotal("?outcome=NEGATIVE&" + practiceARange, 1);
            expectTotal("?invalidated=true&" + practiceARange, 1);
            expectTotal("?outcome=POSITIVE&practiceSlug=" + practiceB.getSlug() + "&" + range, 1);
        }

        private void expectListed(String query, UUID... ids) {
            getOk(OBSERVATIONS + query, workspace.getWorkspaceSlug())
                    .jsonPath("$.content[*].id")
                    .value(listed -> assertThat(listed)
                            .asInstanceOf(InstanceOfAssertFactories.LIST)
                            .containsExactlyInAnyOrder(
                                    Arrays.stream(ids).map(UUID::toString).toArray()));
        }

        private void expectTotal(String query, int total) {
            getOk(OBSERVATIONS + query, workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(total);
        }

        private ObservationInvalidation invalidate(UUID observationId) {
            return invalidationRepository.save(new ObservationInvalidation(
                    observationRepository
                            .findByIdAndWorkspaceId(observationId, workspace.getId())
                            .orElseThrow(),
                    1L,
                    "Wrong when made",
                    Instant.now()));
        }

        @Test
        @WithAdminUser
        void reportsZeroFeedbackCountsWhenNeverBound() {
            insertProblem(practiceA, job, alice, "Orphan", "MAJOR");

            getOk(OBSERVATIONS, workspace.getWorkspaceSlug())
                    .jsonPath("$.content[0].feedback.prepared")
                    .isEqualTo(0)
                    .jsonPath("$.content[0].feedback.delivered")
                    .isEqualTo(0)
                    .jsonPath("$.content[0].feedback.superseded")
                    .isEqualTo(0)
                    .jsonPath("$.content[0].feedback.suppressed")
                    .isEqualTo(0)
                    .jsonPath("$.content[0].feedback.failed")
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
                    .jsonPath("$.content[0].feedback.delivered")
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
                    "$.content[0].reviewedWork",
                    workspace.getWorkspaceSlug(),
                    artifactId);
            expectResolvedPullRequestArtifact(
                    OBSERVATIONS + "/{id}", "$.reviewedWork", workspace.getWorkspaceSlug(), observationId);
            expectResolvedPullRequestArtifact(
                    FEEDBACK + "?artifactKind=scm.pull_request&artifactId={id}",
                    "$.content[0].reviewedWork",
                    workspace.getWorkspaceSlug(),
                    artifactId);
            expectResolvedPullRequestArtifact(
                    FEEDBACK + "/{id}", "$.reviewedWork", workspace.getWorkspaceSlug(), feedback.getId());
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
                    .jsonPath("$.reviewedWork.kind")
                    .isEqualTo("scm.pull_request")
                    .jsonPath("$.reviewedWork.id")
                    .isEqualTo("812")
                    .jsonPath("$.reviewedWork.label")
                    .isEqualTo("Pull request")
                    // The other workspace's run named the work; none of what it named may come back here.
                    .jsonPath("$.reviewedWork.title")
                    .doesNotExist()
                    .jsonPath("$.reviewedWork.provider")
                    .doesNotExist()
                    .jsonPath("$.reviewedWork.repositoryName")
                    .doesNotExist()
                    .jsonPath("$.reviewedWork.url")
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
                    .jsonPath("$.reviewedWork.kind")
                    .isEqualTo("chat.conversation_thread")
                    .jsonPath("$.reviewedWork.id")
                    .isEqualTo("812")
                    .jsonPath("$.reviewedWork.label")
                    .isEqualTo("Conversation")
                    .jsonPath("$.reviewedWork.provider")
                    .doesNotExist()
                    .jsonPath("$.reviewedWork.url")
                    .doesNotExist();
        }
    }

    @Nested
    @DisplayName("Feedback ledger")
    class Ledger {

        /** Each overview feedback count opens the feedback list, filtered to its one delivery state, on the same rows. */
        @Test
        @WithAdminUser
        void shouldListAsMuchFeedbackAsTheOverviewCountsWhenFilteredToTheSameStateAndRange() {
            Instant inside = Instant.parse("2026-02-03T12:00:00Z");
            persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.PARTIALLY_DELIVERED, null, "Half", inside);
            persistUnit(
                    workspace,
                    job,
                    alice,
                    1,
                    FeedbackDeliveryState.PARTIALLY_DELIVERED,
                    FeedbackSuppressionReason.VOLUME_CAPPED,
                    "Half, rest suppressed",
                    inside);
            persistUnit(workspace, job, alice, 2, FeedbackDeliveryState.PARTIALLY_FAILED, null, "Half failed", inside);
            persistUnit(workspace, job, alice, 3, FeedbackDeliveryState.PREPARED, null, "Ready", inside);
            // On the exclusive upper bound and just before the lower one: counted by neither.
            persistUnit(
                    workspace,
                    job,
                    alice,
                    4,
                    FeedbackDeliveryState.PARTIALLY_DELIVERED,
                    null,
                    "Late",
                    Instant.parse("2026-02-08T00:00:00Z"));
            persistUnit(
                    workspace,
                    job,
                    alice,
                    5,
                    FeedbackDeliveryState.PARTIALLY_FAILED,
                    null,
                    "Early",
                    Instant.parse("2026-01-31T23:59:59Z"));
            String range = "from=2026-02-01T00:00:00Z&to=2026-02-08T00:00:00Z";

            getOk("/workspaces/{slug}/practices/reviews/overview?" + range, workspace.getWorkspaceSlug())
                    .jsonPath("$.feedback.partiallyDelivered")
                    .isEqualTo(2)
                    .jsonPath("$.feedback.partiallyFailed")
                    .isEqualTo(1)
                    .jsonPath("$.feedback.prepared")
                    .isEqualTo(1)
                    .jsonPath("$.feedback.suppressed")
                    .isEqualTo(0)
                    .jsonPath("$.feedback.failed")
                    .isEqualTo(0);
            expectFeedbackTotal("?deliveryState=PARTIALLY_DELIVERED&" + range, 2);
            expectFeedbackTotal("?deliveryState=PARTIALLY_FAILED&" + range, 1);
            expectFeedbackTotal("?deliveryState=PREPARED&" + range, 1);
            expectFeedbackTotal("?deliveryState=SUPPRESSED&" + range, 0);
            expectFeedbackTotal("?deliveryState=FAILED&" + range, 0);
        }

        /**
         * A practice's overview feedback counts open the feedback list filtered to that practice, the same range and
         * one delivery state, on the same rows.
         */
        @Test
        @WithAdminUser
        void shouldListAsMuchFeedbackAsEachPracticeCountsWhenFilteredToThePracticeStateAndRange() {
            Instant inside = Instant.parse("2026-02-03T12:00:00Z");
            UUID firstA = insertProblem(practiceA, job, alice, "First A", "MAJOR");
            UUID secondA = insertProblem(practiceA, job, alice, "Second A", "MAJOR");
            UUID onlyB = insertProblem(practiceB, job, bob, "Only B", "MAJOR");
            Feedback fusedA =
                    persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.PARTIALLY_DELIVERED, null, "A", inside);
            bind(fusedA, firstA);
            bind(fusedA, secondA);
            Feedback acrossBoth =
                    persistUnit(workspace, job, alice, 1, FeedbackDeliveryState.DELIVERED, null, "A and B", inside);
            bind(acrossBoth, firstA);
            bind(acrossBoth, onlyB);
            bind(persistUnit(workspace, job, bob, 2, FeedbackDeliveryState.PREPARED, null, "B", inside), onlyB);
            persistUnit(workspace, job, bob, 3, FeedbackDeliveryState.SUPPRESSED, null, "Unbound", inside);
            bind(
                    persistUnit(
                            workspace,
                            job,
                            alice,
                            4,
                            FeedbackDeliveryState.DELIVERED,
                            null,
                            "Late",
                            Instant.parse("2026-02-08T00:00:00Z")),
                    firstA);
            String range = "from=2026-02-01T00:00:00Z&to=2026-02-08T00:00:00Z";

            JsonNode practices = OBJECT_MAPPER
                    .readTree(Objects.requireNonNull(getOk(
                                    "/workspaces/{slug}/practices/reviews/overview?" + range,
                                    workspace.getWorkspaceSlug())
                            .returnResult()
                            .getResponseBody()))
                    .get("practices");
            Map<String, JsonNode> feedbackBySlug = new HashMap<>();
            practices.forEach(
                    practice -> feedbackBySlug.put(practice.get("practiceSlug").asString(), practice.get("feedback")));
            JsonNode countsA = Objects.requireNonNull(feedbackBySlug.get(practiceA.getSlug()));
            JsonNode countsB = Objects.requireNonNull(feedbackBySlug.get(practiceB.getSlug()));
            assertThat(countsA.path("partiallyDelivered").asLong()).isEqualTo(1);
            assertThat(countsA.path("delivered").asLong()).isEqualTo(1);
            assertThat(countsB.path("delivered").asLong()).isEqualTo(1);
            assertThat(countsB.path("prepared").asLong()).isEqualTo(1);
            feedbackBySlug.forEach((slug, counts) -> {
                for (FeedbackDeliveryState state : FeedbackDeliveryState.values()) {
                    expectFeedbackTotal(
                            "?practiceSlug=" + slug + "&deliveryState=" + state + "&" + range,
                            counts.path(camelCase(state.name())).asInt());
                }
            });
        }

        @Test
        @WithAdminUser
        void shouldListFeedbackOldestFirstWhenSortedOldest() {
            Instant at = Instant.parse("2026-02-03T12:00:00Z");
            Feedback middle = persistUnit(workspace, job, alice, 0, FeedbackDeliveryState.PREPARED, null, "Middle", at);
            Feedback oldest = persistUnit(
                    workspace, job, alice, 1, FeedbackDeliveryState.PREPARED, null, "Oldest", at.minusSeconds(60));
            Feedback newest = persistUnit(
                    workspace, job, alice, 2, FeedbackDeliveryState.PREPARED, null, "Newest", at.plusSeconds(60));

            getOk(FEEDBACK, workspace.getWorkspaceSlug())
                    .jsonPath("$.content[*].id")
                    .isEqualTo(List.of(
                            newest.getId().toString(),
                            middle.getId().toString(),
                            oldest.getId().toString()));
            getOk(FEEDBACK + "?sort=OLDEST", workspace.getWorkspaceSlug())
                    .jsonPath("$.content[*].id")
                    .isEqualTo(List.of(
                            oldest.getId().toString(),
                            middle.getId().toString(),
                            newest.getId().toString()));
        }

        private static String camelCase(String constant) {
            String[] words = constant.toLowerCase(Locale.ROOT).split("_");
            StringBuilder result = new StringBuilder(words[0]);
            for (int i = 1; i < words.length; i++) {
                result.append(words[i].substring(0, 1).toUpperCase(Locale.ROOT)).append(words[i].substring(1));
            }
            return result.toString();
        }

        private void expectFeedbackTotal(String query, int total) {
            getOk(FEEDBACK + query, workspace.getWorkspaceSlug())
                    .jsonPath("$.page.totalElements")
                    .isEqualTo(total);
        }

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

    @Nested
    @DisplayName("Observation validity")
    class ObservationValidity {

        private static final String VALIDITY = OBSERVATIONS + "/{id}/validity";

        private WebTestClient.ResponseSpec patchValidity(
                Workspace ws, UUID observationId, boolean valid, String reason) {
            return webTestClient
                    .patch()
                    .uri(VALIDITY, ws.getWorkspaceSlug(), observationId)
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("valid", valid, "reason", reason))
                    .exchange();
        }

        @Test
        @WithAdminUser
        void shouldInvalidateOnlyTheSelectedObservationWithItsActorAndReason() {
            UUID wrong = insertProblem(practiceA, job, alice, "Closed issue #1 already", "MINOR");
            UUID sibling = insertProblem(practiceB, job, alice, "Sibling claim", "MINOR");

            patchValidity(workspace, wrong, false, "  Issue #1 was still open  ")
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.summary")
                    .isEqualTo("Closed issue #1 already")
                    .jsonPath("$.invalidations.length()")
                    .isEqualTo(1)
                    .jsonPath("$.invalidations[0].reason")
                    .isEqualTo("Issue #1 was still open")
                    .jsonPath("$.invalidations[0].invalidatedBy")
                    .isEqualTo("admin")
                    .jsonPath("$.invalidations[0].restoredAt")
                    .doesNotExist();

            ObservationInvalidation recorded =
                    invalidationRepository.findActive(workspace.getId(), wrong).orElseThrow();
            assertThat(recorded.getInvalidatedByAccountId()).isNotNull();
            assertThat(invalidationRepository.findHistory(workspace.getId(), sibling))
                    .isEmpty();
            assertThat(observationRepository
                            .findByIdAndWorkspaceId(wrong, workspace.getId())
                            .orElseThrow()
                            .getSummary())
                    .isEqualTo("Closed issue #1 already");
            getOk(OBSERVATIONS + "?agentJobId=" + job.getId(), workspace.getWorkspaceSlug())
                    .jsonPath("$.content[?(@.id == '%s')].invalidatedAt".formatted(wrong))
                    .isNotEmpty()
                    .jsonPath("$.content[?(@.id == '%s')].invalidatedAt".formatted(sibling))
                    .doesNotExist();
        }

        @Test
        @WithAdminUser
        void shouldStopFeedbackNobodyHasReceivedAndKeepTheDeliveredRecord() {
            UUID wrong = insertProblem(practiceA, job, alice, "Wrong claim", "MINOR");
            UUID other = insertProblem(practiceB, job, alice, "Other claim", "MINOR");
            Feedback proposal =
                    persistUnit(workspace, job, alice, 1, FeedbackDeliveryState.AWAITING_APPROVAL, null, "Proposal");
            Feedback prepared = persistUnit(workspace, job, alice, 2, FeedbackDeliveryState.PREPARED, null, "Prepared");
            Feedback delivered =
                    persistUnit(workspace, job, alice, 3, FeedbackDeliveryState.DELIVERED, null, "Delivered");
            Feedback unrelated =
                    persistUnit(workspace, job, alice, 4, FeedbackDeliveryState.PREPARED, null, "Unrelated");
            bind(proposal, wrong);
            bind(prepared, wrong);
            bind(delivered, wrong);
            bind(unrelated, other);

            patchValidity(workspace, wrong, false, "Wrong when made")
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            for (Feedback stopped : List.of(proposal, prepared)) {
                Feedback reread = feedbackRepository
                        .findByIdAndWorkspaceId(stopped.getId(), workspace.getId())
                        .orElseThrow();
                assertThat(reread.getDeliveryState()).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
                assertThat(reread.getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.OBSERVATION_INVALIDATED);
            }
            assertThat(feedbackRepository
                            .findByIdAndWorkspaceId(delivered.getId(), workspace.getId())
                            .orElseThrow()
                            .getDeliveryState())
                    .isEqualTo(FeedbackDeliveryState.DELIVERED);
            assertThat(feedbackRepository
                            .findByIdAndWorkspaceId(unrelated.getId(), workspace.getId())
                            .orElseThrow()
                            .getDeliveryState())
                    .isEqualTo(FeedbackDeliveryState.PREPARED);
        }

        @Test
        @WithAdminUser
        void shouldRefuseRepeatedTransitionsAndKeepEveryCorrectionInTheHistory() {
            UUID observation = insertProblem(practiceA, job, alice, "Contested claim", "MINOR");

            patchValidity(workspace, observation, false, "First look")
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
            patchValidity(workspace, observation, false, "Again")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody(Void.class);
            patchValidity(workspace, observation, true, "The claim was right after all")
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.invalidations[0].restorationReason")
                    .isEqualTo("The claim was right after all")
                    .jsonPath("$.invalidations[0].restoredBy")
                    .isEqualTo("admin");
            patchValidity(workspace, observation, true, "Again")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody(Void.class);
            patchValidity(workspace, observation, false, "Second look")
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.invalidations.length()")
                    .isEqualTo(2)
                    .jsonPath("$.invalidations[0].reason")
                    .isEqualTo("Second look")
                    .jsonPath("$.invalidations[1].reason")
                    .isEqualTo("First look");
        }

        @Test
        @WithAdminUser
        void shouldNotFindOrChangeAnotherWorkspacesObservation() {
            UUID foreign = insertProblem(otherPractice, otherJob, alice, "Other tenant claim", "MINOR");

            patchValidity(workspace, foreign, false, "Not yours")
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);

            assertThat(invalidationRepository.findHistory(otherWorkspace.getId(), foreign))
                    .isEmpty();
        }

        @Test
        @WithAdminUser
        void shouldRequireAReason() {
            UUID observation = insertProblem(practiceA, job, alice, "Claim", "MINOR");

            patchValidity(workspace, observation, false, "   ")
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);

            assertThat(invalidationRepository.findHistory(workspace.getId(), observation))
                    .isEmpty();
        }

        @Test
        @WithUser
        void shouldForbidAWorkspaceMember() {
            UUID observation = insertProblem(practiceA, job, alice, "Claim", "MINOR");

            patchValidity(workspace, observation, false, "Members cannot correct")
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
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
