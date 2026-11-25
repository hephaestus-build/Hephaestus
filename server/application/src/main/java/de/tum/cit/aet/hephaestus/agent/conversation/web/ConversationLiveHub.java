package de.tum.cit.aet.hephaestus.agent.conversation.web;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerNotifications;
import de.tum.cit.aet.hephaestus.conversation.ConversationReadDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationTimeline;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
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

    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final long HEARTBEAT_INTERVAL_MS = 20_000L;

    /** Limits the streams one developer holds open; CORS does not prevent opening a stream. */
    public static final int MAX_STREAMS_PER_DEVELOPER = 10;

    private static final int QUEUE_CAPACITY = 256;

    /** How long a stream's writer waits for Spring to install the response handler before it closes the stream. */
    static final Duration INSTALL_WAIT = Duration.ofSeconds(10);

    private final ConversationLedgerNotifications notifications;
    private final ConversationTimeline timeline;
    private final AccountMembershipQuery memberships;
    private final OwnerScope owners;
    private final TransactionOperations transactions;
    private final ObjectMapper mapper;
    private final Supplier<ConversationEventEmitter> emitters;
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
            AccountMembershipQuery memberships,
            OwnerScope owners,
            PlatformTransactionManager transactionManager,
            ObjectMapper mapper,
            MeterRegistry meters) {
        this(
                notifications,
                timeline,
                memberships,
                owners,
                readOnly(transactionManager),
                mapper,
                meters,
                () -> new ConversationEventEmitter(TIMEOUT_MS),
                QUEUE_CAPACITY);
    }

    ConversationLiveHub(
            ConversationLedgerNotifications notifications,
            ConversationTimeline timeline,
            AccountMembershipQuery memberships,
            OwnerScope owners,
            TransactionOperations transactions,
            ObjectMapper mapper,
            MeterRegistry meters,
            Supplier<ConversationEventEmitter> emitters,
            int queueCapacity) {
        this.notifications = notifications;
        this.timeline = timeline;
        this.memberships = memberships;
        this.owners = owners;
        this.transactions = transactions;
        this.mapper = mapper;
        this.emitters = emitters;
        this.queueCapacity = queueCapacity;
        Gauge.builder(
                        AgentMetrics.CONVERSATION_LIVE_STREAMS,
                        streams,
                        map -> map.values().stream()
                                .flatMap(Set::stream)
                                .distinct()
                                .count())
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
    private record Event(Set<ResponseBodyEmitter.DataWithMediaType> data) {}

    /** An event in a stream's queue, with the stream's context count when its data was read. */
    private record Queued(Event event, long context) {}

    /**
     * Opens a stream of the account's conversation in a workspace.
     *
     * @param accountId the account that opens the stream: the owner of every read for it, and whose membership every
     *     delivery checks again
     */
    public SseEmitter subscribe(long workspaceId, long accountId) {
        if (listening.compareAndSet(false, true)) {
            notifications.subscribe(this);
        }
        ConversationEventEmitter emitter = emitters.get();
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
        Map<Stream, Long> contexts = contexts(workspaceId, accountId);
        if (!contexts.isEmpty()) {
            read(() -> owned(accountId, () -> timeline.liveTurn(workspaceId, accountId, change.turnId()))
                    .ifPresent(turn -> fanOut(workspaceId, accountId, TURN, turn, contexts)));
        }
    }

    @Override
    public void entryChanged(ConversationLedgerEvents.EntryChanged change) {
        long workspaceId = change.workspaceId();
        long accountId = change.accountId();
        Map<Stream, Long> contexts = contexts(workspaceId, accountId);
        if (!contexts.isEmpty()) {
            change.entryIds()
                    .forEach(entryId ->
                            read(() -> owned(accountId, () -> timeline.liveEntry(workspaceId, accountId, entryId))
                                    .ifPresent(entry -> fanOut(workspaceId, accountId, ENTRY, entry, contexts))));
        }
    }

    /** Another device of the developer moved the read marker. */
    @Override
    public void readChanged(ConversationLedgerEvents.ReadChanged change) {
        Map<Stream, Long> contexts = contexts(change.workspaceId(), change.accountId());
        if (!contexts.isEmpty()) {
            read(() -> fanOut(
                    change.workspaceId(),
                    change.accountId(),
                    READ,
                    new ConversationReadDTO(change.lastReadAt()),
                    contexts));
        }
    }

    /**
     * What the developer's turns may read changed, or part of their history was removed. Each affected stream drops what
     * it queued and everything read before now, then starts over from a snapshot. The count moves here, on the
     * notification thread, not when the reader reaches the snapshot: a read that started earlier is withheld too.
     */
    @Override
    public void contextChanged(ConversationLedgerEvents.ContextChanged change) {
        streams.forEach((developer, open) -> {
            if (change.affects(developer.workspaceId(), developer.accountId())) {
                open.forEach(this::startOver);
            }
        });
    }

    /** Changes made while the notification connection was down were missed: every stream starts over. */
    @Override
    public void resync() {
        allStreams().forEach(this::startOver);
    }

    /** Keeps quiet streams open through proxies' idle timeouts, and closes those whose account left the workspace. */
    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS, initialDelay = HEARTBEAT_INTERVAL_MS)
    @WorkspaceAgnostic("Writes transport heartbeats to streams whose account is still a member")
    void heartbeat() {
        Event ping = new Event(SseEmitter.event().comment("ping").build());
        allStreams()
                .forEach(stream -> read(() -> {
                    if (admitted(stream, memberships.isActiveVisibleMember(stream.workspaceId, stream.accountId))) {
                        send(stream, ping, stream.context());
                    }
                }));
    }

    /** A read of the developer's own rows, in a transaction whose owner is the developer. */
    private <T> T owned(long accountId, Supplier<T> read) {
        return Objects.requireNonNull(transactions.execute(status -> {
            owners.ofJob(accountId);
            return read.get();
        }));
    }

    /**
     * Each open stream of the developer, with its context count when the change was heard; empty when the developer has
     * no stream open here, and then their change is not read. The read follows on the reader thread; a context change
     * heard in between withholds what it reads, and its snapshot covers the change.
     */
    private Map<Stream, Long> contexts(long workspaceId, long accountId) {
        Map<Stream, Long> contexts = new HashMap<>();
        Optional.ofNullable(streams.get(new Developer(workspaceId, accountId)))
                .ifPresent(open -> open.forEach(stream -> contexts.put(stream, stream.context())));
        return contexts;
    }

    private List<Stream> allStreams() {
        return streams.values().stream().flatMap(Set::stream).distinct().toList();
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
        long context = stream.context();
        if (!stream.closed.get()
                && admitted(stream, memberships.isActiveVisibleMember(stream.workspaceId, stream.accountId))) {
            send(
                    stream,
                    event(
                            SNAPSHOT,
                            owned(stream.accountId, () -> timeline.conversation(stream.workspaceId, stream.accountId))),
                    context);
        }
    }

    /**
     * Sends a change to the streams that were open when it was heard. A stream opened later starts with a snapshot of its
     * own.
     */
    private void fanOut(long workspaceId, long accountId, String name, Object value, Map<Stream, Long> contexts) {
        Set<Stream> watching = streams.get(new Developer(workspaceId, accountId));
        if (watching == null || watching.isEmpty()) {
            return;
        }
        Event event = event(name, value);
        // One membership read for this delivery, never reused for a later one.
        boolean member = memberships.isActiveVisibleMember(workspaceId, accountId);
        List.copyOf(watching).forEach(stream -> {
            Long context = contexts.get(stream);
            if (context != null && admitted(stream, member)) {
                send(stream, event, context);
            }
        });
    }

    /**
     * Whether the stream's account is still an active member of its workspace, as {@code member} read it. Closes the
     * stream when it is not. Every delivery reads it again: a removed member receives nothing after the removal
     * commits. Runs on the reader thread.
     */
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

    /** Queues an event whose data was read at the stream's context count {@code context}; an older one is dropped. */
    private void send(Stream stream, Event event, long context) {
        boolean drain;
        synchronized (stream.queue) {
            if (stream.closed.get() || context != stream.context) {
                return;
            }
            if (stream.queue.size() >= queueCapacity) {
                stream.queue.clear();
                read(() -> snapshot(stream));
                return;
            }
            stream.queue.addLast(new Queued(event, context));
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

    /**
     * Writes the stream's queue, on this stream's own writer thread. It first waits until Spring installed the response
     * handler, so no event waits in Spring's own buffer. Then it holds the stream's write lock from taking an event and
     * checking its context count to the end of its transport write. A context change that finds the lock free starts the
     * stream over; one that finds a write in progress closes the stream instead. So no event read before a context change
     * starts its write after it, and no listener waits on a stream.
     */
    private void drain(Stream stream) {
        if (!stream.installed) {
            if (!stream.emitter.awaitInstalled(INSTALL_WAIT)) {
                close(stream);
                return;
            }
            stream.installed = true;
        }
        while (true) {
            stream.writing.lock();
            try {
                Event event;
                synchronized (stream.queue) {
                    Queued next;
                    do {
                        next = stream.queue.pollFirst();
                    } while (next != null && next.context() != stream.context);
                    if (next == null) {
                        stream.draining = false;
                        return;
                    }
                    event = next.event();
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
            } finally {
                stream.writing.unlock();
            }
        }
    }

    /**
     * Starts the stream over from a snapshot. A stream with a write in progress is closed instead, without waiting on
     * it: its client opens a new one, which starts with a snapshot.
     */
    private void startOver(Stream stream) {
        if (stream.startOver()) {
            read(() -> snapshot(stream));
        } else {
            close(stream);
        }
    }

    /**
     * Closes the stream: it sends nothing more from now on. Spring's completion waits for a write in progress on the same
     * emitter, so it runs on a writer thread, never on the caller's.
     */
    private void close(Stream stream) {
        remove(stream);
        try {
            writers.execute(stream::complete);
        } catch (RejectedExecutionException shuttingDown) {
            stream.complete();
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
        // Completed here, not on a writer: an open stream would hold the web server's graceful shutdown.
        new ArrayList<>(allStreams()).forEach(stream -> {
            remove(stream);
            stream.complete();
        });
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
        final ConversationEventEmitter emitter;
        final Deque<Queued> queue = new ArrayDeque<>();

        /**
         * Held by the writer from taking an event to the end of its transport write. A context change only tries it, and
         * never waits on it.
         */
        final ReentrantLock writing = new ReentrantLock();

        /** Set by the writer once Spring installed the response handler. */
        volatile boolean installed;

        final AtomicBoolean closed = new AtomicBoolean();

        /** Guarded by {@link #queue}: a writer is sending this stream's queue. */
        boolean draining;

        /**
         * Guarded by {@link #queue}: how often the stream started over after a context change. An event read at an older
         * count is never sent.
         */
        long context;

        Stream(long workspaceId, long accountId, ConversationEventEmitter emitter) {
            this.workspaceId = workspaceId;
            this.accountId = accountId;
            this.emitter = emitter;
        }

        long context() {
            synchronized (queue) {
                return context;
            }
        }

        /**
         * Drops what the stream queued, and every event read at the count before now. It does not wait for a write in
         * progress.
         *
         * @return {@code false} when a write was in progress: that write began before the change, so the caller closes
         *     the stream; the count moved anyway
         */
        boolean startOver() {
            boolean fenced = writing.tryLock();
            try {
                synchronized (queue) {
                    context++;
                    queue.clear();
                }
            } finally {
                if (fenced) writing.unlock();
            }
            return fenced;
        }

        void complete() {
            try {
                emitter.complete();
            } catch (RuntimeException alreadyComplete) {
                // The client went away or the container timed the request out.
            }
        }
    }
}
