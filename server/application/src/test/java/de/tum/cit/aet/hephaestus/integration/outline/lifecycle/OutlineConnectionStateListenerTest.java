package de.tum.cit.aet.hephaestus.integration.outline.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.outline.sync.OutlineDocumentSyncScheduler;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class OutlineConnectionStateListenerTest extends BaseUnitTest {

    @Mock
    private OutlineWebhookRegistrar webhookRegistrar;

    @Mock
    private OutlineDocumentSyncScheduler syncScheduler;

    @Mock
    private SyncJobService syncJobService;

    @InjectMocks
    private OutlineConnectionStateListener listener;

    @SuppressWarnings("unchecked")
    private void runJobsSynchronously(SyncJobHandle handle) {
        // SyncJobService always passes a non-null handle (started.handle()); the body reads it to honor cancel.
        doAnswer(invocation -> {
                    Consumer<SyncJobHandle> body = invocation.getArgument(1);
                    body.accept(handle);
                    return null;
                })
                .when(syncJobService)
                .run(any(SyncJobRequest.class), any());
    }

    @Test
    void outlineActivation_registersSubscriptionAndRecordsInitialLifecycleJob() {
        SyncJobHandle handle = mock(SyncJobHandle.class);
        when(handle.isCancellationRequested()).thenReturn(false);
        runJobsSynchronously(handle);

        listener.onActivated(new ConnectionLifecycleEvent.Activated(42L, 5L, IntegrationKind.OUTLINE));

        verify(webhookRegistrar).ensureSubscription(5L);
        ArgumentCaptor<SyncJobRequest> requestCaptor = ArgumentCaptor.forClass(SyncJobRequest.class);
        verify(syncJobService).run(requestCaptor.capture(), any());
        SyncJobRequest request = requestCaptor.getValue();
        assertThat(request.workspaceId()).isEqualTo(5L);
        assertThat(request.connectionId()).isEqualTo(42L);
        assertThat(request.kind()).isEqualTo(IntegrationKind.OUTLINE);
        assertThat(request.type()).isEqualTo(SyncJobType.INITIAL);
        assertThat(request.trigger()).isEqualTo(SyncJobTrigger.LIFECYCLE);
        verify(syncScheduler).syncWorkspaceNow(eq(5L), any(SyncExecutionHandle.class), eq(SyncJobType.INITIAL));
        // Not cancelled → the job records its natural outcome, never CANCELLED.
        verify(handle, never()).reportCancelled();
    }

    @Test
    void outlineInitialSync_cancelled_reportsCancelledSoJobIsNotMislabeledSucceeded() {
        SyncJobHandle handle = mock(SyncJobHandle.class);
        when(handle.isCancellationRequested()).thenReturn(true);
        runJobsSynchronously(handle);

        listener.onActivated(new ConnectionLifecycleEvent.Activated(42L, 5L, IntegrationKind.OUTLINE));

        verify(syncScheduler).syncWorkspaceNow(eq(5L), any(SyncExecutionHandle.class), eq(SyncJobType.INITIAL));
        verify(handle).reportCancelled();
    }

    @Test
    void nonOutlineActivation_isIgnored() {
        listener.onActivated(new ConnectionLifecycleEvent.Activated(42L, 5L, IntegrationKind.GITHUB));

        verifyNoInteractions(webhookRegistrar, syncScheduler, syncJobService);
    }

    @Test
    void outlineActivation_jobAlreadyActive_isSkippedWithoutPropagating() {
        SyncJob activeJob = mock(SyncJob.class);
        Connection activeConnection = mock(Connection.class);
        when(activeConnection.getId()).thenReturn(42L);
        when(activeJob.getConnection()).thenReturn(activeConnection);
        when(activeJob.getId()).thenReturn(99L);
        doThrow(new SyncJobConflictException(activeJob)).when(syncJobService).run(any(), any());

        assertThatCode(() ->
                        listener.onActivated(new ConnectionLifecycleEvent.Activated(42L, 5L, IntegrationKind.OUTLINE)))
                .doesNotThrowAnyException();
        verify(syncScheduler, never())
                .syncWorkspaceNow(eq(5L), any(SyncExecutionHandle.class), eq(SyncJobType.INITIAL));
    }

    @Test
    void connectTimeFailure_neverPropagatesOffTheAsyncThread() {
        doThrow(new IllegalStateException("outline is down"))
                .when(webhookRegistrar)
                .ensureSubscription(5L);

        assertThatCode(() ->
                        listener.onActivated(new ConnectionLifecycleEvent.Activated(42L, 5L, IntegrationKind.OUTLINE)))
                .doesNotThrowAnyException();
        verify(syncJobService, times(0)).run(any(), any());
    }
}
