package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** A new review reads a conversation's history only while that conversation's channel still consents. */
class ReviewHistoryConsentGateIntegrationTest extends AbstractSlackConsentGateIntegrationTest {

    @Autowired
    private ReviewHistoryContentSource historySource;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Practice practice;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        setUpWorkspaceAndRecipient("review-history-consent-test");
        practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setSlug("test-practice");
        practice.setName("Test Practice");
        practice.setCriteria("Test description");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.conversationThread());
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice = practiceRepository.saveAndFlush(practice);
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(practice, 1));
        practice.setCurrentRevision(revision);
        practice = practiceRepository.saveAndFlush(practice);
    }

    @Test
    @DisplayName("a paused channel's observations and feedback leave review history until the channel resumes")
    void pausedConversationLeavesReviewHistoryUntilResumed() {
        long active = seedThread("C-active", "100.0", ConsentState.ACTIVE);
        long paused = seedThread("C-paused", "200.0", ConsentState.PAUSED);
        observe(ArtifactKinds.CONVERSATION_THREAD.value(), active, "Said in the active channel");
        UUID pausedObservation =
                observe(ArtifactKinds.CONVERSATION_THREAD.value(), paused, "Said in the paused channel");
        observe(ArtifactKinds.PULL_REQUEST.value(), 4242L, "Seen on the pull request");
        deliver(paused, pausedObservation, "Brief about the paused channel");
        AgentJob review = newJob();

        String held = history(review);

        assertThat(held)
                .contains("Said in the active channel", "Seen on the pull request")
                .doesNotContain("Said in the paused channel", "Brief about the paused channel");

        jdbcTemplate.update(
                "UPDATE slack_monitored_channel SET consent_state = 'ACTIVE' WHERE workspace_id = ? AND slack_channel_id = ?",
                workspace.getId(),
                "C-paused");

        assertThat(history(review)).contains("Said in the paused channel", "Brief about the paused channel");
    }

    private String history(AgentJob review) {
        StringBuilder staged = new StringBuilder();
        historySource
                .capture(new ContextRequest.ConversationReviewRequest(review), historySource.sourceKinds())
                .files()
                .values()
                .forEach(bytes -> staged.append(new String(bytes, StandardCharsets.UTF_8)));
        return staged.toString();
    }

    private UUID observe(String artifactKind, long artifactId, String summary) {
        AgentJob job = newJob();
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                "occ-" + id,
                job.getId(),
                workspace.getId(),
                practice.getId(),
                practice.getCurrentRevision().getId(),
                artifactKind,
                artifactId,
                recipient.getId(),
                summary,
                "ASSESSED",
                "ABSENT",
                "GOOD",
                "MAJOR",
                evidence(artifactKind),
                null,
                null,
                Instant.now(),
                "LIVE");
        return id;
    }

    private void deliver(long threadId, UUID observationId, String body) {
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(newJob().getId())
                .workspaceId(workspace.getId())
                .artifactKind(ArtifactKinds.CONVERSATION_THREAD)
                .artifactId(threadId)
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(FeedbackChannel.IN_CHAT)
                .position(0)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .source(FeedbackSource.AGENT)
                .body(body)
                .createdAt(Instant.now())
                .deliveredAt(Instant.now())
                .build());
        feedbackObservationRepository.insertIfAbsent(feedback.getId(), observationId, EvidenceRole.PRIMARY.name(), 0);
    }

    private static String evidence(String artifactKind) {
        String sourceKind = ArtifactKinds.CONVERSATION_THREAD.value().equals(artifactKind)
                ? "slack.conversation.thread"
                : "scm.pull-request.core";
        return """
        {"citations":[{"sourceKind":"%s","artifactPath":"inputs/context/source.json",\
        "path":"source.json","startLine":1,"endLine":1,"quote":"evidence",\
        "quoteRedacted":false}]}
        """.formatted(sourceKind);
    }
}
