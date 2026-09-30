package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.*;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.*;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class WorkerMentorSessionsTest extends BaseUnitTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkerControlClient client = mock(WorkerControlClient.class);
    private final InteractiveSandboxService adapter = mock(InteractiveSandboxService.class);
    private final MentorProxyCredentialRegistry credentials = new MentorProxyCredentialRegistry();
    private final List<MentorSessionEvent> replies = new CopyOnWriteArrayList<>();
    private final AttachedSandbox attached = mock(AttachedSandbox.class);
    private WorkerCapacityState capacity;
    private WorkerMentorSessions sessions;
    private InteractiveSandboxSpec spec;

    @BeforeEach
    void setup() {
        var environment = new MockEnvironment().withProperty("hephaestus.worker.capacity.mentor-max", "1");
        capacity = new WorkerCapacityState(
                Binder.get(environment).bindOrCreate("hephaestus.worker", WorkerProperties.class));
        sessions = new WorkerMentorSessions(
                client,
                capacity,
                adapter,
                credentials,
                mapper,
                Binder.get(environment).bindOrCreate("hephaestus.mentor", InteractiveSandboxProperties.class));
        spec = new InteractiveSandboxSpec(
                UUID.randomUUID(),
                "1",
                "1",
                "runner",
                List.of("runner"),
                Map.of(),
                new NetworkPolicy(false, "http://gateway/internal/llm", "scoped-token"),
                ResourceLimits.DEFAULT,
                SecurityProfile.DEFAULT,
                Map.of());
        when(attached.identity()).thenReturn(new SandboxIdentity(spec.sessionId(), "1", "1"));
        when(adapter.attach(any())).thenReturn(attached);
        when(attached.subscribeWithReplay(any(), any())).thenReturn(() -> {});
        when(client.sendRequired(any())).thenAnswer(invocation -> {
            replies.add(invocation.getArgument(0));
            return true;
        });
    }

    @AfterEach
    void shutdown() {
        sessions.stop();
    }

    private MentorSessionCommand open(UUID sessionId) {
        var body = mapper.createObjectNode();
        body.set("spec", mapper.valueToTree(spec));
        body.set(
                "route",
                mapper.valueToTree(new MentorProxyCredentialRegistry.Route(
                        "openai-completions", "https://upstream.test", FundingSource.INSTANCE, 1L, 1L, 1L)));
        return new MentorSessionCommand(sessionId, UUID.randomUUID(), MentorSessionCommand.Operation.OPEN, body);
    }

    @Test
    void admitsOnceAndReleasesCapacityAndCredentialsOnDrain() {
        var request = open(spec.sessionId());
        sessions.handle(request);
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(replies).anySatisfy(event -> {
                    assertThat(event.requestId()).isEqualTo(request.requestId());
                    assertThat(event.kind()).isEqualTo(MentorSessionEvent.Kind.ACK);
                }));
        assertThat(capacity.snapshot().inFlightMentor()).isEqualTo(1);
        assertThat(credentials.validate("scoped-token")).isPresent();
        sessions.stop();
        assertThat(capacity.snapshot().inFlightMentor()).isZero();
        assertThat(credentials.validate("scoped-token")).isEmpty();
        verify(attached).close(Duration.ZERO);
        assertThat(replies).anySatisfy(event -> assertThat(event.kind()).isEqualTo(MentorSessionEvent.Kind.CLOSED));
        replies.clear();
        sessions.handle(open(UUID.randomUUID()));
        assertThat(replies.getFirst().kind()).isEqualTo(MentorSessionEvent.Kind.BUSY);
    }

    @Test
    void aCloseFailureStillRevokesCredentialsAndReleasesCapacity() {
        sessions.handle(open(spec.sessionId()));
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(replies)
                        .anySatisfy(event -> assertThat(event.kind()).isEqualTo(MentorSessionEvent.Kind.ACK)));
        doThrow(new InteractiveSandboxException("close failed")).when(attached).close(Duration.ZERO);
        assertThatCode(sessions::stop).doesNotThrowAnyException();
        assertThat(capacity.snapshot().inFlightMentor()).isZero();
        assertThat(credentials.validate("scoped-token")).isEmpty();
        assertThat(replies).anySatisfy(event -> assertThat(event.kind()).isEqualTo(MentorSessionEvent.Kind.CLOSED));
    }

    @Test
    void reservesCapacityWhileAttachmentIsStillStarting() {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(adapter.attach(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(3, java.util.concurrent.TimeUnit.SECONDS);
            return attached;
        });
        sessions.handle(open(spec.sessionId()));
        await().atMost(Duration.ofSeconds(2)).until(() -> entered.getCount() == 0);
        sessions.handle(open(UUID.randomUUID()));
        assertThat(replies).anySatisfy(event -> assertThat(event.kind()).isEqualTo(MentorSessionEvent.Kind.BUSY));
        sessions.stop();
        release.countDown();
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(
                        () -> assertThat(credentials.validate("scoped-token")).isEmpty());
        assertThat(capacity.snapshot().inFlightMentor()).isZero();
    }

    @Test
    void aFailedAttachmentDoesNotConsumeCapacityOrLeaveCredentials() {
        when(adapter.attach(any())).thenThrow(new InteractiveSandboxException("startup failed"));
        sessions.handle(open(spec.sessionId()));
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(replies)
                        .anySatisfy(event -> assertThat(event.kind()).isEqualTo(MentorSessionEvent.Kind.CLOSED)));
        assertThat(capacity.snapshot().inFlightMentor()).isZero();
        assertThat(credentials.validate("scoped-token")).isEmpty();
    }
}
