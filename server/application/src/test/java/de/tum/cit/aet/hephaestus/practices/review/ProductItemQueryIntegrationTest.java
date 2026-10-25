package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadEvents;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemSourceAccess;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ProductItemQueryIntegrationTest extends BaseIntegrationTest {
    /** Stored pull request fields that are not part of its revision; a released mirror row can leave them NULL. */
    private static final List<String> OUTSIDE_THE_REVISION =
            List.of("additions", "deletions", "commits", "changed_files", "base_ref_name", "head_ref_name");

    @Autowired
    ScmItemSourceAccess sourceAccess;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewLookupData lookup;

    @Autowired
    ProductItemQuery items;

    @Autowired
    ProductReferenceQuery references;

    @Autowired
    ItemIndexService index;

    @Autowired
    SqlReadMeasurement reads;

    @Autowired
    IssueRepository issues;

    @Autowired
    RepositoryToMonitorRepository monitors;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void shouldReadOnlyTheTargetWhenUnrelatedScmSlackAndOutlineItemsExist() {
        var scenario = data.scenario();
        var target = lookup.work(scenario.workspace(), IdentityProviderType.GITHUB, true);
        String url = Objects.requireNonNull(Objects.requireNonNull(references
                        .items(target.workspaceId(), List.of(target.itemId()))
                        .get(target.itemId()))
                .url());
        var before = reads.measure(() -> items.atUrl(target.workspaceId(), url));
        assertThat(before.value()).contains(target);
        for (var kind : List.of(ScmItemEvents.PULL_REQUEST, ThreadEvents.THREAD, DocumentEvents.DOCUMENT)) {
            for (int n = 0; n < 12; n++) {
                index.upsert(new ItemRegistration(
                        target.workspaceId(),
                        kind,
                        ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                        null,
                        null));
            }
        }
        var after = reads.measure(() -> items.atUrl(target.workspaceId(), url));
        assertThat(after.value()).contains(target);
        assertThat(after.cost()).isEqualTo(before.cost());
        assertThat(after.sql()).noneMatch(sql -> sql.contains("slack_thread") || sql.contains("outline_document"));
        assertThat(items.atUrl(target.workspaceId(), "https://github.com/unknown/project/pull/5"))
                .isEmpty();
        assertThat(items.atUrl(data.scenario().workspace().getId(), url)).isEmpty();
    }

    @Test
    void shouldRejectTheAddressWhenWorkIsRemovedDeletedOrNoLongerMonitored() {
        var scenario = data.scenario();
        var target = lookup.work(scenario.workspace(), IdentityProviderType.GITLAB, false);
        var reference = Objects.requireNonNull(
                references.items(target.workspaceId(), List.of(target.itemId())).get(target.itemId()));
        String url = Objects.requireNonNull(reference.url());
        assertThat(items.atUrl(target.workspaceId(), url)).contains(target);
        index.remove(target.workspaceId(), target.itemId(), Instant.now());
        assertThat(items.atUrl(target.workspaceId(), url)).isEmpty();
        index.restore(target.workspaceId(), target.itemId());
        var work = issues.findById(Objects.requireNonNull(target.sourceId())).orElseThrow();
        work.setDeletedAt(Instant.now());
        issues.saveAndFlush(work);
        assertThat(items.atUrl(target.workspaceId(), url)).isEmpty();
        work.setDeletedAt(null);
        issues.saveAndFlush(work);
        var monitor = monitors.findByWorkspaceIdAndNameWithOwner(
                        target.workspaceId(), Objects.requireNonNull(reference.container()))
                .orElseThrow();
        monitors.delete(monitor);
        assertThat(items.atUrl(target.workspaceId(), url)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKnowTheStoredRevisionWhenOnlyFieldsOutsideItAreMissing(IdentityProviderType provider) {
        var target = lookup.work(data.scenario().workspace(), provider, true);
        long source = Objects.requireNonNull(target.sourceId());
        String recorded = items.current(target).orElseThrow().version().value();
        assertThat(recorded)
                .isEqualTo(ScmVersion.pullRequest("a".repeat(40), "b".repeat(40), "Reviewed work", "Description")
                        .value());
        assertThat(items.currentWorkVersion(target.workspaceId(), target.itemId()))
                .isEqualTo(new ProductItemQuery.CurrentWorkVersion.Known(recorded));

        jdbc.update("UPDATE issue SET " + String.join("=NULL,", OUTSIDE_THE_REVISION) + "=NULL WHERE id=?", source);
        assertThat(items.currentWorkVersion(target.workspaceId(), target.itemId()))
                .isEqualTo(new ProductItemQuery.CurrentWorkVersion.Known(recorded));
        assertThat(sourceAccess.permits(target, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isTrue();

        jdbc.update("UPDATE issue SET body=? WHERE id=?", "Description that now explains why", source);
        String edited = ScmVersion.pullRequest(
                        "a".repeat(40), "b".repeat(40), "Reviewed work", "Description that now explains why")
                .value();
        assertThat(edited).isNotEqualTo(recorded);
        assertThat(items.currentWorkVersion(target.workspaceId(), target.itemId()))
                .isEqualTo(new ProductItemQuery.CurrentWorkVersion.Known(edited));
        assertStillMissing(source, OUTSIDE_THE_REVISION);
    }

    @Test
    void shouldLeaveAPullRequestWithoutAStoredRevisionUnknownAndItsReadUsable() {
        var target = lookup.work(data.scenario().workspace(), IdentityProviderType.GITHUB, true);
        long source = Objects.requireNonNull(target.sourceId());
        var legacy = new ArrayList<>(OUTSIDE_THE_REVISION);
        legacy.addAll(List.of("base_ref_oid", "head_ref_oid", "is_draft", "is_merged"));
        jdbc.update("UPDATE issue SET " + String.join("=NULL,", legacy) + "=NULL WHERE id=?", source);

        var read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        var version = read.execute(status -> {
            var unknown = items.currentWorkVersion(target.workspaceId(), target.itemId());
            assertThat(items.find(target.workspaceId(), target.itemId())).contains(target);
            assertThat(status.isRollbackOnly()).isFalse();
            return unknown;
        });
        assertThat(version).isEqualTo(ProductItemQuery.CurrentWorkVersion.Unknown.INSTANCE);
        assertStillMissing(source, legacy);
    }

    @Test
    void shouldKeepEverySourceAccessFenceWhenOnlyFieldsOutsideTheRevisionAreMissing() {
        var target = lookup.work(data.scenario().workspace(), IdentityProviderType.GITLAB, true);
        long source = Objects.requireNonNull(target.sourceId());
        var repository = items.find(target.workspaceId(), Objects.requireNonNull(target.parentId()))
                .orElseThrow();
        var monitor = monitors.findByWorkspaceIdAndNameWithOwner(
                        target.workspaceId(),
                        Objects.requireNonNull(Objects.requireNonNull(references
                                        .items(target.workspaceId(), List.of(target.itemId()))
                                        .get(target.itemId()))
                                .container()))
                .orElseThrow();
        jdbc.update("UPDATE issue SET " + String.join("=NULL,", OUTSIDE_THE_REVISION) + "=NULL WHERE id=?", source);
        var purpose = SourceUsePurpose.CONVERSATIONAL_MENTORING;
        assertThat(sourceAccess.permits(target, purpose)).isTrue();

        long foreignWorkspace = data.scenario().workspace().getId();
        var foreignRepository = index.upsert(new ItemRegistration(
                foreignWorkspace, ScmItemEvents.REPOSITORY, Objects.requireNonNull(repository.sourceId()), null, null));
        var foreign = index.upsert(new ItemRegistration(
                foreignWorkspace, ScmItemEvents.PULL_REQUEST, source, foreignRepository.itemId(), null));
        assertThat(sourceAccess.permits(foreign, purpose)).isFalse();
        var forged = index.upsert(
                new ItemRegistration(target.workspaceId(), ScmItemEvents.ISSUE, source, repository.itemId(), null));
        assertThat(sourceAccess.permits(forged, purpose)).isFalse();

        index.remove(target.workspaceId(), target.itemId(), Instant.now());
        assertThat(sourceAccess.permits(target, purpose)).isFalse();
        index.restore(target.workspaceId(), target.itemId());
        jdbc.update("UPDATE issue SET deleted_at=now() WHERE id=?", source);
        assertThat(sourceAccess.permits(target, purpose)).isFalse();
        jdbc.update("UPDATE issue SET deleted_at=NULL WHERE id=?", source);
        assertThat(sourceAccess.permits(target, purpose)).isTrue();
        monitors.delete(monitor);
        assertThat(sourceAccess.permits(target, purpose)).isFalse();
        assertStillMissing(source, OUTSIDE_THE_REVISION);
    }

    @ParameterizedTest
    @CsvSource({"GITHUB,true", "GITHUB,false", "GITLAB,true", "GITLAB,false"})
    void shouldLeaveWorkWithoutAStoredRepositoryUnknownAndItsReadUsable(
            IdentityProviderType provider, boolean pullRequest) {
        var target = lookup.work(data.scenario().workspace(), provider, pullRequest);
        long source = Objects.requireNonNull(target.sourceId());
        var known = items.currentWorkVersion(target.workspaceId(), target.itemId());
        assertThat(known).isInstanceOf(ProductItemQuery.CurrentWorkVersion.Known.class);
        long repository = Objects.requireNonNull(
                jdbc.queryForObject("SELECT repository_id FROM issue WHERE id=?", Long.class, source));

        jdbc.update("UPDATE issue SET repository_id=NULL WHERE id=?", source);
        var read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        var version = read.execute(status -> {
            var unknown = items.currentWorkVersion(target.workspaceId(), target.itemId());
            assertThat(items.find(target.workspaceId(), target.itemId())).contains(target);
            assertThat(status.isRollbackOnly()).isFalse();
            return unknown;
        });
        assertThat(version).isEqualTo(ProductItemQuery.CurrentWorkVersion.Unknown.INSTANCE);
        assertThat(sourceAccess.permits(target, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isFalse();
        assertStillMissing(source, List.of("repository_id"));

        jdbc.update("UPDATE issue SET repository_id=? WHERE id=?", repository, source);
        assertThat(items.currentWorkVersion(target.workspaceId(), target.itemId()))
                .isEqualTo(known);
    }

    /** No read fills a missing field: each stays NULL in the stored row. */
    private void assertStillMissing(long source, List<String> columns) {
        var row = jdbc.queryForMap("SELECT " + String.join(",", columns) + " FROM issue WHERE id=?", source);
        assertThat(row).hasSize(columns.size());
        assertThat(row.values()).containsOnlyNulls();
    }
}
