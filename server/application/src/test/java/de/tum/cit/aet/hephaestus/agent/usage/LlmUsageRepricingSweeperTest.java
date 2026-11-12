package de.tum.cit.aet.hephaestus.agent.usage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class LlmUsageRepricingSweeperTest extends BaseUnitTest {

    @Test
    void shouldResumeAfterAFullPageAndStartOverAfterAShortOne() {
        var repository = mock(LlmUsageEventRepository.class);
        var repricer = mock(LlmUsageRepricer.class);
        when(repricer.reprice(any())).thenReturn(LlmUsageRepricer.ResultOutcome.STILL_UNPRICEABLE);
        List<UnpricedLedgerRow> full = IntStream.range(0, LlmUsageRepricingSweeper.MAX_ROWS_PER_PASS)
                .mapToObj(i -> row())
                .toList();
        UnpricedLedgerRow last = full.getLast();
        Instant lastAt = Instant.parse("2026-08-01T00:00:00Z");
        LlmUsageEvent lastEvent = new LlmUsageEvent();
        lastEvent.setId(last.id());
        lastEvent.setOccurredAt(lastAt);
        List<UnpricedLedgerRow> shortPage = List.of(row());
        when(repository.findUnpriced(any(Pageable.class))).thenReturn(full).thenReturn(shortPage);
        when(repository.findById(last.id())).thenReturn(Optional.of(lastEvent));
        when(repository.findUnpricedAfter(eq(lastAt), eq(last.id()), any(Pageable.class)))
                .thenReturn(List.of(row()));
        var sweeper = new LlmUsageRepricingSweeper(repository, repricer, new SimpleMeterRegistry());

        // Rows that stay unpriced fill the first page; the next pass reads the rows behind them.
        sweeper.repriceNow();
        sweeper.repriceNow();
        // That page was short, so the pass after it starts again from the oldest row.
        sweeper.repriceNow();

        var order = inOrder(repository);
        order.verify(repository).findUnpriced(any(Pageable.class));
        order.verify(repository).findUnpricedAfter(eq(lastAt), eq(last.id()), any(Pageable.class));
        order.verify(repository).findUnpriced(any(Pageable.class));
    }

    private static UnpricedLedgerRow row() {
        return new UnpricedLedgerRow(
                UUID.randomUUID(),
                1L,
                "model",
                UsageFundingSource.INSTANCE,
                10,
                1,
                0,
                0,
                0,
                null,
                null,
                MeasurementVerification.VERIFIED,
                UsagePricingState.UNPRICED,
                null,
                null,
                null,
                null);
    }
}
