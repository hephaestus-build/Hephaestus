package de.tum.cit.aet.hephaestus.agent.conversation.web;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerNotifications;
import de.tum.cit.aet.hephaestus.conversation.ConversationReadDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationScopeFence;
import de.tum.cit.aet.hephaestus.conversation.ConversationScopeFence.Scope;
import de.tum.cit.aet.hephaestus.conversation.ConversationTimeline;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * The live streams of developers' Hephaestus conversations on this replica ({@code docs/contributor/unified-pi-runtime.mdx}
 * § Live updates). Changes arrive with ids only, through the replica's one {@link ConversationLedgerNotifications}
 * connection. One reader thread loads each change once and hands the same event to every stream of its developer in
 * the order it read them, so a stream never receives an older state after a newer one. A stream that falls too far
 * behind, or missed notifications, gets a new snapshot instead. Each read is of one developer's own rows, with the
 * stream's account bound as the owner of its transaction, and only for a developer who has a stream open here.
 */
@Component
@ConditionalOnServerRole
@OwnerScope.StoredOwnerOperation
public class ConversationLiveHub implements ConversationLedgerNotifications.Listener, SmartLifecycle {

    static final String SNAPSHOT = "snapshot";
    static final String TURN = "turn";
    static final String ENTRY = "entry";
    static final String READ = "read";

    private static final Logger log = LoggerFactory.getLogger(ConversationLiveHub.class);

    private static final long HEARTBEAT_INTERVAL_MS = 20_000L;

    /** Limits the streams one developer holds open; CORS does not prevent opening a stream. */
    public static final int MAX_STREAMS_PER_DEVELOPER = 10;

    private static final int QUEUE_CAPACITY = 256;

    private final ConversationLedgerNotifications notifications;
    private final ConversationTimeline timeline;
    private final ConversationScopeFence scopeFence;
    private final AccountMembershipQuery memberships;
    private final OwnerScope owners;
    private final TransactionOperations transactions;
    private final ObjectMapper mapper;
    private final int queueCapacity;

    /** Each stream under its account, which owns the conversation. */
    private final Map<Developer, Set<Stream>> streams = new ConcurrentHashMap<>();

    /** Set when the context closes; guarded by {@code streams}. */
    private boolean closing;

    private volatile boolean running;

