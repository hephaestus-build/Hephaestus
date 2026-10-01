package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ObservationHistoryConsentGateIntegrationTest extends AbstractSlackConsentGateIntegrationTest {

    private static final String EVIDENCE = evidence("scm.pull_request");
    private static final String NO_CITATIONS = "{\"citations\":[]}";

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
        PracticeTestEvidence.configure(practice, ArtifactKinds.CONVERSATION_THREAD);
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.conversationThread());
        practice.setWorkspace(workspace);
        practice.setSlug("test-practice");
        practice.setName("Test Practice");
        practice.setCriteria("Test description");
        PracticeTestEvidence.configure(practice, ScmSignals.PULL_REQUEST_OPENED);
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

    @Test
    @DisplayName("an earlier review stays beside the latest one, abstentions are listed, and no other row is")
    void shouldKeepEarlierReviewsAndAbstentionsBesideEachClaimsLatestRun() {
        Instant base = Instant.now().minus(1, ChronoUnit.DAYS);
        UUID earlier = observe(newJob(), practice, 3L, recipient.getId(), "NOT_MET", "MAJOR", EVIDENCE, base);
        AgentJob repair = newJob();
        UUID latest = observe(repair, practice, 3L, recipient.getId(), "MET", null, EVIDENCE, base.plusSeconds(60));
        UUID abstention = observe(
                newJob(), practice, 4L, recipient.getId(), "NOT_APPLICABLE", null, EVIDENCE, base.plusSeconds(60));
        // A later run this conversation may not use hides its claim entirely rather than exposing its earlier run.
        observe(newJob(), practice, 5L, recipient.getId(), "NOT_MET", "MAJOR", EVIDENCE, base);
        observe(newJob(), practice, 5L, recipient.getId(), "MET", null, NO_CITATIONS, base.plusSeconds(60));
        User colleague = userRepository.save(TestUserFactory.createUser(101L, "colleague", recipient.getProvider()));
        observe(repair, practice, 3L, colleague.getId(), "NOT_MET", "MAJOR", EVIDENCE, base.plusSeconds(120));
        Workspace own = workspace;
        workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("obs-history-other"));
        observe(repair, practice("test-practice"), 3L, recipient.getId(), "NOT_MET", "MAJOR", EVIDENCE, base);
        workspace = own;

        JsonNode root = contribute();

        assertThat(ids(root.get("recentObservations"))).containsExactly(latest.toString());
        assertThat(root.get("earlierObservations"))
                .extracting(
                        o -> o.get("id").asString(),
                        o -> o.get("severity").asString(),
                        o -> o.get("origin").asString())
                .containsExactly(tuple(earlier.toString(), "MAJOR", "LIVE"));
        assertThat(root.get("abstentions"))
                .extracting(o -> o.get("id").asString(), o -> o.get("outcome").asString())
                .containsExactly(tuple(abstention.toString(), "NOT_APPLICABLE"));
        assertThat(root.get("summary").get("byOutcome").get("NOT_MET").asLong()).isZero();
        assertThat(root.get("summary").get("bySeverity").get("MAJOR").asLong()).isZero();
        assertThat(root.get("coverage")
                        .get("maxEntries")
                        .get("earlierObservations")
                        .asInt())
                .isPositive();
    }

    @Test
    @DisplayName("rows this conversation may not use change nothing in the file")
    void shouldAnswerAlikeWhetherOrNotWithheldRowsExist() {
        Instant base = Instant.now().minus(1, ChronoUnit.DAYS);
        for (int i = 0; i < 60; i++) {
            observe(newJob(), practice, i, recipient.getId(), "NOT_MET", "MAJOR", NO_CITATIONS, base.plusSeconds(i));
            observe(
                    newJob(),
                    practice,
                    i,
                    recipient.getId(),
                    "UNDETERMINED",
                    null,
                    NO_CITATIONS,
                    base.plusSeconds(100 + i));
        }
        User newcomer = userRepository.save(TestUserFactory.createUser(102L, "newcomer", recipient.getProvider()));

        ObjectNode withheld = (ObjectNode) objectMapper.readTree(contributed(recipient.getId()));
        ObjectNode none = (ObjectNode) objectMapper.readTree(contributed(newcomer.getId()));
        for (ObjectNode payload : List.of(withheld, none)) {
            payload.remove("user");
            ((ObjectNode) payload.get("coverage")).remove("preparedAt");
        }

        assertThat(withheld).isEqualTo(none);
    }

    @Test
    @DisplayName("however long the stored text, the overview fits its bound with every row, and a detail fits its own")
    void shouldFitTheOverviewAndDetailBounds() {
        Instant base = Instant.now().minus(1, ChronoUnit.DAYS);
        String hostile = "\"\\\u0001x".repeat(2_500);
        UUID newest = null;
        for (int i = 0; i < 30; i++) {
            observe(
                    newJob(),
                    practice,
                    i,
                    recipient.getId(),
                    "NOT_MET",
                    "MAJOR",
                    EVIDENCE,
                    base.plusSeconds(i),
                    hostile);
            newest = observe(
                    newJob(),
                    practice,
                    i,
                    recipient.getId(),
                    "MET",
                    null,
                    EVIDENCE,
                    base.plusSeconds(100 + i),
                    hostile);
        }

        String file = contributed(recipient.getId());
        JsonNode root = objectMapper.readTree(file);

        assertThat(file).hasSizeLessThanOrEqualTo(ObservationHistoryContentSource.OVERVIEW_MAX_CHARS);
        assertThat(root.get("recentObservations")).hasSize(30);
        assertThat(ids(root.get("recentObservations"))).first().isEqualTo(String.valueOf(newest));
        assertThat(root.get("earlierObservations")).hasSize(20);
        assertThat(root.findValues("evidenceRationale")).isEmpty();
        assertThat(root.has("omittedForSize")).isFalse();

        ObjectNode detail = contentSource.inspect(
                workspace.getId(),
                recipient.getId(),
                UUID.fromString(ids(root.get("recentObservations")).getFirst()));
        assertThat(objectMapper.writeValueAsString(detail))
                .hasSizeLessThanOrEqualTo(ObservationHistoryContentSource.DETAIL_MAX_CHARS);
        assertThat(detail.get("observation")
                        .get("evidence")
                        .get("citations")
                        .get(0)
                        .get("path")
                        .asString())
                .isEqualTo("source.json");
        assertThat(detail.get("observation").get("evidenceRationale").asString())
                .contains("x");
    }

    @Test
    @DisplayName("a detail answers only for an observation the overview lists, and alike for every other")
    void detailAnswersOnlyForListedObservations() {
        Instant base = Instant.now().minus(1, ChronoUnit.DAYS);
        UUID earlier = observe(newJob(), practice, 3L, recipient.getId(), "NOT_MET", "MAJOR", EVIDENCE, base);
        AgentJob repair = newJob();
        UUID latest = observe(repair, practice, 3L, recipient.getId(), "MET", null, EVIDENCE, base.plusSeconds(60));
        // A later run this conversation may not use hides its claim's earlier run too.
        UUID orphaned = observe(newJob(), practice, 5L, recipient.getId(), "NOT_MET", "MAJOR", EVIDENCE, base);
        UUID unusable =
                observe(newJob(), practice, 5L, recipient.getId(), "MET", null, NO_CITATIONS, base.plusSeconds(60));
        User colleague = userRepository.save(TestUserFactory.createUser(101L, "colleague", recipient.getProvider()));
        UUID colleagues =
                observe(repair, practice, 3L, colleague.getId(), "NOT_MET", "MAJOR", EVIDENCE, base.plusSeconds(120));
        Workspace own = workspace;
        workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("obs-history-detail-other"));
        UUID foreign =
                observe(repair, practice("test-practice"), 3L, recipient.getId(), "NOT_MET", "MAJOR", EVIDENCE, base);
        workspace = own;

        ObjectNode current = contentSource.inspect(workspace.getId(), recipient.getId(), latest);
        assertThat(current.get("list").asString()).isEqualTo("recentObservations");
        assertThat(current.get("observation").get("evidence").get("citations")).hasSize(1);
        assertThat(contentSource
                        .inspect(workspace.getId(), recipient.getId(), earlier)
                        .get("list")
                        .asString())
                .isEqualTo("earlierObservations");

        ObjectNode unknown = notFound(contentSource.inspect(workspace.getId(), recipient.getId(), UUID.randomUUID()));
        for (UUID hidden : List.of(orphaned, unusable, colleagues, foreign)) {
            assertThat(notFound(contentSource.inspect(workspace.getId(), recipient.getId(), hidden)))
                    .as("observation %s", hidden)
                    .isEqualTo(unknown);
        }
        // Another developer's own history does not answer for this developer's observation either.
        assertThat(notFound(contentSource.inspect(workspace.getId(), colleague.getId(), latest)))
                .isEqualTo(unknown);
    }

    @Test
    @DisplayName("a conversation's detail carries the untrusted envelope, and none once its consent is revoked")
    void conversationDetailFollowsCurrentConsent() {
        long threadId = seedThread("C-detail", "400.0", ConsentState.ACTIVE);
        Observation conversation = saveObservation("occ-detail", "chat.conversation_thread", threadId);
        assertThat(ids(contribute().get("recentObservations")))
                .contains(conversation.getId().toString());

        ObjectNode active = contentSource.inspect(workspace.getId(), recipient.getId(), conversation.getId());
        assertThat(active.get("_meta").get("trustLevel").asString()).isEqualTo("UNTRUSTED_EXTERNAL");
        assertThat(active.get("observation").get("evidence").get("citations")).hasSize(1);

        slackMonitoredChannelRepository.findAll().stream()
                .filter(channel -> "C-detail".equals(channel.getSlackChannelId()))
                .forEach(channel -> {
                    channel.setConsentState(ConsentState.REVOKED);
                    slackMonitoredChannelRepository.save(channel);
                });

        ObjectNode revoked = contentSource.inspect(workspace.getId(), recipient.getId(), conversation.getId());
        assertThat(revoked.get("status").asString()).isEqualTo("NOT_FOUND");
        assertThat(revoked.has("_meta")).isFalse();
        assertThat(revoked.has("observation")).isFalse();
    }

    private static ObjectNode notFound(ObjectNode detail) {
        assertThat(detail.get("status").asString()).isEqualTo("NOT_FOUND");
        detail.remove("readAt");
        return detail;
    }

    private JsonNode contribute() {
        return objectMapper.readTree(contributed(recipient.getId()));
    }

    private String contributed(long developerId) {
        Map<String, byte[]> files = new HashMap<>();
        contentSource.contribute(
                new ContextRequest.MentorChatRequest(workspace.getId(), developerId, UUID.randomUUID()), files);
        return new String(files.get(ObservationHistoryContentSource.OUTPUT_KEY), StandardCharsets.UTF_8);
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
                "NOT_MET",
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

    private static List<String> ids(JsonNode observations) {
        List<String> ids = new ArrayList<>();
        observations.forEach(node -> ids.add(node.get("id").asString()));
        return ids;
    }

    private UUID observe(
            AgentJob review,
            Practice practice,
            long mergeRequestId,
            long aboutUserId,
            String outcome,
            @Nullable String severity,
            String evidence,
            Instant observedAt) {
        return observe(review, practice, mergeRequestId, aboutUserId, outcome, severity, evidence, observedAt, "");
    }

    /** One observation on a merge request; with its explicit outcome. */
    private UUID observe(
            AgentJob review,
            Practice practice,
            long mergeRequestId,
            long aboutUserId,
            String outcome,
            @Nullable String severity,
            String evidence,
            Instant observedAt,
            String rationale) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                "occ-" + id,
                review.getId(),
                workspace.getId(),
                practice.getId(),
                practice.getCurrentRevision().getId(),
                ArtifactKinds.PULL_REQUEST.value(),
                mergeRequestId,
                aboutUserId,
                "Observation " + id,
                outcome,
                severity,
                evidence,
                rationale,
                null,
                observedAt,
                "LIVE");
        return id;
    }

    private Practice practice(String slug) {
        Practice created = new Practice();
        created.setWorkspace(workspace);
        created.setSlug(slug);
        created.setName(slug);
        created.setCriteria("Criteria");
        created.setAutomatedReviewPolicy(PracticeTestEvidence.conversationThread());
        PracticeTestEvidence.configure(created, ScmSignals.PULL_REQUEST_OPENED);
        created = practiceRepository.saveAndFlush(created);
        created.setCurrentRevision(practiceRevisionRepository.save(new PracticeRevision(created, 1)));
        return practiceRepository.saveAndFlush(created);
    }
}
