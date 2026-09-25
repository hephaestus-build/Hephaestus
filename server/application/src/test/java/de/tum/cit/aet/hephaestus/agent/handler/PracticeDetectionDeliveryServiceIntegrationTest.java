package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
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
import de.tum.cit.aet.hephaestus.testconfig.AdmittedReviewJobFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * The reviewed change is quoted from a real checkout staged in the attempt folder, the way the
 * repository-tree source stages one, so the quote is verified through JGit rather than a double.
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
    private LlmConnectionRepository llmConnectionRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    @Autowired
    private WorkspaceAgentBindingRepository workspaceAgentBindingRepository;

    @Autowired
    private LlmModelResolver llmModelResolver;

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
    private JobEvidenceFiles evidenceFiles;

    @Autowired
    private de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout evidenceLayout;

    private final java.util.List<PreparedJobInputs> preparedEvidence = new java.util.ArrayList<>();

    private static final String HEAD_PATH = SandboxLayout.REPO_MOUNT_RELATIVE + ".git/HEAD";
    private static final String REFS_PATH = SandboxLayout.REPO_MOUNT_RELATIVE + ".git/hephaestus-captured-refs";

    @TempDir
    private Path temporary;

    private Workspace workspace;
    private AgentJob agentJob;
    private User developer;
    private Long prId;
    private String baseSha;
    private String headSha;

    @org.junit.jupiter.api.AfterEach
    void releasePreparedEvidence() throws Exception {
        preparedEvidence.forEach(PreparedJobInputs::close);
        preparedEvidence.clear();
        deleteFixtureEvidence();
    }

    private void deleteFixtureEvidence() throws Exception {
        org.apache.commons.io.FileUtils.deleteDirectory(evidenceLayout
                .jobsRoot()
                .resolve(workspace.getId().toString())
                .resolve(agentJob.getId().toString())
                .toFile());
    }

    @BeforeEach
    void setUp() throws Exception {
        databaseTestUtils.cleanDatabase();

        workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("delivery-test"));

        Practice description = createPractice("pr-description-quality", "PR Description Quality");
        Practice errors = createPractice("error-handling", "Error Handling");

        agentJob = new AgentJob();
        agentJob.setWorkspace(workspace);
        agentJob.setWorkerId("test-worker");
        agentJob.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        agentJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        agentJob.setConfigSnapshot(AdmittedReviewJobFixtures.snapshot(
                workspace,
                llmConnectionRepository,
                llmModelRepository,
                workspaceAgentBindingRepository,
                llmModelResolver,
                OBJECT_MAPPER));
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
        // The change: line 10 of src/Auth.java reads requireAdmin() at the base and insecure() at the head.
        Path checkout = Files.createDirectory(temporary.resolve("repo"));
        String treeSha;
        try (Git git = Git.init()
                .setInitialBranch("main")
                .setDirectory(checkout.toFile())
                .call()) {
            GitTestFixtures.disableSigning(git.getRepository());
            Files.createDirectories(checkout.resolve("src"));
            Files.writeString(checkout.resolve("src/Auth.java"), authSource("requireAdmin();"));
            baseSha = commit(git, "Base").name();
            Files.writeString(checkout.resolve("src/Auth.java"), authSource("insecure();"));
            RevCommit head = commit(git, "Head");
            headSha = head.name();
            treeSha = head.getTree().name();
        }
        Files.writeString(checkout.resolve(".git/hephaestus-captured-refs"), headSha + " HEAD\n");
        byte[] headWitness = Files.readAllBytes(checkout.resolve(".git/HEAD"));
        byte[] refsWitness = Files.readAllBytes(checkout.resolve(".git/hephaestus-captured-refs"));
        byte[] change = ("{\"base_sha\":\"" + baseSha + "\",\"head_sha\":\"" + headSha + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        ObjectNode snapshot = EvidenceSnapshotFixtures.snapshot(OBJECT_MAPPER);
        var diff = EvidenceSnapshotFixtures.availableSource(snapshot, "scm.pull-request.diff", baseSha + ":" + headSha);
        EvidenceSnapshotFixtures.artifact(
                        diff, PullRequestContentSource.CHANGE_FILE, ProvenanceDigest.sha256Hex(change))
                .put("mediaType", "application/json")
                .put("bytes", change.length);
        var tree = EvidenceSnapshotFixtures.availableSource(snapshot, "scm.repository.tree", headSha + ":" + treeSha);
        EvidenceSnapshotFixtures.artifact(tree, HEAD_PATH, ProvenanceDigest.sha256Hex(headWitness))
                .put("bytes", headWitness.length);
        EvidenceSnapshotFixtures.artifact(tree, REFS_PATH, ProvenanceDigest.sha256Hex(refsWitness))
                .put("bytes", refsWitness.length);
        EvidenceSnapshotFixtures.admittedPractice(
                snapshot,
                description.getSlug(),
                java.util.Objects.requireNonNull(
                        description.getCurrentRevision().getId()));
        EvidenceSnapshotFixtures.admittedPractice(
                snapshot,
                errors.getSlug(),
                java.util.Objects.requireNonNull(errors.getCurrentRevision().getId()));
        preparedEvidence.add(evidenceFiles.prepare(
                agentJob,
                new PreparedJobInputs(
                        new PreparedEvidence(
                                Map.of(PullRequestContentSource.CHANGE_FILE, change),
                                Map.of(
                                        HEAD_PATH,
                                        checkout.resolve(".git/HEAD"),
                                        REFS_PATH,
                                        checkout.resolve(".git/hephaestus-captured-refs")),
                                List.of(),
                                null,
                                List.of(new EvidenceDirectory(SandboxLayout.REPO_MOUNT_RELATIVE, checkout))),
                        null)));
        agentJob.setEvidenceSnapshot(snapshot);
        agentJob = agentJobRepository.save(agentJob);
    }

    /** Nine lines of context, then {@code line10} at line 10. */
    private static String authSource(String line10) {
        return "// context\n".repeat(9) + line10 + "\n";
    }

    private static RevCommit commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit()
                .setSign(false)
                .setMessage(message)
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call();
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
                    case ABSENT -> Assessment.GOOD;
                    case null -> null;
                };
        return new ValidatedObservation(
                slug,
                "Test: " + slug,
                presence == null ? AssessmentStatus.NOT_APPLICABLE : AssessmentStatus.ASSESSED,
                presence,
                assessment,
                presence == Presence.ABSENT ? Severity.MINOR : null,
                evidence(presence),
                null);
    }

    private static ObjectNode evidence(@Nullable Presence presence) {
        ObjectNode evidence = OBJECT_MAPPER.createObjectNode();
        evidence.putArray("citations")
                .addObject()
                .put("sourceKind", "scm.pull-request.diff")
                .put("artifactPath", PullRequestContentSource.CHANGE_FILE)
                .put("path", "src/Auth.java")
                .put("side", "NEW")
                .put("startLine", 10)
                .put("endLine", 10)
                .put("quote", "insecure();");
        // An ABSENT observation asserts a universal, so delivery requires it to record its search.
        if (presence == Presence.ABSENT) {
            ObjectNode search = evidence.putObject("search");
            search.putArray("consulted").add("scm.pull-request.diff");
            search.put("lookedFor", "a described rationale for the change");
            search.put("boundary", "the diff of this pull request only");
        }
        return evidence;
    }

    private PracticeDetectionDeliveryService.RecordedObservations publishVerified(
            AgentJob job, List<PracticeDetectionResultParser.ValidatedObservation> submitted) {
        return deliveryService.publish(job, deliveryService.prepare(job, submitted));
    }

    @Nested
    class EndToEnd {

        @Test
        void validObservationsPersistedToDb() {
            var observations = List.of(
                    observation("pr-description-quality", Presence.PRESENT),
                    observation("error-handling", Presence.ABSENT));

            var result = publishVerified(agentJob, observations);

            assertThat(result.inserted()).isEqualTo(2);
            assertThat(result.hasNegative()).isTrue();

            List<Observation> persisted = observationRepository.findAll();
            assertThat(persisted).hasSize(2);
            assertThat(persisted)
                    .extracting(Observation::getPresence)
                    .containsExactlyInAnyOrder(Presence.PRESENT, Presence.ABSENT);
        }

        @Test
        void shouldKeepPublishedObservationsVerifiedAfterCapturedBytesAreDeleted() throws Exception {
            publishVerified(agentJob, List.of(observation("error-handling", Presence.ABSENT)));
            String sha = java.util.Objects.requireNonNull(agentJob.getEvidenceSnapshot())
                    .at("/manifest/sources/0/artifacts/0/sha256")
                    .asString();

            deleteFixtureEvidence();

            assertThat(evidenceFiles.inspect(
                            agentJob, PullRequestContentSource.CHANGE_FILE, sha, reader -> Boolean.TRUE))
                    .isEmpty();
            assertThatCode(() -> deliveryService.requirePublished(agentJob)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a quote of the change is read from the captured checkout at the side's revision")
        void shouldVerifyEachSideOfTheChangeAgainstTheCapturedCheckout() {
            var atHead = observation("pr-description-quality", Presence.PRESENT);
            var atBase = observation("error-handling", Presence.PRESENT);
            ((ObjectNode) java.util.Objects.requireNonNull(atBase.evidence())
                            .withArray("citations")
                            .get(0))
                    .put("side", "OLD")
                    .put("quote", "requireAdmin();");

            var result = publishVerified(agentJob, List.of(atHead, atBase));

            assertThat(result.inserted()).isEqualTo(2);
            assertThat(observationRepository.findAll())
                    .extracting(persisted -> java.util.Objects.requireNonNull(persisted.getEvidence())
                            .at("/citations/0/revision")
                            .asString())
                    .containsExactlyInAnyOrder(headSha, baseSha);
            assertThatCode(() -> deliveryService.requirePublished(agentJob)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a quote the checkout does not hold at the cited side is withheld, and the sound claim delivered")
        void shouldWithholdAQuoteTheCheckoutDoesNotHold() {
            var sound = observation("pr-description-quality", Presence.PRESENT);
            var wrongSide = observation("error-handling", Presence.PRESENT);
            ((ObjectNode) java.util.Objects.requireNonNull(wrongSide.evidence())
                            .withArray("citations")
                            .get(0))
                    .put("side", "OLD");

            var result = publishVerified(agentJob, List.of(sound, wrongSide));

            assertThat(result.inserted()).isEqualTo(1);
            assertThat(observationRepository.findAll())
                    .extracting(persisted -> persisted.getPractice().getSlug())
                    .containsExactly("pr-description-quality");
        }

        @Test
        @DisplayName("returned delivered observations align exactly with the persisted recurrence_key set")
        void returnedFingerprintsMatchPersistedRecurrenceKeys() {
            var observations = List.of(
                    observation("pr-description-quality", Presence.PRESENT),
                    observation("error-handling", Presence.ABSENT));

            var result = publishVerified(agentJob, observations);

            assertThat(result.recorded().stream().map(o -> o.recurrenceKey()).toList())
                    .as("one stable key returned per delivered observation")
                    .hasSize(2)
                    .allMatch(k -> k != null && k.matches("[0-9a-f]{64}"));

            List<String> persistedKeys = observationRepository.findAll().stream()
                    .map(Observation::getRecurrenceKey)
                    .toList();
            assertThat(persistedKeys)
                    .as("every returned fingerprint is persisted as a recurrence_key, and vice versa")
                    .containsExactlyInAnyOrderElementsOf(result.recorded().stream()
                            .map(o -> o.recurrenceKey())
                            .toList());
        }

        @Test
        @DisplayName("re-delivering same job creates no duplicates")
        void idempotentRedelivery() {
            var observations = List.of(observation("pr-description-quality", Presence.PRESENT));

            var first = publishVerified(agentJob, observations);
            var second = publishVerified(agentJob, observations);

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

            assertThatThrownBy(() -> publishVerified(agentJob, observations))
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

            var result = publishVerified(agentJob, observations);

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
                        Assessment.GOOD,
                        Severity.MINOR,
                        evidence(Presence.ABSENT),
                        null));
            }

            var result = publishVerified(agentJob, observations);

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

            publishVerified(agentJob, observations);

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
        void shouldRefuseRatherThanPublishWhenNoObservationSurvived() {
            assertThatThrownBy(() -> publishVerified(agentJob, List.of()))
                    .isInstanceOf(ObservationsRefusedException.class);
            assertThat(applicationEvents.stream(PracticeDetectionCompletedEvent.class))
                    .isEmpty();
        }
    }

    @Nested
    class NonNegativeObservations {

        @Test
        void positiveObservationsDoNotTriggerHasNegative() {
            var observations = List.of(
                    observation("pr-description-quality", Presence.PRESENT),
                    observation("error-handling", Presence.PRESENT));

            var result = publishVerified(agentJob, observations);

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

            assertThatThrownBy(() -> publishVerified(agentJob, observations))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("Pull request not found");
        }
    }
}