    private final ExecutorService reader = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("conversation-live-reader").factory());
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean listening = new AtomicBoolean();

    @Autowired
    public ConversationLiveHub(
            ConversationLedgerNotifications notifications,
            ConversationTimeline timeline,
            ConversationScopeFence scopeFence,
            AccountMembershipQuery memberships,
            OwnerScope owners,
            PlatformTransactionManager transactionManager,
            ObjectMapper mapper,
            MeterRegistry meters) {
        this(
                notifications,
                timeline,
                scopeFence,
                memberships,
                owners,
                readOnly(transactionManager),
                mapper,
                meters,
                QUEUE_CAPACITY);
    }

    ConversationLiveHub(
            ConversationLedgerNotifications notifications,
            ConversationTimeline timeline,
            ConversationScopeFence scopeFence,
            AccountMembershipQuery memberships,
            OwnerScope owners,
            TransactionOperations transactions,
            ObjectMapper mapper,
            MeterRegistry meters,
            int queueCapacity) {
        this.notifications = notifications;
        this.timeline = timeline;
        this.scopeFence = scopeFence;
        this.memberships = memberships;
        this.owners = owners;
        this.transactions = transactions;
        this.mapper = mapper;
        this.queueCapacity = queueCapacity;
        Gauge.builder(AgentMetrics.CONVERSATION_LIVE_STREAMS, streams, ConversationLiveHub::count)
                .description("Live Hephaestus streams open on this server")
                .register(meters);
    }

    private static TransactionTemplate readOnly(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setReadOnly(true);
        return template;
    }

    private record Developer(long workspaceId, long accountId) {}

    /** One server-sent event, already written. */
    private record Event(
            Set<ResponseBodyEmitter.DataWithMediaType> data,
            @Nullable Long scopeEpoch) {}

    /**
     * Opens a stream of the account's conversation in a workspace.
     *
     * @param accountId the account that opens the stream: the owner of every read for it, and whose membership every
     *     delivery checks again
     */
    SseEmitter subscribe(long workspaceId, long accountId, SseEmitter emitter) {
        if (listening.compareAndSet(false, true)) {
            notifications.subscribe(this);
        }
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
    public void turnChanged(ConversationLedgerEvents.TurnChanged change) {
        long workspaceId = change.workspaceId();
        long accountId = change.accountId();
        if (isWatched(workspaceId, accountId)) {
            read(() -> owned(accountId, () -> timeline.liveTurn(workspaceId, accountId, change.turnId()))
                    .ifPresent(turn -> fanOut(workspaceId, accountId, event(TURN, turn, turn.scopeEpoch()))));
        }
    }

    @Override
    public void entryChanged(ConversationLedgerEvents.EntryChanged change) {
        long workspaceId = change.workspaceId();
        long accountId = change.accountId();
        if (isWatched(workspaceId, accountId)) {
            change.entryIds()
                    .forEach(entryId -> read(() -> owned(accountId, () -> {
                                long scopeEpoch = timeline.scopeEpoch(workspaceId, accountId);
                                return timeline.liveEntry(workspaceId, accountId, entryId)
                                        .map(entry -> event(ENTRY, entry, scopeEpoch));
                            })
                            .ifPresent(event -> fanOut(workspaceId, accountId, event))));
        }
    }

    /** Another device of the developer moved the read marker. */
    @Override
    public void readChanged(ConversationLedgerEvents.ReadChanged change) {
        if (isWatched(change.workspaceId(), change.accountId())) {
            read(() -> fanOut(
                    change.workspaceId(),
                    change.accountId(),
                    event(READ, new ConversationReadDTO(change.lastReadAt()), null)));
        }
    }

    @Override
    public void scopeChanged(ConversationLedgerEvents.ScopeChanged change) {
        Set<Stream> watching = streams.get(new Developer(change.workspaceId(), change.accountId()));
        if (watching != null) {
            watching.forEach(stream -> read(() -> snapshot(stream)));
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
        Event ping = new Event(SseEmitter.event().comment("ping").build(), null);
        allStreams()
                .forEach(stream -> read(() -> {
                    if (admitted(stream)) {
                        send(stream, ping);
                    }
                }));
    }

    /** Whether the developer has a stream open on this replica: only then is their change read. */
    private boolean isWatched(long workspaceId, long accountId) {
        return streams.containsKey(new Developer(workspaceId, accountId));
    }

    /** A read of the developer's own rows, in a transaction whose owner is the developer. */
    private <T> T owned(long accountId, Supplier<T> read) {
        return Objects.requireNonNull(transactions.execute(status -> {
            owners.ofJob(accountId);
            return read.get();
        }));
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
            var conversation =
                    owned(stream.accountId, () -> timeline.conversation(stream.workspaceId, stream.accountId));
            send(stream, event(SNAPSHOT, conversation, conversation.scopeEpoch()));
        }
    }

    private void fanOut(long workspaceId, long accountId, Event event) {
        Set<Stream> watching = streams.get(new Developer(workspaceId, accountId));
        if (watching == null || watching.isEmpty()) {
            return;
        }
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
    private Event event(String name, Object value, @Nullable Long scopeEpoch) {
        return new Event(
                SseEmitter.event()
                        .name(name)
                        .data(mapper.writeValueAsString(value))
                        .build(),
                scopeEpoch);
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
                if (stream.emitter instanceof ConversationEventEmitter response
                        && !response.awaitInstalled(Duration.ofSeconds(2))) {
                    close(stream);
                    return;
                }
                Long epoch = event.scopeEpoch();
                if (epoch == null) {
                    stream.emitter.send(event.data());
                } else if (!scopeFence.admit(
                        new Scope(stream.workspaceId, stream.accountId, epoch), () -> !stream.closed.get(), () -> {
                            try {
                                stream.emitter.send(event.data());
                            } catch (IOException failure) {
                                throw new UncheckedIOException(failure);
                            }
                        })) {
                    synchronized (stream.queue) {
                        stream.queue.clear();
                    }
                    read(() -> snapshot(stream));
                }
            } catch (IOException | RuntimeException gone) {
                Throwable cause = gone instanceof UncheckedIOException failure
                        ? Objects.requireNonNullElse(failure.getCause(), gone)
                        : gone;
                log.debug(
                        "Hephaestus live stream closed while sending: {}",
                        cause.getClass().getSimpleName());
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
