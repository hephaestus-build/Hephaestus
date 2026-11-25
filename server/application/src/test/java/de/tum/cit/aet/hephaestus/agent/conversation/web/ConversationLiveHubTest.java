package de.tum.cit.aet.hephaestus.agent.conversation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.conversation.ConversationDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerNotifications;
import de.tum.cit.aet.hephaestus.conversation.ConversationTimeline;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.WebServerDrainProbe;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class ConversationLiveHubTest extends BaseUnitTest {

    private static final long WORKSPACE = 1L;
    private static final long ACCOUNT = 70L;
    private static final long COLLEAGUE = 71L;
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final ConversationTimeline timeline = mock(ConversationTimeline.class);
    private final AccountMembershipQuery memberships = mock(AccountMembershipQuery.class);
    private final OwnerScope owners = mock(OwnerScope.class);
    private final AtomicBoolean member = new AtomicBoolean(true);
    private final RecordingEmitter emitter = new RecordingEmitter();
    private final RecordingEmitter second = new RecordingEmitter();
    private final AtomicInteger opened = new AtomicInteger();
    private final ConversationLiveHub hub = new ConversationLiveHub(
            mock(ConversationLedgerNotifications.class),
            timeline,
            memberships,
            owners,
            TransactionOperations.withoutTransaction(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            () -> opened.getAndIncrement() == 0 ? emitter : second,
            1);

    /** Stubs are set before the hub's reader thread runs: Mockito stubbing is not safe while another thread calls the mock. */
    @BeforeEach
    void stubTimeline() {
        when(memberships.isActiveVisibleMember(WORKSPACE, ACCOUNT)).thenAnswer(call -> member.get());
        when(timeline.conversation(anyLong(), anyLong()))
                .thenReturn(new ConversationDTO(null, 0, List.of(), List.of(), null, null));
        when(timeline.liveEntry(anyLong(), anyLong(), any()))
                .thenAnswer(call -> Optional.of(entry(call.getArgument(2))));
    }

    @AfterEach
    void shutDown() {
        emitter.release();
        hub.shutdown();
    }

    private static ConversationEntryDTO entry(UUID id) {
        return new ConversationEntryDTO(
                id,
                UUID.randomUUID(),
                null,
                1,
                ConversationEntryDTO.Kind.ANSWER,
                ConversationEntry.Surface.APP,
                "Your reviews are quick.",
                null,
                UUID.randomUUID(),
                null,
                null,
                Instant.parse("2026-10-08T07:00:00Z"),
                null,
                null,
                false,
                null,
                List.of(),
                null,
                null);
    }

    @Test
    void shouldSendASnapshotInsteadOfTheMissedEventsWhenAStreamFallsBehind() throws InterruptedException {
        emitter.holdSends();
        hub.subscribe(WORKSPACE, ACCOUNT);
        assertThat(emitter.awaitHeld()).isTrue(); // The first snapshot is being written; nothing else goes out.

        UUID queued = UUID.randomUUID();
        UUID overflowing = UUID.randomUUID();
        hub.entryChanged(new ConversationLedgerEvents.EntryChanged(
                WORKSPACE, UUID.randomUUID(), ACCOUNT, List.of(queued, overflowing)));
        verify(timeline, timeout(WAIT.toMillis()).times(2)).conversation(anyLong(), anyLong());
        emitter.release();

        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.names()).containsExactly("snapshot", "snapshot"));
        assertThat(emitter.frames())
                .noneMatch(frame -> frame.contains(queued.toString()) || frame.contains(overflowing.toString()));
    }

    @Test
    void shouldReplaceQueuedEventsWithASnapshotWhenTheReadersContextChanges() throws Exception {
        emitter.holdSends();
        hub.subscribe(WORKSPACE, ACCOUNT);
        assertThat(emitter.awaitHeld()).isTrue();

        UUID queued = UUID.randomUUID();
        hub.entryChanged(
                new ConversationLedgerEvents.EntryChanged(WORKSPACE, UUID.randomUUID(), ACCOUNT, List.of(queued)));
        // A write is in progress: the change does not wait on it, and closes the stream; its client starts over.
        hub.contextChanged(new ConversationLedgerEvents.ContextChanged(WORKSPACE, ACCOUNT));
        emitter.release();

        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.completed).isTrue());
        assertThat(emitter.names()).containsExactly("snapshot");
        assertThat(emitter.frames()).noneMatch(frame -> frame.contains(queued.toString()));
    }

    /**
     * A change read before the context changed reaches the stream after the change was heard: the hub withholds it and
     * sends the new snapshot instead.
     */
    @Test
    void shouldWithholdAnEntryReadBeforeTheContextChangedWhenItsReadEndsAfter() throws InterruptedException {
        UUID stale = UUID.randomUUID();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch changed = new CountDownLatch(1);
        when(timeline.liveEntry(anyLong(), anyLong(), eq(stale))).thenAnswer(call -> {
            reading.countDown();
            assertThat(changed.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            return Optional.of(entry(stale));
        });
        hub.subscribe(WORKSPACE, ACCOUNT);
        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.names()).containsExactly("snapshot"));

        hub.entryChanged(
                new ConversationLedgerEvents.EntryChanged(WORKSPACE, UUID.randomUUID(), ACCOUNT, List.of(stale)));
        assertThat(reading.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        hub.contextChanged(new ConversationLedgerEvents.ContextChanged(WORKSPACE, ACCOUNT));
        changed.countDown();

        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.names()).containsExactly("snapshot", "snapshot"));
        assertThat(emitter.frames()).noneMatch(frame -> frame.contains(stale.toString()));
    }

    /**
     * One stream's write stalls when the context changes. The change does not wait on it: it closes that stream off its
     * own thread and starts the other stream over at once.
     */
    @Test
    void shouldStartAnotherStreamOverAtOnceWhenOneStreamsWriteStalls() throws Exception {
        UUID writing = UUID.randomUUID();
        emitter.holdSendOf(writing.toString());
        hub.subscribe(WORKSPACE, ACCOUNT);
        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.names()).containsExactly("snapshot"));
        hub.subscribe(WORKSPACE, ACCOUNT);
        await().atMost(WAIT).untilAsserted(() -> assertThat(second.names()).containsExactly("snapshot"));
        hub.entryChanged(
                new ConversationLedgerEvents.EntryChanged(WORKSPACE, UUID.randomUUID(), ACCOUNT, List.of(writing)));
        assertThat(emitter.awaitHeld()).isTrue();

        CompletableFuture<Void> change = CompletableFuture.runAsync(
                () -> hub.contextChanged(new ConversationLedgerEvents.ContextChanged(WORKSPACE, ACCOUNT)));

        change.get(1, TimeUnit.SECONDS);
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(second.names()).containsExactly("snapshot", "entry", "snapshot"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.completed).isTrue());
        emitter.release();
    }

    /**
     * Spring has not installed the response handler yet, so a send would wait in its own buffer. The context changes
     * meanwhile: only the snapshot read after the change is sent, once the handler is installed.
     */
    @Test
    void shouldSendNoSnapshotReadBeforeAContextChangeWhileTheHandlerIsNotInstalled() {
        emitter.installed = false;
        hub.subscribe(WORKSPACE, ACCOUNT);
        verify(timeline, timeout(WAIT.toMillis()).times(1)).conversation(anyLong(), anyLong());

        hub.contextChanged(new ConversationLedgerEvents.ContextChanged(WORKSPACE, ACCOUNT));
        verify(timeline, timeout(WAIT.toMillis()).times(2)).conversation(anyLong(), anyLong());
        emitter.installed = true;

        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.names()).containsExactly("snapshot"));
        assertThat(emitter.completed).isFalse();
    }

    @Test
    void shouldSendAnEntryCommittedWhileTheSnapshotIsReadAfterTheSnapshotWhenAStreamOpens() {
        UUID committed = UUID.randomUUID();
        when(timeline.conversation(anyLong(), anyLong())).thenAnswer(call -> {
            hub.entryChanged(new ConversationLedgerEvents.EntryChanged(
                    WORKSPACE, UUID.randomUUID(), ACCOUNT, List.of(committed)));
            return new ConversationDTO(null, 0, List.of(), List.of(), null, null);
        });

        hub.subscribe(WORKSPACE, ACCOUNT);

        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.names()).containsExactly("snapshot", "entry"));
        assertThat(emitter.frames()).anyMatch(frame -> frame.contains(committed.toString()));
    }

    @Test
    void shouldReadNoChangeWhenItsDeveloperHasNoStreamOnThisServer() {
        hub.subscribe(WORKSPACE, ACCOUNT);
        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.names()).containsExactly("snapshot"));

        hub.entryChanged(new ConversationLedgerEvents.EntryChanged(
                WORKSPACE, UUID.randomUUID(), COLLEAGUE, List.of(UUID.randomUUID())));
        hub.turnChanged(new ConversationLedgerEvents.TurnChanged(
                WORKSPACE,
                UUID.randomUUID(),
                COLLEAGUE,
                UUID.randomUUID(),
                ConversationTurn.Status.QUEUED,
                ConversationTurn.Surface.APP,
                0));
        UUID own = UUID.randomUUID();
        hub.entryChanged(
                new ConversationLedgerEvents.EntryChanged(WORKSPACE, UUID.randomUUID(), ACCOUNT, List.of(own)));

        // One reader thread reads in order: the developer's own entry arrives after any read of the colleague's.
        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.names()).containsExactly("snapshot", "entry"));
        verify(timeline, never()).liveEntry(anyLong(), eq(COLLEAGUE), any());
        verify(timeline, never()).liveTurn(anyLong(), eq(COLLEAGUE), any());
        verify(owners, never()).ofJob(COLLEAGUE);
        verify(owners, times(2)).ofJob(ACCOUNT);
    }

    @Test
    void shouldCloseTheStreamWithoutTheChangeWhenTheAccountLeftTheWorkspace() {
        hub.subscribe(WORKSPACE, ACCOUNT);
        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.names()).containsExactly("snapshot"));

        member.set(false);
        hub.entryChanged(new ConversationLedgerEvents.EntryChanged(
                WORKSPACE, UUID.randomUUID(), ACCOUNT, List.of(UUID.randomUUID())));
        hub.heartbeat();

        await().atMost(WAIT).untilTrue(emitter.completed);
        assertThat(emitter.names()).containsExactly("snapshot");
        assertThat(emitter.frames()).doesNotContain(":ping");
    }

    @Test
    void shouldKeepAQuietStreamOpenWithACommentWhenTheHeartbeatRuns() {
        hub.subscribe(WORKSPACE, ACCOUNT);
        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.names()).containsExactly("snapshot"));

        hub.heartbeat();

        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.frames()).contains(":ping"));
        assertThat(emitter.names()).containsExactly("snapshot");
    }

    @Test
    void shouldCloseTheStreamWithoutLoggingPrivateFailureTextWhenSendingFails() {
        Logger logger = (Logger) LoggerFactory.getLogger(ConversationLiveHub.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            logger.setLevel(Level.DEBUG);
            emitter.failSends(new IOException("private-conversation-canary@example.test"));

            hub.subscribe(WORKSPACE, ACCOUNT);

            await().atMost(WAIT).untilTrue(emitter.completed);
            assertThat(emitter.frames()).isEmpty();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Hephaestus live stream closed while sending: IOException")
                        .doesNotContain("private-conversation-canary");
            });
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldEndEveryStreamBeforeTheWebServerDrainsWhenTheContextCloses() {
        boolean ended = WebServerDrainProbe.heldWhenTheWebServerDrains(
                ConversationLiveHub.class, hub, () -> hub.subscribe(WORKSPACE, ACCOUNT), emitter.completed::get);

        assertThat(ended).isTrue();
    }

    @Test
    void shouldKeepAcceptingStreamsWhenAChildContextCloses() {
        try (GenericApplicationContext parent = new GenericApplicationContext()) {
            // Event listeners on, as in the application: a closing child publishes its ContextClosedEvent here too.
            AnnotationConfigUtils.registerAnnotationConfigProcessors(parent);
            parent.registerBean(ConversationLiveHub.class, () -> hub);
            parent.refresh();
            GenericApplicationContext child = new GenericApplicationContext();
            child.setParent(parent);
            child.refresh();

            child.close();

            assertThatCode(() -> hub.subscribe(WORKSPACE, ACCOUNT)).doesNotThrowAnyException();
        }
    }

    @Test
    void shouldRefuseANewStreamWhenTheServerIsClosing() {
        hub.shutdown();

        assertThatThrownBy(() -> hub.subscribe(WORKSPACE, ACCOUNT))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    /** Records what the hub writes; it can hold the writer inside its first send. */
    private static final class RecordingEmitter extends ConversationEventEmitter {

        /** Whether Spring installed the response handler; a test can hold it back. */
        volatile boolean installed = true;

        RecordingEmitter() {
            super(WAIT.toMillis() * 6);
        }

        @Override
        boolean installed() {
            return installed;
        }

        private final List<String> frames = new ArrayList<>();
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean holding;
        private volatile @Nullable String holdMarker;
        private volatile @Nullable IOException sendFailure;

        void failSends(IOException failure) {
            sendFailure = failure;
        }

        void holdSends() {
            holding = true;
        }

        /** Holds the writer inside the transport write of the first frame that names {@code marker}. */
        void holdSendOf(String marker) {
            holdMarker = marker;
        }

        boolean awaitHeld() throws InterruptedException {
            return held.await(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }

        @Override
        public void send(Set<DataWithMediaType> data) throws IOException {
            IOException failure = sendFailure;
            if (failure != null) {
                throw failure;
            }
            StringBuilder sent = new StringBuilder();
            data.forEach(part -> sent.append(part.getData()));
            String marker = holdMarker;
            if (holding || (marker != null && sent.toString().contains(marker))) {
                held.countDown();
                try {
                    released.await(WAIT.toSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            synchronized (frames) {
                frames.addAll(List.of(sent.toString().split("\n")));
            }
        }

        final AtomicBoolean completed = new AtomicBoolean();

        @Override
        public void complete() {
            completed.set(true);
        }

        List<String> frames() {
            synchronized (frames) {
                return List.copyOf(frames);
            }
        }

        List<String> names() {
            return frames().stream()
                    .filter(frame -> frame.startsWith("event:"))
                    .map(frame -> frame.substring("event:".length()))
                    .toList();
        }
    }
}
