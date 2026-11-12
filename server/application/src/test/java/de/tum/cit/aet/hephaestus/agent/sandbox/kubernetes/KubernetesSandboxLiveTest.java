package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.AgentImageReferenceGuard;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

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

    /** The gateway port that the chart's policy admits from a sandbox Pod to its worker Pod. */
    private static final int GATEWAY_PORT = 8081;

    private static final Duration GONE = Duration.ofMinutes(2);
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
    private SimpleMeterRegistry meters;
    private SandboxExits exits;
    private KubernetesSandboxCache cache;
    private ServerSocket gateway;

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
        KubernetesSandboxProperties bound =
                validated(binder.bindOrCreate("hephaestus.sandbox.kubernetes", KubernetesSandboxProperties.class));
        // This JVM runs its own gateway, so the chart's Service, which selects the worker Pods, is no route to it.
        properties = new KubernetesSandboxProperties(
                bound.namespace(),
                bound.podName(),
                bound.podIp(),
                bound.runtimeClassName(),
                bound.seccompProfile(),
                bound.nodeSelector(),
                bound.tolerations(),
                bound.conversationPriorityClass(),
                bound.reviewPriorityClass(),
                bound.probeInterval(),
                "");
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
        levels.forEach((category, level) -> {
            String name = "root".equalsIgnoreCase(category) ? LoggingSystem.ROOT_LOGGER_NAME : category;
            LoggerConfiguration current = logging.getLoggerConfiguration(name);
            applied.add(new Applied(name, current == null ? null : current.getConfiguredLevel()));
            logging.setLogLevel(name, level);
        });

        var configuration = new KubernetesSandboxConfiguration();
        client = configuration.sandboxKubernetesClient();
        requireServiceAccountClient(client.getConfiguration());
        // The ledger id of this run's worker process, unique as the production one is.
        worker = new KubernetesWorker(properties, "live-" + UUID.randomUUID(), gatewayProperties.port());
        requireNoAuthorityInItsOwnNamespace();
        pods = configuration.kubernetesPods(client, worker, sandbox, agentImage);
        meters = new SimpleMeterRegistry();
        reviewJobs = configuration.kubernetesSandboxJobs(client, worker);
        exits = configuration.sandboxExits(pods, meters);
        cache = configuration.kubernetesSandboxCache(client, worker);
        // The probe proves that a sandbox reaches this worker's gateway port, and a TCP connection is all it checks.
        ServerSocket listening = new ServerSocket(gatewayProperties.port());
        gateway = listening;
        Thread.ofVirtual().start(() -> {
            while (!listening.isClosed()) {
                try {
                    listening.accept().close();
                } catch (IOException closed) {
                    return;
                }
            }
        });

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

    // --- The native Job controllers, without an executing adapter ---

    // --- Conversation tools Pods ---

    private KubernetesToolsPods toolsPods() {
        return new KubernetesToolsPods(pods, reviewJobs, agentImage.reference(), exits, cache, Clock.systemUTC());
    }

    @Test
    void shouldStartAToolsPodAndRemoveItWithItsJob() throws Exception {
        KubernetesToolsPods tools = toolsPods();
        String sandboxId = UUID.randomUUID().toString();
        String name = "hephaestus-tools-" + sandboxId;
        ResourceLimits limits = limits(Duration.ofMinutes(10));

        long started = System.nanoTime();
        String nonce = UUID.randomUUID().toString();
        ToolsPods.Started pod = tools.create(sandboxId, nonce, limits);
        log.info("TIMING tools Pod create to its tools program running: {} ms", millis(started));
        assertThat(pod.id()).isEqualTo(name);
        assertThat(pod.nonce()).isEqualTo(nonce);
        Pod running = Objects.requireNonNull(reviewJobs.podOf(name));
        track(running.getMetadata().getName());
        String uid = running.getMetadata().getUid();
        requireCustody(running, KubernetesWorker.KIND_TOOLS);
        requireHardened(running);
        try {
            assertThat(tools.list())
                    .as("the installation's tools Jobs, by name")
                    .anySatisfy(listed -> assertThat(listed.name()).isEqualTo(name));
        } finally {
            tools.remove(name);
        }
        awaitGone(running.getMetadata().getName(), uid);
        // A Foreground delete keeps the Job, with its finalizer, until its Pod is gone.
        client.batch()
                .v1()
                .jobs()
                .inNamespace(worker.namespace())
                .withName(name)
                .waitUntilCondition(Objects::isNull, 1, TimeUnit.MINUTES);
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

    private static Map<String, String> labelsOf(Pod pod) {
        Map<String, String> labels = pod.getMetadata().getLabels();
        return labels == null ? Map.of() : labels;
    }

    private static Map<String, String> labelsOf(Job job) {
        Map<String, String> labels = job.getMetadata().getLabels();
        return labels == null ? Map.of() : labels;
    }

    /** Removes the Jobs this run left, each with its Pod. A Job that this process did not create is kept. */
    private void removeCreatedJobs() {
        for (Job job : Stream.of(KubernetesWorker.KIND_TOOLS, KubernetesWorker.KIND_REVIEW)
                .flatMap(kind -> reviewJobs.list(kind).stream())
                .toList()) {
            if (!worker.createdBySelf(labelsOf(job))) {
                continue;
            }
            String name = job.getMetadata().getName();
            log.warn("Removing Job {}, which this run left", name);
            reviewJobs.deleteWithPod(name);
            awaitJobGone(name, job.getMetadata().getUid());
        }
    }

    /**
     * Removes the Pods this run left: those it recorded and those its own process labelled. A Pod that this process
     * did not create is kept, whatever its name.
     */
    private void removeCreated() {
        Set<String> left = new LinkedHashSet<>(created);
        for (String kind : List.of(KubernetesWorker.KIND_REVIEW, KubernetesWorker.KIND_TOOLS)) {
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

    private static ResourceLimits limits(Duration maxRuntime) {
        return new ResourceLimits(1.0, maxRuntime);
    }

    private static long millis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
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
}
