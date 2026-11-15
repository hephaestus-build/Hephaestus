package de.tum.cit.aet.hephaestus.integration.core.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class ItemIndexServiceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ItemIndexService writer;

    @Autowired
    private ItemRepository items;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldKeepTheItemLockUntilTheCallerCommitsAndRefuseAnotherWorkspace() {
        long workspace = workspace();
        long foreignWorkspace = workspace();
        var item = writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.repository"), 1L, null, null));
        assertThatThrownBy(() -> writer.lock(workspace, item.itemId()))
                .isInstanceOf(IllegalTransactionStateException.class);
        transactions.executeWithoutResult(status -> {
            writer.lock(workspace, item.itemId());
            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement(
                            "SELECT id FROM item WHERE workspace_id=? AND id=? FOR UPDATE NOWAIT")) {
                statement.setLong(1, workspace);
                statement.setLong(2, item.itemId());
                assertThatThrownBy(statement::executeQuery)
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                failure -> assertThat(failure.getSQLState()).isEqualTo("55P03"));
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
        transactions.executeWithoutResult(status -> writer.lock(workspace, item.itemId()));
        assertThatThrownBy(
                        () -> transactions.executeWithoutResult(status -> writer.lock(foreignWorkspace, item.itemId())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReadItemsOnlyInTheirWorkspaceAndReportARemovedOne() {
        long workspace = workspace();
        long foreign = workspace();
        var kept = writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.issue"), 900000101L, null, null));
        var removed = writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.issue"), 900000102L, null, null));
        var other = writer.upsert(new ItemRegistration(foreign, new ItemKind("scm.issue"), 900000103L, null, null));
        assertThat(writer.remove(workspace, removed.itemId(), Instant.now())).isTrue();

        assertThat(writer.findAll(workspace, List.of(kept.itemId(), removed.itemId(), other.itemId())))
                .containsOnlyKeys(kept.itemId(), removed.itemId())
                .containsEntry(kept.itemId(), kept);
        assertThat(writer.find(workspace, kept.itemId())).contains(new ItemIndexService.IndexedItem(kept, false));
        assertThat(writer.find(workspace, removed.itemId())).contains(new ItemIndexService.IndexedItem(removed, true));
        assertThat(writer.find(workspace, other.itemId())).isEmpty();
    }

    @Test
    void shouldResolveSourceIdentityWithinItsWorkspaceAndKindAfterRemoval() {
        long workspace = workspace();
        long foreign = workspace();
        long sourceId = 900000001L;
        var pull =
                writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.pull_request"), sourceId, null, null));
        var issue = writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.issue"), sourceId, null, null));
        var other =
                writer.upsert(new ItemRegistration(foreign, new ItemKind("scm.pull_request"), sourceId, null, null));
        assertThat(pull.itemId()).isNotEqualTo(sourceId);
        assertThat(writer.remove(workspace, pull.itemId(), Instant.now())).isTrue();
        assertThat(writer.findSource(workspace, "scm.pull_request", sourceId)).contains(pull);
        assertThat(writer.findSource(workspace, "scm.issue", sourceId)).contains(issue);
        assertThat(writer.findSource(foreign, "scm.pull_request", sourceId)).contains(other);
        assertThat(writer.findSource(workspace, "scm.pull_request", sourceId + 1))
                .isEmpty();
    }

    @Test
    void shouldKeepIdentityAcrossRemoveAndRestore() {
        long workspace = workspace();
        var registration = new ItemRegistration(workspace, new ItemKind("scm.repository"), 1L, null, null);
        var item = writer.upsert(registration);
        assertThat(writer.upsert(registration)).isEqualTo(item);
        assertThat(writer.remove(workspace, item.itemId(), Instant.now())).isTrue();
        assertThat(writer.remove(workspace, item.itemId(), Instant.now())).isFalse();
        assertThat(writer.restore(workspace, item.itemId())).isTrue();
        assertThat(writer.restore(workspace, item.itemId())).isFalse();
        assertThat(items.findByIdAndWorkspaceId(item.itemId(), workspace))
                .get()
                .extracting(Item::getRemovedAt)
                .isNull();
    }

    @Test
    void shouldRejectParentFromAnotherWorkspace() {
        long workspace = workspace();
        var parent = writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.repository"), 1L, null, null));
        long otherWorkspace = workspace();
        assertThatThrownBy(() -> writer.upsert(new ItemRegistration(
                        otherWorkspace, new ItemKind("scm.file"), null, parent.itemId(), "src/A.java")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldGiveRenamedPathItsOwnIdentity() {
        long workspace = workspace();
        var parent = writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.repository"), 1L, null, null));
        var oldPath = writer.upsert(
                new ItemRegistration(workspace, new ItemKind("scm.file"), null, parent.itemId(), "old.java"));
        var newPath = writer.upsert(
                new ItemRegistration(workspace, new ItemKind("scm.file"), null, parent.itemId(), "new.java"));
        assertThat(newPath.itemId()).isNotEqualTo(oldPath.itemId());
    }

    @Test
    void shouldKeepPathIdentityWithinItsParent() {
        long workspace = workspace();
        var repository = writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.repository"), 1L, null, null));
        var registration =
                new ItemRegistration(workspace, new ItemKind("scm.file"), null, repository.itemId(), "src/A.java");
        var first = writer.upsert(registration);
        assertThat(writer.upsert(registration)).isEqualTo(first);
        assertThat(items.findByIdAndWorkspaceId(first.itemId(), workspace))
                .get()
                .satisfies(row -> {
                    assertThat(row.getParentId()).isEqualTo(repository.itemId());
                    assertThat(row.getPath()).isEqualTo("src/A.java");
                });
    }

    @Test
    void shouldRejectReparentingAnExistingSourceItem() {
        long workspace = workspace();
        var first = writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.repository"), 1L, null, null));
        var second = writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.repository"), 2L, null, null));
        var original =
                writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.issue"), 3L, first.itemId(), null));

        assertThatThrownBy(() -> writer.upsert(
                        new ItemRegistration(workspace, new ItemKind("scm.issue"), 3L, second.itemId(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(items.findByIdAndWorkspaceId(original.itemId(), workspace))
                .get()
                .extracting(Item::getParentId)
                .isEqualTo(first.itemId());
    }

    @Test
    void shouldRefuseForeignRemoveAndRestoreWithoutChangingTheItem() {
        long workspace = workspace();
        long foreign = workspace();
        var item = writer.upsert(new ItemRegistration(workspace, new ItemKind("scm.repository"), 1L, null, null));
        assertThat(writer.remove(foreign, item.itemId(), Instant.now())).isFalse();
        assertThat(items.findByIdAndWorkspaceId(item.itemId(), workspace))
                .get()
                .extracting(Item::getRemovedAt)
                .isNull();
        Instant removedAt = Instant.parse("2026-10-14T10:00:00Z");
        assertThat(writer.remove(workspace, item.itemId(), removedAt)).isTrue();
        assertThat(writer.restore(foreign, item.itemId())).isFalse();
        assertThat(items.findByIdAndWorkspaceId(item.itemId(), workspace))
                .get()
                .extracting(Item::getRemovedAt)
                .isEqualTo(removedAt);
    }

    private long workspace() {
        return workspaces
                .save(WorkspaceTestFixtures.activeWorkspace("item-" + UUID.randomUUID()))
                .getId();
    }
}
