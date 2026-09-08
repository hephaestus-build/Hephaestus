package de.tum.cit.aet.hephaestus.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.Counter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class WorkspaceStatementInspectorTest extends BaseUnitTest {

    private final WorkspaceScopedTables scopedTables = mock(WorkspaceScopedTables.class);
    private final TenancyViolationReporter reporter = mock(TenancyViolationReporter.class);
    private final Counter parseFailureCounter = mock(Counter.class);

    @AfterEach
    void clearBypass() {
        // Fail loud if a test leaked ThreadLocal bypass state (BaseUnitTest does not fork): a bypass is
        // only opened via try-with-resources, so an active bypass here means someone forgot to close it.
        if (TenancyBypass.isActive()) {
            throw new IllegalStateException("TenancyBypass not closed by prior test");
        }
    }

    private WorkspaceStatementInspector newInspector(TenancyEnforcement mode) {
        return new WorkspaceStatementInspector(scopedTables, mode, reporter, parseFailureCounter);
    }

    // short-circuits

    @Test
    void offModeSkipsEverything() {
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.OFF);
        inspector.inspect("SELECT * FROM pull_request");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void bypassActiveSkipsEverything() {
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        try (TenancyBypass.Scope ignored = TenancyBypass.open("test")) {
            inspector.inspect("SELECT * FROM pull_request");
        }
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void blankSqlIsIgnored() {
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect(null);
        inspector.inspect("");
        inspector.inspect("   ");
        verifyNoInteractions(reporter, scopedTables);
    }

    // regex fast-path

    @Test
    void predicateMatchShortCircuitsParser() {
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect("SELECT * FROM pull_request WHERE workspace_id = ?");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void inPredicateMatchShortCircuits() {
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect("SELECT * FROM pull_request WHERE workspace_id IN (?, ?)");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void compositeKeyTupleInPredicateShortCircuits() {
        // Hibernate emits composite-key lookups as
        //   (col1, workspace_id) IN ((?, ?))
        // The inspector MUST treat this as a legitimate workspace_id reference and not
        // throw. Regression for false positive that broke ObservationControllerIntegrationTest.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        String sql = "select wm.user_id, wm.workspace_id from workspace_membership wm "
                + "where (wm.user_id, wm.workspace_id) in ((?,?))";
        inspector.inspect(sql);
        verifyNoInteractions(reporter, scopedTables);
    }

    // table extraction

    @Test
    void unguardedScopedTableIsReported() {
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.LOG);
        when(scopedTables.isScoped("pull_request")).thenReturn(true);
        inspector.inspect("SELECT * FROM pull_request");
        verify(reporter).report("SELECT * FROM pull_request", Set.of("pull_request"), TenancyEnforcement.LOG);
    }

    @Test
    void globalTableQueryIsNotReported() {
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.LOG);
        when(scopedTables.isScoped("user")).thenReturn(false);
        inspector.inspect("SELECT * FROM \"user\"");
        verify(reporter, never()).report(any(), any(), any());
    }

    @Test
    void schemaQualifiedTableIsUnqualifiedBeforeLookup() {
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.LOG);
        when(scopedTables.isScoped("pull_request")).thenReturn(true);
        inspector.inspect("SELECT * FROM public.pull_request");
        verify(reporter).report("SELECT * FROM public.pull_request", Set.of("pull_request"), TenancyEnforcement.LOG);
    }

    @Test
    void backslashEscapedPostgresCastDoesNotPropagate() {
        // Regression: @Query native queries can contain Postgres casts like
        // CONCAT(:id\:\:text, ...). The inspector MUST NOT propagate exceptions on those —
        // would brick UserRepository.tryAcquireLoginLock and the whole context.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        String sql = "SELECT pg_try_advisory_xact_lock(hashtext(CONCAT(?\\:\\:text, ':', LOWER(?))))";
        String returned = inspector.inspect(sql);
        assertThat(returned).isEqualTo(sql);
        verify(reporter, never()).report(any(), any(), any());
    }

    // DML semantics

    @Test
    void insertOnScopedTableIsAllowed() {
        // INSERTs cannot leak existing data across workspaces. The workspace_id (or FK
        // chain) is placed into the row by application code, not enforced by the inspector.
        // Regression: observation/reaction inserts emitted at Hibernate flush
        // time triggered TenancyViolationException despite being safe by construction.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect("insert into observation (id, title, practice_id) values (?, ?, ?)");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void pkOnlyDeleteOnScopedTableIsAllowed() {
        // Hibernate emits delete-by-id for delete(entity)/deleteById(id). The row was
        // loaded into the persistence context within a workspace-checked transaction;
        // requiring an additional workspace_id predicate would force @WorkspaceAgnostic
        // onto every repository.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect("delete from practice where id=?");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void pkOnlyUpdateOnScopedTableIsAllowed() {
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect("update practice set name=?, updated_at=? where id=?");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void deleteJoinTableByParentFkIsAllowed() {
        // @ManyToMany cascade-delete on the join table when a parent is removed:
        // Hibernate emits DELETE FROM join_table WHERE <parent>_id = ?. Safe — the caller
        // already had the parent's PK (workspace-scoped at retrieval).
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect("delete from issue_blocking where blocked_issue_id=?");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void deleteManyToManyJoinTableRowByBothKeysIsAllowed() {
        // Removing one element of a @ManyToMany: Hibernate deletes the join-table row by both
        // foreign keys. The table is known from the mapping metamodel, not from its name.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        when(scopedTables.isManyToManyJoinTable("issue_label")).thenReturn(true);
        inspector.inspect("delete from issue_label where issue_id=? and label_id=?");
        inspector.inspect("DELETE FROM \"issue_label\" WHERE \"issue_id\"=? AND \"label_id\"=?");
        verifyNoInteractions(reporter);
    }

    @Test
    void twoForeignKeysOnAnEntityTableStillRequireWorkspaceId() {
        // feedback.id is the key; recipient_user_id and about_user_id are ordinary columns, so
        // this shape must not pass on the strength of two *_id names.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.LOG);
        when(scopedTables.isScoped("feedback")).thenReturn(true);
        String sql = "delete from feedback where recipient_user_id=? and about_user_id=?";
        inspector.inspect(sql);
        verify(reporter).report(sql, Set.of("feedback"), TenancyEnforcement.LOG);
    }

    @Test
    void joinTableAllowanceIsDeleteByBothKeysAndNothingElse() {
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.LOG);
        when(scopedTables.isScoped("issue_label")).thenReturn(true);
        when(scopedTables.isManyToManyJoinTable("issue_label")).thenReturn(true);
        for (String sql : List.of(
                "delete from issue_label where issue_id=? or label_id=?",
                "delete from issue_label where issue_id=? and name=?",
                "update issue_label set label_id=? where issue_id=? and label_id=?",
                "delete from issue_label where issue_id=? and label_id=? and version=?")) {
            inspector.inspect(sql);
            verify(reporter).report(sql, Set.of("issue_label"), TenancyEnforcement.LOG);
        }
    }

    @Test
    void optimisticLockUpdateIsAllowed() {
        // Hibernate appends "AND version = ?" to UPDATEs for entities with @Version.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect(
                "update chat_message set metadata=?,parts=?,role=?,status=?,thread_id=?,version=? where id=? and version=?");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void deleteWithExtraPredicateStillRequiresWorkspaceId() {
        // The key-only allowance covers conjunctions of keys, never a non-key column: that is a
        // hand-written query where we still want the workspace_id discipline.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.LOG);
        when(scopedTables.isScoped("practice")).thenReturn(true);
        inspector.inspect("delete from practice where id=? and slug=?");
        verify(reporter)
                .report("delete from practice where id=? and slug=?", Set.of("practice"), TenancyEnforcement.LOG);
    }

    @Test
    void hibernateEntityLoadByPkIsAllowed() {
        // Hibernate-emitted entity load / lazy fetch SQL: SELECT ... FROM table alias
        // WHERE alias.id = ?. Safe because the caller already had the surrogate PK.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect("select r1_0.id,r1_0.archived,r1_0.created_at,r1_0.html_url,r1_0.name "
                + "from repository r1_0 where r1_0.id=?");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void hibernateEntityLoadByPkWithJoinsIsAllowed() {
        // Eager @ManyToOne fetches add LEFT JOINs but the WHERE is still <alias>.id = ?.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect("select r1_0.id,o1_0.id,o1_0.login " + "from repository r1_0 "
                + "left join organization o1_0 on r1_0.organization_id=o1_0.id "
                + "where r1_0.id=?");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void selectWithoutPkPredicateStillEnforced() {
        // Don't let PK-only allowance leak into broader SELECT patterns.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.LOG);
        when(scopedTables.isScoped("pull_request")).thenReturn(true);
        inspector.inspect("select pr1_0.id from pull_request pr1_0 where pr1_0.state=?");
        verify(reporter)
                .report(
                        "select pr1_0.id from pull_request pr1_0 where pr1_0.state=?",
                        Set.of("pull_request"),
                        TenancyEnforcement.LOG);
    }

    @Test
    void hibernateCollectionLoadByParentFkIsAllowed() {
        // Hibernate-emitted @ManyToMany collection initialisation queries the join table
        // filtered by the parent's FK column. The join table inherits scope from the
        // parent's PK, which the caller already had.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect("select i1_0.label_id,i1_1.id,i1_1.body,i1_1.title " + "from issue_label i1_0 "
                + "join issue i1_1 on i1_0.issue_id=i1_1.id "
                + "where i1_0.label_id=?");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void hibernateOneToManyByParentFkIsAllowed() {
        // @OneToMany owned by the child via FK: SELECT c.* FROM comment c WHERE c.issue_id = ?
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect("select c1_0.id,c1_0.body from issue_comment c1_0 where c1_0.issue_id=?");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void hibernateBatchedCollectionLoadByParentFkIsAllowed() {
        // The query that took production down: Repository.labels carries @BatchSize, so Hibernate
        // fills several repositories' label collections in one round trip and writes the parent FK
        // predicate as "= any(?)" rather than "= ?". Same safety argument as the unbatched form —
        // the caller already held every key in the array — so it stays on the fast path.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect("select l1_0.repository_id,l1_0.id,l1_0.color,l1_0.name "
                + "from label l1_0 where l1_0.repository_id=any(?)");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void batchedParentFkDisjoinedWithOrStillEnforced() {
        // The batched form earns the fast path only while the array predicate is what pins the
        // result set. A disjunction widens it past those keys exactly as it does for "= ?".
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.LOG);
        when(scopedTables.isScoped("label")).thenReturn(true);
        String sql = "select l1_0.id from label l1_0 where l1_0.repository_id=any(?) or l1_0.is_public=true";
        inspector.inspect(sql);
        verify(reporter).report(sql, Set.of("label"), TenancyEnforcement.LOG);
    }

    @Test
    void pkPredicateDisjoinedWithOrStillEnforced() {
        // The PK-only fast path tolerates extra CONJUNCTIVE predicates, but a DISJUNCTION broadens the
        // result set past the single keyed row — "WHERE id = ? OR is_public = true" returns every public
        // row regardless of the key. That must NOT bypass enforcement.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.LOG);
        when(scopedTables.isScoped("practice")).thenReturn(true);
        String sql = "select p1_0.id from practice p1_0 where p1_0.id=? or p1_0.is_public=true";
        inspector.inspect(sql);
        verify(reporter).report(sql, Set.of("practice"), TenancyEnforcement.LOG);
    }

    @Test
    void pkLoadWithCorrelationFreeExistsSubqueryIsAllowed() {
        // Policy: an outer load still pinned to a single keyed row by <alias>.id = ?, with a
        // correlation-free EXISTS guard, stays on the PK-only fast path — the projected result set is
        // still the one keyed row, so it is tenancy-safe by construction.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect("select p1_0.id from practice p1_0 where p1_0.id=? and exists (select 1 from observation o)");
        verifyNoInteractions(reporter, scopedTables);
    }

    @Test
    void malformedSingleQuotedTableIdentifierStillEnforced() {
        // A malformed single open-quote identifier ("practice with no closing quote) must not slip past the
        // scoped lookup: unqualify strips every stray quote char so the lowercase name still resolves to the
        // scoped set (degrade toward OVER-reporting, never under-reporting).
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.LOG);
        when(scopedTables.isScoped("practice")).thenReturn(true);
        String sql = "select x from \"practice where y=?";
        inspector.inspect(sql);
        verify(reporter).report(sql, Set.of("practice"), TenancyEnforcement.LOG);
    }

    @Test
    void hibernateSingleTableInheritanceLoadIsAllowed() {
        // Single-table inheritance: SELECT … FROM issue alias WHERE alias.id = ? AND
        // alias.<discriminator> = '<subtype>'. The PK predicate still pins the result set.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        inspector.inspect("select pr1_0.id,pr1_0.body,pr1_0.title " + "from issue pr1_0 "
                + "where pr1_0.id=? and pr1_0.issue_type='PullRequest'");
        verifyNoInteractions(reporter, scopedTables);
    }

    // composite primary keys: the mapping metamodel decides, never a naming convention

    @Test
    void updateByCompleteCompositeKeyIsAllowed() {
        // The production shape: an @EmbeddedId association entity carrying a payload column.
        // Collaborator sync aborted on this until the key came from the metamodel.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        when(scopedTables.primaryKeyColumns("repository_collaborator")).thenReturn(Set.of("repository_id", "user_id"));
        inspector.inspect("update repository_collaborator set permission=? where repository_id=? and \"user_id\"=?");
        inspector.inspect("delete from repository_collaborator where repository_id=? and user_id=?");
        verifyNoInteractions(reporter);
    }

    @Test
    void compositeKeyAllowanceToleratesTheOptimisticLock() {
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        when(scopedTables.primaryKeyColumns("repository_collaborator")).thenReturn(Set.of("repository_id", "user_id"));
        inspector.inspect(
                "update repository_collaborator set permission=? where repository_id=? and user_id=? and version=?");
        verifyNoInteractions(reporter);
    }

    @Test
    void aPartialOrPaddedKeyIsNotACompleteKey() {
        // Fewer columns than the key reaches more than one row; more columns is a hand-written
        // query the standard check must still see.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.LOG);
        when(scopedTables.isScoped("repository_collaborator")).thenReturn(true);
        when(scopedTables.primaryKeyColumns("repository_collaborator")).thenReturn(Set.of("repository_id", "user_id"));
        for (String sql : List.of(
                "update repository_collaborator set permission=? where repository_id=? and permission=?",
                "delete from repository_collaborator where repository_id=? and user_id=? and permission=?",
                "delete from repository_collaborator where repository_id=? or user_id=?",
                "delete from repository_collaborator where repository_id=? and repository_id=?",
                "delete from repository_collaborator where repository_id=? and user_id in (select id from \"user\")")) {
            inspector.inspect(sql);
            verify(reporter).report(sql, Set.of("repository_collaborator"), TenancyEnforcement.LOG);
        }
    }

    @Test
    void aTableTheMetamodelDoesNotKnowFailsClosed() {
        // Before ApplicationReady the key map is empty. Unsure must mean reported, not allowed.
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.LOG);
        when(scopedTables.isScoped("repository_collaborator")).thenReturn(true);
        when(scopedTables.primaryKeyColumns("repository_collaborator")).thenReturn(Set.of());
        String sql = "update repository_collaborator set permission=? where repository_id=? and user_id=?";
        inspector.inspect(sql);
        verify(reporter).report(sql, Set.of("repository_collaborator"), TenancyEnforcement.LOG);
    }

    @Test
    void aSingleColumnKeyIsCoveredTooWhenTheMetamodelNamesIt() {
        WorkspaceStatementInspector inspector = newInspector(TenancyEnforcement.THROW);
        when(scopedTables.primaryKeyColumns("bad_practice")).thenReturn(Set.of("id"));
        inspector.inspect("update bad_practice set title=? where id=?");
        verifyNoInteractions(reporter);
    }

    // helper: Mockito.any() shorthand
    private static <T> T any() {
        return ArgumentMatchers.any();
    }
}
