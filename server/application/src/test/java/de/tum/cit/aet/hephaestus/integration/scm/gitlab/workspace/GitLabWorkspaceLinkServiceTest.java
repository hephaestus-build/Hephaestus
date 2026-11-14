package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLookup;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceProviderAccounts;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRoots;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class GitLabWorkspaceLinkServiceTest extends BaseUnitTest {

    private static final String GROUP = "course/intro";
    private static final String PROJECT = GROUP + "/team-1/demo";
    private static final long PROVIDER_ID = 10L;
    private static final long OWNER = 1L;
    private static final long SECOND = 2L;

    @Mock
    private WorkspaceRoots workspaces;

    @Mock
    private WorkspaceLookup workspaceLookup;

    @Mock
    private WorkspaceProviderAccounts providerAccounts;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private WorkspaceActorSelector actorSelector;

    @Mock
    private SourceProviderNamespaces providerNamespaces;

    @Mock
    private WorkspaceRepositoryMonitors monitors;

    private GitLabWorkspaceLinkService service;
    private Organization group;
    private Repository project;

    @BeforeEach
    void setUp() {
        service = new GitLabWorkspaceLinkService(
                workspaces,
                workspaceLookup,
                providerAccounts,
                organizationRepository,
                actorSelector,
                providerNamespaces,
                monitors);
        IdentityProvider gitLab = TestEntities.gitProvider(PROVIDER_ID, IdentityProviderType.GITLAB);
        group = new Organization();
        group.setId(5L);
        group.setLogin(GROUP);
        group.setProvider(gitLab);
        project = new Repository();
        project.setNativeId(700L);
        project.setNameWithOwner(PROJECT);
        project.setProvider(gitLab);

        Workspace owner = workspace(OWNER);
        owner.setOrganizationId(group.getId());
        Workspace second = workspace(SECOND);
        lenient().when(workspaceLookup.find(OWNER)).thenReturn(Optional.of(owner));
        lenient().when(workspaceLookup.find(SECOND)).thenReturn(Optional.of(second));
        lenient().when(providerAccounts.organizationBound(5L)).thenReturn(true);
        lenient().when(actorSelector.connectedProviderId(OWNER)).thenReturn(Optional.of(PROVIDER_ID));
        lenient().when(actorSelector.connectedProviderId(SECOND)).thenReturn(Optional.of(PROVIDER_ID));
        lenient()
                .when(organizationRepository.findByLoginIgnoreCaseAndProviderId(GROUP, PROVIDER_ID))
                .thenReturn(Optional.of(group));
        lenient().when(providerNamespaces.findForMirror(PROVIDER_ID)).thenReturn(Optional.of(gitLab));
    }

    @Test
    void shouldLetOnlyTheGroupsOwnerRewriteAProjectBothMonitor() {
        when(organizationRepository.findById(5L)).thenReturn(Optional.of(group));
        when(monitors.monitorsOf(OWNER, 700L)).thenReturn(List.of(monitor(700L)));
        when(monitors.monitorsOf(SECOND, 700L)).thenReturn(List.of(monitor(700L)));

        assertThat(service.mayWriteRepository(OWNER, project)).isTrue();
        assertThat(service.mayWriteRepository(SECOND, project))
                .as("a second workspace on the same group reads it with its own, possibly" + " narrower, credentials")
                .isFalse();
    }

    @Test
    void shouldRefuseARepositoryWithoutAMonitorForItsProviderId() {
        when(monitors.monitorsOf(OWNER, 700L)).thenReturn(List.of());

        assertThat(service.mayWriteRepository(OWNER, project)).isFalse();
    }

    @Test
    void shouldLinkTheGroupOnTheConnectedInstanceWhenAnotherInstanceHasTheSamePath() {
        Organization onOtherInstance = new Organization();
        onOtherInstance.setId(6L);
        onOtherInstance.setLogin(GROUP);
        onOtherInstance.setProvider(TestEntities.gitProvider(PROVIDER_ID + 1, IdentityProviderType.GITLAB));
        when(actorSelector.connectedProviderId(SECOND)).thenReturn(Optional.of(PROVIDER_ID + 1));
        when(organizationRepository.findByLoginIgnoreCaseAndProviderId(GROUP, PROVIDER_ID + 1))
                .thenReturn(Optional.of(onOtherInstance));
        Workspace second = workspace(SECOND);

        service.link(second);

        assertThat(second.getOrganizationId()).isEqualTo(onOtherInstance.getId());
    }

    @Test
    void shouldLinkNothingWithoutAnActiveConnection() {
        when(actorSelector.connectedProviderId(SECOND)).thenReturn(Optional.empty());
        Workspace second = workspace(SECOND);

        service.link(second);

        assertThat(second.getOrganizationId()).isNull();
        verify(organizationRepository, never()).findByLoginIgnoreCaseAndProviderId(any(), anyLong());
    }

    private static Workspace workspace(long id) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setAccountLogin(GROUP);
        return workspace;
    }

    private static RepositoryToMonitor monitor(long nativeId) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setNameWithOwner(PROJECT);
        monitor.setNativeId(nativeId);
        return monitor;
    }
}
