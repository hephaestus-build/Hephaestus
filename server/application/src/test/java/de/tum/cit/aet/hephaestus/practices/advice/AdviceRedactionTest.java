package de.tum.cit.aet.hephaestus.practices.advice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceErasureListener;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class AdviceRedactionTest extends BaseUnitTest {
    private final ResultRepository results = mock(ResultRepository.class);
    private final PracticeAdviceRevisionRepository revisions = mock(PracticeAdviceRevisionRepository.class);
    private final AdviceErasureListener listener = mock(AdviceErasureListener.class);

    @Test
    void shouldLockTheResultsThenRedactAndReportTheRevisionsThatLostTheirText() {
        UUID result = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        when(revisions.lockWithSourcesIn(anyLong(), any())).thenReturn(List.of(revision));

        var stale = redaction(listener).beforeResultsDeleted(7L, List.of(result, result));

        assertThat(stale).containsExactly(revision);
        verify(results).lockInIdOrder(7L, new UUID[] {result});
        verify(revisions).redact(7L, new UUID[] {revision});
        verify(listener).staleRevisions(7L, List.of(revision));
    }

    @Test
    void shouldWriteNothingWhenNoRevisionHasTheResultsAsSources() {
        when(revisions.lockWithSourcesIn(anyLong(), any())).thenReturn(List.of());

        assertThat(redaction(listener).beforeResultsDeleted(7L, List.of(UUID.randomUUID())))
                .isEmpty();

        verify(revisions, never()).redact(anyLong(), any());
        verify(listener, never()).staleRevisions(anyLong(), any());
    }

    @Test
    void shouldRedactWithoutAListener() {
        UUID revision = UUID.randomUUID();
        when(revisions.lockWithSourcesIn(anyLong(), any())).thenReturn(List.of(revision));

        assertThat(redaction(null).beforeResultsDeleted(7L, List.of(UUID.randomUUID())))
                .containsExactly(revision);

        verify(revisions).redact(7L, new UUID[] {revision});
    }

    private AdviceRedaction redaction(@Nullable AdviceErasureListener present) {
        return new AdviceRedaction(results, revisions, present == null ? List.of() : List.of(present));
    }
}
