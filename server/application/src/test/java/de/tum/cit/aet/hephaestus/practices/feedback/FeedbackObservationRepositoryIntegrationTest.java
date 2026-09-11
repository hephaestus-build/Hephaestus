package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.ObservationFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

class FeedbackObservationRepositoryIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> IN_CONTEXT_ONLY = List.of(FeedbackChannel.IN_CONTEXT.name());

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    private Workspace workspace;
    private Practice practice;
    private AgentJob agentJob;
    private User recipient;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();

        workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("feedback-observation-test"));

        practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(workspace);
        practice.setSlug("test-practice");
        practice.setName("Test Practice");
        practice.setCriteria("Test description");
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice = practiceRepository.save(practice);

        agentJob = new AgentJob();
        agentJob.setWorkspace(workspace);
        agentJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        agentJob.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        agentJob = agentJobRepository.save(agentJob);

        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        recipient = userRepository.save(TestUserFactory.createUser(100L, "recipient", provider));
    }

    @Test
    @DisplayName(
            "insertIfAbsent is idempotent: a second insert on the same (feedback, observation) returns 0 and keeps the original role/ordinal")
    void insertIfAbsentIsIdempotent() {
        Feedback feedback = saveFeedback(0, FeedbackDeliveryState.DELIVERED, "Delivered advice body");
        Observation observation = saveObservation("obs-1");

        int first = feedbackObservationRepository.insertIfAbsent(feedback.getId(), observation.getId(), "PRIMARY", 0);
        int second =
                feedbackObservationRepository.insertIfAbsent(feedback.getId(), observation.getId(), "SUPPORTING", 7);

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);

        assertThat(feedbackObservationRepository.findAll()).hasSize(1);
        FeedbackObservation row = feedbackObservationRepository.findAll().get(0);
        assertThat(row.getRole()).isEqualTo(EvidenceRole.PRIMARY);
        assertThat(row.getOrdinal()).isEqualTo(0);
        assertThat(row.getFeedback().getId()).isEqualTo(feedback.getId());
        assertThat(row.getObservation().getId()).isEqualTo(observation.getId());
    }

    @Test
    @DisplayName("findLatestFeedbackBodiesByObservationIds returns DELIVERED and FAILED bodies and excludes PREPARED, "
            + "SUPPRESSED, and null-body units")
    void findAdviceBodiesIncludesFailedExcludesPreparedSuppressed() {
        Observation delivered = saveObservation("obs-delivered");
        Observation failed = saveObservation("obs-failed");
        Observation prepared = saveObservation("obs-prepared");
        Observation suppressed = saveObservation("obs-suppressed");
        Observation nullBody = saveObservation("obs-null-body");

        bind(saveFeedback(0, FeedbackDeliveryState.DELIVERED, "The advice the student saw"), delivered);
        bind(saveFeedback(4000, FeedbackDeliveryState.FAILED, "The advice the direct post could not place"), failed);
        bind(saveFeedback(1, FeedbackDeliveryState.PREPARED, "Not yet delivered"), prepared);
        bind(saveFeedback(2, FeedbackDeliveryState.SUPPRESSED, "Withheld"), suppressed);
        bind(saveFeedback(3, FeedbackDeliveryState.DELIVERED, null), nullBody);

        List<ObservationFeedbackBody> bodies = feedbackObservationRepository.findLatestFeedbackBodiesByObservationIds(
                workspace.getId(),
                List.of(delivered.getId(), failed.getId(), prepared.getId(), suppressed.getId(), nullBody.getId()),
                IN_CONTEXT_ONLY);

        assertThat(bodies).hasSize(2);
        Map<UUID, String> byObservation = bodies.stream()
                .collect(Collectors.toMap(ObservationFeedbackBody::getObservationId, ObservationFeedbackBody::getBody));
        assertThat(byObservation)
                .containsEntry(delivered.getId(), "The advice the student saw")
                .containsEntry(failed.getId(), "The advice the direct post could not place")
                .doesNotContainKeys(prepared.getId(), suppressed.getId(), nullBody.getId());
    }

    @Test
    void findLatestFeedbackBodiesUsesIdAsDeterministicTieBreak() {
        Observation observation = saveObservation("obs-repeated");
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higherId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        bind(saveFeedback(lowerId, 10, FeedbackDeliveryState.DELIVERED, "Earlier identity", createdAt), observation);
        bind(saveFeedback(higherId, 11, FeedbackDeliveryState.DELIVERED, "Latest identity", createdAt), observation);

        List<ObservationFeedbackBody> bodies = feedbackObservationRepository.findLatestFeedbackBodiesByObservationIds(
                workspace.getId(), List.of(observation.getId()), IN_CONTEXT_ONLY);

        assertThat(bodies)
                .singleElement()
                .extracting(ObservationFeedbackBody::getBody)
                .isEqualTo("Latest identity");
    }

    @Test
    @DisplayName("a newer IN_APP unit bound to the same observation does not become its advice: the per-finding "
            + "surfaces keep showing what was said about that finding")
    void findLatestFeedbackBodiesAnswersOnlyForTheChannelsTheCallerNames() {
        Observation observation = saveObservation("obs-both-lanes");
        bind(
                saveFeedback(
                        null,
                        20,
                        FeedbackDeliveryState.DELIVERED,
                        "The note posted on the pull request",
                        Instant.parse("2026-01-01T00:00:00Z")),
                observation);
        // The in-app unit is newer, DELIVERED and non-null-bodied, so it wins every other clause of
        // the query — the channel predicate is the only thing keeping it off a per-finding surface.
        bind(
                saveFeedback(
                        null,
                        7000,
                        FeedbackDeliveryState.DELIVERED,
                        "### You keep shipping untested changes\n\nAcross three pull requests…\n\n**Try next:** …",
                        Instant.parse("2026-02-01T00:00:00Z"),
                        FeedbackChannel.IN_APP),
                observation);

        assertThat(feedbackObservationRepository.findLatestFeedbackBodiesByObservationIds(
                        workspace.getId(), List.of(observation.getId()), IN_CONTEXT_ONLY))
                .singleElement()
                .extracting(ObservationFeedbackBody::getBody)
                .isEqualTo("The note posted on the pull request");

        assertThat(feedbackObservationRepository.findLatestFeedbackBodiesByObservationIds(
                        workspace.getId(), List.of(observation.getId()), List.of(FeedbackChannel.IN_APP.name())))
                .singleElement()
                .extracting(ObservationFeedbackBody::getBody)
                .asString()
                .startsWith("### You keep shipping untested changes");
    }

    @Test
    @DisplayName("deleting the parent Feedback cascades the join row away (ON DELETE CASCADE)")
    void deletingFeedbackCascadesJoinRow() {
        Feedback feedback = saveFeedback(0, FeedbackDeliveryState.DELIVERED, "Body");
        Observation observation = saveObservation("obs-cascade");
        bind(feedback, observation);
        assertThat(feedbackObservationRepository.findAll()).hasSize(1);

        feedbackRepository.deleteById(feedback.getId());
        feedbackRepository.flush();

        assertThat(feedbackObservationRepository.findAll()).isEmpty();
        assertThat(observationRepository.findById(observation.getId())).isPresent();
    }

    private void bind(Feedback feedback, Observation observation) {
        feedbackObservationRepository.insertIfAbsent(feedback.getId(), observation.getId(), "PRIMARY", 0);
    }

    private Feedback saveFeedback(int position, FeedbackDeliveryState state, @Nullable String body) {
        return saveFeedback(null, position, state, body, Instant.now());
    }

    private Feedback saveFeedback(
            @Nullable UUID id, int position, FeedbackDeliveryState state, @Nullable String body, Instant createdAt) {
        return saveFeedback(id, position, state, body, createdAt, FeedbackChannel.IN_CONTEXT);
    }

    private Feedback saveFeedback(
            @Nullable UUID id,
            int position,
            FeedbackDeliveryState state,
            @Nullable String body,
            Instant createdAt,
            FeedbackChannel channel) {
        boolean anchored = channel == FeedbackChannel.IN_CONTEXT;
        return feedbackRepository.save(Feedback.builder()
                .id(id)
                .agentJobId(agentJob.getId())
                .workspaceId(workspace.getId())
                .artifactKind(anchored ? ArtifactKinds.PULL_REQUEST : null)
                .artifactId(anchored ? 42L : null)
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(channel)
                .position(position)
                .deliveryState(state)
                .body(body)
                .source(FeedbackSource.AGENT)
                .createdAt(createdAt)
                .deliveredAt(state == FeedbackDeliveryState.DELIVERED ? Instant.now() : null)
                .build());
    }

    private Observation saveObservation(String occurrenceKey) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                occurrenceKey,
                agentJob.getId(),
                agentJob.getWorkspace().getId(),
                practice.getId(),
                null,
                "scm.pull_request",
                42L,
                recipient.getId(),
                "Observation title",
                "ASSESSED",
                "ABSENT",
                "BAD",
                "MAJOR",
                null,
                null,
                null,
                Instant.now(),
                "LIVE");
        return observationRepository.findById(id).orElseThrow();
    }
}
