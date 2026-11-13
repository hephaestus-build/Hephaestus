package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

class WorkspaceQueriesIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private WorkspaceSummaryQuery queries;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Test
    void shouldFindOnlyTheRequestedWorkspaceWhenItsSlugIsActive() {
        var owner = persistUser("query-owner");
        var requested = createWorkspace("query-requested", "Requested", "query-requested", AccountType.ORG, owner);
        createWorkspace("query-other", "Other", "query-other", AccountType.ORG, owner);

        assertThat(queries.findActiveIdBySlug(requested.getWorkspaceSlug())).contains(requested.getId());
        assertThat(queries.findActiveIdBySlug("query-missing")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = Workspace.WorkspaceStatus.class,
            names = {"SUSPENDED", "PURGED"})
    void shouldHideTheWorkspaceWhenItsStatusIsInactive(Workspace.WorkspaceStatus status) {
        var owner = persistUser("inactive-query-owner");
        var workspace = createWorkspace("inactive-query", "Inactive", "inactive-query", AccountType.ORG, owner);
        WorkspaceStatusFixture.withStatus(workspace, status);
        workspaceRepository.saveAndFlush(workspace);

        assertThat(queries.findActiveIdBySlug(workspace.getWorkspaceSlug())).isEmpty();
    }
}
