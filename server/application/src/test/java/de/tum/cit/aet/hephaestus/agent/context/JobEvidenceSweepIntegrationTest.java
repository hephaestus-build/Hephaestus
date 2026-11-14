package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The sweeps that run on their own: the one at startup and the reconcile pass. Neither has a transaction around it, so
 * these tests call them as the server does and read the folders and rows they leave.
 */
@DisplayName("Evidence folder sweeps over PostgreSQL Integration")
class JobEvidenceSweepIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JobEvidenceMaintenanceConfiguration maintenance;

    @Autowired
    private EvidenceReconciler reconciler;

    @Autowired
    private FabricLayout layout;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = workspaces.save(TestEntities.activeWorkspace("evidence-sweep-ws-" + UUID.randomUUID()));
    }

    @Test
    void shouldRemoveEveryFolderThatNoRunningAttemptReadsWhenTheServerStarts() throws IOException {
        var sweep = new Folders();

        maintenance.cleanAfterRestart();

        sweep.assertOnlyTheRunningAttemptKeepsItsFolder();
    }

    @Test
    void shouldRemoveEveryFolderThatNoRunningAttemptReadsInAReconcilePass() throws IOException {
        var sweep = new Folders();

        reconciler.reconcile();

        sweep.assertOnlyTheRunningAttemptKeepsItsFolder();
    }

    /** One job in each state that the sweep tells apart, each with the folders that it can leave behind. */
    private final class Folders {
        private final Path failed;
        private final ReviewJob admitted;
        private final Path composed;
        private final Path running;
        private final Path superseded;

        Folders() throws IOException {
            var failedJob = job(ReviewJobStatus.FAILED);
            failed = folder(failedJob, failedJob.getRunId());

            // An admitted review that went on to its composition: the folder of its measuring run stays behind.
            var admittedJob = job(ReviewJobStatus.COMPLETED);
            admittedJob.setAdmissionAttempt(admittedJob.getAttempt());
            admittedJob.setAdmissionDigest("a".repeat(64));
            admittedJob.setCapture(mapper.readTree("""
                    {"manifest": {"itemKind": "test.item", "files": [{"path": "context/a.json"}],
                     "citableTexts": [{"path": "src/A.java"}]}}
                    """));
            admitted = jobs.saveAndFlush(admittedJob);
            composed = folder(admitted, UUID.randomUUID());

            var runningJob = job(ReviewJobStatus.RUNNING);
            running = folder(runningJob, runningJob.getRunId());
            superseded = folder(runningJob, UUID.randomUUID());
        }

        void assertOnlyTheRunningAttemptKeepsItsFolder() {
            assertThat(failed).doesNotExist();
            assertThat(composed).doesNotExist();
            assertThat(superseded).doesNotExist();
            assertThat(running.resolve("context/source.txt")).exists();
            // The ended job's inventory went with its folder; the rest of its capture stays.
            var manifest = Objects.requireNonNull(jobs.findById(admitted.getId()).orElseThrow().getCapture())
                    .path("manifest");
            assertThat(manifest.path("itemKind").asString()).isEqualTo("test.item");
            assertThat(manifest.has("files")).isFalse();
            assertThat(manifest.has("citableTexts")).isFalse();
        }
    }

    private ReviewJob job(ReviewJobStatus status) {
        var job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        job.setStatus(status);
        job.setConfigSnapshot(mapper.createObjectNode());
        job.setStartedAt(Instant.now());
        if (status != ReviewJobStatus.RUNNING) job.setCompletedAt(Instant.now());
        var saved = jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
        return jobs.findById(saved.getId()).orElseThrow();
    }

    /** An attempt folder of the job, named as the capture names it, for the run that took it. */
    private Path folder(ReviewJob job, UUID runId) throws IOException {
        Path root = layout.jobsRoot()
                .resolve(Long.toString(job.getWorkspaceId()))
                .resolve(job.getId().toString())
                .resolve(job.getAttempt() + "-"
                        + ProvenanceDigest.sha256Hex(runId.toString().getBytes(StandardCharsets.UTF_8)));
        Files.createDirectories(root.resolve("context"));
        Files.writeString(root.resolve("context/source.txt"), "captured");
        return root;
    }
}
