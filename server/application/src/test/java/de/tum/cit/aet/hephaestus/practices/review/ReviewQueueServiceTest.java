package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewPersonDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueueJob;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueueSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewQueueServiceTest extends BaseUnitTest {
    private static final long WORKSPACE = 1L;
    private static final long DEVELOPER = 7L;
    private final Instant now = Instant.parse("2026-11-15T10:00:00Z");
    private final ReviewRuntimeQuery runtime = mock(ReviewRuntimeQuery.class);
    private final ProductReferenceQuery references = mock(ProductReferenceQuery.class);
    private final ReviewProgressService progress = mock(ReviewProgressService.class);
    private final PerPersonResults perPerson = mock(PerPersonResults.class);
    private final ReviewQueueService service =
            new ReviewQueueService(runtime, references, Clock.fixed(now, ZoneOffset.UTC), progress, perPerson);

    @BeforeEach
    void queueOneReviewOfOnePersonsWork() {
        var job = new ReviewQueueJob(
                UUID.randomUUID(), UUID.randomUUID(), 5L, DEVELOPER, ReviewJobStatus.QUEUED, false, now);
        when(runtime.queue(WORKSPACE, now, ReviewQueueService.HEAD_SIZE))
                .thenReturn(new ReviewQueueSnapshot(now, 0, 1, now, 0, List.of(job), 0, 0));
        when(references.items(eq(WORKSPACE), anyCollection())).thenReturn(Map.of(5L, item()));
        // The names a read asks for, and only those: what it does not ask for it cannot show.
        when(references.developers(eq(WORKSPACE), anyCollection())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(1);
            return ids.stream()
                    .collect(Collectors.toMap(id -> id, id -> new ReviewPersonDTO(id.toString(), "Ada", null, "ada")));
        });
        when(progress.progress(anyLong(), any())).thenReturn(Map.of());
    }

    @Test
    void shouldNameWhoseWorkWaitsWhilePerPersonResultsAreOn() {
        when(perPerson.shown(WORKSPACE)).thenReturn(true);

        assertThat(service.queue(WORKSPACE).head().getFirst().person()).isNotNull();
    }

    @Test
    void shouldNameNobodyWhilePerPersonResultsAreOff() {
        when(perPerson.shown(WORKSPACE)).thenReturn(false);

        assertThat(service.queue(WORKSPACE).head().getFirst().person()).isNull();
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
