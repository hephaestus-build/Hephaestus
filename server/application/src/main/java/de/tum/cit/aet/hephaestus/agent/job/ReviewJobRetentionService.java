package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.BoundedBatchPass;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Retention removes bulky terminal payloads before unreferenced rows; {@link AgentProperties#payloadRetention} bounds payload lifetime. */
@ConditionalOnServerRole
@Component
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("Cross-workspace retention sweep; caller is @WorkspaceAgnostic maintenance job")
public class ReviewJobRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobRetentionService.class);

    private final ReviewJobRepository jobRepository;
    private final ReviewCompositionBasisRepository basisRepository;
    private final PracticeStagingStore staging;
    private final EvidenceFolderPersonDataCatalog evidenceCopies;
    private final AgentProperties agentProperties;
    private final Clock clock;
    private final BoundedBatchPass pass;
    private final TransactionTemplate transactionTemplate;
    private final Counter stripped;
    private final Counter deleted;

    public ReviewJobRetentionService(
            ReviewJobRepository jobRepository,
            ReviewCompositionBasisRepository basisRepository,
            PracticeStagingStore staging,
            EvidenceFolderPersonDataCatalog evidenceCopies,
            AgentProperties agentProperties,
            Clock clock,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        this.basisRepository = basisRepository;
        this.staging = staging;
        this.evidenceCopies = evidenceCopies;
        this.agentProperties = agentProperties;
        this.clock = clock;
        this.pass = new BoundedBatchPass(clock);
        this.transactionTemplate = transactionTemplate;
        this.stripped = Counter.builder(AgentMetrics.AGENT_JOB_RETENTION_STRIPPED)
                .description("Terminal review_job rows whose heavy payload columns were stripped to NULL")
                .register(meterRegistry);
        this.deleted = Counter.builder(AgentMetrics.AGENT_JOB_RETENTION_DELETED)
                .description("Terminal review_job rows deleted by the retention sweep")
                .register(meterRegistry);
    }

    /**
     * {@code @SchedulerLock} single-flights this across replicas: concurrent passes cannot go faster (the
     * batches serialize on row-level contention anyway) and only multiply lock/WAL pressure.
     * {@code lockAtMostFor} must stay above the five passes' combined {@link BoundedBatchPass#DEFAULT_BUDGET}.
     */
    @Scheduled(fixedDelay = 6, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    @SchedulerLock(name = "agent-job-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT10S")
    public void runRetention() {
        pass.run(log, "staged-practices", () -> staging.discardUnusable(clock.instant(), pass.batchSize()));
        deleteUnusableCompositionInputs();
        stripPayloads();
        deleteOldRows();
        retireReceiptsOfDeletedJobs();
    }

    private void deleteUnusableCompositionInputs() {
        Instant now = clock.instant();
        BoundedBatchPass.Result result = pass.run(log, "composition-inputs", () -> {
            Integer removed =
                    transactionTemplate.execute(status -> basisRepository.deleteUnusable(now, pass.batchSize()));
            return removed != null ? removed : 0;
        });
        if (result.affected() > 0) {
            log.info("Retention: deleted original composition inputs of {} review(s)", result.affected());
        }
    }

    private void stripPayloads() {
        Instant cutoff = clock.instant().minus(agentProperties.payloadRetention());
        BoundedBatchPass.Result result = pass.run(log, "strip", () -> {
            Integer updated = transactionTemplate.execute(
                    status -> jobRepository.stripTerminalPayloads(cutoff, pass.batchSize()));
            int batchUpdated = updated != null ? updated : 0;
            if (batchUpdated > 0) {
                stripped.increment(batchUpdated);
            }
            return batchUpdated;
        });
        if (result.affected() > 0) {
            log.info(
                    "Retention: stripped payloads from {} terminal review_job row(s) completed before {}",
                    result.affected(),
                    cutoff);
        }
    }

    private void retireReceiptsOfDeletedJobs() {
        BoundedBatchPass.Result result = pass.run(log, "evidence-receipts", () -> {
            Integer changed =
                    transactionTemplate.execute(status -> evidenceCopies.retireReceiptsOfDeletedJobs(pass.batchSize()));
            return changed != null ? changed : 0;
        });
        if (result.affected() > 0) {
            log.info("Retention: retired {} evidence-copy receipt(s) of deleted review jobs", result.affected());
        }
    }

    private void deleteOldRows() {
        Instant cutoff = clock.instant().minus(agentProperties.rowRetention());
        BoundedBatchPass.Result result = pass.run(log, "delete", () -> {
            Integer removed = transactionTemplate.execute(
                    status -> jobRepository.deleteUnreferencedTerminalRowsOlderThan(cutoff, pass.batchSize()));
            int batchDeleted = removed != null ? removed : 0;
            if (batchDeleted > 0) {
                deleted.increment(batchDeleted);
            }
            return batchDeleted;
        });
        if (result.affected() > 0) {
            log.info("Retention: deleted {} terminal review_job row(s) completed before {}", result.affected(), cutoff);
        }
    }
}
