package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class WorkspaceScopeFilterTest {

    private static WorkspaceScopeFilter filter(Set<String> organizations, Set<String> repositories) {
        SyncSchedulerProperties properties = mock(SyncSchedulerProperties.class);
        when(properties.filters())
                .thenReturn(new SyncSchedulerProperties.FilterProperties(organizations, repositories, Set.of()));
        return new WorkspaceScopeFilter(properties);
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
