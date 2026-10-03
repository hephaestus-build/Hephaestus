package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.BoundedBatchPass;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
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

/**
 * Bounds {@code agent_job} growth in two passes: TERMINAL rows older than
 * {@link AgentProperties#payloadRetention} keep everything but their bulky {@code container_logs} /
 * {@code output}, and unreferenced rows older than {@link AgentProperties#rowRetention} go entirely.
 *
 * <p>Both run as {@link BoundedBatchPass} batches, so the queue sharing this table is never held up
 * by one unbounded statement.
 */
@ConditionalOnServerRole
@Component
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("Cross-workspace retention sweep; caller is @WorkspaceAgnostic maintenance job")
public class AgentJobRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AgentJobRetentionService.class);

    private final AgentJobRepository jobRepository;
    private final AgentProperties agentProperties;
    private final Clock clock;
    private final BoundedBatchPass pass;
    private final TransactionTemplate transactionTemplate;
    private final Counter stripped;
    private final Counter deleted;

    public AgentJobRetentionService(
            AgentJobRepository jobRepository,
            AgentProperties agentProperties,
            Clock clock,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        this.agentProperties = agentProperties;
        this.clock = clock;
        this.pass = new BoundedBatchPass(clock);
        this.transactionTemplate = transactionTemplate;
        this.stripped = Counter.builder(AgentMetrics.AGENT_JOB_RETENTION_STRIPPED)
                .description("Terminal agent_job rows whose heavy payload columns were stripped to NULL")
                .register(meterRegistry);
        this.deleted = Counter.builder(AgentMetrics.AGENT_JOB_RETENTION_DELETED)
                .description("Terminal agent_job rows deleted by the retention sweep")
                .register(meterRegistry);
    }

    /**
     * {@code @SchedulerLock} single-flights this across replicas: concurrent passes cannot go faster (the
     * batches serialize on row-level contention anyway) and only multiply lock/WAL pressure.
     * {@code lockAtMostFor} must stay above both passes' {@link BoundedBatchPass#DEFAULT_BUDGET}.
     */
    @Scheduled(fixedDelay = 6, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    @SchedulerLock(name = "agent-job-retention", lockAtMostFor = "PT20M", lockAtLeastFor = "PT10S")
    public void runRetention() {
        stripPayloads();
        deleteOldRows();
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
                    "Retention: stripped payloads from {} terminal agent_job row(s) completed before {}",
                    result.affected(),
                    cutoff);
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
            log.info("Retention: deleted {} terminal agent_job row(s) completed before {}", result.affected(), cutoff);
        }
    }
}
