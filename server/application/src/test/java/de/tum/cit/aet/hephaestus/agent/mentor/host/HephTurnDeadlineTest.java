package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.config.AgentBindingLimits;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnSandboxes;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarter;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyCallsInFlight;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The turn deadline against a turn that keeps writing: the dispatcher's clock is the test's, and the test runs the
 * dispatcher's tick itself. A reasoning model at about 35 tokens a second wrote for more than five minutes in one turn,
 * and the turn must not end for that.
 */
class HephTurnDeadlineTest extends BaseUnitTest {

    private static final JsonMapper MAPPER = new JsonMapper();
    private static final UUID TURN = UUID.randomUUID();

    private final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
    private final MutableClock clock = MutableClock.atFixedInstant();
    private final MentorTurnCompletion completion = mock(MentorTurnCompletion.class);
    private HephTurnDispatcher dispatcher;

    /** A ready host whose command channel is a queue the test reads. */
    private final class QueueHost extends HephHost {
        QueueHost() {
            super(mock(HostSandbox.class));
        }

        @Override
        boolean sandboxReady() {
            return true;
        }

        @Override
        boolean awaitReady(Duration timeout) {
            return true;
        }

        @Override
        void send(String frame) {
            frames.add(MAPPER.readTree(frame));
        }

        @Override
        void shutdown() {}
    }

    @BeforeEach
    void startATurn() throws InterruptedException {
        MentorTurn turn = mock(MentorTurn.class);
        when(turn.getId()).thenReturn(TURN);
        when(turn.getWorkspaceId()).thenReturn(7L);
        when(turn.getAttempt()).thenReturn(1);
        when(turn.getConversationId()).thenReturn(UUID.randomUUID());
        when(turn.getStatus()).thenReturn(MentorTurn.Status.RUNNING);
        MentorTurnService turns = mock(MentorTurnService.class);
        when(turns.require(7L, TURN)).thenReturn(turn);
        MentorTurnStarter starter = mock(MentorTurnStarter.class);
        // The fixture's binding has the default Heph timeout: the ceiling.
        var started = Optional.of(
                new MentorTurnStarter.StartedTurn(turn, 11L, LlmCatalogTestFixtures.admittedMentorConfig()));
        when(starter.startNext(any(), any())).thenReturn(started).thenReturn(Optional.empty());
        dispatcher = new HephTurnDispatcher(
                starter,
                turns,
                completion,
                mock(MentorLedgerNotifications.class),
                new QueueHost(),
                new ProxyCallsInFlight(),
                mock(TurnSandboxes.class),
                mock(WorkerJwtIssuer.class),
                MAPPER,
                clock,
                new SimpleMeterRegistry(),
                "worker",
                1,
                Duration.ofMinutes(1),
                HephTurnDispatcher.STOP_GRACE);
        dispatcher.start();
        dispatcher.resync();
        assertThat(next().path("type").asString()).isEqualTo("start");
    }

    @AfterEach
    void stopTheDispatcher() {
        dispatcher.ended(TURN, 1);
        dispatcher.stop();
    }

    @Test
    void shouldKeepATurnThatKeepsWritingPastFiveMinutesAndStopItOnlyAtTheCeiling() throws InterruptedException {
        Duration ceiling = Duration.ofSeconds(AgentBindingLimits.MAX_HEPH_TURN_SECONDS);
        Duration step = Duration.ofSeconds(30);
        Duration written = Duration.ZERO;
        int part = 0;
        // A partial answer every 30 seconds, to the last tick before the ceiling.
        while (written.plus(step).compareTo(ceiling) <= 0) {
            clock.advance(step);
            written = written.plus(step);
            dispatcher.partial(TURN, 1, MAPPER.readTree("{\"activity\":\"writing\",\"part\":" + ++part + "}"));
            dispatcher.tick();
        }
        assertThat(written).isGreaterThan(Duration.ofMinutes(5));
        assertThat(frames).as("no stop while the turn is within its ceiling").isEmpty();
        assertThat(dispatcher.runs(TURN, 1)).isTrue();
        verify(completion, never()).end(any(), anyInt(), any(), any());

        clock.advance(Duration.ofSeconds(1));
        dispatcher.tick();
        JsonNode stop = next();
        assertThat(stop.path("type").asString()).isEqualTo("stop");
        assertThat(stop.path("reason").asString()).isEqualTo("deadline");

        clock.advance(HephTurnDispatcher.STOP_GRACE.plusSeconds(1));
        dispatcher.tick();
        verify(completion)
                .end(
                        eq(TURN),
                        eq(1),
                        eq("worker"),
                        eq(new MentorTurnCompletion.Ending(
                                MentorTurn.Status.FAILED,
                                List.of(),
                                HephTurnDispatcher.TIME_LIMIT_REACHED,
                                "its Heph host did not end it within " + HephTurnDispatcher.STOP_GRACE
                                        + " after its deadline")));
        assertThat(dispatcher.runs(TURN, 1)).isFalse();
    }

    private JsonNode next() throws InterruptedException {
        return Objects.requireNonNull(frames.poll(10, TimeUnit.SECONDS), "a command frame");
    }
}
