package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.ContentAddressedStore;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeDetectionCompletedEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Integration test for {@link PracticeDetectionDeliveryService} exercising real PostgreSQL
 * for observation persistence (INSERT ... ON CONFLICT DO NOTHING), negative cap enforcement,
 * observation classification, and {@link PracticeDetectionCompletedEvent} publication.
 *
 * <p>No mocks required — this service layer does not call external APIs. It resolves practice
 * slugs against the DB and persists observations via {@code ObservationRepository.insertIfAbsent()}.
 */
@RecordApplicationEvents
class PracticeDetectionDeliveryServiceIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private PracticeDetectionDeliveryService deliveryService;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private ApplicationEvents applicationEvents;

    @Autowired
    private ContentAddressedStore cas;

    private Workspace workspace;
    private AgentJob agentJob;
    private User developer;
    private Long prId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();

        workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("delivery-test"));

        Practice description = createPractice("pr-description-quality", "PR Description Quality");
        Practice errors = createPractice("error-handling", "Error Handling");

        agentJob = new AgentJob();
        agentJob.setWorkspace(workspace);
        agentJob.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        agentJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        agentJob.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        agentJob = agentJobRepository.save(agentJob);

        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        developer = TestUserFactory.createUser(200L, "test-pr-author", provider);
        developer = userRepository.save(developer);

        Repository repo = new Repository();
        repo.setNativeId(1001L);
        repo.setProvider(provider);
        repo.setName("test-repo");
        repo.setNameWithOwner("org/test-repo");
        repo.setHtmlUrl("https://github.com/org/test-repo");
        repo.setDefaultBranch("main");
        repo = repositoryRepository.save(repo);

        Instant now = Instant.now();
        Long providerId = java.util.Objects.requireNonNull(provider.getId());
        pullRequestRepository.upsertCore(
                5001L,
                providerId,
                42,
                "Test PR",
                "Test body",
                "OPEN",
                null,
                "https://github.com/org/test-repo/pull/42",
                false,
                null,
                0,
                now,
                now,
                now,
                developer.getId(),
                repo.getId(),
                null,
                null,
                false,
                false,
                1,
                10,
                5,
                3,
                null,
                null,
                null,
                "feature/test",
                "main",
                "abc123",
                "def456",
                null,
                null // mergeCommitSha
                );
        prId = pullRequestRepository
                .findByRepositoryIdAndNumber(repo.getId(), 42)
                .orElseThrow()
                .getId();

        ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
        metadata.put("pull_request_id", prId);
        metadata.put("repository_id", repo.getId());
        metadata.put("repository_full_name", repo.getNameWithOwner());
        metadata.put("pr_number", 42);
        agentJob.setMetadata(metadata);
        ObjectNode snapshot = OBJECT_MAPPER.createObjectNode();
        var source = snapshot.putObject("manifest")
                .put("contractVersion", "1.0.0")
                .putArray("sources")
                .addObject()
                .put("kind", "scm.pull-request.diff");
        source.putObject("state")
                .put("availability", "AVAILABLE")
                .put("content", "NON_EMPTY")
                .put("completeness", "COMPLETE")
                .putObject("facts")
                .put("capturedAt", "2026-08-03T00:00:00Z")
                .put("immutableIdentity", "abc123");
        byte[] diff =
                "diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] + insecure();\n"
                        .getBytes(StandardCharsets.UTF_8);
        source.putArray("artifacts")
                .addObject()
                .put("path", "inputs/context/diff.patch")
                .put("mediaType", "text/x-diff")
                .put("sha256", cas.put(diff))
                .put("bytes", diff.length);
        var admitted = snapshot.putArray("practices");
        admitted.addObject()
                .put("slug", description.getSlug())
                .put("revisionId", description.getCurrentRevision().getId());
        admitted.addObject()
                .put("slug", errors.getSlug())
                .put("revisionId", errors.getCurrentRevision().getId());
        agentJob.setEvidenceSnapshot(snapshot);
        agentJob = agentJobRepository.save(agentJob);
    }

    private Practice createPractice(String slug, String name) {
        Practice p = new Practice();
        p.setWorkspace(workspace);
        p.setSlug(slug);
        p.setName(name);
        p.setCriteria("Test " + slug);
        p.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
        p.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        p = practiceRepository.saveAndFlush(p);
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(p, 1));
        p.setCurrentRevision(revision);
        return practiceRepository.saveAndFlush(p);
    }

    /**
     * Build an observation whose valence follows the former-GOOD practice convention used by these
     * fixtures (pr-description-quality, error-handling): PRESENT→GOOD, ABSENT→BAD, NOT_APPLICABLE→null.
     */
    private ValidatedObservation observation(String slug, @Nullable Presence presence) {
        Assessment assessment =
                switch (presence) {
                    case PRESENT -> Assessment.GOOD;
                    case ABSENT -> Assessment.BAD;
                    case null -> null;
                };
        return new ValidatedObservation(
                slug,
                "Test: " + slug,
                presence == null ? AssessmentStatus.NOT_APPLICABLE : AssessmentStatus.ASSESSED,
                presence,
                assessment,
                assessment == Assessment.BAD ? Severity.MINOR : null,
                evidence(presence),
                null);
    }

    private static ObjectNode evidence(@Nullable Presence presence) {
        ObjectNode evidence = OBJECT_MAPPER.createObjectNode();
        evidence.putArray("citations")
                .addObject()
                .put("sourceKind", "scm.pull-request.diff")
                .put("artifactPath", "inputs/context/diff.patch")
                .put("path", "src/Auth.java")
                .put("side", "NEW")
                .put("startLine", 10)
                .put("endLine", 10)
                .put("quote", "+ insecure();");
        // An ABSENT observation asserts a universal, so delivery requires it to record its search.
        if (presence == Presence.ABSENT) {
            ObjectNode search = evidence.putObject("search");
            search.putArray("consulted").add("scm.pull-request.diff");
            search.put("lookedFor", "a described rationale for the change");
            search.put("boundary", "the diff of this pull request only");
        }
        return evidence;
    }

    @Nested
    class EndToEnd {

        @Test
        void validObservationsPersistedToDb() {
            var observations = List.of(
                    observation("pr-description-quality", Presence.PRESENT),
                    observation("error-handling", Presence.ABSENT));

            var result = deliveryService.deliver(agentJob, observations);

            assertThat(result.inserted()).isEqualTo(2);
            assertThat(result.hasNegative()).isTrue();

            List<Observation> persisted = observationRepository.findAll();
            assertThat(persisted).hasSize(2);
            assertThat(persisted)
                    .extracting(Observation::getPresence)
                    .containsExactlyInAnyOrder(Presence.PRESENT, Presence.ABSENT);
        }

        @Test
        @DisplayName("returned delivered observations align exactly with the persisted recurrence_key set")
        void returnedFingerprintsMatchPersistedRecurrenceKeys() {
            var observations = List.of(
                    observation("pr-description-quality", Presence.PRESENT),
                    observation("error-handling", Presence.ABSENT));

            var result = deliveryService.deliver(agentJob, observations);

            assertThat(result.delivered().stream().map(o -> o.recurrenceKey()).toList())
                    .as("one stable key returned per delivered observation")
                    .hasSize(2)
                    .allMatch(k -> k != null && k.matches("[0-9a-f]{64}"));

            List<String> persistedKeys = observationRepository.findAll().stream()
                    .map(Observation::getRecurrenceKey)
                    .toList();
            assertThat(persistedKeys)
                    .as("every returned fingerprint is persisted as a recurrence_key, and vice versa")
                    .containsExactlyInAnyOrderElementsOf(result.delivered().stream()
                            .map(o -> o.recurrenceKey())
                            .toList());
        }

        @Test
        @DisplayName("re-delivering same job creates no duplicates")
        void idempotentRedelivery() {
            var observations = List.of(observation("pr-description-quality", Presence.PRESENT));

            var first = deliveryService.deliver(agentJob, observations);
            var second = deliveryService.deliver(agentJob, observations);

            assertThat(first.inserted()).isEqualTo(1);
            assertThat(second.inserted()).isZero();
            assertThat(second.discardedDuplicate()).isEqualTo(1);
            assertThat(observationRepository.findAll()).hasSize(1);
        }
    }

    @Nested
    class PracticeResolution {

        @Test
        void unknownSlugsFailDelivery() {
            var observations = List.of(
                    observation("pr-description-quality", Presence.PRESENT),
                    observation("nonexistent-practice", Presence.PRESENT));

            assertThatThrownBy(() -> deliveryService.deliver(agentJob, observations))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("not admitted");
            assertThat(observationRepository.findAll()).isEmpty();
        }
    }

    @Nested
    class RevisionPinning {

        @Test
        @DisplayName("persisted observation pins the practice's current definition revision")
        void findingPinsCurrentRevision() {
            Practice practice = practiceRepository
                    .findByWorkspaceIdAndSlug(workspace.getId(), "pr-description-quality")
                    .orElseThrow();
            PracticeRevision revision = practice.getCurrentRevision();

            var observations = List.of(observation("pr-description-quality", Presence.PRESENT));

            var result = deliveryService.deliver(agentJob, observations);

            assertThat(result.inserted()).isEqualTo(1);

            List<Observation> persisted = observationRepository.findAll();
            assertThat(persisted).hasSize(1);
            Observation only = persisted.get(0);
            assertThat(only.getPracticeRevision()).isNotNull();
            assertThat(only.getPracticeRevision().getId()).isEqualTo(revision.getId());
        }
    }

    @Nested
    class DistinctBadFindingsAllPersisted {

        @Test
        void persistsEveryDistinctBadObservation() {
            // Each idempotency key includes the index, so all 7 are distinct: there is no per-practice cap.
            var observations = new ArrayList<ValidatedObservation>();
            for (int i = 0; i < 7; i++) {
                observations.add(new ValidatedObservation(
                        "pr-description-quality",
                        "Negative observation " + i,
                        AssessmentStatus.ASSESSED,
                        Presence.ABSENT,
                        Assessment.BAD,
                        Severity.MINOR,
                        evidence(Presence.ABSENT),
                        null));
            }

            var result = deliveryService.deliver(agentJob, observations);

            assertThat(result.inserted()).isEqualTo(7);
            assertThat(result.discardedDuplicate()).isEqualTo(0);
            assertThat(observationRepository.findAll()).hasSize(7);
        }
    }

    @Nested
    class EventPublication {

        @Test
        void publishesEvent() {
            var observations = List.of(observation("pr-description-quality", Presence.PRESENT));

            deliveryService.deliver(agentJob, observations);

            List<PracticeDetectionCompletedEvent> events = applicationEvents.stream(
                            PracticeDetectionCompletedEvent.class)
                    .toList();
            assertThat(events).hasSize(1);
            PracticeDetectionCompletedEvent event = events.get(0);
            assertThat(event.agentJobId()).isEqualTo(agentJob.getId());
            assertThat(event.workspaceId()).isEqualTo(workspace.getId());
            assertThat(event.artifactKind()).isEqualTo(ArtifactKinds.PULL_REQUEST);
            assertThat(event.artifactId()).isEqualTo(prId);
            assertThat(event.observationsInserted()).isEqualTo(1);
            assertThat(event.observationsDiscarded()).isZero();
            assertThat(event.hasNegative()).isFalse();
            assertThat(event.developerId()).isEqualTo(developer.getId());
        }

        @Test
        void emptyFindingsPublishesZeroEvent() {
            var result = deliveryService.deliver(agentJob, List.of());

            assertThat(result.inserted()).isZero();
            assertThat(result.hasNegative()).isFalse();

            List<PracticeDetectionCompletedEvent> events = applicationEvents.stream(
                            PracticeDetectionCompletedEvent.class)
                    .toList();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).observationsInserted()).isZero();
            assertThat(events.get(0).observationsDiscarded()).isZero();
            assertThat(events.get(0).hasNegative()).isFalse();
        }
    }

    @Nested
    class NonNegativeObservations {

        @Test
        void positiveObservationsDoNotTriggerHasNegative() {
            var observations = List.of(
                    observation("pr-description-quality", Presence.PRESENT),
                    observation("error-handling", Presence.PRESENT));

            var result = deliveryService.deliver(agentJob, observations);

            assertThat(result.inserted()).isEqualTo(2);
            assertThat(result.hasNegative()).isFalse();

            List<Observation> persisted = observationRepository.findAll();
            assertThat(persisted).hasSize(2);
            assertThat(persisted)
                    .extracting(Observation::getPresence)
                    .containsExactlyInAnyOrder(Presence.PRESENT, Presence.PRESENT);
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void throwsWhenPrNotFound() {
            ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
            metadata.put("pull_request_id", 999999L);
            agentJob.setMetadata(metadata);
            agentJob = agentJobRepository.save(agentJob);

            var observations = List.of(observation("pr-description-quality", Presence.PRESENT));

            assertThatThrownBy(() -> deliveryService.deliver(agentJob, observations))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("Pull request not found");
        }
    }
}
