package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A delivery that came through no connection route rechecks, before its second write, the repository as stored now
 * against the same scope filter and workspace resolution it was resolved with. The connection route's recheck needs an
 * admitted delivery and is proven end to end in {@code GitLabWorkspaceEventRoutingIntegrationTest}.
 */
@Tag("unit")
class GitLabWebhookContextResolverTest {

    private static final long REPOSITORY_ID = 7L;
    private static final long WORKSPACE_ID = 3L;
    private static final String PATH = "course/project";

    private final RepositoryRepository repositories = mock(RepositoryRepository.class);
    private final RepositoryScopeFilter scopeFilter = mock(RepositoryScopeFilter.class);
    private final ScopeIdResolver scopes = mock(ScopeIdResolver.class);
    private final GitLabWebhookContextResolver resolver =
            new GitLabWebhookContextResolver(repositories, scopeFilter, scopes, mock(GitLabRouteAdmission.class));

    private final ProcessingContext resolved = ProcessingContext.forWebhook(WORKSPACE_ID, repository(PATH), "update");

    @BeforeEach
    void storeTheRepositoryAsResolved() {
        when(repositories.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository(PATH)));
        when(scopeFilter.isRepositoryAllowed(PATH)).thenReturn(true);
        when(scopes.findScopeIdByRepositoryName(PATH)).thenReturn(Optional.of(WORKSPACE_ID));
    }

    @Test
    void shouldLetTheDeliveryWriteWhileItsRepositoryIsStillInScopeForTheSameWorkspace() {
        assertThat(resolver.mayStillWrite(resolved)).isTrue();
    }

    @Test
    void shouldNotLetTheDeliveryWriteOnceItsRepositoryIsGone() {
        when(repositories.findById(REPOSITORY_ID)).thenReturn(Optional.empty());

        assertThat(resolver.mayStillWrite(resolved)).isFalse();
    }

    @Test
    void shouldNotLetTheDeliveryWriteOnceItsRepositoryMovedOutOfScope() {
        when(repositories.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository("elsewhere/project")));

        assertThat(resolver.mayStillWrite(resolved)).isFalse();
    }

    @Test
    void shouldNotLetTheDeliveryWriteOnceItsRepositoryBelongsToAnotherWorkspace() {
        when(scopes.findScopeIdByRepositoryName(PATH)).thenReturn(Optional.of(WORKSPACE_ID + 1));

        assertThat(resolver.mayStillWrite(resolved)).isFalse();
    }

    private static Repository repository(String nameWithOwner) {
        Repository repository = new Repository();
        repository.setId(REPOSITORY_ID);
        repository.setNameWithOwner(nameWithOwner);
        repository.setProvider(new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example.com"));
        return repository;
    }
}
