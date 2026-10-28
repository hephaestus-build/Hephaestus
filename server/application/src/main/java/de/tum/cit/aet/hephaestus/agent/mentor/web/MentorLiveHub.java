package de.tum.cit.aet.hephaestus.agent.mentor.web;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorReadDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorTimeline;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * The live streams of developers' Hephaestus conversations on this replica ({@code docs/contributor/unified-pi-runtime.mdx}
 * § Live updates). Changes arrive with ids only, through the replica's one {@link MentorLedgerNotifications}
 * connection. One reader thread loads each change once and hands the same event to every stream of its developer in
 * the order it read them, so a stream never receives an older state after a newer one. A stream that falls too far
 * behind, or missed notifications, gets a new snapshot instead.
 */
@Component
@ConditionalOnServerRole
public class MentorLiveHub implements MentorLedgerNotifications.Listener, SmartLifecycle {

    static final String SNAPSHOT = "snapshot";
    static final String TURN = "turn";
    static final String ENTRY = "entry";
    static final String READ = "read";

    private static final Logger log = LoggerFactory.getLogger(MentorLiveHub.class);

    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final long HEARTBEAT_INTERVAL_MS = 20_000L;

    /** Limits the streams one developer holds open; CORS does not prevent opening a stream. */
    public static final int MAX_STREAMS_PER_DEVELOPER = 10;

    private static final int QUEUE_CAPACITY = 256;

    private final MentorLedgerNotifications notifications;
    private final MentorTimeline timeline;
    private final AccountMembershipQuery memberships;
    private final ObjectMapper mapper;
    private final Supplier<SseEmitter> emitters;
    private final int queueCapacity;

    /** Each stream under its account, which owns the conversation. */
    private final Map<Developer, Set<Stream>> streams = new ConcurrentHashMap<>();

    /** Set when the context closes; guarded by {@code streams}. */
    private boolean closing;

    private volatile boolean running;

