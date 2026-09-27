package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.collaborator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaboratorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceLinkService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class GitLabCollaboratorSyncServiceTest extends BaseUnitTest {

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private RepositoryCollaboratorRepository collaboratorRepository;

    @Mock
    private GitLabWorkspaceLinkService workspaceLinkService;

    @Test
    void shouldLeaveASharedProjectsCollaboratorsToTheWorkspaceThatOwnsIt() {
        var service = new GitLabCollaboratorSyncService(
                mock(RepositoryRepository.class),
                collaboratorRepository,
                graphQlClientProvider,
                mock(GitLabGraphQlResponseHandler.class),
                mock(GitLabUserService.class),
                mock(GitLabProperties.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                workspaceLinkService);
        Repository project = new Repository();
        project.setNameWithOwner("course/intro/team-1/demo");
        project.setProvider(TestEntities.gitProvider(10L, IdentityProviderType.GITLAB));
        when(workspaceLinkService.mayWriteRepository(2L, project)).thenReturn(false);

        var result = service.syncCollaboratorsForRepository(2L, project);

        assertThat(result.isCompleted())
                .as("a reading this scope may not apply is not a complete answer")
                .isFalse();
        verifyNoInteractions(graphQlClientProvider, collaboratorRepository);
    }
}
