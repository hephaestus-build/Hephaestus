package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.context.AttemptInputs;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndexBuilder;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxTestLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CheckoutKey;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryLockManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitTransportFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReviewPublishedPreparationTest extends BaseUnitTest {
    private static final RepositoryKey KEY = new RepositoryKey(100L, 1L);
    private static final String CHECKOUT = "repos/reviewed/";
    private final JsonMapper mapper = new JsonMapper();
    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final DefaultReviewExecutionHandler handler = mock(DefaultReviewExecutionHandler.class);
    private final PracticePiAdapter adapter = mock(PracticePiAdapter.class);
    private final AttemptTokenIssuer issuer = mock(AttemptTokenIssuer.class);
    private final AttemptInputs inputs = mock(AttemptInputs.class);
    private final AtomicInteger captures = new AtomicInteger();
    private final AtomicInteger ended = new AtomicInteger(2);
    private final Map<String, byte[]> frozen = new LinkedHashMap<>();
    private final List<Instant> tokenDeadlines = new ArrayList<>();
    private final AtomicReference<PracticeSandboxSpec> program = new AtomicReference<>(program("original"));
    private final TransactionTemplate transactions = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private final ReviewJob job = RuntimeReviewTestData.job();

    @TempDir
    Path root;

    private GitRepositoryManager git;
    private String head;
    private JobEvidenceFiles first;
    private AgentRunFence fence;

    @BeforeEach
    void prepareFrozenSnapshot() throws Exception {
        job.setWorkspaceId(KEY.workspaceId());
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setRunId(UUID.randomUUID());
        fence = new AgentRunFence(job.getRunId(), job.getWorkspaceId(), 1);
        var source = Files.createDirectories(root.resolve("source"));
        try (var repository = Git.init()
                .setInitialBranch("main")
                .setDirectory(source.toFile())
                .call()) {
            GitTestFixtures.disableSigning(repository.getRepository());
            Files.writeString(source.resolve("README.md"), "Original repository\n");
            repository.add().addFilepattern(".").call();
            head = repository
                    .commit()
                    .setSign(false)
                    .setMessage("Initial")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call()
                    .name();
            repository.branchCreate().setName("other-work").setStartPoint(head).call();
        }
        git = new GitRepositoryManager(
                new GitRepositoryProperties(true, 2, 1L << 30, 1L << 40),
                new GitRepositoryLockManager(),
                new FabricLayout(root.resolve("mirror").toString()),
                GitTransportFixtures.guarded(),
                mock(RepositoryRefState.class),
                GitTransportFixtures.copyFence());
        git.ensureRepository(KEY, source.toUri().toString(), null);
        first = files("first");
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.databaseClock()).thenReturn(Instant.parse("2026-11-13T12:00:00.123456Z"));
        when(jobs.markExecutionStarted(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            job.setExecutionStartedAt(invocation.getArgument(3));
            return 1;
        });
        when(jobs.updateCapture(any(), anyLong(), any(), anyInt(), any())).thenAnswer(invocation -> {
            job.setCapture(invocation.getArgument(4));
            return 1;
        });
        when(adapter.buildSandboxSpec(any())).thenAnswer(unused -> program.get());
        when(issuer.issueForReview(any(), anyLong(), anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    tokenDeadlines.add(invocation.getArgument(4));
                    return "attempt-token";
                });
        when(handler.endedPractices(job)).thenAnswer(unused -> ended.get());
        when(handler.prepareInputs(job)).thenAnswer(unused -> capture());
        doAnswer(invocation -> {
                    Map<String, Path> paths = invocation.getArgument(3);
                    for (var entry : paths.entrySet()) frozen.put(entry.getKey(), Files.readAllBytes(entry.getValue()));
                    return null;
                })
                .when(inputs)
                .record(any(), anyLong(), anyInt(), anyMap());
        when(inputs.list(any()))
                .thenAnswer(unused -> frozen.entrySet().stream()
                        .map(entry -> new AttemptInputs.RecordedFile(
                                entry.getKey(), ProvenanceDigest.sha256Hex(entry.getValue()), "r--------"))
                        .toList());
        when(inputs.content(any(), anyString()))
                .thenAnswer(invocation -> frozen.get(invocation.<String>getArgument(1)));
        doAnswer(invocation -> {
                    CheckoutKey key = invocation.getArgument(1);
                    Path target = invocation.getArgument(2);
                    Predicate<Path> verified = invocation.getArgument(3);
                    git.copyCheckout(KEY, key.commit(), key.tree(), key.paths(), target);
                    if (!verified.test(target))
                        throw new CheckoutUnverifiedException(
                                CheckoutUnverifiedException.Reason.MISMATCH, "Checkout content differs");
                    return null;
                })
                .when(inputs)
                .copyCheckout(anyLong(), any(), any(), any());
    }

    private JobEvidenceFiles files(String replica) {
        var copies = mock(
                EvidenceFolderPersonDataCatalog.class,
                invocation -> switch (invocation.getMethod().getName()) {
                    case "vouchesForLiveAttempt" -> true;
                    case "finishCapture" -> (AutoCloseable) () -> {};
                    case "withLiveAttempt" -> {
                        Supplier<?> restore = invocation.getArgument(1);
                        yield restore.get();
                    }
                    default -> Answers.RETURNS_DEFAULTS.answer(invocation);
                });
        return new JobEvidenceFiles(
                new FabricLayout(root.resolve(replica).toString()), jobs, Clock.systemUTC(), copies, inputs);
    }

    private PreparedJobInputs capture() {
        captures.incrementAndGet();
        var snapshot = git.readTreeSnapshot(KEY, head);
        var evidence = new JobFolderIndexBuilder(
                        new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC()), mapper, Clock.systemUTC())
                .build(
                        new ItemKind("scm.pull_request"),
                        Map.of(new SourceKind("scm.repository.tree"), CaptureContribution.empty()));
        var manifest = Objects.requireNonNull(evidence.manifest());
        first.beginPersonCapture(job);
        return first.prepare(
                job,
                new PreparedEvidence(
                        Map.of(SandboxLayout.MANIFEST_PATH, mapper.writeValueAsBytes(manifest)),
                        Map.of(
                                CHECKOUT + ".git/HEAD",
                                snapshot.stagingDir().resolve(".git/HEAD"),
                                CHECKOUT + ".git/" + GitRepositoryManager.CAPTURED_REFS,
                                snapshot.stagingDir().resolve(".git/" + GitRepositoryManager.CAPTURED_REFS)),
                        List.of(snapshot),
                        manifest,
                        List.of(new EvidenceDirectory(
                                CHECKOUT,
                                snapshot.stagingDir(),
                                new CheckoutKey(KEY.repositoryId(), head, snapshot.treeSha(), null, null)))),
                null);
    }

    private ReviewAttemptPreparation preparation(JobEvidenceFiles files) {
        return new ReviewAttemptPreparation(
                jobs,
                handler,
                adapter,
                issuer,
                SandboxTestLimits.PROPERTIES,
                transactions,
                mapper,
                mock(ReviewJobTelemetry.class, Answers.RETURNS_DEEP_STUBS),
                files);
    }

    private ReviewAttemptPreparation.PreparedSandbox prepare(JobEvidenceFiles files) {
        return preparation(files)
                .prepare(job.getId(), job, fence, snapshot(), new AtomicReference<>(PreparationFailure.Stage.INPUTS));
    }

    @Test
    void shouldRestoreARealCheckoutAndKeepTheOriginalDeadlineAndProgressBaseline() throws Exception {
        var initial = prepare(first);
        var original = initial.stagedInputs();
        try (original) {
            JsonNode published = Objects.requireNonNull(job.getCapture()).deepCopy();
            Instant started = Objects.requireNonNull(job.getExecutionStartedAt());
            ended.set(3);
            var next = prepare(files("second"));
            try (var restored = next.stagedInputs()) {
                assertThat(Files.readString(
                                restored.directories().getFirst().source().resolve("README.md")))
                        .isEqualTo("Original repository\n");
                SandboxSpec spec = Objects.requireNonNull(next.spec());
                assertThat(ProvenanceDigest.inputsDigestHex(
                                spec.inputFiles(), restored.filesOnDisk(), restored.directories(), job.getId()))
                        .as("physical Git metadata differs while the portable snapshot is unchanged")
                        .isNotEqualTo(published.path("inputsDigest").asString());
                assertThat(job.getCapture()).isEqualTo(published);
                assertThat(job.getExecutionStartedAt()).isEqualTo(started);
                assertThat(spec.workDeadline())
                        .isEqualTo(
                                started.plusSeconds(snapshot().timeoutSeconds()).truncatedTo(ChronoUnit.SECONDS));
                assertThat(tokenDeadlines).containsExactly(spec.workDeadline(), spec.workDeadline());
                assertThat(ReviewAttemptPreparation.endedBefore(job)).isEqualTo(2);
                assertThat(captures).hasValue(1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"files", "environment", "command", "image"})
    void shouldReuseThePublishedExecutionPlanWhenTheLiveProgramChanges(String changed) {
        var initial = prepare(first);
        var original = initial.stagedInputs();
        try (original) {
            var old = program.get();
            program.set(new PracticeSandboxSpec(
                    changed.equals("image") ? "agent:new" : old.image(),
                    changed.equals("command") ? List.of("node", "--new-runtime-flag", "runner.js") : old.command(),
                    changed.equals("environment") ? Map.of("PRACTICE_MODEL_CALLS", "1") : old.environment(),
                    changed.equals("files") ? program("new").inputFiles() : old.inputFiles(),
                    "irrelevant-live-credential",
                    old.promptDigest()));
            var next = prepare(files("second"));
            try (var restored = next.stagedInputs()) {
                var spec = Objects.requireNonNull(next.spec());
                assertThat(spec.image()).isEqualTo(old.image());
                assertThat(spec.command()).isEqualTo(old.command());
                assertThat(spec.environment()).isEqualTo(old.environment());
                spec.inputFiles()
                        .forEach((path, bytes) ->
                                assertThat(bytes).isEqualTo(old.inputFiles().get(path)));
                assertThat(spec.gatewayToken()).isEqualTo("attempt-token");
                assertThat(restored.directories()).hasSize(1);
                assertThat(captures).hasValue(1);
            }
        }
    }

    @Test
    void shouldKeepPublishedProvenanceWhenAnEvidenceRefusalStampsItAgain() {
        var initial = prepare(first);
        var original = initial.stagedInputs();
        try (original) {
            var published = Objects.requireNonNull(job.getCapture()).deepCopy();
            ended.set(3);
            var next = prepare(files("second"));
            try (var restored = next.stagedInputs()) {
                preparation(files("second"))
                        .persistPreparedEvidence(job.getId(), fence, job.getWorkspaceId(), job.getAttempt(), restored);
                assertThat(job.getCapture()).isEqualTo(published);
                assertThat(ReviewAttemptPreparation.endedBefore(job)).isEqualTo(2);
            }
        }
    }

    private static PracticeSandboxSpec program(String text) {
        var files = Map.of("runner.js", text.getBytes(StandardCharsets.UTF_8));
        return new PracticeSandboxSpec(
                "agent:test",
                List.of("node", "runner.js"),
                Map.of(),
                files,
                "attempt-token",
                ProvenanceDigest.rootDigestHex(files));
    }

    private static ConfigSnapshot snapshot() {
        return new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                new FrozenModel(
                        LlmApiProtocol.OPENAI_COMPLETIONS,
                        "https://model.invalid/v1",
                        "model",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false),
                null,
                null,
                600);
    }
}
