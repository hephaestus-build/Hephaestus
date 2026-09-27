package de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWebhookService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.ScmWorkspaceContentEraser;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

/**
 * Pins GitLab's disconnect wiring. Disconnect is GitLab's ONLY erase trigger (a PAT has no vendor-side
 * uninstall signal), so a regression here means GitLab-mirrored data becomes unerasable short of manual
 * SQL.
 */
class GitlabConnectionStrategyTest extends BaseUnitTest {

    @Mock
    private GitLabWebhookService webhookService;

    @Mock
    private ScmWorkspaceContentEraser contentEraser;

    @InjectMocks
    private GitlabConnectionStrategy strategy;

    @Test
    void eraseLocalData_erasesTheScmMirrorWithoutCallingGitLab() {
        strategy.eraseLocalData(new IntegrationRef(IntegrationKind.GITLAB, 11L, "group-99", 7L));

        verify(contentEraser).eraseWorkspaceScmMirror(11L);
        verifyNoInteractions(webhookService);
    }

    @Test
    void revokeProvider_onlyDeregistersTheConnectionsWebhook() {
        strategy.revokeProvider(new IntegrationRef(IntegrationKind.GITLAB, 11L, "group-99", 7L));

        verify(webhookService).deregisterWebhookForConnectionStrict(11L, 7L);
        verifyNoInteractions(contentEraser);
    }

    @Test
    void revokeProvider_propagatesProviderFailure() {
        doThrow(new RuntimeException("gitlab unavailable"))
                .when(webhookService)
                .deregisterWebhookForConnectionStrict(11L, 7L);

        assertThatThrownBy(
                        () -> strategy.revokeProvider(new IntegrationRef(IntegrationKind.GITLAB, 11L, "group-99", 7L)))
                .hasMessage("gitlab unavailable");
    }
}
