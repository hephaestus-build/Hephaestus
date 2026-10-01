package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * Who may rewrite a shared project's rows from its own reading of GitLab: the workspace that owns the project's group
 * on its connected instance, and still monitors that very project.
 */
class GitLabWorkspaceLinkServiceTest extends BaseUnitTest {

    private static final String GROUP = "course/intro";
    private static final String PROJECT = GROUP + "/team-1/demo";
    private static final long PROVIDER_ID = 10L;
    private static final long OWNER = 1L;
    private static final long SECOND = 2L;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private WorkspaceActorSelector actorSelector;

    @Mock
    private IdentityProviderRepository identityProviderRepository;

    @Mock
    private RepositoryToMonitorRepository monitorRepository;

    private GitLabWorkspaceLinkService service;
    private Organization group;
    private Repository project;

    @BeforeEach
    void setUp() {
        service = new GitLabWorkspaceLinkService(
                workspaceRepository,
                organizationRepository,
                actorSelector,
                identityProviderRepository,
                monitorRepository);
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
        owner.setOrganization(group);
        Workspace second = workspace(SECOND);
        lenient().when(workspaceRepository.findById(OWNER)).thenReturn(Optional.of(owner));
        lenient().when(workspaceRepository.findById(SECOND)).thenReturn(Optional.of(second));
        lenient().when(workspaceRepository.existsByOrganizationId(5L)).thenReturn(true);
        lenient().when(actorSelector.connectedProviderId(OWNER)).thenReturn(Optional.of(PROVIDER_ID));
        lenient().when(actorSelector.connectedProviderId(SECOND)).thenReturn(Optional.of(PROVIDER_ID));
        lenient()
                .when(organizationRepository.findByLoginIgnoreCaseAndProviderId(GROUP, PROVIDER_ID))
                .thenReturn(Optional.of(group));
        lenient().when(identityProviderRepository.findById(PROVIDER_ID)).thenReturn(Optional.of(gitLab));
    }

    @Test
    void shouldLetOnlyTheGroupsOwnerRewriteAProjectBothMonitor() {
        when(monitorRepository.findByWorkspaceIdAndNativeId(OWNER, 700L)).thenReturn(List.of(monitor(700L)));
        when(monitorRepository.findByWorkspaceIdAndNativeId(SECOND, 700L)).thenReturn(List.of(monitor(700L)));

        assertThat(service.mayWriteRepository(OWNER, project)).isTrue();
        assertThat(service.mayWriteRepository(SECOND, project))
                .as("a second workspace on the same group reads it with its own, possibly narrower, credentials")
                .isFalse();
    }

    @Test
    void shouldNotTakeAMonitorOfAnotherProjectAtTheSamePathForThisOne() {
        when(monitorRepository.findByWorkspaceIdAndNativeId(OWNER, 700L)).thenReturn(List.of());
        when(monitorRepository.findByWorkspaceIdAndNameWithOwner(OWNER, PROJECT))
                .thenReturn(Optional.of(monitor(701L)));

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

        assertThat(second.getOrganization()).isSameAs(onOtherInstance);
    }

    @Test
    void shouldLinkNothingWithoutAnActiveConnection() {
        when(actorSelector.connectedProviderId(SECOND)).thenReturn(Optional.empty());
        Workspace second = workspace(SECOND);

        service.link(second);

        assertThat(second.getOrganization()).isNull();
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
