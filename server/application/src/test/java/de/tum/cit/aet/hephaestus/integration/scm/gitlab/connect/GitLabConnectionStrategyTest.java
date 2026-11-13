package de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWebhookService;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.ScmWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

// A GitLab PAT has no provider uninstall event; disconnect must erase the mirrored content.
class GitLabConnectionStrategyTest extends BaseUnitTest {

    @Mock
    private GitLabWebhookService webhookService;

    @Mock
    private ScmWorkspaceContentEraser contentEraser;

    @InjectMocks
    private GitLabConnectionStrategy strategy;

    @Test
    void eraseLocalData_erasesTheScmMirrorWithoutCallingGitLab() {
        strategy.eraseLocalData(new IntegrationRef(IntegrationKind.GITLAB, 11L, "group-99", 7L));

        verify(contentEraser).eraseWorkspaceScmMirror(11L);
        verifyNoInteractions(webhookService);
    }
}
