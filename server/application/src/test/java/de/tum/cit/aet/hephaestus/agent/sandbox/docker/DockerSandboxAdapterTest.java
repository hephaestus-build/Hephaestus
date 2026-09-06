package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.NetworkPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SecurityProfile;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;

class DockerSandboxAdapterTest extends BaseUnitTest {

    @Mock
    private SandboxNetworkManager networkManager;

    @Mock
    private SandboxWorkspaceManager workspaceManager;

    @Mock
    private SandboxContainerManager containerManager;

    @Mock
    private ContainerSecurityPolicy securityPolicy;

    private DockerSandboxAdapter sandboxAdapter;
    private SimpleMeterRegistry meterRegistry;

    private static final UUID JOB_ID = UUID.randomUUID();
    private static final String NETWORK_ID = "net-123abc";
    private static final String CONTAINER_ID = "container-456def";
    private static final String APP_SERVER_IP = "172.18.0.2";

    /** Reusable default host config — avoids 14-arg constructor duplication across tests. */
    private static final DockerOperations.HostConfigSpec DEFAULT_HOST_CONFIG = new DockerOperations.HostConfigSpec(
            4L * 1024 * 1024 * 1024,
            4L * 1024 * 1024 * 1024,
            2_000_000_000L,
            256,
            true,
            false,
            List.of("ALL"),
            List.of(),
            Map.of(),
            List.of(),
            "private",
            "none",
            null,
            Map.of());

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        sandboxAdapter = new DockerSandboxAdapter(
                networkManager, workspaceManager, containerManager, securityPolicy, 8081, meterRegistry);
    }

    private SandboxSpec createSpec() {
        return createSpec(false);
    }

    private SandboxSpec createSpec(boolean allowInternet) {
        return createSpec(allowInternet, null);
    }

    private SandboxSpec createSpec(
            boolean allowInternet, @org.jspecify.annotations.Nullable Map<String, String> volumeMounts) {
        return new SandboxSpec(
                JOB_ID,
                "alpine:latest",
                List.of("echo", "hello"),
                Map.of("FOO", "bar"),
                new NetworkPolicy(allowInternet, null, "test-token"),
                ResourceLimits.DEFAULT,
                SecurityProfile.DEFAULT,
                Map.of(".prompt", "test prompt".getBytes()),
                "/workspace/out",
                volumeMounts);
    }

    private void setupHappyPath() {
        setupExecution();
        when(workspaceManager.collectOutput(eq(CONTAINER_ID), anyString()))
                .thenReturn(Map.of("result.json", "{}".getBytes()));
    }

    private void setupExecution() {
        setupExecution(0, false);
    }

    private void setupExecution(int exitCode, boolean timedOut) {
        when(networkManager.createJobNetwork(eq(JOB_ID), eq(false))).thenReturn(NETWORK_ID);
        when(networkManager.connectAppServer(NETWORK_ID)).thenReturn(APP_SERVER_IP);
        when(securityPolicy.buildHostConfig(any(), any(), any())).thenReturn(DEFAULT_HOST_CONFIG);
        when(securityPolicy.buildLabels(JOB_ID))
                .thenReturn(Map.of("hephaestus.managed", "true", "hephaestus.job-id", JOB_ID.toString()));
        when(containerManager.createContainer(any())).thenReturn(CONTAINER_ID);
        when(containerManager.waitForCompletion(eq(CONTAINER_ID), any()))
                .thenReturn(new SandboxContainerManager.WaitOutcome(exitCode, timedOut));
        when(containerManager.getLogs(eq(CONTAINER_ID), anyInt())).thenReturn("hello\n");
    }

    @Nested
    class HappyPath {

        @Test
        void shouldExecuteFullLifecycle() {
            setupHappyPath();

            SandboxResult result = sandboxAdapter.execute(createSpec());

            assertThat(result.exitCode()).isZero();
            assertThat(result.timedOut()).isFalse();
            assertThat(result.outputFiles()).containsKey("result.json");
            assertThat(result.logs()).isEqualTo("hello\n");
            assertThat(result.duration()).isPositive();

            verify(networkManager).createJobNetwork(JOB_ID, false);
            verify(networkManager).connectAppServer(NETWORK_ID);
            verify(containerManager).createContainer(any());
            verify(workspaceManager).injectFiles(eq(CONTAINER_ID), any(), any());
            verify(containerManager).startContainer(CONTAINER_ID);
            verify(containerManager).waitForCompletion(eq(CONTAINER_ID), any());
            verify(workspaceManager).collectOutput(CONTAINER_ID, "/workspace/out");
            // 0 is every line: a Docker tail is applied by the daemon, so a persisted transcript that
            // asked for one would arrive already missing its beginning.
            verify(containerManager).getLogs(CONTAINER_ID, 0);

            verify(containerManager).forceRemove(CONTAINER_ID);
            verify(networkManager).disconnectAppServer(NETWORK_ID);
            verify(networkManager).removeNetwork(NETWORK_ID);
        }

        @Test
        void shouldInjectDefaultLlmProxyUrl() {
            setupHappyPath();

            SandboxSpec spec = new SandboxSpec(
                    JOB_ID,
                    "alpine:latest",
                    List.of("echo"),
                    Map.of(),
                    new NetworkPolicy(false, null, "token-123"),
                    ResourceLimits.DEFAULT,
                    SecurityProfile.DEFAULT,
                    Map.of(".prompt", "test".getBytes()),
                    "/workspace/out",
                    null);

            sandboxAdapter.execute(spec);

            ArgumentCaptor<DockerOperations.ContainerSpec> captor =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            verify(containerManager).createContainer(captor.capture());

            Map<String, String> env = captor.getValue().environment();
            // No per-provider path segment — the connection is identified from the authenticated token, not the URL.
            assertThat(env).containsEntry("LLM_PROXY_URL", "http://172.18.0.2:8081/internal/llm");
            assertThat(env).containsEntry("GATEWAY_URL", "http://172.18.0.2:8081");
            assertThat(env).containsEntry("LLM_PROXY_TOKEN", "token-123");
        }

        @Test
        void shouldPointTheSandboxAtTheConfiguredGatewayPort() {
            sandboxAdapter = new DockerSandboxAdapter(
                    networkManager, workspaceManager, containerManager, securityPolicy, 8090, meterRegistry);
            setupHappyPath();

            SandboxSpec spec = new SandboxSpec(
                    JOB_ID,
                    "alpine:latest",
                    List.of("echo"),
                    Map.of(),
                    new NetworkPolicy(false, null, "token-123"),
                    ResourceLimits.DEFAULT,
                    SecurityProfile.DEFAULT,
                    Map.of(".prompt", "test".getBytes()),
                    "/workspace/out",
                    null);

            sandboxAdapter.execute(spec);

            ArgumentCaptor<DockerOperations.ContainerSpec> captor =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            verify(containerManager).createContainer(captor.capture());

            assertThat(captor.getValue().environment())
                    .containsEntry("LLM_PROXY_URL", "http://172.18.0.2:8090/internal/llm");
        }

        @Test
        void shouldResolveProxyUrlPlaceholder() {
            setupHappyPath();

            SandboxSpec spec = new SandboxSpec(
                    JOB_ID,
                    "alpine:latest",
                    List.of("echo"),
                    Map.of(),
                    new NetworkPolicy(false, "http://{appServerIp}:9090/v1", "tok"),
                    ResourceLimits.DEFAULT,
                    SecurityProfile.DEFAULT,
                    Map.of(".prompt", "test".getBytes()),
                    "/workspace/out",
                    null);

            sandboxAdapter.execute(spec);

            ArgumentCaptor<DockerOperations.ContainerSpec> captor =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            verify(containerManager).createContainer(captor.capture());

            assertThat(captor.getValue().environment()).containsEntry("LLM_PROXY_URL", "http://172.18.0.2:9090/v1");
        }

        @Test
        void shouldUseExplicitProxyUrl() {
            setupHappyPath();

            SandboxSpec spec = new SandboxSpec(
                    JOB_ID,
                    "alpine:latest",
                    List.of("echo"),
                    Map.of(),
                    new NetworkPolicy(false, "https://my-proxy.example.com/api", null),
                    ResourceLimits.DEFAULT,
                    SecurityProfile.DEFAULT,
                    Map.of(".prompt", "test".getBytes()),
                    "/workspace/out",
                    null);

            sandboxAdapter.execute(spec);

            ArgumentCaptor<DockerOperations.ContainerSpec> captor =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            verify(containerManager).createContainer(captor.capture());

            assertThat(captor.getValue().environment())
                    .containsEntry("LLM_PROXY_URL", "https://my-proxy.example.com/api");
        }

        @Test
        void shouldMergeUserEnvironment() {
            setupHappyPath();

            sandboxAdapter.execute(createSpec());

            ArgumentCaptor<DockerOperations.ContainerSpec> captor =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            verify(containerManager).createContainer(captor.capture());

            assertThat(captor.getValue().environment()).containsEntry("FOO", "bar");
        }

        @Test
        void shouldFilterBlockedEnvVars() {
            setupHappyPath();

            SandboxSpec spec = new SandboxSpec(
                    JOB_ID,
                    "alpine:latest",
                    List.of("echo"),
                    Map.of(
                            "SAFE_VAR",
                            "ok",
                            "LD_PRELOAD",
                            "/evil.so",
                            "AWS_SECRET_ACCESS_KEY",
                            "leaked",
                            "DOCKER_HOST",
                            "tcp://evil",
                            "GOOGLE_CLOUD_PROJECT",
                            "stolen",
                            "HOME",
                            "/home/agent"),
                    new NetworkPolicy(false, null, "tok"),
                    ResourceLimits.DEFAULT,
                    SecurityProfile.DEFAULT,
                    Map.of(".prompt", "test".getBytes()),
                    "/workspace/out",
                    null);

            sandboxAdapter.execute(spec);

            ArgumentCaptor<DockerOperations.ContainerSpec> captor =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            verify(containerManager).createContainer(captor.capture());

            Map<String, String> env = captor.getValue().environment();
            assertThat(env).containsEntry("SAFE_VAR", "ok");
            assertThat(env).doesNotContainKey("LD_PRELOAD");
            assertThat(env).doesNotContainKey("AWS_SECRET_ACCESS_KEY");
            assertThat(env).doesNotContainKey("DOCKER_HOST");
            assertThat(env).doesNotContainKey("GOOGLE_CLOUD_PROJECT");
            assertThat(env).containsEntry("HOME", "/home/agent");
        }

        @Test
        void shouldCreateInternetNetwork() {
            when(networkManager.createJobNetwork(eq(JOB_ID), eq(true))).thenReturn(NETWORK_ID);
            when(networkManager.connectAppServer(NETWORK_ID)).thenReturn(APP_SERVER_IP);
            when(securityPolicy.buildHostConfig(any(), any(), any())).thenReturn(DEFAULT_HOST_CONFIG);
            when(securityPolicy.buildLabels(JOB_ID)).thenReturn(Map.of("hephaestus.managed", "true"));
            when(containerManager.createContainer(any())).thenReturn(CONTAINER_ID);
            when(containerManager.waitForCompletion(eq(CONTAINER_ID), any()))
                    .thenReturn(new SandboxContainerManager.WaitOutcome(0, false));
            when(workspaceManager.collectOutput(eq(CONTAINER_ID), anyString())).thenReturn(Map.of());
            when(containerManager.getLogs(eq(CONTAINER_ID), anyInt())).thenReturn("");

            sandboxAdapter.execute(createSpec(true));

            verify(networkManager).createJobNetwork(JOB_ID, true);
        }

        @Test
        void shouldSkipInjectionWhenNoFiles() {
            setupHappyPath();

            SandboxSpec specWithoutFiles = new SandboxSpec(
                    JOB_ID,
                    "alpine:latest",
                    List.of("echo"),
                    Map.of(),
                    new NetworkPolicy(false, null, null),
                    ResourceLimits.DEFAULT,
                    SecurityProfile.DEFAULT,
                    Map.of(),
                    "/workspace/out",
                    null);

            sandboxAdapter.execute(specWithoutFiles);

            verify(workspaceManager, never()).injectFiles(anyString(), any());
        }

        @Test
        void shouldHandleNullNetworkPolicy() {
            when(networkManager.createJobNetwork(eq(JOB_ID), eq(false))).thenReturn(NETWORK_ID);
            when(networkManager.connectAppServer(NETWORK_ID)).thenReturn(APP_SERVER_IP);
            when(securityPolicy.buildHostConfig(any(), any(), any())).thenReturn(DEFAULT_HOST_CONFIG);
            when(securityPolicy.buildLabels(JOB_ID)).thenReturn(Map.of());
            when(containerManager.createContainer(any())).thenReturn(CONTAINER_ID);
            when(containerManager.waitForCompletion(eq(CONTAINER_ID), any()))
                    .thenReturn(new SandboxContainerManager.WaitOutcome(0, false));
            when(workspaceManager.collectOutput(eq(CONTAINER_ID), anyString())).thenReturn(Map.of());
            when(containerManager.getLogs(eq(CONTAINER_ID), anyInt())).thenReturn("");

            SandboxSpec spec = new SandboxSpec(
                    JOB_ID,
                    "alpine:latest",
                    List.of("echo"),
                    Map.of("USER_VAR", "value"),
                    null,
                    ResourceLimits.DEFAULT,
                    SecurityProfile.DEFAULT,
                    Map.of(),
                    "/workspace/out",
                    null);

            SandboxResult result = sandboxAdapter.execute(spec);
            assertThat(result.exitCode()).isZero();

            ArgumentCaptor<DockerOperations.ContainerSpec> captor =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            verify(containerManager).createContainer(captor.capture());
            Map<String, String> env = captor.getValue().environment();
            assertThat(env).doesNotContainKey("LLM_PROXY_URL");
            assertThat(env).doesNotContainKey("LLM_PROXY_TOKEN");
            assertThat(env).containsEntry("USER_VAR", "value");
        }

        @Test
        void usesSpecOutputPath() {
            setupHappyPath();

            SandboxSpec spec = new SandboxSpec(
                    JOB_ID,
                    "alpine:latest",
                    List.of("echo"),
                    Map.of(),
                    new NetworkPolicy(false, null, null),
                    ResourceLimits.DEFAULT,
                    SecurityProfile.DEFAULT,
                    Map.of(),
                    "/custom/output",
                    null);

            sandboxAdapter.execute(spec);

            verify(workspaceManager).collectOutput(CONTAINER_ID, "/custom/output");
        }
    }

    @Nested
    class TimeoutHandling {

        private void setupTimeoutPath() {
            when(networkManager.createJobNetwork(eq(JOB_ID), eq(false))).thenReturn(NETWORK_ID);
            when(networkManager.connectAppServer(NETWORK_ID)).thenReturn(APP_SERVER_IP);
            when(securityPolicy.buildHostConfig(any(), any(), any())).thenReturn(DEFAULT_HOST_CONFIG);
            when(securityPolicy.buildLabels(JOB_ID))
                    .thenReturn(Map.of("hephaestus.managed", "true", "hephaestus.job-id", JOB_ID.toString()));
            when(containerManager.createContainer(any())).thenReturn(CONTAINER_ID);
            when(containerManager.waitForCompletion(eq(CONTAINER_ID), any()))
                    .thenReturn(new SandboxContainerManager.WaitOutcome(137, true));
            when(containerManager.getLogs(eq(CONTAINER_ID), anyInt())).thenReturn("timeout\n");
        }

        @Test
        void shouldReturnTimedOutOnTimeout() {
            setupTimeoutPath();
            when(workspaceManager.collectOutput(eq(CONTAINER_ID), anyString())).thenReturn(Map.of());

            SandboxResult result = sandboxAdapter.execute(createSpec());

            assertThat(result.timedOut()).isTrue();
            assertThat(result.exitCode()).isEqualTo(137);
        }

        @Test
        void shouldCollectOutputOnTimeout() {
            setupTimeoutPath();
            when(workspaceManager.collectOutput(eq(CONTAINER_ID), anyString()))
                    .thenReturn(Map.of("partial.json", "{}".getBytes()));

            SandboxResult result = sandboxAdapter.execute(createSpec());

            assertThat(result.outputFiles()).containsKey("partial.json");
            verify(workspaceManager).collectOutput(CONTAINER_ID, "/workspace/out");
        }
    }

    @Nested
    class FailureHandling {

        @ParameterizedTest
        @CsvSource({"137,true", "42,false"})
        void shouldPreserveTerminalFailureWhenOutputCollectionFails(int exitCode, boolean timedOut) {
            setupExecution(exitCode, timedOut);
            when(workspaceManager.collectOutput(eq(CONTAINER_ID), anyString()))
                    .thenThrow(new SandboxException("No output directory"));

            var result = sandboxAdapter.execute(createSpec());

            assertThat(result.timedOut()).isEqualTo(timedOut);
            assertThat(result.exitCode()).isEqualTo(exitCode);
            assertThat(result.outputFiles()).isEmpty();
            verify(containerManager).forceRemove(CONTAINER_ID);
        }

        @Test
        void shouldFailAndCleanUpWhenOutputIsInvalidDespiteZeroExit() {
            setupExecution();
            when(workspaceManager.collectOutput(eq(CONTAINER_ID), anyString()))
                    .thenThrow(new SandboxException("Invalid output archive"));

            assertThatThrownBy(() -> sandboxAdapter.execute(createSpec()))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("Invalid output archive");
            verify(containerManager).forceRemove(CONTAINER_ID);
            verify(networkManager).disconnectAppServer(NETWORK_ID);
            verify(networkManager).removeNetwork(NETWORK_ID);
        }

        @Test
        void shouldThrowOnNetworkFailure() {
            when(networkManager.createJobNetwork(any(), eq(false)))
                    .thenThrow(new SandboxException("Network creation failed"));

            assertThatThrownBy(() -> sandboxAdapter.execute(createSpec()))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("Network creation failed");
        }

        @Test
        void shouldCleanupOnContainerFailure() {
            when(networkManager.createJobNetwork(eq(JOB_ID), eq(false))).thenReturn(NETWORK_ID);
            when(networkManager.connectAppServer(NETWORK_ID)).thenReturn(APP_SERVER_IP);
            when(securityPolicy.buildHostConfig(any(), any(), any())).thenReturn(DEFAULT_HOST_CONFIG);
            when(securityPolicy.buildLabels(JOB_ID)).thenReturn(Map.of());
            when(containerManager.createContainer(any())).thenThrow(new SandboxException("Image not found"));

            assertThatThrownBy(() -> sandboxAdapter.execute(createSpec())).isInstanceOf(SandboxException.class);

            verify(networkManager).disconnectAppServer(NETWORK_ID);
            verify(networkManager).removeNetwork(NETWORK_ID);
        }

        @Test
        void shouldToleratePartialCleanupFailure() {
            setupHappyPath();
            doThrow(new SandboxException("Container stuck"))
                    .when(containerManager)
                    .forceRemove(CONTAINER_ID);

            SandboxResult result = sandboxAdapter.execute(createSpec());
            assertThat(result.exitCode()).isZero();

            verify(networkManager).disconnectAppServer(NETWORK_ID);
            verify(networkManager).removeNetwork(NETWORK_ID);
        }

        @Test
        void shouldCaptureLogsOnError() {
            when(networkManager.createJobNetwork(eq(JOB_ID), eq(false))).thenReturn(NETWORK_ID);
            when(networkManager.connectAppServer(NETWORK_ID)).thenReturn(APP_SERVER_IP);
            when(securityPolicy.buildHostConfig(any(), any(), any())).thenReturn(DEFAULT_HOST_CONFIG);
            when(securityPolicy.buildLabels(JOB_ID)).thenReturn(Map.of());
            when(containerManager.createContainer(any())).thenReturn(CONTAINER_ID);
            when(containerManager.waitForCompletion(eq(CONTAINER_ID), any()))
                    .thenThrow(new SandboxException("Docker daemon lost"));
            when(containerManager.getLogs(eq(CONTAINER_ID), anyInt())).thenReturn("error logs here");

            assertThatThrownBy(() -> sandboxAdapter.execute(createSpec()))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("Docker daemon lost");

            // 500 mirrors DockerSandboxAdapter.ERROR_ECHO_TAIL_LINES: this echo only reaches the log, cut
            // to 32 KB, so it reads a tail rather than the whole stream.
            InOrder inOrder = inOrder(containerManager);
            inOrder.verify(containerManager).getLogs(CONTAINER_ID, 500);
            inOrder.verify(containerManager).forceRemove(CONTAINER_ID);
        }

        @Test
        void shouldUseDefaultSecurityProfileWhenNull() {
            when(networkManager.createJobNetwork(eq(JOB_ID), eq(false))).thenReturn(NETWORK_ID);
            when(networkManager.connectAppServer(NETWORK_ID)).thenReturn(APP_SERVER_IP);
            when(securityPolicy.buildHostConfig(any(), any(), any())).thenReturn(DEFAULT_HOST_CONFIG);
            when(securityPolicy.buildLabels(JOB_ID)).thenReturn(Map.of());
            when(containerManager.createContainer(any())).thenReturn(CONTAINER_ID);
            when(containerManager.waitForCompletion(eq(CONTAINER_ID), any()))
                    .thenReturn(new SandboxContainerManager.WaitOutcome(0, false));
            when(workspaceManager.collectOutput(eq(CONTAINER_ID), anyString())).thenReturn(Map.of());
            when(containerManager.getLogs(eq(CONTAINER_ID), anyInt())).thenReturn("");

            SandboxSpec specWithNullSecurity = new SandboxSpec(
                    JOB_ID,
                    "alpine:latest",
                    List.of("echo"),
                    Map.of(),
                    new NetworkPolicy(false, null, null),
                    ResourceLimits.DEFAULT,
                    null,
                    Map.of(),
                    "/workspace/out",
                    null);

            SandboxResult result = sandboxAdapter.execute(specWithNullSecurity);
            assertThat(result.exitCode()).isZero();

            ArgumentCaptor<SecurityProfile> secCaptor = ArgumentCaptor.forClass(SecurityProfile.class);
            verify(securityPolicy).buildHostConfig(secCaptor.capture(), any(), any());
            assertThat(secCaptor.getValue()).isEqualTo(SecurityProfile.DEFAULT);
        }
    }

    @Nested
    class Cancellation {

        @Test
        void shouldThrowWhenCancelRacesNetworkCreation() {
            when(networkManager.createJobNetwork(eq(JOB_ID), eq(false))).thenAnswer(invocation -> {
                sandboxAdapter.cancel(JOB_ID);
                return NETWORK_ID;
            });
            when(networkManager.connectAppServer(NETWORK_ID)).thenReturn(APP_SERVER_IP);

            assertThatThrownBy(() -> sandboxAdapter.execute(createSpec()))
                    .isInstanceOf(SandboxCancelledException.class)
                    .hasMessageContaining("cancelled");
        }

        @Test
        void shouldStopRunningContainer() throws Exception {
            CountDownLatch containerStarted = new CountDownLatch(1);
            CountDownLatch cancelDone = new CountDownLatch(1);
            var thrownException = new AtomicReference<Exception>();

            when(networkManager.createJobNetwork(eq(JOB_ID), eq(false))).thenReturn(NETWORK_ID);
            when(networkManager.connectAppServer(NETWORK_ID)).thenReturn(APP_SERVER_IP);
            when(securityPolicy.buildHostConfig(any(), any(), any())).thenReturn(DEFAULT_HOST_CONFIG);
            when(securityPolicy.buildLabels(JOB_ID)).thenReturn(Map.of("hephaestus.managed", "true"));
            when(containerManager.createContainer(any())).thenReturn(CONTAINER_ID);

            when(containerManager.waitForCompletion(eq(CONTAINER_ID), any())).thenAnswer(inv -> {
                containerStarted.countDown();
                cancelDone.await(5, TimeUnit.SECONDS);
                return new SandboxContainerManager.WaitOutcome(137, false);
            });
            // No getLogs stub: cancel() throws before the COLLECT phase or captureLogsOnError() run.

            Thread bg = new Thread(() -> {
                try {
                    sandboxAdapter.execute(createSpec());
                } catch (Exception e) {
                    thrownException.set(e);
                }
            });
            bg.start();

            assertThat(containerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            sandboxAdapter.cancel(JOB_ID);
            cancelDone.countDown();
            bg.join(5000);
            assertThat(bg.isAlive()).isFalse();

            verify(containerManager).stopContainer(CONTAINER_ID);
            assertThat(thrownException.get()).isInstanceOf(SandboxCancelledException.class);
        }

        @Test
        void shouldNoOpForUnknownJob() {
            assertThatCode(() -> sandboxAdapter.cancel(UUID.randomUUID())).doesNotThrowAnyException();
            verify(containerManager, never()).stopContainer(anyString());
        }
    }

    @Nested
    class HealthCheck {

        @Test
        void shouldReturnTrueWhenHealthy() {
            when(containerManager.ping()).thenReturn(true);
            assertThat(sandboxAdapter.isHealthy()).isTrue();
        }

        @Test
        void shouldReturnFalseWhenUnhealthy() {
            when(containerManager.ping()).thenReturn(false);
            assertThat(sandboxAdapter.isHealthy()).isFalse();
        }
    }

    @Nested
    class Metrics {

        @Test
        void shouldIncrementSuccessCounter() {
            setupHappyPath();

            sandboxAdapter.execute(createSpec());

            assertThat(meterRegistry
                            .counter("sandbox.executions", "outcome", "success")
                            .count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry
                            .counter("sandbox.executions", "outcome", "failure")
                            .count())
                    .isZero();
        }

        @Test
        void shouldIncrementTimeoutCounter() {
            when(networkManager.createJobNetwork(eq(JOB_ID), eq(false))).thenReturn(NETWORK_ID);
            when(networkManager.connectAppServer(NETWORK_ID)).thenReturn(APP_SERVER_IP);
            when(securityPolicy.buildHostConfig(any(), any(), any())).thenReturn(DEFAULT_HOST_CONFIG);
            when(securityPolicy.buildLabels(JOB_ID)).thenReturn(Map.of());
            when(containerManager.createContainer(any())).thenReturn(CONTAINER_ID);
            when(containerManager.waitForCompletion(eq(CONTAINER_ID), any()))
                    .thenReturn(new SandboxContainerManager.WaitOutcome(137, true));
            when(workspaceManager.collectOutput(eq(CONTAINER_ID), anyString())).thenReturn(Map.of());
            when(containerManager.getLogs(eq(CONTAINER_ID), anyInt())).thenReturn("");

            sandboxAdapter.execute(createSpec());

            assertThat(meterRegistry
                            .counter("sandbox.executions", "outcome", "timeout")
                            .count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry
                            .counter("sandbox.executions", "outcome", "success")
                            .count())
                    .isZero();
        }

        @Test
        void shouldIncrementFailureCounter() {
            when(networkManager.createJobNetwork(any(), eq(false))).thenThrow(new SandboxException("boom"));

            try {
                sandboxAdapter.execute(createSpec());
            } catch (SandboxException ignored) {
            }

            assertThat(meterRegistry
                            .counter("sandbox.executions", "outcome", "failure")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void shouldIncrementCancelledCounter() {
            when(networkManager.createJobNetwork(eq(JOB_ID), eq(false))).thenAnswer(invocation -> {
                sandboxAdapter.cancel(JOB_ID);
                return NETWORK_ID;
            });
            when(networkManager.connectAppServer(NETWORK_ID)).thenReturn(APP_SERVER_IP);

            try {
                sandboxAdapter.execute(createSpec());
            } catch (SandboxCancelledException ignored) {
            }

            assertThat(meterRegistry
                            .counter("sandbox.executions", "outcome", "cancelled")
                            .count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry
                            .counter("sandbox.executions", "outcome", "failure")
                            .count())
                    .isZero();
        }

        @Test
        void shouldRecordDurationAlways() {
            when(networkManager.createJobNetwork(any(), eq(false))).thenThrow(new SandboxException("fail"));

            try {
                sandboxAdapter.execute(createSpec());
            } catch (SandboxException ignored) {
            }

            assertThat(meterRegistry.timer("sandbox.execution.duration").count())
                    .isEqualTo(1);
        }

        @Test
        void shouldIncrementCleanupFailureCounterWithStep() {
            setupHappyPath();
            doThrow(new SandboxException("stuck")).when(containerManager).forceRemove(CONTAINER_ID);

            sandboxAdapter.execute(createSpec());

            assertThat(meterRegistry
                            .counter("sandbox.cleanup.failures", "step", "remove container")
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void shouldTrackActiveContainersGauge() throws Exception {
            CountDownLatch inExecution = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            when(networkManager.createJobNetwork(eq(JOB_ID), eq(false))).thenReturn(NETWORK_ID);
            when(networkManager.connectAppServer(NETWORK_ID)).thenReturn(APP_SERVER_IP);
            when(securityPolicy.buildHostConfig(any(), any(), any())).thenReturn(DEFAULT_HOST_CONFIG);
            when(securityPolicy.buildLabels(JOB_ID)).thenReturn(Map.of("hephaestus.managed", "true"));
            when(containerManager.createContainer(any())).thenReturn(CONTAINER_ID);
            when(containerManager.waitForCompletion(eq(CONTAINER_ID), any())).thenAnswer(inv -> {
                inExecution.countDown();
                release.await(5, TimeUnit.SECONDS);
                return new SandboxContainerManager.WaitOutcome(0, false);
            });
            when(workspaceManager.collectOutput(eq(CONTAINER_ID), anyString())).thenReturn(Map.of());
            when(containerManager.getLogs(eq(CONTAINER_ID), anyInt())).thenReturn("");

            Thread bg = new Thread(() -> {
                try {
                    sandboxAdapter.execute(createSpec());
                } catch (Exception ignored) {
                }
            });
            bg.start();

            assertThat(inExecution.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(meterRegistry.get("sandbox.containers.active").gauge().value())
                    .isEqualTo(1.0);

            release.countDown();
            bg.join(5000);
            assertThat(bg.isAlive()).isFalse();

            assertThat(meterRegistry.get("sandbox.containers.active").gauge().value())
                    .isZero();
        }
    }

    @Nested
    class DuplicateJobGuard {

        @Test
        void shouldRejectDuplicateJobId() throws Exception {
            CountDownLatch enteredExecute = new CountDownLatch(1);
            CountDownLatch releaseBlock = new CountDownLatch(1);

            when(networkManager.createJobNetwork(eq(JOB_ID), eq(false))).thenAnswer(inv -> {
                enteredExecute.countDown();
                releaseBlock.await(5, TimeUnit.SECONDS);
                return NETWORK_ID;
            });

            Thread bg = new Thread(() -> {
                try {
                    sandboxAdapter.execute(createSpec());
                } catch (Exception ignored) {
                }
            });
            bg.start();

            assertThat(enteredExecute.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> sandboxAdapter.execute(createSpec()))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("already executing");

            releaseBlock.countDown();
            bg.join(5000);
            assertThat(bg.isAlive()).isFalse();
        }
    }

    @Nested
    @DisplayName("Environment variable blocklist")
    class EnvVarBlocklist {

        static Stream<String> exactBlockedVars() {
            return SandboxEnvBlocklist.BLOCKED_NAMES.stream();
        }

        static Stream<String> prefixBlockedVars() {
            return Stream.of(
                    "AWS_ACCESS_KEY_ID",
                    "AWS_SECRET_ACCESS_KEY",
                    "AWS_SESSION_TOKEN",
                    "AWS_ROLE_ARN",
                    "GOOGLE_APPLICATION_CREDENTIALS",
                    "GOOGLE_CLOUD_PROJECT",
                    "GCP_PROJECT",
                    "AZURE_CLIENT_SECRET",
                    "AZURE_TENANT_ID",
                    "DOCKER_HOST",
                    "DOCKER_TLS_VERIFY",
                    "DOCKER_CERT_PATH",
                    "ALIBABA_CLOUD_ACCESS_KEY",
                    "GIT_CONFIG_COUNT",
                    "GIT_CONFIG_KEY_0",
                    "GIT_CONFIG_VALUE_99");
        }

        @ParameterizedTest(name = "should block exact var: {0}")
        @MethodSource("exactBlockedVars")
        void shouldBlockExactVars(String varName) {
            assertThat(SandboxEnvBlocklist.isBlocked(varName)).isTrue();
        }

        @ParameterizedTest(name = "should block prefix var: {0}")
        @MethodSource("prefixBlockedVars")
        void shouldBlockPrefixVars(String varName) {
            assertThat(SandboxEnvBlocklist.isBlocked(varName)).isTrue();
        }

        @Test
        void shouldAllowSafeVars() {
            assertThat(SandboxEnvBlocklist.isBlocked("MY_APP_KEY")).isFalse();
            assertThat(SandboxEnvBlocklist.isBlocked("FOO")).isFalse();
            assertThat(SandboxEnvBlocklist.isBlocked("CUSTOM_VAR")).isFalse();
        }

        @Test
        void shouldBlockCaseVariants() {
            // Some tools and shells inject lowercase variants.
            assertThat(SandboxEnvBlocklist.isBlocked("aws_access_key_id")).isTrue();
            assertThat(SandboxEnvBlocklist.isBlocked("docker_host")).isTrue();
            assertThat(SandboxEnvBlocklist.isBlocked("Google_Cloud_Project")).isTrue();
            assertThat(SandboxEnvBlocklist.isBlocked("Azure_Client_Secret")).isTrue();
        }
    }

    @Nested
    class GitSecurityConfig {

        @Test
        void shouldContainAllExpectedKeys() {
            assertThat(DockerSandboxAdapter.GIT_SECURITY_CONFIGS)
                    .extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder(
                            "core.hooksPath",
                            "core.fsmonitor",
                            "core.sshCommand",
                            "core.askPass",
                            "core.editor",
                            "core.pager",
                            "core.gitProxy",
                            "sequence.editor",
                            "credential.helper",
                            "diff.external",
                            "protocol.ext.allow");
        }

        @Test
        void shouldInjectSecurityConfigsWithoutVolumeMounts() {
            setupHappyPath();

            sandboxAdapter.execute(createSpec());

            ArgumentCaptor<DockerOperations.ContainerSpec> captor =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            verify(containerManager).createContainer(captor.capture());

            Map<String, String> env = captor.getValue().environment();

            int count = Integer.parseInt(env.get("GIT_CONFIG_COUNT"));
            assertThat(count).isEqualTo(DockerSandboxAdapter.GIT_SECURITY_CONFIGS.size());

            for (int i = 0; i < DockerSandboxAdapter.GIT_SECURITY_CONFIGS.size(); i++) {
                var expected = DockerSandboxAdapter.GIT_SECURITY_CONFIGS.get(i);
                assertThat(env.get("GIT_CONFIG_KEY_" + i)).isEqualTo(expected.getKey());
                assertThat(env.get("GIT_CONFIG_VALUE_" + i)).isEqualTo(expected.getValue());
            }
        }

        @Test
        void shouldInjectSafeDirectoryPerVolumeMount() {
            setupHappyPath();

            // Use LinkedHashMap to guarantee iteration order for index assertions
            Map<String, String> mounts = new LinkedHashMap<>();
            mounts.put("/host/repo1", "/workspace/repo1");
            mounts.put("/host/repo2", "/workspace/repo2");
            SandboxSpec spec = createSpec(false, mounts);

            sandboxAdapter.execute(spec);

            ArgumentCaptor<DockerOperations.ContainerSpec> captor =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            verify(containerManager).createContainer(captor.capture());

            Map<String, String> env = captor.getValue().environment();

            assertThat(env.get("GIT_CONFIG_KEY_0")).isEqualTo("safe.directory");
            assertThat(env.get("GIT_CONFIG_VALUE_0")).isEqualTo("/workspace/repo1");
            assertThat(env.get("GIT_CONFIG_KEY_1")).isEqualTo("safe.directory");
            assertThat(env.get("GIT_CONFIG_VALUE_1")).isEqualTo("/workspace/repo2");

            int securityStartIdx = mounts.size();
            for (int i = 0; i < DockerSandboxAdapter.GIT_SECURITY_CONFIGS.size(); i++) {
                var expected = DockerSandboxAdapter.GIT_SECURITY_CONFIGS.get(i);
                assertThat(env.get("GIT_CONFIG_KEY_" + (securityStartIdx + i))).isEqualTo(expected.getKey());
                assertThat(env.get("GIT_CONFIG_VALUE_" + (securityStartIdx + i)))
                        .isEqualTo(expected.getValue());
            }

            int expectedCount = mounts.size() + DockerSandboxAdapter.GIT_SECURITY_CONFIGS.size();
            assertThat(env.get("GIT_CONFIG_COUNT")).isEqualTo(String.valueOf(expectedCount));
        }

        @Test
        @DisplayName("should set GIT_TERMINAL_PROMPT and GIT_ATTR_NOSYSTEM")
        void shouldSetGitHardeningEnvVars() {
            setupHappyPath();

            sandboxAdapter.execute(createSpec());

            ArgumentCaptor<DockerOperations.ContainerSpec> captor =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            verify(containerManager).createContainer(captor.capture());

            Map<String, String> env = captor.getValue().environment();
            assertThat(env).containsEntry("GIT_TERMINAL_PROMPT", "0");
            assertThat(env).containsEntry("GIT_ATTR_NOSYSTEM", "1");
        }

        @Test
        void shouldBlockCallerGitConfigVars() {
            assertThat(SandboxEnvBlocklist.isBlocked("GIT_CONFIG_COUNT")).isTrue();
            assertThat(SandboxEnvBlocklist.isBlocked("GIT_CONFIG_KEY_0")).isTrue();
            assertThat(SandboxEnvBlocklist.isBlocked("GIT_CONFIG_VALUE_99")).isTrue();
        }

        @Test
        void shouldOverwriteSecurityEnvVarsViaOrdering() {
            setupHappyPath();

            // Even if these leaked past the blocklist, the injection at the end of buildEnvironment() wins.
            sandboxAdapter.execute(createSpec());

            ArgumentCaptor<DockerOperations.ContainerSpec> captor =
                    ArgumentCaptor.forClass(DockerOperations.ContainerSpec.class);
            verify(containerManager).createContainer(captor.capture());

            Map<String, String> env = captor.getValue().environment();

            assertThat(env).containsEntry("GIT_TERMINAL_PROMPT", "0");
            assertThat(env).containsEntry("GIT_ATTR_NOSYSTEM", "1");
            assertThat(env).containsKey("GIT_CONFIG_COUNT");

            assertThat(env.get("GIT_CONFIG_KEY_0")).isEqualTo("core.hooksPath");
            assertThat(env.get("GIT_CONFIG_VALUE_0")).isEqualTo("/nonexistent");
        }
    }
}
