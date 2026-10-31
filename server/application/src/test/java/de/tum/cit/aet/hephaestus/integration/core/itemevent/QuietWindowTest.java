package de.tum.cit.aet.hephaestus.integration.core.itemevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class QuietWindowTest extends BaseUnitTest {
    private static final ItemEventName PUSH = new ItemEventName("scm.pull_request.synchronized");

    private final MutableClock clock = MutableClock.atFixedInstant();
    private final ItemEventService events = new ItemEventService(
            mock(ItemEventRepository.class),
            mock(ItemRepository.class),
            mock(ApplicationEventPublisher.class),
            clock,
            new ItemEventLedgerProperties(
                    Duration.ofHours(1),
                    Duration.ofDays(7),
                    200,
                    Duration.ofDays(30),
                    Duration.ofMinutes(2),
                    Duration.ofMinutes(10)));

    @Test
    void shouldCloseOneWindowAfterAnOccasionThatJustJoined() {
        assertThat(events.windowCloses(List.of(recorded())))
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(2)));
    }

    @Test
    void shouldMoveTheCloseWithEachNewerOccasionUpToTheCapFromTheFirst() {
        Instant first = clock.instant();
        var waiting = waiting(first);
        clock.advance(Duration.ofMinutes(1));
        assertThat(events.windowCloses(List.of(waiting, recorded()))).isEqualTo(first.plus(Duration.ofMinutes(3)));
        clock.advance(Duration.ofMinutes(8));
        assertThat(events.windowCloses(List.of(waiting, recorded()))).isEqualTo(first.plus(Duration.ofMinutes(10)));
    }

    @Test
    void shouldCloseAWindowWhoseOccasionsAllWaitedLongEnough() {
        var waiting = waiting(clock.instant());
        clock.advance(Duration.ofMinutes(2));
        assertThat(events.windowCloses(List.of(waiting))).isEqualTo(clock.instant());
    }

    @Test
    void shouldGiveAnOccasionThatWaitedForAnotherReasonAFullWindowWhenItRejoins() {
        var refused = new ItemEventService.Open(
                UUID.randomUUID(),
                PUSH,
                "v",
                ItemEventState.WAITING,
                ReviewRequestReason.BUDGET_EXHAUSTED,
                clock.instant(),
                clock.instant());
        clock.advance(Duration.ofHours(1));
        assertThat(events.windowCloses(List.of(refused, recorded())))
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(2)));
    }

    private ItemEventService.Open recorded() {
        return new ItemEventService.Open(
                UUID.randomUUID(), PUSH, "v", ItemEventState.RECORDED, null, clock.instant(), clock.instant());
    }

    private static ItemEventService.Open waiting(Instant since) {
        return new ItemEventService.Open(
                UUID.randomUUID(), PUSH, "v", ItemEventState.WAITING, ReviewRequestReason.QUIET_WINDOW, since, since);
    }
}
