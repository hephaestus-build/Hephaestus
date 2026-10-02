package de.tum.cit.aet.hephaestus.agent.sandbox.remote;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.*;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.core.runtime.hub.*;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.*;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class WorkerInteractiveSandboxServiceTest extends BaseUnitTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MentorProxyCredentialRegistry credentials = new MentorProxyCredentialRegistry();
    private final WorkerSession worker = mock(WorkerSession.class);
    private final WorkerSessionRegistry registry = mock(WorkerSessionRegistry.class);
    private final List<MentorSessionCommand> sent = new CopyOnWriteArrayList<>();
    private WorkerInteractiveSandboxService service;

    @BeforeEach
    void setup() {
        when(worker.isOpen()).thenReturn(true);
        when(worker.workerId()).thenReturn("worker-1");
        when(worker.sessionId()).thenReturn("connection-1");
        when(worker.lastCapacity()).thenReturn(new CapacityReport(1, 1, 0, 0, 1, 1));
        when(registry.sessions()).thenReturn(List.of(worker));
        service = new WorkerInteractiveSandboxService(
                registry,
                credentials,
                mapper,
                Binder.get(new MockEnvironment().withProperty("hephaestus.mentor.stdin-write-timeout-ms", "100"))
                        .bindOrCreate("hephaestus.mentor", InteractiveSandboxProperties.class),
                mock(WorkerControlWebSocketHandler.class),
                new SimpleMeterRegistry());
        doAnswer(invocation -> {
                    var command = (MentorSessionCommand) invocation.getArgument(0);
                    sent.add(command);
                    service.receive(new WorkerMentorSessionEvent(
                            worker,
                            new MentorSessionEvent(
                                    command.sessionId(),
                                    command.requestId(),
                                    MentorSessionEvent.Kind.ACK,
                                    mapper.createObjectNode().put("frameByteBudget", 1024 * 1024))));
                    return true;
                })
                .when(worker)
                .send(any());
    }

    private InteractiveSandboxSpec spec(String developer) {
        UUID id = UUID.randomUUID();
        String token = credentials.mint(
                id,
                new MentorProxyCredentialRegistry.Route(
                        "openai-completions", "https://example.test/v1", FundingSource.INSTANCE, 1L, 2L, 3L));
        return new InteractiveSandboxSpec(
                id,
                developer,
                "3",
                "image@sha256:abc",
                List.of("runner"),
                Map.of(),
                new NetworkPolicy(false, "http://{appServerIp}:8081/internal/llm", token),
                ResourceLimits.DEFAULT,
                SecurityProfile.DEFAULT,
                Map.of());
    }

    @Test
    void reusesItsWorkerWithoutConsumingAnotherSlot() {
        var first = spec("1");
        var handle = service.attach(first);
        assertThat(service.attach(spec("1"))).isSameAs(handle);
        assertThat(service.attach(first)).isSameAs(handle);
        assertThat(sent)
                .filteredOn(c -> c.operation() == MentorSessionCommand.Operation.OPEN)
                .hasSize(1);
        assertThat(credentials.route(first.sessionId())).isPresent();
        assertThatThrownBy(() -> service.attach(spec("2"))).isInstanceOf(MentorBusyException.class);
    }

    @Test
    void noCapacityHasNoFallbackAndRevokesTheUnusedCredential() {
        when(worker.lastCapacity()).thenReturn(new CapacityReport(1, 1, 0, 1, 1, 0));
        var request = spec("1");
        assertThatThrownBy(() -> service.attach(request)).isInstanceOf(MentorBusyException.class);
        assertThat(credentials.route(request.sessionId())).isEmpty();
        assertThat(sent).isEmpty();
    }

    @Test
    void rejectsFramesFromAnotherAuthenticatedConnection() {
        var handle = service.attach(spec("1"));
        var received = new CopyOnWriteArrayList<String>();
        var subscription =
                handle.subscribeFromNow(frame -> received.add(frame.path("text").asString()), () -> {});
        var payload = mapper.createObjectNode().put("text", "right worker");
        service.receive(new WorkerMentorSessionEvent(
                mock(WorkerSession.class),
                new MentorSessionEvent(handle.identity().sessionId(), null, MentorSessionEvent.Kind.FRAME, payload)));
        assertThat(received).isEmpty();
        service.receive(new WorkerMentorSessionEvent(
                worker,
                new MentorSessionEvent(handle.identity().sessionId(), null, MentorSessionEvent.Kind.FRAME, payload)));
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(received).containsExactly("right worker"));
        subscription.dispose();
    }

    @Test
    void disconnectEndsSubscribersAndTheNextAttachUsesANewWorker() {
        var handle = service.attach(spec("1"));
        var lost = new AtomicBoolean();
        handle.subscribeFromNow(frame -> {}, () -> lost.set(true));
        service.disconnected(new WorkerDisconnectedEvent("worker-1", "stale-connection", "closed", Instant.now()));
        assertThat(lost).isFalse();
        service.disconnected(new WorkerDisconnectedEvent("worker-1", "connection-1", "closed", Instant.now()));
        assertThat(lost).isTrue();
        var replacement = mock(WorkerSession.class);
        when(replacement.isOpen()).thenReturn(true);
        when(replacement.lastCapacity()).thenReturn(new CapacityReport(1, 1, 0, 0, 1, 1));
        when(registry.sessions()).thenReturn(List.of(replacement));
        when(replacement.send(any())).thenAnswer(invocation -> {
            var command = (MentorSessionCommand) invocation.getArgument(0);
            service.receive(new WorkerMentorSessionEvent(
                    replacement,
                    new MentorSessionEvent(
                            command.sessionId(),
                            command.requestId(),
                            MentorSessionEvent.Kind.ACK,
                            mapper.createObjectNode().put("frameByteBudget", 1024 * 1024))));
            return true;
        });
        assertThat(service.attach(spec("1")).identity().sessionId())
                .isNotEqualTo(handle.identity().sessionId());
    }

    @Test
    void workerCloseDeliversQueuedFramesBeforeReportingLoss() throws Exception {
        var handle = service.attach(spec("1"));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var received = new CopyOnWriteArrayList<String>();
        var lost = new AtomicBoolean();
        var subscription = handle.subscribeFromNow(
                frame -> {
                    entered.countDown();
                    try {
                        release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    received.add(frame.path("text").asString());
                },
                () -> lost.set(true));
        try {
            service.receive(new WorkerMentorSessionEvent(
                    worker,
                    new MentorSessionEvent(
                            handle.identity().sessionId(),
                            null,
                            MentorSessionEvent.Kind.FRAME,
                            mapper.createObjectNode().put("text", "first"))));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            service.receive(new WorkerMentorSessionEvent(
                    worker,
                    new MentorSessionEvent(
                            handle.identity().sessionId(),
                            null,
                            MentorSessionEvent.Kind.FRAME,
                            mapper.createObjectNode().put("text", "terminal"))));
            service.receive(new WorkerMentorSessionEvent(
                    worker,
                    new MentorSessionEvent(
                            handle.identity().sessionId(),
                            null,
                            MentorSessionEvent.Kind.CLOSED,
                            mapper.createObjectNode())));
            assertThat(lost).isFalse();
            release.countDown();
            await().atMost(Duration.ofSeconds(2)).untilTrue(lost);
            assertThat(received).containsExactly("first", "terminal");
        } finally {
            release.countDown();
            subscription.dispose();
        }
    }

    @Test
    void aMissingCommandAcknowledgementRetiresTheSession() {
        var handle = service.attach(spec("1"));
        var lost = new AtomicBoolean();
        handle.subscribeFromNow(frame -> {}, () -> lost.set(true));
        doAnswer(invocation -> {
                    var command = (MentorSessionCommand) invocation.getArgument(0);
                    sent.add(command);
                    if (command.operation() != MentorSessionCommand.Operation.SEND) {
                        service.receive(new WorkerMentorSessionEvent(
                                worker,
                                new MentorSessionEvent(
                                        command.sessionId(),
                                        command.requestId(),
                                        MentorSessionEvent.Kind.ACK,
                                        mapper.createObjectNode().put("frameByteBudget", 1024 * 1024))));
                    }
                    return true;
                })
                .when(worker)
                .send(any());
        assertThatThrownBy(() -> handle.send(mapper.createObjectNode().put("id", 1)))
                .isInstanceOf(InteractiveSandboxException.class)
                .hasMessageContaining("timed out");
        assertThat(lost).isTrue();
        assertThat(credentials.route(handle.identity().sessionId())).isEmpty();
        assertThat(service.attach(spec("1")).identity().sessionId())
                .isNotEqualTo(handle.identity().sessionId());
    }

    @Test
    void workerBusyIsPreservedAsARetryableFailure() {
        doAnswer(invocation -> {
                    var command = (MentorSessionCommand) invocation.getArgument(0);
                    service.receive(new WorkerMentorSessionEvent(
                            worker,
                            new MentorSessionEvent(
                                    command.sessionId(),
                                    command.requestId(),
                                    MentorSessionEvent.Kind.BUSY,
                                    mapper.createObjectNode().put("frameByteBudget", 1024 * 1024))));
                    return true;
                })
                .when(worker)
                .send(any());
        assertThatThrownBy(() -> service.attach(spec("1"))).isInstanceOf(MentorBusyException.class);
    }

    @Test
    void aFullSubscriberQueueDoesNotBlockAnotherSessionAcknowledgement() throws Exception {
        when(worker.lastCapacity()).thenReturn(new CapacityReport(1, 2, 0, 0, 1, 2));
        service = new WorkerInteractiveSandboxService(
                registry,
                credentials,
                mapper,
                Binder.get(new MockEnvironment().withProperty("hephaestus.mentor.subscriber-queue-capacity", "1"))
                        .bindOrCreate("hephaestus.mentor", InteractiveSandboxProperties.class),
                mock(WorkerControlWebSocketHandler.class),
                new SimpleMeterRegistry());
        var stalled = service.attach(spec("1"));
        var healthy = service.attach(spec("2"));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CompletableFuture<Void>();
        var lost = new AtomicBoolean();
        var subscription = stalled.subscribeFromNow(
                frame -> {
                    entered.countDown();
                    release.join();
                },
                () -> lost.set(true));
        var frame = new WorkerMentorSessionEvent(
                worker,
                new MentorSessionEvent(
                        stalled.identity().sessionId(),
                        null,
                        MentorSessionEvent.Kind.FRAME,
                        mapper.createObjectNode().put("text", "delta")));
        try {
            service.receive(frame);
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            service.receive(frame);
            var dispatched = java.util.concurrent.CompletableFuture.runAsync(() -> {
                service.receive(frame);
                healthy.send(mapper.createObjectNode().put("method", "hello"));
            });
            dispatched.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(lost).isTrue();
            assertThat(sent).anySatisfy(command -> {
                assertThat(command.sessionId()).isEqualTo(healthy.identity().sessionId());
                assertThat(command.operation()).isEqualTo(MentorSessionCommand.Operation.SEND);
            });
        } finally {
            release.complete(null);
            subscription.dispose();
            stalled.close(Duration.ZERO);
            healthy.close(Duration.ZERO);
        }
    }

    @Test
    void retiringOneWorkerDoesNotHoldThePlacementLockForAnotherWorker() throws Exception {
        var original = service.attach(spec("1"));
        var replacementWorker = mock(WorkerSession.class);
        when(replacementWorker.isOpen()).thenReturn(true);
        when(replacementWorker.lastCapacity()).thenReturn(new CapacityReport(1, 1, 0, 0, 1, 1));
        when(registry.sessions()).thenReturn(List.of(worker, replacementWorker));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CompletableFuture<Void>();
        doAnswer(invocation -> {
                    var command = (MentorSessionCommand) invocation.getArgument(0);
                    if (command.operation() == MentorSessionCommand.Operation.CLOSE
                            && command.sessionId().equals(original.identity().sessionId())) {
                        entered.countDown();
                        release.join();
                    }
                    service.receive(new WorkerMentorSessionEvent(
                            worker,
                            new MentorSessionEvent(
                                    command.sessionId(),
                                    command.requestId(),
                                    MentorSessionEvent.Kind.ACK,
                                    mapper.createObjectNode().put("frameByteBudget", 1024 * 1024))));
                    return true;
                })
                .when(worker)
                .send(any());
        doAnswer(invocation -> {
                    var command = (MentorSessionCommand) invocation.getArgument(0);
                    service.receive(new WorkerMentorSessionEvent(
                            replacementWorker,
                            new MentorSessionEvent(
                                    command.sessionId(),
                                    command.requestId(),
                                    MentorSessionEvent.Kind.ACK,
                                    mapper.createObjectNode().put("frameByteBudget", 1024 * 1024))));
                    return true;
                })
                .when(replacementWorker)
                .send(any());
        var requested = spec("1");
        var changed = new InteractiveSandboxSpec(
                requested.sessionId(),
                requested.userId(),
                requested.workspaceId(),
                "image@sha256:changed",
                requested.command(),
                requested.environment(),
                requested.networkPolicy(),
                requested.resourceLimits(),
                requested.securityProfile(),
                requested.inputFiles());
        var replacing = java.util.concurrent.CompletableFuture.supplyAsync(() -> service.attach(changed));
        java.util.concurrent.CompletableFuture<AttachedSandbox> other = null;
        try {
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            other = java.util.concurrent.CompletableFuture.supplyAsync(() -> service.attach(spec("2")));
            assertThat(other.get(2, java.util.concurrent.TimeUnit.SECONDS)
                            .identity()
                            .userId())
                    .isEqualTo("2");
        } finally {
            release.complete(null);
            replacing.get(5, java.util.concurrent.TimeUnit.SECONDS).close(Duration.ZERO);
            if (other != null)
                other.get(5, java.util.concurrent.TimeUnit.SECONDS).close(Duration.ZERO);
            original.close(Duration.ZERO);
        }
    }
}
