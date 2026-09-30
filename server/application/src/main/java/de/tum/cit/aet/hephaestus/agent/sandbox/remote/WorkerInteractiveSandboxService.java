package de.tum.cit.aet.hephaestus.agent.sandbox.remote;

import de.tum.cit.aet.hephaestus.agent.proxy.MentorProxyCredentialRegistry;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxRuntimeKey;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxService;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.InteractiveSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.MentorBusyException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerControlWebSocketHandler;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerDisconnectedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerMentorSessionEvent;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerSession;
import de.tum.cit.aet.hephaestus.core.runtime.hub.WorkerSessionRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Server-side placement. No local execution path, including when both roles share a JVM. */
@Service
@Primary
@ConditionalOnProperty(name = RuntimeRole.SERVER_PROPERTY, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(InteractiveSandboxProperties.class)
public final class WorkerInteractiveSandboxService implements InteractiveSandboxService {
    private final WorkerSessionRegistry workers;
    private final MentorProxyCredentialRegistry credentials;
    private final ObjectMapper mapper;
    private final InteractiveSandboxProperties properties;
    private final MeterRegistry meters;
    private final Map<Key, RemoteAttachedSandbox> sessions = new ConcurrentHashMap<>();

    public WorkerInteractiveSandboxService(
            WorkerSessionRegistry workers,
            MentorProxyCredentialRegistry credentials,
            ObjectMapper mapper,
            InteractiveSandboxProperties properties,
            WorkerControlWebSocketHandler handler,
            MeterRegistry meters) {
        this.workers = workers;
        this.credentials = credentials;
        this.mapper = mapper;
        this.properties = properties;
        this.meters = meters;
        handler.setMentorHandler(this::receive);
    }

    private InteractiveSandboxRuntimeKey runtimeKey(InteractiveSandboxSpec spec) {
        String token =
                spec.networkPolicy() == null ? null : spec.networkPolicy().llmProxyToken();
        return InteractiveSandboxRuntimeKey.of(
                spec, token == null ? null : credentials.validate(token).orElse(null));
    }

    @Override
    public AttachedSandbox attach(InteractiveSandboxSpec spec) {
        RemoteAttachedSandbox handle;
        boolean created;
        var key = new Key(spec.userId(), spec.workspaceId());
        var runtime = runtimeKey(spec);
        synchronized (sessions) {
            var current = sessions.get(key);
            if (current != null && current.isLive() && current.runtime().equals(runtime)) {
                if (!current.identity().sessionId().equals(spec.sessionId())) credentials.revoke(spec.sessionId());
                handle = current;
                created = false;
            } else {
                if (current != null) current.close(Duration.ZERO);
                WorkerSession selected = workers.sessions().stream()
                        .filter(w -> w.isOpen() && !w.isDraining() && w.lastCapacity() != null)
                        .filter(w -> {
                            var capacity = w.lastCapacity();
                            if (capacity == null) return false;
                            long reserved = sessions.values().stream()
                                    .filter(s -> s.worker() == w && s.isLive())
                                    .count();
                            // Counting all local placements is conservative between capacity reports; the worker
                            // performs the authoritative atomic admission across server replicas.
                            return reserved < capacity.mentorMax() && capacity.spareMentor() > 0;
                        })
                        .findFirst()
                        .orElse(null);
                if (selected == null) {
                    credentials.revoke(spec.sessionId());
                    throw new MentorBusyException();
                }
                handle = new RemoteAttachedSandbox(
                        new SandboxIdentity(spec.sessionId(), spec.userId(), spec.workspaceId()),
                        selected,
                        runtime,
                        mapper,
                        properties,
                        closed -> {
                            sessions.remove(key, closed);
                            credentials.revoke(spec.sessionId());
                        },
                        meters);
                sessions.put(key, handle);
                created = true;
            }
        }
        if (created) {
            try {
                var body = mapper.createObjectNode();
                body.set("spec", mapper.valueToTree(spec));
                body.set(
                        "route",
                        mapper.valueToTree(credentials.route(spec.sessionId()).orElseThrow()));
                handle.open(body);
            } catch (RuntimeException failure) {
                handle.close(Duration.ZERO);
                throw failure;
            }
        } else {
            handle.awaitOpen();
        }
        return handle;
    }

    @Override
    public boolean isWarm(InteractiveSandboxSpec spec) {
        synchronized (sessions) {
            var session = sessions.get(new Key(spec.userId(), spec.workspaceId()));
            return session != null && session.isReady() && session.runtime().equals(runtimeKey(spec));
        }
    }

    @Override
    public boolean isHealthy() {
        return workers.sessions().stream().anyMatch(w -> w.isOpen() && !w.isDraining() && w.lastCapacity() != null);
    }

    public void receive(WorkerMentorSessionEvent event) {
        RemoteAttachedSandbox target = sessions.values().stream()
                .filter(s -> s.worker() == event.worker()
                        && s.identity().sessionId().equals(event.frame().sessionId()))
                .findFirst()
                .orElse(null);
        if (target != null) target.receive(event.frame());
    }

    @EventListener
    public void disconnected(WorkerDisconnectedEvent event) {
        var lost = sessions.values().stream()
                .filter(s -> s.worker().sessionId().equals(event.sessionId())
                        && s.worker().workerId().equals(event.workerId()))
                .toList();
        lost.forEach(s -> s.lost("The worker connection ended. Please try again."));
    }

    private record Key(String userId, String workspaceId) {}
}
