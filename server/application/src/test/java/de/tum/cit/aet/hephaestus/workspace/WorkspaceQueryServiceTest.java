package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicActivityPolicy;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceListItemDTO;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.security.core.context.SecurityContextHolder;

class WorkspaceQueryServiceTest extends BaseUnitTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private MembershipRepository workspaceMembershipRepository;

    @Mock
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private PublicActivityPolicy publicActivityPolicy;

    private WorkspaceQueryService newService() {
        return new WorkspaceQueryService(
                workspaceRepository,
                workspaceMembershipRepository,
                repositoryToMonitorRepository,
                connectionService,
                publicActivityPolicy,
                new WorkspaceProperties(false, null, false, null, WorkspaceProperties.CreationPolicy.ADMIN_ONLY),
                new WorkspaceSubdomainProperties(false, ""),
                "http://localhost:4200",
                List.of());
    }

    @Test
    void shouldSortAccessibleWorkspacesByDisplayName() {
        Workspace alphaWorkspace = workspace(1L, "alpha-space", "Alpha Workspace", true);
        Workspace bravoWorkspace = workspace(2L, "bravo-space", "Bravo Workspace", false);

        authenticate();

        WorkspaceQueryService service = newService();

        when(workspaceMembershipRepository.findActiveWorkspaceIdsByAccountId(42L))
                .thenReturn(List.of(2L, 1L));
        when(workspaceRepository.findAllById(List.of(2L, 1L))).thenReturn(List.of(bravoWorkspace, alphaWorkspace));

        List<Workspace> workspaces = service.findAccessibleWorkspaces();

        assertThat(workspaces).extracting(Workspace::getWorkspaceSlug).containsExactly("alpha-space", "bravo-space");
    }

    @Test
    void shouldReturnNoWorkspacesForAnonymousUsers() {
        WorkspaceQueryService service = newService();

        List<Workspace> workspaces = service.findAccessibleWorkspaces();

        assertThat(workspaces).isEmpty();
    }

    @Test
    void shouldKeepAccountMembershipWhenProviderMirrorsAreAbsent() {
        authenticate();
        var workspace = workspace(1L, "gh-space", "Workspace", false);
        when(workspaceMembershipRepository.findActiveWorkspaceIdsByAccountId(42L)).thenReturn(List.of(1L));
        when(workspaceRepository.findAllById(List.of(1L))).thenReturn(List.of(workspace));
        assertThat(newService().findAccessibleWorkspaces()).containsExactly(workspace);
    }

    private static void authenticate() {
        SecurityContextHolder.setContext(
                MockSecurityContextUtils.createSecurityContext("developer", "42", new String[0], "token"));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldNameTheProviderOfTheActiveConnection() {
        Workspace workspace = workspace(1L, "gh-space", "GitHub Workspace", false);
        when(connectionService.findActiveProviderKind(1L)).thenReturn(Optional.of(IntegrationKind.GITLAB));

        assertThat(newService().scmProviderType(workspace)).isEqualTo(IdentityProviderType.GITLAB);
    }

    @Test
    void shouldNameNoProviderWithoutAnActiveConnection() {
        Workspace workspace = workspace(1L, "gh-space", "GitHub Workspace", false);
        when(connectionService.findActiveProviderKind(1L)).thenReturn(Optional.empty());

        assertThat(newService().scmProviderType(workspace)).isNull();
    }

    @Test
    void shouldPublishPublicActivityOnlyWhenTheInstanceAllowsItAndAnActiveWorkspaceTurnedItOn() {
        Workspace enabled = workspace(1L, "open", "Open", true);
        Workspace off = workspace(2L, "off", "Off", false);
        Workspace suspended = workspace(3L, "suspended", "Suspended", true);
        suspended.setStatus(Workspace.WorkspaceStatus.SUSPENDED);

        assertThat(publishes(enabled, true)).isTrue();
        assertThat(publishes(enabled, false)).isFalse();
        assertThat(publishes(off, true)).isFalse();
        assertThat(publishes(suspended, true)).isFalse();
    }

    private boolean publishes(Workspace workspace, boolean instanceAllows) {
        return WorkspaceListItemDTO.from(
                        new WorkspaceListData(workspace.getId(), workspace.getWorkspaceSlug(), workspace.getDisplayName(),
                                workspace.getStatus(), workspace.getAccountLogin(), Instant.EPOCH,
                                workspace.getFeatures().getPracticesEnabled(), workspace.getPublicActivityEnabled()),
                        null, "/w/" + workspace.getWorkspaceSlug(), instanceAllows)
                .publishesPublicActivity();
    }

    private Workspace workspace(Long id, String slug, String displayName, boolean publiclyViewable) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName(displayName);
        workspace.setAccountLogin(slug);
        workspace.setPublicActivityEnabled(publiclyViewable);
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        return workspace;
    }

}
