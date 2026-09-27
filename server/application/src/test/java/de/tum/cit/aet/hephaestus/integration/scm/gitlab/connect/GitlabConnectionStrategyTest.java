package de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWebhookService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.ScmWorkspaceContentEraser;
import java.util.Optional;
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
    void prepareProviderTeardown_isTheConnectionsWebhookDeregistration() {
        Runnable deletion = () -> {};
        when(webhookService.prepareWebhookDeregistration(11L, 7L)).thenReturn(Optional.of(deletion));

        assertThat(strategy.prepareProviderTeardown(new IntegrationRef(IntegrationKind.GITLAB, 11L, "group-99", 7L)))
                .containsSame(deletion);
        verifyNoInteractions(contentEraser);
    }
}
