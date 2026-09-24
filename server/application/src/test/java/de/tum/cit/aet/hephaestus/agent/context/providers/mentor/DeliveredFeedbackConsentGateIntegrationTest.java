package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DeliveredFeedbackConsentGateIntegrationTest extends AbstractSlackConsentGateIntegrationTest {

    @Autowired
    private DeliveredFeedbackContentSource contentSource;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private AgentJob job;
    private Practice practice;
    private int nextPosition;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        setUpWorkspaceAndRecipient("delivered-consent-gate-test");
        practice = new Practice();
        practice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.CONVERSATION_THREAD));
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.conversationThread());
        practice.setWorkspace(workspace);
        practice.setSlug("test-practice");
        practice.setName("Test Practice");
        practice.setCriteria("Test description");
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice = practiceRepository.saveAndFlush(practice);
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(practice, 1));
        practice.setCurrentRevision(revision);
        practice = practiceRepository.saveAndFlush(practice);
        job = newJob();
        nextPosition = 0;
    }

    @Test
    @DisplayName("consent gate: only an ACTIVE-channel conversation body surfaces; PAUSED/REVOKED are withheld")
    void onlyActiveChannelConversationBodySurfaces() {
        long activeThreadId = seedThread("C-active", "100.0", ConsentState.ACTIVE);
        long pausedThreadId = seedThread("C-paused", "200.0", ConsentState.PAUSED);
        long revokedThreadId = seedThread("C-revoked", "300.0", ConsentState.REVOKED);

        saveDelivered(ArtifactKinds.CONVERSATION_THREAD, activeThreadId, FeedbackChannel.IN_CHAT, "active-body");
        saveDelivered(ArtifactKinds.CONVERSATION_THREAD, pausedThreadId, FeedbackChannel.IN_CHAT, "paused-body");
        saveDelivered(ArtifactKinds.CONVERSATION_THREAD, revokedThreadId, FeedbackChannel.IN_CHAT, "revoked-body");
        saveDelivered(ArtifactKinds.PULL_REQUEST, 4242L, FeedbackChannel.IN_CONTEXT, "pr-body");

        JsonNode root = contribute();

        assertThat(root.get("_meta").get("trustLevel").asString()).isEqualTo("UNTRUSTED_EXTERNAL");

        List<String> bodies = bodies(root);
        assertThat(bodies).containsExactlyInAnyOrder("active-body", "pr-body");
    }

    @Test
    @DisplayName("Slack consent does not suppress otherwise-authorized PR/issue feedback")
    void prIssueOnlyPayloadPassesThroughWithoutEnvelope() {
        saveDelivered(ArtifactKinds.PULL_REQUEST, 555L, FeedbackChannel.IN_CONTEXT, "pr-body");
        saveDelivered(ArtifactKinds.ISSUE, 777L, FeedbackChannel.IN_CONTEXT, "issue-body");

        JsonNode root = contribute();

        assertThat(root.has("_meta")).isFalse();
        assertThat(bodies(root)).containsExactlyInAnyOrder("pr-body", "issue-body");
    }

    @Test
    @DisplayName("revoked conversation feedback does not suppress authorized PR feedback")
    void prSurvivesWhenAllConversationRevoked() {
        long revokedThreadId = seedThread("C-revoked", "300.0", ConsentState.REVOKED);
        saveDelivered(ArtifactKinds.CONVERSATION_THREAD, revokedThreadId, FeedbackChannel.IN_CHAT, "revoked-body");
        saveDelivered(ArtifactKinds.PULL_REQUEST, 909L, FeedbackChannel.IN_CONTEXT, "pr-body");

        JsonNode root = contribute();

        assertThat(root.has("_meta")).isFalse();
        assertThat(bodies(root)).containsExactly("pr-body");
    }

    private JsonNode contribute() {
        Map<String, byte[]> files = new HashMap<>();
        contentSource.contribute(
                new ContextRequest.MentorChatRequest(workspace.getId(), recipient.getId(), UUID.randomUUID()), files);
        return objectMapper.readTree(files.get(DeliveredFeedbackContentSource.OUTPUT_KEY));
    }

    private static List<String> bodies(JsonNode root) {
        List<String> bodies = new ArrayList<>();
        for (JsonNode node : root.get("deliveredFeedback")) {
            bodies.add(node.get("body").asString());
        }
        return bodies;
    }

    private void saveDelivered(ArtifactKind artifactKind, long artifactId, FeedbackChannel channel, String body) {
        Instant now = Instant.now();
        UUID observationId = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                observationId,
                "observation-" + nextPosition,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                practice.getCurrentRevision().getId(),
                artifactKind.value(),
                artifactId,
                recipient.getId(),
                "Observation title",
                "ASSESSED",
                "ABSENT",
                "GOOD",
                "MAJOR",
                evidence(artifactKind),
                null,
                null,
                now,
                "LIVE");
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .artifactKind(artifactKind)
                .artifactId(artifactId)
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(channel)
                .position(nextPosition++)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .source(FeedbackSource.AGENT)
                .body(body)
                .createdAt(now)
                .deliveredAt(now)
                .build());
        feedbackObservationRepository.insertIfAbsent(feedback.getId(), observationId, EvidenceRole.PRIMARY.name(), 0);
    }

    private static String evidence(ArtifactKind artifactKind) {
        String sourceKind = ArtifactKinds.CONVERSATION_THREAD.equals(artifactKind)
                ? "slack.conversation.thread"
                : "scm.pull-request.core";
        return """
        {"citations":[{"sourceKind":"%s","artifactPath":"inputs/context/source.json",\
        "path":"source.json","startLine":1,"endLine":1,"quote":"evidence",\
        "quoteRedacted":false}]}
        """.formatted(sourceKind);
    }
}
