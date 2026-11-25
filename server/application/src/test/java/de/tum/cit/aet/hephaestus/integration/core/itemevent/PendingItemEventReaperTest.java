package de.tum.cit.aet.hephaestus.integration.core.itemevent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.ControlledClock;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventRecorded;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

@Tag("unit")
class PendingItemEventReaperTest {

    @Test
    void shouldOfferAllDueItemEventsWhenTheClockAdvances() {
        var repository = mock(ItemEventRepository.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var clock = new ControlledClock(Instant.EPOCH);
        var properties = new ItemEventLedgerProperties(
                Duration.ofHours(1),
                Duration.ofDays(7),
                1,
                Duration.ofDays(30),
                Duration.ofMinutes(2),
                Duration.ofMinutes(10));
        var reaper = new PendingItemEventReaper(
                repository,
                properties,
                publisher,
                clock,
                new MockEnvironment().withProperty(RuntimeRole.AGENT_ENABLED_PROPERTY, "true"));
        Instant now = clock.instant().plus(Duration.ofHours(2));
        var first = event();
        var second = event();
        when(repository.claimDue(now, Integer.MAX_VALUE)).thenReturn(List.of(first, second));

        clock.set(now);
        reaper.advance(now);

        for (var event : List.of(first, second)) {
            verify(repository).attempted(event.getWorkspaceId(), event.getId(), now, now.plus(Duration.ofHours(1)));
            verify(publisher)
                    .publishEvent(new ItemEventRecorded(event.getWorkspaceId(), event.getId(), event.getItemId()));
        }
    }

    private static ItemEventRepository.DueEvent event() {
        var event = mock(ItemEventRepository.DueEvent.class);
        when(event.getId()).thenReturn(UUID.randomUUID());
        when(event.getWorkspaceId()).thenReturn(1L);
        when(event.getItemId()).thenReturn(2L);
        return event;
    }
}
