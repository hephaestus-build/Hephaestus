package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
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

class ObservationHistoryConsentGateIntegrationTest extends AbstractSlackConsentGateIntegrationTest {

    @Autowired
    private ObservationHistoryContentSource contentSource;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Practice practice;
    private AgentJob job;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        setUpWorkspaceAndRecipient("obs-consent-gate-test");
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
    }

    @Test
    @DisplayName("consent gate: only an ACTIVE-channel conversation observation surfaces; PAUSED/REVOKED are withheld")
    void onlyActiveChannelConversationObservationSurfaces() {
        long activeThreadId = seedThread("C-active", "100.0", ConsentState.ACTIVE);
        long pausedThreadId = seedThread("C-paused", "200.0", ConsentState.PAUSED);
        long revokedThreadId = seedThread("C-revoked", "300.0", ConsentState.REVOKED);

        Observation activeObs = saveObservation("occ-active", "chat.conversation_thread", activeThreadId);
        saveObservation("occ-paused", "chat.conversation_thread", pausedThreadId);
        saveObservation("occ-revoked", "chat.conversation_thread", revokedThreadId);
        Observation prObs = saveObservation("occ-pr", "scm.pull_request", 4242L);

        JsonNode root = contribute();

        assertThat(root.get("_meta").get("trustLevel").asString()).isEqualTo("UNTRUSTED_EXTERNAL");

        List<String> ids = observationIds(root);
        assertThat(ids)
                .containsExactlyInAnyOrder(
                        activeObs.getId().toString(), prObs.getId().toString());
        assertThat(ids).doesNotContainNull();
        assertThat(ids).hasSize(2);
    }

    @Test
    @DisplayName("Slack consent does not suppress otherwise-authorized PR/issue observations")
    void prIssueOnlyPayloadPassesThroughWithoutEnvelope() {
        Observation prObs = saveObservation("occ-pr", "scm.pull_request", 555L);
        Observation issueObs = saveObservation("occ-issue", "scm.issue", 777L);

        JsonNode root = contribute();

        assertThat(root.has("_meta")).isFalse();
        assertThat(observationIds(root))
                .containsExactlyInAnyOrder(
                        prObs.getId().toString(), issueObs.getId().toString());
    }

    @Test
    @DisplayName("a revoked conversation observation does not suppress an authorized PR observation")
    void prSurvivesWhenAllConversationRevoked() {
        long revokedThreadId = seedThread("C-revoked", "300.0", ConsentState.REVOKED);
        saveObservation("occ-revoked", "chat.conversation_thread", revokedThreadId);
        Observation prObs = saveObservation("occ-pr", "scm.pull_request", 909L);

        JsonNode root = contribute();

        assertThat(root.has("_meta")).isFalse();
        assertThat(observationIds(root)).containsExactly(prObs.getId().toString());
    }

    private JsonNode contribute() {
        Map<String, byte[]> files = new HashMap<>();
        contentSource.contribute(
                new ContextRequest.MentorChatRequest(workspace.getId(), recipient.getId(), UUID.randomUUID()), files);
        return objectMapper.readTree(files.get(ObservationHistoryContentSource.OUTPUT_KEY));
    }

    private static List<String> observationIds(JsonNode root) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : root.get("recentObservations")) {
            ids.add(node.get("id").asString());
        }
        return ids;
    }

    private Observation saveObservation(String occurrenceKey, String artifactKind, long artifactId) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                occurrenceKey,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                practice.getCurrentRevision().getId(),
                artifactKind,
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
                Instant.now(),
                "LIVE");
        return observationRepository.findById(id).orElseThrow();
    }

    private static String evidence(String artifactKind) {
        String sourceKind =
                "chat.conversation_thread".equals(artifactKind) ? "slack.conversation.thread" : "scm.pull-request.core";
        return """
        {"citations":[{"sourceKind":"%s","artifactPath":"inputs/context/source.json",\
        "path":"source.json","startLine":1,"endLine":1,"quote":"evidence",\
        "quoteRedacted":false}]}
        """.formatted(sourceKind);
    }
}
