package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class GitLabConnectionStateListenerTest extends BaseUnitTest {
    @Mock
    private GitLabWorkspaceInitializationService initService;

    @Mock
    private GitLabSuspendedWebhookCleanup cleanup;

    private GitLabConnectionStateListener listener;

    @BeforeEach
    void setUp() {
        listener = new GitLabConnectionStateListener(initService, cleanup);
    }

    @Test
    void shouldDispatchGitLabInitialization() {
        listener.onActivated(new ConnectionLifecycleEvent.Activated(42L, 5L, IntegrationKind.GITLAB));
        verify(initService).initializeAsync(5L);
        verifyNoInteractions(cleanup);
    }

    @Test
    void shouldIgnoreOtherAdapters() {
        listener.onActivated(new ConnectionLifecycleEvent.Activated(42L, 5L, IntegrationKind.GITHUB));
        listener.onDeactivated(new ConnectionLifecycleEvent.Deactivated(42L, 5L, IntegrationKind.SLACK));
        verifyNoInteractions(initService, cleanup);
    }

    @Test
    void shouldRemoveTheDeactivatedConnectionsHook() {
        listener.onDeactivated(new ConnectionLifecycleEvent.Deactivated(42L, 5L, IntegrationKind.GITLAB));
        verify(cleanup).remove(5L, 42L);
    }

    @Test
    void shouldLeaveFailedRemovalForRegistryRedelivery() {
        doThrow(new IllegalStateException("provider unavailable")).when(cleanup).remove(5L, 42L);
        assertThatThrownBy(() -> listener.onDeactivated(
                        new ConnectionLifecycleEvent.Deactivated(42L, 5L, IntegrationKind.GITLAB)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldLeaveInitializationDispatchFailuresToTheScheduledRepair() {
        doThrow(new IllegalStateException("provider unavailable"))
                .when(initService)
                .initializeAsync(5L);
        assertThatCode(() ->
                        listener.onActivated(new ConnectionLifecycleEvent.Activated(42L, 5L, IntegrationKind.GITLAB)))
                .doesNotThrowAnyException();
        verify(initService).initializeAsync(5L);
    }
}
