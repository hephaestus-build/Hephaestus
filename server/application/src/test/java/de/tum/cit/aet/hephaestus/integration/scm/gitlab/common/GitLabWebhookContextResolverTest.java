package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabAdmissionTestFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteTestFixtures;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A delivery resolves only on the route admission confirmed, and rechecks, before its second write, the repository as
 * stored now against that route. Without an admitted route it resolves nothing and writes nothing.
 */
@Tag("unit")
class GitLabWebhookContextResolverTest {

    private static final long REPOSITORY_ID = 7L;
    private static final long WORKSPACE_ID = 3L;
    private static final long PROVIDER_ID = 2L;
    private static final String PATH = "course/project";
    private static final GitLabRouteAdmission.AdmittedRoute ROUTE =
            new GitLabRouteAdmission.AdmittedRoute(1L, WORKSPACE_ID, PROVIDER_ID, 42L, "course");

    private final GitLabProjectAdmission projectAdmission = mock(GitLabProjectAdmission.class);
    private final RepositoryRepository repositories = mock(RepositoryRepository.class);
    private final GitLabRouteAdmission routeAdmission = mock(GitLabRouteAdmission.class);
    private final GitLabWebhookContextResolver resolver =
            new GitLabWebhookContextResolver(repositories, routeAdmission, projectAdmission);

    private final ProcessingContext resolved = ProcessingContext.forWebhook(WORKSPACE_ID, repository(PATH), "update");

    private GitLabProjectAdmission.ProjectRead originalPermit;

    @BeforeEach
    void storeTheRepositoryAsResolved() {
        originalPermit = GitLabAdmissionTestFixtures.permit(projectAdmission, WORKSPACE_ID, repository(PATH), 11L);
        when(repositories.findByNameWithOwnerAndProviderId(PATH, PROVIDER_ID))
                .thenReturn(Optional.of(repository(PATH)));
        when(repositories.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository(PATH)));
        when(routeAdmission.holdActive(ROUTE)).thenReturn(true);
        when(routeAdmission.admitRepository(eq(ROUTE), any()))
                .thenAnswer(invocation ->
                        PATH.equals(invocation.<Repository>getArgument(1).getNameWithOwner()));
    }

    @Test
    void shouldResolveTheAdmittedRoutesWorkspace() {
        ProcessingContext context = onRoute(() -> resolver.resolve(PATH, "update", "issue"));

        assertThat(context).isNotNull();
        assertThat(context.scopeId()).isEqualTo(WORKSPACE_ID);
    }

    @Test
    void shouldResolveNothingWithoutAnAdmittedRoute() {
        assertThat(resolver.resolve(PATH, "update", "issue")).isNull();
        assertThat(resolver.mayStillWrite(resolved, originalPermit)).isFalse();
    }

    @Test
    void shouldLetTheDeliveryWriteWhileItsRepositoryIsStillAdmitted() {
        assertThat(onRoute(() -> resolver.mayStillWrite(resolved, originalPermit)))
                .isTrue();
    }

    @Test
    void shouldNotLetTheDeliveryWriteOnceItsRepositoryIsGone() {
        when(repositories.findById(REPOSITORY_ID)).thenReturn(Optional.empty());

        assertThat(onRoute(() -> resolver.mayStillWrite(resolved, originalPermit)))
                .isFalse();
    }

    @Test
    void shouldNotLetTheDeliveryWriteOnceItsRepositoryMovedOutOfTheGroup() {
        when(repositories.findById(REPOSITORY_ID)).thenReturn(Optional.of(repository("elsewhere/project")));

        assertThat(onRoute(() -> resolver.mayStillWrite(resolved, originalPermit)))
                .isFalse();
    }

    @Test
    void shouldNotLetTheDeliveryWriteOnceTheConnectionIsNoLongerActive() {
        when(routeAdmission.holdActive(ROUTE)).thenReturn(false);

        assertThat(onRoute(() -> resolver.mayStillWrite(resolved, originalPermit)))
                .isFalse();
    }

    private static <T> @Nullable T onRoute(Supplier<@Nullable T> call) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        GitLabRouteTestFixtures.handle(ROUTE, Map.of(), null, () -> result.set(call.get()));
        return result.get();
    }

    private static Repository repository(String nameWithOwner) {
        Repository repository = new Repository();
        repository.setId(REPOSITORY_ID);
        repository.setNameWithOwner(nameWithOwner);
        IdentityProvider provider = new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example.com");
        provider.setId(PROVIDER_ID);
        repository.setProvider(provider);
        return repository;
    }
}
