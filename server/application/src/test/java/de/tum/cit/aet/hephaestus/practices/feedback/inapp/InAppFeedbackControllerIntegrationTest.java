package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
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
 * The developer's own practice pages, end to end: who may read it, and what reading it records.
 */
class InAppFeedbackControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String IN_APP = "/workspaces/{slug}/practices/feedback/in-app";
    private static final String DIFF_EVIDENCE_JSON =
            "{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\",\"artifactPath\":\"inputs/context/diff.patch\","
                    + "\"path\":\"src/Main.java\",\"side\":\"NEW\",\"startLine\":42,\"endLine\":42,\"quote\":\"example\","
                    + "\"quoteRedacted\":false}]}";

    @Autowired
    private WebTestClient webTestClient;

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
    private Practice practice;
    private AgentJob job;
    private User developer;
    private User teammate;

    @BeforeEach
    void setUpWorkspace() {
        User owner = persistUser("in-app-owner");
        workspace = createWorkspace("in-app-ws", "In-app WS", "in-app-org", AccountType.ORG, owner);
        developer = persistUser("testuser"); // matches @WithUser
        teammate = persistUser("teammate");
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, teammate, WorkspaceMembership.WorkspaceRole.MEMBER);

        practice = persistPractice(workspace, "ships-tests-with-changes", "Ships Tests With Changes");
        job = persistJob(workspace);
    }

    @Test
    @WithUser
    @DisplayName("a message prepared for this developer is returned, split into headline and body")
    void returnsTheDevelopersOwnPreparedMessage() {
        Feedback unit = persistInAppUnit(
                workspace,
                job,
                developer,
                7000,
                FeedbackDeliveryState.PREPARED,
                "You keep shipping untested changes",
                "Across your last three pull requests the tests did not move with the code.");
        bind(unit, persistObservation(practice, job, developer, 101L, "No test touched"));

        getOk(workspace)
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].headline")
                .isEqualTo("You keep shipping untested changes")
                // The headline is lifted out of the stored layout; what is left is the message plus its next step.
                .jsonPath("$[0].body")
                .isEqualTo("Across your last three pull requests the tests did not move with the code.\n\n"
                        + "**Try next:** Write the test first next time.")
                .jsonPath("$[0].practiceSlug")
                .isEqualTo("ships-tests-with-changes")
                .jsonPath("$[0].occurrenceCount")
                .isEqualTo(1)
                .jsonPath("$[0].evidence[0].artifactId")
                .isEqualTo(101);
    }

    @Test
    @WithUser
    @DisplayName("a message prepared for somebody else is not on this developer's page")
    void doesNotReturnAnotherDevelopersMessage() {
        Feedback theirs = persistInAppUnit(
                workspace, job, teammate, 7000, FeedbackDeliveryState.PREPARED, "Their habit", "Not about the caller.");
        bind(theirs, persistObservation(practice, job, teammate, 202L, "Their finding"));

        getOk(workspace).jsonPath("$.length()").isEqualTo(0);

        assertThat(feedbackRepository.findById(theirs.getId()))
                .get()
                .extracting(Feedback::getDeliveryState)
                .isEqualTo(FeedbackDeliveryState.PREPARED);
    }

    /**
     * The read is the delivery on this lane, and the flip is a compare-and-set: the second read must not
     * restamp {@code deliveredAt}, or "when they first saw it" would drift forward on every page load.
     */
    @Test
    @WithUser
    @DisplayName("the first read delivers the message; a second read does not re-deliver it")
    void firstReadFlipsPreparedToDeliveredAndTheSecondIsANoOp() {
        Feedback unit = persistInAppUnit(
                workspace,
                job,
                developer,
                7000,
                FeedbackDeliveryState.PREPARED,
                "You keep shipping untested changes",
                "Across your last three pull requests the tests did not move with the code.");
        bind(unit, persistObservation(practice, job, developer, 101L, "No test touched"));

        getOk(workspace).jsonPath("$[0].readAt").doesNotExist();

        Feedback afterFirstRead = feedbackRepository.findById(unit.getId()).orElseThrow();
        assertThat(afterFirstRead.getDeliveryState()).isEqualTo(FeedbackDeliveryState.DELIVERED);
        assertThat(afterFirstRead.getDeliveredAt()).isNotNull();

        getOk(workspace).jsonPath("$[0].readAt").exists();

        assertThat(feedbackRepository.findById(unit.getId()))
                .get()
                .extracting(Feedback::getDeliveredAt)
                .isEqualTo(afterFirstRead.getDeliveredAt());
    }

    /**
     * Composition freezes text; it must not freeze permission. A message whose evidence was measured under
     * review rules the practice has since replaced stops being shown — and the ledger row stays, because
     * hiding is not deleting.
     */
    @Test
    @WithUser
    @DisplayName("a message whose evidence went non-current is hidden at read time, not deleted")
    void hidesAMessageWhoseEvidenceIsNoLongerCurrent() {
        Feedback unit = persistInAppUnit(
                workspace,
                job,
                developer,
                7000,
                FeedbackDeliveryState.PREPARED,
                "You keep shipping untested changes",
                "Across your last three pull requests the tests did not move with the code.");
        bind(unit, persistObservation(practice, job, developer, 101L, "No test touched"));
        getOk(workspace).jsonPath("$.length()").isEqualTo(1);

        // The measurement stays pinned to revision 1; the practice moves on, so its claim is stale.
        practice.setCriteria("A rewritten rubric, measuring something else");
        practice = practiceRepository.saveAndFlush(practice);
        PracticeRevision second = practiceRevisionRepository.save(new PracticeRevision(practice, 2));
        practice.setCurrentRevision(second);
        practiceRepository.saveAndFlush(practice);

        getOk(workspace).jsonPath("$.length()").isEqualTo(0);

        assertThat(feedbackRepository.findById(unit.getId())).isPresent();
    }

    private WebTestClient.BodyContentSpec getOk(Workspace ws) {
        return webTestClient
                .get()
                .uri(IN_APP, ws.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    private Practice persistPractice(Workspace ws, String slug, String name) {
        Practice created = new Practice();
        created.setWorkspace(ws);
        created.setSlug(slug);
        created.setName(name);
        created.setCriteria("Criteria for " + slug);
        created.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        created.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        created = practiceRepository.saveAndFlush(created);
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(created, 1));
        created.setCurrentRevision(revision);
        return practiceRepository.saveAndFlush(created);
    }

    private AgentJob persistJob(Workspace ws) {
        AgentJob created = new AgentJob();
        created.setWorkspace(ws);
        created.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        created.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        created.setEvidenceSnapshot(OBJECT_MAPPER.valueToTree(Map.of("manifest", Map.of("contractVersion", "1.0.0"))));
        return agentJobRepository.save(created);
    }

    private UUID persistObservation(Practice about, AgentJob agentJob, User subject, Long artifactId, String title) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                "occ-" + id,
                agentJob.getId(),
                agentJob.getWorkspace().getId(),
                about.getId(),
                about.getCurrentRevision().getId(),
                "scm.pull_request",
                artifactId,
                subject.getId(),
                title,
                "ASSESSED",
                "ABSENT",
                "BAD",
                "MAJOR",
                DIFF_EVIDENCE_JSON,
                "Reasoning for " + title,
                null,
                Instant.now(),
                "LIVE");
        return id;
    }

    private Feedback persistInAppUnit(
            Workspace ws,
            AgentJob agentJob,
            User recipient,
            int position,
            FeedbackDeliveryState state,
            String headline,
            String message) {
        return feedbackRepository.save(Feedback.builder()
                .agentJobId(agentJob.getId())
                .workspaceId(ws.getId())
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(FeedbackChannel.IN_APP)
                .position(position)
                .deliveryState(state)
                .body(InAppFeedbackBody.render(headline, message, "Write the test first next time."))
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .build());
    }

    private void bind(Feedback unit, UUID observationId) {
        feedbackObservationRepository.insertIfAbsent(unit.getId(), observationId, "PRIMARY", 0);
    }
}
