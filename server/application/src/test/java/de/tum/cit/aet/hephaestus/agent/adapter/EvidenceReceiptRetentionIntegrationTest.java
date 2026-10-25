package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import tools.jackson.databind.ObjectMapper;

/** The retention of evidence-copy receipts once their review job is deleted, on real PostgreSQL. */
@Tag("integration")
class EvidenceReceiptRetentionIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String PEOPLE = "{\"identities\":[{\"providerType\":\"GITHUB\",\"subject\":\"42\"}]}";

    @Autowired
    private EvidenceFolderPersonDataCatalog receipts;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldRetireOnlyTheReceiptsWhoseJobRetentionDeleted() {
        Workspace workspace = createWorkspace(
                "receipt-retention",
                "Receipt retention",
                "receipt-retention-org",
                AccountType.ORG,
                persistUser("receipt-retention-owner"));
        UUID live = job(workspace).getId();
        UUID deleted = job(workspace).getId();
        UUID liveReady = receipt(workspace, live, "READY", PEOPLE);
        UUID liveErased = receipt(workspace, live, "ERASED", "{}");
        UUID ready = receipt(workspace, deleted, "READY", PEOPLE);
        UUID capturing = receipt(workspace, deleted, "CAPTURING", PEOPLE);
        UUID erased = receipt(workspace, deleted, "ERASED", "{}");
        UUID erasing = receipt(workspace, deleted, "ERASED", PEOPLE);
        UUID requested = receipt(workspace, deleted, "ERASE_REQUESTED", PEOPLE);
        jdbc.update("DELETE FROM review_job WHERE id = ?", deleted);

        receipts.retireReceiptsOfDeletedJobs(500);

        assertThat(states(liveReady, liveErased, ready, capturing, erased, erasing, requested))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        liveReady, "READY:people",
                        liveErased, "ERASED:cleared",
                        ready, "PURGE_REQUESTED:people",
                        capturing, "PURGE_REQUESTED:people",
                        requested, "ERASE_REQUESTED:people"));
    }

    @Test
    void shouldFinishAnErasureWhoseReceiptRetentionAlreadyDeleted() {
        var frozen = new PersonDataSelection(List.of(new PersonDataSelection.RowKey(Map.of(
                "job_id", UUID.randomUUID().toString(),
                "copy_id", UUID.randomUUID().toString(),
                "workspace_id", "973210"))));

        assertThatCode(() -> receipts.contributors().getFirst().prepareErasure(frozen))
                .doesNotThrowAnyException();
    }

    private ReviewJob job(Workspace workspace) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        job.setStatus(ReviewJobStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        job.setConfigSnapshot(new ObjectMapper().createObjectNode());
        job.prePersist();
        return jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
    }

    private UUID receipt(Workspace workspace, UUID jobId, String state, String payload) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO person_evidence_copy(id,workspace_id,job_id,store_id,state,payload)"
                        + " VALUES (?,?,?,?,?,CAST(? AS jsonb))",
                id,
                workspace.getId(),
                jobId,
                UUID.randomUUID(),
                state,
                payload);
        return id;
    }

    private Map<UUID, String> states(UUID... ids) {
        return jdbc
                .query(
                        "SELECT id, state || ':' || CASE WHEN payload = '{}'::jsonb THEN 'cleared' ELSE 'people' END AS fact"
                                + " FROM person_evidence_copy WHERE id = ANY(?)",
                        (row, n) -> Map.entry(row.getObject("id", UUID.class), row.getString("fact")),
                        new SqlArrayValue("uuid", (Object[]) ids))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
