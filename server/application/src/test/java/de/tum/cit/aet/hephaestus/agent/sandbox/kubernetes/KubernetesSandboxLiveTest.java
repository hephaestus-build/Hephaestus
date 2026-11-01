package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.tum.cit.aet.hephaestus.agent.gateway.LiveSandboxGateway;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnContextFolder;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnDocuments;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnSandboxFixtures;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnSandboxes;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.AgentImageReferenceGuard;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxAttemptRunner;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxHosts;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ExecSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxInfrastructureException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobSpec;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Kubernetes sandbox runtime on a live cluster, run as the only JVM in the worker Pod that the chart's Deployment
 * started: practice reviews as Jobs whose Pods the cluster's Job controller creates, and the Heph host and turn
 * sandboxes as Pods, through the production adapters and the production transfer controllers on the worker's own
 * gateway port. Three cases use the native Job controllers without an executing adapter: the deadline that stops and
 * removes a Job's one Pod, the deletion of a completed Job that takes its Pod with it, and the TTL controller's removal
 * of a finished Job with its Pod. The client is the worker's own, from the Pod's
 * service account; the Pod metadata, namespaces and cluster prerequisites are the worker's own environment, bound by the
 * shipped {@code application.yml}. Every case uses synthetic files and commands; no model or provider is reached.
 *
 * <p>Ordinary live runs opt out unless {@value #OPT_IN} is {@code true}. An explicitly selected Kubernetes
 * qualification requires that opt-in and zero skipped tests; enabled runs fail when the worker configuration is not
 * qualified. It removes only Jobs and Pods that this process created, a Job by its UID and a Pod by its name, and logs
 * the time of each step without judging it.
 *
 * <p>Before any case, the production isolation probe runs as the worker runs it at its start: its pass is this run's
 * evidence that the cluster enforces the Restricted Pod Security Standard on the sandbox namespace, the chart's
 * gateway-only egress and the per-Pod process limit. What the class does not prove: that the RuntimeClass, when one is
 * named, isolates more than the default runtime does, and what a restart or the loss of the worker leaves. Those are
 * the operator's phases of the qualification.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "HEPHAESTUS_KUBERNETES_LIVE", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class KubernetesSandboxLiveTest {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxLiveTest.class);

    /** Selects the Kubernetes sandbox qualification. */
    private static final String OPT_IN = "HEPHAESTUS_KUBERNETES_LIVE";

    private static final String IMAGE_VARIABLE = "HEPHAESTUS_AGENT_IMAGE_REFERENCE";

    private static final Pattern PINNED_IMAGE = Pattern.compile("[^\\s@]+@sha256:[0-9a-f]{64}");

    /**
     * The worker's environment as the chart writes it, Downward API values included. The RuntimeClass and the seccomp
     * profile are optional: the bound values decide what the Pods name.
     */
    private static final List<String> WORKER_VARIABLES = List.of(
            "HEPHAESTUS_SANDBOX_KUBERNETES_NAMESPACE",
            "HEPHAESTUS_SANDBOX_KUBERNETES_POD_NAME",
            "HEPHAESTUS_SANDBOX_KUBERNETES_POD_IP",
            "SANDBOX_API_PORT",
            IMAGE_VARIABLE);

    /**
     * Client settings that would replace the service account's server, credential or trust. Each is refused as a
     * system property and as the environment variable the client reads for it.
     */
    private static final List<String> REFUSED_CLIENT_SETTINGS = List.of(
            Config.KUBERNETES_KUBECONFIG_FILE,
            Config.KUBERNETES_AUTH_TRYKUBECONFIG_SYSTEM_PROPERTY,
            Config.KUBERNETES_MASTER_SYSTEM_PROPERTY,
            Config.KUBERNETES_OAUTH_TOKEN_SYSTEM_PROPERTY,
            Config.KUBERNETES_AUTH_SERVICEACCOUNT_TOKEN_FILE_SYSTEM_PROPERTY,
            Config.KUBERNETES_AUTH_TRYSERVICEACCOUNT_SYSTEM_PROPERTY,
            Config.KUBERNETES_AUTH_BASIC_USERNAME_SYSTEM_PROPERTY,
            Config.KUBERNETES_AUTH_BASIC_PASSWORD_SYSTEM_PROPERTY,
            Config.KUBERNETES_CLIENT_CERTIFICATE_FILE_SYSTEM_PROPERTY,
            Config.KUBERNETES_CLIENT_CERTIFICATE_DATA_SYSTEM_PROPERTY,
            Config.KUBERNETES_CLIENT_KEY_FILE_SYSTEM_PROPERTY,
            Config.KUBERNETES_CLIENT_KEY_DATA_SYSTEM_PROPERTY,
            Config.KUBERNETES_CA_CERTIFICATE_FILE_SYSTEM_PROPERTY,
            Config.KUBERNETES_CA_CERTIFICATE_DATA_SYSTEM_PROPERTY,
            Config.KUBERNETES_TRUST_CERT_SYSTEM_PROPERTY,
            Config.KUBERNETES_DISABLE_HOSTNAME_VERIFICATION_SYSTEM_PROPERTY,
            Config.KUBERNETES_DISABLE_AUTO_CONFIG_SYSTEM_PROPERTY,
            Config.KUBERNETES_IMPERSONATE_USERNAME,
            Config.KUBERNETES_IMPERSONATE_GROUP,
            Config.KUBERNETES_HTTP_PROXY,
            Config.KUBERNETES_HTTPS_PROXY,
            Config.KUBERNETES_ALL_PROXY);

    private static final String CLIENT_CATEGORY = "io.fabric8.kubernetes.client.dsl.internal.core.v1.PodOperationsImpl";

    /** The gateway port that the chart's policy admits from a sandbox Pod to its worker Pod. */
    private static final int GATEWAY_PORT = 8081;

    private static final Duration START = Duration.ofMinutes(3);
    private static final Duration GONE = Duration.ofMinutes(2);
    private static final Duration COMMAND = Duration.ofSeconds(30);
    private static final Duration STAGE = Duration.ofSeconds(30);
    private static final int OUTPUT_LIMIT = 65_536;

    /** The transcript manifest that the agent image's gateway runner adds to every result it uploads. */
    private static final String TRACE_MANIFEST = "traces/manifest.json";

    /** The runner's cap on a result's transcripts: {@code TRACE_BYTES} in {@code docker/agents/pi/gateway-run.ts}. */
    private static final long TRACE_BYTES = 7L * 1024 * 1024;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The one context document a turn's folder is staged with. */
    private static final String USER_DOCUMENT = "{\n  \"counts\" : 3\n}";

    /** A logger level this run set, and the level it had before. */
    private record Applied(String name, @Nullable LogLevel previous) {}

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private final Set<String> created = new LinkedHashSet<>();
    private final List<Applied> applied = new ArrayList<>();

    private LoggingSystem logging;
    private KubernetesSandboxProperties properties;
    private AgentImageProperties agentImage;
    private KubernetesClient client;
    private KubernetesWorker worker;
    private KubernetesPods pods;
    private KubernetesSandboxJobs reviewJobs;
    private SandboxWorkspaceManager workspaces;
    private LiveSandboxGateway gateway;
    private SimpleMeterRegistry meters;
    private SandboxExits exits;
    private KubernetesSandboxCache cache;
    private SandboxBackend backend;
    private KubernetesLiveWorkers liveWorkers;
    private SandboxManager reviews;
    private HostSandbox hosts;
    private KubernetesExecSandboxes turns;

    @BeforeAll
    void requireTheQualifiedWorker() throws Exception {
        assertEquals("true", System.getenv(OPT_IN), OPT_IN + "=true selects this class; it does not skip");
        for (String variable : WORKER_VARIABLES) {
            String value = System.getenv(variable);
            assertTrue(value != null && !value.isBlank(), variable + " must be set, as the chart sets it");
        }
        assertNull(
                System.getenv("HEPHAESTUS_WORKER_ID"),
                "the review ledger's worker id stays per process: a run sets none");
        refuseClientOverrides();
        // Check only metadata, using the same default paths as the unmodified production client.
        for (String filename : Config.getKubeconfigFilenames()) {
            assertTrue(
                    Files.notExists(Path.of(filename)),
                    "no kubeconfig is present: the production client must use the Pod's service account");
        }
        assertTrue(
                Files.isRegularFile(Path.of(Config.KUBERNETES_SERVICE_ACCOUNT_TOKEN_PATH)),
                "the Pod's service account token is mounted");
        assertTrue(
                Files.isRegularFile(Path.of(Config.KUBERNETES_SERVICE_ACCOUNT_CA_CRT_PATH)),
                "the Pod's service account CA is mounted");

        Binder binder = Binder.get(shippedEnvironment());
        properties = validated(binder.bindOrCreate("hephaestus.sandbox.kubernetes", KubernetesSandboxProperties.class));
        SandboxProperties sandbox = validated(binder.bindOrCreate("hephaestus.sandbox", SandboxProperties.class));
        SandboxGatewayProperties gatewayProperties =
                validated(binder.bindOrCreate("hephaestus.sandbox.gateway", SandboxGatewayProperties.class));
        agentImage = validated(binder.bindOrCreate("hephaestus.agent.image", AgentImageProperties.class));
        // The production check of the reference runs in its constructor.
        new AgentImageReferenceGuard(agentImage);
        assertTrue(
                PINNED_IMAGE.matcher(agentImage.reference()).matches(),
                IMAGE_VARIABLE + " names the agent image by its sha256 digest");
        assertEquals(System.getenv(IMAGE_VARIABLE), agentImage.reference(), "the bound image is the pinned one");
        assertEquals(GATEWAY_PORT, gatewayProperties.port(), "the gateway port the chart's policy admits");
        assertEquals(properties.podName(), System.getenv("HOSTNAME"), "this JVM runs in the configured worker Pod");

        logging = LoggingSystem.get(getClass().getClassLoader());
        Map<String, LogLevel> levels = binder.bind("logging.level", Bindable.mapOf(String.class, LogLevel.class))
                .orElse(Map.of());
        assertEquals(LogLevel.OFF, levels.get(CLIENT_CATEGORY), "the shipped level closes the exec URL logger");
        levels.forEach((category, level) -> {
            String name = "root".equalsIgnoreCase(category) ? LoggingSystem.ROOT_LOGGER_NAME : category;
            LoggerConfiguration current = logging.getLoggerConfiguration(name);
            applied.add(new Applied(name, current == null ? null : current.getConfiguredLevel()));
            logging.setLogLevel(name, level);
        });
        LoggerConfiguration clientLogger = Objects.requireNonNull(logging.getLoggerConfiguration(CLIENT_CATEGORY));
        assertEquals(LogLevel.OFF, clientLogger.getEffectiveLevel(), "the client's exec logger records nothing");

        var configuration = new KubernetesSandboxConfiguration();
        client = configuration.sandboxKubernetesClient();
        requireServiceAccountClient(client.getConfiguration());
        // The ledger id of this run's worker process, unique as the production one is.
        worker = new KubernetesWorker(properties, "live-" + UUID.randomUUID(), gatewayProperties.port());
        liveWorkers = KubernetesFixtures.live(worker.workerId());
        requireNoAuthorityInItsOwnNamespace();
        pods = configuration.kubernetesPods(client, worker, sandbox, agentImage);
        workspaces = new SandboxWorkspaceManager();
        gateway = new LiveSandboxGateway(gatewayProperties.port());
        assertEquals(GATEWAY_PORT, gateway.port(), "the gateway listens on the port it was given");
        meters = new SimpleMeterRegistry();
        reviewJobs = configuration.kubernetesSandboxJobs(client, worker);
        exits = configuration.sandboxExits(pods, meters);
        cache = configuration.kubernetesSandboxCache(client, worker);
        backend = configuration.kubernetesSandboxBackend(pods, reviewJobs, worker, liveWorkers, exits, cache);
        reviews = new SandboxAttemptRunner(backend, workspaces, gateway.sessions(), meters);
        hosts = new SandboxHosts(backend, workspaces, gateway.sessions(), agentImage.reference());
        turns = configuration.kubernetesExecSandboxes(pods, worker, agentImage, liveWorkers, exits, cache);

        String managementPort = System.getenv("MANAGEMENT_PORT");
        KubernetesIsolationProbe probe = configuration.kubernetesIsolationProbe(
                client,
                pods,
                reviewJobs,
                worker,
                agentImage,
                managementPort == null ? 9090 : Integer.parseInt(managementPort));
        long probing = System.nanoTime();
        probe.prove();
        log.info("TIMING isolation probe: {} ms", millis(probing));
    }

    @AfterAll
    void removeWhatThisRunCreated() throws Exception {
        Throwable failure = null;
        if (client != null && worker != null && reviewJobs != null) {
            failure = attempt(failure, this::removeCreatedJobs);
        }
        if (client != null && worker != null && pods != null) {
            failure = attempt(failure, this::removeCreated);
        }
        if (turns != null) {
            failure = attempt(failure, turns::close);
        }
        if (cache != null) {
            failure = attempt(failure, cache::close);
        }
        if (gateway != null) {
            failure = attempt(failure, gateway::close);
        }
        if (client != null) {
            failure = attempt(failure, client::close);
        }
        if (logging != null) {
            for (Applied level : applied) {
                failure = attempt(failure, () -> logging.setLogLevel(level.name(), level.previous()));
            }
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    // --- The isolation probe ---

    /** The probe ran before every case; it would have thrown, naming each failed control, had the cluster failed it. */
    @Test
    void shouldPassTheIsolationProbeBeforeAnySandboxStarts() {
        assertThat(worker.isolated()).isTrue();
        assertThat(worker.proof()).isNotNull();
    }

    // --- Practice reviews ---

    @Test
    void shouldStageATreeFarPastEveryRemovedLimit(@TempDir Path staging) throws Exception {

        Path large = staging.resolve("large.bin");
        byte[] chunk = new byte[1024 * 1024];
        Arrays.fill(chunk, (byte) 'x');
        try (OutputStream out = Files.newOutputStream(large)) {
            for (int i = 0; i < 64; i++) {
                out.write(chunk);
            }
        }

        Path many = Files.createDirectories(staging.resolve("many"));
        for (int i = 0; i < 25_000; i++) {
            Path file = many.resolve("file" + i + ".txt");
            Files.writeString(file, "content " + i);
        }

        String script = "set -e\n" + "repo=/workspace/"
                + SandboxLayout.REPO_MOUNT_RELATIVE
                + "\n"
                + "bytes=$(wc -c < ${repo}large.bin)\n"
                + "files=$(find ${repo}many -type f | wc -l)\n"
                + "sample=$(cat ${repo}many/file24999.txt)\n"
                + "echo \"bytes=$bytes files=$files sample=$sample\" > /workspace/"
                + SandboxLayout.OUTPUT_PREFIX
                + "seen.txt\n";

        SandboxSpec spec = new SandboxSpec(
                UUID.randomUUID(),
                0,
                agentImage.reference(),
                List.of("sh", "-c", script),
                Map.of(),
                ReviewJob.generateJobToken(),
                limits(Duration.ofMinutes(5)),
                null,
                Map.of(),
                Map.of(),
                List.of(new EvidenceDirectory(SandboxLayout.REPO_MOUNT_RELATIVE, staging)));

        SandboxResult result = reviews.execute(spec);

        assertThat(result.exitCode()).as("container logs: %s", result.logs()).isZero();
        String seen = new String(result.outputFiles().get("seen.txt"), UTF_8).trim();
        assertThat(seen).isEqualTo("bytes=" + (64 * 1024L * 1024L) + " files=" + 25_000 + " sample=content 24999");
    }

    @Test
    void shouldRunAReviewOnItsInjectedFileAndRemoveItsJobAndPod() throws Exception {
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 0);
        byte[] prompt = synthetic(4096);
        double succeeded = executions("success");

        long started = System.nanoTime();
        SandboxResult result = reviews.execute(review(
                jobId,
                "cp /workspace/.prompt /workspace/out/echo.bin && printf review-stdout && printf review-stderr >&2",
                Map.of(".prompt", prompt),
                Duration.ofMinutes(5)));
        log.info("TIMING review (create Job, initialize, run, upload, delete): {} ms", millis(started));

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.outputFiles()).containsOnlyKeys("echo.bin", TRACE_MANIFEST);
        assertThat(result.outputFiles().get("echo.bin"))
                .as("the file the gateway served")
                .isEqualTo(prompt);
        requireEmptyTraceManifest(result.outputFiles());
        // The kubelet keeps one log per container: stdout and stderr arrive in it together.
        assertThat(result.logs()).contains("review-stdout", "review-stderr");
        assertThat(executions("success")).isEqualTo(succeeded + 1);
        assertThat(activeReviews()).isZero();
        awaitJobGone(name, null);
        awaitNoPodsOf(jobId);
    }

    @Test
    void shouldReportTheNonZeroExitOfAReview() throws Exception {
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 0);

        SandboxResult result = reviews.execute(
                review(jobId, "printf exit-stdout; printf exit-stderr >&2; exit 7", Map.of(), Duration.ofMinutes(5)));

        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.outputFiles()).containsOnlyKeys(TRACE_MANIFEST);
        requireEmptyTraceManifest(result.outputFiles());
        assertThat(result.logs()).contains("exit-stdout", "exit-stderr");
        awaitJobGone(name, null);
        awaitNoPodsOf(jobId);
    }

    @Test
    void shouldEndAReviewAtItsWorkDeadlineWithExit124AndItsPartialOutput() throws Exception {
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 0);
        Duration maxRuntime = Duration.ofSeconds(90);
        double timedOut = executions("timeout");

        SandboxResult result = reviews.execute(
                review(jobId, "printf partial > /workspace/out/partial.txt; sleep 600", Map.of(), maxRuntime));
        log.info("TIMING review to its work deadline of {}: {}", maxRuntime, result.duration());

        assertThat(result.exitCode()).isEqualTo(SandboxLayout.EXIT_WORK_TIMEOUT);
        assertThat(result.timedOut()).isTrue();
        assertThat(result.outputFiles()).containsOnlyKeys("partial.txt", TRACE_MANIFEST);
        assertThat(result.outputFiles().get("partial.txt")).isEqualTo("partial".getBytes(UTF_8));
        requireEmptyTraceManifest(result.outputFiles());
        assertThat(result.duration()).isLessThan(maxRuntime.plus(SandboxLayout.RESULT_UPLOAD_GRACE));
        assertThat(executions("timeout")).isEqualTo(timedOut + 1);
        awaitJobGone(name, null);
        awaitNoPodsOf(jobId);
    }

    @Test
    void shouldCancelARunningReviewAndRemoveItsJobAndPodWhenCancelled() throws Exception {
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 0);
        ResourceLimits limits = limits(Duration.ofMinutes(10));
        double cancelled = executions("cancelled");
        ExecutorService executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("live-review").factory());
        try {
            long started = System.nanoTime();
            Future<SandboxResult> running =
                    executor.submit(() -> reviews.execute(review(jobId, "sleep 600", Map.of(), limits)));
            Job job = awaitJob(name, running);
            requireBoundedJob(job);
            // The attempt's upload deadline plus the backstop, counted when the Job was created.
            long deadline = limits.maxRuntime()
                    .plus(SandboxLayout.RESULT_UPLOAD_GRACE)
                    .plus(KubernetesSandboxBackend.DEADLINE_BACKSTOP)
                    .toSeconds();
            assertThat(job.getSpec().getActiveDeadlineSeconds()).isBetween(deadline - 60, deadline);
            String jobUid = job.getMetadata().getUid();
            Pod pod = awaitPodOf(
                    job,
                    candidate -> KubernetesPods.running(candidate, KubernetesPods.RUNTIME)
                            || KubernetesPods.terminal(candidate),
                    running);
            log.info("TIMING review Job created to its Pod's runtime running: {} ms", millis(started));
            requireControlledBy(pod, job);
            assertThat(KubernetesPods.running(pod, KubernetesPods.RUNTIME))
                    .as("the runtime runs")
                    .isTrue();
            requireCustody(pod, KubernetesWorker.KIND_REVIEW);
            assertThat(pod.getMetadata().getLabels())
                    .containsEntry(KubernetesWorker.JOB_ID, jobId.toString())
                    .containsEntry(KubernetesWorker.JOB_ATTEMPT, "0");
            requireHardened(pod);
            requireBounds(
                    container(pod.getSpec().getContainers(), KubernetesPods.RUNTIME), limits, KubernetesPods.REVIEW);
            requirePinnedImageRan(pod, KubernetesPods.RUNTIME);
            assertThat(pod.getStatus().getQosClass())
                    .as("its whole memory requested, a share of CPU")
                    .isEqualTo("Burstable");
            assertThat(activeReviews()).isOne();
            String uid = pod.getMetadata().getUid();

            long cancelling = System.nanoTime();
            reviews.cancel(jobId);
            assertThatThrownBy(() -> running.get(pods.stopGraceSeconds() + 60, TimeUnit.SECONDS))
                    .as("the execution ends as cancelled")
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SandboxCancelledException.class);
            log.info("TIMING review end after its cancellation: {} ms", millis(cancelling));
            awaitJobGone(name, jobUid);
            awaitGone(pod.getMetadata().getName(), uid);
            assertThat(executions("cancelled")).isEqualTo(cancelled + 1);
            assertThat(activeReviews()).isZero();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.MINUTES))
                    .as("the execution's thread ends")
                    .isTrue();
        }
    }

    /**
     * A kill by another process, which knows the review only by its Job's name, ends the Pod that runs it, and the Job
     * starts no other Pod: the launching process sees the runtime end.
     */
    @Test
    void shouldEndAReviewThatAnotherProcessKillsByItsNameAndStartNoOtherPod() throws Exception {
        UUID jobId = UUID.randomUUID();
        String name = KubernetesSandboxBackend.jobName(jobId, 0);
        ResourceLimits limits = limits(Duration.ofMinutes(10));
        ExecutorService executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("live-review").factory());
        try {
            Future<SandboxResult> running =
                    executor.submit(() -> reviews.execute(review(jobId, "sleep 600", Map.of(), limits)));
            Job job = awaitJob(name, running);
            Pod pod = awaitPodOf(job, candidate -> KubernetesPods.running(candidate, KubernetesPods.RUNTIME), running);

            new KubernetesSandboxBackend(
                            pods,
                            reviewJobs,
                            worker,
                            liveWorkers,
                            new SandboxExits(pods, new SimpleMeterRegistry()),
                            cache)
                    .kill(new SandboxBackend.Handle(name));

            SandboxResult result = running.get(pods.stopGraceSeconds() + 90, TimeUnit.SECONDS);
            assertThat(result.exitCode()).as("the kubelet ended the runtime").isNotZero();
            assertThat(client.pods()
                            .inNamespace(namespace())
                            .withLabel(KubernetesSandboxJobs.JOB_NAME, name)
                            .list()
                            .getItems())
                    .extracting(candidate -> candidate.getMetadata().getUid())
                    .as("no replacement Pod; the attempt's own removal can have deleted the one it had")
                    .allMatch(uid -> uid.equals(pod.getMetadata().getUid()));
            awaitJobGone(name, job.getMetadata().getUid());
            awaitNoPodsOf(jobId);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.MINUTES))
                    .as("the execution's thread ends")
                    .isTrue();
        }
    }

    // --- The native Job controllers, without an executing adapter ---

    @Test
    void shouldStopAndRemoveTheOnePodOfAJobAtItsDeadlineWhenNoWorkerStopsIt() {
        int deadlineSeconds = 20;
        Job created = disposableJob("sleep 600", deadlineSeconds);
        String name = created.getMetadata().getName();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        // A watch of the Job's Pods by its name from before its creation through its end: every Pod the controller
        // made.
        SharedIndexInformer<Pod> children = client.pods()
                .inNamespace(namespace())
                .withLabel(KubernetesSandboxJobs.JOB_NAME, name)
                .inform(new ResourceEventHandler<>() {
                    @Override
                    public void onAdd(Pod pod) {
                        seen.add(pod.getMetadata().getUid());
                    }

                    @Override
                    public void onUpdate(Pod before, Pod pod) {
                        seen.add(pod.getMetadata().getUid());
                    }

                    @Override
                    public void onDelete(Pod pod, boolean finalStateUnknown) {
                        seen.add(pod.getMetadata().getUid());
                    }
                });
        try {
            reviewJobs.create(created);
            Job job = presentJob(name);
            requireBoundedJob(job);
            // Recorded before the deadline: the Job controller deletes its active Pods when the deadline passes.
            Pod pod = awaitPodOf(job, candidate -> true, null);
            requireControlledBy(pod, job);

            Job ended = client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(namespace())
                    .withName(name)
                    .waitUntilCondition(
                            candidate -> candidate != null && failed(candidate) != null,
                            START.toMillis(),
                            TimeUnit.MILLISECONDS);
            assertThat(failed(ended))
                    .as("the Job controller stopped the Job at its deadline")
                    .isEqualTo("DeadlineExceeded");
            // Nothing of this run deletes the Pod: the Job controller does, while the Job itself stays.
            awaitGone(pod.getMetadata().getName(), pod.getMetadata().getUid());
            assertThat(seen)
                    .as("the Job created one Pod and replaced none, up to its end")
                    .containsExactly(pod.getMetadata().getUid());
            assertThat(presentJob(name).getMetadata().getUid())
                    .isEqualTo(job.getMetadata().getUid());
        } finally {
            children.stop();
        }
    }

    @Test
    void shouldDeleteThePodOfACompletedJobWithTheJobWhenTheJobIsDeleted() {
        Job created = disposableJob("exit 0", 300);
        String name = created.getMetadata().getName();
        reviewJobs.create(created);
        Job job = presentJob(name);
        Pod pod = awaitPodOf(job, KubernetesPods::terminal, null);
        requireControlledBy(pod, job);
        client.batch()
                .v1()
                .jobs()
                .inNamespace(namespace())
                .withName(name)
                .waitUntilCondition(
                        candidate -> candidate != null && complete(candidate), START.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(pods.get(pod.getMetadata().getName()))
                .as("the production TTL keeps the completed Job, and with it its Pod")
                .extracting(kept -> kept.getMetadata().getUid())
                .isEqualTo(pod.getMetadata().getUid());

        reviewJobs.delete(name);
        awaitJobGone(name, job.getMetadata().getUid());
        // Only the garbage collector deletes the Pod here: nothing of this run deletes it by name.
        awaitGone(pod.getMetadata().getName(), pod.getMetadata().getUid());
        // A Job that is gone is no error.
        reviewJobs.delete(name);
    }

    @Test
    void shouldRemoveAFinishedJobWithItsPodThroughTheTtlControllerWhenNoWorkerDeletesIt() {
        Job production = disposableJob("exit 0", 300);
        assertThat(production.getSpec().getTtlSecondsAfterFinished())
                .as("the production TTL")
                .isEqualTo((int) KubernetesSandboxJobs.TTL_AFTER_FINISHED.toSeconds());
        // A copy with a short TTL, made before the creation, so that the test does not wait for the production one.
        Job shortLived = new JobBuilder(production)
                .editSpec()
                .withTtlSecondsAfterFinished(5)
                .endSpec()
                .build();
        String name = shortLived.getMetadata().getName();
        reviewJobs.create(shortLived);
        Job job = presentJob(name);
        Pod pod = awaitPodOf(job, candidate -> true, null);
        requireControlledBy(pod, job);

        long started = System.nanoTime();
        // The TTL controller removes only a finished Job, and the garbage collector its Pod; nothing of this run does.
        awaitJobGone(name, job.getMetadata().getUid());
        awaitGone(pod.getMetadata().getName(), pod.getMetadata().getUid());
        log.info("TIMING Job created to it and its Pod removed by the TTL controller: {} ms", millis(started));
    }

    // --- The Heph host ---

    @Test
    void shouldStartAHostOnTheFilesTheGatewayServedAndRemoveIt() throws Exception {
        byte[] payload = synthetic(8192);
        String expected = sha256(payload) + "  /workspace/payload.bin\n";
        ResourceLimits limits = limits(Duration.ofMinutes(3));

        long started = System.nanoTime();
        HostSandbox.Host host = hosts.launch(
                UUID.randomUUID(),
                new HostSandbox.HostSpec(
                        Map.of("payload.bin", payload),
                        Map.of(),
                        List.of("sh", "-c", "sha256sum /workspace/payload.bin && exec sleep 3600"),
                        limits));
        log.info("TIMING host launch to its process running: {} ms", millis(started));
        String name = "hephaestus-host-" + host.id();
        track(name);
        String uid;
        try {
            Pod pod = present(name);
            uid = pod.getMetadata().getUid();
            requireCustody(pod, KubernetesWorker.KIND_HOST);
            assertThat(pod.getMetadata().getLabels())
                    .containsEntry(KubernetesWorker.SESSION_ID, host.id().toString());
            requireHardened(pod);
            requireBounds(
                    container(pod.getSpec().getContainers(), KubernetesPods.RUNTIME), limits, KubernetesPods.HOST);
            assertThat(hosts.isRunning(host)).isTrue();
            // The host API returns no output: the Pod's log is where its process reports.
            assertThat(awaitLog(name, expected, Duration.ofMinutes(1)))
                    .as("the host's process read the exact bytes of its file")
                    .contains(expected);
        } finally {
            hosts.remove(host);
        }
        assertThat(hosts.isRunning(host)).isFalse();
        awaitGone(name, uid);
    }

    // --- Turn sandboxes ---

    @Test
    void shouldStageRunCancelAndRemoveATurnSandbox() throws Exception {
        String sandboxId = UUID.randomUUID().toString();
        String name = "hephaestus-turn-" + sandboxId;
        track(name);
        ResourceLimits limits = limits(ResourceLimits.MAX_RUNTIME);

        long started = System.nanoTime();
        String sandbox = turns.create(sandboxId, limits);
        log.info("TIMING turn sandbox create to both containers running: {} ms", millis(started));
        assertThat(sandbox).isEqualTo(name);
        Pod pod = present(sandbox);
        String uid = pod.getMetadata().getUid();
        requireCustody(pod, KubernetesWorker.KIND_TURN);
        assertThat(pod.getMetadata().getLabels()).containsEntry(KubernetesWorker.SANDBOX_ID, sandboxId);
        requireHardened(pod);
        requireBounds(container(pod.getSpec().getContainers(), KubernetesPods.SANDBOX), limits, KubernetesPods.TURN);
        requirePinnedImageRan(pod, KubernetesPods.SANDBOX);
        assertThat(pod.getStatus().getQosClass())
                .as("its whole memory requested, a share of CPU")
                .isEqualTo("Burstable");
        assertThat(turns.list()).anySatisfy(listed -> {
            assertThat(listed.id()).isEqualTo(sandbox);
            assertThat(listed.workerInstance()).isEqualTo(properties.podName());
            assertThat(listed.workerGone()).isFalse();
        });

        byte[] alpha = "alpha\n".getBytes(UTF_8);
        byte[] top = synthetic(2048);
        // The archive writes a directory outside work/ read-only (0555), which a replacement could not empty.
        stage(sandbox, Map.of("work/a.txt", alpha, "top.txt", top), null);
        ExecSandboxes.Execution sums = run(sandbox, "sha256sum work/a.txt top.txt");
        assertThat(sums.exitCode()).isZero();
        assertThat(text(sums.stdout()))
                .as("the staged files have their exact bytes")
                .isEqualTo(sha256(alpha) + "  work/a.txt\n" + sha256(top) + "  top.txt\n");
        assertThat(sums.stderr()).isEmpty();

        byte[] beta = synthetic(512);
        stage(sandbox, Map.of("b.txt", beta), "work");
        assertThat(text(run(sandbox, "ls -A work").stdout()))
                .as("the replacement removed what the directory held")
                .isEqualTo("b.txt\n");
        assertThat(run(sandbox, "cat work/b.txt").stdout()).isEqualTo(beta);

        ExecSandboxes.Execution append = run(sandbox, "printf x >> top.txt");
        assertThat(append.exitCode())
                .as("the sandbox user cannot write what was staged")
                .isNotNull()
                .isNotZero();
        ExecSandboxes.Execution addition = run(sandbox, "touch work/new");
        assertThat(addition.exitCode()).isNotNull().isNotZero();
        assertThat(text(run(sandbox, "sha256sum top.txt; ls -A work").stdout()))
                .isEqualTo(sha256(top) + "  top.txt\nb.txt\n");

        ExecSandboxes.Execution streams = run(sandbox, "printf turn-stdout; printf turn-stderr >&2; exit 3");
        assertThat(streams.exitCode()).isEqualTo(3);
        assertThat(text(streams.stdout())).isEqualTo("turn-stdout");
        assertThat(text(streams.stderr())).isEqualTo("turn-stderr");
        assertThat(streams.truncated()).isFalse();

        ExecSandboxes.Execution many =
                turns.exec(sandbox, new ExecSandboxes.Command(List.of("seq", "1", "100000"), COMMAND, 1024));
        assertThat(many.exitCode()).isZero();
        assertThat(many.truncated()).isTrue();
        assertThat(many.stdout()).hasSize(1024);
        assertThat(text(many.stdout())).startsWith("1\n2\n3\n");

        long slowStarted = System.nanoTime();
        ExecSandboxes.Execution slow = turns.exec(
                sandbox,
                new ExecSandboxes.Command(
                        List.of("sh", "-c", "setsid sh -c 'echo $$ > /tmp/detached.pid; exec sleep 300' & sleep 300"),
                        Duration.ofSeconds(3),
                        1024));
        log.info("TIMING turn command with a 3 s timeout: {} ms", millis(slowStarted));
        assertThat(slow.timedOut()).as("the command still ran at its timeout").isTrue();
        long cancelStarted = System.nanoTime();
        turns.cancelCommands(sandbox);
        log.info("TIMING turn cancellation: {} ms", millis(cancelStarted));
        assertThat(text(run(
                                sandbox,
                                "[ -s /tmp/detached.pid ] || { echo missing; exit 0; }; p=$(cat /tmp/detached.pid);"
                                        + " for i in 1 2 3 4 5 6 7 8 9 10; do"
                                        + " kill -0 \"$p\" 2>/dev/null || { echo gone; exit 0; }; sleep 0.2; done;"
                                        + " echo alive")
                        .stdout()))
                .as("the cancellation ends a child that left the command's process group")
                .isEqualTo("gone\n");
        assertThat(text(run(sandbox, "kill -0 1 && echo init").stdout()))
                .as("the container's first process survives the cancellation")
                .isEqualTo("init\n");
        Pod after = present(sandbox);
        assertThat(after.getMetadata().getUid()).isEqualTo(uid);
        assertThat(KubernetesPods.running(after, KubernetesPods.SANDBOX)).isTrue();
        assertThat(KubernetesPods.running(after, KubernetesPods.STAGER)).isTrue();
        assertThat(after.getStatus().getContainerStatuses())
                .allSatisfy(status -> assertThat(status.getRestartCount()).isZero());
        assertThat(text(run(sandbox, "echo next").stdout())).isEqualTo("next\n");

        List<Long> execs = new ArrayList<>();
        for (int call = 0; call < 10; call++) {
            long callStarted = System.nanoTime();
            assertThat(run(sandbox, "true").exitCode()).isZero();
            execs.add(millis(callStarted));
        }
        log.info("TIMING turn exec p50={} ms p95={} ms", percentile(execs, 50), percentile(execs, 95));

        turns.remove(sandbox);
        awaitGone(sandbox, uid);
        assertThat(turns.list()).noneMatch(listed -> listed.id().equals(sandbox));
    }

    @Test
    void shouldFailAStagingThatDoesNotUnpackAsAConfirmedExitAndRemoveTheSandbox() {
        String sandboxId = UUID.randomUUID().toString();
        track("hephaestus-turn-" + sandboxId);
        String sandbox = turns.create(sandboxId, limits(ResourceLimits.MAX_RUNTIME));
        String uid = present(sandbox).getMetadata().getUid();
        byte[] garbage = "not a tar archive\n".repeat(64).getBytes(UTF_8);

        // An exit status of the stager's script is a confirmed outcome: a failure of the sandbox, not of the runtime.
        assertThatThrownBy(() -> turns.stage(
                        sandbox,
                        new ExecSandboxes.Replace("docs", new ByteArrayInputStream(garbage), garbage.length, STAGE)))
                .isInstanceOf(SandboxException.class)
                .isNotInstanceOf(SandboxInfrastructureException.class)
                .hasMessageStartingWith("Staging into the turn sandbox failed");

        turns.remove(sandbox);
        awaitGone(sandbox, uid);
    }

    @Test
    void shouldGiveTheNextCallANewSandboxWhenTheContextFolderLosesItsSandbox() throws Exception {
        TurnSandboxes sandboxes = new TurnSandboxes(turns, 0, new SimpleMeterRegistry());
        try {
            var folder = new TurnContextFolder(
                    sandboxes,
                    new DefaultListableBeanFactory().getBeanProvider(ContextLoader.class),
                    Clock.systemUTC());
            TurnAttempt turn = new TurnAttempt(UUID.randomUUID(), 1L, 2L, UUID.randomUUID(), 1, UUID.randomUUID());
            Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
            var attempt = new TurnSandboxes.Attempt(turn.turnId(), turn.attempt());

            ObjectNode ready = folder.exec(turn, deadline, documents(), "echo ready", null);
            assertThat(ready.path("exitCode").asInt()).as(ready.toString()).isZero();
            assertThat(ready.path("output").asString()).isEqualTo("ready\n");
            String first =
                    sandboxes.acquire(attempt, turn.workspaceId(), () -> false).containerId();
            track(first);
            Pod lost = present(first);
            assertThat(worker.createdBySelf(lost.getMetadata().getLabels()))
                    .as("this run created the sandbox it removes")
                    .isTrue();
            String lostUid = lost.getMetadata().getUid();
            client.pods()
                    .inNamespace(namespace())
                    .withName(first)
                    .withGracePeriod(0)
                    .delete();
            awaitGone(first, lostUid);

            ObjectNode failed = folder.exec(turn, deadline, documents(), "echo lost", null);
            assertThat(failed.path("ok").asBoolean()).as(failed.toString()).isFalse();
            assertThat(failed.path("error").asString()).isEqualTo("UNAVAILABLE");

            long replaced = System.nanoTime();
            ObjectNode again = folder.exec(turn, deadline, documents(), "echo again", null);
            log.info("TIMING context folder call on a new sandbox (create + stage + exec): {} ms", millis(replaced));
            assertThat(again.path("exitCode").asInt()).as(again.toString()).isZero();
            assertThat(again.path("output").asString()).isEqualTo("again\n");
            String second =
                    sandboxes.acquire(attempt, turn.workspaceId(), () -> false).containerId();
            track(second);
            assertThat(second).as("the next call has a sandbox of its own").isNotEqualTo(first);
            Pod replacement = present(second);
            requireCustody(replacement, KubernetesWorker.KIND_TURN);
            assertThat(text(turns.exec(
                                    second,
                                    new ExecSandboxes.Command(
                                            List.of("cat", "inputs/context/user.json"), COMMAND, OUTPUT_LIMIT))
                            .stdout()))
                    .as("the new sandbox has the documents staged again")
                    .isEqualTo(USER_DOCUMENT);

            sandboxes.release(turn.turnId(), turn.attempt());
            awaitGone(second, replacement.getMetadata().getUid());
        } finally {
            sandboxes.close();
        }
    }

    // --- The runtime's own threads ---

    /** A closed runtime takes no further call: its next command is refused, not opened. */
    @Test
    void shouldRefuseTheCommandsOfATurnRuntimeThatIsClosed() {
        String sandboxId = UUID.randomUUID().toString();
        track("hephaestus-turn-" + sandboxId);
        String sandbox = turns.create(sandboxId, limits(ResourceLimits.MAX_RUNTIME));
        String uid = present(sandbox).getMetadata().getUid();
        KubernetesExecSandboxes runtime = new KubernetesSandboxConfiguration()
                .kubernetesExecSandboxes(
                        pods,
                        worker,
                        agentImage,
                        KubernetesFixtures.live(worker.workerId()),
                        new SandboxExits(pods, new SimpleMeterRegistry()),
                        cache);
        try {
            assertThat(text(runtime.exec(
                                    sandbox, new ExecSandboxes.Command(List.of("echo", "owned"), COMMAND, OUTPUT_LIMIT))
                            .stdout()))
                    .isEqualTo("owned\n");
        } finally {
            runtime.close();
        }
        assertThatThrownBy(() -> runtime.exec(
                        sandbox, new ExecSandboxes.Command(List.of("echo", "late"), COMMAND, OUTPUT_LIMIT)))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("no longer runs");
        assertThat(text(run(sandbox, "echo shared").stdout()))
                .as("the other runtime still runs commands")
                .isEqualTo("shared\n");

        turns.remove(sandbox);
        awaitGone(sandbox, uid);
    }

    // --- Preconditions ---

    private static void refuseClientOverrides() {
        for (String setting : REFUSED_CLIENT_SETTINGS) {
            assertNull(System.getProperty(setting), "the client setting " + setting + " is not overridden");
            assertNull(
                    System.getenv(variableOf(setting)),
                    "the client setting " + variableOf(setting) + " is not overridden");
        }
        assertNotNull(System.getenv(Config.KUBERNETES_SERVICE_HOST_PROPERTY), "the Pod has the API's address");
        assertNotNull(System.getenv(Config.KUBERNETES_SERVICE_PORT_PROPERTY), "the Pod has the API's port");
    }

    /** The environment variable the client reads for a setting: upper case, with underscores. */
    private static String variableOf(String setting) {
        return setting.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /** The client the configuration built: the in-cluster API, the service account's CA, and no kubeconfig file. */
    private static void requireServiceAccountClient(Config config) {
        URI master = URI.create(config.getMasterUrl());
        String host = Objects.requireNonNull(System.getenv(Config.KUBERNETES_SERVICE_HOST_PROPERTY));
        String port = Objects.requireNonNull(System.getenv(Config.KUBERNETES_SERVICE_PORT_PROPERTY));
        assertEquals("https", master.getScheme());
        assertEquals(
                host, Objects.requireNonNull(master.getHost()).replace("[", "").replace("]", ""));
        assertEquals(Integer.parseInt(port), master.getPort());
        assertNull(config.getCurrentContext(), "no kubeconfig was merged");
        assertEquals(Config.KUBERNETES_SERVICE_ACCOUNT_CA_CRT_PATH, config.getCaCertFile());
        assertFalse(config.isTrustCerts(), "the API's certificate is verified");
        assertFalse(config.isDisableHostnameVerification(), "the API's name is verified");
    }

    /** The worker has no authority in its own namespace: not over its own Pod, and not over its Secrets. */
    private void requireNoAuthorityInItsOwnNamespace() {
        // The client's own reading of the service account's namespace file.
        String own = Objects.requireNonNull(client.getConfiguration().getNamespace(), "the Pod's namespace is mounted");
        assertThat(properties.namespace())
                .as("the sandbox namespace is not the worker's")
                .isNotEqualTo(own);
        assertThatThrownBy(() -> client.pods()
                        .inNamespace(own)
                        .withName(properties.podName())
                        .get())
                .isInstanceOfSatisfying(KubernetesClientException.class, e -> assertEquals(403, e.getCode()));
        assertThatThrownBy(() -> client.secrets().inNamespace(own).list())
                .isInstanceOfSatisfying(KubernetesClientException.class, e -> assertEquals(403, e.getCode()));
        assertThatThrownBy(() ->
                        client.secrets().inNamespace(properties.namespace()).list())
                .as("the sandbox namespace holds no Secret the worker may read")
                .isInstanceOfSatisfying(KubernetesClientException.class, e -> assertEquals(403, e.getCode()));
    }

    private static StandardEnvironment shippedEnvironment() throws IOException {
        PropertySource<?> shared = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .getFirst();
        StandardEnvironment environment = new StandardEnvironment();
        // Behind the system properties and the environment, as Boot orders them.
        environment.getPropertySources().addLast(shared);
        return environment;
    }

    /** The bound properties, checked against their constraints as {@code @Validated} checks them at startup. */
    private static <T> T validated(T bound) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<T>> violations = factory.getValidator().validate(bound);
            assertTrue(
                    violations.isEmpty(),
                    () -> bound.getClass().getSimpleName() + " is invalid: "
                            + violations.stream()
                                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                                    .sorted()
                                    .toList());
            return bound;
        }
    }

    // --- Pods ---

    private String namespace() {
        return worker.namespace();
    }

    private void track(String name) {
        created.add(name);
    }

    private Pod present(String name) {
        return Objects.requireNonNull(pods.get(name), () -> "Pod " + name + " exists");
    }

    /** Waits for the review Job of the name to exist; a failed execution is reported with the wait. */
    private Job awaitJob(String name, Future<?> execution) {
        try {
            return client.batch()
                    .v1()
                    .jobs()
                    .inNamespace(namespace())
                    .withName(name)
                    .waitUntilCondition(job -> job != null, START.toMillis(), TimeUnit.MILLISECONDS);
        } catch (KubernetesClientTimeoutException e) {
            throw withFailureOf(e, execution);
        }
    }

    /**
     * Waits for a Pod that the Job controller created for the Job, found by the Job's name as the controller labels it,
     * to satisfy the condition. A failed execution is reported with the wait.
     */
    private Pod awaitPodOf(Job job, Predicate<Pod> condition, @Nullable Future<?> execution) {
        CompletableFuture<List<Pod>> found = client.pods()
                .inNamespace(namespace())
                .withLabel(KubernetesSandboxJobs.JOB_NAME, job.getMetadata().getName())
                .informOnCondition(candidates -> candidates.stream().anyMatch(condition));
        try {
            return found.get(START.toMillis(), TimeUnit.MILLISECONDS).stream()
                    .filter(condition)
                    .findFirst()
                    .orElseThrow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the Pod of a Job", e);
        } catch (ExecutionException | TimeoutException e) {
            throw withFailureOf(
                    new AssertionError("No Pod of Job " + job.getMetadata().getName() + " in time", e), execution);
        } finally {
            found.cancel(true);
        }
    }

    /** The failure of a wait, with the failure of the execution it waited for when that execution already ended. */
    private static <T extends Throwable> T withFailureOf(T failure, @Nullable Future<?> execution) {
        if (execution != null && execution.isDone()) {
            try {
                execution.get();
            } catch (ExecutionException failed) {
                failure.addSuppressed(failed.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure.addSuppressed(interrupted);
            }
        }
        return failure;
    }

    /** The Job runs one Pod, replaces none, is bounded by a deadline and the production TTL, and is this run's. */
    private void requireBoundedJob(Job job) {
        JobSpec spec = job.getSpec();
        assertThat(spec.getCompletions()).isOne();
        assertThat(spec.getParallelism()).isOne();
        assertThat(spec.getBackoffLimit()).isZero();
        assertThat(spec.getActiveDeadlineSeconds()).isPositive();
        assertThat(spec.getTtlSecondsAfterFinished())
                .isEqualTo((int) KubernetesSandboxJobs.TTL_AFTER_FINISHED.toSeconds());
        assertThat(job.getMetadata().getNamespace()).isEqualTo(namespace());
        assertThat(labelsOf(job))
                .containsEntry(KubernetesWorker.PART_OF, "hephaestus")
                .containsEntry(KubernetesWorker.WORKER_POD, properties.podName())
                .containsEntry(KubernetesWorker.SANDBOX_KIND, KubernetesWorker.KIND_REVIEW);
        assertThat(worker.createdBySelf(labelsOf(job))).isTrue();
        assertThat(reviewJobs.list())
                .extracting(listed -> listed.getMetadata().getName())
                .contains(job.getMetadata().getName());
    }

    /**
     * The Job controls the Pod, as the Job controller records it, and the Pod carries this run's review custody. The
     * runtime itself finds the Pod by the Job's name label only: the namespace is the installation's.
     */
    private void requireControlledBy(Pod pod, Job job) {
        OwnerReference owner = Optional.ofNullable(pod.getMetadata().getOwnerReferences()).orElse(List.of()).stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getController()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the Pod has a controller"));
        assertThat(owner.getKind()).isEqualTo("Job");
        assertThat(owner.getApiVersion()).isEqualTo("batch/v1");
        assertThat(owner.getUid()).isEqualTo(job.getMetadata().getUid());
        assertThat(owner.getName()).isEqualTo(job.getMetadata().getName());
        assertThat(labelsOf(pod))
                .containsEntry(KubernetesSandboxJobs.JOB_NAME, job.getMetadata().getName());
        requireCustody(pod, KubernetesWorker.KIND_REVIEW);
    }

    private Job presentJob(String name) {
        return Objects.requireNonNull(
                client.batch()
                        .v1()
                        .jobs()
                        .inNamespace(namespace())
                        .withName(name)
                        .get(),
                () -> "Job " + name + " exists");
    }

    /** The reason of the Job's {@code Failed} condition, or null while it has none. */
    private static @Nullable String failed(Job job) {
        return job.getStatus() == null || job.getStatus().getConditions() == null
                ? null
                : job.getStatus().getConditions().stream()
                        .filter(condition ->
                                "Failed".equals(condition.getType()) && "True".equals(condition.getStatus()))
                        .map(condition -> Objects.requireNonNullElse(condition.getReason(), ""))
                        .findFirst()
                        .orElse(null);
    }

    private static boolean complete(Job job) {
        return job.getStatus() != null
                && job.getStatus().getConditions() != null
                && job.getStatus().getConditions().stream()
                        .anyMatch(condition ->
                                "Complete".equals(condition.getType()) && "True".equals(condition.getStatus()));
    }

    /**
     * A review Job of this run that no adapter executes: the review Pod whose initializer fills nothing, since no gateway
     * session serves a workspace for it. Its labels, security, isolation gate, limits, deadline and TTL are the production
     * ones.
     */
    private Job disposableJob(String script, long deadlineSeconds) {
        UUID jobId = UUID.randomUUID();
        Map<String, String> labels = worker.labels(KubernetesWorker.KIND_REVIEW);
        labels.put(KubernetesWorker.JOB_ID, jobId.toString());
        labels.put(KubernetesWorker.JOB_ATTEMPT, "0");
        Pod reviewPod = pods.workspacePod(
                new KubernetesPods.Workload(
                        KubernetesSandboxBackend.jobName(jobId, 0), labels, agentImage.reference(), "agent"),
                Map.of(),
                List.of("sh", "-c", script),
                limits(Duration.ofMinutes(5)),
                KubernetesPods.REVIEW);
        // The initializer stays, so that it makes the runtime's mount points, but fills nothing.
        Pod runtimeOnly = new PodBuilder(reviewPod)
                .editSpec()
                .editMatchingInitContainer(container -> KubernetesPods.INITIALIZER.equals(container.getName()))
                .withCommand(KubernetesPods.limited(List.of("true")))
                .endInitContainer()
                .endSpec()
                .build();
        return reviewJobs.job(runtimeOnly, deadlineSeconds);
    }

    /** Waits until no Job of the name is left, or only one with another UID. */
    private void awaitJobGone(String name, @Nullable String uid) {
        long started = System.nanoTime();
        client.batch()
                .v1()
                .jobs()
                .inNamespace(namespace())
                .withName(name)
                .waitUntilCondition(
                        job -> job == null
                                || (uid != null && !uid.equals(job.getMetadata().getUid())),
                        GONE.toMillis(),
                        TimeUnit.MILLISECONDS);
        log.info("TIMING Job gone: {} ms", millis(started));
    }

    /** Waits until no Pod of the review job is left, whatever created it. */
    private void awaitNoPodsOf(UUID jobId) throws Exception {
        CompletableFuture<List<Pod>> gone = client.pods()
                .inNamespace(namespace())
                .withLabel(KubernetesWorker.JOB_ID, jobId.toString())
                .informOnCondition(List::isEmpty);
        try {
            gone.get(GONE.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            gone.cancel(true);
        }
    }

    /**
     * Waits until no Pod of the name is left, or only one with another UID.
     *
     * @throws KubernetesClientTimeoutException when it is still there
     */
    private void awaitGone(String name, @Nullable String uid) {
        long started = System.nanoTime();
        client.pods()
                .inNamespace(namespace())
                .withName(name)
                .waitUntilCondition(
                        pod -> pod == null
                                || (uid != null && !uid.equals(pod.getMetadata().getUid())),
                        GONE.toMillis(),
                        TimeUnit.MILLISECONDS);
        log.info("TIMING Pod gone: {} ms", millis(started));
    }

    /** The container's log once it holds {@code expected}, or as it is at the timeout. */
    private String awaitLog(String name, String expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String text = pods.logs(name, KubernetesPods.RUNTIME);
        while (!text.contains(expected) && System.nanoTime() < deadline) {
            Thread.sleep(200);
            text = pods.logs(name, KubernetesPods.RUNTIME);
        }
        return text;
    }

    /** The custody labels of a sandbox Pod: Hephaestus, the kind, this worker Pod and this process. */
    private void requireCustody(Pod pod, String kind) {
        Map<String, String> labels = labelsOf(pod);
        assertThat(pod.getMetadata().getNamespace()).isEqualTo(namespace());
        assertThat(labels)
                .containsEntry(KubernetesWorker.PART_OF, "hephaestus")
                .containsEntry(KubernetesWorker.WORKER_POD, properties.podName())
                .containsEntry(KubernetesWorker.SANDBOX_KIND, kind)
                .containsEntry(KubernetesWorker.WORKER_ID, KubernetesWorker.labelValue(worker.workerId()));
        assertThat(worker.createdBySelf(labels)).isTrue();
    }

    /**
     * The hardened Pod as the API holds it: the Restricted Pod Security fields, the configured RuntimeClass and seccomp
     * profile or the cluster's defaults, and every container restricted.
     */
    private void requireHardened(Pod pod) {
        PodSpec spec = pod.getSpec();
        assertThat(spec.getRuntimeClassName())
                .isEqualTo(properties.runtimeClassName().isEmpty() ? null : properties.runtimeClassName());
        assertThat(spec.getAutomountServiceAccountToken()).isFalse();
        assertThat(spec.getEnableServiceLinks()).isFalse();
        assertThat(spec.getHostNetwork()).isNotEqualTo(true);
        assertThat(spec.getHostPID()).isNotEqualTo(true);
        assertThat(spec.getHostIPC()).isNotEqualTo(true);
        assertThat(spec.getShareProcessNamespace()).isNotEqualTo(true);
        assertThat(spec.getRestartPolicy()).isEqualTo("Never");
        assertThat(spec.getDnsPolicy()).isEqualTo("None");
        PodSecurityContext security = spec.getSecurityContext();
        assertThat(security.getRunAsNonRoot()).isTrue();
        if (properties.seccompProfile().isEmpty()) {
            assertThat(security.getSeccompProfile().getType()).isEqualTo("RuntimeDefault");
        } else {
            assertThat(security.getSeccompProfile().getType()).isEqualTo("Localhost");
            assertThat(security.getSeccompProfile().getLocalhostProfile()).isEqualTo(properties.seccompProfile());
        }
        List<Container> containers = Stream.concat(
                        Optional.ofNullable(spec.getInitContainers()).orElse(List.of()).stream(),
                        spec.getContainers().stream())
                .toList();
        assertThat(containers).allSatisfy(container -> {
            assertThat(container.getImage()).isEqualTo(agentImage.reference());
            assertThat(container.getCommand()).startsWith(KubernetesPods.RLIMITS.toArray(String[]::new));
            SecurityContext restricted = container.getSecurityContext();
            assertThat(restricted.getPrivileged()).isFalse();
            assertThat(restricted.getAllowPrivilegeEscalation()).isFalse();
            assertThat(restricted.getReadOnlyRootFilesystem()).isTrue();
            assertThat(restricted.getRunAsNonRoot()).isTrue();
            assertThat(restricted.getCapabilities().getDrop()).containsExactly("ALL");
            assertThat(restricted.getCapabilities().getAdd()).isNullOrEmpty();
        });
    }

    /** The API holds the Pod kind's measured requests and limits and the caller's CPU limit. */
    private static void requireBounds(Container container, ResourceLimits limits, KubernetesPods.Size size) {
        Map<String, Quantity> bounds = container.getResources().getLimits();
        Map<String, Quantity> requests = container.getResources().getRequests();
        assertThat(Quantity.getAmountInBytes(bounds.get("memory")))
                .isEqualByComparingTo(BigDecimal.valueOf(size.memoryLimit()));
        assertThat(Quantity.getAmountInBytes(requests.get("memory")))
                .isEqualByComparingTo(BigDecimal.valueOf(size.memoryRequest()));
        assertThat(Quantity.getAmountInBytes(bounds.get("cpu")))
                .isEqualByComparingTo(BigDecimal.valueOf(limits.cpus()));
        assertThat(Quantity.getAmountInBytes(requests.get("cpu")))
                .isEqualByComparingTo(
                        BigDecimal.valueOf(size.cpuRequestMillicores()).movePointLeft(3));
    }

    /** The Pod requests the pinned image; the runtime may report its resolved platform image ID. */
    private void requirePinnedImageRan(Pod pod, String name) {
        assertEquals(
                agentImage.reference(),
                container(pod.getSpec().getContainers(), name).getImage());
        ContainerStatus status = pod.getStatus().getContainerStatuses().stream()
                .filter(candidate -> name.equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(status.getImageID()).isNotBlank();
        var state = Objects.requireNonNull(status.getState());
        assertTrue(state.getRunning() != null || state.getTerminated() != null, "the pinned container started");
    }

    private static Container container(List<Container> containers, String name) {
        return containers.stream()
                .filter(container -> name.equals(container.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, String> labelsOf(Pod pod) {
        Map<String, String> labels = pod.getMetadata().getLabels();
        return labels == null ? Map.of() : labels;
    }

    private static Map<String, String> labelsOf(Job job) {
        Map<String, String> labels = job.getMetadata().getLabels();
        return labels == null ? Map.of() : labels;
    }

    /** Removes the review Jobs this run left, each with its Pod. A Job that this process did not create is kept. */
    private void removeCreatedJobs() {
        for (Job job : reviewJobs.list()) {
            if (!worker.createdBySelf(labelsOf(job))) {
                continue;
            }
            String name = job.getMetadata().getName();
            log.warn("Removing Job {}, which this run left", name);
            reviewJobs.delete(name);
            awaitJobGone(name, job.getMetadata().getUid());
        }
    }

    /**
     * Removes the Pods this run left: those it recorded and those its own process labelled. A Pod that this process
     * did not create is kept, whatever its name.
     */
    private void removeCreated() {
        Set<String> left = new LinkedHashSet<>(created);
        for (String kind :
                List.of(KubernetesWorker.KIND_REVIEW, KubernetesWorker.KIND_HOST, KubernetesWorker.KIND_TURN)) {
            for (Pod pod : pods.list(kind)) {
                if (worker.createdBySelf(labelsOf(pod))) {
                    left.add(pod.getMetadata().getName());
                }
            }
        }
        for (String name : left) {
            Pod found = client.pods().inNamespace(namespace()).withName(name).get();
            if (found == null) {
                continue;
            }
            if (!worker.createdBySelf(labelsOf(found))) {
                log.warn("Kept Pod {}: this run did not create it", name);
                continue;
            }
            log.warn("Removing Pod {}, which this run left", name);
            client.pods()
                    .inNamespace(namespace())
                    .withName(name)
                    .withGracePeriod(0)
                    .delete();
            awaitGone(name, found.getMetadata().getUid());
        }
    }

    // --- Sandboxes ---

    private SandboxSpec review(UUID jobId, String script, Map<String, byte[]> files, Duration maxRuntime) {
        return review(jobId, script, files, limits(maxRuntime));
    }

    private SandboxSpec review(UUID jobId, String script, Map<String, byte[]> files, ResourceLimits limits) {
        return new SandboxSpec(
                jobId,
                0,
                agentImage.reference(),
                List.of("sh", "-c", script),
                Map.of(),
                ReviewJob.generateJobToken(),
                limits,
                null,
                files,
                Map.of(),
                List.of());
    }

    private static ResourceLimits limits(Duration maxRuntime) {
        return new ResourceLimits(1.0, maxRuntime);
    }

    private ExecSandboxes.Execution run(String sandbox, String script) {
        return turns.exec(sandbox, new ExecSandboxes.Command(List.of("sh", "-c", script), COMMAND, OUTPUT_LIMIT));
    }

    /** Stages the files through the production archive, added to the workspace or replacing one directory. */
    private void stage(String sandbox, Map<String, byte[]> files, @Nullable String replaced) throws IOException {
        Path archive = workspaces.createInputTar(files, Map.of());
        try (InputStream input = Files.newInputStream(archive)) {
            long size = Files.size(archive);
            List<String> names = files.keySet().stream()
                    .map(path -> path.split("/", -1)[0])
                    .distinct()
                    .sorted()
                    .toList();
            turns.stage(
                    sandbox,
                    replaced == null
                            ? new ExecSandboxes.Add(names, input, size, STAGE)
                            : new ExecSandboxes.Replace(replaced, input, size, STAGE));
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private static TurnDocuments documents() {
        Map<String, byte[]> built = new LinkedHashMap<>();
        built.put("inputs/context/user.json", USER_DOCUMENT.getBytes(UTF_8));
        return new TurnDocuments() {
            @Override
            public Map<String, byte[]> built() {
                return built;
            }

            @Override
            public Optional<byte[]> item(String path) {
                return Optional.empty();
            }

            @Override
            public String builtAt() {
                return "Fri 2026-10-23 10:00";
            }
        };
    }

    // --- Metrics ---

    private double executions(String outcome) {
        return meters.get(AgentMetrics.SANDBOX_EXECUTIONS)
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private double activeReviews() {
        return meters.get(AgentMetrics.SANDBOX_CONTAINERS_ACTIVE).gauge().value();
    }

    // --- Values ---

    /** Every byte value, so that a transfer that alters any byte shows. */
    private static byte[] synthetic(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (index * 31 + 7);
        }
        return bytes;
    }

    private static String sha256(byte[] content) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static String text(byte[] bytes) {
        return new String(bytes, UTF_8);
    }

    /**
     * The runner's manifest of a result whose command started no model: its four fields exactly, a complete scan and no
     * session. The budget is what the result had left, up to the runner's cap, so only its bounds are fixed.
     */
    private static void requireEmptyTraceManifest(Map<String, byte[]> outputFiles) {
        JsonNode manifest = MAPPER.readTree(Objects.requireNonNull(outputFiles.get(TRACE_MANIFEST), TRACE_MANIFEST));
        assertThat(manifest.isObject()).as("the manifest is a JSON object").isTrue();
        assertThat(manifest.propertyNames())
                .containsExactlyInAnyOrder("schemaVersion", "budgetBytes", "sessionScanTruncated", "sessions");
        JsonNode version = manifest.path("schemaVersion");
        assertThat(version.isIntegralNumber() && version.canConvertToLong())
                .as("schemaVersion is an integer")
                .isTrue();
        assertThat(version.longValue()).isEqualTo(1);
        JsonNode budget = manifest.path("budgetBytes");
        assertThat(budget.isIntegralNumber() && budget.canConvertToLong())
                .as("budgetBytes is an integer")
                .isTrue();
        assertThat(budget.longValue()).isPositive().isLessThanOrEqualTo(TRACE_BYTES);
        JsonNode truncated = manifest.path("sessionScanTruncated");
        assertThat(truncated.isBoolean())
                .as("sessionScanTruncated is a boolean")
                .isTrue();
        assertThat(truncated.booleanValue())
                .as("the scan of the sessions was complete")
                .isFalse();
        JsonNode sessions = manifest.path("sessions");
        assertThat(sessions.isArray()).as("sessions is an array").isTrue();
        assertThat(sessions.size())
                .as("no session: the command started no model")
                .isZero();
    }

    private static long millis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static long percentile(List<Long> values, int percentile) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    /** Runs one cleanup step; its failure joins the first one, and the next step still runs. */
    private static @Nullable Throwable attempt(@Nullable Throwable failure, Step step) {
        try {
            step.run();
            return failure;
        } catch (Exception | AssertionError e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (failure == null) {
                return e;
            }
            failure.addSuppressed(e);
            return failure;
        }
    }

    /** The context folder's read, Git, timeout and cancellation contracts on the qualified turn Pod. */
    @Nested
    class TurnContext {
        @TempDir
        Path temp;

        private KubernetesExecSandboxes runtime;
        private TurnSandboxes sandboxes;
        private TurnContextFolder folder;
        private TurnAttempt turn;
        private Instant deadline;
        private Path repository;

        @BeforeEach
        void setUp() throws Exception {
            runtime = turns;
            sandboxes = new TurnSandboxes(runtime, 1, new SimpleMeterRegistry());
            repository = repository(temp.resolve("api"));
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("loader", new FixedLoader(repository));
            folder = new TurnContextFolder(sandboxes, beans.getBeanProvider(ContextLoader.class), Clock.systemUTC());
            turn = new TurnAttempt(UUID.randomUUID(), 1L, 2L, UUID.randomUUID(), 1, UUID.randomUUID());
            deadline = Instant.now().plus(Duration.ofMinutes(5));
        }

        @AfterEach
        void tearDown() throws IOException {
            sandboxes.close();
        }

        @Test
        void shouldStageReadRunGitAndRemoveTheSandboxWhenTheAttemptEnds() throws Exception {
            sandboxes.reconcile((turnId, attempt) -> true);
            long poolStarted = System.nanoTime();
            while (TurnSandboxFixtures.pooled(sandboxes) < 1
                    && System.nanoTime() - poolStarted < Duration.ofSeconds(60).toNanos()) {
                Thread.sleep(50);
            }
            log.info("TIMING pool fill (cold start of one sandbox): {} ms", millis(poolStarted));
            assertThat(TurnSandboxFixtures.pooled(sandboxes)).isOne();

            long first = System.nanoTime();
            ObjectNode index = folder.read(turn, deadline, documents(), "INDEX.md", null, null);
            log.info("TIMING first call (acquire + stage + read): {} ms", millis(first));
            assertThat(index.path("ok").asBoolean()).as(index.toString()).isTrue();
            assertThat(index.path("text").asString()).contains("# Context folder", "inputs/context/activity.json");

            List<Long> reads = new ArrayList<>();
            for (int run = 0; run < 10; run++) {
                long started = System.nanoTime();
                ObjectNode page = folder.read(turn, deadline, documents(), "inputs/context/activity.json", 10, 5);
                reads.add(millis(started));
                assertThat(page.path("text").asString())
                        .contains("[3988 more lines in file. Use offset=15 to continue.]");
            }
            log.info("TIMING read p50={} ms p95={} ms", percentile(reads, 50), percentile(reads, 95));

            ObjectNode item = folder.read(turn, deadline, documents(), "inputs/context/issue/7.json", null, null);
            assertThat(item.path("text").asString()).contains("\"issue\" : 7");
            assertThat(folder.read(turn, deadline, documents(), "../etc/passwd", null, null)
                            .path("error")
                            .asString())
                    .isEqualTo("NOT_FOUND");
            assertThat(folder.read(turn, deadline, documents(), "inputs", null, null)
                            .path("error")
                            .asString())
                    .isEqualTo("IS_DIRECTORY");

            long loadStarted = System.nanoTime();
            ObjectNode loaded = folder.load(turn, deadline, documents(), "repos/github.com/acme/api");
            log.info("TIMING load (stage of a repository with its history): {} ms", millis(loadStarted));
            assertThat(loaded.path("ok").asBoolean()).as(loaded.toString()).isTrue();
            assertThat(loaded.path("resolved").path("cite").asString()).startsWith("@");

            List<Long> execs = new ArrayList<>();
            ObjectNode history = null;
            for (int run = 0; run < 10; run++) {
                long started = System.nanoTime();
                history = folder.exec(
                        turn, deadline, documents(), "git -C repos/github.com/acme/api log --format=%s", null);
                execs.add(millis(started));
            }
            log.info("TIMING exec git log p50={} ms p95={} ms", percentile(execs, 50), percentile(execs, 95));
            assertThat(history).isNotNull();
            assertThat(history.path("exitCode").asInt()).as(history.toString()).isZero();
            assertThat(history.path("output").asString()).contains("Second commit", "First commit");

            assertThat(folder.exec(turn, deadline, documents(), "sha256sum inputs/context/user.json", null)
                            .path("output")
                            .asString())
                    .as("the added document has its exact bytes")
                    .startsWith(sha256("{\n  \"counts\" : 3\n}"));
            assertThat(folder.exec(turn, deadline, documents(), "sha256sum repos/github.com/acme/api/README.md", null)
                            .path("output")
                            .asString())
                    .as("the loaded file has its exact bytes")
                    .startsWith(sha256("# API\n\nSecond line.\n"));

            ObjectNode write = folder.exec(turn, deadline, documents(), "touch INDEX.md.new || echo refused", null);
            assertThat(write.path("output").asString()).contains("refused");
            ObjectNode append = folder.exec(
                    turn,
                    deadline,
                    documents(),
                    "(echo x >> inputs/context/user.json) 2>/dev/null || echo refused",
                    null);
            assertThat(append.path("output").asString()).contains("refused");
            ObjectNode addition = folder.exec(
                    turn,
                    deadline,
                    documents(),
                    "touch repos/github.com/acme/api/new 2>/dev/null || echo refused",
                    null);
            assertThat(addition.path("output").asString()).contains("refused");
            ObjectNode environment = folder.exec(turn, deadline, documents(), "env | grep -c TOKEN || true", null);
            assertThat(environment.path("output").asString().strip()).isEqualTo("0");

            ObjectNode many = folder.exec(turn, deadline, documents(), "seq 1 5000", null);
            assertThat(many.path("truncated").asBoolean()).isTrue();
            String fullOutput = many.path("fullOutputPath").asString();
            assertThat(many.path("output").asString())
                    .endsWith("[Showing lines 3001-5000 of 5000. Full output: " + fullOutput + "]");
            assertThat(folder.read(turn, deadline, documents(), fullOutput, 1, 2)
                            .path("text")
                            .asString())
                    .startsWith("1\n2\n");

            Files.delete(repository.resolve("README.md"));
            Files.writeString(repository.resolve("NOTES.md"), "notes\n");
            ObjectNode reloaded = folder.load(turn, deadline, documents(), "repos/github.com/acme/api");
            assertThat(reloaded.path("ok").asBoolean()).as(reloaded.toString()).isTrue();
            assertThat(folder.exec(turn, deadline, documents(), "ls repos/github.com/acme/api", null)
                            .path("output")
                            .asString())
                    .as("a second load replaces the directory")
                    .isEqualTo("NOTES.md\n");

            long timeoutStarted = System.nanoTime();
            ObjectNode slow = folder.exec(
                    turn,
                    deadline,
                    documents(),
                    "setsid sh -c 'echo $$ > /tmp/detached.pid; exec sleep 300' & sleep 30",
                    1000);
            log.info("TIMING exec with a 1 s timeout: {} ms", millis(timeoutStarted));
            assertThat(slow.path("timedOut").asBoolean()).as(slow.toString()).isTrue();
            assertThat(millis(timeoutStarted)).isLessThan(10_000);
            ObjectNode detached = folder.exec(
                    turn,
                    deadline,
                    documents(),
                    "[ -s /tmp/detached.pid ] || { echo missing; exit 0; }; p=$(cat /tmp/detached.pid);"
                            + " for i in 1 2 3 4 5 6 7 8 9 10; do"
                            + " kill -0 \"$p\" 2>/dev/null || { echo gone; exit 0; }; sleep 0.2; done; echo alive",
                    null);
            assertThat(detached.path("output").asString())
                    .as("the cancellation ends a child that left the command's process group")
                    .isEqualTo("gone\n");
            ObjectNode after = folder.exec(turn, deadline, documents(), "echo still here", null);
            assertThat(after.path("output").asString())
                    .as("the kill of the sandbox user's processes leaves the sandbox running")
                    .isEqualTo("still here\n");

            String container = held();
            sandboxes.release(turn.turnId(), turn.attempt());
            awaitGone(container);
        }

        @Test
        void shouldRemoveTheSandboxAndEndItsCommandWhenTheAttemptStopsWhileTheCommandRuns() throws Exception {
            ObjectNode first = folder.exec(turn, deadline, documents(), "true", null);
            assertThat(first.path("exitCode").isInt()).as(first.toString()).isTrue();
            assertThat(first.path("exitCode").asInt()).isZero();
            CompletableFuture<ObjectNode> running =
                    CompletableFuture.supplyAsync(() -> folder.exec(turn, deadline, documents(), "sleep 50", null));
            Thread.sleep(1000);
            String container = held();

            long stopped = System.nanoTime();
            sandboxes.release(turn.turnId(), turn.attempt());
            ObjectNode ended = running.get();
            log.info("TIMING command end after the attempt stopped: {} ms", millis(stopped));

            assertThat(ended.path("ok").asBoolean()).as(ended.toString()).isFalse();
            assertThat(millis(stopped)).isLessThan(15_000);
            awaitGone(container);
        }

        /** The attempt's sandbox, which its first call took. */
        private String held() {
            return TurnSandboxFixtures.held(sandboxes, turn);
        }

        private void awaitGone(String container) throws InterruptedException {
            long started = System.nanoTime();
            while (runtime.list().stream().anyMatch(listed -> listed.id().equals(container))
                    && System.nanoTime() - started < Duration.ofSeconds(30).toNanos()) {
                Thread.sleep(100);
            }
            assertThat(runtime.list())
                    .as("the attempt's sandbox is removed")
                    .noneMatch(listed -> listed.id().equals(container));
        }

        private static TurnDocuments documents() {
            StringBuilder activity = new StringBuilder("{\n");
            for (int line = 1; line < 4000; line++) {
                activity.append("  \"line")
                        .append(line)
                        .append("\" : ")
                        .append(line)
                        .append(",\n");
            }
            activity.append("  \"end\" : true\n}");
            Map<String, byte[]> built = new LinkedHashMap<>();
            built.put("inputs/context/activity.json", activity.toString().getBytes(StandardCharsets.UTF_8));
            built.put("inputs/context/user.json", "{\n  \"counts\" : 3\n}".getBytes(StandardCharsets.UTF_8));
            return new TurnDocuments() {
                @Override
                public Map<String, byte[]> built() {
                    return built;
                }

                @Override
                public Optional<byte[]> item(String path) {
                    return path.equals("inputs/context/issue/7.json")
                            ? Optional.of("{\n  \"issue\" : 7\n}".getBytes(StandardCharsets.UTF_8))
                            : Optional.empty();
                }

                @Override
                public String builtAt() {
                    return "Tue 2026-10-20 10:00";
                }
            };
        }

        private static Path repository(Path directory) throws Exception {
            try (Git git = Git.init()
                    .setDirectory(directory.toFile())
                    .setInitialBranch("main")
                    .call()) {
                Files.writeString(directory.resolve("README.md"), "# API\n");
                git.add().addFilepattern(".").call();
                git.commit()
                        .setMessage("First commit")
                        .setAuthor("A", "a@example.invalid")
                        .setSign(false)
                        .call();
                Files.writeString(directory.resolve("README.md"), "# API\n\nSecond line.\n");
                git.add().addFilepattern(".").call();
                git.commit()
                        .setMessage("Second commit")
                        .setAuthor("A", "a@example.invalid")
                        .setSign(false)
                        .call();
            }
            return directory;
        }

        /** Loads the one test repository at its head, as the SCM loader stages a checkout. */
        private record FixedLoader(Path directory) implements ContextLoader {
            @Override
            public String prefix() {
                return "repos/";
            }

            @Override
            public String index(long workspaceId, long accountId) {
                return "## repos/\n\n- `repos/github.com/acme/api`\n";
            }

            @Override
            public Loaded load(long workspaceId, long accountId, String path, BooleanSupplier abandoned) {
                Supplier<String> head = () -> {
                    try (Git git = Git.open(directory.toFile())) {
                        return Objects.requireNonNull(git.getRepository().resolve("HEAD"))
                                .name();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                };
                String sha = head.get();
                return new Loaded() {
                    @Override
                    public String path() {
                        return "repos/github.com/acme/api";
                    }

                    @Override
                    public Path directory() {
                        return directory;
                    }

                    @Override
                    public Version version() {
                        return new Version("main", sha, "@" + sha.substring(0, 7));
                    }

                    @Override
                    public String note() {
                        return "Loaded.";
                    }

                    @Override
                    public void close() {}
                };
            }
        }

        private static String sha256(String content) throws NoSuchAlgorithmException {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        }

        private static long millis(long startedNanos) {
            return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        }

        private static long percentile(List<Long> values, int percentile) {
            List<Long> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, index));
        }
    }
}
