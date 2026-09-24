package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.config.AgentBindingLimits;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.mentor.ChatMessage;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.OptimisticLockingFailureException;

class MentorInFlightReaperTest extends BaseUnitTest {

    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final LlmUsageRecorder usageRecorder = mock(LlmUsageRecorder.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("one turn's failed write does not stop the turns behind it")
    void shouldKeepAccountingAfterOneTurnFails() {
        MentorInFlightAccounting accounting = mock(MentorInFlightAccounting.class);
        List<ChatMessage> stale = List.of(messageWithId(), messageWithId(), messageWithId());
        UUID first = stale.get(0).getId();
        UUID second = stale.get(1).getId();
        UUID third = stale.get(2).getId();
        when(chatMessageRepository.findStaleInFlightForAccounting(any())).thenReturn(stale);
        when(accounting.account(second)).thenThrow(new OptimisticLockingFailureException("row moved"));
        when(accounting.account(first)).thenReturn(true);
        when(accounting.account(third)).thenReturn(true);

        MentorInFlightReaper reaper = reaperWith(accounting);

        assertThatCode(reaper::reap).doesNotThrowAnyException();
        verify(accounting).account(third);
        assertThat(meterRegistry.counter("mentor.in_flight.reaper.failure").count())
                .as("a lost write must be counted, not swallowed — it means a turn is staying stuck")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a turn that left in_flight since the select is skipped, not billed again")
    void shouldSkipTurnThatIsNoLongerInFlight() {
        ChatMessage finished = messageWithId();
        finished.setStatus(ChatMessage.Status.completed);
        when(chatMessageRepository.findById(finished.getId())).thenReturn(Optional.of(finished));

        MentorInFlightAccounting accounting = new MentorInFlightAccounting(chatMessageRepository, usageRecorder);

        assertThat(accounting.account(finished.getId())).isFalse();
        verify(usageRecorder, never()).record(anyLong(), any());
        verify(usageRecorder, never()).recordUnverifiable(anyLong(), any());
    }

    @Test
    @DisplayName("a turn deleted since the select is skipped rather than NPEing the sweep")
    void shouldSkipTurnThatNoLongerExists() {
        UUID gone = UUID.randomUUID();
        when(chatMessageRepository.findById(gone)).thenReturn(Optional.empty());

        MentorInFlightAccounting accounting = new MentorInFlightAccounting(chatMessageRepository, usageRecorder);

        assertThat(accounting.account(gone)).isFalse();
        verify(usageRecorder, never()).record(anyLong(), any());
    }

    @Test
    @DisplayName("the sweep is single-flighted across replicas")
    void shouldBeSchedulerLocked() throws NoSuchMethodException {
        Method reap = MentorInFlightReaper.class.getMethod("reap");

        SchedulerLock lock = reap.getAnnotation(SchedulerLock.class);

        assertThat(lock)
                .as("an unlocked money-writing sweep double-runs on every multi-replica deploy")
                .isNotNull();
        assertThat(lock.name()).isEqualTo("mentor-in-flight-reaper");
        assertThat(lock.lockAtMostFor())
                .as(
                        "without its own bound the sweep falls back to ShedLockConfig's 30-minute default, so a crashed pod "
                                + "holds the lock across far more of this two-minute sweep's ticks than its own runtime warrants")
                .isNotBlank();
    }

    @Test
    @DisplayName("the window always outlasts the longest turn a binding can be configured to produce")
    void shouldNeverReapWithinTheConfigurableTimeoutCeiling() {
        Duration longestPossibleTurn = Duration.ofSeconds(AgentBindingLimits.MAX_TIMEOUT_SECONDS);

        MentorInFlightReaper defaultWindow =
                reaperWith(new MentorInFlightAccounting(chatMessageRepository, usageRecorder));
        MentorInFlightReaper misconfigured = new MentorInFlightReaper(
                chatMessageRepository,
                new MentorInFlightAccounting(chatMessageRepository, usageRecorder),
                meterRegistry,
                Duration.ofMinutes(1));

        assertThat(defaultWindow.window()).isGreaterThan(longestPossibleTurn);
        assertThat(misconfigured.window())
                .as("a window under the timeout ceiling reaps turns that are still streaming")
                .isGreaterThan(longestPossibleTurn);
    }

    @Test
    void shouldDeriveTheDefaultWindowWhenNoPropertyIsConfigured() {
        new ApplicationContextRunner()
                .withBean(ChatMessageRepository.class, () -> chatMessageRepository)
                .withBean(MentorInFlightAccounting.class, () -> mock(MentorInFlightAccounting.class))
                .withBean(io.micrometer.core.instrument.MeterRegistry.class, () -> meterRegistry)
                .withBean(MentorInFlightReaper.class)
                .run(context -> assertThat(
                                context.getBean(MentorInFlightReaper.class).window())
                        .isEqualTo(Duration.ofSeconds(AgentBindingLimits.MAX_TIMEOUT_SECONDS)
                                .plusMinutes(10)));
    }

    @Test
    void shouldPreserveALongerConfiguredWindow() {
        Duration configured = Duration.ofDays(1);
        MentorInFlightReaper reaper = new MentorInFlightReaper(
                chatMessageRepository, mock(MentorInFlightAccounting.class), meterRegistry, configured);

        assertThat(reaper.window()).isEqualTo(configured);
    }

    private MentorInFlightReaper reaperWith(MentorInFlightAccounting accounting) {
        return new MentorInFlightReaper(chatMessageRepository, accounting, meterRegistry, null);
    }

    private static ChatMessage messageWithId() {
        ChatMessage message = new ChatMessage();
        message.setId(UUID.randomUUID());
        message.setStatus(ChatMessage.Status.in_flight);
        return message;
    }
}
