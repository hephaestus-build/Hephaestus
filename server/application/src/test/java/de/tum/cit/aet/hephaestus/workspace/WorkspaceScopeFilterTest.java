package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class WorkspaceScopeFilterTest {

    private static WorkspaceScopeFilter filter(Set<String> organizations, Set<String> repositories) {
        SyncSchedulerProperties properties = mock(SyncSchedulerProperties.class);
        when(properties.filters())
                .thenReturn(new SyncSchedulerProperties.FilterProperties(organizations, repositories));
        return new WorkspaceScopeFilter(properties, List.of());
    }

    private static WorkspaceScopeFilter excluding(String repository) {
        SyncSchedulerProperties properties = mock(SyncSchedulerProperties.class);
        when(properties.filters()).thenReturn(new SyncSchedulerProperties.FilterProperties(Set.of(), Set.of()));
        return new WorkspaceScopeFilter(properties, List.of(repository::equalsIgnoreCase));
    }

    @Test
    void shouldRefuseAnExcludedRepositoryInAnyCaseWhileEveryOtherRepositoryStaysAllowed() {
        WorkspaceScopeFilter filter = excluding("acme/reports");

        assertThat(filter.isActive()).isFalse();
        assertThat(filter.isGitHubRepositoryAllowed("Acme/Reports")).isFalse();
        assertThat(filter.isExcluded("acme/reports")).isTrue();
        assertThat(filter.isGitHubRepositoryAllowed("acme/product")).isTrue();
        // A GitLab project on the same path is another repository, so the allow-lists alone decide.
        assertThat(filter.isRepositoryAllowed("acme/reports")).isTrue();
    }

    private static Workspace workspace(String accountLogin) {
        Workspace workspace = new Workspace();
        workspace.setAccountLogin(accountLogin);
        return workspace;
    }

    @Test
    void shouldAllowRepositoriesOfAnAllowedNestedGroupWithoutAllowingItsSiblings() {
        WorkspaceScopeFilter filter = filter(Set.of("hephaestustest/introcourse"), Set.of());

        assertThat(filter.isRepositoryAllowed(
                        workspace("hephaestustest/introcourse"), "hephaestustest/introcourse/team-1/project"))
                .isTrue();
        assertThat(filter.isRepositoryAllowed(
                        workspace("hephaestustest/other-course"), "hephaestustest/other-course/project"))
                .isFalse();
    }

    @Test
    void shouldStillRequireTheExactRepositoryWhenRepositoriesAreFiltered() {
        WorkspaceScopeFilter filter = filter(Set.of(), Set.of("hephaestustest/introcourse/allowed"));
        Workspace workspace = workspace("hephaestustest/introcourse");

        assertThat(filter.isRepositoryAllowed(workspace, "hephaestustest/introcourse/allowed"))
                .isTrue();
        assertThat(filter.isRepositoryAllowed(workspace, "hephaestustest/introcourse/other"))
                .isFalse();
    }
}
