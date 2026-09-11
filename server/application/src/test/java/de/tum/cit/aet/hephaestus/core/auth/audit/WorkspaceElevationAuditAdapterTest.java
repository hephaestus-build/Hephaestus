package de.tum.cit.aet.hephaestus.core.auth.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class WorkspaceElevationAuditAdapterTest extends BaseUnitTest {

    @Test
    void shouldDeduplicateOnlyWithinTheSameAccountAndWorkspace() {
        AuthEventWriter writer = mock(AuthEventWriter.class);
        when(writer.write(any())).thenReturn(true);
        WorkspaceElevationAuditAdapter adapter = new WorkspaceElevationAuditAdapter(new AuthEventLogger(writer));

        adapter.recordElevatedAccess(42L, 7L);
        adapter.recordElevatedAccess(42L, 7L);
        adapter.recordElevatedAccess(42L, 8L);
        adapter.recordElevatedAccess(43L, 7L);

        verify(writer, times(3)).write(any());
    }

    @Test
    void shouldRecordAgainWhenTheFirstWriteWasLost() {
        AuthEventWriter writer = mock(AuthEventWriter.class);
        when(writer.write(any())).thenReturn(false, true);
        WorkspaceElevationAuditAdapter adapter = new WorkspaceElevationAuditAdapter(new AuthEventLogger(writer));

        adapter.recordElevatedAccess(42L, 7L);
        adapter.recordElevatedAccess(42L, 7L);
        adapter.recordElevatedAccess(42L, 7L);

        // A lost row must not claim the window: the second call retries, the third rides the write
        // that succeeded.
        verify(writer, times(2)).write(any());
    }
}
