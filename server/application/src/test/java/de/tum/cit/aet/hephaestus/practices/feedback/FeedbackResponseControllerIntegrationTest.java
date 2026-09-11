package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResolutionCountsDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.Reaction;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

class FeedbackResponseControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RECURRENCE_KEY = "ck-integration-observation";
    private static final String FEEDBACK_URI = "/workspaces/{workspaceSlug}/practices/feedback/{feedbackId}/response";
    private static final String RESOLUTION_COUNTS_URI =
            "/workspaces/{workspaceSlug}/practices/feedback/resolution-counts";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private ReactionRepository reactionRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    private Workspace workspace;
    private User adminUser;
    private Feedback feedbackUnit;

    @BeforeEach
    void setUpTestData() {
        User owner = persistUser("feedback-owner");
        workspace = createWorkspace("feedback-ws", "Feedback WS", "feedback-org", AccountType.ORG, owner);

        adminUser = ensureAdminMembership(workspace).getUser();

        Practice practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(workspace);
        practice.setSlug("test-practice");
        practice.setName("Test Practice");
        practice.setCriteria("Test description");
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice = practiceRepository.save(practice);

        AgentJob agentJob = new AgentJob();
        agentJob.setWorkspace(workspace);
        agentJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        agentJob.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        agentJob = agentJobRepository.save(agentJob);

        Observation finding = Observation.builder()
                .occurrenceKey("test-key-" + UUID.randomUUID())
                .recurrenceKey(RECURRENCE_KEY)
                .agentJobId(agentJob.getId())
                .workspaceId(workspace.getId())
                .practice(practice)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(42L)
                .aboutUserId(adminUser.getId())
                .summary("Missing error handling")
                .presence(Presence.ABSENT)
                .assessment(Assessment.BAD)
                .severity(Severity.MAJOR)
                .observedAt(Instant.now())
                .build();
        finding = observationRepository.save(finding);

        feedbackUnit = feedbackRepository.save(Feedback.builder()
                .agentJobId(agentJob.getId())
                .workspaceId(workspace.getId())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(42L)
                .recipientUserId(adminUser.getId())
                .aboutUserId(adminUser.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(0)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .deliveredAt(Instant.now())
                .build());

        feedbackObservationRepository.insertIfAbsent(
                feedbackUnit.getId(), finding.getId(), EvidenceRole.PRIMARY.name(), 0);
    }

    @Nested
    @DisplayName("PUT /{feedbackId}/response")
    class SubmitFeedback {

        @Test
        @WithAdminUser
        void usefulnessWithoutResolutionReturns200() {
            var request = new FeedbackResponseRequestDTO(FeedbackUsefulness.HELPFUL, null, null);

            FeedbackResponseDTO response = webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(FeedbackResponseDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(response).isNotNull();
            assertThat(response.usefulness()).isEqualTo(FeedbackUsefulness.HELPFUL);
            assertThat(response.resolution()).isNull();
        }

        @Test
        @WithAdminUser
        void combinedResponseReturns200() {
            var request =
                    new FeedbackResponseRequestDTO(FeedbackUsefulness.HELPFUL, FeedbackResolution.ADDRESSED, null);

            FeedbackResponseDTO response = webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(FeedbackResponseDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(response).isNotNull();
            assertThat(response.usefulness()).isEqualTo(FeedbackUsefulness.HELPFUL);
            assertThat(response.resolution()).isEqualTo(FeedbackResolution.ADDRESSED);
            assertThat(response.feedbackId()).isEqualTo(feedbackUnit.getId());
            assertThat(response.respondedAt()).isNotNull();

            Reaction saved = reactionRepository.findAll().stream()
                    .filter(row -> feedbackUnit.getId().equals(row.getFeedbackId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(saved.getUsefulness()).isEqualTo(FeedbackUsefulness.HELPFUL);
        }

        @Test
        @WithAdminUser
        void disputedWithExplanationReturns200() {
            var request =
                    new FeedbackResponseRequestDTO(null, FeedbackResolution.DISPUTED, "The AI is wrong about this");

            FeedbackResponseDTO response = webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(FeedbackResponseDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(response).isNotNull();
            assertThat(response.resolution()).isEqualTo(FeedbackResolution.DISPUTED);
            assertThat(response.comment()).isEqualTo("The AI is wrong about this");
        }

        @Test
        @WithAdminUser
        void resolutionLookupUsesTheFeedbackObservationBinding() {
            var request = new FeedbackResponseRequestDTO(null, FeedbackResolution.DISPUTED, "Incorrect");

            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            var resolutions = reactionRepository.findCurrentResolutionByRecurrenceKeys(
                    List.of(RECURRENCE_KEY), adminUser.getId(), workspace.getId());
            assertThat(resolutions).singleElement().satisfies(resolution -> {
                assertThat(resolution.getRecurrenceKey()).isEqualTo(RECURRENCE_KEY);
                assertThat(resolution.getResolution()).isEqualTo(FeedbackResolution.DISPUTED.name());
            });
        }

        @Test
        @WithAdminUser
        void disputedWithoutExplanationReturns400() {
            var request = new FeedbackResponseRequestDTO(null, FeedbackResolution.DISPUTED, null);

            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        void appendOnlyCreatesNewRow() {
            var request1 = new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null);
            var request2 = new FeedbackResponseRequestDTO(null, FeedbackResolution.DISPUTED, "Changed my mind");

            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request1)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request2)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            assertThat(reactionRepository.findAll()).hasSize(2);
        }

        @Test
        @WithAdminUser
        void nonExistentFeedbackReturns404() {
            var request = new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null);

            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), UUID.randomUUID())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void nonRecipientReturns404() {
            User mentorUser = persistUser("mentor");
            ensureWorkspaceMembership(workspace, mentorUser, WorkspaceMembership.WorkspaceRole.MEMBER);

            var request = new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null);

            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void subjectButNotRecipientReturns404() {
            User mentorUser = persistUser("mentor");
            ensureWorkspaceMembership(workspace, mentorUser, WorkspaceMembership.WorkspaceRole.MEMBER);

            Feedback aboutMentorDeliveredToAdmin = feedbackRepository.save(Feedback.builder()
                    .agentJobId(feedbackUnit.getAgentJobId())
                    .workspaceId(workspace.getId())
                    .artifactKind(ArtifactKinds.PULL_REQUEST)
                    .artifactId(43L)
                    .recipientUserId(adminUser.getId())
                    .aboutUserId(mentorUser.getId())
                    .channel(FeedbackChannel.IN_CONTEXT)
                    .position(1)
                    .deliveryState(FeedbackDeliveryState.DELIVERED)
                    .source(FeedbackSource.AGENT)
                    .createdAt(Instant.now())
                    .deliveredAt(Instant.now())
                    .build());

            var request = new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null);

            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), aboutMentorDeliveredToAdmin.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        void unauthenticatedMutationRejected() {
            var request = new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null);

            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }
    }

    @Nested
    class GetLatestFeedback {

        @Test
        @WithAdminUser
        @DisplayName("returns 204 when no response has been submitted yet")
        void returns204WhenEmpty() {
            webTestClient
                    .get()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNoContent()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        void returns404ForNonExistentFeedback() {
            webTestClient
                    .get()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), UUID.randomUUID())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithAdminUser
        void returnsLatestAfterMultipleSubmissions() {
            var request1 = new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null);
            var request2 = new FeedbackResponseRequestDTO(null, FeedbackResolution.DISPUTED, "Actually wrong");

            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request1)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request2)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            FeedbackResponseDTO response = webTestClient
                    .get()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(FeedbackResponseDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(response).isNotNull();
            assertThat(response.resolution()).isEqualTo(FeedbackResolution.DISPUTED);
            assertThat(response.comment()).isEqualTo("Actually wrong");
        }

        @Test
        @WithAdminUser
        @DisplayName("PUT clears response dimensions omitted from the replacement")
        void replacesTheCompleteResponse() {
            submit(new FeedbackResponseRequestDTO(null, FeedbackResolution.DISPUTED, "Actually wrong"));
            submit(new FeedbackResponseRequestDTO(FeedbackUsefulness.HELPFUL, null, null));

            FeedbackResponseDTO response = current();

            assertThat(response).isNotNull();
            assertThat(response.usefulness()).isEqualTo(FeedbackUsefulness.HELPFUL);
            assertThat(response.resolution()).isNull();
            assertThat(response.comment()).isNull();
        }

        @Test
        @WithAdminUser
        void resolutionOnlyReplacementClearsUsefulness() {
            submit(new FeedbackResponseRequestDTO(FeedbackUsefulness.HELPFUL, FeedbackResolution.ADDRESSED, null));
            submit(new FeedbackResponseRequestDTO(null, FeedbackResolution.DISPUTED, "Incorrect"));

            FeedbackResponseDTO response = current();

            assertThat(response).isNotNull();
            assertThat(response.usefulness()).isNull();
            assertThat(response.resolution()).isEqualTo(FeedbackResolution.DISPUTED);
            assertThat(response.comment()).isEqualTo("Incorrect");
        }

        @Test
        @WithAdminUser
        @DisplayName("a comment written alongside usefulness alone is still readable")
        void keepsACommentThatCameWithoutAResolution() {
            submit(new FeedbackResponseRequestDTO(FeedbackUsefulness.UNHELPFUL, null, "Missed the point"));

            FeedbackResponseDTO response = current();

            assertThat(response).isNotNull();
            assertThat(response.usefulness()).isEqualTo(FeedbackUsefulness.UNHELPFUL);
            assertThat(response.resolution()).isNull();
            assertThat(response.comment()).isEqualTo("Missed the point");
        }

        @Test
        @WithAdminUser
        void replacingAnAnswerDoesNotKeepItsComment() {
            submit(new FeedbackResponseRequestDTO(null, FeedbackResolution.DISPUTED, "Actually wrong"));
            submit(new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null));

            FeedbackResponseDTO response = current();

            assertThat(response).isNotNull();
            assertThat(response.resolution()).isEqualTo(FeedbackResolution.ADDRESSED);
            assertThat(response.comment()).isNull();
        }

        @Test
        @WithAdminUser
        @DisplayName("withdrawing leaves no answer, and a later one starts clean")
        void withdrawalEndsEverythingBeforeIt() {
            submit(new FeedbackResponseRequestDTO(FeedbackUsefulness.HELPFUL, FeedbackResolution.ADDRESSED, null));
            webTestClient
                    .delete()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNoContent()
                    .expectBody(Void.class);

            webTestClient
                    .get()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNoContent()
                    .expectBody(Void.class);

            submit(new FeedbackResponseRequestDTO(FeedbackUsefulness.UNHELPFUL, null, null));
            FeedbackResponseDTO response = current();

            assertThat(response).isNotNull();
            assertThat(response.usefulness()).isEqualTo(FeedbackUsefulness.UNHELPFUL);
            assertThat(response.resolution()).isNull();
        }

        private void submit(FeedbackResponseRequestDTO request) {
            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
        }

        private @org.jspecify.annotations.Nullable FeedbackResponseDTO current() {
            return webTestClient
                    .get()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(FeedbackResponseDTO.class)
                    .returnResult()
                    .getResponseBody();
        }
    }

    @Nested
    class WorkspaceIsolation {

        @Test
        @WithAdminUser
        @DisplayName("resolution counts exclude feedback from other workspaces")
        void resolutionCountsAreScopedToWorkspace() {
            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            User owner2 = persistUser("other-ws-owner");
            Workspace otherWorkspace = createWorkspace("other-ws", "Other WS", "other-org", AccountType.ORG, owner2);
            ensureWorkspaceMembership(otherWorkspace, adminUser, WorkspaceMembership.WorkspaceRole.ADMIN);

            FeedbackResolutionCountsDTO response = webTestClient
                    .get()
                    .uri(RESOLUTION_COUNTS_URI, otherWorkspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(FeedbackResolutionCountsDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(response).isNotNull();
            assertThat(response.addressed()).isZero();
            assertThat(response.disputed()).isZero();
            assertThat(response.notApplicable()).isZero();
        }
    }

    @Nested
    class GetResolutionCounts {

        @Test
        @WithAdminUser
        void returnsZerosWhenNoFeedback() {
            FeedbackResolutionCountsDTO response = webTestClient
                    .get()
                    .uri(RESOLUTION_COUNTS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(FeedbackResolutionCountsDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(response).isNotNull();
            assertThat(response.addressed()).isZero();
            assertThat(response.disputed()).isZero();
            assertThat(response.notApplicable()).isZero();
        }

        @Test
        @WithAdminUser
        void returnsCorrectCounts() {
            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new FeedbackResponseRequestDTO(null, FeedbackResolution.DISPUTED, "Wrong detection"))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            FeedbackResolutionCountsDTO response = webTestClient
                    .get()
                    .uri(RESOLUTION_COUNTS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(FeedbackResolutionCountsDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(response).isNotNull();
            assertThat(response.addressed()).isZero();
            assertThat(response.disputed()).isEqualTo(1);
            assertThat(response.notApplicable()).isZero();
        }

        @Test
        @WithAdminUser
        void countsDistinctFeedbackUnitsNotHistoricalRows() {
            Feedback secondFeedbackUnit = feedbackRepository.save(Feedback.builder()
                    .agentJobId(feedbackUnit.getAgentJobId())
                    .workspaceId(workspace.getId())
                    .artifactKind(ArtifactKinds.PULL_REQUEST)
                    .artifactId(42L)
                    .recipientUserId(feedbackUnit.getRecipientUserId())
                    .aboutUserId(feedbackUnit.getAboutUserId())
                    .channel(FeedbackChannel.IN_CONTEXT)
                    .position(1)
                    .deliveryState(FeedbackDeliveryState.DELIVERED)
                    .source(FeedbackSource.AGENT)
                    .createdAt(Instant.now())
                    .deliveredAt(Instant.now())
                    .build());

            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), feedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
            webTestClient
                    .put()
                    .uri(FEEDBACK_URI, workspace.getWorkspaceSlug(), secondFeedbackUnit.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            FeedbackResolutionCountsDTO response = webTestClient
                    .get()
                    .uri(RESOLUTION_COUNTS_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(FeedbackResolutionCountsDTO.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(response).isNotNull();
            assertThat(response.addressed()).isEqualTo(2);
        }
    }
}
