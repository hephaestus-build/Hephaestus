package de.tum.cit.aet.hephaestus.agent.mentor.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorTimeline;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

class MentorLiveHubTest extends BaseUnitTest {

    private static final long WORKSPACE = 1L;
    private static final long ACCOUNT = 70L;
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final MentorTimeline timeline = mock(MentorTimeline.class);
    private final AccountMembershipQuery memberships = mock(AccountMembershipQuery.class);
    private final AtomicBoolean member = new AtomicBoolean(true);
    private final RecordingEmitter emitter = new RecordingEmitter();
    private final MentorLiveHub hub = new MentorLiveHub(
            mock(MentorLedgerNotifications.class),
            timeline,
            memberships,
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            () -> emitter,
            1);

    /** Stubs are set before the hub's reader thread runs: Mockito stubbing is not safe while another thread calls the mock. */
    @BeforeEach
    void stubTimeline() {
        when(memberships.isActiveVisibleMember(WORKSPACE, ACCOUNT)).thenAnswer(call -> member.get());
        when(timeline.conversation(anyLong(), anyLong()))
                .thenReturn(new MentorConversationDTO(null, 0, List.of(), List.of(), null, null));
        when(timeline.liveEntry(anyLong(), any()))
                .thenAnswer(call -> Optional.of(new MentorTimeline.Owned<>(ACCOUNT, entry(call.getArgument(1)))));
    }

    @AfterEach
    void shutDown() {
        emitter.release();
        hub.shutdown();
    }

    private static MentorEntryDTO entry(UUID id) {
        return new MentorEntryDTO(
                id,
                UUID.randomUUID(),
                null,
                1,
                MentorEntryDTO.Kind.HEPH,
                MentorEntry.Surface.APP,
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
        hub.entryChanged(
                new MentorLedgerEvents.EntryChanged(WORKSPACE, UUID.randomUUID(), List.of(queued, overflowing)));
        verify(timeline, timeout(WAIT.toMillis()).times(2)).conversation(anyLong(), anyLong());
        emitter.release();

        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.names()).containsExactly("snapshot", "snapshot"));
        assertThat(emitter.frames())
                .noneMatch(frame -> frame.contains(queued.toString()) || frame.contains(overflowing.toString()));
    }

    @Test
    void shouldSendAnEntryCommittedWhileTheSnapshotIsReadAfterTheSnapshotWhenAStreamOpens() {
        UUID committed = UUID.randomUUID();
        when(timeline.conversation(anyLong(), anyLong())).thenAnswer(call -> {
            hub.entryChanged(new MentorLedgerEvents.EntryChanged(WORKSPACE, UUID.randomUUID(), List.of(committed)));
            return new MentorConversationDTO(null, 0, List.of(), List.of(), null, null);
        });

        hub.subscribe(WORKSPACE, ACCOUNT);

        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.names()).containsExactly("snapshot", "entry"));
        assertThat(emitter.frames()).anyMatch(frame -> frame.contains(committed.toString()));
    }

    @Test
    void shouldCloseTheStreamWithoutTheChangeWhenTheAccountLeftTheWorkspace() {
        hub.subscribe(WORKSPACE, ACCOUNT);
        await().atMost(WAIT).untilAsserted(() -> assertThat(emitter.names()).containsExactly("snapshot"));

        member.set(false);
        hub.entryChanged(new MentorLedgerEvents.EntryChanged(WORKSPACE, UUID.randomUUID(), List.of(UUID.randomUUID())));
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
        Logger logger = (Logger) LoggerFactory.getLogger(MentorLiveHub.class);
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
                        .isEqualTo("Heph live stream closed while sending: IOException")
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
                MentorLiveHub.class, hub, () -> hub.subscribe(WORKSPACE, ACCOUNT), emitter.completed::get);

        assertThat(ended).isTrue();
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
    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> frames = new ArrayList<>();
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean holding;
        private volatile @Nullable IOException sendFailure;

        void failSends(IOException failure) {
            sendFailure = failure;
        }

        void holdSends() {
            holding = true;
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
            if (holding) {
                held.countDown();
                try {
                    released.await(WAIT.toSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            StringBuilder wire = new StringBuilder();
            data.forEach(part -> wire.append(part.getData()));
            synchronized (frames) {
                frames.addAll(List.of(wire.toString().split("\n")));
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
