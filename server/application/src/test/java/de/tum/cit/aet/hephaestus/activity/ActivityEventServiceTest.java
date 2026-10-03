package de.tum.cit.aet.hephaestus.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;

class ActivityEventServiceTest extends BaseUnitTest {

    @Mock
    private ActivityEventRepository eventRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    private MeterRegistry meterRegistry;
    private ActivityEventService service;

    private final PersonDataWriteFence writeFence = Mockito.mock(PersonDataWriteFence.class);

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        Mockito.lenient()
                .when(writeFence.holdForUserWrite(ArgumentMatchers.anyLong()))
                .thenReturn(true);
        service = new ActivityEventService(eventRepository, workspaceRepository, meterRegistry, writeFence);
    }

    @Test
    void backfillAdmitsTheWholeBatchBeforeUpdatingOnlyPermittedAuthors() {
        when(eventRepository.unresolvedCommitAuthors(200L)).thenReturn(List.of(42L, 84L));
        when(writeFence.holdForUserWrites(List.of(42L, 84L))).thenReturn(List.of(84L));
        when(eventRepository.backfillCommitActors(200L, List.of(84L))).thenReturn(3);
        assertThat(service.backfillCommitActors(200L)).isEqualTo(3);
        var order = inOrder(writeFence, eventRepository);
        order.verify(eventRepository).unresolvedCommitAuthors(200L);
        order.verify(writeFence).holdForUserWrites(List.of(42L, 84L));
        order.verify(eventRepository).backfillCommitActors(200L, List.of(84L));
    }

    @Test
    void backfillDoesNotUpdateAnEntirelySuppressedBatch() {
        when(eventRepository.unresolvedCommitAuthors(200L)).thenReturn(List.of(42L));
        when(writeFence.holdForUserWrites(List.of(42L))).thenReturn(List.of());
        assertThat(service.backfillCommitActors(200L)).isZero();
        verify(eventRepository, never()).backfillCommitActors(anyLong(), anyList());
    }

    @Test
    void record_success_savesEvent() {
        when(workspaceRepository.existsById(1L)).thenReturn(true);
        when(eventRepository.insertIfAbsent(
                        any(UUID.class),
                        anyString(),
                        anyString(),
                        any(Instant.class),
                        any(),
                        eq(1L),
                        any(),
                        anyString(),
                        anyLong()))
                .thenReturn(1);

        boolean result = service.record(
                1L,
                ActivityEventType.PULL_REQUEST_OPENED,
                Instant.now(),
                null,
                null,
                ActivityTargetType.PULL_REQUEST,
                100L);

        assertThat(result).isTrue();
        verify(eventRepository)
                .insertIfAbsent(
                        any(UUID.class),
                        anyString(),
                        anyString(),
                        any(Instant.class),
                        any(),
                        eq(1L),
                        any(),
                        anyString(),
                        anyLong());
        assertThat(meterRegistry.counter("activity.events.recorded").count()).isEqualTo(1.0);
    }

    @Test
    void record_duplicate_returnsFalse() {
        when(workspaceRepository.existsById(1L)).thenReturn(true);
        when(eventRepository.insertIfAbsent(
                        any(UUID.class),
                        anyString(),
                        anyString(),
                        any(Instant.class),
                        any(),
                        eq(1L),
                        any(),
                        anyString(),
                        anyLong()))
                .thenReturn(0);

        boolean result = service.record(
                1L,
                ActivityEventType.PULL_REQUEST_OPENED,
                Instant.now(),
                null,
                null,
                ActivityTargetType.PULL_REQUEST,
                100L);

        assertThat(result).isFalse();
        assertThat(meterRegistry.counter("activity.events.recorded").count()).isEqualTo(0.0);
        assertThat(meterRegistry.counter("activity.events.duplicate").count()).isEqualTo(1.0);
    }

    @Test
    void record_workspaceNotFound_returnsFalse() {
        when(workspaceRepository.existsById(999L)).thenReturn(false);

        boolean result = service.record(
                999L,
                ActivityEventType.PULL_REQUEST_OPENED,
                Instant.now(),
                null,
                null,
                ActivityTargetType.PULL_REQUEST,
                100L);

        assertThat(result).isFalse();
        verifyNoInteractions(eventRepository);
    }
}
