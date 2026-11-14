package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class WorkspaceQueriesIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private WorkspaceSummaryQuery queries;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void shouldFindOnlyTheRequestedWorkspaceWhenItsSlugIsActive() {
        var owner = persistUser("query-owner");
        var requested = createWorkspace("query-requested", "Requested", "query-requested", AccountType.ORG, owner);
        createWorkspace("query-other", "Other", "query-other", AccountType.ORG, owner);

        assertThat(queries.findActiveIdBySlug(requested.getWorkspaceSlug())).contains(requested.getId());
        assertThat(queries.findActiveIdBySlug("query-missing")).isEmpty();
        assertThat(queries.findState(requested.getId()))
                .contains(new WorkspaceSummaryQuery.WorkspaceState(
                        requested.getId(), requested.getWorkspaceSlug(), "ACTIVE", true));
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
        assertThat(queries.findState(workspace.getId()))
                .contains(new WorkspaceSummaryQuery.WorkspaceState(
                        workspace.getId(), workspace.getWorkspaceSlug(), status.name(), false));
    }

    @Test
    void shouldRequireATransactionWhenWorkspaceStateIsLocked() {
        var owner = persistUser("locked-query-owner");
        var workspace = createWorkspace("locked-query", "Locked", "locked-query", AccountType.ORG, owner);

        assertThatThrownBy(() -> queries.findStateForUpdate(workspace.getId()))
                .isInstanceOf(IllegalTransactionStateException.class);
        var state = transactions.execute(transaction -> queries.findStateForUpdate(workspace.getId()));
        assertThat(state).isEqualTo(queries.findState(workspace.getId()));
    }
}
