package de.tum.cit.aet.hephaestus.core.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WorkspaceElevationAuditIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private WorkspaceElevationAuditAdapter elevationAudit;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private AuthEventRepository events;

    @Test
    void shouldRecordANewAccessWindowWhenDatabaseCleanupCreatesDifferentAccountsAndWorkspaces() {
        databaseTestUtils.cleanDatabase();
        long firstAccount = Objects.requireNonNull(
                accounts.save(new Account("First administrator")).getId());
        long firstWorkspace = Objects.requireNonNull(workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("first-window"))
                .getId());
        elevationAudit.recordElevatedAccess(firstAccount, firstWorkspace);
        assertThat(events.findByAccountSince(firstAccount, Instant.EPOCH)).hasSize(1);

        databaseTestUtils.cleanDatabase();
        long secondAccount = Objects.requireNonNull(
                accounts.save(new Account("Second administrator")).getId());
        long secondWorkspace = Objects.requireNonNull(workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("second-window"))
                .getId());
        assertThat(secondAccount).isNotEqualTo(firstAccount);
        assertThat(secondWorkspace).isNotEqualTo(firstWorkspace);
        elevationAudit.recordElevatedAccess(secondAccount, secondWorkspace);
        elevationAudit.recordElevatedAccess(secondAccount, secondWorkspace);

        assertThat(events.findByAccountSince(secondAccount, Instant.EPOCH))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo(AuthEvent.EventType.WORKSPACE_ELEVATION);
                    assertThat(event.getWorkspaceId()).isEqualTo(secondWorkspace);
                });
    }
}
