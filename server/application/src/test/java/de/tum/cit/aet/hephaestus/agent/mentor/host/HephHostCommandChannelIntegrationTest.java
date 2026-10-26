package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConfiguration;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayConnector;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAccounting;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.testing.WorkerPropertiesFixtures;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import de.tum.cit.aet.hephaestus.core.auth.ratelimit.BucketResolver;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtInvalidException;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtVerifier;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import io.github.bucket4j.Bucket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Heph host's command channel end to end: a real web server with the production gateway connector, the gateway's
 * security chains and the channel's handler. A WebSocket client stands in for the host and dials the gateway as the
 * host does. Only the host's sandbox is a fake.
 */
@Tag("integration")
class HephHostCommandChannelIntegrationTest {

    private static final String TOKEN = "host-session-token";
    private static final Duration READY = Duration.ofSeconds(30);

    /** One client for the whole test: a client that is no longer referenced closes its connections. */
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** Starts hosts that run until the test ends; the test opens their channel itself. */
    static final class FakeSandbox implements HostSandbox {
        final List<Host> launched = new CopyOnWriteArrayList<>();

        @Override
        public Host launch(UUID id, HostSpec spec) {
            Host host = new Host(id);
            launched.add(host);
            return host;
        }

        @Override
        public boolean isRunning(Host host) {
            return true;
        }

        @Override
        public void remove(Host host) {}
    }

    @TempDir
    private Path workspace;

    @Test
    @Timeout(120)
    void shouldCarryCommandsToTheHostWhenItDialsTheGatewayWithItsSessionToken() throws Exception {
        var application = new SpringApplication(
                ChannelApplication.class,
                Class.forName("de.tum.cit.aet.hephaestus.agent.proxy.LlmProxySecurityConfig"));
        try (ConfigurableApplicationContext context = application.run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                // The test JVM turns the worker role off; the gateway is a worker capability.
                "--" + RuntimeRole.WORKER_PROPERTY + "=true",
                "--hephaestus.sandbox.gateway.port=0")) {
            int gatewayPort = context.getBean(SandboxGatewayConnector.class).port();
            HephHost host = context.getBean(HephHost.class);
            FakeSandbox sandbox = context.getBean(FakeSandbox.class);
            CompletableFuture<Boolean> ready = CompletableFuture.supplyAsync(() -> host.awaitReady(READY));
            await().atMost(READY).until(() -> !sandbox.launched.isEmpty());
            UUID hostId = sandbox.launched.getLast().id();
            context.getBean(SandboxGatewaySessions.class)
                    .registerHost(hostId, TOKEN, Files.createFile(workspace.resolve("input.tar")), "/out");
            String commands = "/internal/llm/runtime/" + hostId + "/commands";
            assertThat(refusedWith(gatewayPort, commands, "Bearer wrong")).isEqualTo(401);

            var received = new CompletableFuture<String>();
            WebSocket channel = dial(gatewayPort, commands, "Bearer " + TOKEN, new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket socket) {
                            socket.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                            received.complete(data.toString());
                            return CompletableFuture.completedFuture(null);
                        }
                    })
                    .get(10, TimeUnit.SECONDS);
            try {
                assertThat(ready.get(READY.toSeconds(), TimeUnit.SECONDS)).isTrue();
                host.send("{\"type\":\"stop\"}");
                assertThat(received.get(10, TimeUnit.SECONDS)).isEqualTo("{\"type\":\"stop\"}");
            } finally {
                channel.sendClose(WebSocket.NORMAL_CLOSURE, "test done").get(5, TimeUnit.SECONDS);
            }
            await().atMost(READY).until(() -> !host.isReady());
        }
    }

    private static CompletableFuture<WebSocket> dial(
            int port, String path, String authorization, WebSocket.Listener listener) {
        return CLIENT.newWebSocketBuilder()
                .header("Authorization", authorization)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + path), listener);
    }

    /** The status the handshake was refused with. */
    private static int refusedWith(int port, String path, String authorization) {
        Throwable refused = catchThrowable(() ->
                dial(port, path, authorization, new WebSocket.Listener() {}).get(10, TimeUnit.SECONDS));
        assertThat(refused).hasCauseInstanceOf(WebSocketHandshakeException.class);
        return ((WebSocketHandshakeException) Objects.requireNonNull(refused.getCause()))
                .getResponse()
                .statusCode();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
    })
    @Import({SandboxGatewayConfiguration.class, HephHostChannelConfiguration.class})
    static class ChannelApplication {

        @Bean
        SandboxGatewaySessions sandboxGatewaySessions() {
            return new SandboxGatewaySessions();
        }

        @Bean
        FakeSandbox fakeSandbox() {
            return new FakeSandbox();
        }

        @Bean
        HephHost hephHost(FakeSandbox sandbox) {
            return new HephHost(sandbox);
        }

        @Bean
        BucketResolver bucketResolver() {
            return (key, configuration) ->
                    Bucket.builder().addLimit(configuration.getBandwidths()[0]).build();
        }

        @Bean
        ReviewJobRepository reviewJobRepository() {
            return mock(ReviewJobRepository.class);
        }

        @Bean
        MentorTurnRepository mentorTurnRepository() {
            return mock(MentorTurnRepository.class);
        }

        /** A host session token is not a signed worker token, so the verifier refuses it. */
        @Bean
        WorkerJwtVerifier workerJwtVerifier() {
            WorkerJwtVerifier verifier = mock(WorkerJwtVerifier.class);
            when(verifier.verify(anyString())).thenThrow(new WorkerJwtInvalidException("unsigned", "malformed"));
            return verifier;
        }

        @Bean
        ProxyAccounting proxyAccounting() {
            return mock(ProxyAccounting.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        WorkerProperties workerProperties() {
            return WorkerPropertiesFixtures.minimal("1", "4");
        }
    }
}
