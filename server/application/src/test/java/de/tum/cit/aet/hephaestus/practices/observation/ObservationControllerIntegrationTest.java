package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
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
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

class ObservationControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BASE_URI = "/workspaces/{workspaceSlug}/practices/observations";
    private static final String DIFF_EVIDENCE_JSON =
            "{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\",\"artifactPath\":\"inputs/context/diff.patch\",\"path\":\"src/Main.java\",\"side\":\"NEW\",\"startLine\":42,\"endLine\":42,\"quote\":\"example\",\"quoteRedacted\":false}]}";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private PracticeGroupRepository groupRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    private Workspace workspace;
    private Practice practiceA;
    private Practice practiceB;
    private AgentJob agentJob;
    private User developer; // login = "testuser" to match @WithUser

    @BeforeEach
    void setUpWorkspace() {
        User owner = persistUser("finding-owner");
        workspace = createWorkspace("finding-ws", "Finding WS", "finding-org", AccountType.ORG, owner);

        // Create developer with login "testuser" matching @WithUser default
        developer = persistUser("testuser");
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);

        practiceA = persistPractice("pr-description-quality", "PR Description Quality");
        practiceB = persistPractice("code-review-thoroughness", "Code Review Thoroughness");

        agentJob = new AgentJob();
        agentJob.setWorkspace(workspace);
        agentJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        agentJob.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        agentJob.setEvidenceSnapshot(OBJECT_MAPPER.valueToTree(Map.of("manifest", Map.of("contractVersion", "1.0.0"))));
        agentJob = agentJobRepository.save(agentJob);
    }

    private Practice persistPractice(String slug, String name) {
        Practice practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(workspace);
        practice.setSlug(slug);
        practice.setName(name);
        practice.setCriteria("Description for " + slug);
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        practice = practiceRepository.saveAndFlush(practice);
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(practice, 1));
        practice.setCurrentRevision(revision);
        return practiceRepository.saveAndFlush(practice);
    }

    private UUID insertFinding(
            Practice practice,
            User user,
            String title,
            String presence,
            @Nullable String severity,
            float confidence,
            String artifactKind,
            Long artifactId,
            Instant detectedAt) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                "key-" + id,
                agentJob.getId(),
                agentJob.getWorkspace().getId(),
                practice.getId(),
                practice.getCurrentRevision().getId(),
                artifactKind,
                artifactId,
                user.getId(),
                title,
                "NOT_APPLICABLE".equals(presence) ? "NOT_APPLICABLE" : "ASSESSED",
                "NOT_APPLICABLE".equals(presence) ? null : presence,
                assessmentFor(presence),
                "ABSENT".equals(presence) ? severity : null,
                DIFF_EVIDENCE_JSON,
                "Test reasoning for " + title,
                null,
                detectedAt,
                "LIVE");
        return id;
    }

    /** Presence-derived valence: PRESENT is a strength (GOOD), ABSENT a problem (BAD), NOT_APPLICABLE none. */
    private static @Nullable String assessmentFor(String presence) {
        if ("NOT_APPLICABLE".equals(presence)) {
            return null;
        }
        return "GOOD";
    }

    /** Binds delivered guidance because observations do not own advice. */
    private void deliverFeedbackFor(UUID findingId, String body, Instant createdAt) {
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
                .createdAt(createdAt)
                .build());
        feedbackObservationRepository.insertIfAbsent(feedback.getId(), findingId, "PRIMARY", 0);
    }

    // GET /practices/observations

    @Nested
    class ListFindings {

        @Test
        @WithUser
        void shouldReturnEmptyPage() {
            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(0)
                    .jsonPath("$.totalElements")
                    .isEqualTo(0);
        }

        @Test
        @WithUser
        void shouldReturnOnlyOwnFindings() {
            Instant now = Instant.now();
            insertFinding(practiceA, developer, "My finding", "PRESENT", "INFO", 0.9f, "scm.pull_request", 1L, now);

            // Other user's finding should NOT appear
            User otherUser = persistUser("other-user");
            insertFinding(practiceA, otherUser, "Other finding", "ABSENT", "MAJOR", 0.8f, "scm.pull_request", 2L, now);

            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("My finding")
                    .jsonPath("$.totalElements")
                    .isEqualTo(1);
        }

        @Test
        @WithUser
        void shouldFilterByPracticeSlug() {
            Instant now = Instant.now();
            insertFinding(practiceA, developer, "Practice A", "PRESENT", "INFO", 0.9f, "scm.pull_request", 1L, now);
            insertFinding(practiceB, developer, "Practice B", "ABSENT", "MAJOR", 0.8f, "scm.pull_request", 2L, now);

            webTestClient
                    .get()
                    .uri(BASE_URI + "?practiceSlug={slug}", workspace.getWorkspaceSlug(), practiceA.getSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Practice A")
                    .jsonPath("$.content[0].practiceSlug")
                    .isEqualTo("pr-description-quality");
        }

        @Test
        @WithUser
        @DisplayName("filters by the practice group the observed practice belongs to")
        void shouldFilterByGroupSlug() {
            PracticeGroup group = new PracticeGroup();
            group.setWorkspace(workspace);
            group.setSlug("review-communication");
            group.setName("Review Communication");
            group = groupRepository.save(group);
            practiceA.setGroup(group);
            practiceRepository.save(practiceA);

            Instant now = Instant.now();
            insertFinding(practiceA, developer, "In group", "PRESENT", "INFO", 0.9f, "scm.pull_request", 1L, now);
            // practiceB has NO group — the LEFT JOIN must keep it visible when no filter is set.
            insertFinding(practiceB, developer, "No group", "ABSENT", "MAJOR", 0.8f, "scm.pull_request", 2L, now);

            webTestClient
                    .get()
                    .uri(BASE_URI + "?groupSlug={groupSlug}", workspace.getWorkspaceSlug(), "review-communication")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("In group");

            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(2);
        }

        @Test
        @WithUser
        @DisplayName("filters by artifact kind so a single integration's events can be shown")
        void shouldFilterByArtifactKinds() {
            Instant now = Instant.now();
            insertFinding(practiceA, developer, "From a PR", "PRESENT", "INFO", 0.9f, "scm.pull_request", 1L, now);
            insertFinding(
                    practiceA, developer, "From Slack", "ABSENT", "MINOR", 0.8f, "chat.conversation_thread", 2L, now);

            webTestClient
                    .get()
                    .uri(BASE_URI + "?artifactKinds=chat.conversation_thread", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("From Slack");
        }

        @Test
        @WithUser
        @DisplayName("filters gaps by severity while keeping strengths visible")
        void shouldFilterBySeverities() {
            Instant now = Instant.now();
            insertFinding(practiceA, developer, "Major gap", "ABSENT", "MAJOR", 0.9f, "scm.pull_request", 1L, now);
            insertFinding(practiceA, developer, "Minor nit", "ABSENT", "MINOR", 0.9f, "scm.pull_request", 2L, now);
            insertFinding(
                    practiceA,
                    developer,
                    "Recent strength",
                    "PRESENT",
                    null,
                    0.9f,
                    "scm.pull_request",
                    3L,
                    now.minus(1, ChronoUnit.DAYS));

            webTestClient
                    .get()
                    .uri(BASE_URI + "?severities=MAJOR&severities=CRITICAL", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(2)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Major gap")
                    .jsonPath("$.content[1].summary")
                    .isEqualTo("Recent strength");
        }

        @Test
        @WithUser
        @DisplayName("sorts most-severe-first when sort=SEVERITY, ties broken newest-first")
        void shouldSortBySeverity() {
            Instant now = Instant.now();
            insertFinding(practiceA, developer, "Newest minor", "ABSENT", "MINOR", 0.9f, "scm.pull_request", 1L, now);
            insertFinding(
                    practiceA,
                    developer,
                    "Older critical",
                    "ABSENT",
                    "CRITICAL",
                    0.9f,
                    "scm.pull_request",
                    2L,
                    now.minus(3, ChronoUnit.DAYS));
            insertFinding(
                    practiceA,
                    developer,
                    "Strength without severity",
                    "PRESENT",
                    null,
                    0.9f,
                    "scm.pull_request",
                    3L,
                    now.minus(1, ChronoUnit.DAYS));

            webTestClient
                    .get()
                    .uri(BASE_URI + "?sort=SEVERITY", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Older critical")
                    .jsonPath("$.content[1].summary")
                    .isEqualTo("Newest minor")
                    .jsonPath("$.content[2].summary")
                    .isEqualTo("Strength without severity");
        }

        @Test
        @WithUser
        @DisplayName("reverses the severity order with direction=ASC (strengths lead)")
        void shouldSortBySeverityAscending() {
            Instant now = Instant.now();
            insertFinding(
                    practiceA, developer, "Critical gap", "ABSENT", "CRITICAL", 0.9f, "scm.pull_request", 1L, now);
            insertFinding(practiceA, developer, "Minor nit", "ABSENT", "MINOR", 0.9f, "scm.pull_request", 2L, now);
            insertFinding(practiceA, developer, "Strength", "PRESENT", null, 0.9f, "scm.pull_request", 3L, now);

            webTestClient
                    .get()
                    .uri(BASE_URI + "?sort=SEVERITY&direction=ASC", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Strength")
                    .jsonPath("$.content[1].summary")
                    .isEqualTo("Minor nit")
                    .jsonPath("$.content[2].summary")
                    .isEqualTo("Critical gap");
        }

        @Test
        @WithUser
        @DisplayName("drops NOT_APPLICABLE rows when displayableOnly is set")
        void shouldDropNotApplicableWhenDisplayableOnly() {
            Instant now = Instant.now();
            insertFinding(practiceA, developer, "Observed", "PRESENT", "INFO", 0.9f, "scm.pull_request", 1L, now);
            insertFinding(
                    practiceA, developer, "Did not apply", "NOT_APPLICABLE", null, 0.9f, "scm.pull_request", 2L, now);

            webTestClient
                    .get()
                    .uri(BASE_URI + "?displayableOnly=true", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Observed");
        }

        @Test
        @WithUser
        void shouldFilterByObservation() {
            Instant now = Instant.now();
            insertFinding(practiceA, developer, "Good", "PRESENT", "INFO", 0.9f, "scm.pull_request", 1L, now);
            insertFinding(practiceA, developer, "Bad", "ABSENT", "MAJOR", 0.8f, "scm.pull_request", 2L, now);

            webTestClient
                    .get()
                    .uri(BASE_URI + "?presence=ABSENT", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Bad");
        }

        @Test
        @WithUser
        void shouldFilterByPracticeSlugAndObservation() {
            Instant now = Instant.now();
            insertFinding(practiceA, developer, "A pos", "PRESENT", "INFO", 0.9f, "scm.pull_request", 1L, now);
            insertFinding(practiceA, developer, "A neg", "ABSENT", "MAJOR", 0.8f, "scm.pull_request", 2L, now);
            insertFinding(practiceB, developer, "B neg", "ABSENT", "MINOR", 0.7f, "scm.pull_request", 3L, now);

            webTestClient
                    .get()
                    .uri(
                            BASE_URI + "?practiceSlug={slug}&presence=ABSENT",
                            workspace.getWorkspaceSlug(),
                            practiceA.getSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("A neg")
                    .jsonPath("$.content[0].practiceSlug")
                    .isEqualTo("pr-description-quality")
                    .jsonPath("$.content[0].presence")
                    .isEqualTo("ABSENT");
        }

        @Test
        @WithUser
        @DisplayName("respects pagination")
        void shouldPaginate() {
            Instant base = Instant.now();
            for (int i = 0; i < 5; i++) {
                insertFinding(
                        practiceA,
                        developer,
                        "Finding " + i,
                        "PRESENT",
                        "INFO",
                        0.9f,
                        "scm.pull_request",
                        (long) (i + 1),
                        base.minus(i, ChronoUnit.HOURS));
            }

            webTestClient
                    .get()
                    .uri(BASE_URI + "?page=0&size=2", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(2)
                    .jsonPath("$.totalElements")
                    .isEqualTo(5)
                    .jsonPath("$.totalPages")
                    .isEqualTo(3)
                    .jsonPath("$.number")
                    .isEqualTo(0);
        }

        @Test
        @WithUser
        void shouldRejectPageSizeAboveLimit() {
            webTestClient
                    .get()
                    .uri(BASE_URI + "?size=999", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        @WithUser
        @DisplayName("rejects a negative page and a zero page size")
        void shouldRejectInvalidPagination() {
            webTestClient
                    .get()
                    .uri(BASE_URI + "?page=-1&size=0", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        @WithUser
        void shouldReturnCorrectShapeWithoutInternalFields() {
            Instant now = Instant.now();
            insertFinding(practiceA, developer, "Shape check", "ABSENT", "MAJOR", 0.85f, "scm.pull_request", 42L, now);

            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    // Expected fields present
                    .jsonPath("$.content[0].id")
                    .isNotEmpty()
                    .jsonPath("$.content[0].practiceSlug")
                    .isEqualTo("pr-description-quality")
                    .jsonPath("$.content[0].practiceName")
                    .isEqualTo("PR Description Quality")
                    .jsonPath("$.content[0].artifactKind")
                    .isEqualTo("scm.pull_request")
                    .jsonPath("$.content[0].artifactId")
                    .isEqualTo(42)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Shape check")
                    .jsonPath("$.content[0].presence")
                    .isEqualTo("ABSENT")
                    .jsonPath("$.content[0].severity")
                    .isEqualTo("MAJOR")
                    .jsonPath("$.content[0].observedAt")
                    .isNotEmpty()
                    // Internal fields must not leak
                    .jsonPath("$.content[0].agentJobId")
                    .doesNotExist()
                    .jsonPath("$.content[0].idempotencyKey")
                    .doesNotExist()
                    .jsonPath("$.content[0].evidence")
                    .doesNotExist()
                    .jsonPath("$.content[0].evidenceRationale")
                    .doesNotExist()
                    .jsonPath("$.content[0].deliveredFeedback")
                    .doesNotExist();
        }

        @Test
        void shouldReturn401ForUnauthenticated() {
            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }

        @Test
        @WithUser
        @DisplayName("orders findings by detected_at descending")
        void shouldOrderByDetectedAtDesc() {
            Instant now = Instant.now();
            insertFinding(
                    practiceA,
                    developer,
                    "Oldest",
                    "PRESENT",
                    "INFO",
                    0.9f,
                    "scm.pull_request",
                    1L,
                    now.minus(2, ChronoUnit.HOURS));
            insertFinding(practiceA, developer, "Newest", "ABSENT", "MAJOR", 0.8f, "scm.pull_request", 2L, now);
            insertFinding(
                    practiceA,
                    developer,
                    "Middle",
                    "PRESENT",
                    "INFO",
                    0.7f,
                    "scm.pull_request",
                    3L,
                    now.minus(1, ChronoUnit.HOURS));

            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Newest")
                    .jsonPath("$.content[1].summary")
                    .isEqualTo("Middle")
                    .jsonPath("$.content[2].summary")
                    .isEqualTo("Oldest");
        }

        @Test
        @WithUser
        void shouldNotReturnFindingsFromDifferentWorkspace() {
            Instant now = Instant.now();
            insertFinding(practiceA, developer, "My WS finding", "PRESENT", "INFO", 0.9f, "scm.pull_request", 1L, now);

            // Create a second workspace with its own practice and finding
            User otherOwner = persistUser("other-ws-owner");
            Workspace otherWorkspace =
                    createWorkspace("other-ws", "Other WS", "other-org", AccountType.ORG, otherOwner);
            ensureWorkspaceMembership(otherWorkspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);

            Practice otherPractice = new Practice();
            otherPractice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
            otherPractice.setWorkspace(otherWorkspace);
            otherPractice.setSlug("other-practice");
            otherPractice.setName("Other Practice");
            otherPractice.setCriteria("Desc");
            otherPractice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
            otherPractice.setAutonomy(PracticeAutonomy.AUTOMATIC);
            otherPractice = practiceRepository.save(otherPractice);

            AgentJob otherJob = new AgentJob();
            otherJob.setWorkspace(otherWorkspace);
            otherJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
            otherJob.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
            otherJob = agentJobRepository.save(otherJob);

            UUID otherFindingId = UUID.randomUUID();
            observationRepository.insertIfAbsent(
                    otherFindingId,
                    "key-" + otherFindingId,
                    otherJob.getId(),
                    otherJob.getWorkspace().getId(),
                    otherPractice.getId(),
                    null, // practiceRevisionId
                    "scm.pull_request",
                    2L,
                    developer.getId(),
                    "Other WS finding",
                    "ASSESSED",
                    "ABSENT",
                    "GOOD",
                    "MAJOR",
                    null,
                    "reasoning",
                    null,
                    now,
                    "LIVE");

            // Query the first workspace — should only see "My WS finding"
            webTestClient
                    .get()
                    .uri(BASE_URI, workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("My WS finding")
                    .jsonPath("$.totalElements")
                    .isEqualTo(1);
        }
    }

    // GET /practices/observations/summary

    @Nested
    class GetSummary {

        @Test
        @WithUser
        void shouldReturnEmptyList() {
            webTestClient
                    .get()
                    .uri(BASE_URI + "/summary", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(0);
        }

        @Test
        @WithUser
        void shouldReturnCorrectCountsAndFields() {
            Instant now = Instant.now();
            Instant oldest = now.minus(2, ChronoUnit.HOURS);
            insertFinding(practiceA, developer, "A pos 1", "PRESENT", "INFO", 0.9f, "scm.pull_request", 1L, now);
            insertFinding(
                    practiceA,
                    developer,
                    "A pos 2",
                    "PRESENT",
                    "INFO",
                    0.8f,
                    "scm.pull_request",
                    2L,
                    now.minus(1, ChronoUnit.HOURS));
            insertFinding(practiceA, developer, "A neg 1", "ABSENT", "MAJOR", 0.7f, "scm.pull_request", 3L, oldest);
            insertFinding(
                    practiceB,
                    developer,
                    "B neg 1",
                    "ABSENT",
                    "MINOR",
                    0.6f,
                    "scm.pull_request",
                    4L,
                    now.minus(3, ChronoUnit.HOURS));

            webTestClient
                    .get()
                    .uri(BASE_URI + "/summary", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(2)
                    // Results ordered by practice name ASC
                    .jsonPath("$[0].practiceSlug")
                    .isEqualTo("code-review-thoroughness")
                    .jsonPath("$[0].practiceName")
                    .isEqualTo("Code Review Thoroughness")
                    .jsonPath("$[0].totalObservations")
                    .isEqualTo(1)
                    .jsonPath("$[0].positiveCount")
                    .isEqualTo(0)
                    .jsonPath("$[0].negativeCount")
                    .isEqualTo(1)
                    .jsonPath("$[0].lastObservedAt")
                    .isNotEmpty()
                    .jsonPath("$[1].practiceSlug")
                    .isEqualTo("pr-description-quality")
                    .jsonPath("$[1].practiceName")
                    .isEqualTo("PR Description Quality")
                    .jsonPath("$[1].totalObservations")
                    .isEqualTo(3)
                    .jsonPath("$[1].positiveCount")
                    .isEqualTo(2)
                    .jsonPath("$[1].negativeCount")
                    .isEqualTo(1)
                    .jsonPath("$[1].lastObservedAt")
                    .isNotEmpty();
        }

        @Test
        void shouldReturn401ForUnauthenticated() {
            webTestClient
                    .get()
                    .uri(BASE_URI + "/summary", workspace.getWorkspaceSlug())
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }

        @Test
        @WithUser
        void shouldExcludeOtherUsersFindings() {
            Instant now = Instant.now();
            insertFinding(practiceA, developer, "Mine", "PRESENT", "INFO", 0.9f, "scm.pull_request", 1L, now);

            User otherUser = persistUser("someone-else");
            insertFinding(practiceA, otherUser, "Theirs", "ABSENT", "MAJOR", 0.8f, "scm.pull_request", 2L, now);

            webTestClient
                    .get()
                    .uri(BASE_URI + "/summary", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(1)
                    .jsonPath("$[0].totalObservations")
                    .isEqualTo(1)
                    .jsonPath("$[0].positiveCount")
                    .isEqualTo(1);
        }
    }

    // GET /practices/observations/{findingId}

    @Nested
    class GetFindingDetail {

        @Test
        @WithUser
        void shouldReturnDetailForOwnFinding() {
            Instant now = Instant.now();
            UUID findingId = insertFinding(
                    practiceA, developer, "Detailed finding", "ABSENT", "MAJOR", 0.85f, "scm.pull_request", 42L, now);
            // Advice lives on the delivered Feedback, not the finding (ADR 0021): the detail view sources
            // guidance from here.
            deliverFeedbackFor(findingId, "Split this PR so each change reviews on its own.", now);

            webTestClient
                    .get()
                    .uri(BASE_URI + "/{findingId}", workspace.getWorkspaceSlug(), findingId)
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.id")
                    .isEqualTo(findingId.toString())
                    .jsonPath("$.summary")
                    .isEqualTo("Detailed finding")
                    .jsonPath("$.presence")
                    .isEqualTo("ABSENT")
                    .jsonPath("$.severity")
                    .isEqualTo("MAJOR")
                    .jsonPath("$.practiceSlug")
                    .isEqualTo("pr-description-quality")
                    .jsonPath("$.practiceName")
                    .isEqualTo("PR Description Quality")
                    .jsonPath("$.artifactKind")
                    .isEqualTo("scm.pull_request")
                    .jsonPath("$.artifactId")
                    .isEqualTo(42)
                    .jsonPath("$.evidenceRationale")
                    .isEqualTo("Test reasoning for Detailed finding")
                    .jsonPath("$.deliveredFeedback")
                    .isEqualTo("Split this PR so each change reviews on its own.")
                    .jsonPath("$.observedAt")
                    .isNotEmpty()
                    // Internal fields must not leak
                    .jsonPath("$.agentJobId")
                    .doesNotExist()
                    .jsonPath("$.idempotencyKey")
                    .doesNotExist();
        }

        @Test
        @WithUser
        void shouldReturn404ForOtherUserFinding() {
            User otherUser = persistUser("other-developer");
            UUID otherId = insertFinding(
                    practiceA, otherUser, "Not mine", "PRESENT", "INFO", 0.9f, "scm.pull_request", 1L, Instant.now());

            webTestClient
                    .get()
                    .uri(BASE_URI + "/{findingId}", workspace.getWorkspaceSlug(), otherId)
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        void shouldReturn401ForUnauthenticated() {
            webTestClient
                    .get()
                    .uri(BASE_URI + "/{findingId}", workspace.getWorkspaceSlug(), UUID.randomUUID())
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }

        @Test
        @WithUser
        void shouldReturn404ForNonExistentFinding() {
            webTestClient
                    .get()
                    .uri(BASE_URI + "/{findingId}", workspace.getWorkspaceSlug(), UUID.randomUUID())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithUser
        void shouldReturnEvidenceWhenPresent() {
            UUID findingId = UUID.randomUUID();
            String evidenceJson =
                    "{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\",\"artifactPath\":\"inputs/context/diff.patch\",\"path\":\"README.md\",\"side\":\"NEW\",\"startLine\":42,\"endLine\":42,\"quote\":\"example\",\"quoteRedacted\":false}]}";
            observationRepository.insertIfAbsent(
                    findingId,
                    "key-" + findingId,
                    agentJob.getId(),
                    agentJob.getWorkspace().getId(),
                    practiceA.getId(),
                    null, // practiceRevisionId
                    "scm.pull_request",
                    50L,
                    developer.getId(),
                    "Evidence finding",
                    "ASSESSED",
                    "ABSENT",
                    "GOOD",
                    "MAJOR",
                    evidenceJson,
                    "reasoning",
                    null,
                    Instant.now(),
                    "LIVE");

            webTestClient
                    .get()
                    .uri(BASE_URI + "/{findingId}", workspace.getWorkspaceSlug(), findingId)
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.evidence")
                    .isNotEmpty()
                    .jsonPath("$.evidence.citations[0].path")
                    .isEqualTo("README.md")
                    .jsonPath("$.evidence.citations[0].startLine")
                    .isEqualTo(42);
        }

        @Test
        @WithUser
        @DisplayName("returns 200 (not 500) when evidence jsonb is not an object")
        void shouldReturn200WhenEvidenceIsNotAnObject() {
            // The detail mapper coerces evidence jsonb to a Map; a non-object payload (here a JSON array) would
            // make convertValue throw -> HTTP 500 on a perfectly valid stored observation. The endpoint must
            // return 200 with the evidence map simply absent.
            UUID findingId = UUID.randomUUID();
            String arrayEvidenceJson = "[{\"file\":\"README.md\",\"line\":42}]";
            observationRepository.insertIfAbsent(
                    findingId,
                    "key-" + findingId,
                    agentJob.getId(),
                    agentJob.getWorkspace().getId(),
                    practiceA.getId(),
                    null, // practiceRevisionId
                    "scm.pull_request",
                    51L,
                    developer.getId(),
                    "Array evidence finding",
                    "ASSESSED",
                    "ABSENT",
                    "GOOD",
                    "MAJOR",
                    arrayEvidenceJson,
                    "reasoning",
                    null,
                    Instant.now(),
                    "LIVE");

            webTestClient
                    .get()
                    .uri(BASE_URI + "/{findingId}", workspace.getWorkspaceSlug(), findingId)
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.summary")
                    .isEqualTo("Array evidence finding")
                    .jsonPath("$.evidence")
                    .doesNotExist();
        }

        @Test
        @WithUser
        void shouldReturn404ForFindingInDifferentWorkspace() {
            // Create finding in current workspace
            UUID findingId = insertFinding(
                    practiceA,
                    developer,
                    "WS1 finding",
                    "PRESENT",
                    "INFO",
                    0.9f,
                    "scm.pull_request",
                    1L,
                    Instant.now());

            // Create a different workspace
            User otherOwner = persistUser("ws2-owner");
            Workspace otherWorkspace =
                    createWorkspace("other-ws2", "Other WS2", "other-org2", AccountType.ORG, otherOwner);
            ensureWorkspaceMembership(otherWorkspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);

            // Try to access finding from workspace1 via workspace2's URL
            webTestClient
                    .get()
                    .uri(BASE_URI + "/{findingId}", otherWorkspace.getWorkspaceSlug(), findingId)
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    // GET /practices/observations/pull-request/{prId}

    @Nested
    class GetPullRequestFindings {

        @Test
        @WithUser
        @DisplayName("returns all findings for a pull request")
        void shouldReturnPrFindings() {
            Instant now = Instant.now();
            insertFinding(practiceA, developer, "PR finding 1", "PRESENT", "INFO", 0.9f, "scm.pull_request", 100L, now);
            insertFinding(
                    practiceB,
                    developer,
                    "PR finding 2",
                    "ABSENT",
                    "MAJOR",
                    0.8f,
                    "scm.pull_request",
                    100L,
                    now.minus(1, ChronoUnit.HOURS));

            // Different PR — should not appear
            insertFinding(practiceA, developer, "Other PR", "PRESENT", "INFO", 0.7f, "scm.pull_request", 200L, now);

            webTestClient
                    .get()
                    .uri(BASE_URI + "/pull-request/{prId}", workspace.getWorkspaceSlug(), 100L)
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(2)
                    .jsonPath("$[0].summary")
                    .isEqualTo("PR finding 1")
                    .jsonPath("$[1].summary")
                    .isEqualTo("PR finding 2");
        }

        @Test
        @WithUser
        void shouldIncludeOtherUsersFindingsForSamePr() {
            Instant now = Instant.now();
            insertFinding(
                    practiceA, developer, "My PR finding", "PRESENT", "INFO", 0.9f, "scm.pull_request", 100L, now);

            User otherUser = persistUser("pr-collaborator");
            insertFinding(
                    practiceA,
                    otherUser,
                    "Their PR finding",
                    "ABSENT",
                    "MAJOR",
                    0.8f,
                    "scm.pull_request",
                    100L,
                    now.minus(1, ChronoUnit.HOURS));

            webTestClient
                    .get()
                    .uri(BASE_URI + "/pull-request/{prId}", workspace.getWorkspaceSlug(), 100L)
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(2)
                    .jsonPath("$[0].summary")
                    .isEqualTo("My PR finding")
                    .jsonPath("$[0].presence")
                    .isEqualTo("PRESENT")
                    .jsonPath("$[1].summary")
                    .isEqualTo("Their PR finding")
                    .jsonPath("$[1].presence")
                    .isEqualTo("ABSENT");
        }

        @Test
        void shouldReturn401ForUnauthenticated() {
            webTestClient
                    .get()
                    .uri(BASE_URI + "/pull-request/{prId}", workspace.getWorkspaceSlug(), 999L)
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }

        @Test
        @WithUser
        void shouldReturnEmptyForUnknownPr() {
            webTestClient
                    .get()
                    .uri(BASE_URI + "/pull-request/{prId}", workspace.getWorkspaceSlug(), 999L)
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(0);
        }

        @Test
        @WithUser
        @DisplayName("orders findings by detected_at descending")
        void shouldOrderByDetectedAtDesc() {
            Instant now = Instant.now();
            insertFinding(
                    practiceA,
                    developer,
                    "Old",
                    "PRESENT",
                    "INFO",
                    0.9f,
                    "scm.pull_request",
                    100L,
                    now.minus(2, ChronoUnit.HOURS));
            insertFinding(practiceB, developer, "New", "ABSENT", "MAJOR", 0.8f, "scm.pull_request", 100L, now);

            webTestClient
                    .get()
                    .uri(BASE_URI + "/pull-request/{prId}", workspace.getWorkspaceSlug(), 100L)
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[0].summary")
                    .isEqualTo("New")
                    .jsonPath("$[1].summary")
                    .isEqualTo("Old");
        }

        @Test
        @WithUser
        void shouldNotReturnPrFindingsFromDifferentWorkspace() {
            Instant now = Instant.now();
            insertFinding(
                    practiceA, developer, "WS1 PR finding", "PRESENT", "INFO", 0.9f, "scm.pull_request", 100L, now);

            // Create second workspace with its own practice and finding for same PR ID
            User otherOwner = persistUser("ws2-pr-owner");
            Workspace otherWorkspace = createWorkspace("ws2-pr", "WS2 PR", "ws2-org", AccountType.ORG, otherOwner);
            ensureWorkspaceMembership(otherWorkspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);

            Practice otherPractice = new Practice();
            otherPractice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
            otherPractice.setWorkspace(otherWorkspace);
            otherPractice.setSlug("ws2-practice");
            otherPractice.setName("WS2 Practice");
            otherPractice.setCriteria("Desc");
            otherPractice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
            otherPractice.setAutonomy(PracticeAutonomy.AUTOMATIC);
            otherPractice = practiceRepository.save(otherPractice);

            AgentJob otherJob = new AgentJob();
            otherJob.setWorkspace(otherWorkspace);
            otherJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
            otherJob.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
            otherJob = agentJobRepository.save(otherJob);

            UUID ws2FindingId = UUID.randomUUID();
            observationRepository.insertIfAbsent(
                    ws2FindingId,
                    "key-" + ws2FindingId,
                    otherJob.getId(),
                    otherJob.getWorkspace().getId(),
                    otherPractice.getId(),
                    null, // practiceRevisionId
                    "scm.pull_request",
                    100L,
                    developer.getId(),
                    "WS2 PR finding",
                    "ASSESSED",
                    "ABSENT",
                    "GOOD",
                    "MAJOR",
                    null,
                    "reasoning",
                    null,
                    now,
                    "LIVE");

            // Query workspace1 — should only see WS1 finding
            webTestClient
                    .get()
                    .uri(BASE_URI + "/pull-request/{prId}", workspace.getWorkspaceSlug(), 100L)
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(1)
                    .jsonPath("$[0].summary")
                    .isEqualTo("WS1 PR finding");
        }
    }

    // GET /practices/standings

    @Nested
    class GetStandings {

        @Test
        @WithUser
        @DisplayName("returns per-practice practice standings with the standing/toWorkOn/strengths shape")
        void shouldReturnPracticeStandings() {
            Instant now = Instant.now();
            insertFinding(
                    practiceA, developer, "Thin PR description", "ABSENT", "MAJOR", 0.9f, "scm.pull_request", 1L, now);
            insertFinding(
                    practiceB, developer, "Thorough review", "PRESENT", "INFO", 0.9f, "scm.pull_request", 2L, now);

            webTestClient
                    .get()
                    .uri("/workspaces/{workspaceSlug}/practices/standings", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(2)
                    .jsonPath("$[0].slug")
                    .isEqualTo(practiceA.getSlug())
                    .jsonPath("$[0].standing")
                    .isEqualTo("DEVELOPING")
                    .jsonPath("$[0].toWorkOn[0].title")
                    .isEqualTo("Thin PR description")
                    .jsonPath("$[0].toWorkOn[0].severity")
                    .isEqualTo("MAJOR")
                    .jsonPath("$[0].strengths.length()")
                    .isEqualTo(0)
                    .jsonPath("$[1].slug")
                    .isEqualTo(practiceB.getSlug())
                    .jsonPath("$[1].standing")
                    .isEqualTo("STRENGTH")
                    .jsonPath("$[1].strengths[0].title")
                    .isEqualTo("Thorough review")
                    .jsonPath("$[1].toWorkOn.length()")
                    .isEqualTo(0);
        }

        @Test
        @WithUser
        @DisplayName("a watched practice with nothing to say appears, and says which silence it is")
        void shouldReportWhyAWatchedPracticeHasNothingToSay() {
            insertFinding(
                    practiceA,
                    developer,
                    "Thin PR description",
                    "ABSENT",
                    "MAJOR",
                    0.9f,
                    "scm.pull_request",
                    1L,
                    Instant.now());

            webTestClient
                    .get()
                    .uri("/workspaces/{workspaceSlug}/practices/standings", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(2)
                    .jsonPath("$[0].slug")
                    .isEqualTo(practiceA.getSlug())
                    .jsonPath("$[0].standing")
                    .isEqualTo("DEVELOPING")
                    .jsonPath("$[1].slug")
                    .isEqualTo(practiceB.getSlug())
                    .jsonPath("$[1].standing")
                    .isEqualTo("NOT_OBSERVED")
                    .jsonPath("$[1].toWorkOn.length()")
                    .isEqualTo(0)
                    .jsonPath("$[1].strengths.length()")
                    .isEqualTo(0)
                    .jsonPath("$[1].direction")
                    .doesNotExist();
        }

        @Test
        void shouldReturn401ForUnauthenticated() {
            webTestClient
                    .get()
                    .uri("/workspaces/{workspaceSlug}/practices/standings", workspace.getWorkspaceSlug())
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
    }
}
