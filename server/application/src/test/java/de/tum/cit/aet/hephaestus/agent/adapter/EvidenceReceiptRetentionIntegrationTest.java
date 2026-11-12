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
    void shouldDeleteOnlyTheReceiptsWhoseJobRetentionDeleted() {
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
        jdbc.update("DELETE FROM review_job WHERE id = ?", deleted);

        // No server needs to confirm: each removes the folders of a job without its row in its next pass.
        receipts.retireReceiptsOfDeletedJobs(500);

        assertThat(states(liveReady, liveErased, ready, capturing, erased, erasing))
                .containsExactlyInAnyOrderEntriesOf(Map.of(liveReady, "READY:people", liveErased, "ERASED:cleared"));
    }

    @Test
    void shouldRetainPhysicalCleanupUntilADeletedJobsSandboxIsRemoved() {
        Workspace workspace = createWorkspace(
                "pending-sandbox-receipt",
                "Pending sandbox receipt",
                "pending-sandbox-org",
                AccountType.ORG,
                persistUser("pending-sandbox-owner"));
        UUID job = job(workspace).getId();
        UUID sandbox = UUID.randomUUID();
        UUID pending = receipt(
                workspace, job, "READY", "{\"sandboxId\":\"" + sandbox + "\",\"identities\":[{\"subject\":\"42\"}]}");
        jdbc.update("DELETE FROM review_job WHERE id=?", job);

        receipts.retireReceiptsOfDeletedJobs(500);
        assertThat(jdbc.queryForMap(
                        "SELECT state,payload::text AS payload FROM person_evidence_copy WHERE id=?", pending))
                .containsEntry("state", "ERASED");
        assertThat(jdbc.queryForObject(
                        "SELECT payload::text FROM person_evidence_copy WHERE id=?", String.class, pending))
                .contains(sandbox.toString())
                .doesNotContain("identities");
        jdbc.update("UPDATE person_evidence_copy SET payload=payload-'sandboxId' WHERE id=?", pending);
        receipts.retireReceiptsOfDeletedJobs(500);
        assertThat(states(pending)).isEmpty();
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
                "INSERT INTO person_evidence_copy(id,workspace_id,job_id,state,payload)"
                        + " VALUES (?,?,?,?,CAST(? AS jsonb))",
                id,
                workspace.getId(),
                jobId,
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
