package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.ReviewJobFixtures;
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

    @Autowired
    private EvidenceReceipts custody;

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

    /**
     * A read that checks many producing jobs reads their receipts in one batch. Each job's custody must be what its own
     * read decides, for every way a receipt can vouch or refuse.
     */
    @Test
    void shouldDecideEachJobsCustodyInABatchAsItsOwnReadDoes() {
        Workspace workspace = createWorkspace(
                "receipt-batch",
                "Receipt batch",
                "receipt-batch-org",
                AccountType.ORG,
                persistUser("receipt-batch-owner"));
        var provider = ensureGitHubProvider();
        UUID complete = job(workspace).getId();
        UUID capturing = job(workspace).getId();
        UUID missing = job(workspace).getId();
        UUID unvouched = job(workspace).getId();
        UUID erased = job(workspace).getId();
        receipt(workspace, complete, "READY", vouching("batch-kept"));
        receipt(workspace, capturing, "CAPTURING", vouching("batch-capturing"));
        receipt(workspace, unvouched, "READY", "{\"identities\":[],\"repositories\":[],\"copiedJobs\":[]}");
        receipt(workspace, erased, "READY", vouching("batch-erased"));
        jdbc.update(
                "INSERT INTO identity_restriction(id,provider_id,provider_user_key,team_key,kind) VALUES (?,?,?,'','ERASED')",
                UUID.randomUUID(),
                provider.getId(),
                "batch-erased");
        var all = List.of(complete, capturing, missing, unvouched, erased);

        var batch = custody.custodyOfEach(workspace.getId(), all);

        assertThat(batch).containsOnlyKeys(all);
        all.forEach(job -> assertThat(batch.get(job))
                .as("custody of job %s", job)
                .isEqualTo(custody.custody(workspace.getId(), List.of(job))));
        assertThat(batch)
                .hasEntrySatisfying(
                        complete, held -> assertThat(held.complete()).isTrue());
        assertThat(batch)
                .hasEntrySatisfying(
                        capturing, held -> assertThat(held.refused()).isTrue());
        assertThat(batch)
                .hasEntrySatisfying(missing, held -> assertThat(held.complete()).isFalse());
        assertThat(batch)
                .hasEntrySatisfying(
                        unvouched, held -> assertThat(held.complete()).isFalse());
        assertThat(batch)
                .hasEntrySatisfying(erased, held -> assertThat(held.refused()).isTrue());
    }

    private static String vouching(String subject) {
        return "{\"dependencies\":\"COMPLETE\",\"repositories\":[],\"copiedJobs\":[],\"identities\":"
                + "[{\"providerType\":\"GITHUB\",\"providerOrigin\":\"https://github.com\",\"subject\":\""
                + subject + "\"}]}";
    }

    @Test
    void shouldFinishAnErasureWhoseReceiptRetentionAlreadyDeleted() {
        var frozen = new PersonDataSelection(List.of(new PersonDataSelection.RowKey(Map.of(
                "job_id", UUID.randomUUID().toString(),
                "copy_id", UUID.randomUUID().toString(),
                "workspace_id", "973210"))));

        assertThatCode(() -> receipts.contributors()
                        .getFirst()
                        .prepareErasure(frozen, new PersonScope(null, List.of(), List.of())))
                .doesNotThrowAnyException();
    }

    private ReviewJob job(Workspace workspace) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        ReviewJobFixtures.withStatus(job, ReviewJobStatus.COMPLETED);
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
