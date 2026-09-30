package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry;
import de.tum.cit.aet.hephaestus.agent.proxy.MentorTurnMeter;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxService;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.MentorBusyException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.MentorSessionCommand;
import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.MentorSessionEvent;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Worker owns admission, Docker attachment and all terminal paths. A hub reconnect retires its sessions. */
public final class WorkerMentorSessions {
    private static final Logger log = LoggerFactory.getLogger(WorkerMentorSessions.class);
    private final WorkerControlClient client;
    private final WorkerCapacityState capacity;
    private final InteractiveSandboxService sandbox;
    private final MentorProxyCredentialRegistry credentials;
    private final ObjectMapper mapper;
    private final InteractiveSandboxProperties properties;
    private final ThreadPoolExecutor startup;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public WorkerMentorSessions(
            WorkerControlClient client,
            WorkerCapacityState capacity,
            InteractiveSandboxService sandbox,
            MentorProxyCredentialRegistry credentials,
            ObjectMapper mapper,
            InteractiveSandboxProperties properties) {
        this.client = client;
        this.capacity = capacity;
        this.sandbox = sandbox;
        this.credentials = credentials;
        this.mapper = mapper;
        this.properties = properties;
        int threads = Math.max(1, Math.min(4, capacity.mentorMax()));
        startup = new ThreadPoolExecutor(
                threads,
                threads,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, capacity.mentorMax())),
                Thread.ofPlatform().name("worker-mentor-start-", 0).daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        client.setMentorHandlers(this::handle, this::disconnected);
    }

    public void handle(MentorSessionCommand command) {
        if (command.operation() == MentorSessionCommand.Operation.OPEN) {
            open(command);
            return;
        }
        Session session = sessions.get(command.sessionId());
        if (session == null) {
            reply(command, MentorSessionEvent.Kind.CLOSED);
            return;
        }
        try {
            switch (command.operation()) {
                case SEND -> {
                    AttachedSandbox attached = session.attached;
                    if (attached == null
                            || mapper.writeValueAsBytes(command.body()).length > properties.maxFrameChars()) {
                        throw new InteractiveSandboxException("Session unavailable or frame too large");
                    }
                    attached.send(command.body());
                }
                case BIND_TURN -> {
                    UUID turnId = UUID.fromString(command.body().path("turnId").asString());
                    JsonNode priceNode = command.body().path("price");
                    var price = priceNode.isNull() ? null : mapper.treeToValue(priceNode, LlmPriceSnapshot.class);
                    var meter = new MentorTurnMeter(turnId, price);
                    if (!credentials.bindTurn(command.sessionId(), meter))
                        throw new InteractiveSandboxException("Expired credential");
                    session.meter = meter;
                }
                case UNBIND_TURN -> {
                    var meter = session.meter;
                    if (meter != null
                            && meter.turnId()
                                    .toString()
                                    .equals(command.body().path("turnId").asString())) {
                        credentials.unbindTurn(command.sessionId(), meter);
                        session.meter = null;
                    }
                }
                case CLOSE ->
                    retire(
                            command.sessionId(),
                            session,
                            Duration.ofMillis(Math.clamp(
                                    command.body().path("graceMillis").asLong(0),
                                    0,
                                    properties.graceTimeoutSeconds() * 1000L)));
                case OPEN -> throw new IllegalStateException("Open dispatched twice");
            }
            reply(command, MentorSessionEvent.Kind.ACK);
        } catch (RuntimeException failure) {
            reply(command, MentorSessionEvent.Kind.FAILED);
            retire(command.sessionId(), session);
        }
    }

    private void open(MentorSessionCommand command) {
        synchronized (sessions) {
            if (!running || sessions.containsKey(command.sessionId()) || !capacity.tryClaimMentor()) {
                reply(command, MentorSessionEvent.Kind.BUSY);
                return;
            }
            Session session = new Session();
            sessions.put(command.sessionId(), session);
            try {
                startup.execute(() -> attach(command, session));
            } catch (java.util.concurrent.RejectedExecutionException full) {
                retire(command.sessionId(), session);
                reply(command, MentorSessionEvent.Kind.BUSY);
            }
        }
    }

    private void attach(MentorSessionCommand command, Session session) {
        try {
            var spec = mapper.treeToValue(command.body().path("spec"), InteractiveSandboxSpec.class);
            if (!spec.sessionId().equals(command.sessionId()) || spec.networkPolicy() == null) {
                throw new InteractiveSandboxException("Session identity or routing missing");
            }
            var route = mapper.treeToValue(command.body().path("route"), MentorProxyCredentialRegistry.Route.class);
            String token = spec.networkPolicy().llmProxyToken();
            if (token == null
                    || route.workspaceId() == null
                    || !spec.workspaceId().equals(route.workspaceId().toString())) {
                throw new InteractiveSandboxException("Session workspace or credential missing");
            }
            credentials.install(spec.sessionId(), token, route);
            AttachedSandbox attached = sandbox.attach(spec);
            // Placement must not accidentally adopt a sandbox left by a previous hub connection.
            if (!attached.identity().sessionId().equals(command.sessionId())) {
                throw new InteractiveSandboxException("Session identity changed");
            }
            synchronized (session) {
                if (session.closed.get()) {
                    attached.close(Duration.ZERO);
                    return;
                }
                session.attached = attached;
                session.subscription = attached.subscribeWithReplay(
                        frame -> {
                            if (!client.sendRequired(new MentorSessionEvent(
                                    command.sessionId(), null, MentorSessionEvent.Kind.FRAME, frame))) {
                                retire(command.sessionId(), session);
                            }
                        },
                        () -> retire(command.sessionId(), session));
            }
            reply(command, MentorSessionEvent.Kind.ACK);
        } catch (RuntimeException failure) {
            reply(
                    command,
                    failure instanceof MentorBusyException
                            ? MentorSessionEvent.Kind.BUSY
                            : MentorSessionEvent.Kind.FAILED);
            retire(command.sessionId(), session);
        } finally {
            if (session.closed.get()) credentials.revoke(command.sessionId());
        }
    }

    private void reply(MentorSessionCommand command, MentorSessionEvent.Kind kind) {
        var body = mapper.createObjectNode();
        if (command.operation() == MentorSessionCommand.Operation.OPEN && kind == MentorSessionEvent.Kind.ACK) {
            body.put("frameByteBudget", properties.maxFrameChars());
        }
        if (!client.sendRequired(new MentorSessionEvent(command.sessionId(), command.requestId(), kind, body))) {
            Session session = sessions.get(command.sessionId());
            if (session != null) retire(command.sessionId(), session);
        }
    }

    private void retire(UUID id, Session session) {
        retire(id, session, Duration.ZERO);
    }

    private void retire(UUID id, Session session, Duration grace) {
        AttachedSandbox attached;
        Disposable subscription;
        synchronized (session) {
            if (!session.closed.compareAndSet(false, true)) return;
            sessions.remove(id, session);
            capacity.releaseMentor();
            credentials.revoke(id);
            attached = session.attached;
            subscription = session.subscription;
        }
        client.sendRequired(
                new MentorSessionEvent(id, null, MentorSessionEvent.Kind.CLOSED, mapper.createObjectNode()));
        if (subscription != null) subscription.dispose();
        if (attached != null) {
            try {
                attached.close(grace);
            } catch (RuntimeException failure) {
                log.warn(
                        "Worker mentor close failed for {}: {}",
                        id,
                        failure.getClass().getSimpleName());
            }
        }
    }

    private void disconnected() {
        // Docker close already has a bounded wait. Run those waits together, outside session locks,
        // so a full worker drains in one close budget rather than one budget per live session.
        try (var cleanup = Executors.newVirtualThreadPerTaskExecutor()) {
            sessions.forEach((id, session) -> cleanup.submit(() -> retire(id, session)));
        }
    }

    @PreDestroy
    public void stop() {
        synchronized (sessions) {
            running = false;
        }
        disconnected();
        startup.shutdownNow();
    }

    private static final class Session {
        final AtomicBoolean closed = new AtomicBoolean();
        volatile @Nullable AttachedSandbox attached;
        volatile @Nullable Disposable subscription;
        volatile @Nullable MentorTurnMeter meter;
    }
}
