package de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWebhookService;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.ScmWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import java.util.Set;
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

    @Test
    void prepareProviderTeardown_deletesTheRetainedWebhookWithTheRetainedToken() {
        Runnable deletion = () -> {};
        var config = new ConnectionConfig.GitLabConfig(
                "https://gitlab.example", 99L, 5L, Set.of(), null, ConnectionConfig.GitLabProjectExclusions.empty());
        var token = new BearerToken("glpat-retained", null);
        when(webhookService.webhookDeregistration(11L, 7L, config, token)).thenReturn(Optional.of(deletion));

        assertThat(strategy.prepareProviderTeardown(
                        new IntegrationRef(IntegrationKind.GITLAB, 11L, "group-99", 7L),
                        new ConnectionStrategy.ProviderFootprint(config, token)))
                .containsSame(deletion);
        verifyNoInteractions(contentEraser);
    }
}