    private final ExecutorService reader = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("mentor-live-reader").factory());
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean listening = new AtomicBoolean();

    @Autowired
    public MentorLiveHub(
            MentorLedgerNotifications notifications,
            MentorTimeline timeline,
            AccountMembershipQuery memberships,
            ObjectMapper mapper,
            MeterRegistry meters) {
        this(notifications, timeline, memberships, mapper, meters, () -> new SseEmitter(TIMEOUT_MS), QUEUE_CAPACITY);
    }

    MentorLiveHub(
            MentorLedgerNotifications notifications,
            MentorTimeline timeline,
            AccountMembershipQuery memberships,
            ObjectMapper mapper,
            MeterRegistry meters,
            Supplier<SseEmitter> emitters,
            int queueCapacity) {
        this.notifications = notifications;
        this.timeline = timeline;
        this.memberships = memberships;
        this.mapper = mapper;
        this.emitters = emitters;
        this.queueCapacity = queueCapacity;
        Gauge.builder(AgentMetrics.MENTOR_LIVE_STREAMS, streams, MentorLiveHub::count)
                .description("Live Hephaestus streams open on this server")
                .register(meters);
    }

    private record Developer(long workspaceId, long accountId) {}

    /** One server-sent event, already written. */
    private record Event(Set<ResponseBodyEmitter.DataWithMediaType> data) {}

    /**
     * Opens a stream of the account's conversation in a workspace.
     *
     * @param accountId the account that opens the stream, whose membership every delivery checks again
     */
    public SseEmitter subscribe(long workspaceId, long accountId) {
        if (listening.compareAndSet(false, true)) {
            notifications.subscribe(this);
        }
        SseEmitter emitter = emitters.get();
        Stream stream = new Stream(workspaceId, accountId, emitter);
        emitter.onCompletion(() -> remove(stream));
        emitter.onTimeout(() -> close(stream));
        emitter.onError(failure -> remove(stream));
        synchronized (streams) {
            if (closing) {
                // A stream opened now would hold the web server's graceful shutdown; the client retries the next
                // instance.
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The server is restarting.");
            }
            Set<Stream> own = streams.computeIfAbsent(
                    new Developer(workspaceId, accountId), key -> ConcurrentHashMap.newKeySet());
            if (own.size() >= MAX_STREAMS_PER_DEVELOPER) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too many live Hephaestus streams are open. Close one and try again.");
            }
            own.add(stream);
        }
        read(() -> snapshot(stream));
        return emitter;
    }

    @Override
    public void turnChanged(MentorLedgerEvents.TurnChanged change) {
        if (watches(change.workspaceId())) {
            read(() -> timeline.liveTurn(change.workspaceId(), change.turnId())
                    .ifPresent(turn -> fanOut(change.workspaceId(), turn.accountId(), TURN, turn.value())));
        }
    }

    @Override
    public void entryChanged(MentorLedgerEvents.EntryChanged change) {
        if (watches(change.workspaceId())) {
            change.entryIds()
                    .forEach(entryId -> read(() -> timeline.liveEntry(change.workspaceId(), entryId)
                            .ifPresent(
                                    entry -> fanOut(change.workspaceId(), entry.accountId(), ENTRY, entry.value()))));
        }
    }

    /** Another device of the developer moved the read marker. */
    @Override
    public void readChanged(MentorLedgerEvents.ReadChanged change) {
        if (watches(change.workspaceId())) {
            read(() -> fanOut(change.workspaceId(), change.accountId(), READ, new MentorReadDTO(change.lastReadAt())));
        }
    }

    /** Changes made while the notification connection was down were missed: every stream starts over. */
    @Override
    public void resync() {
        allStreams().forEach(stream -> read(() -> snapshot(stream)));
    }

    /** Keeps quiet streams open through proxies' idle timeouts, and closes those whose account left the workspace. */
    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS, initialDelay = HEARTBEAT_INTERVAL_MS)
    @WorkspaceAgnostic("Writes transport heartbeats to streams whose account is still a member")
    void heartbeat() {
        Event ping = new Event(SseEmitter.event().comment("ping").build());
        allStreams()
                .forEach(stream -> read(() -> {
                    if (admitted(stream)) {
                        send(stream, ping);
                    }
                }));
    }

    private boolean watches(long workspaceId) {
        return streams.keySet().stream().anyMatch(developer -> developer.workspaceId() == workspaceId);
    }

    private List<Stream> allStreams() {
        return streams.values().stream().flatMap(Set::stream).distinct().toList();
    }

    private static double count(Map<Developer, Set<Stream>> streams) {
        return streams.values().stream().flatMap(Set::stream).distinct().count();
    }

    private void read(Runnable read) {
        try {
            reader.execute(() -> {
                try {
                    read.run();
                } catch (RuntimeException e) {
                    log.warn("Could not read a Hephaestus conversation change for its live streams", e);
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            // The replica stops; its streams close with it.
        }
    }

    private void snapshot(Stream stream) {
        if (!stream.closed.get() && admitted(stream)) {
            send(stream, event(SNAPSHOT, timeline.conversation(stream.workspaceId, stream.accountId)));
        }
    }

    private void fanOut(long workspaceId, long accountId, String name, Object value) {
        Set<Stream> watching = streams.get(new Developer(workspaceId, accountId));
        if (watching == null || watching.isEmpty()) {
            return;
        }
        Event event = event(name, value);
        // One membership read for this delivery, never reused for a later one.
        boolean member = memberships.isActiveVisibleMember(workspaceId, accountId);
        List.copyOf(watching).forEach(stream -> {
            if (admitted(stream, member)) {
                send(stream, event);
            }
        });
    }

    /**
     * Whether the stream's account is still an active member of its workspace, read now. Closes the stream when it is
     * not. Every delivery reads it again: a removed member receives nothing after the removal commits. Runs on the
     * reader thread.
     */
    private boolean admitted(Stream stream) {
        return admitted(stream, memberships.isActiveVisibleMember(stream.workspaceId, stream.accountId));
    }

    private boolean admitted(Stream stream, boolean member) {
        if (!member) {
            close(stream);
        }
        return member;
    }

    /** Built once, sent to many streams: a built event is immutable, a builder is not. */
    private Event event(String name, Object value) {
        return new Event(SseEmitter.event()
                .name(name)
                .data(mapper.writeValueAsString(value))
                .build());
    }

    private void send(Stream stream, Event event) {
        boolean drain;
        synchronized (stream.queue) {
            if (stream.closed.get()) {
                return;
            }
            if (stream.queue.size() >= queueCapacity) {
                stream.queue.clear();
                read(() -> snapshot(stream));
                return;
            }
            stream.queue.addLast(event);
            drain = !stream.draining;
            stream.draining = true;
        }
        if (drain) {
            try {
                writers.execute(() -> drain(stream));
            } catch (RejectedExecutionException shuttingDown) {
                close(stream);
            }
        }
    }

    private void drain(Stream stream) {
        while (true) {
            Event event;
            synchronized (stream.queue) {
                event = stream.queue.pollFirst();
                if (event == null) {
                    stream.draining = false;
                    return;
                }
            }
            try {
                stream.emitter.send(event.data());
            } catch (IOException | RuntimeException gone) {
                log.debug(
                        "Hephaestus live stream closed while sending: {}",
                        gone.getClass().getSimpleName());
                close(stream);
                return;
            }
        }
    }

    private void close(Stream stream) {
        remove(stream);
        try {
            stream.emitter.complete();
        } catch (RuntimeException alreadyComplete) {
            // The client went away or the container timed the request out.
        }
    }

    private void remove(Stream stream) {
        if (!stream.closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (streams) {
            Developer developer = new Developer(stream.workspaceId, stream.accountId);
            Optional.ofNullable(streams.get(developer)).ifPresent(own -> {
                own.remove(stream);
                if (own.isEmpty()) {
                    streams.remove(developer);
                }
            });
        }
        synchronized (stream.queue) {
            stream.queue.clear();
        }
    }

    /**
     * Ends every stream when this context stops, in the default phase, before the web server's graceful shutdown: an
     * open stream would hold that phase until its timeout. A lifecycle phase, unlike a context event, belongs to this
     * context alone, so a child context that closes ends no stream here. The browser reconnects to the next instance,
     * and the new stream opens with a snapshot.
     */
    @Override
    public void stop() {
        endStreams();
        running = false;
    }

    @Override
    public void start() {
        synchronized (streams) {
            closing = false;
        }
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE;
    }

    private void endStreams() {
        synchronized (streams) {
            closing = true;
        }
        new ArrayList<>(allStreams()).forEach(this::close);
    }

    @PreDestroy
    void shutdown() {
        endStreams();
        reader.shutdownNow();
        writers.shutdown();
        try {
            if (!writers.awaitTermination(5, TimeUnit.SECONDS)) {
                writers.shutdownNow();
            }
        } catch (InterruptedException e) {
            writers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class Stream {

        final long workspaceId;
        final long accountId;
        final SseEmitter emitter;
        final Deque<Event> queue = new ArrayDeque<>();
        final AtomicBoolean closed = new AtomicBoolean();

        /** Guarded by {@link #queue}: a writer is sending this stream's queue. */
        boolean draining;

        Stream(long workspaceId, long accountId, SseEmitter emitter) {
            this.workspaceId = workspaceId;
            this.accountId = accountId;
            this.emitter = emitter;
        }
    }
}
