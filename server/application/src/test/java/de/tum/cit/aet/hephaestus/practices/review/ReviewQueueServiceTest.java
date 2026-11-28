package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueueJob;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueueSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewQueueServiceTest extends BaseUnitTest {
    private static final long WORKSPACE = 1L;
    private final Instant now = Instant.parse("2026-11-15T10:00:00Z");
    private final ReviewRuntimeQuery runtime = mock(ReviewRuntimeQuery.class);
    private final ProductReferenceQuery references = mock(ProductReferenceQuery.class);
    private final ReviewProgressService progress = mock(ReviewProgressService.class);
    private final ReviewQueueService service =
            new ReviewQueueService(runtime, references, Clock.fixed(now, ZoneOffset.UTC), progress);

    @BeforeEach
    void queueOneReviewOfOnePersonsWork() {
        var job = new ReviewQueueJob(UUID.randomUUID(), UUID.randomUUID(), 5L, ReviewJobStatus.QUEUED, false, now);
        when(runtime.queue(WORKSPACE, now, ReviewQueueService.HEAD_SIZE))
                .thenReturn(new ReviewQueueSnapshot(now, 0, 1, now, 0, List.of(job), 0, 0));
        when(references.items(eq(WORKSPACE), anyCollection())).thenReturn(Map.of(5L, item()));
        when(progress.progress(anyLong(), any())).thenReturn(Map.of());
    }

    /** ADR 0125: the queue shows the waiting work, never whose work it is. */
    @Test
    void shouldShowTheWaitingWorkAndReadNoPerson() {
        assertThat(service.queue(WORKSPACE).head().getFirst().item().url())
                .isEqualTo("https://example.test/project/pull/5");
        verify(references, never()).developers(anyLong(), anyCollection());
        verify(references, never()).accounts(anyLong(), anyCollection());
    }

    private static ItemRefDTO item() {
        return new ItemRefDTO(
                "5",
                "scm.pull_request",
                null,
                IntegrationKind.GITHUB,
                "#5",
                "A change",
                "https://example.test/project/pull/5",
                "example/project",
                null,
                null);
    }
}
