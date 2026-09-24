package de.tum.cit.aet.hephaestus.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The composite-key allowance against the real Hibernate mapping metamodel rather than a stubbed
 * one. {@link WorkspaceStatementInspectorTest} proves the rule reads a key correctly; this proves
 * the key it reads is the one Hibernate actually maps, which is what decides whether collaborator
 * sync runs in production.
 */
class TenancyCompositeKeyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WorkspaceScopedTables scopedTables;

    @Test
    void anEmbeddedIdAssociationReportsBothOfItsKeyColumns() {
        assertThat(scopedTables.primaryKeyColumns("repository_collaborator"))
                .containsExactlyInAnyOrder("repository_id", "user_id");
    }

    @Test
    void theStatementCollaboratorSyncEmitsIsAllowed() {
        // Verbatim from the production log that aborted `prompt-edu`'s collaborator sync.
        String sql = "update repository_collaborator set permission=? where repository_id=? and \"user_id\"=?";
        TenancyViolationReporter reporter = mock(TenancyViolationReporter.class);
        WorkspaceStatementInspector inspector =
                new WorkspaceStatementInspector(scopedTables, TenancyEnforcement.THROW, reporter, mock(Counter.class));

        inspector.inspect(sql);

        verifyNoInteractions(reporter);
    }

    @Test
    void aWhereClauseThatIsNotTheKeyIsStillReported() {
        // The allowance is the whole key and nothing else. A non-key column alongside a key column
        // is the shape this rule must not accept, and the metamodel is what settles it.
        //
        // Note `WHERE repository_id = ?` on its own is NOT the case to test here: the pre-existing
        // single-key rule already admits any `*_id = ?` predicate, which is how Hibernate's
        // cascade-delete-by-parent-FK is allowed.
        String sql = "update repository_collaborator set permission=? where repository_id=? and permission=?";
        TenancyViolationReporter reporter = mock(TenancyViolationReporter.class);
        WorkspaceStatementInspector inspector =
                new WorkspaceStatementInspector(scopedTables, TenancyEnforcement.LOG, reporter, mock(Counter.class));

        inspector.inspect(sql);

        org.mockito.Mockito.verify(reporter)
                .report(sql, java.util.Set.of("repository_collaborator"), TenancyEnforcement.LOG);
    }
}
